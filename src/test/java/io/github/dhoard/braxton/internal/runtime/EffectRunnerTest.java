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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.app.RuntimeEnvironment;
import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.testkit.FakeTerminal;

/** Commands are inert descriptions with exactly-once, epoch-guarded interpretation. */
class EffectRunnerTest {

    private final Mailbox mailbox = new Mailbox(16);
    private final FakeTerminal.ManualTimers timers = new FakeTerminal.ManualTimers();
    private final List<String> writes = new ArrayList<>();
    private final RuntimeEnvironment environment = new RuntimeEnvironment(
            () -> 0L, Runnable::run, timers, message -> { });
    private final ClipboardService clipboard = new ClipboardService(
            ClipboardConfig.defaults(), new TerminalEnvironment(Map.of("TERM", "dumb")),
            writes::add);
    private final EffectRunner<String> runner =
            new EffectRunner<>(mailbox, environment, clipboard, 2);

    EffectRunnerTest() {
        runner.bindOwner(Thread.currentThread());
    }

    private String nextMessage() throws InterruptedException {
        Mailbox.Envelope envelope = mailbox.poll(20);
        assertThat(envelope).isInstanceOf(Mailbox.AppMessage.class);
        return (String) ((Mailbox.AppMessage) envelope).payload();
    }

    @Test
    void aDelayDeliversItsMessageOnce() throws Exception {
        runner.interpret(Command.delay(EffectId.of("a"), Duration.ofSeconds(1), "tick", "rejected"));
        timers.advance(999_000_000L);
        assertThat(mailbox.size()).isZero();
        timers.advance(1_000_000L);
        assertThat(nextMessage()).isEqualTo("tick");
        timers.advance(5_000_000_000L);
        assertThat(mailbox.size()).isZero();
    }

    @Test
    void cancellingAnEffectSuppressesDelivery() throws Exception {
        EffectId id = EffectId.of("a");
        runner.interpret(Command.delay(id, Duration.ofSeconds(1), "tick", "rejected"));
        runner.interpret(Command.cancel(id));
        timers.advance(2_000_000_000L);
        assertThat(mailbox.size()).isZero();
    }

    @Test
    void replacingAnIdInvalidatesTheOlderEpoch() throws Exception {
        EffectId id = EffectId.of("a");
        runner.interpret(Command.delay(id, Duration.ofSeconds(1), "first", "rejected"));
        runner.interpret(Command.delay(id, Duration.ofSeconds(2), "second", "rejected"));
        timers.advance(1_000_000_000L);
        assertThat(mailbox.size()).isZero();
        timers.advance(1_000_000_000L);
        assertThat(nextMessage()).isEqualTo("second");
    }

    @Test
    void exceedingTheEffectQuotaMapsToTheRejectionMessage() throws Exception {
        runner.interpret(Command.delay(EffectId.of("a"), Duration.ofSeconds(1), "a", "rejected-a"));
        runner.interpret(Command.delay(EffectId.of("b"), Duration.ofSeconds(1), "b", "rejected-b"));
        runner.interpret(Command.delay(EffectId.of("c"), Duration.ofSeconds(1), "c", "rejected-c"));
        assertThat(nextMessage()).isEqualTo("rejected-c");
    }

    @Test
    void replacingAnIdAtQuotaIsAllowed() throws Exception {
        EffectId id = EffectId.of("a");
        runner.interpret(Command.delay(id, Duration.ofSeconds(1), "first", "rejected-a"));
        runner.interpret(Command.delay(EffectId.of("b"), Duration.ofSeconds(1), "b", "rejected-b"));
        runner.interpret(Command.delay(id, Duration.ofSeconds(2), "second", "rejected-a"));
        timers.advance(1_000_000_000L);
        assertThat(nextMessage()).isEqualTo("b");
        timers.advance(1_000_000_000L);
        assertThat(nextMessage()).isEqualTo("second");
    }

    @Test
    void taskSuccessAndFailureBecomeMessages() throws Exception {
        runner.interpret(Command.task(EffectId.of("ok"), () -> 7, value -> "ok:" + value,
                failure -> "fail"));
        assertThat(nextMessage()).isEqualTo("ok:7");

        runner.interpret(Command.task(EffectId.of("bad"), () -> {
            throw new IllegalStateException("boom");
        }, value -> "ok", failure -> "fail:" + failure.getMessage()));
        assertThat(nextMessage()).isEqualTo("fail:boom");
    }

    @Test
    void taskRejectionByTheExecutorMapsToFailure() throws Exception {
        RuntimeEnvironment rejecting = new RuntimeEnvironment(() -> 0L, task -> {
            throw new java.util.concurrent.RejectedExecutionException("busy");
        }, timers, message -> { });
        EffectRunner<String> limited = new EffectRunner<>(mailbox, rejecting, clipboard, 4);
        limited.bindOwner(Thread.currentThread());
        limited.interpret(Command.task(EffectId.of("t"), () -> 1, value -> "ok",
                failure -> "rejected:" + failure.getClass().getSimpleName()));
        assertThat(nextMessage()).isEqualTo("rejected:RejectedExecutionException");
    }

    @Test
    void cancellingARunningTaskInterruptsItsBodyAndDropsItsLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RuntimeEnvironment async = new RuntimeEnvironment(() -> 0L, executor, timers,
                    message -> { });
            EffectRunner<String> asyncRunner = new EffectRunner<>(mailbox, async, clipboard, 4);
            asyncRunner.bindOwner(Thread.currentThread());
            asyncRunner.interpret(Command.task(EffectId.of("work"), () -> {
                entered.countDown();
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException stop) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "done";
            }, value -> "success:" + value, failure -> "failure:" + failure.getClass().getSimpleName()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            asyncRunner.interpret(Command.cancel(EffectId.of("work")));
            assertThat(interrupted.await(5, TimeUnit.SECONDS))
                    .as("cancelling a running task must interrupt its body").isTrue();
            Thread.sleep(100L);
            assertThat(mailbox.size()).as("a cancelled task's result must not arrive").isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aThrowingSuccessMapperIsReportedThroughTheFailureMapper() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RuntimeEnvironment async = new RuntimeEnvironment(() -> 0L, executor, timers,
                    message -> { });
            EffectRunner<String> asyncRunner = new EffectRunner<>(mailbox, async, clipboard, 4);
            asyncRunner.bindOwner(Thread.currentThread());
            asyncRunner.interpret(Command.task(EffectId.of("work"), () -> "value",
                    value -> {
                        throw new IllegalStateException("mapper broke");
                    },
                    failure -> "failure:" + failure.getMessage()));
            String message = nextMessage();
            assertThat(message).isEqualTo("failure:mapper broke");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aThrowingFailureMapperIsContainedAndReportedAsADiagnostic() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        RuntimeEnvironment reporting = new RuntimeEnvironment(() -> 0L, Runnable::run, timers,
                diagnostics::add);
        EffectRunner<String> runner = new EffectRunner<>(mailbox, reporting, clipboard, 4);
        runner.bindOwner(Thread.currentThread());
        runner.interpret(Command.task(EffectId.of("work"), () -> {
            throw new IllegalStateException("work broke");
        }, value -> "ok", failure -> {
            throw new IllegalStateException("failure mapper broke");
        }));
        assertThat(mailbox.size()).as("no message can be produced").isZero();
        assertThat(diagnostics).anySatisfy(message ->
                assertThat(message).contains("effect work").contains("failure mapper"));
    }

    @Test
    void copyReportsTheTruthfulResultAndWritesThroughTheSession() throws Exception {
        ClipboardConfig config = ClipboardConfig.builder()
                .tmuxStrategy(io.github.dhoard.braxton.clipboard.TmuxStrategy.DIRECT).build();
        ClipboardService direct = new ClipboardService(config,
                new TerminalEnvironment(Map.of("TERM", "xterm-256color")), writes::add);
        EffectRunner<String> copyRunner = new EffectRunner<>(mailbox, environment, direct, 4);
        copyRunner.bindOwner(Thread.currentThread());
        copyRunner.interpret(Command.copy("hello", result -> result.path() + ":"
                + result.systemWriteRequested() + ":" + result.text()));
        assertThat(nextMessage()).isEqualTo(ClipboardPath.OSC52_DIRECT + ":true:hello");
        assertThat(writes).hasSize(1).allSatisfy(write ->
                assertThat(write).startsWith("\u001B]52;c;"));
    }

    @Test
    void readingTheClipboardReturnsTheInternalBuffer() throws Exception {
        ClipboardResult stored = clipboard.copy("payload");
        assertThat(stored.storedInternally()).isTrue();
        runner.interpret(Command.readClipboard(text -> "read:" + text));
        assertThat(nextMessage()).isEqualTo("read:payload");
    }

    @Test
    void aBatchStopsAfterQuitAndQuitIsRecorded() {
        runner.interpret(Command.batch(
                Command.delay(EffectId.of("a"), Duration.ofSeconds(1), "a", "r"),
                Command.quit(3),
                Command.delay(EffectId.of("b"), Duration.ofSeconds(1), "b", "r")));
        assertThat(runner.quitRequested()).isTrue();
        assertThat(runner.quitStatus()).isEqualTo(3);
        assertThat(timers.pending()).isEqualTo(1);
    }

    @Test
    void cancelNamespaceInvalidatesEveryChildEffect() throws Exception {
        EffectRunner<String> runner = new EffectRunner<>(mailbox, environment, clipboard, 8);
        runner.bindOwner(Thread.currentThread());
        runner.interpret(Command.batch(
                Command.delay(EffectId.of("harness/a"), Duration.ofSeconds(1), "a", "r"),
                Command.delay(EffectId.of("harness/b"), Duration.ofSeconds(1), "b", "r"),
                Command.delay(EffectId.of("other/c"), Duration.ofSeconds(1), "c", "r")));
        runner.interpret(Command.cancelNamespace("harness"));
        timers.advance(2_000_000_000L);
        assertThat(nextMessage()).isEqualTo("c");
        assertThat(mailbox.size()).isZero();
    }

    @Test
    void timersWithNoPendingWorkReportZero() {
        assertThat(timers.pending()).isZero();
        assertThat(Timers.system()).isNotNull();
    }
}
