/*
 * Copyright (c) 2026-present Doug Hoard
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

/** Immutable spacing around a view, in cells. */
public record Insets(int top, int right, int bottom, int left) {

    public static final Insets NONE = new Insets(0, 0, 0, 0);

    public Insets {
        if (top < 0 || right < 0 || bottom < 0 || left < 0) {
            throw new IllegalArgumentException("insets must be non-negative");
        }
    }

    public static Insets uniform(int value) {
        return new Insets(value, value, value, value);
    }

    public static Insets symmetric(int vertical, int horizontal) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }

    /**
     * Total horizontal spacing, saturating at {@link Integer#MAX_VALUE}.
     *
     * <p>Both halves are non-negative but their sum need not fit in an {@code int}; a wrapped
     * negative total would make {@link Rect#inset(Insets)} grow a rectangle instead of shrinking
     * it.</p>
     */
    public int horizontal() {
        return saturatedSum(left, right);
    }

    /** Total vertical spacing, saturating at {@link Integer#MAX_VALUE} like {@link #horizontal()}. */
    public int vertical() {
        return saturatedSum(top, bottom);
    }

    private static int saturatedSum(int first, int second) {
        return (int) Math.min(Integer.MAX_VALUE, (long) first + second);
    }
}
