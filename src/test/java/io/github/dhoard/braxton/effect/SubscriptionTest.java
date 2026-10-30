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

package io.github.dhoard.braxton.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Subscription descriptors reject input the runtime cannot schedule. */
class SubscriptionTest {

    @Test
    void anIntervalLongerThanItsNanosecondScheduleIsRejectedAtConstruction() {
        assertThatThrownBy(() -> Subscription.interval(SubscriptionId.of("tick"),
                Duration.ofDays(1_000_000), time -> "tick"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
    }

    @Test
    void anIntervalThatFitsInNanosecondsIsAccepted() {
        Subscription<String> subscription = Subscription.interval(SubscriptionId.of("tick"),
                Duration.ofDays(100_000), time -> "tick");
        assertThat(subscription).isInstanceOf(Subscription.Interval.class);
        assertThat(((Subscription.Interval<String>) subscription).period())
                .isEqualTo(Duration.ofDays(100_000));
    }

    @Test
    void mappedSubscriptionsPreserveDeclinedEmissionsAtEveryBoundary() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var subscription = Subscription.batch(
                Subscription.<Integer>interval(SubscriptionId.of("tick"), Duration.ofSeconds(1),
                        time -> time == 0 ? null : time.intValue()),
                Subscription.<Integer>resize(SubscriptionId.of("size"),
                        size -> size.rows() == 0 ? null : size.rows()),
                Subscription.<Integer>source(SubscriptionId.of("source"), "config",
                        emit -> () -> {}, value -> (Integer) value));
        var mapped = subscription.<String>map(value -> {
            calls.incrementAndGet();
            return value == 2 ? null : "parent:" + value;
        }).map(value -> {
            calls.incrementAndGet();
            return "root:" + value;
        });
        for (var leaf : Subscription.leaves(mapped)) {
            java.util.function.Function<Integer, String> emit = switch (leaf) {
                case Subscription.Interval<String> interval -> value ->
                        interval.onTick().apply(value == null ? 0L : value.longValue());
                case Subscription.Resize<String> resize -> value ->
                        resize.onResize().apply(new io.github.dhoard.braxton.layout.Size(
                                value == null ? 0 : value, 1));
                case Subscription.Source<String> source -> source.mapper()::apply;
                default -> throw new AssertionError("expected a leaf");
            };
            calls.set(0);
            assertThat(emit.apply(null)).isNull();
            assertThat(calls).hasValue(0);
            assertThat(emit.apply(2)).isNull();
            assertThat(calls).hasValue(1);
            assertThat(emit.apply(1)).isEqualTo("root:parent:1");
            assertThat(calls).hasValue(3);
        }
    }
}
