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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Mapping a completion does not end the effect's cancellable lifetime. */
class EffectCompletionCancellationTest {

    private static final EffectId ID = EffectId.of("group-work");

    @Test
    void cancellationWhileSuccessIsMappedDiscardsTheResult() throws Exception {
        verify(Command.cancel(ID), 0);
    }

    @Test
    void namespaceCancellationWhileSuccessIsMappedDiscardsTheResult() throws Exception {
        verify(Command.cancelAll(), 0);
    }

    @Test
    void shutdownWhileSuccessIsMappedDiscardsTheResult() throws Exception {
        verify(null, 0);
    }

    @Test
    void replacementWhileSuccessIsMappedKeepsOnlyTheNewResult() throws Exception {
        verify(Command.task(ID, () -> "new", value -> value, failure -> "new-failure"), 0);
    }

    @Test
    void cancellationWhileFailureIsMappedDiscardsTheResult() throws Exception {
        verify(Command.cancel(ID), 1);
    }

    @Test
    void cancellationWhileMapperFailureIsMappedDiscardsTheResult() throws Exception {
        verify(Command.cancel(ID), 2);
    }

    private void verify(Command<String> invalidation, int failureMode) throws Exception {
        Mailbox mailbox = new Mailbox(8);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        RuntimeServices environment = new RuntimeServices(() -> 0L, task -> {
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    uncaught.set(failure);
                }
            });
            worker.set(thread);
            thread.start();
        }, new ManualTimers(), message -> { });
        EffectRunner<String> runner = new EffectRunner<>(mailbox, environment, null, 1);
        CountDownLatch mapping = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        Function<Object, String> blockedMapper = value -> {
            mapping.countDown();
            release.join();
            // Ignore cooperative interruption to test the epoch check, not just an interrupted
            // mailbox enqueue.
            Thread.interrupted();
            return "obsolete";
        };
        runner.interpret(Command.task(ID, () -> {
            if (failureMode == 1) {
                throw new IllegalStateException("work failed");
            }
            return "value";
        }, value -> {
            if (failureMode == 2) {
                throw new IllegalArgumentException("success mapper failed");
            }
            return blockedMapper.apply(value);
        }, failure -> blockedMapper.apply(failure)));
        Thread obsolete = worker.get();
        try {
            assertThat(mapping.await(3, TimeUnit.SECONDS)).as("completion mapper started").isTrue();
            if (invalidation == null) {
                runner.cancelAll();
            } else {
                runner.interpret(invalidation);
            }
            if (invalidation instanceof Command.Task<?>) {
                worker.get().join(3000);
                assertThat(worker.get().isAlive()).isFalse();
            }
        } finally {
            release.complete(null);
            obsolete.join(3000);
            runner.cancelAll();
        }
        assertThat(obsolete.isAlive()).isFalse();
        assertThat(uncaught.get()).isNull();
        if (invalidation instanceof Command.Task<?>) {
            assertThat(((Mailbox.AppMessage) mailbox.poll(0)).payload()).isEqualTo("new");
        }
        assertThat(mailbox.poll(0)).as("no result from the invalidated mapper").isNull();
        runner.interpret(Command.task(ID, () -> "fresh", value -> value, failure -> "failed"));
        worker.get().join(3000);
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(((Mailbox.AppMessage) mailbox.poll(0)).payload()).isEqualTo("fresh");
        assertThat(mailbox.size()).isZero();
    }
}
