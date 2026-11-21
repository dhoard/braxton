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

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable numeric transition over caller-supplied monotonic time; no clock, timer or thread.
 * Reduced motion and zero duration select the target immediately, including before the start.
 * Clock differences use the same clamped, saturating arithmetic as {@link Spinner#elapsed}.
 *
 * @param from starting value, finite
 * @param to target value, finite
 * @param startedNanos start timestamp in the caller's clock domain
 * @param duration nonnegative duration fitting in nanoseconds
 * @param easing the interpolation curve
 * @param reducedMotion whether to select the target without animation
 */
public record Transition(
        double from, double to, long startedNanos, Duration duration, Easing easing, boolean reducedMotion) {
    /**
     * Bounded curves that never overshoot either endpoint.
     */
    public enum Easing {
        /**
         * Constant-rate interpolation.
         */
        LINEAR,
        /**
         * Smooth interpolation that eases both endpoints.
         */
        EASE_IN_OUT,
        /**
         * Interpolation that slows as it reaches the target.
         */
        EASE_OUT;

        double apply(double fraction) {
            return switch (this) {
                case LINEAR -> fraction;
                case EASE_IN_OUT -> fraction * fraction * (3 - 2 * fraction);
                case EASE_OUT -> 1 - Math.pow(1 - fraction, 3);
            };
        }
    }

    /**
     * Creates a {@code Transition} value.
     *
     * @param from starting value, finite
     * @param to target value, finite
     * @param startedNanos start timestamp in the caller's clock domain
     * @param duration nonnegative duration fitting in nanoseconds
     * @param easing the interpolation curve
     * @param reducedMotion whether to select the target without animation
     */
    public Transition {
        if (!Double.isFinite(from) || !Double.isFinite(to))
            throw new IllegalArgumentException("transition endpoints must be finite");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(easing, "easing");
        if (duration.isNegative()) throw new IllegalArgumentException("transition duration cannot be negative");
        try {
            duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("transition duration must fit in nanoseconds", overflow);
        }
    }
    /**
     * A normal-motion linear transition.
     *
     * @param from starting value, finite
     * @param to target value, finite
     * @param startedNanos start timestamp in the caller's clock domain
     * @param duration nonnegative duration fitting in nanoseconds
     * @return a normal-motion linear transition
     */
    public static Transition between(double from, double to, long startedNanos, Duration duration) {
        return new Transition(from, to, startedNanos, duration, Easing.LINEAR, false);
    }

    /**
     * Returns the same value with easing replaced.
     *
     * @param value the requested easing
     * @return the value with the requested easing
     */
    public Transition withEasing(Easing value) {
        return new Transition(from, to, startedNanos, duration, value, reducedMotion);
    }

    /**
     * Returns the same value with reduced motion replaced.
     *
     * @param value the requested reduced motion
     * @return the value with the requested reduced motion
     */
    public Transition withReducedMotion(boolean value) {
        return new Transition(from, to, startedNanos, duration, easing, value);
    }
    /**
     * Eased progress in [0, 1], with a backwards clock clamped to the start.
     *
     * @param nowNanos the caller-supplied monotonic time in nanoseconds
     * @return eased progress in [0, 1], with a backwards clock clamped to the start
     */
    public double progressAt(long nowNanos) {
        if (reducedMotion || duration.isZero()) return 1;
        double fraction = Math.min(1.0, (double) Spinner.elapsed(startedNanos, nowNanos) / duration.toNanos());
        return easing.apply(fraction);
    }
    /**
     * The displayed value, bounded by the finite endpoints even at extreme magnitudes.
     *
     * @param nowNanos the caller-supplied monotonic time in nanoseconds
     * @return the displayed value, bounded by the finite endpoints even at extreme magnitudes
     */
    public double valueAt(long nowNanos) {
        double progress = progressAt(nowNanos);
        if (progress <= 0) return from;
        if (progress >= 1) return to;
        double value = (1 - progress) * from + progress * to;
        return Math.max(Math.min(from, to), Math.min(Math.max(from, to), value));
    }
    /**
     * Whether further time can no longer change this transition's value.
     *
     * @param nowNanos the caller-supplied monotonic time in nanoseconds
     * @return {@code true} if further time can no longer change this transition's value; otherwise {@code false}
     */
    public boolean finishedAt(long nowNanos) {
        return from == to
                || reducedMotion
                || duration.isZero()
                || Spinner.elapsed(startedNanos, nowNanos) >= duration.toNanos();
    }
    /**
     * Changes the target without jumping from the value displayed at this timestamp.
     *
     * @param target the target value to animate toward
     * @param nowNanos the caller-supplied monotonic time in nanoseconds
     * @return a transition starting at the currently displayed value
     */
    public Transition retarget(double target, long nowNanos) {
        return new Transition(valueAt(nowNanos), target, nowNanos, duration, easing, reducedMotion);
    }
}
