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
 * Scroll offsets, visible content and scrollbar thumbs for a cell grid.
 */
public final class ScrollLayout {

    private ScrollLayout() {}

    /**
     * Largest legal offset on one axis; zero when the content already fits.
     *
     * @param visible the visible extent in cells
     * @param content the content extent in cells
     * @return largest legal offset on one axis; zero when the content already fits
     */
    public static int maxOffset(int visible, int content) {
        return Math.max(0, content - Math.max(0, visible));
    }

    /**
     * Clamps an offset to the scrollable range.
     *
     * @param offset the starting offset
     * @param visible the visible extent in cells
     * @param content the content extent in cells
     * @return the offset clamped to the legal range
     */
    public static int clampOffset(int offset, int visible, int content) {
        return Math.max(0, Math.min(offset, maxOffset(visible, content)));
    }

    /**
     * The content rectangle currently visible inside the viewport.
     *
     * @param viewport the available terminal viewport
     * @param content the content extent in cells
     * @param rowOffset the vertical scroll offset in rows
     * @param colOffset the horizontal scroll offset in cells
     * @return the content rectangle currently visible inside the viewport
     */
    public static Rect visible(Rect viewport, Size content, int rowOffset, int colOffset) {
        int row = clampOffset(rowOffset, viewport.rows(), content.rows());
        int col = clampOffset(colOffset, viewport.cols(), content.cols());
        int rows = Math.min(viewport.rows(), Math.max(0, content.rows() - row));
        int cols = Math.min(viewport.cols(), Math.max(0, content.cols() - col));
        return new Rect(row, col, rows, cols);
    }

    /**
     * A scrollbar thumb on a one-cell track; fills the track when no scrolling is possible.
     *
     * @param track the scrollbar track rectangle
     * @param content the content extent in cells
     * @param offset the starting offset
     * @param vertical whether to compute a vertical rather than horizontal thumb
     * @return a scrollbar thumb on a one-cell track; fills the track when no scrolling is possible
     */
    public static Rect thumb(Rect track, Size content, int offset, boolean vertical) {
        int visible = vertical ? track.rows() : track.cols();
        int total = vertical ? content.rows() : content.cols();
        int at = clampOffset(offset, visible, total);
        if (total <= visible || track.isEmpty()) return track;
        int length = Math.max(1, (int) (((long) visible * visible + total - 1) / total));
        length = Math.min(visible, length);
        int position =
                maxOffset(visible, total) == 0 ? 0 : (int) ((long) at * (visible - length) / maxOffset(visible, total));
        return vertical
                ? new Rect(track.row() + position, track.col(), length, track.cols())
                : new Rect(track.row(), track.col() + position, track.rows(), length);
    }
}
