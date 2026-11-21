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

package io.github.dhoard.braxton.layout;

/**
 * A half-open rectangle of cells, relative to the screen origin.
 *
 * @param row the first row, never negative
 * @param col the first column, never negative
 * @param rows the number of rows, never negative
 * @param cols the number of columns, never negative
 */
public record Rect(int row, int col, int rows, int cols) {

    /**
     * Creates a {@code Rect} value.
     *
     * @param row the first row, never negative
     * @param col the first column, never negative
     * @param rows the number of rows, never negative
     * @param cols the number of columns, never negative
     */
    public Rect {
        if (row < 0 || col < 0 || rows < 0 || cols < 0) {
            throw new IllegalArgumentException("rect must be non-negative");
        }
    }

    /**
     * Tests whether at least one cell dimension is zero.
     *
     * @return {@code true} if at least one cell dimension is zero; otherwise {@code false}
     */
    public boolean isEmpty() {
        return rows == 0 || cols == 0;
    }

    /**
     * Tests whether the target cell is inside this half-open rectangle.
     *
     * @param targetRow the zero-based target row
     * @param targetCol the zero-based target column
     * @return {@code true} if the target cell is inside this half-open rectangle; otherwise {@code false}
     */
    public boolean contains(int targetRow, int targetCol) {
        return targetRow >= row && targetRow < (long) row + rows && targetCol >= col && targetCol < (long) col + cols;
    }

    /**
     * Shrinks this rectangle by insets; never produces negative dimensions.
     *
     * @param insets the edge insets in cells
     * @return shrinks this rectangle by insets; never produces negative dimensions
     */
    public Rect inset(Insets insets) {
        // The origin addition saturates for the same reason the sums below do: a row or column
        // already inside the screen plus a huge inset would otherwise wrap negative and be rejected
        // by the constructor, turning a layout that can only clip into a failed run.
        int newRow = saturatedAdd(row, insets.top());
        int newCol = saturatedAdd(col, insets.left());
        int newRows = Math.max(0, rows - insets.vertical());
        int newCols = Math.max(0, cols - insets.horizontal());
        return new Rect(newRow, newCol, newRows, newCols);
    }

    private static int saturatedAdd(int origin, int offset) {
        return (int) Math.min(Integer.MAX_VALUE, (long) origin + offset);
    }

    /**
     * Returns the intersection with the supplied clipping rectangle.
     *
     * @param bounds the allocated cell rectangle
     * @return the intersection with the supplied clipping rectangle
     */
    public Rect clip(Rect bounds) {
        int top = Math.max(row, bounds.row);
        int left = Math.max(col, bounds.col);
        long bottom = Math.min((long) row + rows, (long) bounds.row + bounds.rows);
        long right = Math.min((long) col + cols, (long) bounds.col + bounds.cols);
        // An intersection is at most as large as either input, so its dimensions still fit int.
        return new Rect(top, left, (int) Math.max(0L, bottom - top), (int) Math.max(0L, right - left));
    }
}
