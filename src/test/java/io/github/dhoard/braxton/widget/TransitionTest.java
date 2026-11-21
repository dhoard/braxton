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

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TransitionTest {
    @Test
    void endpointsAndLinearMidpointFollowCallerTime() {
        Transition t = Transition.between(10, 30, 100, Duration.ofNanos(100));
        assertThat(t.valueAt(0)).isEqualTo(10);
        assertThat(t.valueAt(100)).isEqualTo(10);
        assertThat(t.valueAt(150)).isEqualTo(20);
        assertThat(t.valueAt(200)).isEqualTo(30);
        assertThat(t.valueAt(Long.MAX_VALUE)).isEqualTo(30);
        assertThat(t.finishedAt(199)).isFalse();
        assertThat(t.finishedAt(200)).isTrue();
        assertThat(Transition.between(2, 2, 100, Duration.ofDays(1)).finishedAt(0))
                .isTrue();
    }

    @Test
    void reducedMotionAndZeroDurationArriveWithoutTicks() {
        Transition t = Transition.between(0, 1, 100, Duration.ofSeconds(1)).withReducedMotion(true);
        assertThat(t.valueAt(Long.MIN_VALUE)).isEqualTo(1);
        assertThat(t.finishedAt(0)).isTrue();
        assertThat(t.withReducedMotion(false).valueAt(100)).isZero();
        assertThat(Transition.between(1, 4, 100, Duration.ZERO).valueAt(0)).isEqualTo(4);
    }

    @Test
    void retargetingStartsAtTheDisplayedValue() {
        Transition original = Transition.between(0, 100, 0, Duration.ofNanos(100));
        Transition next = original.retarget(0, 50);
        assertThat(next.valueAt(50)).isEqualTo(original.valueAt(50));
        assertThat(next.valueAt(100)).isEqualTo(25);
        assertThat(next.valueAt(150)).isZero();
        assertThat(original.to()).isEqualTo(100);
    }

    @Test
    void easingIsMonotonicBoundedAndFinishesExactly() {
        Random random = new Random(63);
        for (var easing : Transition.Easing.values())
            for (int run = 0; run < 100; run++) {
                double from = random.nextDouble() * 1000, to = from + random.nextDouble() * 1000;
                Transition t =
                        Transition.between(from, to, 0, Duration.ofNanos(100)).withEasing(easing);
                double previous = from;
                for (int now = 0; now <= 100; now++) {
                    double current = t.valueAt(now);
                    assertThat(current).isBetween(previous, to);
                    previous = current;
                }
                assertThat(t.valueAt(100)).isEqualTo(to);
            }
        assertThat(Transition.between(0, 1, 0, Duration.ofNanos(100))
                        .withEasing(Transition.Easing.EASE_OUT)
                        .valueAt(50))
                .isEqualTo(0.875);
    }

    @Test
    void extremeEndpointsAndClockGapsRemainFinite() {
        Transition t = Transition.between(-Double.MAX_VALUE, Double.MAX_VALUE, 0, Duration.ofNanos(100));
        assertThat(t.valueAt(50)).isZero();
        assertThat(t.valueAt(99)).isFinite();
        assertThat(Transition.between(0, 1, Long.MIN_VALUE, Duration.ofSeconds(1))
                        .valueAt(Long.MAX_VALUE))
                .isEqualTo(1);
        assertThat(Transition.between(0, 1, Long.MAX_VALUE, Duration.ofSeconds(1))
                        .valueAt(Long.MIN_VALUE))
                .isZero();
        assertThat(Transition.between(9, -9, 0, Duration.ofNanos(10)).valueAt(5))
                .isZero();
    }

    @Test
    void invalidEndpointsDurationsAndCurvesAreRejected() {
        for (double bad : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> Transition.between(bad, 1, 0, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Transition.between(0, bad, 0, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Transition.between(0, 1, 0, Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transition.between(0, 1, 0, Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transition.between(0, 1, 0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Transition.between(0, 1, 0, Duration.ZERO).withEasing(null))
                .isInstanceOf(NullPointerException.class);
    }
}
