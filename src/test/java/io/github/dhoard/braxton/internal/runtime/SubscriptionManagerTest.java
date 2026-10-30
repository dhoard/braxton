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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Size;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Source reconciliation: identity, mapping refresh, epochs, and limits. */
class SubscriptionManagerTest {

    private final Mailbox mailbox = new Mailbox(32);
    private final ManualTimers timers = new ManualTimers();
    private final List<String> diagnostics = new ArrayList<>();
    private final SubscriptionManager<String> manager =
            new SubscriptionManager<>(mailbox, timers, 2, diagnostics::add);

    private String next() throws InterruptedException {
        Mailbox.Envelope envelope = mailbox.poll(20);
        assertThat(envelope).isInstanceOf(Mailbox.AppMessage.class);
        return (String) ((Mailbox.AppMessage) envelope).payload();
    }

    @Test
    void anIntervalEmitsOncePerPeriod() throws Exception {
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "tick"));
        timers.advance(99_000_000L);
        assertThat(mailbox.size()).isZero();
        timers.advance(1_000_000L);
        assertThat(next()).isEqualTo("tick");
        timers.advance(100_000_000L);
        assertThat(next()).isEqualTo("tick");
    }

    @Test
    void anIntervalMapperThatThrowsIsReportedAndTheIntervalKeepsTicking() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new IllegalStateException("boom");
                    }
                    return "tick-" + calls.get();
                }));
        timers.advance(100_000_000L);
        assertThat(next()).isEqualTo("tick-1");
        try {
            // A real scheduler swallows the throwable into the timer future; the manual clock
            // surfaces it here so the test can observe what the runtime did with the source.
            timers.advance(100_000_000L);
        } catch (RuntimeException swallowedByTheScheduler) {
            // Expected before the fix: the tick task died without rescheduling itself.
        }
        timers.advance(100_000_000L);
        assertThat(next()).as("a throwing mapper must not end a declared subscription")
                .isEqualTo("tick-3");
        assertThat(diagnostics).as("the mapper failure is reported, not silently swallowed")
                .anySatisfy(message -> assertThat(message).contains("tick")
                        .contains("IllegalStateException"));
    }

    @Test
    void unchangedIdentityKeepsTheResourceAndRefreshesOnlyTheMapper() throws Exception {
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "old"));
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "new"));
        assertThat(timers.pending()).isEqualTo(1);
        timers.advance(100_000_000L);
        assertThat(next()).isEqualTo("new");
    }

    @Test
    void changingConfigurationClosesTheOldSourceAndStartsANewOne() throws Exception {
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "old"));
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(200),
                time -> "new"));
        assertThat(timers.pending()).isEqualTo(1);
        timers.advance(200_000_000L);
        assertThat(next()).isEqualTo("new");
    }

    @Test
    void aDifferentKindOfSourceWithTheSameConfigurationIsRestarted() {
        List<String> events = new ArrayList<>();
        manager.reconcile(Subscription.resize(SubscriptionId.of("shared"), size -> "resize"));
        manager.reconcile(Subscription.source(SubscriptionId.of("shared"), "resize",
                emitter -> {
                    events.add("started");
                    return () -> events.add("closed");
                }, value -> "source"));
        assertThat(events).as("a source of another kind is not the live resize subscription")
                .containsExactly("started");
    }

    @Test
    void removingASourceStopsEmissions() throws Exception {
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "tick"));
        manager.reconcile(Subscription.none());
        timers.advance(500_000_000L);
        assertThat(mailbox.size()).isZero();
    }

    @Test
    void closeAllStopsAnIntervalFromTickingFurther() throws Exception {
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "tick"));
        timers.advance(100_000_000L);
        assertThat(next()).isEqualTo("tick");
        manager.closeAll();
        timers.advance(500_000_000L);
        assertThat(mailbox.size()).as("a closed interval must stop scheduling").isZero();
    }

    @Test
    void aReconcileThatCannotStartASourceLeavesTheKeptMapperUnchanged() throws Exception {
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                time -> "old"));
        Subscription<String> failing = Subscription.source(SubscriptionId.of("boom"), "cfg",
                emitter -> {
                    throw new IllegalStateException("cannot start");
                }, value -> "boom");
        assertThatThrownBy(() -> manager.reconcile(Subscription.batch(
                Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(100),
                        time -> "new"),
                failing))).isInstanceOf(IllegalStateException.class);
        timers.advance(100_000_000L);
        assertThat(next()).as("a failed reconcile commits nothing, not even a mapper refresh")
                .isEqualTo("old");
    }

    @Test
    void anEmissionIsNotDroppedWhenTheMailboxIsFull() throws Exception {
        Mailbox small = new Mailbox(1);
        SubscriptionManager<String> manager =
                new SubscriptionManager<>(small, timers, 2, diagnostics::add);
        small.enqueueFromOwner(new Mailbox.AppMessage("occupant"));
        manager.reconcile(Subscription.interval(SubscriptionId.of("tick"),
                Duration.ofMillis(100), time -> "tick"));
        Thread timer = Thread.ofVirtual().start(() -> timers.advance(100_000_000L));
        Thread.sleep(1_200L);
        assertThat(small.poll(2_000)).as("the queued envelope is still there")
                .isInstanceOf(Mailbox.AppMessage.class);
        assertThat(small.poll(2_000))
                .as("a tick is delivered once the owner makes room, not dropped after a timeout")
                .isInstanceOf(Mailbox.AppMessage.class);
        timer.join(2_000L);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aSourceThatEmitsOnTheOwnerThreadNeverWaitsForTheOwner() throws Exception {
        // The documented threading model: an envelope generated on the owner thread does not wait
        // for the owner, so a full mailbox there is a fatal overflow. A source that emits from its
        // own start function runs on that thread; waiting there would wait for the consumer itself
        // and wedge the run with no way to quit.
        Mailbox full = new Mailbox(1);
        SubscriptionManager<String> tight =
                new SubscriptionManager<>(full, timers, 2, diagnostics::add);
        full.enqueueFromOwner(new Mailbox.AppMessage("occupant"));
        AtomicInteger started = new AtomicInteger();
        Subscription<String> eager = Subscription.source(SubscriptionId.of("eager"), "config",
                emitter -> {
                    started.incrementAndGet();
                    emitter.accept("first");
                    return () -> { };
                },
                value -> "mapped:" + value);
        assertThatThrownBy(() -> tight.reconcile(eager))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mailbox overflow");
        assertThat(started).as("the start function ran on this thread").hasValue(1);
        assertThat(full.poll(20)).as("the occupant is still queued, nothing was dropped")
                .isInstanceOf(Mailbox.AppMessage.class);
        assertThat(full.size()).as("the emission that did not fit was not queued").isZero();

        // The rejected reconcile commits nothing, so the same descriptor starts again once there
        // is room rather than staying half-started.
        tight.reconcile(eager);
        assertThat(started).hasValue(2);
        Mailbox.Envelope delivered = full.poll(20);
        assertThat(delivered).isInstanceOf(Mailbox.AppMessage.class);
        assertThat(((Mailbox.AppMessage) delivered).payload()).isEqualTo("mapped:first");
        tight.closeAll();
    }

    @Test
    void resizeSourcesReceiveTheMeasuredSize() throws Exception {
        manager.reconcile(Subscription.resize(SubscriptionId.of("resize"),
                size -> "resized:" + size.cols()));
        manager.notifyResize(new Size(10, 40));
        assertThat(next()).isEqualTo("resized:40");
    }

    @Test
    void aResizeFansOutToEveryResizeSourceAndNotToIntervals() throws Exception {
        SubscriptionManager<String> wide = new SubscriptionManager<>(mailbox, timers, 4,
                diagnostics::add);
        wide.reconcile(Subscription.batch(
                Subscription.resize(SubscriptionId.of("first"), size -> "first:" + size.rows()),
                Subscription.resize(SubscriptionId.of("second"), size -> "second:" + size.rows()),
                Subscription.interval(SubscriptionId.of("tick"), Duration.ofSeconds(5),
                        time -> "tick")));
        wide.notifyResize(new Size(7, 20));
        assertThat(next()).isEqualTo("first:7");
        assertThat(next()).isEqualTo("second:7");
        assertThat(mailbox.size()).as("an interval does not receive resize events").isZero();
        wide.notifyResize(new Size(8, 20));
        assertThat(next()).isEqualTo("first:8");
        assertThat(next()).isEqualTo("second:8");
    }

    @Test
    void aResizeMapperThatReturnsNullEmitsNoMessage() throws Exception {
        // A mapper may decline a size it does not care about, exactly like an interval or source
        // mapper: null is not a message value, so nothing is enqueued for the owner. Enqueuing it
        // would hand Component.update a null instead of a value from the application's message
        // type.
        manager.reconcile(
                Subscription.resize(
                        SubscriptionId.of("resize"),
                        size -> size.rows() > 2 ? "big:" + size.rows() : null));
        manager.notifyResize(new Size(1, 4));
        assertThat(mailbox.poll(0)).as("a null mapper result is not a message").isNull();
        manager.notifyResize(new Size(3, 4));
        assertThat(next()).isEqualTo("big:3");
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void anIntervalMapperRunsOnTheTimerWorkerNotTheOwner() throws Exception {
        // The documented threading model: mapping happens where the value is emitted, so a mapper
        // must be pure and must never touch the model.
        java.util.concurrent.atomic.AtomicReference<String> mapperThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        Mailbox ownBox = new Mailbox(4);
        SubscriptionManager<String> real = new SubscriptionManager<>(ownBox,
                io.github.dhoard.braxton.internal.runtime.Timers.system(), 2, message -> { });
        String owner = Thread.currentThread().getName();
        real.reconcile(Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(1),
                time -> {
                    mapperThread.set(Thread.currentThread().getName());
                    ran.countDown();
                    return "tick";
                }));
        try {
            assertThat(ran.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            real.closeAll();
        }
        assertThat(mapperThread.get()).isNotEqualTo(owner).startsWith("braxton-timers");
    }

    @Test
    void anExternalSourceIsStartedOnceAndClosedOnce() {
        List<String> events = new ArrayList<>();
        Subscription<String> source = Subscription.source(SubscriptionId.of("external"), "config",
                emitter -> {
                    events.add("start");
                    emitter.accept("item");
                    return () -> events.add("close");
                }, value -> "mapped:" + value);
        manager.reconcile(source);
        manager.reconcile(source);
        manager.closeAll();
        assertThat(events).containsExactly("start", "close");
    }

    @Test
    void reconcileAfterCloseAllDoesNotStartNewSources() {
        manager.closeAll();
        manager.reconcile(Subscription.interval(SubscriptionId.of("late"),
                Duration.ofMillis(100), time -> "late"));
        assertThat(timers.pending()).isZero();
    }

    @Test
    void duplicateIdsAndOversizedSetsAreConfigurationErrors() {
        assertThatThrownBy(() -> Subscription.leaves(Subscription.batch(
                Subscription.interval(SubscriptionId.of("same"), Duration.ofMillis(1), t -> "a"),
                Subscription.interval(SubscriptionId.of("same"), Duration.ofMillis(1), t -> "b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.reconcile(Subscription.batch(
                Subscription.interval(SubscriptionId.of("a"), Duration.ofMillis(1), t -> "a"),
                Subscription.interval(SubscriptionId.of("b"), Duration.ofMillis(1), t -> "b"),
                Subscription.interval(SubscriptionId.of("c"), Duration.ofMillis(1), t -> "c"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many subscriptions");
    }

    @Test
    void aReconcileThatCannotStartASourceClosesTheSourcesItAlreadyStarted() {
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> manager.reconcile(Subscription.batch(
                Subscription.source(SubscriptionId.of("first"), "one",
                        emitter -> closes::incrementAndGet, value -> "first"),
                Subscription.source(SubscriptionId.of("second"), "two", emitter -> {
                    throw new IllegalStateException("cannot start");
                }, value -> "second"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot start");
        manager.closeAll();
        assertThat(closes).hasValue(1);
    }

    @Test
    void mappingChangesTheChildIdentityWithoutBreakingReconciliation() throws Exception {
        record Child(String name) {
        }
        SubscriptionManager<Child> childManager =
                new SubscriptionManager<>(mailbox, timers, 4, diagnostics::add);
        childManager.reconcile(
                Subscription.interval(
                        SubscriptionId.of("tick")
                                .scoped(
                                        io.github.dhoard.braxton.mvu.ComponentPath.ROOT.append(
                                                io.github.dhoard.braxton.mvu.ComponentId.of("c"))),
                        Duration.ofMillis(10),
                        time -> new Child("tick")));
        timers.advance(10_000_000L);
        Mailbox.AppMessage message = (Mailbox.AppMessage) mailbox.poll(20);
        assertThat(message.payload()).isEqualTo(new Child("tick"));
    }

    @Test
    void aMappedSubscriptionKeepsItsIdentityAndMapsEveryEmission() throws Exception {
        // Subscription.map is the composition primitive for child features: it re-types emissions
        // without changing the id or the configuration that decides whether the live resource is
        // kept.
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        var emitter = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Object>>();
        Subscription<String> source = Subscription.<String>source(SubscriptionId.of("child"), "cfg",
                sink -> {
                    starts.incrementAndGet();
                    emitter.set(sink);
                    return () -> { };
                }, Object::toString).map(value -> "parent-" + value);
        manager.reconcile(source);
        emitter.get().accept("a");
        assertThat(next()).isEqualTo("parent-a");
        manager.reconcile(Subscription.<String>interval(SubscriptionId.of("tick"),
                Duration.ofMillis(100), time -> "tick").map(value -> "parent-" + value));
        timers.advance(100_000_000L);
        assertThat(next()).as("an interval keeps its period and maps its tick")
                .isEqualTo("parent-tick");
        manager.reconcile(Subscription.<String>resize(SubscriptionId.of("resize"),
                size -> "resize").map(value -> "parent-" + value));
        manager.notifyResize(new Size(3, 4));
        assertThat(next()).isEqualTo("parent-resize");
        assertThat(starts).as("mapping does not restart the source").hasValue(1);
        assertThat(Subscription.<String>none().map(value -> "x").id())
                .isEqualTo(Subscription.none().id());
    }
}
