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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.testkit.FakeTerminal;

/** Source reconciliation: identity, mapping refresh, epochs, and limits. */
class SubscriptionManagerTest {

    private final Mailbox mailbox = new Mailbox(32);
    private final FakeTerminal.ManualTimers timers = new FakeTerminal.ManualTimers();
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
    void resizeSourcesReceiveTheMeasuredSize() throws Exception {
        manager.reconcile(Subscription.resize(SubscriptionId.of("resize"),
                size -> "resized:" + size.cols()));
        manager.notifyResize(new Size(10, 40));
        assertThat(next()).isEqualTo("resized:40");
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
        childManager.reconcile(Subscription.interval(SubscriptionId.of("c").child("tick"),
                Duration.ofMillis(10), time -> new Child("tick")));
        timers.advance(10_000_000L);
        Mailbox.AppMessage message = (Mailbox.AppMessage) mailbox.poll(20);
        assertThat(message.payload()).isEqualTo(new Child("tick"));
    }
}
