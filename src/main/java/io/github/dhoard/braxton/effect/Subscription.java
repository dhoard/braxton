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

package io.github.dhoard.braxton.effect;

import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.ComponentPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An inert description of an ongoing source of messages.
 *
 * <p>The runtime reconciles subscriptions after every model commit. A descriptor's identity and
 * configuration decide whether the live resource is kept; the mapping function is always replaced
 * with the current one, so closures over stale models cannot be retained. Removing a subscription
 * closes it exactly once and ignores later emissions. A mapper that returns {@code null} declines
 * an emission: nothing is delivered to the owner.</p>
 *
 * <p>A mapper that throws is handled where it runs. An {@link Interval} or {@link Source} mapper
 * runs on a thread the runtime does not own, so its failure is reported through runtime diagnostics
 * and the source keeps running, because letting the throwable end a tick chain would leave a dead
 * declared subscription unreported. A {@link Resize} mapper runs on the runtime thread, inside the
 * run loop, so its failure ends the run with a {@link io.github.dhoard.braxton.mvu.ComponentFailure}
 * that reports the {@code SUBSCRIPTIONS} phase and the source's structural path.</p>
 *
 * @param <Msg> the component message type this source emits
 */
public sealed interface Subscription<Msg> {

    /**
     * Stable identity of this source.
     *
     * @return stable identity of this source
     */
    SubscriptionId id();

    /**
     * Configuration compared to decide whether the live resource can be reused.
     *
     * @return configuration compared to decide whether the live resource can be reused
     */
    Object configuration();

    /**
     * Re-types emitted messages, preserving null (declined) emissions without calling the mapper.
     *
     * @param <Msg2> the Msg2 value type
     * @param mapper the value-to-message mapper
     * @return re-types emitted messages, preserving null (declined) emissions without calling the mapper
     */
    <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper);

    /**
     * Prepends scope recursively without starting sources or evaluating message mappers.
     *
     * @param path the component scope path
     * @return prepends scope recursively without starting sources or evaluating message mappers
     */
    default Subscription<Msg> scoped(ComponentPath path) {
        return switch (this) {
            case None<Msg> none -> none;
            case Batch<Msg> batch ->
                new Batch<>(
                        batch.items().stream().map(item -> item.scoped(path)).toList());
            case Interval<Msg> interval ->
                new Interval<>(interval.id().scoped(path), interval.period(), interval.onTick());
            case Resize<Msg> resize -> new Resize<>(resize.id().scoped(path), resize.onResize());
            case Facts<Msg> facts -> new Facts<>(facts.id().scoped(path), facts.onFacts());
            case Source<Msg> source ->
                new Source<>(source.id().scoped(path), source.configuration(), source.start(), source.mapper());
        };
    }

    /**
     * No source.
     *
     * @param <Msg> the component message type a source would have emitted
     */
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
            Objects.requireNonNull(mapper, "mapper");
            return new None<>();
        }
    }

    /**
     * Several independent sources, reconciled as a set.
     *
     * @param <Msg> the component message type these sources emit
     * @param items the sources to reconcile, in declaration order
     */
    record Batch<Msg>(List<Subscription<Msg>> items) implements Subscription<Msg> {

        private static final SubscriptionId ID = SubscriptionId.of("batch");

        /**
         * Rejects a null list of sources.
         */
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
            Objects.requireNonNull(mapper, "mapper");
            List<Subscription<Msg2>> mapped = new ArrayList<>(items.size());
            for (Subscription<Msg> item : items) {
                mapped.add(item.map(mapper));
            }
            return new Batch<>(mapped);
        }
    }

    /**
     * Emits a message after every {@code period}; the payload is the emission time in nanos.
     *
     * <p>The period is a monotonic duration, not a wall-clock deadline: it keeps its cadence across a
     * session that released its terminal for a child process or a suspend. What pauses with the
     * terminal is publishing - a tick that elapses while somebody else owns the screen is recorded
     * once per interval, and a resume delivers one tick for each interval that had one, so an
     * application sees its period continue rather than a burst of queued ticks. A {@link Source} keeps
     * emitting: its values come from code the session does not schedule, and dropping one would lose a
     * fact the application never hears about again.</p>
     *
     * @param <Msg> the component message type this source emits
     * @param id the source identity, scoped by the owning mount
     * @param period the interval between emissions; must be positive and fit in nanoseconds
     * @param onTick maps the emission time in nanoseconds to a message, or to null to decline it
     */
    record Interval<Msg>(SubscriptionId id, Duration period, Function<Long, Msg> onTick) implements Subscription<Msg> {

        /**
         * Rejects a null identity, mapper or a non-positive period that does not fit in nanoseconds.
         */
        public Interval {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("interval period must be positive");
            }
            try {
                period.toNanos();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("interval period must fit in nanoseconds: " + period, overflow);
            }
            Objects.requireNonNull(onTick, "onTick");
        }

        @Override
        public Object configuration() {
            return period;
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Interval<>(id, period, time -> mapMessage(onTick.apply(time), mapper));
        }
    }

    /**
     * Emits a message whenever the terminal size changes.
     *
     * @param <Msg> the component message type this source emits
     * @param id the source identity, scoped by the owning mount
     * @param onResize maps the measured size to a message, or to null to decline it
     */
    record Resize<Msg>(SubscriptionId id, Function<Size, Msg> onResize) implements Subscription<Msg> {

        /**
         * Rejects a null identity or mapper.
         */
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
            Objects.requireNonNull(mapper, "mapper");
            return new Resize<>(id, size -> mapMessage(onResize.apply(size), mapper));
        }
    }

    /**
     * Emits the session's terminal facts when they start and whenever they change.
     *
     * <p>A color profile or a reported background color is a property of the session, not of any
     * component, so this is how a component learns one it did not ask for. The current facts arrive
     * once when the source starts, which is also the only emission before anything changes; a
     * declared source that stays declared is not re-emitted for an unchanged fact.</p>
     *
     * @param <Msg> the component message type this source emits
     * @param id the source identity, scoped by the owning mount
     * @param onFacts maps the facts to a message, or to null to decline them
     */
    record Facts<Msg>(SubscriptionId id, Function<TerminalFacts, Msg> onFacts) implements Subscription<Msg> {

        /**
         * Rejects a null identity or mapper.
         */
        public Facts {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(onFacts, "onFacts");
        }

        @Override
        public Object configuration() {
            return "terminal-facts";
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Facts<>(id, facts -> mapMessage(onFacts.apply(facts), mapper));
        }
    }

    /**
     * An application-supplied source.
     *
     * <p>{@code start} receives an emitter and returns the resource to close on removal. Emitted
     * values are mapped to messages on the thread that emits - the source's own thread, or the
     * timer worker for an interval - and the message is then enqueued for the owner thread. The
     * mapper must therefore be pure: it must not read or mutate the model, which only the owner
     * thread may touch. {@code configuration} must compare equal for an unchanged live source.
     * If {@code start} throws, later emissions through that emitter are ignored.</p>
     *
     * <p>Emitting from {@code start} itself runs on the owner thread, where an envelope never waits
     * for the queue to drain: a burst larger than the mailbox capacity fails the run with a mailbox
     * overflow instead of waiting for the consumer that is already blocked in {@code start}.</p>
     *
     * @param <Msg> the component message type this source emits
     * @param id the source identity, scoped by the owning mount
     * @param configuration compared with the live source's configuration to decide reuse
     * @param start receives the emitter and returns the resource to close on removal
     * @param mapper maps one emitted value to a message, or to null to decline it
     */
    record Source<Msg>(
            SubscriptionId id,
            Object configuration,
            Function<Consumer<Object>, AutoCloseable> start,
            Function<Object, Msg> mapper)
            implements Subscription<Msg> {

        /**
         * Rejects a null identity, configuration, start function or mapper.
         */
        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Subscription<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Source<>(id, configuration, start, value -> mapMessage(this.mapper.apply(value), mapper));
        }
    }

    private static <Msg, Msg2> Msg2 mapMessage(Msg message, Function<? super Msg, ? extends Msg2> mapper) {
        return message == null ? null : mapper.apply(message);
    }

    /**
     * Returns an empty subscription declaration.
     *
     * @param <Msg> the component message type this source emits
     * @return an empty subscription declaration
     */
    static <Msg> Subscription<Msg> none() {
        return new None<>();
    }

    /**
     * Combines subscription descriptors in declaration order.
     *
     * @param <Msg> the component message type this source emits
     * @param items the subscription descriptors in declaration order
     * @return the combined subscription descriptor
     */
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

    /**
     * Declares a scoped periodic source of monotonic-time messages.
     *
     * @param <Msg> the component message type this source emits
     * @param id the local identity of the resource
     * @param period the positive interval period
     * @param onTick the monotonic-time-to-message mapper
     * @return declares a scoped periodic source of monotonic-time messages
     */
    static <Msg> Subscription<Msg> interval(SubscriptionId id, Duration period, Function<Long, Msg> onTick) {
        return new Interval<>(id, period, onTick);
    }

    /**
     * Declares a scoped source of terminal-size messages.
     *
     * @param <Msg> the component message type this source emits
     * @param id the local identity of the resource
     * @param onResize the size-to-message mapper
     * @return declares a scoped source of terminal-size messages
     */
    static <Msg> Subscription<Msg> resize(SubscriptionId id, Function<Size, Msg> onResize) {
        return new Resize<>(id, onResize);
    }

    /**
     * Emits the session's terminal facts when they start and whenever they change.
     *
     * @param <Msg> the component message type
     * @param id the source identity, scoped by the owning mount
     * @param onFacts maps the facts to a message
     * @return the source
     */
    static <Msg> Subscription<Msg> facts(SubscriptionId id, Function<TerminalFacts, Msg> onFacts) {
        return new Facts<>(id, onFacts);
    }

    /**
     * Declares a scoped custom source whose resource is closed when removed.
     *
     * @param <Msg> the component message type this source emits
     * @param id the local identity of the resource
     * @param configuration the value whose equality identifies an unchanged source
     * @param start the source starter; its returned resource is closed when the source stops
     * @param mapper the value-to-message mapper
     * @return declares a scoped custom source whose resource is closed when removed
     */
    static <Msg> Subscription<Msg> source(
            SubscriptionId id,
            Object configuration,
            Function<Consumer<Object>, AutoCloseable> start,
            Function<Object, Msg> mapper) {
        return new Source<>(id, configuration, start, mapper);
    }

    /**
     * Flattens a subscription tree into its leaves in declaration order. Duplicate ids are a
     * configuration error and rejected, not deduplicated: two sources cannot share one identity.
     *
     * @param <Msg> the component message type this source emits
     * @param subscription the subscription descriptor
     * @return the leaf descriptors in declaration order
     */
    static <Msg> List<Subscription<Msg>> leaves(Subscription<Msg> subscription) {
        List<Subscription<Msg>> leaves = new ArrayList<>();
        collect(subscription, leaves);
        java.util.Set<SubscriptionId> seen = new java.util.HashSet<>();
        for (Subscription<Msg> leaf : leaves) {
            if (!seen.add(leaf.id())) {
                throw new IllegalArgumentException("duplicate subscription id: " + leaf.id());
            }
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
