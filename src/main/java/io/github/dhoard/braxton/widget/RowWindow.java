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

package io.github.dhoard.braxton.widget;

/**
 * The window arithmetic a scrolling row widget shares: where a window starts when it has to show a
 * selected row out of a list.
 *
 * <p>It is the rule {@link Tree}, {@link Menu} and {@link CommandPalette} all need, stated once:
 * the window is inside the rows, and it is over the selection whenever the rows allow it. Every
 * step, page and toggle moves the selection first and admits the window after, so the two cannot
 * disagree about which row is on screen.</p>
 */
final class RowWindow {

    private RowWindow() {}

    /**
     * The first row a window paints.
     *
     * @param index the row that has to be shown; -1 when there is no selection
     * @param size the rows there are; never negative
     * @param offset the window the caller asked for
     * @param rows the rows the window may paint; never negative
     * @return the first row to paint
     */
    static int admit(int index, int size, int offset, int rows) {
        int count = Math.max(0, size);
        int height = Math.max(0, rows);
        int start = Math.max(0, Math.min(offset, Math.max(0, count - height)));
        if (height <= 0 || index < 0 || index >= count) {
            return start;
        }
        if (index < start) {
            return index;
        }
        return index >= start + height ? Math.max(0, index - height + 1) : start;
    }
}
