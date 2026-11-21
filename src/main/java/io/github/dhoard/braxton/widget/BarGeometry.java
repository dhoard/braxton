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

package io.github.dhoard.braxton.widget;

/**
 * The scrollbar arithmetic shared by the widgets that paint one.
 *
 * <p>A scrollbar answers two questions about a window over longer content: how many rows of the
 * window the thumb covers, and which window row it starts at. Both are fractions - the window's
 * share of the content, and the fraction of the content the window has scrolled past - rounded to
 * whole rows and clamped so the thumb is never empty, never longer than the window and never
 * leaves it. One implementation keeps {@link ScrollView}'s painted bar and the standalone
 * {@link Scrollbar} widget from disagreeing about a position they are both asked to show, which is
 * what lets an application paint its own bar beside a pane and have the two line up.</p>
 */
final class BarGeometry {

    /**
     * The glyph a thumb cell paints.
     */
    static final String THUMB = "\u2588";

    /**
     * The glyph a track cell paints.
     */
    static final String TRACK = "\u2502";

    private BarGeometry() {}

    /**
     * Where a thumb sits in a window and how many rows it covers.
     *
     * @param start the first window row the thumb covers
     * @param size the rows the thumb covers
     */
    record Span(int start, int size) {}

    /**
     * The thumb geometry for a window over taller content.
     *
     * <p>Content that fits the window has no bar - the caller paints none - so an empty span is
     * what this answers for it, exactly as for a window of no rows. The arithmetic is done in
     * {@code long} before it is narrowed: the window's square is what the size fraction needs, and
     * a content count near the {@code int} range would overflow it.</p>
     *
     * @param first the first content row the window shows, already clamped to the content
     * @param window the rows the window covers
     * @param content the content rows there are
     * @return the thumb geometry; empty when there is nothing to scroll
     */
    static Span thumb(int first, int window, long content) {
        if (window <= 0 || content <= window) {
            return new Span(0, 0);
        }
        long size = Math.max(1L, Math.min(window, Math.round((double) window * window / content)));
        long travel = content - window;
        long start = Math.round((double) (window - size) * first / travel);
        return new Span((int) Math.max(0L, Math.min(start, window - size)), (int) size);
    }
}
