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

/** A half-open rectangle of cells, relative to the screen origin. */
public record Rect(int row, int col, int rows, int cols) {

    public Rect {
        if (row < 0 || col < 0 || rows < 0 || cols < 0) {
            throw new IllegalArgumentException("rect must be non-negative");
        }
    }

    public boolean isEmpty() {
        return rows == 0 || cols == 0;
    }

    public boolean contains(int targetRow, int targetCol) {
        return targetRow >= row && targetRow < row + rows
                && targetCol >= col && targetCol < col + cols;
    }

    /** Shrinks this rectangle by insets; never produces negative dimensions. */
    public Rect inset(Insets insets) {
        int newRow = row + insets.top();
        int newCol = col + insets.left();
        int newRows = Math.max(0, rows - insets.vertical());
        int newCols = Math.max(0, cols - insets.horizontal());
        return new Rect(newRow, newCol, newRows, newCols);
    }

    public Rect clip(Rect bounds) {
        int top = Math.max(row, bounds.row);
        int left = Math.max(col, bounds.col);
        int bottom = Math.min(row + rows, bounds.row + bounds.rows);
        int right = Math.min(col + cols, bounds.col + bounds.cols);
        return new Rect(top, left, Math.max(0, bottom - top), Math.max(0, right - left));
    }
}
