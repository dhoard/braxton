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

package io.github.dhoard.braxton.layout;

/**
 * Characters used to draw a box. {@code NONE} draws no border and adds no inset.
 *
 * <p>Each character is painted as content, so it passes the same sanitization as text: a control
 * character or an unpaired surrogate occupies its cell as a blank rather than reaching the terminal,
 * and a custom border can therefore never emit an escape sequence or an unencodable unit. A line
 * break or tab is a control character too: a frame row has no line structure to break and no tab
 * stop to advance, so both are blank cells as well instead of moving the cursor.</p>
 */
public record Border(char topLeft, char topRight, char bottomLeft, char bottomRight,
        char horizontal, char vertical) {

    public static final Border NONE = new Border(' ', ' ', ' ', ' ', ' ', ' ');
    public static final Border SINGLE = new Border('\u250C', '\u2510', '\u2514', '\u2518', '\u2500', '\u2502');
    public static final Border ROUNDED = new Border('\u256D', '\u256E', '\u2570', '\u256F', '\u2500', '\u2502');
    public static final Border DOUBLE = new Border('\u2554', '\u2557', '\u255A', '\u255D', '\u2550', '\u2551');

    /**
     * The character painted in one cell of a frame: {@code character} itself, or a blank when it is
     * a control character or an unpaired surrogate.
     *
     * <p>A cell cannot hold a line break or a tab, and neither may reach the terminal: the first
     * would split a frame row and the second would move the cursor to a tab stop. Both are control
     * characters, so they take the same blank cell as {@code ESC} or a lone surrogate.</p>
     */
    public static String glyph(char character) {
        return Character.isISOControl(character) || Character.isSurrogate(character)
                ? " " : String.valueOf(character);
    }

    public boolean isVisible() {
        return this != NONE && vertical != ' ';
    }
}
