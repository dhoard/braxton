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

package io.github.dhoard.braxton.ui;

/**
 * How an element takes its share of the main axis of the group that holds it.
 *
 * <p>A group places its children along one axis - a row's main axis is its columns, a column's is
 * its rows - and this is the three-way choice every layout framework needs: the element asks for
 * what it measures ({@link #measured()}), for a fixed number of cells ({@link #fixed(int)}), or for
 * a share of what is left ({@link #grow(int)}). The main axis is the parent's, so the same element
 * is measured in a column and grown in a row without being rebuilt.</p>
 *
 * <p>The cross axis is not sized here: a row gives every child its own height, a column its own
 * width, and {@link Element.Group#align()} decides where a child smaller than that sits.</p>
 *
 * <p>An element that does not choose asks for what it measures, which is what makes a scene of
 * containers and content compose the way its pixels suggest: a child takes the cells its content
 * needs and a container takes the cells its children need, so leftover space is left over rather
 * than claimed. Asking for a share of it is a deliberate act - {@link #grow()}, through
 * {@code Elements.grow} or {@code withSizing}.</p>
 *
 * @param kind how the element is sized
 * @param value the cells of a fixed size, or the weight of a grown one
 */
public record Sizing(Kind kind, int value) {

    /**
     * The three ways an element takes its share of the main axis.
     */
    public enum Kind {

        /**
         * As many cells as the element measures, kept inside the space that is left.
         */
        MEASURED,

        /**
         * Exactly {@link Sizing#value()} cells.
         */
        FIXED,

        /**
         * A share of the cells no other child claims, in proportion to {@link Sizing#value()}.
         */
        GROW
    }

    /**
     * Rejects a missing kind and a value no size could hold.
     */
    public Sizing {
        if (kind == null) {
            throw new IllegalArgumentException("sizing kind is required");
        }
        if (value < 0) {
            throw new IllegalArgumentException("sizing value must not be negative");
        }
        if (kind == Kind.GROW && value == 0) {
            value = 1;
        }
    }

    /**
     * The element's own measured size.
     *
     * @return the measured sizing
     */
    public static Sizing measured() {
        return new Sizing(Kind.MEASURED, 0);
    }

    /**
     * A fixed number of cells on the main axis.
     *
     * @param cells the cells the element takes; negative is zero
     * @return the fixed sizing
     */
    public static Sizing fixed(int cells) {
        return new Sizing(Kind.FIXED, Math.max(0, cells));
    }

    /**
     * One share of the cells that are left, with every grown sibling weighing the same.
     *
     * @return the grown sizing
     */
    public static Sizing grow() {
        return new Sizing(Kind.GROW, 1);
    }

    /**
     * A share of the cells that are left, in proportion to {@code weight}.
     *
     * @param weight the share's weight; values below one are one
     * @return the grown sizing
     */
    public static Sizing grow(int weight) {
        return new Sizing(Kind.GROW, Math.max(1, weight));
    }

    /**
     * Whether the element asks for what it measures.
     *
     * @return true for {@link Kind#MEASURED}
     */
    public boolean isMeasured() {
        return kind == Kind.MEASURED;
    }

    /**
     * Whether the element asks for a fixed number of cells.
     *
     * @return true for {@link Kind#FIXED}
     */
    public boolean isFixed() {
        return kind == Kind.FIXED;
    }

    /**
     * Whether the element asks for a share of what is left.
     *
     * @return true for {@link Kind#GROW}
     */
    public boolean isGrow() {
        return kind == Kind.GROW;
    }
}
