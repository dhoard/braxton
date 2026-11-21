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

import java.util.Collection;

/**
 * The value range a chart maps onto cells: a lowest value, a highest value, and the fraction between
 * them a value sits at.
 *
 * <p>The four data charts - {@link LineChart}, {@link BarChart}, {@link Sparkline} and
 * {@link Gauge} - all have to answer the same question, "how far along its axis is this value", and
 * they must answer it the same way or two charts of the same data would disagree about where a
 * point is. The range is that one answer, and it is public because an application that paints its
 * own axis labels has to place them with the same arithmetic the chart paints with.</p>
 *
 * <p>A range is normally derived from the data ({@link #of(Collection)}), so a chart scales itself
 * to what it shows; an application that needs a fixed axis - a percentage from zero to a hundred, a
 * range a live chart must not jump within as samples arrive - builds one directly and hands it to
 * the model.</p>
 *
 * <p>A degenerate range, one whose bounds are equal, is a legal flat axis: every value maps to
 * {@code 0.5}, the middle of the axis. That is what a chart of a constant series paints - a line
 * across the middle rather than a division by zero - and it means a configured maximum of zero
 * still paints instead of failing at frame time.</p>
 *
 * @param min the lower bound
 * @param max the maximum available cell size
 * @see ChartRange#fraction(double)
 */
public record ChartRange(double min, double max) {

    /**
     * The range a chart with no samples uses: nothing to show, so nothing to divide by.
     */
    private static final ChartRange EMPTY = new ChartRange(0.0, 1.0);

    /**
     * Requires two finite bounds and orders them.
     *
     * <p>Reversed bounds are swapped rather than rejected, because a caller that computes its own
     * bounds can hand them over in either order and the axis means the same thing. A non-finite
     * bound is rejected: a {@code NaN} range would silently place every value at the same cell, and
     * an infinite one would map real values onto a {@code NaN} fraction.</p>
     *
     * @param min one end of the range
     * @param max the other end of the range
     */
    public ChartRange {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("range bounds must be finite");
        }
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
    }

    /**
     * The range of some values.
     *
     * <p>A value that is not a number is a gap in a series rather than a value, so it takes no part
     * in the range; a collection with nothing to measure yields {@code 0..1}, the range a chart with
     * no samples paints against.</p>
     *
     * @param values the values to cover; null is treated as empty
     * @return the range
     */
    public static ChartRange of(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
        }
        return lowest > highest ? EMPTY : new ChartRange(lowest, highest);
    }

    /**
     * A range between two bounds, ordered as the canonical constructor orders them.
     *
     * @param min one end of the range
     * @param max the other end of the range
     * @return the range
     */
    public static ChartRange of(double min, double max) {
        return new ChartRange(min, max);
    }

    /**
     * This range extended to include zero.
     *
     * <p>A bar grows from zero, so a bar chart of values with a sign needs the zero column inside
     * its axis; a range that already covers zero is returned unchanged.</p>
     *
     * @return the range that includes zero
     */
    public ChartRange withZero() {
        return min <= 0.0 && max >= 0.0 ? this : new ChartRange(Math.min(0.0, min), Math.max(0.0, max));
    }

    /**
     * How far along the range a value sits.
     *
     * <p>The answer is in {@code [0,1]}, so a value outside the range is clamped onto the end it is
     * past - an explicit axis clips rather than grows - and a value that is not a number returns
     * zero, which is the cell a caller that forgot to skip a gap would paint.</p>
     *
     * @param value the value to place
     * @return the position in the range, from zero to one
     */
    public double fraction(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double span = max - min;
        if (span <= 0.0) {
            // A flat axis has no ends to be between, so everything is in the middle of it.
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, (value - min) / span));
    }

    /**
     * The range of some values, with a null range deriving one.
     *
     * <p>The chart models accept a null range to mean "scale to the data", so every one of them
     * resolves the component the same way and an application never has to compute a range it did
     * not want to choose.</p>
     *
     * @param range the range to use; null derives one from {@code values}
     * @param values the values the range is derived from
     * @return the range
     */
    static ChartRange resolve(ChartRange range, Collection<Double> values) {
        return range == null ? of(values) : range;
    }
}
