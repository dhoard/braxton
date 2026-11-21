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

import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Cursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Turns painted cells into terminal escape sequences.
 *
 * <p>Only this encoder writes SGR and OSC 8 sequences. Content text never contains escape bytes, so
 * data cannot inject terminal commands. Rows are addressed absolutely, painted in place, and only
 * their unused suffix is cleared, so shrinking rows leave no stale cells or clear-before-paint gap.</p>
 *
 * <p>A frame that follows another one is presented as the cells that differ: {@link #diff} addresses
 * the runs of changed cells inside a row rather than the whole row, and when the changed rows are
 * one region of the screen that scrolled it moves the region with the terminal's own insert and
 * delete line sequences and repaints only the rows that scrolled into view. A one-cell change
 * therefore costs a cursor move, the cell, and the style it needs - not a row.</p>
 */
public final class AnsiEncoder {

    /**
     * Reset every SGR attribute and color.
     */
    public static final String RESET = "\u001B[0m";
    /**
     * Hide the cursor while a frame owns the screen.
     */
    public static final String HIDE_CURSOR = "\u001B[?25l";
    /**
     * Show the cursor again on the way out.
     */
    public static final String SHOW_CURSOR = "\u001B[?25h";
    /**
     * Erase from the cursor to the end of its line.
     */
    public static final String ERASE_LINE_RIGHT = "\u001B[0K";
    /**
     * Erase the whole line the cursor is on, whichever column it is in.
     */
    public static final String ERASE_LINE = "\u001B[2K";
    /**
     * Erase from the cursor to the end of the screen, leaving everything above it alone.
     */
    public static final String ERASE_DISPLAY_BELOW = "\u001B[0J";

    /**
     * Enter the alternate screen.
     */
    public static final String ALT_SCREEN_ON = "\u001B[?1049h";
    /**
     * Leave the alternate screen.
     */
    public static final String ALT_SCREEN_OFF = "\u001B[?1049l";
    /**
     * Enable bracketed paste.
     */
    public static final String ENABLE_BRACKETED_PASTE = "\u001B[?2004h";
    /**
     * Disable bracketed paste.
     */
    public static final String DISABLE_BRACKETED_PASTE = "\u001B[?2004l";
    /**
     * Enable press/release pointer reporting in its SGR form.
     */
    public static final String ENABLE_MOUSE = enableMouse(MouseMode.CLICK);
    /**
     * Undo {@link #ENABLE_MOUSE}.
     */
    public static final String DISABLE_MOUSE = disableMouse(MouseMode.CLICK);
    /**
     * Ask the terminal to report gaining and losing focus.
     */
    public static final String ENABLE_FOCUS_REPORTING = "\u001B[?1004h";
    /**
     * Stop the terminal reporting focus changes.
     */
    public static final String DISABLE_FOCUS_REPORTING = "\u001B[?1004l";
    /**
     * Ask the terminal which enhanced-keyboard flags it has enabled.
     */
    public static final String QUERY_KEYBOARD_ENHANCEMENTS = "\u001B[?u";
    /**
     * Restore the keyboard flags the terminal had before the application pushed its own.
     */
    public static final String POP_KEYBOARD_ENHANCEMENTS = "\u001B[<u";
    /**
     * DECSCUSR with no shape preference: the terminal's default cursor style.
     */
    public static final String RESET_CURSOR_STYLE = "\u001B[0 q";
    /**
     * OSC 112: restore the terminal's configured cursor color.
     */
    public static final String RESET_CURSOR_COLOR = "\u001B]112\u001B\\";
    /**
     * OSC 110: restore the terminal's configured default text color.
     */
    public static final String RESET_FOREGROUND = "\u001B]110\u001B\\";
    /**
     * OSC 111: restore the terminal's configured default background color.
     */
    public static final String RESET_BACKGROUND = "\u001B]111\u001B\\";
    /**
     * XTPUSHTITLE: save the window title this session is about to replace.
     */
    public static final String PUSH_TITLE = "\u001B[22;0t";
    /**
     * XTPOPTITLE: put the window title back the way it was when it was pushed.
     */
    public static final String POP_TITLE = "\u001B[23;0t";
    /**
     * The OSC command that carries program-status reports and their feature detection.
     */
    public static final int PROGRAM_STATUS_OSC = 7501;
    /**
     * OSC 7501 feature detection: ask the terminal whether it keeps program-status records.
     */
    public static final String QUERY_PROGRAM_STATUS = "\u001B]7501;?\u001B\\";

    private static final String OSC8_OPEN = "\u001B]8;;";
    /**
     * The control sequence introducer every non-hyperlink OSC starts with.
     */
    private static final String OSC_PREFIX = "\u001B]";
    /**
     * The string terminator that closes an OSC; CAN and SUB are never written here.
     */
    private static final String STRING_TERMINATOR = "\u001B\\";

    private AnsiEncoder() {}

    /**
     * Ask the terminal to report pointer input at the level a mode names, in SGR form.
     *
     * <p>Two things are requested: how much to report, and how to encode it. The encoding is always
     * SGR ({@code CSI ? 1006}), which is the only form that can name a release's button and can
     * address a column past 223 - the legacy byte form cannot, and mixing the two would make the
     * meaning of a report depend on the cell it came from. A mode of {@link MouseMode#NONE} asks for
     * nothing at all, so a session that does not want pointer input writes no sequence.</p>
     *
     * <p>A terminal that does not implement one of these ignores it and keeps sending what it sent
     * before, which is what makes the request safe to make without negotiating first.</p>
     *
     * @param mode the reporting level to ask for; required
     * @return the escape sequence, empty for {@link MouseMode#NONE}
     */
    public static String enableMouse(MouseMode mode) {
        return switch (mode) {
            case NONE -> "";
            // Presses, releases and wheel gestures.
            case CLICK -> mouseTracking(1000);
            // The same, plus motion while a button is held, which is what a drag needs.
            case CELL_MOTION -> mouseTracking(1002);
            // Every motion, with or without a button held, which is what hover needs.
            case ALL_MOTION -> mouseTracking(1003);
        };
    }

    /**
     * Stop the reporting {@link #enableMouse(MouseMode)} asked for, in the reverse order.
     *
     * @param mode the reporting level the session asked for; required
     * @return the escape sequence, empty for {@link MouseMode#NONE}
     */
    public static String disableMouse(MouseMode mode) {
        return switch (mode) {
            case NONE -> "";
            case CLICK -> stopMouseTracking(1000);
            case CELL_MOTION -> stopMouseTracking(1002);
            case ALL_MOTION -> stopMouseTracking(1003);
        };
    }

    /**
     * One tracking level, followed by the SGR encoding every report is asked to use.
     */
    private static String mouseTracking(int level) {
        return "\u001B[?" + level + "h\u001B[?1006h";
    }

    /**
     * The reverse of {@link #mouseTracking}: the encoding first, then the level it encoded.
     */
    private static String stopMouseTracking(int level) {
        return "\u001B[?1006l\u001B[?" + level + "l";
    }

    /**
     * Push enhanced-keyboard flags onto the terminal's own stack.
     *
     * <p>Push rather than set, so the flags the application asked for last exactly as long as the
     * session does and {@link #POP_KEYBOARD_ENHANCEMENTS} restores whatever the user's shell had.
     * A terminal that does not implement the protocol ignores the sequence and keeps sending legacy
     * input.</p>
     *
     * @param flags the enhancement bits to request, as {@code KeyboardEnhancements.flags()} reports them
     * @return the escape sequence
     */
    public static String pushKeyboardEnhancements(int flags) {
        return "\u001B[>" + flags + "u";
    }

    /**
     * Set the terminal cursor's shape and blink state.
     *
     * <p>DECSCUSR ({@code CSI Ps SP q}) is the cross-vendor control for this; xterm defines the
     * steady and bar forms the VT520 table did not. The blink flag is not a separate control: it
     * selects the odd (blinking) or even (steady) parameter of the same sequence, so a shape and a
     * blink change are one write.</p>
     *
     * @param shape the glyph the cursor takes
     * @param blink whether the terminal blinks it
     * @return the escape sequence
     */
    public static String cursorStyle(Cursor.Shape shape, boolean blink) {
        int parameter = switch (shape) {
            case BLOCK -> blink ? 1 : 2;
            case UNDERLINE -> blink ? 3 : 4;
            case BAR -> blink ? 5 : 6;
        };
        return "\u001B[" + parameter + " q";
    }

    /**
     * Color the terminal cursor.
     *
     * <p>{@code OSC 12} takes an X11 color specification; the {@code rgb:rr/gg/bb} form carries the
     * exact channel values {@link Color} holds, so no rounding happens on the way out.</p>
     *
     * @param color the color to apply
     * @return the escape sequence
     */
    public static String cursorColor(Color color) {
        return defaultColor(12, color);
    }

    private static String hex(int channel) {
        return channel < 0x10 ? "0" + Integer.toHexString(channel) : Integer.toHexString(channel);
    }

    /**
     * The window title.
     *
     * <p>{@code OSC 2} takes one free-form string, so the text is the whole payload: a control
     * character in it would end this sequence and start whatever the string went on to describe.
     * The one caller is the session presentation, which already removed them; they are removed here
     * as well, because this class is the only place that may write a sequence and no path into it may
     * bypass that rule.</p>
     *
     * @param title the title; null or empty clears it
     * @return the escape sequence
     */
    public static String title(String title) {
        return OSC_PREFIX + "2;" + safeText(title) + STRING_TERMINATOR;
    }

    /**
     * A default terminal color.
     *
     * <p>{@code OSC 10}, {@code OSC 11} and {@code OSC 12} set the text, background and cursor
     * colors the terminal uses for everything it draws itself. The {@code rgb:rr/gg/bb} form carries
     * the exact channel values, as for {@link #cursorColor(Color)}.</p>
     *
     * @param code the color command: 10 for text, 11 for background, 12 for the cursor
     * @param color the color to apply; required
     * @return the escape sequence
     */
    public static String defaultColor(int code, Color color) {
        if (color == null) {
            throw new IllegalArgumentException("a default color is required");
        }
        if (code != 10 && code != 11 && code != 12) {
            throw new IllegalArgumentException("default color command must be 10, 11 or 12, was " + code);
        }
        return OSC_PREFIX + code + ";rgb:" + hex(color.red()) + "/" + hex(color.green()) + "/" + hex(color.blue())
                + STRING_TERMINATOR;
    }

    /**
     * A taskbar progress value.
     *
     * <p>{@code OSC 9 ; 4 ; state ; value} is the progress convention xterm, Windows Terminal, ConEmu
     * and several other terminals understand: state 0 removes the indication, 1 is normal progress, 2
     * is an error, 3 is indeterminate and 4 is paused, and the value is a percentage the states that
     * show no fraction carry as zero. A terminal that does not implement it ignores the sequence.</p>
     *
     * @param progress the progress to show; required
     * @return the escape sequence
     */
    public static String progress(TerminalPresentation.Progress progress) {
        if (progress == null) {
            throw new IllegalArgumentException("progress is required");
        }
        int state = switch (progress.state()) {
            case NONE -> 0;
            case NORMAL -> 1;
            case ERROR -> 2;
            case INDETERMINATE -> 3;
            case PAUSED -> 4;
        };
        return OSC_PREFIX + "9;4;" + state + ";" + progress.value() + STRING_TERMINATOR;
    }

    /**
     * A program-status report.
     *
     * <p>{@code OSC 7501} carries a colon-separated list of {@code key=value} pairs; the state is the
     * one required key, the rest are written when the record holds them. Free-text values are base64
     * of UTF-8 so that neither a colon nor a semicolon can appear in them, and a base64 payload cannot
     * end the sequence. The keys are written in one fixed order - state, id, kind, app, title,
     * progress, message - which is the order the protocol's own examples use.</p>
     *
     * <p>Every field is already bounded and grammatically checked by
     * {@link TerminalPresentation.Status}, so the largest report stays inside the protocol's 4096-byte
     * sequence limit: 128 bytes of id, 32 of app, 256 of encoded title and 2732 of encoded message, plus
     * the keys that name them.</p>
     *
     * @param status the record to report; required
     * @return the escape sequence
     */
    public static String programStatus(TerminalPresentation.Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        StringBuilder out = new StringBuilder(OSC_PREFIX).append("7501;state=");
        out.append(status.state().wireName());
        pair(out, "id", status.id());
        if (status.kind() != null) {
            pair(out, "kind", status.kind().wireName());
        }
        pair(out, "app", status.app());
        if (status.title() != null) {
            pair(out, "title", base64(status.title()));
        }
        if (status.progress() != null) {
            pair(out, "progress", Integer.toString(status.progress()));
        }
        if (status.message() != null) {
            pair(out, "msg", base64(status.message()));
        }
        return out.append(STRING_TERMINATOR).toString();
    }

    /**
     * Appends one {@code :key=value} pair, or nothing when the record does not carry that key.
     */
    private static void pair(StringBuilder out, String key, String value) {
        if (value == null) {
            return;
        }
        out.append(':').append(key).append('=').append(value);
    }

    /**
     * The base64 of the UTF-8 bytes of a value the protocol expects as base64.
     */
    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(safeText(text).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Absolute cursor move to a screen cell.
     *
     * @param row the target row, counted from zero
     * @param col the target column, counted from zero
     * @return the escape sequence
     */
    public static String moveCursor(int row, int col) {
        return "\u001B[" + (row + 1) + ";" + (col + 1) + "H";
    }

    /**
     * Move the cursor up {@code rows} lines, keeping its column.
     *
     * <p>A relative move is what an inline frame needs: the live region is anchored where the session
     * started it, and its absolute row moves as the terminal scrolls, so addressing rows absolutely
     * would paint somewhere else after any scroll. A move of zero or less writes nothing.</p>
     *
     * @param rows the number of lines to move up
     * @return the escape sequence, empty when nothing is to be moved
     */
    public static String moveUp(int rows) {
        return rows <= 0 ? "" : "\u001B[" + rows + "A";
    }

    /**
     * Move the cursor down {@code rows} lines, keeping its column.
     *
     * <p>Unlike a newline, this does not scroll: a terminal at the bottom line stays there.</p>
     *
     * @param rows the number of lines to move down
     * @return the escape sequence, empty when nothing is to be moved
     */
    public static String moveDown(int rows) {
        return rows <= 0 ? "" : "\u001B[" + rows + "B";
    }

    /**
     * Move the cursor to a column on its current line.
     *
     * @param col the target column, counted from zero
     * @return the escape sequence, empty for the column the cursor is already in
     */
    public static String moveToColumn(int col) {
        return col <= 0 ? "" : "\u001B[" + (col + 1) + "G";
    }

    /**
     * A complete frame: every row repainted in place.
     *
     * @param screen the screen to encode
     * @param profile the color profile to encode for; {@link ColorProfile#AUTO} is treated as
     *     {@link ColorProfile#TRUECOLOR}, because a frame is never written with an unresolved profile
     * @return the escape sequences for the whole screen
     */
    public static String frame(Screen screen, ColorProfile profile) {
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < screen.rows(); row++) {
            out.append(encodeRow(screen, row, profile));
        }
        return out.toString();
    }

    /**
     * A complete frame with color switched on or off.
     *
     * @param screen the screen to encode
     * @param color whether color SGR parameters are emitted
     * @return the escape sequences for the whole screen
     */
    public static String frame(Screen screen, boolean color) {
        return frame(screen, profileFor(color));
    }

    /**
     * Only the cells that differ, scrolled into place where the change is a scroll.
     *
     * <p>Two things are compared cell by cell rather than row by row. Within a row, only the runs of
     * changed cells are written, each addressed with one absolute cursor move, so a changed cell in
     * an otherwise identical row costs that cell instead of the row. Across rows, when the changed
     * rows are one region whose content moved by the same number of rows, the region is scrolled with
     * the terminal's own insert or delete line sequences inside a temporary scrolling region, and
     * only the rows the scroll exposed are painted.</p>
     *
     * <p>A run of changed cells is widened to whole grapheme clusters: a wide grapheme belongs to the
     * cell it starts in and the cell after it, so half of one is never painted on its own. The empty
     * cells the frame leaves at the end of a row are cleared rather than written as blanks, and a row
     * that paints nothing at all in the frame is cleared with one line erase.</p>
     *
     * <p>A row whose run writes the last column can leave some terminals with a wrap pending, or with
     * a grapheme wider than the grid charged it finished past the right margin, so the row below is
     * repainted in full in the same batch. That is the only reason this method ever repaints a row the
     * frame did not change.</p>
     *
     * @param previous the last presented screen, or null
     * @param next the screen to present
     * @param profile the color profile to encode for
     * @return the escape sequences that take the terminal to {@code next}
     */
    public static String diff(Screen previous, Screen next, ColorProfile profile) {
        if (previous == null || previous.rows() != next.rows() || previous.cols() != next.cols()) {
            return frame(next, profile);
        }
        int rows = next.rows();
        if (rows == 0 || next.cols() == 0) {
            return "";
        }
        int top = 0;
        while (top < rows && rowEquals(previous, next, top)) {
            top++;
        }
        if (top == rows) {
            return "";
        }
        int bottom = rows - 1;
        while (rowEquals(previous, next, bottom)) {
            bottom--;
        }
        StringBuilder out = new StringBuilder();
        Scroll scroll = scroll(previous, next, top, bottom, out);
        boolean repaintNext = false;
        for (int row = 0; row < rows; row++) {
            boolean whole = repaintNext || (scroll != null && scroll.exposed(row));
            repaintNext = false;
            if (!whole && !changed(previous, next, row, scroll)) {
                continue;
            }
            if (whole) {
                out.append(encodeRow(next, row, profile));
                repaintNext = paintedEnd(next, row) >= next.cols();
            } else {
                repaintNext = diffRow(previous, next, row, profile, out);
            }
        }
        return out.toString();
    }

    /**
     * Only the cells that differ, with color switched on or off.
     *
     * @param previous the last presented screen, or null
     * @param next the screen to present
     * @param color whether color SGR parameters are emitted
     * @return the escape sequences that take the terminal to {@code next}
     */
    public static String diff(Screen previous, Screen next, boolean color) {
        return diff(previous, next, profileFor(color));
    }

    /**
     * The profile a legacy color flag names.
     */
    private static ColorProfile profileFor(boolean color) {
        return color ? ColorProfile.TRUECOLOR : ColorProfile.NONE;
    }

    /**
     * Whether the terminal still has to be told about one row of the frame.
     *
     * <p>With a scroll, the region covers every row the frame changed: the rows the shift did not move
     * into place are the ones it exposed, and a row outside the region is one both frames hold the
     * same way.</p>
     */
    private static boolean changed(Screen previous, Screen next, int row, Scroll scroll) {
        if (scroll != null) {
            return scroll.exposed(row);
        }
        return !rowEquals(previous, next, row);
    }

    /**
     * A region of the screen whose content moved by the same number of rows.
     *
     * @param top the region's first row
     * @param bottom the region's last row
     * @param count how many rows the content moved
     * @param up whether the content moved toward the top of the screen
     */
    private record Scroll(int top, int bottom, int count, boolean up) {

        /**
         * Whether the scroll exposed the row, leaving it blank for the frame to paint.
         */
        boolean exposed(int row) {
            return up ? row > bottom - count && row <= bottom : row >= top && row < top + count;
        }
    }

    /**
     * Tries to explain the changed rows as a scroll, and writes the sequences when it fits.
     *
     * <p>The changed rows span one region, so the whole region is the candidate: either its content
     * moved toward the top by {@code count} rows - the frame's row {@code top + index} is the old
     * frame's row {@code top + count + index} - or toward the bottom by the same reading in reverse.
     * A candidate is accepted only when every row the shift covers already matches, which is what
     * makes the rows the encoder then leaves alone provably correct, and only when it covers more rows
     * than it exposes, because the rows it exposes have to be painted anyway and the sequences
     * themselves cost bytes.</p>
     *
     * <p>The region is confined to itself with DECSTBM for the duration of the shift and given back
     * immediately, so nothing written after it scrolls rows the frame did not move. A shift that
     * covers the whole screen needs no margins at all, because that is the region a terminal scrolls
     * by default. The rows a delete leaves behind, and the rows an insert creates, are blanked by the
     * terminal; the caller repaints them.</p>
     *
     * @param previous the last presented screen
     * @param next the screen to present
     * @param top the first row the frame changed
     * @param bottom the last row the frame changed
     * @param out the sequences appended to when a scroll fits
     * @return the scroll that was written, or null when the changed rows are not a scroll
     */
    private static Scroll scroll(Screen previous, Screen next, int top, int bottom, StringBuilder out) {
        int height = bottom - top + 1;
        if (height < 3) {
            return null;
        }
        for (int count = 1; count * 2 < height; count++) {
            if (rowsEqual(previous, next, top + count, top, height - count)) {
                appendScroll(out, top, bottom, count, true, next.rows());
                return new Scroll(top, bottom, count, true);
            }
            if (rowsEqual(previous, next, top, top + count, height - count)) {
                appendScroll(out, top, bottom, count, false, next.rows());
                return new Scroll(top, bottom, count, false);
            }
        }
        return null;
    }

    /**
     * The margins, the cursor move and the one insert or delete line sequence a scroll needs.
     */
    private static void appendScroll(StringBuilder out, int top, int bottom, int count, boolean up, int rows) {
        boolean wholeScreen = top == 0 && bottom == rows - 1;
        if (!wholeScreen) {
            out.append("\u001B[").append(top + 1).append(';').append(bottom + 1).append('r');
        }
        out.append(moveCursor(top, 0));
        out.append("\u001B[").append(count).append(up ? 'M' : 'L');
        if (!wholeScreen) {
            // Given back at once: a scrolling region left behind would move rows the application
            // writes later, and nothing else in this encoder expects one to be set.
            out.append("\u001B[r");
        }
    }

    /**
     * One row's changed cells, as runs addressed where they are, appending to the frame.
     *
     * @return whether a run wrote the row's last column, so the row below has to be repainted
     */
    private static boolean diffRow(Screen previous, Screen next, int row, ColorProfile profile, StringBuilder out) {
        int cols = next.cols();
        int painted = paintedEnd(next, row);
        if (painted == 0) {
            // The frame paints nothing here, so the whole line goes, whatever it held before.
            out.append(moveCursor(row, 0)).append(RESET).append(ERASE_LINE);
            return false;
        }
        // Runs are looked for inside the extent the frame paints. Every cell from there to the end
        // of the row is empty in the frame, so those cells are cleared with one erase however much
        // the previous frame held there - writing them as blanks would cost a byte a cell and leave
        // the row's tail painted rather than cleared.
        int first = firstDifference(previous, next, row, 0, painted);
        while (first < painted) {
            int start = clusterStart(previous, next, row, first);
            int stop = runEnd(previous, next, row, first, painted);
            out.append(moveCursor(row, start));
            appendContent(next, row, start, stop, profile, out);
            if (stop >= cols) {
                return true;
            }
            if (stop >= painted) {
                // Nothing the frame paints is left of this run, so the rest of the line is cleared
                // instead of written as blanks - the suffix clear a whole-row repaint writes. A run
                // that ends here is never blank itself: the frame's last painted cell is in it.
                out.append(moveCursor(row, stop)).append(RESET).append(ERASE_LINE_RIGHT);
                return false;
            }
            first = firstDifference(previous, next, row, stop, painted);
        }
        if (tailDiffers(previous, next, row, painted)) {
            out.append(moveCursor(row, painted)).append(RESET).append(ERASE_LINE_RIGHT);
        }
        return false;
    }

    /**
     * The first column at or after {@code from} and before {@code to} whose cell differs.
     *
     * @return that column, or {@code to} when no cell in the range differs
     */
    private static int firstDifference(Screen previous, Screen next, int row, int from, int to) {
        for (int col = from; col < to; col++) {
            if (differs(previous, next, row, col)) {
                return col;
            }
        }
        return to;
    }

    /**
     * Whether any cell from the frame's painted end to the row's last column differs.
     */
    private static boolean tailDiffers(Screen previous, Screen next, int row, int from) {
        return firstDifference(previous, next, row, from, next.cols()) < next.cols();
    }

    /**
     * The column after the changed graphemes that start at {@code first}, widened to whole ones.
     */
    private static int runEnd(Screen previous, Screen next, int row, int first, int limit) {
        int stop = first + 1;
        while (stop < limit) {
            while (stop < limit && (previous.isContinuation(row, stop) || next.isContinuation(row, stop))) {
                // A grapheme is painted whole or not at all, on either side of the change.
                stop++;
            }
            if (stop >= limit || !differs(previous, next, row, stop)) {
                return stop;
            }
            stop++;
        }
        return stop;
    }

    /**
     * Whether one cell differs between a row of two screens.
     */
    private static boolean differs(Screen previous, Screen next, int row, int col) {
        return previous.isContinuation(row, col) != next.isContinuation(row, col)
                || !previous.text(row, col).equals(next.text(row, col))
                || !previous.style(row, col).equals(next.style(row, col))
                || !Objects.equals(previous.link(row, col), next.link(row, col));
    }

    /**
     * The first cell of the grapheme cluster a change at {@code start} belongs to.
     */
    private static int clusterStart(Screen previous, Screen next, int row, int start) {
        int col = start;
        while (col > 0 && (previous.isContinuation(row, col) || next.isContinuation(row, col))) {
            col--;
        }
        return col;
    }

    private static boolean rowEquals(Screen previous, Screen next, int row) {
        return rowEquals(previous, row, next, row);
    }

    /**
     * Whether one row of one screen holds exactly what another row of another screen holds.
     */
    private static boolean rowEquals(Screen one, int oneRow, Screen other, int otherRow) {
        for (int col = 0; col < one.cols(); col++) {
            if (one.isContinuation(oneRow, col) != other.isContinuation(otherRow, col)
                    || !one.text(oneRow, col).equals(other.text(otherRow, col))
                    || !one.style(oneRow, col).equals(other.style(otherRow, col))
                    || !Objects.equals(one.link(oneRow, col), other.link(otherRow, col))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code count} consecutive rows hold the same cells in two screens.
     */
    private static boolean rowsEqual(Screen one, Screen other, int oneRow, int otherRow, int count) {
        for (int index = 0; index < count; index++) {
            if (!rowEquals(one, oneRow + index, other, otherRow + index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Absolute cursor move, styled content, then a default-style clear of the unused suffix.
     *
     * @param screen the screen holding the row
     * @param row the row to encode
     * @param color whether color SGR parameters are emitted
     * @return the escape sequences for that row
     */
    public static String encodeRow(Screen screen, int row, boolean color) {
        return encodeRow(screen, row, profileFor(color));
    }

    /**
     * One row, encoded for a color profile.
     *
     * @param screen the screen holding the row
     * @param row the row to encode
     * @param profile the color profile to encode for
     * @return the escape sequences for that row
     */
    public static String encodeRow(Screen screen, int row, ColorProfile profile) {
        if (screen.cols() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("\u001B[").append(row + 1).append(";1H");
        int end = paintedEnd(screen, row);
        if (end == 0) {
            out.append(RESET).append(ERASE_LINE);
            return out.toString();
        }
        appendContent(screen, row, 0, end, profile, out);
        if (end < screen.cols()) {
            out.append(moveCursor(row, end)).append(RESET).append(ERASE_LINE_RIGHT);
        }
        return out.toString();
    }

    /**
     * The painted content of one row, without addressing and without clearing.
     *
     * <p>This is the half of {@link #encodeRow(Screen, int, ColorProfile)} that an inline frame can
     * reuse: an inline row is written where the terminal's cursor already is, so the caller supplies
     * the position and the tail it needs, and this produces only the styled cells. Gaps between
     * painted cells stay spaces, because the cells after them must keep the columns the grid gave
     * them.</p>
     *
     * @param screen the screen holding the row
     * @param row the row to encode
     * @param profile the color profile to encode for
     * @return the styled content of the row's painted extent, which may be empty
     */
    public static String rowContent(Screen screen, int row, ColorProfile profile) {
        StringBuilder out = new StringBuilder();
        if (screen.cols() != 0) {
            appendContent(screen, row, 0, paintedEnd(screen, row), profile, out);
        }
        return out.toString();
    }

    /**
     * The styled content of one row's cells from {@code from} up to {@code to}, appended in place.
     *
     * <p>The terminal's style state is tracked inside the run and given back with a reset before the
     * run ends, so every run may be written anywhere on the screen: nothing it leaves behind changes
     * how a later run or the next frame is drawn. A gap - a cell the frame leaves empty - is written
     * as a blank in the default style, because the cells after it must keep the columns the grid gave
     * them.</p>
     */
    private static void appendContent(
            Screen screen, int row, int from, int to, ColorProfile profile, StringBuilder out) {
        Style active = Style.NONE;
        String activeLink = null;
        boolean styled = false;
        for (int col = from; col < to; col++) {
            if (screen.isContinuation(row, col)) {
                continue;
            }
            String text = screen.text(row, col);
            Style style = screen.style(row, col);
            String link = screen.link(row, col);
            if (screen.isEmptyCell(row, col)) {
                // Gaps must overwrite old content and occupy their columns so later cells stay
                // where the grid placed them.
                if (activeLink != null) {
                    out.append(OSC8_OPEN).append(STRING_TERMINATOR);
                    activeLink = null;
                }
                if (styled) {
                    out.append(RESET);
                    styled = false;
                    active = Style.NONE;
                }
                out.append(' ');
                continue;
            }
            if (!style.equals(active) || (link == null) != (activeLink == null)) {
                if (styled || activeLink != null) {
                    if (activeLink != null) {
                        out.append(OSC8_OPEN).append(STRING_TERMINATOR);
                        activeLink = null;
                    }
                    out.append(RESET);
                    styled = false;
                }
                active = style;
                if (!active.isPlain()) {
                    appendSgr(out, active, profile);
                    styled = true;
                }
            }
            if (link != null && !link.equals(activeLink)) {
                if (activeLink != null) {
                    out.append(OSC8_OPEN).append(STRING_TERMINATOR);
                }
                out.append(OSC8_OPEN).append(safeLink(link)).append(STRING_TERMINATOR);
                activeLink = link;
            }
            out.append(text);
        }
        if (activeLink != null) {
            out.append(OSC8_OPEN).append(STRING_TERMINATOR);
        }
        if (styled) {
            out.append(RESET);
        }
    }

    /**
     * The exclusive extent of what a row paints, including the second column of a wide grapheme.
     *
     * <p>An inline frame needs it for two decisions: how many rows the live region occupies, and
     * whether the last of them writes into the final column - a row that reaches it can leave the
     * terminal's cursor pending a wrap, so the row below it is painted too.</p>
     *
     * @param screen the screen holding the row
     * @param row the row to measure
     * @return the column after the last painted cell, zero for a row that paints nothing
     */
    public static int paintedEnd(Screen screen, int row) {
        for (int col = screen.cols() - 1; col >= 0; col--) {
            if (screen.isContinuation(row, col)) {
                return col + 1;
            }
            if (!screen.isEmptyCell(row, col)) {
                return col + 1;
            }
        }
        return 0;
    }

    private static String safeLink(String link) {
        return safeText(link);
    }

    /**
     * The text of a payload with every control character removed.
     */
    private static String safeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c)) {
                safe.append(c);
            }
        }
        return safe.toString();
    }

    /**
     * SGR parameter sequence for a style (without the reset terminator).
     *
     * @param style the style to encode
     * @return the sequence, or an empty string for a plain style
     */
    public static String sgr(Style style) {
        return sgr(style, ColorProfile.TRUECOLOR);
    }

    /**
     * SGR parameter sequence for a style under a color profile.
     *
     * <p>Attributes are emitted whatever the profile says: a session without color still has bold,
     * underline and the rest. Only the two color parameters change, and they change shape with the
     * profile - {@code 38;2;r;g;b}, {@code 38;5;index}, or one of the classic 16-color codes - so a
     * narrower profile loses precision, never decoration. A color profile of {@link
     * ColorProfile#NONE} emits no color parameters at all.</p>
     *
     * @param style the style to encode
     * @param profile the color profile to encode for
     * @return the sequence, or an empty string for a plain style
     */
    public static String sgr(Style style, ColorProfile profile) {
        StringBuilder out = new StringBuilder();
        appendSgr(out, style, profile);
        return out.toString();
    }

    /**
     * Appends a style's SGR parameters to a frame already under construction.
     *
     * <p>Written straight into the caller's builder rather than assembled from a parameter list,
     * because a frame changes style once per painted run: a temporary list, its boxed integers and a
     * joined string per run is the whole of what a diff used to allocate. A style under a profile
     * that leaves nothing to say - a color and a 16-color-free session - appends nothing at all,
     * which is the same answer {@link #sgr(Style, ColorProfile)} gives.</p>
     *
     * @param out the frame under construction
     * @param style the style to encode
     * @param profile the color profile to encode for
     */
    private static void appendSgr(StringBuilder out, Style style, ColorProfile profile) {
        int start = out.length();
        out.append("\u001B[");
        int params = 0;
        params = attribute(out, params, style.bold(), "1");
        params = attribute(out, params, style.italic(), "3");
        params = attribute(out, params, style.underline(), "4");
        params = attribute(out, params, style.invert(), "7");
        params = attribute(out, params, style.hidden(), "8");
        params = attribute(out, params, style.strikethrough(), "9");
        ColorProfile resolved = profile == null || profile == ColorProfile.AUTO ? ColorProfile.TRUECOLOR : profile;
        if (resolved.color()) {
            params = color(out, params, style.foreground(), resolved, true);
            params = color(out, params, style.background(), resolved, false);
        }
        if (params == 0) {
            out.setLength(start);
            return;
        }
        out.append('m');
    }

    /**
     * Appends one attribute parameter when the style has it, and answers how many are written.
     */
    private static int attribute(StringBuilder out, int written, boolean present, String parameter) {
        if (!present) {
            return written;
        }
        if (written > 0) {
            out.append(';');
        }
        out.append(parameter);
        return written + 1;
    }

    /**
     * Appends one color's parameters.
     *
     * <p>Only called for a profile that emits color, so the three branches are the three forms a
     * terminal accepts: 24-bit, a 256-color palette index, and one of the classic 16-color codes.
     * The profile is compared rather than switched so the caller's guard is the only place that has to
     * know {@link ColorProfile#NONE} exists.</p>
     */
    private static int color(StringBuilder out, int written, Color color, ColorProfile profile, boolean foreground) {
        if (color == null) {
            return written;
        }
        if (written > 0) {
            out.append(';');
        }
        if (profile == ColorProfile.ANSI16) {
            int index = Colors.nearestAnsi16(color);
            int code = index < 8 ? (foreground ? 30 : 40) + index : (foreground ? 90 : 100) + index - 8;
            out.append(code);
        } else if (profile == ColorProfile.ANSI256) {
            out.append(foreground ? "38;5;" : "48;5;").append(Colors.nearestAnsi256(color));
        } else {
            out.append(foreground ? "38;2;" : "48;2;")
                    .append(color.red())
                    .append(';')
                    .append(color.green())
                    .append(';')
                    .append(color.blue());
        }
        return written + 1;
    }
}
