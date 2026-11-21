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
 * Virtualized scrolling over an indexed content model.
 *
 * @param itemCount the total number of items
 * @param viewportRows the number of visible rows
 * @param offset the starting offset
 */
public record VirtualScroll(int itemCount, int viewportRows, int offset) {

    /**
     * Creates a {@code VirtualScroll} value.
     *
     * @param itemCount the total number of items
     * @param viewportRows the number of visible rows
     * @param offset the starting offset
     */
    public VirtualScroll {
        if (itemCount < 0 || viewportRows < 0) throw new IllegalArgumentException("sizes must be non-negative");
    }

    /**
     * Creates a {@code VirtualScroll} value.
     *
     * @param itemCount the total number of items
     * @param viewportRows the number of visible rows
     * @return the scroll state clamped to the supplied geometry
     */
    public static VirtualScroll of(int itemCount, int viewportRows) {
        return new VirtualScroll(itemCount, viewportRows, 0);
    }

    /**
     * Returns the greatest top offset that keeps the viewport inside the content.
     *
     * @return the greatest top offset that keeps the viewport inside the content
     */
    public int maxOffset() {
        return Math.max(0, itemCount - viewportRows);
    }

    /**
     * Returns the same window with its offset clamped around the requested position.
     *
     * @param value the requested offset, clamped to the scrollable range
     * @return the same window with its offset clamped around the requested position
     */
    public VirtualScroll scrollTo(int value) {
        return new VirtualScroll(itemCount, viewportRows, Math.max(0, Math.min(value, maxOffset())));
    }

    /**
     * Moves the window by the requested signed number of viewport pages.
     *
     * @param delta the requested signed change
     * @return moves the window by the requested signed number of viewport pages
     */
    public VirtualScroll page(int delta) {
        return scrollTo(offset + delta * Math.max(1, viewportRows));
    }

    /**
     * Moves the window only far enough to show the requested item.
     *
     * @param index the zero-based item index
     * @return moves the window only far enough to show the requested item
     */
    public VirtualScroll ensureVisible(int index) {
        if (index < 0 || itemCount == 0) return this;
        if (index < offset) return scrollTo(index);
        if (index >= offset + viewportRows) return scrollTo(index - viewportRows + 1);
        return this;
    }

    /**
     * Returns the first visible item index.
     *
     * @return the first visible item index
     */
    public int firstVisible() {
        return Math.min(offset, Math.max(0, itemCount - 1));
    }

    /**
     * Returns the exclusive end index of the visible window.
     *
     * @return the exclusive end index of the visible window
     */
    public int lastVisibleExclusive() {
        return Math.min(itemCount, offset + viewportRows);
    }
}
