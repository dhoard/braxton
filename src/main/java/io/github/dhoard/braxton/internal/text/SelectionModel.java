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

package io.github.dhoard.braxton.internal.text;

/**
 * A caret and anchor in cell coordinates; the selection is always normalized.
 *
 * @param anchorRow the zero-based selection anchor row
 * @param anchorCol the zero-based selection anchor column
 * @param headRow the zero-based selection head row
 * @param headCol the zero-based selection head column
 */
public record SelectionModel(int anchorRow, int anchorCol, int headRow, int headCol) {

    /**
     * Creates a collapsed selection at the supplied cell.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return a collapsed selection at the supplied cell
     */
    public static SelectionModel caret(int row, int col) {
        return new SelectionModel(row, col, row, col);
    }

    /**
     * Returns the first row in normalized selection order.
     *
     * @return the first row in normalized selection order
     */
    public int startRow() {
        return Math.min(anchorRow, headRow);
    }

    /**
     * Returns the last row in normalized selection order.
     *
     * @return the last row in normalized selection order
     */
    public int endRow() {
        return Math.max(anchorRow, headRow);
    }

    /**
     * Returns the first column in normalized selection order.
     *
     * @return the first column in normalized selection order
     */
    public int startCol() {
        if (anchorRow == headRow) return Math.min(anchorCol, headCol);
        return anchorRow < headRow ? anchorCol : headCol;
    }

    /**
     * Returns the last column in normalized selection order.
     *
     * @return the last column in normalized selection order
     */
    public int endCol() {
        if (anchorRow == headRow) return Math.max(anchorCol, headCol);
        return anchorRow < headRow ? headCol : anchorCol;
    }

    /**
     * Tests whether the selection has no extent.
     *
     * @return {@code true} if the selection has no extent; otherwise {@code false}
     */
    public boolean isEmpty() {
        return anchorRow == headRow && anchorCol == headCol;
    }

    /**
     * Moves only the head; the anchor holds the other end of the selection.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return moves only the head; the anchor holds the other end of the selection
     */
    public SelectionModel moveHead(int row, int col) {
        return new SelectionModel(anchorRow, anchorCol, Math.max(0, row), Math.max(0, col));
    }

    /**
     * Collapses the selection to its head.
     *
     * @return collapses the selection to its head
     */
    public SelectionModel collapse() {
        return caret(headRow, headCol);
    }
}
