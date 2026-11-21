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

package io.github.dhoard.braxton.render;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * The mutable painting surface passed to a widget. No internal or native type is exposed.
 */
public interface Surface {

    /**
     * Writes one grapheme cluster; width 2 marks the next cell as its continuation.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @param grapheme the complete grapheme cluster
     * @param style the style to apply
     * @param link the hyperlink target, or null for none
     * @param width the width in cells
     */
    void put(int row, int col, String grapheme, Style style, String link, int width);

    /**
     * Replaces only the style of an already painted cell.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @param style the style to apply
     */
    void restyle(int row, int col, Style style);

    /**
     * Fills a rectangle with blank cells in one style.
     *
     * @param rect the cell rectangle to fill
     * @param style the style to apply
     */
    void fill(Rect rect, Style style);

    /**
     * Paints one wrapped line of styled text at a screen cell, clipped to {@code cols}.
     *
     * <p>A cell is written only once no later cluster continues it, because a continuation can
     * widen or narrow the cell before it: a mark or a selector a style boundary split off its base
     * belongs to that base's cell, not to a column of its own. That is the rule every widget would
     * otherwise have to restate, and restating it wrongly is how a row ends up a column wider than
     * the value it paints.</p>
     *
     * @param row the screen row to paint on
     * @param col the screen column the line starts at
     * @param cols the columns this writer may use
     * @param line one line of styled text, already wrapped
     * @param policy how a cluster nobody has assigned a width is measured
     */
    default void writeLine(int row, int col, int cols, StyledText line, WidthPolicy policy) {
        SurfaceWriter.writeLine(this, row, col, cols, line, policy);
    }

    /**
     * Wraps styled text to a rectangle and paints as much of it as the rectangle holds.
     *
     * @param bounds the rectangle to paint inside
     * @param text the value to wrap and paint; null paints nothing
     * @param policy how a cluster nobody has assigned a width is measured
     */
    default void write(Rect bounds, StyledText text, WidthPolicy policy) {
        SurfaceWriter.write(this, bounds, text, policy);
    }
}
