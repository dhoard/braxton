/*
 * Copyright (c) 2026-present Douglas Hoard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.dhoard.braxton.internal.render;

import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A live region on a terminal's normal screen, painted where the session found the cursor.
 *
 * <p>Everything here is relative. The region begins at whatever row the session started on, and that
 * row moves as the terminal scrolls, so the renderer never addresses a row absolutely: it keeps one
 * number - how far the terminal's cursor sits below the region's first row - and moves by that much.
 * That is the whole reason an inline session cannot erase anything it does not own: the only clearing
 * it emits is {@link AnsiEncoder#ERASE_DISPLAY_BELOW}, written at the region's own first row, which
 * reaches the region and the empty screen below it and nothing above.</p>
 *
 * <p>A frame is the region repainted in place. The renderer returns to the region's first row, erases
 * forward from there, and writes each painted row followed by an erase-to-end-of-line, so a view that
 * got shorter leaves its old rows cleared instead of showing the tail of the previous frame. Rows are
 * separated by {@code CR LF} rather than by absolute addressing, and the caret is placed last, by
 * moving back up inside the region and then across.</p>
 *
 * <p>Durable output - the lines an application prints above its live view - is the one operation that
 * moves the region: the renderer erases the region, writes the lines where it began, and leaves the
 * cursor on the line below them, which is the region's new first row. The caller repaints the frame
 * immediately after, in the same write, so a line never appears with a blank gap where the live view
 * was.</p>
 *
 * <p>The renderer does not present an idle frame: given the screen it last painted and an identical
 * one, with the caret where it already is, it returns the empty string.</p>
 */
public final class InlineRenderer {

    private final CursorPresenter cursor;

    /**
     * Rows the region currently occupies; zero before the first frame and after a durable print.
     */
    private int height;

    /**
     * How far below the region's first row the terminal's cursor currently is.
     */
    private int row;

    /**
     * The column the terminal's cursor is in.
     */
    private int column;

    /**
     * Creates a renderer whose caret state is shared with the session.
     *
     * @param cursor the session's caret presenter; required
     */
    public InlineRenderer(CursorPresenter cursor) {
        this.cursor = Objects.requireNonNull(cursor, "cursor");
    }

    /**
     * The rows the region currently occupies.
     *
     * @return the row count, zero when nothing is painted
     */
    public int height() {
        return height;
    }

    /**
     * Forgets the region without writing anything.
     *
     * <p>Used when the screen the region was painted on stops being the one the session is drawing on,
     * or when somebody else took the terminal: the next frame starts from wherever the cursor is then,
     * which is the only thing that is still true.</p>
     */
    public void reset() {
        height = 0;
        row = 0;
        column = 0;
    }

    /**
     * The sequences that repaint the region with {@code next}.
     *
     * @param previous the screen the region shows, or null to force a full repaint
     * @param next the screen to present; required
     * @param profile the color profile to encode for
     * @param caret the cursor the layout resolved, or empty when nothing is to be shown
     * @return the escape sequences to write, or an empty string when the region already shows
     *     {@code next} with the caret in place
     */
    public String frame(Screen previous, Screen next, ColorProfile profile, Optional<Cursor> caret) {
        Objects.requireNonNull(next, "next");
        Optional<Cursor> wanted = nativeCaret(caret);
        int painted = paintedHeight(next);
        if (wanted.isPresent()) {
            // A caret the layout placed below the last painted row is part of the region: the frame
            // has to be tall enough to hold the cell it sits on.
            painted = Math.max(painted, Math.min(wanted.get().row() + 1, next.rows()));
        }
        if (painted > 0 && painted < next.rows() && AnsiEncoder.paintedEnd(next, painted - 1) >= next.cols()) {
            // A row that writes the last column leaves some terminals with a wrap pending, so the row
            // below is painted as well: a spilled cell lands inside the region instead of below it.
            painted++;
        }
        int parked = painted == 0 ? 0 : painted - 1;
        int targetRow = wanted.map(value -> Math.min(value.row(), parked)).orElse(parked);
        int targetColumn = wanted.map(Cursor::column).orElse(0);
        String properties = cursor.style(caret);
        String visibility = cursor.visibility(caret);
        boolean same = previous != null && painted == height && next.equals(previous);
        if (same && targetRow == row && targetColumn == column && properties.isEmpty() && visibility.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!same) {
            out.append('\r').append(AnsiEncoder.moveUp(row));
            out.append(AnsiEncoder.ERASE_DISPLAY_BELOW);
            for (int index = 0; index < painted; index++) {
                if (index > 0) {
                    out.append("\r\n");
                }
                out.append(AnsiEncoder.rowContent(next, index, profile)).append(AnsiEncoder.ERASE_LINE_RIGHT);
            }
            if (painted > 0) {
                out.append('\r');
            }
            height = painted;
            row = parked;
            column = 0;
        }
        out.append(moveTo(targetRow, targetColumn));
        out.append(properties).append(visibility);
        row = targetRow;
        column = targetColumn;
        return out.toString();
    }

    /**
     * The sequences that erase the region and write {@code text} where it began.
     *
     * <p>The lines become part of the terminal's own content: they scroll with everything else and the
     * session never touches them again. The cursor ends on the line below the last one written, which
     * is where the region's next frame starts, so the caller must present a frame in the same write to
     * leave no gap. Empty text prints nothing at all.</p>
     *
     * @param text the text to print; control characters other than tab and newline are dropped
     * @return the escape sequences to write, or an empty string when there is nothing to print
     */
    public String print(String text) {
        List<String> lines = DurableText.lines(text);
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append('\r').append(AnsiEncoder.moveUp(row));
        if (height > 0) {
            out.append(AnsiEncoder.ERASE_DISPLAY_BELOW);
        }
        out.append(AnsiEncoder.RESET);
        for (String line : lines) {
            out.append(line).append("\r\n");
        }
        height = 0;
        row = 0;
        column = 0;
        return out.toString();
    }

    /**
     * The sequences that leave the region: the cursor on the line below it, where a shell prompts.
     *
     * <p>Called on the way out, before the cursor is shown again. The final frame stays on screen - it
     * is the session's result - and the prompt that follows it starts on its own line. A session that
     * painted nothing writes nothing.</p>
     *
     * @return the escape sequences to write, or an empty string when the region is empty
     */
    public String finish() {
        if (height == 0) {
            return "";
        }
        String out = "\r" + AnsiEncoder.moveDown(height - 1 - row) + "\r\n";
        height = 0;
        row = 0;
        column = 0;
        return out;
    }

    /**
     * The move from the cursor's current cell to another inside the region.
     *
     * <p>Vertical first, because a vertical move keeps the column; then the column, which is written
     * as a carriage return when it is the first one, since that needs no sequence at all.</p>
     */
    private String moveTo(int targetRow, int targetColumn) {
        StringBuilder out = new StringBuilder();
        if (targetRow > row) {
            out.append(AnsiEncoder.moveDown(targetRow - row));
        } else if (targetRow < row) {
            out.append(AnsiEncoder.moveUp(row - targetRow));
        }
        if (targetColumn != column) {
            out.append(targetColumn == 0 ? "\r" : AnsiEncoder.moveToColumn(targetColumn));
        }
        return out.toString();
    }

    /**
     * The caret the terminal shows itself, or empty when it stays hidden.
     */
    private static Optional<Cursor> nativeCaret(Optional<Cursor> caret) {
        if (caret == null) {
            return Optional.empty();
        }
        return caret.filter(value -> value.visible() && value.strategy() == Cursor.Strategy.NATIVE);
    }

    /**
     * How many rows from the top of {@code screen} hold anything at all.
     */
    private static int paintedHeight(Screen screen) {
        for (int row = screen.rows() - 1; row >= 0; row--) {
            if (AnsiEncoder.paintedEnd(screen, row) > 0) {
                return row + 1;
            }
        }
        return 0;
    }
}
