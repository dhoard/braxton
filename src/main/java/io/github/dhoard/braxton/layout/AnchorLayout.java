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
 * Anchor points for an overlay inside a parent rectangle.
 */
public final class AnchorLayout {

    /**
     * Which edge or corner owns the overlay.
     */
    public enum Anchor {
        /**
         * Anchors the child at the top left of the available rectangle.
         */
        TOP_LEFT,
        /**
         * Anchors the child at the top center of the available rectangle.
         */
        TOP_CENTER,
        /**
         * Anchors the child at the top right of the available rectangle.
         */
        TOP_RIGHT,
        /**
         * Anchors the child at the center left of the available rectangle.
         */
        CENTER_LEFT,
        /**
         * Anchors the child at the center of the available rectangle.
         */
        CENTER,
        /**
         * Anchors the child at the center right of the available rectangle.
         */
        CENTER_RIGHT,
        /**
         * Anchors the child at the bottom left of the available rectangle.
         */
        BOTTOM_LEFT,
        /**
         * Anchors the child at the bottom center of the available rectangle.
         */
        BOTTOM_CENTER,
        /**
         * Anchors the child at the bottom right of the available rectangle.
         */
        BOTTOM_RIGHT
    }

    private AnchorLayout() {}

    /**
     * Positions an overlay and clips it to the parent; never returns a rectangle outside bounds.
     *
     * @param bounds the allocated cell rectangle
     * @param anchor the placement anchor
     * @param size the requested cell dimensions
     * @param insets the edge insets in cells
     * @return positions an overlay and clips it to the parent; never returns a rectangle outside bounds
     */
    public static Rect layout(Rect bounds, Anchor anchor, Size size, Insets insets) {
        Insets padding = insets == null ? Insets.NONE : insets;
        Rect area = bounds.inset(padding);
        int rows = Math.min(area.rows(), Math.max(0, size.rows()));
        int cols = Math.min(area.cols(), Math.max(0, size.cols()));
        Anchor point = anchor == null ? Anchor.CENTER : anchor;
        int row = switch (point) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> area.row();
            case CENTER_LEFT, CENTER, CENTER_RIGHT -> area.row() + (area.rows() - rows) / 2;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> area.row() + area.rows() - rows;
        };
        int col = switch (point) {
            case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> area.col();
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> area.col() + (area.cols() - cols) / 2;
            case TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> area.col() + area.cols() - cols;
        };
        return new Rect(row, col, rows, cols).clip(bounds);
    }
}
