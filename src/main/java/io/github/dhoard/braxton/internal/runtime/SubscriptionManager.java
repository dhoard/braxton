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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reconciles ongoing sources against the committed model.
 *
 * <p>A live source is identified by its id and configuration. When those are unchanged the resource
 * is kept and only the message mapper is replaced, so a new closure can never leak a stale model
 * into a running timer. A changed or removed source is closed exactly once, and a stale emission is
 * ignored.</p>
 * @param <Msg> the component message type sources emit
 */
public final class SubscriptionManager<Msg> {

    private abstract static class Live {
        final SubscriptionId id;
        final Object configuration;

        Live(SubscriptionId id, Object configuration) {
            this.id = id;
            this.configuration = configuration;
        }

        abstract void close();
    }

    private static final class IntervalLive<Msg> extends Live {
        final AtomicReference<Function<Long, Msg>> mapper;
        final Timers timers;
        final long periodNanos;
        final java.util.function.BiConsumer<Object, java.util.function.BooleanSupplier> emitter;
        final Consumer<String> diagnostics;
        final java.util.function.BooleanSupplier paused;
        /** Whether a period elapsed while the session had released its terminal. */
        final java.util.concurrent.atomic.AtomicBoolean tickPending = new java.util.concurrent.atomic.AtomicBoolean();

        private final Object timerLock = new Object();
        private long scheduleGeneration;
        private Timers.Handle handle;

        IntervalLive(
                Timers timers,
                SubscriptionId id,
                Duration period,
                Function<Long, Msg> mapper,
                java.util.function.BiConsumer<Object, java.util.function.BooleanSupplier> emitter,
                Consumer<String> diagnostics,
                java.util.function.BooleanSupplier paused) {
            super(id, period);
            this.timers = timers;
            this.mapper = new AtomicReference<>(mapper);
            this.periodNanos = Math.max(1L, period.toNanos());
            this.emitter = emitter;
            this.diagnostics = diagnostics;
            this.paused = paused;
            scheduleNext();
        }

        private void scheduleNext() {
            long generation;
            synchronized (timerLock) {
                if (mapper.get() == null) {
                    return;
                }
                generation = ++scheduleGeneration;
            }
            Timers.Handle scheduled = timers.schedule(periodNanos, () -> {
                if (mapper.get() == null) {
                    return;
                }
                if (paused.getAsBoolean()) {
                    // The period is a monotonic duration, so the chain keeps ticking; what it does
                    // not do is publish while somebody else owns the terminal. One flag records
                    // that a tick elapsed, so resume delivers one tick rather than a burst.
                    tickPending.set(true);
                } else {
                    tick();
                }
                if (mapper.get() != null) {
                    scheduleNext();
                }
            });
            synchronized (timerLock) {
                // Removal can finish while schedule is returning. A callback can also run and
                // schedule its successor before its own handle returns; that older registration
                // must not overwrite the successor that close needs to cancel.
                if (mapper.get() != null && generation == scheduleGeneration) {
                    handle = scheduled;
                    return;
                }
            }
            scheduled.cancel();
        }

        /** Maps one elapsed period and publishes it, containing a throwing mapper. */
        private void tick() {
            Function<Long, Msg> current = mapper.get();
            if (current == null) {
                return;
            }
            try {
                Msg message = current.apply(System.nanoTime());
                // Mapping runs on the source thread and can outlive removal,
                // so the source's lifetime is rechecked before publishing the
                // mapped value, not only before invoking application code. A
                // mapper refresh alone keeps this source live.
                if (message != null && mapper.get() != null) {
                    emitter.accept(message, () -> mapper.get() != null);
                }
            } catch (RuntimeException failure) {
                // A mapper is application code. Letting the failure escape
                // ends this tick chain while reconcile still keeps the
                // resource, which would leave a declared subscription
                // permanently dead and unreported, so the failure is reported
                // and the interval keeps ticking.
                diagnostics.accept("subscription " + id + " mapper threw "
                        + failure.getClass().getSimpleName());
            }
        }

        /** Claims the tick that elapsed while the session was released, if there was one. */
        boolean takePendingTick() {
            return tickPending.getAndSet(false);
        }

        @Override
        void close() {
            Timers.Handle scheduled;
            synchronized (timerLock) {
                mapper.set(null);
                scheduled = handle;
                handle = null;
            }
            if (scheduled != null) {
                scheduled.cancel();
            }
        }
    }

    private static final class FactsLive<Msg> extends Live {
        final AtomicReference<Function<TerminalFacts, Msg>> mapper;

        FactsLive(SubscriptionId id, Function<TerminalFacts, Msg> mapper) {
            super(id, "terminal-facts");
            this.mapper = new AtomicReference<>(mapper);
        }

        @Override
        void close() {
            mapper.set(null);
        }
    }

    private static final class ResizeLive<Msg> extends Live {
        final AtomicReference<Function<Size, Msg>> mapper;

        ResizeLive(SubscriptionId id, Function<Size, Msg> mapper) {
            super(id, "resize");
            this.mapper = new AtomicReference<>(mapper);
        }

        @Override
        void close() {
            mapper.set(null);
        }
    }

    private static final class ExternalLive<Msg> extends Live {
        final AtomicReference<Function<Object, Msg>> mapper;
        final AutoCloseable resource;
        final Consumer<String> diagnostics;

        ExternalLive(
                SubscriptionId id,
                Object configuration,
                AtomicReference<Function<Object, Msg>> mapper,
                AutoCloseable resource,
                Consumer<String> diagnostics) {
            super(id, configuration);
            this.mapper = mapper;
            this.resource = resource;
            this.diagnostics = diagnostics;
        }

        @Override
        void close() {
            mapper.set(null);
            try {
                resource.close();
            } catch (Exception failure) {
                diagnostics.accept("subscription " + id + " close failed: "
                        + failure.getClass().getSimpleName());
            }
        }
    }

    private ScopeManager scopes;
    private java.util.function.Supplier<TerminalFacts> facts = () -> null;

    public void bindScopes(ScopeManager scopes) {
        this.scopes = scopes;
    }

    /**
     * Binds the session facts a {@link Subscription.Facts} source publishes when it starts.
     *
     * @param facts the current facts, or a supplier that returns null before the session has any
     */
    public void bindFacts(java.util.function.Supplier<TerminalFacts> facts) {
        this.facts = facts == null ? () -> null : facts;
    }

    private ScopeManager.Origin origin(SubscriptionId id) {
        return scopes == null ? null : scopes.capture(id.path());
    }

    /** Invalidates removed scopes before any replacement sources start. */
    public void closeScope(ComponentPath path) {
        List<Live> removed = new ArrayList<>();
        synchronized (lock) {
            var iterator = live.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().path().startsWith(path)) {
                    removed.add(entry.getValue());
                    iterator.remove();
                }
            }
        }
        for (Live source : removed) {
            source.close();
        }
    }

    private final Mailbox mailbox;
    private final Timers timers;
    private final int maxSubscriptions;
    private final Consumer<String> diagnostics;
    private final Map<SubscriptionId, Live> live = new java.util.LinkedHashMap<>();
    private final Object lock = new Object();
    /** Whether the session has released its terminal, so interval ticks are coalesced. */
    private volatile boolean paused;

    private boolean closed;
    private Thread owner = Thread.currentThread();

    public SubscriptionManager(Mailbox mailbox, Timers timers, int maxSubscriptions, Consumer<String> diagnostics) {
        this.mailbox = mailbox;
        this.timers = timers;
        this.maxSubscriptions = Math.max(0, maxSubscriptions);
        this.diagnostics = diagnostics == null ? message -> {} : diagnostics;
    }

    /** Marks the thread allowed to enqueue without waiting for itself. */
    public void bindOwner(Thread thread) {
        this.owner = thread;
    }

    /** Applies a new subscription description, keeping unchanged live resources. */
    public void reconcile(Subscription<Msg> subscription) {
        List<Subscription<Msg>> leaves = Subscription.leaves(subscription);
        if (leaves.size() > maxSubscriptions) {
            throw new IllegalArgumentException("too many subscriptions: " + leaves.size() + " > " + maxSubscriptions);
        }
        List<Live> toClose = new ArrayList<>();
        Throwable rejected = null;
        synchronized (lock) {
            if (closed) {
                // Shutdown already closed every source; a late reconcile must not resurrect one.
                return;
            }
            Map<SubscriptionId, Live> next = new HashMap<>();
            List<Live> started = new ArrayList<>();
            // Mapper refreshes are deferred until every leaf has started: a later failure must
            // leave the kept sources exactly as they were, including their mappers.
            Map<Live, Subscription<Msg>> refreshed = new java.util.LinkedHashMap<>();
            try {
                for (Subscription<Msg> leaf : leaves) {
                    Live existing = live.get(leaf.id());
                    if (existing != null
                            && sameKind(existing, leaf)
                            && existing.configuration.equals(leaf.configuration())) {
                        refreshed.put(existing, leaf);
                        next.put(leaf.id(), existing);
                    } else {
                        if (existing != null) {
                            toClose.add(existing);
                        }
                        Live created = start(leaf);
                        started.add(created);
                        next.put(leaf.id(), created);
                    }
                }
            } catch (RuntimeException | Error failure) {
                // A descriptor could not be started. Nothing is committed yet, so keep the
                // previously live sources (and their replacement candidates) untouched and close
                // exactly the sources this call created; otherwise a started resource would never
                // be reachable by closeAll and its emissions would stay live.
                toClose.clear();
                toClose.addAll(started);
                rejected = failure;
            }
            if (rejected == null) {
                for (Map.Entry<Live, Subscription<Msg>> entry : refreshed.entrySet()) {
                    refreshMapper(entry.getKey(), entry.getValue());
                }
                for (Map.Entry<SubscriptionId, Live> entry : live.entrySet()) {
                    if (!next.containsKey(entry.getKey())) {
                        toClose.add(entry.getValue());
                    }
                }
                live.clear();
                live.putAll(next);
            }
        }
        for (Live removed : toClose) {
            removed.close();
        }
        if (rejected instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (rejected instanceof Error error) {
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshMapper(Live existing, Subscription<Msg> leaf) {
        if (existing instanceof IntervalLive<?> interval && leaf instanceof Subscription.Interval<Msg> source) {
            ((IntervalLive<Msg>) interval).mapper.set(source.onTick());
        } else if (existing instanceof ResizeLive<?> resize && leaf instanceof Subscription.Resize<Msg> source) {
            ((ResizeLive<Msg>) resize).mapper.set(source.onResize());
        } else if (existing instanceof FactsLive<?> facts && leaf instanceof Subscription.Facts<Msg> source) {
            ((FactsLive<Msg>) facts).mapper.set(source.onFacts());
        } else if (existing instanceof ExternalLive<?> external && leaf instanceof Subscription.Source<Msg> source) {
            ((ExternalLive<Msg>) external).mapper.set(source.mapper());
        }
    }

    /**
     * Whether an existing live resource implements the same kind of source as {@code leaf}. The
     * configuration is an arbitrary value, so two different kinds can carry an equal one (a resize
     * source's marker is the string a caller could use as an external source's configuration, for
     * instance); such a leaf must replace the resource, not reuse it.
     */
    private static boolean sameKind(Live existing, Subscription<?> leaf) {
        return switch (leaf) {
            case Subscription.Interval<?> interval -> existing instanceof IntervalLive<?>;
            case Subscription.Resize<?> resize -> existing instanceof ResizeLive<?>;
            case Subscription.Facts<?> facts -> existing instanceof FactsLive<?>;
            case Subscription.Source<?> source -> existing instanceof ExternalLive<?>;
            case Subscription.Batch<?> batch -> false;
            case Subscription.None<?> none -> false;
        };
    }

    private Live start(Subscription<Msg> leaf) {
        ScopeManager.Origin origin = origin(leaf.id());
        if (leaf instanceof Subscription.Interval<Msg> interval) {
            return new IntervalLive<>(
                    timers,
                    interval.id(),
                    interval.period(),
                    interval.onTick(),
                    (message, valid) -> enqueue(message, origin, valid),
                    diagnostics,
                    () -> paused);
        }
        if (leaf instanceof Subscription.Resize<Msg> resize) {
            return new ResizeLive<>(resize.id(), resize.onResize());
        }
        if (leaf instanceof Subscription.Facts<Msg> declared) {
            FactsLive<Msg> live = new FactsLive<>(declared.id(), declared.onFacts());
            // The current facts are what a source that just started is about to know; a session that
            // has none yet emits nothing until it does.
            TerminalFacts current = facts.get();
            if (current != null) {
                Msg message = mapFacts(live, declared.onFacts(), current);
                if (message != null) {
                    enqueue(message, origin, () -> live.mapper.get() != null);
                }
            }
            return live;
        }
        if (leaf instanceof Subscription.Source<Msg> source) {
            AtomicReference<Function<Object, Msg>> mapper = new AtomicReference<>(source.mapper());
            Consumer<Object> emitter = value -> {
                Function<Object, Msg> current = mapper.get();
                if (current != null) {
                    Msg message = current.apply(value);
                    // Removal/failed startup may have invalidated this callback while its
                    // application mapper was running. A captured function is not liveness.
                    if (mapper.get() != null) {
                        enqueue(message, origin, () -> mapper.get() != null);
                    }
                }
            };
            try {
                AutoCloseable resource = source.start().apply(emitter);
                return new ExternalLive<>(source.id(), source.configuration(), mapper, resource, diagnostics);
            } catch (RuntimeException | Error failure) {
                // start may have handed this emitter to another thread before failing. No Live
                // was returned for reconcile to close, so invalidate that callback here instead.
                mapper.set(null);
                throw failure;
            }
        }
        throw new IllegalArgumentException("unsupported subscription: " + leaf);
    }

    /**
     * Stops publishing interval ticks while the session has released its terminal.
     *
     * <p>The periods keep running: an interval is a monotonic duration, not a wall-clock deadline, so
     * a stopped chain would report a different elapsed time than the clock did. What pauses is
     * publishing: a tick that elapses now is recorded once per interval, and {@link #resume()}
     * publishes one tick for each interval that had one. An application therefore sees the period it
     * asked for continue across a handoff instead of a burst of queued ticks. External sources keep
     * emitting: their values are produced by code the session does not schedule, and dropping one
     * would lose a fact the application never hears about again.</p>
     */
    public void pause() {
        paused = true;
    }

    /**
     * Resumes publishing interval ticks and delivers one tick for each period that elapsed.
     *
     * <p>Called on the owner thread after the terminal has been taken back, so the coalesced ticks are
     * enqueued in the same order as any other owner-side delivery.</p>
     */
    public void resume() {
        paused = false;
        List<Live> targets;
        synchronized (lock) {
            targets = new ArrayList<>(live.values());
        }
        for (Live source : targets) {
            if (source instanceof IntervalLive<?> interval) {
                deliverPendingTick(interval);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void deliverPendingTick(IntervalLive<?> interval) {
        if (!interval.takePendingTick()) {
            return;
        }
        ((IntervalLive<Msg>) interval).tick();
    }

    /** Called on the owner thread when a resize is measured. */
    public void notifyResize(Size size) {
        List<Live> targets;
        synchronized (lock) {
            targets = new ArrayList<>(live.values());
        }
        for (Live source : targets) {
            if (source instanceof ResizeLive<?> resize) {
                Function<Size, Msg> mapper = resizeMapper(resize);
                if (mapper != null) {
                    Msg message = mapResize(source, mapper, size);
                    // A null result declines this size, exactly as it does on the interval and
                    // source paths (enqueue drops it): a null is not a message, and enqueuing one
                    // would hand the owner's update a value outside the application's message type.
                    if (message != null) {
                        mailbox.enqueueFromOwner(
                                new Mailbox.AppMessage(message, origin(source.id), () -> resize.mapper.get() != null));
                    }
                }
            }
        }
    }

    /**
     * Publishes a changed set of session facts to every live facts source.
     *
     * <p>Called on the owner thread when a fact changes - a colour profile upgraded by a capability
     * reply, or a background colour reported for the first time - so a component that did not ask for
     * the change still learns it.</p>
     *
     * @param current the facts in force
     */
    public void notifyFacts(TerminalFacts current) {
        if (current == null) {
            return;
        }
        List<Live> targets;
        synchronized (lock) {
            targets = new ArrayList<>(live.values());
        }
        for (Live source : targets) {
            if (!(source instanceof FactsLive<?> live)) {
                continue;
            }
            Function<TerminalFacts, Msg> mapper = mapperOf(live);
            if (mapper == null) {
                continue;
            }
            Msg message = mapFacts(live, mapper, current);
            if (message != null) {
                mailbox.enqueueFromOwner(
                        new Mailbox.AppMessage(message, origin(source.id), () -> live.mapper.get() != null));
            }
        }
    }

    /**
     * Maps one set of facts for the source that declared it, containing a throwing mapper.
     *
     * <p>Facts are mapped on the owner thread, so a throw is an application failure on a synchronous
     * path and is reported with the source's structural path and the {@code SUBSCRIPTIONS} phase, as
     * a resize mapper's is.</p>
     */
    private Msg mapFacts(Live source, Function<TerminalFacts, Msg> mapper, TerminalFacts current) {
        try {
            return mapper.apply(current);
        } catch (RuntimeException failure) {
            throw new ComponentFailure(
                    source.id.path(),
                    ComponentFailure.Phase.SUBSCRIPTIONS,
                    "facts mapper threw " + failure.getClass().getSimpleName(),
                    failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Function<TerminalFacts, Msg> mapperOf(FactsLive<?> live) {
        return ((FactsLive<Msg>) live).mapper.get();
    }

    /**
     * Maps one measured size for the source that declared it.
     *
     * <p>Interval and source mappers run on a thread the runtime does not own, so a failure there
     * is contained and reported: letting it escape would end a tick chain while the source stayed
     * declared, leaving a dead subscription nobody hears about. A resize mapper runs on the owner
     * thread, inside the run loop, so a throw is an application failure on a synchronous path - the
     * runtime fails the run as it does for {@code update} and {@code view}, and reports the failure
     * with the source's structural path and the {@code SUBSCRIPTIONS} phase.</p>
     */
    private Msg mapResize(Live source, Function<Size, Msg> mapper, Size size) {
        try {
            return mapper.apply(size);
        } catch (RuntimeException failure) {
            throw new ComponentFailure(
                    source.id.path(),
                    ComponentFailure.Phase.SUBSCRIPTIONS,
                    "resize mapper threw " + failure.getClass().getSimpleName(),
                    failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Function<Size, Msg> resizeMapper(Live live) {
        return ((ResizeLive<Msg>) live).mapper.get();
    }

    /**
     * Enqueues one emission for the owner, waiting while the owner is busy.
     *
     * <p>A full mailbox means the owner is still working, not that the emission is disposable; a
     * fixed timeout would silently drop a tick or a source value. The wait ends when the mailbox
     * is closed, which is how shutdown releases an emitter thread.</p>
     *
     * <p>An emission generated <em>on</em> the owner thread - a source that emits from its own
     * {@code start} function, for instance - never waits: the owner is the only consumer, so waiting
     * would wait for itself and wedge the run. An owner-side overflow is fatal by design, exactly as
     * for a resize or an effect delivered on the owner thread.</p>
     */
    private void enqueue(Object message, ScopeManager.Origin origin, java.util.function.BooleanSupplier valid) {
        if (message == null) {
            return;
        }
        Mailbox.Envelope envelope = new Mailbox.AppMessage(message, origin, valid);
        if (Thread.currentThread() == owner) {
            mailbox.enqueueFromOwner(envelope);
            return;
        }
        while (true) {
            try {
                if (mailbox.enqueue(envelope, 100L)) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (mailbox.isClosed()) {
                return;
            }
        }
    }

    /** Closes every live source once. */
    public void closeAll() {
        List<Live> all;
        synchronized (lock) {
            closed = true;
            all = new ArrayList<>(live.values());
            live.clear();
        }
        for (Live source : all) {
            source.close();
        }
    }
}
