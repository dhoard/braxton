/*
 * Copyright (c) 2026-present Doug Hoard
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

import java.util.ArrayList;
import java.util.List;

import io.github.dhoard.braxton.style.Style;

/**
 * Turns painted cells into terminal escape sequences.
 *
 * <p>Only this encoder writes SGR and OSC 8 sequences. Content text never contains escape bytes, so
 * data cannot inject terminal commands. Rows are addressed absolutely, painted in place, and only
 * their unused suffix is cleared, so shrinking rows leave no stale cells or clear-before-paint gap.</p>
 */
public final class AnsiEncoder {

    public static final String RESET = "\u001B[0m";
    public static final String HIDE_CURSOR = "\u001B[?25l";
    public static final String SHOW_CURSOR = "\u001B[?25h";
    public static final String CLEAR_SCREEN = "\u001B[H\u001B[2J";
    public static final String ALT_SCREEN_ON = "\u001B[?1049h";
    public static final String ALT_SCREEN_OFF = "\u001B[?1049l";
    public static final String ENABLE_BRACKETED_PASTE = "\u001B[?2004h";
    public static final String DISABLE_BRACKETED_PASTE = "\u001B[?2004l";
    public static final String ENABLE_MOUSE = "\u001B[?1000h\u001B[?1006h";
    public static final String DISABLE_MOUSE = "\u001B[?1006l\u001B[?1000l";

    private static final String OSC8_OPEN = "\u001B]8;;";
    private static final String STRING_TERMINATOR = "\u001B\\";

    private AnsiEncoder() {}

    /** A complete frame: every row repainted in place. */
    public static String frame(Screen screen, boolean color) {
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < screen.rows(); row++) {
            out.append(encodeRow(screen, row, color));
        }
        return out.toString();
    }

    /** Only the rows whose cells differ, or a full frame when there is no previous screen. */
    public static String diff(Screen previous, Screen next, boolean color) {
        if (previous == null || previous.rows() != next.rows() || previous.cols() != next.cols()) {
            return frame(next, color);
        }
        StringBuilder out = new StringBuilder();
        boolean repaintNext = false;
        for (int row = 0; row < next.rows(); row++) {
            if (repaintNext || !rowEquals(previous, next, row)) {
                out.append(encodeRow(next, row, color));
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

    private static boolean rowEquals(Screen previous, Screen next, int row) {
        for (int col = 0; col < next.cols(); col++) {
            if (!previous.cell(row, col).equals(next.cell(row, col))
                    || previous.isContinuation(row, col) != next.isContinuation(row, col)) {
                return false;
            }
        }
        return true;
    }

    /** Absolute cursor move, styled content, then a default-style clear of the unused suffix. */
    public static String encodeRow(Screen screen, int row, boolean color) {
        if (screen.cols() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("\u001B[").append(row + 1).append(";1H");
        int end = paintedEnd(screen, row);
        if (end == 0) {
            out.append(RESET).append("\u001B[2K");
            return out.toString();
        }
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
                    out.append(
                            sgr(color ? active : active.withForeground(null).withBackground(null)));
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
        if (end < screen.cols()) {
            out.append("\u001B[").append(row + 1).append(';').append(end + 1).append("H")
                    .append(RESET).append("\u001B[0K");
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

    /** Exclusive painted extent, including the second column of a wide grapheme. */
    private static int paintedEnd(Screen screen, int row) {
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
        StringBuilder safe = new StringBuilder(link.length());
        for (int i = 0; i < link.length(); i++) {
            char c = link.charAt(i);
            if (!Character.isISOControl(c)) {
                safe.append(c);
            }
        }
        return safe.toString();
    }

    /** SGR parameter sequence for a style (without the reset terminator). */
    public static String sgr(Style style) {
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
        if (style.foreground() != null) {
            params.add("38;2;" + style.foreground().red() + ";"
                    + style.foreground().green() + ";" + style.foreground().blue());
        }
        if (style.background() != null) {
            params.add("48;2;" + style.background().red() + ";"
                    + style.background().green() + ";" + style.background().blue());
        }
        return params.isEmpty() ? "" : "\u001B[" + String.join(";", params) + "m";
    }
}
