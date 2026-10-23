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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.internal.runtime.JLineTestTerminal;

class SubscriptionStartFailureTest {

    @Test
    void aFailedStartInvalidatesTheEmitterItAlreadyHandedToTheSource() {
        assertFailedStartInvalidatesEmitter(new IllegalStateException("cannot start"));
    }

    @Test
    void anErrorDuringStartAlsoInvalidatesTheEmitter() {
        assertFailedStartInvalidatesEmitter(new AssertionError("cannot start"));
    }

    private static void assertFailedStartInvalidatesEmitter(Throwable failure) {
        Mailbox mailbox = new Mailbox(8);
        SubscriptionManager<String> manager = new SubscriptionManager<>(mailbox,
                new ManualTimers(), 4, message -> { });
        AtomicReference<Consumer<Object>> callback = new AtomicReference<>();
        AtomicInteger mapped = new AtomicInteger();
        Subscription<String> source = Subscription.source(SubscriptionId.of("failed"), "cfg",
                emitter -> {
                    callback.set(emitter);
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw (Error) failure;
                }, value -> {
                    mapped.incrementAndGet();
                    return "stale:" + value;
                });

        assertThatThrownBy(() -> manager.reconcile(source)).isSameAs(failure);
        callback.get().accept("after failure");
        manager.closeAll();
        callback.get().accept("after shutdown");

        assertThat(mapped).as("a failed source must not retain an active mapper").hasValue(0);
        assertThat(mailbox.size()).as("a source that never became live cannot emit").isZero();
    }

    @Test
    void aFailedReplacementKeepsOnlyTheOldSourceAndAllowsASuccessfulRetry() throws Exception {
        Mailbox mailbox = new Mailbox(8);
        SubscriptionManager<String> manager = new SubscriptionManager<>(mailbox,
                new ManualTimers(), 4, message -> { });
        SubscriptionId id = SubscriptionId.of("source");
        AtomicReference<Consumer<Object>> old = new AtomicReference<>();
        AtomicReference<Consumer<Object>> failed = new AtomicReference<>();
        AtomicReference<Consumer<Object>> replacement = new AtomicReference<>();
        AtomicInteger oldCloses = new AtomicInteger();
        AtomicInteger newCloses = new AtomicInteger();
        manager.reconcile(Subscription.source(id, "old", emitter -> {
            old.set(emitter);
            return oldCloses::incrementAndGet;
        }, value -> "old:" + value));
        IllegalStateException failure = new IllegalStateException("cannot replace");
        assertThatThrownBy(() -> manager.reconcile(Subscription.source(id, "new", emitter -> {
            failed.set(emitter);
            throw failure;
        }, value -> "failed:" + value))).isSameAs(failure);

        failed.get().accept("ignored");
        old.get().accept("kept");
        assertThat(oldCloses).hasValue(0);
        assertThat(mailbox.poll(0)).isEqualTo(new Mailbox.AppMessage("old:kept"));
        assertThat(mailbox.size()).isZero();

        manager.reconcile(Subscription.source(id, "new", emitter -> {
            replacement.set(emitter);
            return newCloses::incrementAndGet;
        }, value -> "new:" + value));
        old.get().accept("removed");
        failed.get().accept("still ignored");
        replacement.get().accept("current");
        assertThat(mailbox.poll(0)).isEqualTo(new Mailbox.AppMessage("new:current"));
        assertThat(mailbox.size()).isZero();
        manager.closeAll();
        manager.closeAll();
        replacement.get().accept("closed");
        assertThat(mailbox.size()).isZero();
        assertThat(oldCloses).hasValue(1);
        assertThat(newCloses).hasValue(1);
    }
}
