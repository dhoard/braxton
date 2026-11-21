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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Docks children to the four edges and gives the remainder to the center.
 */
public final class DockLayout {

    /**
     * Dock position; the center is resolved last.
     */
    public enum Edge {
        /**
         * Places content in the top docking region.
         */
        TOP,
        /**
         * Places content in the bottom docking region.
         */
        BOTTOM,
        /**
         * Places content in the left docking region.
         */
        LEFT,
        /**
         * Places content in the right docking region.
         */
        RIGHT,
        /**
         * Places content in the center docking region.
         */
        CENTER
    }

    private DockLayout() {}

    /**
     * Docks in top, bottom, left, right, center order; oversized docks are clipped in that order.
     *
     * @param bounds the allocated cell rectangle
     * @param sizes the supplier of current terminal sizes
     * @return docks in top, bottom, left, right, center order; oversized docks are clipped in that order
     */
    public static Map<Edge, Rect> layout(Rect bounds, Map<Edge, Size> sizes) {
        Map<Edge, Rect> result = new LinkedHashMap<>();
        int row = bounds.row();
        int col = bounds.col();
        int rows = bounds.rows();
        int cols = bounds.cols();
        for (Edge edge : new Edge[] {Edge.TOP, Edge.BOTTOM}) {
            Size size = sizes.get(edge);
            if (size == null) continue;
            int height = Math.min(rows, Math.max(0, size.rows()));
            Rect rect = new Rect(row, col, height, cols);
            result.put(edge, rect);
            if (edge == Edge.TOP) row += height;
            rows -= height;
        }
        for (Edge edge : new Edge[] {Edge.LEFT, Edge.RIGHT}) {
            Size size = sizes.get(edge);
            if (size == null) continue;
            int width = Math.min(cols, Math.max(0, size.cols()));
            Rect rect = edge == Edge.LEFT
                    ? new Rect(row, col, rows, width)
                    : new Rect(row, col + cols - width, rows, width);
            result.put(edge, rect);
            if (edge == Edge.LEFT) col += width;
            cols -= width;
        }
        result.put(Edge.CENTER, new Rect(row, col, rows, cols));
        return result;
    }
}
