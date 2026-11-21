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

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic cell-grid layout with independent row and column constraints.
 */
public final class GridLayout {

    private GridLayout() {}

    /**
     * Returns one rectangle per cell in row-major order.
     *
     * @param bounds the allocated cell rectangle
     * @param colGap the gap between columns in cells
     * @param colSpecs the column constraints
     * @param cols the number of columns
     * @param rowGap the gap between rows in cells
     * @param rowSpecs the row constraints
     * @param rows the number of rows
     * @return one rectangle per cell in row-major order
     */
    public static List<Rect> layout(
            Rect bounds,
            int rows,
            int cols,
            List<List<Constraint>> rowSpecs,
            List<List<Constraint>> colSpecs,
            int rowGap,
            int colGap) {
        if (rows <= 0 || cols <= 0 || bounds.isEmpty()) return List.of();
        int[] rowSizes = Constraints.solve(bounds.rows() - Math.max(0, rowGap) * (rows - 1), padded(rowSpecs, rows));
        int[] colSizes = Constraints.solve(bounds.cols() - Math.max(0, colGap) * (cols - 1), padded(colSpecs, cols));
        List<Rect> result = new ArrayList<>(rows * cols);
        int rowStart = bounds.row();
        for (int row = 0; row < rows; row++) {
            int colStart = bounds.col();
            for (int col = 0; col < cols; col++) {
                result.add(new Rect(rowStart, colStart, rowSizes[row], colSizes[col]).clip(bounds));
                colStart += colSizes[col] + Math.max(0, colGap);
            }
            rowStart += rowSizes[row] + Math.max(0, rowGap);
        }
        return List.copyOf(result);
    }

    private static List<List<Constraint>> padded(List<List<Constraint>> specs, int count) {
        List<List<Constraint>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(specs != null && i < specs.size() ? specs.get(i) : List.of(Constraint.flex(1)));
        }
        return result;
    }
}
