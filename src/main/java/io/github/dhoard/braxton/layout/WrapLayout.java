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
 * Flow layout that wraps children into rows and clips anything beyond the bounds.
 */
public final class WrapLayout {

    private WrapLayout() {}

    /**
     * Places known-size children left to right, wrapping at the right edge.
     *
     * @param bounds the allocated cell rectangle
     * @param children the children in declaration order
     * @param colGap the gap between columns in cells
     * @param rowGap the gap between rows in cells
     * @return the wrapped child rectangles in declaration order
     */
    public static List<Rect> layout(Rect bounds, List<Size> children, int colGap, int rowGap) {
        List<Rect> result = new ArrayList<>(children.size());
        int horizontalGap = Math.max(0, colGap);
        int verticalGap = Math.max(0, rowGap);
        int row = bounds.row();
        int col = bounds.col();
        int rowHeight = 0;
        for (Size child : children) {
            int height = Math.min(bounds.rows(), Math.max(0, child.rows()));
            int width = Math.min(bounds.cols(), Math.max(0, child.cols()));
            if (width == 0 || height == 0) {
                result.add(new Rect(0, 0, 0, 0));
                continue;
            }
            if (col > bounds.col() && (long) col + width > (long) bounds.col() + bounds.cols()) {
                col = bounds.col();
                row += rowHeight + verticalGap;
                rowHeight = 0;
            }
            if (row >= bounds.row() + bounds.rows()) {
                result.add(new Rect(0, 0, 0, 0));
                continue;
            }
            int clippedWidth = (int) Math.min(width, (long) bounds.col() + bounds.cols() - col);
            int clippedHeight = (int) Math.min(height, (long) bounds.row() + bounds.rows() - row);
            result.add(new Rect(row, col, clippedHeight, clippedWidth));
            col += width + horizontalGap;
            rowHeight = Math.max(rowHeight, height);
        }
        return List.copyOf(result);
    }
}
