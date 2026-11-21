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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.Objects;
import java.util.Optional;

/**
 * The terminal cursor's presentation state, and only the sequences that change it.
 *
 * <p>The runtime owns one presenter for the session. Each frame hands it the cursor the layout
 * resolved, and it answers with the sequences that take the terminal from what this session last
 * applied to what the frame asks for - the move, the shape, the blink state, the color and the
 * visibility, each only when it differs. A frame that does not move the caret writes nothing for it,
 * and an idle application keeps emitting no traffic at all.</p>
 *
 * <p>Painting content is not idle, though: a row the frame repaints ends by leaving the terminal's
 * cursor inside that row, so a cell this presenter wrote earlier is no longer where the terminal is.
 * {@link #contentPainted()} says so, and the next frame re-emits the move it would otherwise have
 * skipped. That is why a caret that did not move can still be re-stated after a frame that changed
 * the line it sits on.</p>
 *
 * <p>The presenter starts where the session opens: the runtime hides the cursor before the first
 * frame, so nothing is shown and no shape or color has been applied. {@link #reset()} returns the
 * terminal to the properties this session changed and nothing more: a session that never touched
 * the cursor writes no reset at all. Because the terminal's own preferences cannot be read here,
 * the resets are the DEC defaults (a default shape, the configured color) rather than a restore of
 * a style the user may have customized.</p>
 *
 * <p>Only a {@link Cursor.Strategy#NATIVE native} cursor is presented this way. A virtual cursor is
 * already painted into the frame's cells, so the terminal cursor stays hidden rather than sitting
 * on a cell the application draws itself.</p>
 */
public final class CursorPresenter {

    private boolean color;

    private boolean shown;
    private boolean shaped;
    private Cursor.Shape shape;
    private Cursor.Mode mode;
    private Color appliedColor;
    private int row = -1;
    private int column = -1;

    /**
     * Creates a presenter for a session whose frames start with the cursor hidden.
     *
     * @param color whether color output is enabled; a session that emits no color emits no cursor
     *     color either
     */
    public CursorPresenter(boolean color) {
        this.color = color;
    }

    /**
     * Changes whether a cursor color is presented.
     *
     * <p>A session that loses color keeps the caret's shape and blink state but gives up its color;
     * the next frame writes the terminal's own color back, because the color this session applied
     * would otherwise outlive it. Turning color on again lets the frame's color apply as usual.</p>
     *
     * @param value whether a cursor color may be emitted
     */
    public void colorEnabled(boolean value) {
        this.color = value;
    }

    /**
     * Records that the frame wrote content, so the cell this presenter last moved to is stale.
     *
     * <p>Every repainted row addresses the terminal and leaves its cursor at that row, which is
     * usually not the caret's cell. Nothing else in the applied state changes.</p>
     */
    public void contentPainted() {
        row = -1;
        column = -1;
    }

    /**
     * The sequences that present {@code cursor} after the frame's content.
     *
     * @param cursor the cursor the layout resolved, or empty when nothing is to be shown
     * @return the escape sequences to write, or an empty string when the terminal is already in the
     *     state the frame asks for
     */
    public String frame(Optional<Cursor> cursor) {
        Cursor wanted = nativeCursor(cursor);
        if (wanted == null) {
            return hide();
        }
        StringBuilder out = new StringBuilder(style(cursor));
        if (!shown || wanted.row() != row || wanted.column() != column) {
            out.append(AnsiEncoder.moveCursor(wanted.row(), wanted.column()));
            row = wanted.row();
            column = wanted.column();
        }
        out.append(visibility(cursor));
        return out.toString();
    }

    /**
     * The sequences that change the caret's shape, blink state and color, and move it nowhere.
     *
     * <p>An inline frame cannot use {@link #frame(Optional)}: it addresses cells relative to its own
     * live region, so the move is the frame's business. Splitting the two keeps one ledger for what
     * the session has applied - a caret whose shape or color the frame changed is still exactly what
     * the exit path has to give back - while each renderer decides where the caret goes.</p>
     *
     * @param cursor the cursor the layout resolved, or empty when nothing is to be shown
     * @return the escape sequences that apply the caret's properties, or an empty string when the
     *     terminal already has them
     */
    public String style(Optional<Cursor> cursor) {
        Cursor wanted = nativeCursor(cursor);
        if (wanted == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!shaped || wanted.shape() != shape || blinks(wanted.mode()) != blinks(mode)) {
            out.append(AnsiEncoder.cursorStyle(wanted.shape(), blinks(wanted.mode())));
            shaped = true;
            shape = wanted.shape();
            mode = wanted.mode();
        }
        Color wantedColor = color ? wanted.color() : null;
        if (!Objects.equals(wantedColor, appliedColor)) {
            // The reset is the only way back to the terminal's own color: OSC 12 has no "default".
            out.append(wantedColor == null ? AnsiEncoder.RESET_CURSOR_COLOR : AnsiEncoder.cursorColor(wantedColor));
            appliedColor = wantedColor;
        }
        return out.toString();
    }

    /**
     * The sequence that shows or hides the caret, and moves it nowhere.
     *
     * <p>Written after the content and after any move, so a terminal that renders an update
     * atomically never shows the caret flying to its cell. Idempotent: a caret that is already in the
     * state the frame asks for writes nothing.</p>
     *
     * @param cursor the cursor the layout resolved, or empty when nothing is to be shown
     * @return the escape sequence, or an empty string when the visibility is unchanged
     */
    public String visibility(Optional<Cursor> cursor) {
        if (nativeCursor(cursor) == null) {
            return hide();
        }
        if (shown) {
            return "";
        }
        shown = true;
        return AnsiEncoder.SHOW_CURSOR;
    }

    /**
     * The native caret a frame asks for, or null when the terminal's own cursor stays hidden.
     */
    private static Cursor nativeCursor(Optional<Cursor> cursor) {
        if (cursor == null) {
            return null;
        }
        return cursor.filter(c -> c.visible() && c.strategy() == Cursor.Strategy.NATIVE)
                .orElse(null);
    }

    /**
     * Hides the cursor when it is shown; the position is forgotten, so showing it moves again.
     */
    private String hide() {
        if (!shown) {
            row = -1;
            column = -1;
            return "";
        }
        shown = false;
        row = -1;
        column = -1;
        return AnsiEncoder.HIDE_CURSOR;
    }

    /**
     * The sequences that undo every cursor property this session changed.
     *
     * <p>Called from the exit path, before the cursor is shown again for the shell. The presenter
     * forgets its applied state either way, so a reset cannot be written twice however many exit
     * paths run.</p>
     *
     * @return the escape sequences to write, or an empty string when this session changed nothing
     */
    public String reset() {
        StringBuilder out = new StringBuilder();
        if (shaped) {
            out.append(AnsiEncoder.RESET_CURSOR_STYLE);
            shaped = false;
            shape = null;
            mode = null;
        }
        if (appliedColor != null) {
            out.append(AnsiEncoder.RESET_CURSOR_COLOR);
            appliedColor = null;
        }
        shown = false;
        row = -1;
        column = -1;
        return out.toString();
    }

    private static boolean blinks(Cursor.Mode mode) {
        return mode == Cursor.Mode.BLINK;
    }
}
