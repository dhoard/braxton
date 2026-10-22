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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;

@Timeout(10)
class SubscriptionInFlightRemovalTest {

    @Test
    void removingAnExternalSourceDiscardsItsInFlightMapping() throws Exception {
        verify(false, "remove");
    }

    @Test
    void replacingAnExternalSourceDiscardsItsInFlightMapping() throws Exception {
        verify(false, "replace");
    }

    @Test
    void closingAnExternalSourceDiscardsItsInFlightMapping() throws Exception {
        verify(false, "close");
    }

    @Test
    void removingAnIntervalDiscardsItsInFlightMapping() throws Exception {
        verify(true, "remove");
    }

    @Test
    void replacingAnIntervalDiscardsItsInFlightMapping() throws Exception {
        verify(true, "replace");
    }

    @Test
    void closingAnIntervalDiscardsItsInFlightMapping() throws Exception {
        verify(true, "close");
    }

    @Test
    void refreshingAnExternalMapperKeepsTheResourceAndItsInFlightEmission() throws Exception {
        verify(false, "refresh");
    }

    @Test
    void refreshingAnIntervalMapperKeepsTheResourceAndItsInFlightEmission() throws Exception {
        verify(true, "refresh");
    }

    private static void verify(boolean interval, String action) throws Exception {
        Mailbox mailbox = new Mailbox(8);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        AtomicInteger cancellations = new AtomicInteger();
        Timers timers = (delay, task) -> {
            tick.set(task);
            return cancellations::incrementAndGet;
        };
        SubscriptionManager<String> manager = new SubscriptionManager<>(mailbox, timers, 4,
                message -> { });
        SubscriptionId id = SubscriptionId.of("source");
        AtomicReference<Consumer<Object>> callback = new AtomicReference<>();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger mappings = new AtomicInteger();
        CountDownLatch mapping = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Function<Object, String> mapper = value -> {
            mappings.incrementAndGet();
            mapping.countDown();
            release.join();
            return "obsolete";
        };
        Subscription<String> source = interval
                ? Subscription.interval(id, Duration.ofSeconds(1), mapper::apply)
                : Subscription.source(id, "old", sink -> {
                    callback.set(sink);
                    return closes::incrementAndGet;
                }, mapper);
        manager.reconcile(source);
        Runnable emit = interval ? tick.get() : () -> callback.get().accept("value");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(emit);
            try {
                assertThat(mapping.await(3, TimeUnit.SECONDS)).isTrue();
                switch (action) {
                    case "remove" -> manager.reconcile(Subscription.none());
                    case "replace" -> manager.reconcile(Subscription.source(id, "replacement",
                            sink -> { sink.accept("fresh"); return () -> { }; },
                            Object::toString));
                    case "refresh" -> manager.reconcile(interval
                            ? Subscription.interval(id, Duration.ofSeconds(1), time -> "fresh")
                            : Subscription.source(id, "old", sink -> {
                                throw new AssertionError("unchanged source must not restart");
                            }, value -> "fresh"));
                    case "close" -> manager.closeAll();
                    default -> throw new AssertionError(action);
                }
            } finally {
                release.complete(null);
            }
            inFlight.get(3, TimeUnit.SECONDS);
            if (action.equals("replace")) {
                assertThat(mailbox.poll(0)).isEqualTo(new Mailbox.AppMessage("fresh"));
            }
            if (action.equals("refresh")) {
                assertThat(mailbox.poll(0)).as("a mapper refresh does not remove the source")
                        .isEqualTo(new Mailbox.AppMessage("obsolete"));
                (interval ? tick.get() : emit).run();
                assertThat(mailbox.poll(0)).isEqualTo(new Mailbox.AppMessage("fresh"));
                assertThat(interval ? cancellations : closes).hasValue(0);
                return;
            }
            assertThat(mailbox.poll(0)).as("a removed source cannot publish a late mapping")
                    .isNull();
            emit.run();
            assertThat(mappings).as("late callbacks must not invoke application mapping")
                    .hasValue(1);
            assertThat(mailbox.size()).isZero();
            assertThat(interval ? cancellations : closes).hasValue(1);
            if (interval) {
                assertThat(tick.get()).as("a removed tick must not schedule a successor")
                        .isSameAs(emit);
            }
        } finally {
            release.complete(null);
            manager.closeAll();
            mailbox.close();
        }
    }
}
