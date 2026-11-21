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
 * Constraint-driven row and column layout.
 */
public final class FlexLayout {

    /**
     * Which axis children are placed along.
     */
    public enum Direction {
        /**
         * Places children in a horizontal row.
         */
        ROW,
        /**
         * Places children in a vertical column.
         */
        COLUMN
    }

    private FlexLayout() {}

    /**
     * Places children along one axis, filling the other axis.
     *
     * @param bounds the allocated cell rectangle
     * @param direction the layout or navigation direction
     * @param specs the track constraints in declaration order
     * @param gap the gap between children in cells
     * @return the child rectangles in declaration order
     */
    public static List<Rect> layout(Rect bounds, Direction direction, List<List<Constraint>> specs, int gap) {
        int mainCells = direction == Direction.ROW ? bounds.cols() : bounds.rows();
        int main = mainCells - Math.max(0, gap) * Math.max(0, specs.size() - 1);
        int[] sizes = Constraints.solve(main, specs);
        List<Rect> result = new ArrayList<>(specs.size());
        int cursor = 0;
        for (int size : sizes) {
            result.add(rect(bounds, direction, cursor, size, bounds.rows(), bounds.cols()));
            cursor += size + Math.max(0, gap);
        }
        return List.copyOf(result);
    }

    /**
     * Places children with explicit cross-axis sizes and alignment.
     *
     * @param alignment the alignment on the cross axis
     * @param bounds the allocated cell rectangle
     * @param crossSizes the measured cross-axis sizes
     * @param direction the layout or navigation direction
     * @param gap the gap between children in cells
     * @param specs the track constraints in declaration order
     * @return the aligned child rectangles in declaration order
     */
    public static List<Rect> layout(
            Rect bounds,
            Direction direction,
            List<List<Constraint>> specs,
            List<Integer> crossSizes,
            int gap,
            Alignment alignment) {
        List<Rect> result = new ArrayList<>(specs.size());
        List<Rect> mainRects = layout(bounds, direction, specs, gap);
        int crossLimit = direction == Direction.ROW ? bounds.rows() : bounds.cols();
        Alignment placement = alignment == null ? Alignment.START : alignment;
        for (int i = 0; i < mainRects.size(); i++) {
            int cross = i < crossSizes.size() ? Math.max(0, Math.min(crossLimit, crossSizes.get(i))) : crossLimit;
            int offset = switch (placement) {
                case START -> 0;
                case CENTER -> (crossLimit - cross) / 2;
                case END -> crossLimit - cross;
            };
            Rect main = mainRects.get(i);
            if (direction == Direction.ROW) {
                result.add(new Rect(bounds.row() + offset, main.col(), cross, main.cols()));
            } else {
                result.add(new Rect(main.row(), bounds.col() + offset, main.rows(), cross));
            }
        }
        return List.copyOf(result);
    }

    private static Rect rect(Rect bounds, Direction direction, int cursor, int size, int rows, int cols) {
        if (direction == Direction.ROW) {
            return new Rect(bounds.row(), bounds.col() + cursor, rows, size).clip(bounds);
        }
        return new Rect(bounds.row() + cursor, bounds.col(), size, cols).clip(bounds);
    }
}
