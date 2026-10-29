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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;

/** Timer registration can finish after removal or after its own callback schedules a successor. */
@Timeout(10)
class SubscriptionTimerLifecycleTest {

    @ParameterizedTest
    @ValueSource(strings = {"remove", "replace", "close"})
    void closingAnIntervalCancelsAHandleStillBeingScheduled(String action) throws Exception {
        HeldTimers timers = new HeldTimers(2);
        Mailbox mailbox = new Mailbox(8);
        SubscriptionManager<String> manager = new SubscriptionManager<>(mailbox, timers, 4,
                message -> { });
        SubscriptionId id = SubscriptionId.of("tick");
        AtomicInteger mappings = new AtomicInteger();
        manager.reconcile(Subscription.interval(id, Duration.ofHours(1), time -> {
            mappings.incrementAndGet();
            return null;
        }));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var ticking = executor.submit(timers.calls.get(0).task);
            try {
                assertThat(timers.holding.await(3, TimeUnit.SECONDS)).isTrue();
                switch (action) {
                    case "remove" -> manager.reconcile(Subscription.none());
                    case "replace" -> manager.reconcile(Subscription.interval(id,
                            Duration.ofHours(2), time -> "replacement"));
                    case "close" -> manager.closeAll();
                    default -> throw new AssertionError(action);
                }
            } finally {
                timers.release.complete(null);
            }
            ticking.get(3, TimeUnit.SECONDS);
            assertThat(timers.calls.get(1).cancellations)
                    .as("a handle returned after closure must not remain queued for an hour")
                    .hasValue(1);
            int scheduled = timers.calls.size();
            timers.calls.get(1).task.run();
            assertThat(mappings).as("a closed timer must not map another tick").hasValue(1);
            assertThat(timers.calls).hasSize(scheduled);
            assertThat(mailbox.size()).isZero();
            if (action.equals("replace")) {
                assertThat(timers.calls.get(2).cancellations).hasValue(0);
                timers.calls.get(2).task.run();
                assertThat(mailbox.poll(0)).isEqualTo(new Mailbox.AppMessage("replacement"));
            }
            manager.closeAll();
            manager.closeAll();
            assertThat(timers.calls.get(0).cancellations).hasValue(1);
            assertThat(timers.calls.get(1).cancellations).hasValue(1);
            if (action.equals("replace")) {
                assertThat(timers.calls.get(3).cancellations).hasValue(1);
            }
        } finally {
            timers.release.complete(null);
            manager.closeAll();
            mailbox.close();
        }
    }

    @Test
    void refreshingAMapperKeepsAHandleThatIsStillBeingScheduled() throws Exception {
        HeldTimers timers = new HeldTimers(2);
        Mailbox mailbox = new Mailbox(8);
        SubscriptionManager<String> manager = new SubscriptionManager<>(mailbox, timers, 4,
                message -> { });
        SubscriptionId id = SubscriptionId.of("tick");
        manager.reconcile(Subscription.interval(id, Duration.ofHours(1), time -> null));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var ticking = executor.submit(timers.calls.get(0).task);
            try {
                assertThat(timers.holding.await(3, TimeUnit.SECONDS)).isTrue();
                manager.reconcile(Subscription.interval(id, Duration.ofHours(1), time -> "fresh"));
            } finally {
                timers.release.complete(null);
            }
            ticking.get(3, TimeUnit.SECONDS);
            assertThat(timers.calls).hasSize(2);
            assertThat(timers.calls.get(1).cancellations).hasValue(0);
            timers.calls.get(1).task.run();
            assertThat(mailbox.poll(0)).isEqualTo(new Mailbox.AppMessage("fresh"));
            manager.closeAll();
            assertThat(timers.calls.get(2).cancellations).hasValue(1);
        } finally {
            timers.release.complete(null);
            manager.closeAll();
            mailbox.close();
        }
    }

    @Test
    void anOlderRegistrationCannotOverwriteItsAlreadyScheduledSuccessor() throws Exception {
        HeldTimers timers = new HeldTimers(1);
        Mailbox mailbox = new Mailbox(8);
        SubscriptionManager<String> manager = new SubscriptionManager<>(mailbox, timers, 4,
                message -> { });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var starting = executor.submit(() -> manager.reconcile(Subscription.interval(
                    SubscriptionId.of("tick"), Duration.ofHours(1), time -> null)));
            try {
                assertThat(timers.holding.await(3, TimeUnit.SECONDS)).isTrue();
                // The scheduler can run the callback before schedule() returns its handle.
                timers.calls.get(0).task.run();
                assertThat(timers.calls).hasSize(2);
            } finally {
                timers.release.complete(null);
            }
            starting.get(3, TimeUnit.SECONDS);
            manager.closeAll();
            manager.closeAll();
            assertThat(timers.calls.get(1).cancellations)
                    .as("shutdown must cancel the successor, not its already-fired predecessor")
                    .hasValue(1);
            assertThat(timers.calls.get(0).cancellations).hasValue(1);
        } finally {
            timers.release.complete(null);
            manager.closeAll();
            mailbox.close();
        }
    }

    private static final class Pending {
        final Runnable task;
        final AtomicInteger cancellations = new AtomicInteger();

        Pending(Runnable task) {
            this.task = task;
        }
    }

    private static final class HeldTimers implements Timers {
        final List<Pending> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch holding = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        private final int heldCall;

        HeldTimers(int heldCall) {
            this.heldCall = heldCall;
        }

        @Override
        public Handle schedule(long delayNanos, Runnable task) {
            Pending pending = new Pending(task);
            calls.add(pending);
            if (calls.size() == heldCall) {
                holding.countDown();
                release.join();
            }
            return pending.cancellations::incrementAndGet;
        }
    }
}
