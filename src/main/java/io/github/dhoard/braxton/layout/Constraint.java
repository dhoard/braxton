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
 * One sizing rule on one axis. Rules compose: a {@code flex} may also carry a {@code min} and a
 * {@code max}, and a definite rule may be bounded the same way.
 */
public sealed interface Constraint
        permits Constraint.Fixed,
                Constraint.Minimum,
                Constraint.Maximum,
                Constraint.Percent,
                Constraint.Fractional,
                Constraint.Flex {

    /**
     * Exactly {@code cells}, clipped to the available space.
     *
     * @param cells the number of cells
     */
    record Fixed(int cells) implements Constraint {
        /**
         * Creates a {@code Fixed} value.
         *
         * @param cells the number of cells
         */
        public Fixed {
            if (cells < 0) throw new IllegalArgumentException("fixed size must be non-negative");
        }
    }

    /**
     * At least {@code cells} when the available space allows it.
     *
     * @param cells the number of cells
     */
    record Minimum(int cells) implements Constraint {
        /**
         * Creates a {@code Minimum} value.
         *
         * @param cells the number of cells
         */
        public Minimum {
            if (cells < 0) throw new IllegalArgumentException("minimum size must be non-negative");
        }
    }

    /**
     * At most {@code cells}.
     *
     * @param cells the number of cells
     */
    record Maximum(int cells) implements Constraint {
        /**
         * Creates a {@code Maximum} value.
         *
         * @param cells the number of cells
         */
        public Maximum {
            if (cells < 0) throw new IllegalArgumentException("maximum size must be non-negative");
        }
    }

    /**
     * An integer percentage of the available space, 0 through 100.
     *
     * @param value the percentage, 0 through 100
     */
    record Percent(int value) implements Constraint {
        /**
         * Creates a {@code Percent} value.
         *
         * @param value the percentage, 0 through 100
         */
        public Percent {
            if (value < 0 || value > 100) throw new IllegalArgumentException("percentage must be 0..100");
        }
    }

    /**
     * A ratio of the available space with a positive denominator.
     *
     * @param numerator the fractional share numerator
     * @param denominator the positive fractional share denominator
     */
    record Fractional(int numerator, int denominator) implements Constraint {
        /**
         * Creates a {@code Fractional} value.
         *
         * @param numerator the fractional share numerator
         * @param denominator the positive fractional share denominator
         */
        public Fractional {
            if (numerator < 0 || denominator <= 0) {
                throw new IllegalArgumentException("fraction needs a non-negative numerator and positive denominator");
            }
        }
    }

    /**
     * A share of the space left after definite rules, weighted by {@code weight}.
     *
     * @param weight the positive share of remaining space
     */
    record Flex(int weight) implements Constraint {
        /**
         * Creates a {@code Flex} value.
         *
         * @param weight the positive share of remaining space
         */
        public Flex {
            if (weight <= 0) throw new IllegalArgumentException("flex weight must be positive");
        }
    }

    /**
     * Convenience factories for the six rule kinds.
     *
     * @param cells the number of cells
     * @return convenience factories for the six rule kinds
     */
    static Fixed fixed(int cells) {
        return new Fixed(cells);
    }

    /**
     * Creates a lower cell bound.
     *
     * @param cells the number of cells
     * @return a lower cell bound
     */
    static Minimum min(int cells) {
        return new Minimum(cells);
    }

    /**
     * Creates an upper cell bound.
     *
     * @param cells the number of cells
     * @return an upper cell bound
     */
    static Maximum max(int cells) {
        return new Maximum(cells);
    }

    /**
     * Creates a percentage constraint.
     *
     * @param value the percentage, 0 through 100
     * @return a percentage constraint
     */
    static Percent percent(int value) {
        return new Percent(value);
    }

    /**
     * Creates a fractional constraint with a positive denominator.
     *
     * @param numerator the fractional share numerator
     * @param denominator the positive fractional share denominator
     * @return a fractional constraint with a positive denominator
     */
    static Fractional fraction(int numerator, int denominator) {
        return new Fractional(numerator, denominator);
    }

    /**
     * Creates a positive-weight share of the remaining space.
     *
     * @param weight the positive share of remaining space
     * @return a positive-weight share of the remaining space
     */
    static Flex flex(int weight) {
        return new Flex(weight);
    }
}
