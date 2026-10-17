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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.dhoard.braxton.layout.Size;

/**
 * An inert description of an ongoing source of messages.
 *
 * <p>The runtime reconciles subscriptions after every model commit. A descriptor's identity and
 * configuration decide whether the live resource is kept; the mapping function is always replaced
 * with the current one, so closures over stale models cannot be retained. Removing a subscription
 * closes it exactly once and ignores later emissions.</p>
 */
public sealed interface Subscription<Msg> {

    /** Stable identity of this source. */
    SubscriptionId id();

    /** Configuration compared to decide whether the live resource can be reused. */
    Object configuration();

    <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper);

    /** No source. */
    record None<Msg>() implements Subscription<Msg> {

        private static final SubscriptionId ID = SubscriptionId.of("none");

        @Override
        public SubscriptionId id() {
            return ID;
        }

        @Override
        public Object configuration() {
            return "none";
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new None<>();
        }
    }

    /** Several independent sources, reconciled as a set. */
    record Batch<Msg>(List<Subscription<Msg>> items) implements Subscription<Msg> {

        private static final SubscriptionId ID = SubscriptionId.of("batch");

        public Batch {
            items = List.copyOf(items);
        }

        @Override
        public SubscriptionId id() {
            return ID;
        }

        @Override
        public Object configuration() {
            return items.size();
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            List<Subscription<Msg2>> mapped = new ArrayList<>(items.size());
            for (Subscription<Msg> item : items) {
                mapped.add(item.map(mapper));
            }
            return new Batch<>(mapped);
        }
    }

    /** Emits a message after every {@code period}; the payload is the emission time in nanos. */
    record Interval<Msg>(SubscriptionId id, Duration period, Function<Long, Msg> onTick)
            implements Subscription<Msg> {

        public Interval {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("interval period must be positive");
            }
            Objects.requireNonNull(onTick, "onTick");
        }

        @Override
        public Object configuration() {
            return period;
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Interval<>(id, period, onTick.andThen(mapper));
        }
    }

    /** Emits a message whenever the terminal size changes. */
    record Resize<Msg>(SubscriptionId id, Function<Size, Msg> onResize) implements Subscription<Msg> {

        public Resize {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(onResize, "onResize");
        }

        @Override
        public Object configuration() {
            return "resize";
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Resize<>(id, onResize.andThen(mapper));
        }
    }

    /**
     * An application-supplied source.
     *
     * <p>{@code start} receives an emitter and returns the resource to close on removal. Emitted
     * values are mapped to messages; mapping runs on the owner thread. {@code configuration} must
     * compare equal for an unchanged live source.</p>
     */
    record Source<Msg>(SubscriptionId id, Object configuration,
            Function<Consumer<Object>, AutoCloseable> start, Function<Object, Msg> mapper)
            implements Subscription<Msg> {

        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Source<>(id, configuration, start, this.mapper.andThen(mapper));
        }
    }

    static <Msg> Subscription<Msg> none() {
        return new None<>();
    }

    @SafeVarargs
    static <Msg> Subscription<Msg> batch(Subscription<Msg>... items) {
        List<Subscription<Msg>> flat = new ArrayList<>();
        for (Subscription<Msg> item : items) {
            if (item instanceof None<Msg>) {
                continue;
            }
            if (item instanceof Batch<Msg> batch) {
                flat.addAll(batch.items());
            } else {
                flat.add(item);
            }
        }
        if (flat.isEmpty()) {
            return new None<>();
        }
        return flat.size() == 1 ? flat.get(0) : new Batch<>(flat);
    }

    static <Msg> Subscription<Msg> interval(SubscriptionId id, Duration period,
            Function<Long, Msg> onTick) {
        return new Interval<>(id, period, onTick);
    }

    static <Msg> Subscription<Msg> resize(SubscriptionId id, Function<Size, Msg> onResize) {
        return new Resize<>(id, onResize);
    }

    static <Msg> Subscription<Msg> source(SubscriptionId id, Object configuration,
            Function<Consumer<Object>, AutoCloseable> start, Function<Object, Msg> mapper) {
        return new Source<>(id, configuration, start, mapper);
    }

    /** Flattens a subscription tree into its distinct leaves (deduplicating by id). */
    static <Msg> List<Subscription<Msg>> leaves(Subscription<Msg> subscription) {
        List<Subscription<Msg>> leaves = new ArrayList<>();
        collect(subscription, leaves);
        List<SubscriptionId> seen = new ArrayList<>();
        for (Subscription<Msg> leaf : leaves) {
            if (seen.contains(leaf.id())) {
                throw new IllegalArgumentException("duplicate subscription id: " + leaf.id());
            }
            seen.add(leaf.id());
        }
        return List.copyOf(leaves);
    }

    private static <Msg> void collect(Subscription<Msg> subscription, List<Subscription<Msg>> out) {
        if (subscription instanceof None<Msg>) {
            return;
        }
        if (subscription instanceof Batch<Msg> batch) {
            for (Subscription<Msg> item : batch.items()) {
                collect(item, out);
            }
            return;
        }
        out.add(subscription);
    }
}
