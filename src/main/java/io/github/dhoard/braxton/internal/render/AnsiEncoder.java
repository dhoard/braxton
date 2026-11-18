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
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.Cursor;
import io.github.dhoard.braxton.view.TerminalPresentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Turns painted cells into terminal escape sequences.
 *
 * <p>Only this encoder writes SGR and OSC 8 sequences. Content text never contains escape bytes, so
 * data cannot inject terminal commands. Rows are addressed absolutely, painted in place, and only
 * their unused suffix is cleared, so shrinking rows leave no stale cells or clear-before-paint gap.</p>
 */
public final class AnsiEncoder {

    /** Reset every SGR attribute and colour. */
    public static final String RESET = "\u001B[0m";
    /** Hide the cursor while a frame owns the screen. */
    public static final String HIDE_CURSOR = "\u001B[?25l";
    /** Show the cursor again on the way out. */
    public static final String SHOW_CURSOR = "\u001B[?25h";
    /** Erase from the cursor to the end of its line. */
    public static final String ERASE_LINE_RIGHT = "\u001B[0K";
    /** Erase the whole line the cursor is on, whichever column it is in. */
    public static final String ERASE_LINE = "\u001B[2K";
    /** Erase from the cursor to the end of the screen, leaving everything above it alone. */
    public static final String ERASE_DISPLAY_BELOW = "\u001B[0J";

    /** Enter the alternate screen. */
    public static final String ALT_SCREEN_ON = "\u001B[?1049h";
    /** Leave the alternate screen. */
    public static final String ALT_SCREEN_OFF = "\u001B[?1049l";
    /** Enable bracketed paste. */
    public static final String ENABLE_BRACKETED_PASTE = "\u001B[?2004h";
    /** Disable bracketed paste. */
    public static final String DISABLE_BRACKETED_PASTE = "\u001B[?2004l";
    /** Enable press/release pointer reporting in its SGR form. */
    public static final String ENABLE_MOUSE = enableMouse(MouseMode.CLICK);
    /** Undo {@link #ENABLE_MOUSE}. */
    public static final String DISABLE_MOUSE = disableMouse(MouseMode.CLICK);
    /** Ask the terminal to report gaining and losing focus. */
    public static final String ENABLE_FOCUS_REPORTING = "\u001B[?1004h";
    /** Stop the terminal reporting focus changes. */
    public static final String DISABLE_FOCUS_REPORTING = "\u001B[?1004l";
    /** Ask the terminal which enhanced-keyboard flags it has enabled. */
    public static final String QUERY_KEYBOARD_ENHANCEMENTS = "\u001B[?u";
    /** Restore the keyboard flags the terminal had before the application pushed its own. */
    public static final String POP_KEYBOARD_ENHANCEMENTS = "\u001B[<u";
    /** DECSCUSR with no shape preference: the terminal's default cursor style. */
    public static final String RESET_CURSOR_STYLE = "\u001B[0 q";
    /** OSC 112: restore the terminal's configured cursor colour. */
    public static final String RESET_CURSOR_COLOR = "\u001B]112\u001B\\";
    /** OSC 110: restore the terminal's configured default text colour. */
    public static final String RESET_FOREGROUND = "\u001B]110\u001B\\";
    /** OSC 111: restore the terminal's configured default background colour. */
    public static final String RESET_BACKGROUND = "\u001B]111\u001B\\";
    /** XTPUSHTITLE: save the window title this session is about to replace. */
    public static final String PUSH_TITLE = "\u001B[22;0t";
    /** XTPOPTITLE: put the window title back the way it was when it was pushed. */
    public static final String POP_TITLE = "\u001B[23;0t";
    /** The OSC command that carries program-status reports and their feature detection. */
    public static final int PROGRAM_STATUS_OSC = 7501;
    /** OSC 7501 feature detection: ask the terminal whether it keeps program-status records. */
    public static final String QUERY_PROGRAM_STATUS = "\u001B]7501;?\u001B\\";

    private static final String OSC8_OPEN = "\u001B]8;;";
    /** The control sequence introducer every non-hyperlink OSC starts with. */
    private static final String OSC_PREFIX = "\u001B]";
    /** The string terminator that closes an OSC; CAN and SUB are never written here. */
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

    /** One tracking level, followed by the SGR encoding every report is asked to use. */
    private static String mouseTracking(int level) {
        return "\u001B[?" + level + "h\u001B[?1006h";
    }

    /** The reverse of {@link #mouseTracking}: the encoding first, then the level it encoded. */
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
     * Colour the terminal cursor.
     *
     * <p>{@code OSC 12} takes an X11 colour specification; the {@code rgb:rr/gg/bb} form carries the
     * exact channel values {@link Color} holds, so no rounding happens on the way out.</p>
     *
     * @param color the colour to apply
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
     * A default terminal colour.
     *
     * <p>{@code OSC 10}, {@code OSC 11} and {@code OSC 12} set the text, background and cursor
     * colours the terminal uses for everything it draws itself. The {@code rgb:rr/gg/bb} form carries
     * the exact channel values, as for {@link #cursorColor(Color)}.</p>
     *
     * @param code the colour command: 10 for text, 11 for background, 12 for the cursor
     * @param color the colour to apply; required
     * @return the escape sequence
     */
    public static String defaultColor(int code, Color color) {
        if (color == null) {
            throw new IllegalArgumentException("a default colour is required");
        }
        if (code != 10 && code != 11 && code != 12) {
            throw new IllegalArgumentException("default colour command must be 10, 11 or 12, was " + code);
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

    /** Appends one {@code :key=value} pair, or nothing when the record does not carry that key. */
    private static void pair(StringBuilder out, String key, String value) {
        if (value == null) {
            return;
        }
        out.append(':').append(key).append('=').append(value);
    }

    /** The base64 of the UTF-8 bytes of a value the protocol expects as base64. */
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
     * @param profile the colour profile to encode for; {@link ColorProfile#AUTO} is treated as
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
     * A complete frame with colour switched on or off.
     *
     * @param screen the screen to encode
     * @param color whether colour SGR parameters are emitted
     * @return the escape sequences for the whole screen
     */
    public static String frame(Screen screen, boolean color) {
        return frame(screen, profileFor(color));
    }

    /**
     * Only the rows whose cells differ, or a full frame when there is no previous screen.
     *
     * @param previous the last presented screen, or null
     * @param next the screen to present
     * @param profile the colour profile to encode for
     * @return the escape sequences that take the terminal to {@code next}
     */
    public static String diff(Screen previous, Screen next, ColorProfile profile) {
        if (previous == null || previous.rows() != next.rows() || previous.cols() != next.cols()) {
            return frame(next, profile);
        }
        StringBuilder out = new StringBuilder();
        boolean repaintNext = false;
        for (int row = 0; row < next.rows(); row++) {
            if (repaintNext || !rowEquals(previous, next, row)) {
                out.append(encodeRow(next, row, profile));
                // A row that writes its last column leaves the terminal at the right edge, and a
                // terminal that renders some cluster wider than the grid charged it finishes the row
                // past that edge: the tail wraps, and it wraps onto the start of the row below. That
                // row's own cells did not change, so a diff would leave the wrapped cells there for
                // good. Repainting it clears whatever the row above spilled into it, in the same
                // batch, so nothing is ever painted outside the row that owns it.
                repaintNext = lastPaintedColumn(next, row) + 1 >= next.cols();
            } else {
                repaintNext = false;
            }
        }
        return out.toString();
    }

    /**
     * Only the rows whose cells differ, with colour switched on or off.
     *
     * @param previous the last presented screen, or null
     * @param next the screen to present
     * @param color whether colour SGR parameters are emitted
     * @return the escape sequences that take the terminal to {@code next}
     */
    public static String diff(Screen previous, Screen next, boolean color) {
        return diff(previous, next, profileFor(color));
    }

    /** The profile a legacy colour flag names. */
    private static ColorProfile profileFor(boolean color) {
        return color ? ColorProfile.TRUECOLOR : ColorProfile.NONE;
    }

    private static boolean rowEquals(Screen previous, Screen next, int row) {
        for (int col = 0; col < next.cols(); col++) {
            if (!previous.cell(row, col).equals(next.cell(row, col))
                    || previous.isContinuation(row, col) != next.isContinuation(row, col)) {
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
     * @param color whether colour SGR parameters are emitted
     * @return the escape sequences for that row
     */
    public static String encodeRow(Screen screen, int row, boolean color) {
        return encodeRow(screen, row, profileFor(color));
    }

    /**
     * One row, encoded for a colour profile.
     *
     * @param screen the screen holding the row
     * @param row the row to encode
     * @param profile the colour profile to encode for
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
        out.append(content(screen, row, end, profile));
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
     * @param profile the colour profile to encode for
     * @return the styled content of the row's painted extent, which may be empty
     */
    public static String rowContent(Screen screen, int row, ColorProfile profile) {
        return screen.cols() == 0 ? "" : content(screen, row, paintedEnd(screen, row), profile);
    }

    /** The styled content of a row up to {@code end}, the exclusive painted extent. */
    private static String content(Screen screen, int row, int end, ColorProfile profile) {
        if (end == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int last = end - 1;
        if (screen.isContinuation(row, last)) {
            last--;
        }
        Style active = Style.NONE;
        String activeLink = null;
        boolean styled = false;
        for (int col = 0; col <= last; col++) {
            if (screen.isContinuation(row, col)) {
                continue;
            }
            Cell cell = screen.cell(row, col);
            if (cell.isEmpty()) {
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
            if (!cell.style().equals(active) || (cell.link() == null) != (activeLink == null)) {
                if (styled || activeLink != null) {
                    if (activeLink != null) {
                        out.append(OSC8_OPEN).append(STRING_TERMINATOR);
                        activeLink = null;
                    }
                    out.append(RESET);
                    styled = false;
                }
                active = cell.style();
                if (!active.isPlain()) {
                    out.append(sgr(active, profile));
                    styled = true;
                }
            }
            if (cell.link() != null && !cell.link().equals(activeLink)) {
                if (activeLink != null) {
                    out.append(OSC8_OPEN).append(STRING_TERMINATOR);
                }
                out.append(OSC8_OPEN).append(safeLink(cell.link())).append(STRING_TERMINATOR);
                activeLink = cell.link();
            }
            out.append(cell.text());
        }
        if (activeLink != null) {
            out.append(OSC8_OPEN).append(STRING_TERMINATOR);
        }
        if (styled) {
            out.append(RESET);
        }
        return out.toString();
    }

    /** Last column holding a painted grapheme, excluding continuation cells. */
    private static int lastPaintedColumn(Screen screen, int row) {
        for (int col = screen.cols() - 1; col >= 0; col--) {
            if (!screen.isContinuation(row, col) && !screen.cell(row, col).isEmpty()) {
                return col;
            }
        }
        return -1;
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
            if (!screen.cell(row, col).isEmpty()) {
                return col + 1;
            }
        }
        return 0;
    }

    private static String safeLink(String link) {
        return safeText(link);
    }

    /** The text of a payload with every control character removed. */
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
     * SGR parameter sequence for a style under a colour profile.
     *
     * <p>Attributes are emitted whatever the profile says: a session without colour still has bold,
     * underline and the rest. Only the two colour parameters change, and they change shape with the
     * profile - {@code 38;2;r;g;b}, {@code 38;5;index}, or one of the classic 16-colour codes - so a
     * narrower profile loses precision, never decoration. A colour profile of {@link
     * ColorProfile#NONE} emits no colour parameters at all.</p>
     *
     * @param style the style to encode
     * @param profile the colour profile to encode for
     * @return the sequence, or an empty string for a plain style
     */
    public static String sgr(Style style, ColorProfile profile) {
        List<String> params = new ArrayList<>();
        if (style.bold()) {
            params.add("1");
        }
        if (style.italic()) {
            params.add("3");
        }
        if (style.underline()) {
            params.add("4");
        }
        if (style.invert()) {
            params.add("7");
        }
        if (style.hidden()) {
            params.add("8");
        }
        if (style.strikethrough()) {
            params.add("9");
        }
        ColorProfile resolved = profile == null || profile == ColorProfile.AUTO ? ColorProfile.TRUECOLOR : profile;
        if (resolved.color()) {
            colour(params, style.foreground(), resolved, true);
            colour(params, style.background(), resolved, false);
        }
        return params.isEmpty() ? "" : "\u001B[" + String.join(";", params) + "m";
    }

    /**
     * Adds one colour's parameters to a style's parameter list.
     *
     * <p>Only called for a profile that emits colour, so the three branches are the three forms a
     * terminal accepts: 24-bit, a 256-colour palette index, and one of the classic 16-colour codes.
     * The profile is compared rather than switched so the guard above is the only place that has to
     * know {@link ColorProfile#NONE} exists.</p>
     */
    private static void colour(List<String> params, Color color, ColorProfile profile, boolean foreground) {
        if (color == null) {
            return;
        }
        if (profile == ColorProfile.ANSI16) {
            int index = Colors.nearestAnsi16(color);
            int code = index < 8 ? (foreground ? 30 : 40) + index : (foreground ? 90 : 100) + index - 8;
            params.add(Integer.toString(code));
        } else if (profile == ColorProfile.ANSI256) {
            params.add((foreground ? "38;5;" : "48;5;") + Colors.nearestAnsi256(color));
        } else {
            params.add((foreground ? "38;2;" : "48;2;") + color.red() + ";" + color.green() + ";" + color.blue());
        }
    }
}
