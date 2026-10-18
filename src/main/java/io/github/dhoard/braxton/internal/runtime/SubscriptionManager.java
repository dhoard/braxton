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

package io.github.dhoard.braxton.internal.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Size;

/**
 * Reconciles ongoing sources against the committed model.
 *
 * <p>A live source is identified by its id and configuration. When those are unchanged the resource
 * is kept and only the message mapper is replaced, so a new closure can never leak a stale model
 * into a running timer. A changed or removed source is closed exactly once, and a stale emission is
 * ignored.</p>
 */
public final class SubscriptionManager<Msg> {

    private abstract static class Live {
        final SubscriptionId id;
        final Object configuration;
        long epoch;

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
        final Consumer<Object> emitter;
        final Consumer<String> diagnostics;
        Timers.Handle handle;

        IntervalLive(Timers timers, SubscriptionId id, Duration period,
                Function<Long, Msg> mapper, long epoch, Consumer<Object> emitter,
                Consumer<String> diagnostics) {
            super(id, period);
            this.timers = timers;
            this.mapper = new AtomicReference<>(mapper);
            this.periodNanos = Math.max(1L, period.toNanos());
            this.emitter = emitter;
            this.diagnostics = diagnostics;
            this.epoch = epoch;
            scheduleNext();
        }

        private void scheduleNext() {
            handle = timers.schedule(periodNanos, () -> {
                Function<Long, Msg> current = mapper.get();
                if (current == null) {
                    return;
                }
                try {
                    Msg message = current.apply(System.nanoTime());
                    if (message != null) {
                        emitter.accept(message);
                    }
                } catch (RuntimeException failure) {
                    // A mapper is application code. Letting the failure escape ends this tick
                    // chain while reconcile still keeps the resource, which would leave a
                    // declared subscription permanently dead and unreported, so the failure is
                    // reported and the interval keeps ticking.
                    diagnostics.accept("subscription " + id + " mapper threw "
                            + failure.getClass().getSimpleName());
                }
                if (mapper.get() != null) {
                    scheduleNext();
                }
            });
        }

        @Override
        void close() {
            mapper.set(null);
            if (handle != null) {
                handle.cancel();
            }
        }
    }

    private static final class ResizeLive<Msg> extends Live {
        final AtomicReference<Function<Size, Msg>> mapper;

        ResizeLive(SubscriptionId id, Function<Size, Msg> mapper, long epoch) {
            super(id, "resize");
            this.mapper = new AtomicReference<>(mapper);
            this.epoch = epoch;
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

        ExternalLive(SubscriptionId id, Object configuration,
                AtomicReference<Function<Object, Msg>> mapper, AutoCloseable resource, long epoch,
                Consumer<String> diagnostics) {
            super(id, configuration);
            this.mapper = mapper;
            this.resource = resource;
            this.epoch = epoch;
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

    private final Mailbox mailbox;
    private final Timers timers;
    private final int maxSubscriptions;
    private final Consumer<String> diagnostics;
    private final Map<SubscriptionId, Live> live = new HashMap<>();
    private final Object lock = new Object();
    private long nextEpoch = 1L;
    private boolean closed;

    public SubscriptionManager(Mailbox mailbox, Timers timers, int maxSubscriptions,
            Consumer<String> diagnostics) {
        this.mailbox = mailbox;
        this.timers = timers;
        this.maxSubscriptions = Math.max(0, maxSubscriptions);
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
    }

    /** Applies a new subscription description, keeping unchanged live resources. */
    public void reconcile(Subscription<Msg> subscription) {
        List<Subscription<Msg>> leaves = Subscription.leaves(subscription);
        if (leaves.size() > maxSubscriptions) {
            throw new IllegalArgumentException("too many subscriptions: " + leaves.size()
                    + " > " + maxSubscriptions);
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
                    if (existing != null && sameKind(existing, leaf)
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
            removed.epoch = -1L;
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
        if (existing instanceof IntervalLive<?> interval
                && leaf instanceof Subscription.Interval<Msg> source) {
            ((IntervalLive<Msg>) interval).mapper.set(source.onTick());
        } else if (existing instanceof ResizeLive<?> resize
                && leaf instanceof Subscription.Resize<Msg> source) {
            ((ResizeLive<Msg>) resize).mapper.set(source.onResize());
        } else if (existing instanceof ExternalLive<?> external
                && leaf instanceof Subscription.Source<Msg> source) {
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
            case Subscription.Source<?> source -> existing instanceof ExternalLive<?>;
            case Subscription.Batch<?> batch -> false;
            case Subscription.None<?> none -> false;
        };
    }

    private Live start(Subscription<Msg> leaf) {
        long epoch = nextEpoch++;
        if (leaf instanceof Subscription.Interval<Msg> interval) {
            return new IntervalLive<>(timers, interval.id(), interval.period(), interval.onTick(), epoch,
                    this::enqueue, diagnostics);
        }
        if (leaf instanceof Subscription.Resize<Msg> resize) {
            return new ResizeLive<>(resize.id(), resize.onResize(), epoch);
        }
        if (leaf instanceof Subscription.Source<Msg> source) {
            AtomicReference<Function<Object, Msg>> mapper = new AtomicReference<>(source.mapper());
            Consumer<Object> emitter = value -> {
                Function<Object, Msg> current = mapper.get();
                if (current != null) {
                    enqueue(current.apply(value));
                }
            };
            AutoCloseable resource = source.start().apply(emitter);
            return new ExternalLive<>(source.id(), source.configuration(), mapper, resource, epoch,
                    diagnostics);
        }
        throw new IllegalArgumentException("unsupported subscription: " + leaf);
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
                    mailbox.enqueueFromOwner(new Mailbox.AppMessage(mapper.apply(size)));
                }
            }
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
     */
    private void enqueue(Object message) {
        if (message == null) {
            return;
        }
        while (true) {
            try {
                if (mailbox.enqueue(new Mailbox.AppMessage(message), 100L)) {
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
            source.epoch = -1L;
            source.close();
        }
    }
}
