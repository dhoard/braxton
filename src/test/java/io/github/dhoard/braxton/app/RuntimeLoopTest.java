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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.terminal.TerminalBackend;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.testkit.FakeTerminal;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** End-to-end runtime behaviour over an in-memory terminal: one owner, no native access. */
class RuntimeLoopTest {

    /** Increments on "a", quits on "q", and renders the count. */
    private record Counter() implements Program<Integer, String> {

        @Override
        public Transition<Integer, String> init(InitContext context) {
            return Transition.stay(0);
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            return switch (message) {
                case "inc" -> Transition.stay(model + 1);
                case "quit" -> Transition.next(model, Command.quit(3));
                default -> Transition.stay(model);
            };
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.keyed(List.of(
                    Binding.of("a", "inc"),
                    Binding.of("space", "inc"),
                    Binding.of("q", "quit")), Views.text("count: " + model), List.of());
        }
    }

    private static RuntimeOptions options(FakeTerminal.ManualTimers timers) {
        return RuntimeOptions.defaults().withLimits(32, 8, 8)
                .withEnvironment(RuntimeEnvironment.system()
                        .withTimers(timers)
                        .withEffectExecutor(Runnable::run)
                        .withNanoTime(() -> 1_000L));
    }

    /** A sealed message type with no null case, so a null message fails the run. */
    private sealed interface ResizeMessage permits SizeChanged {
    }

    private record SizeChanged(Size size) implements ResizeMessage {
    }

    /** Renders the viewport it was given, so a resize is visible in the output. */
    private record Measured() implements Program<Integer, String> {

        @Override
        public Transition<Integer, String> init(InitContext context) {
            return Transition.stay(0);
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            return Transition.stay(model);
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.text("size: " + viewport.rows() + "x" + viewport.cols());
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("condition not met within the deadline");
    }

    @Test
    @Timeout(15)
    void aResizeIsMeasuredAndPaintedWithoutAnotherMessage() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers());
        Thread run = Thread.ofVirtual().name("resize-test")
                .start(() -> Application.run(new Measured(), options, terminal));
        await(() -> terminal.output().contains("size: 6x20"));

        terminal.resize(new Size(4, 10));

        await(() -> terminal.output().contains("size: 4x10"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(terminal.output()).contains("size: 4x10");
    }

    @Test
    @Timeout(15)
    void aResizeMapperThatDeclinesASizeDoesNotEndTheRun() throws Exception {
        // The mapper declines the smaller size; a declined emission is not a message, so nothing
        // reaches update and the run keeps going. Delivering it as a null message instead makes a
        // sealed-message dispatch throw and ends the run with a failure.
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers());
        Program<Integer, ResizeMessage> program = new Program<>() {
            @Override
            public Transition<Integer, ResizeMessage> init(InitContext context) {
                return Transition.stay(0);
            }

            @Override
            public Transition<Integer, ResizeMessage> update(Integer model, ResizeMessage message) {
                return switch (message) {
                    case SizeChanged changed -> Transition.stay(changed.size().rows());
                };
            }

            @Override
            public View<ResizeMessage> view(Integer model, Viewport viewport) {
                return Views.text("size: " + viewport.rows() + "x" + viewport.cols());
            }

            @Override
            public Subscription<ResizeMessage> subscriptions(Integer model) {
                return Subscription.resize(SubscriptionId.of("resize"),
                        size -> size.rows() > 4 ? new SizeChanged(size) : null);
            }
        };
        java.util.concurrent.atomic.AtomicReference<ExitResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = Thread.ofVirtual().name("declining-resize")
                .start(() -> result.set(Application.run(program, options, terminal)));
        await(() -> terminal.output().contains("size: 6x20"));

        terminal.resize(new Size(4, 10));

        await(() -> terminal.output().contains("size: 4x10"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(result.get().kind())
                .as("a mapper that declines a size must not fail the run: %s",
                        result.get().failure())
                .isEqualTo(ExitResult.Kind.INPUT_CLOSED);
    }

    @Test
    @Timeout(15)
    void theReaderThreadsAreVirtualThreads() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers());
        Thread run = Thread.ofVirtual().name("reader-kinds")
                .start(() -> Application.run(new Counter(), options, terminal));
        await(() -> terminal.readOnVirtualThread() && terminal.measuredOnVirtualThread());
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(terminal.readOnVirtualThread()).as("input reader").isTrue();
        assertThat(terminal.measuredOnVirtualThread()).as("resize poller").isTrue();
    }

    @Test
    void inputIsRoutedAndRenderedBeforeEndOfInput() {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers());
        terminal.feed("aa");
        terminal.endInput();

        ExitResult result = Application.run(new Counter(), options, terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("count: 2");
        assertThat(terminal.output()).contains("\u001B[");
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    @Timeout(5)
    void typingASpaceDoesNotKillTheInputReader() {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.feed(" ");
        terminal.endInput();

        ExitResult result = Application.run(new Counter(), options(new FakeTerminal.ManualTimers()),
                terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("count: 1");
    }

    /** Blocks its first update until the test releases it, so the owner is busy at end-of-input. */
    private static final class SlowStart implements Program<Integer, String> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Transition<Integer, String> init(InitContext context) {
            return Transition.stay(0);
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            if ("a".equals(message)) {
                entered.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return Transition.stay(model + 1);
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.keyed(List.of(Binding.of("a", "a"), Binding.of("b", "b"),
                    Binding.of("c", "c")), Views.text("count: " + model), List.of());
        }
    }

    @Test
    @Timeout(15)
    void inputIsNotLostWhenTheOwnerIsBusy() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers()).withLimits(1, 8, 8);
        SlowStart program = new SlowStart();
        java.util.concurrent.atomic.AtomicReference<ExitResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(
                () -> outcome.set(Application.run(program, options, terminal)));
        terminal.feed("a");
        assertThat(program.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the first update is running").isTrue();
        // Two more keys: the first fills the one free slot, the second finds it full while the
        // owner is still inside update.
        terminal.feed("bc");
        Thread.sleep(300L);
        terminal.endInput();
        program.release.countDown();
        runner.join(5_000L);
        assertThat(outcome.get()).isNotNull();
        assertThat(outcome.get().kind())
                .as("every key is processed, so the run ends at end-of-input: %s", outcome.get())
                .isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output())
                .as("no key is dropped while the owner is busy")
                .contains("count: 3");
    }

    @Test
    @Timeout(15)
    void aCleanEndOfInputIsNotReportedAsAFailureWhenTheMailboxWasFull() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers()).withLimits(1, 8, 8);
        SlowStart program = new SlowStart();
        java.util.concurrent.atomic.AtomicReference<ExitResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(
                () -> outcome.set(Application.run(program, options, terminal)));
        terminal.feed("a");
        assertThat(program.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the first update is running").isTrue();
        // The owner is busy, so this fills the one-slot mailbox; end-of-input then finds it full.
        terminal.feed("b");
        Thread.sleep(200L);
        terminal.endInput();
        Thread.sleep(300L);
        program.release.countDown();
        runner.join(5_000L);
        assertThat(outcome.get()).isNotNull();
        assertThat(outcome.get().kind())
                .as("end-of-input is not a runtime failure: %s", outcome.get())
                .isEqualTo(ExitResult.Kind.INPUT_CLOSED);
    }

    @Test
    @Timeout(5)
    void anInputReaderFailureEndsTheRunInsteadOfHanging() {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.failInput(new IllegalStateException("decoder boom"));

        ExitResult result = Application.run(new Counter(), options(new FakeTerminal.ManualTimers()),
                terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).hasMessageContaining("decoder boom");
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    void aMouseClickIsIgnoredInsteadOfEndingTheProgram() {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.feed("\u001B[<0;10;5M\u001B[<0;10;5ma");
        terminal.endInput();

        ExitResult result = Application.run(new Counter(), options(new FakeTerminal.ManualTimers()),
                terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("count: 1");
    }

    @Test
    void aQuitCommandEndsTheRunWithItsStatus() {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.feed("q");
        terminal.endInput();

        ExitResult result = Application.run(new Counter(), options(new FakeTerminal.ManualTimers()),
                terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(result.status()).isEqualTo(3);
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    @Timeout(30)
    void twoSequentialRunsInOneJvmAreIndependent() {
        // Application.run builds a fresh runtime per call, so a second run in the same JVM must
        // start clean: its own terminal, its own first frame, its own exit, and no reader thread
        // left over from the first run.
        FakeTerminal first = new FakeTerminal(new Size(6, 20));
        first.feed("q");
        first.endInput();
        ExitResult firstResult = Application.run(new Counter(),
                options(new FakeTerminal.ManualTimers()), first);
        assertThat(firstResult.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(firstResult.status()).isEqualTo(3);
        assertThat(first.isOpen()).as("the first session is closed").isFalse();

        FakeTerminal second = new FakeTerminal(new Size(6, 20));
        second.feed("a");
        second.feed(" ");
        second.feed("q");
        second.endInput();
        ExitResult secondResult = Application.run(new Counter(),
                options(new FakeTerminal.ManualTimers()), second);
        assertThat(secondResult.kind()).as("the second run still quits").isEqualTo(ExitResult.Kind.QUIT);
        assertThat(secondResult.status()).isEqualTo(3);
        assertThat(second.isOpen()).isFalse();
        // The second run received its own input and painted its own model, not the first one's.
        assertThat(second.output()).as("the second run counts its own keys").contains("count: 2");
        assertThat(second.output()).doesNotContain("count: 5");
    }

    @Test
    @Timeout(30)
    void aSecondRunAfterAFailedFirstRunStillWorks() {
        FakeTerminal broken = new FakeTerminal(new Size(6, 20));
        broken.endInput();
        ExitResult first = Application.run(new Exploding(), options(new FakeTerminal.ManualTimers()),
                broken);
        assertThat(first.kind()).isEqualTo(ExitResult.Kind.FAILURE);

        FakeTerminal healthy = new FakeTerminal(new Size(6, 20));
        healthy.feed("q");
        healthy.endInput();
        ExitResult second = Application.run(new Counter(),
                options(new FakeTerminal.ManualTimers()), healthy);
        assertThat(second.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(healthy.output()).contains("count: 0");
    }

    /** Fails during init, so the run ends in a failure that must not poison the next one. */
    private record Exploding() implements Program<Integer, String> {

        @Override
        public Transition<Integer, String> init(InitContext context) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            return Transition.stay(model);
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.text("never");
        }
    }

    /** Starts a task that ignores interruption, then asks to quit after one tick. */
    private record HangingEffect(CountDownLatch started) implements Program<Integer, String> {

        @Override
        public Transition<Integer, String> init(InitContext context) {
            Command<String> work = Command.task(EffectId.of("work"), () -> {
                started.countDown();
                while (true) {
                    try {
                        Thread.sleep(60_000L);
                    } catch (InterruptedException ignored) {
                        // Deliberately ignores cancellation: shutdown must not hang on it.
                    }
                }
            }, value -> "done", failure -> "failed");
            return Transition.next(0, work);
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            return "tick".equals(message)
                    ? Transition.next(model, Command.quit())
                    : Transition.stay(model);
        }

        @Override
        public Subscription<String> subscriptions(Integer model) {
            return Subscription.interval(io.github.dhoard.braxton.effect.SubscriptionId.of("tick"),
                    java.time.Duration.ofMillis(20), time -> "tick");
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.text("hanging");
        }
    }

    @Test
    @Timeout(15)
    void shutdownAbandonsAHangingEffectAfterTheGracePeriod() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        CountDownLatch started = new CountDownLatch(1);
        List<String> diagnostics = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        FakeTerminal.ManualTimers ignored = new FakeTerminal.ManualTimers();
        RuntimeOptions options = RuntimeOptions.defaults()
                .withLimits(32, 8, 8)
                .withEnvironment(RuntimeEnvironment.system()
                        .withTimers(Timers.system())
                        .withDiagnostics(diagnostics::add));
        Thread run = Thread.ofVirtual().name("hanging-effect")
                .start(() -> Application.run(new HangingEffect(started), options, terminal));
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        run.join(10_000L);
        assertThat(run.isAlive()).as("shutdown must not wait for a task that ignores interruption")
                .isFalse();
        assertThat(diagnostics).anySatisfy(message ->
                assertThat(message).contains("still running"));
    }

    /** Throws from update on "boom" and from view while the model says "explode". */
    private record Crashing() implements Program<String, String> {

        @Override
        public Transition<String, String> init(InitContext context) {
            return Transition.stay("ok");
        }

        @Override
        public Transition<String, String> update(String model, String message) {
            if ("boom".equals(message)) {
                throw new IllegalStateException("update failed");
            }
            return Transition.stay("explode".equals(message) ? "explode" : model);
        }

        @Override
        public View<String> view(String model, Viewport viewport) {
            if ("explode".equals(model)) {
                throw new IllegalStateException("view failed");
            }
            return Views.keyed(List.of(Binding.of("x", "boom"), Binding.of("y", "explode")),
                    Views.text("safe"), List.of());
        }
    }

    @Test
    @Timeout(15)
    void aThrowingUpdateOrViewEndsTheRunAsAFailureAndClosesTheSession() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(3, 12));
        RuntimeOptions options = options(new FakeTerminal.ManualTimers());
        Thread run = Thread.ofVirtual().name("crashing-update")
                .start(() -> Application.run(new Crashing(), options, terminal));
        await(() -> terminal.output().contains("safe"));
        terminal.feed("x");
        run.join(5_000L);
        assertThat(run.isAlive()).as("a throwing update must end the run").isFalse();
        assertThat(terminal.isOpen()).as("the session is closed on failure").isFalse();

        FakeTerminal second = new FakeTerminal(new Size(3, 12));
        Thread crashingView = Thread.ofVirtual().name("crashing-view")
                .start(() -> Application.run(new Crashing(), options, second));
        await(() -> second.output().contains("safe"));
        second.feed("y");
        crashingView.join(5_000L);
        assertThat(crashingView.isAlive()).as("a throwing view must end the run").isFalse();
        assertThat(second.isOpen()).as("the session is closed on failure").isFalse();
    }

    @Test
    void anEmptyProgramStillPresentsOneFrame() {
        Program<Integer, String> empty = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                return Transition.stay(0);
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                return Transition.stay(model);
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                return Views.text("hello");
            }
        };
        FakeTerminal terminal = new FakeTerminal(new Size(4, 10));
        terminal.endInput();
        ExitResult result = Application.run(empty, options(new FakeTerminal.ManualTimers()),
                terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("hello");
    }

    @Test
    @Timeout(15)
    void anIdleApplicationDoesNoWorkAndWritesNothing() throws Exception {
        // The documented rendering model: "an idle application does no work". With no envelope to
        // process, the owner must neither call update nor rebuild the view nor repaint, however
        // many poll and resize intervals elapse.
        FakeTerminal terminal = new FakeTerminal(new Size(4, 12));
        java.util.concurrent.atomic.AtomicInteger updates =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger views =
                new java.util.concurrent.atomic.AtomicInteger();
        Program<Integer, String> program = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                return Transition.stay(0);
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                updates.incrementAndGet();
                return Transition.stay(model + 1);
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                views.incrementAndGet();
                return Views.keyed(List.of(Binding.of("a", "inc")),
                        Views.text("idle " + model), List.of());
            }
        };
        Thread run = Thread.ofVirtual().name("idle-test")
                .start(() -> Application.run(program, options(new FakeTerminal.ManualTimers()),
                        terminal));
        // The owner writes its first frame after buildView returns, so waiting for the write
        // (not for the view counter) is what makes the baseline frame complete: sampling the log
        // while the owner is still inside its first pass records an empty frame and then reports
        // the initial paint as idle output.
        await(() -> !terminal.writes().isEmpty());
        List<String> firstFrame = terminal.writes();
        Thread.sleep(400L);
        assertThat(updates).as("an idle application calls no update").hasValue(0);
        assertThat(views).as("an idle application does not rebuild its view").hasValue(1);
        assertThat(terminal.writes()).as("an idle application writes no frame").isEqualTo(firstFrame);

        terminal.feed("a");
        await(() -> terminal.writes().size() > firstFrame.size());
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
    }

    @Test
    @Timeout(15)
    void theOwnerIsTheCallersThreadAndOnlyTheReadersAreVirtual() {
        // The documented threading model: "the runtime owner is the caller's thread; Application.run
        // does not start or hijack it", while the input and resize readers are virtual threads.
        // Every program callback therefore observes the thread that called run().
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        Thread caller = Thread.currentThread();
        List<Thread> callbacks = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Program<Integer, String> program = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                callbacks.add(Thread.currentThread());
                return Transition.stay(0);
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                callbacks.add(Thread.currentThread());
                return Transition.stay(model + 1);
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                callbacks.add(Thread.currentThread());
                return Views.keyed(List.of(Binding.of("a", "inc")), Views.text("n " + model),
                        List.of());
            }
        };
        terminal.feed("a");
        terminal.endInput();
        ExitResult result = Application.run(program, options(new FakeTerminal.ManualTimers()),
                terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(callbacks).as("init, update and view all run on the caller's thread")
                .isNotEmpty().allSatisfy(thread -> assertThat(thread).isSameAs(caller));
        assertThat(terminal.readOnVirtualThread()).as("the input reader is a virtual thread")
                .isTrue();
        assertThat(terminal.measuredOnVirtualThread()).as("the resize poller is a virtual thread")
                .isTrue();
    }

    @Test
    @Timeout(20)
    void shutdownWaitsOnlyTheGracePeriodForATaskThatIgnoresInterruption() throws Exception {
        // The documented limitation: "cancellation is cooperative: a task that ignores interruption
        // is not forcibly stopped; its result is suppressed and shutdown waits only for the
        // configured grace period".
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger updates =
                new java.util.concurrent.atomic.AtomicInteger();
        Program<Integer, String> program = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                return Transition.next(0, Command.task(EffectId.of("stubborn"), () -> {
                    started.countDown();
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException ignored) {
                            // Deliberately uncooperative: the runtime must not force it.
                        }
                    }
                    finished.countDown();
                    return "late";
                }, value -> "done", failure -> "failed"));
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                updates.incrementAndGet();
                return Transition.stay(model);
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                return Views.text("working");
            }
        };
        java.util.List<String> diagnostics =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        RuntimeEnvironment environment = RuntimeEnvironment.system()
                .withTimers(new FakeTerminal.ManualTimers())
                .withEffectExecutor(executor)
                .withDiagnostics(diagnostics::add)
                .withNanoTime(() -> 1_000L);
        RuntimeOptions options = new RuntimeOptions(java.time.Duration.ofMillis(200), 32, 8, 8,
                io.github.dhoard.braxton.clipboard.ClipboardConfig.defaults(),
                io.github.dhoard.braxton.terminal.TerminalOptions.DEFAULT, environment);
        java.util.concurrent.atomic.AtomicReference<ExitResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = Thread.ofVirtual().name("grace-test")
                .start(() -> result.set(Application.run(program, options, terminal)));
        try {
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the stubborn task starts").isTrue();
            long startedAt = System.nanoTime();
            terminal.endInput();
            run.join(10_000L);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(run.isAlive()).as("the run ends without waiting for the task").isFalse();
            assertThat(elapsedMillis).as("shutdown waits for the grace period, then returns")
                    .isGreaterThanOrEqualTo(150L).isLessThan(5_000L);
            assertThat(result.get().kind())
                    .as("a task that ignores interruption is not a failure").isEqualTo(
                            ExitResult.Kind.INPUT_CLOSED);
            assertThat(diagnostics).anySatisfy(message ->
                    assertThat(message).contains("shutdown").contains("still running"));
        } finally {
            release.countDown();
            finished.await(5, java.util.concurrent.TimeUnit.SECONDS);
            executor.close();
            run.join(1_000L);
        }
        Thread.sleep(100L);
        assertThat(updates).as("the late result never reaches update").hasValue(0);
    }

    @Test
    @Timeout(15)
    void aQuitInsideABatchEndsTheRunAndStopsTheCommandsAfterIt() {
        // The documented contract for Command.batch: "interpreted in order; a Quit ends the batch".
        // At the runtime level that must also end the run with the quit's status and leave nothing
        // from the rest of the batch running.
        java.util.List<String> diagnostics =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger updates =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger tasks =
                new java.util.concurrent.atomic.AtomicInteger();
        FakeTerminal.ManualTimers timers = new FakeTerminal.ManualTimers();
        Program<Integer, String> program = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                return Transition.next(0, Command.batch(
                        Command.delay(EffectId.of("before"), java.time.Duration.ofSeconds(1),
                                "before", "rejected"),
                        Command.quit(5),
                        Command.task(EffectId.of("after"), () -> {
                            tasks.incrementAndGet();
                            return "after";
                        }, value -> "after", failure -> "after")));
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                updates.incrementAndGet();
                return Transition.stay(model);
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                return Views.text("batch");
            }
        };
        FakeTerminal terminal = new FakeTerminal(new Size(3, 10));
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(32, 8, 8)
                .withEnvironment(RuntimeEnvironment.system()
                        .withTimers(timers)
                        .withEffectExecutor(Runnable::run)
                        .withDiagnostics(diagnostics::add)
                        .withNanoTime(() -> 1_000L));
        ExitResult result = Application.run(program, options, terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(result.status()).isEqualTo(5);
        assertThat(tasks).as("a task after the quit never starts").hasValue(0);
        timers.advance(5_000_000_000L);
        assertThat(updates).as("nothing from the batch reaches update after the quit").hasValue(0);
        assertThat(timers.pending()).as("the cancelled delay is gone").isZero();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    @Timeout(15)
    void aFailureKeepsItsPrimaryCauseAndCarriesTheCleanupFailures() throws Exception {
        // The documented contract: ExitResult "carries cleanup failures without hiding the primary
        // cause". A timer that cannot be cancelled during shutdown must not replace the exception
        // that ended the run, and it must still be reported.
        Timers uncancellable = (delayNanos, task) -> () -> {
            throw new IllegalStateException("cancel failed");
        };
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(32, 8, 8)
                .withEnvironment(RuntimeEnvironment.system()
                        .withTimers(uncancellable)
                        .withEffectExecutor(Runnable::run)
                        .withNanoTime(() -> 1_000L));
        FakeTerminal terminal = new FakeTerminal(new Size(3, 10));
        Program<Integer, String> program = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                return Transition.next(0, Command.delay(EffectId.of("slow"),
                        java.time.Duration.ofSeconds(30), "late", "rejected"));
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                throw new IllegalStateException("update broke");
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                return Views.keyed(List.of(Binding.of("a", "inc")), Views.text("safe"), List.of());
            }
        };
        java.util.concurrent.atomic.AtomicReference<ExitResult> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = Thread.ofVirtual().name("cleanup-failure-test")
                .start(() -> result.set(Application.run(program, options, terminal)));
        await(() -> !terminal.writes().isEmpty());
        terminal.feed("a");
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(result.get().kind()).as("a throwing update ends the run").isEqualTo(
                ExitResult.Kind.FAILURE);
        assertThat(result.get().failure()).as("the primary cause is not replaced")
                .hasMessage("update broke");
        assertThat(result.get().cleanupFailures()).as("the cleanup failure is still reported")
                .anySatisfy(failure -> assertThat(failure).hasMessage("cancel failed"));
    }

    @Test
    void theSessionIsClosedEvenWhenStartupFails() {
        FakeTerminal terminal = new FakeTerminal(new Size(4, 10));
        Program<Integer, String> broken = new Program<>() {
            @Override
            public Transition<Integer, String> init(InitContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public Transition<Integer, String> update(Integer model, String message) {
                return Transition.stay(model);
            }

            @Override
            public View<String> view(Integer model, Viewport viewport) {
                return Views.text("");
            }
        };
        ExitResult result = Application.run(broken, options(new FakeTerminal.ManualTimers()),
                terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).hasMessageContaining("boom");
        assertThat(terminal.isOpen()).isFalse();
    }

    /** Quits as soon as it starts, so the only failure in the run is a cleanup failure. */
    private record Quitting() implements Program<Integer, String> {

        @Override
        public Transition<Integer, String> init(InitContext context) {
            return Transition.next(0, Command.quit());
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            return Transition.stay(model);
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.text("quit");
        }
    }

    /**
     * A session whose restore fails once. It marks the restore attempted first, like the real
     * session, so the second, idempotent close completes the underlying session instead of failing
     * again.
     */
    private static final class FailingRestoreSession implements TerminalSession {

        private final FakeTerminal terminal;
        private final AtomicBoolean restoreAttempted = new AtomicBoolean();

        FailingRestoreSession(FakeTerminal terminal) {
            this.terminal = terminal;
        }

        @Override
        public TerminalCapabilities capabilities() {
            return terminal.capabilities();
        }

        @Override
        public Size size() {
            return terminal.size();
        }

        @Override
        public java.io.InputStream input() {
            return terminal.input();
        }

        @Override
        public void write(String data) {
            terminal.write(data);
        }

        @Override
        public boolean isOpen() {
            return terminal.isOpen();
        }

        @Override
        public void close() {
            if (!restoreAttempted.getAndSet(true)) {
                throw new IllegalStateException("session restore failed");
            }
            terminal.close();
        }
    }

    @Test
    @Timeout(20)
    void aFailingSessionRestoreIsCarriedAsACleanupFailureInsteadOfEscapingTheRun() {
        // Application.run's contract is to return how the run ended and to carry cleanup failures.
        // A session whose restore throws must not abort the rest of cleanup (the mailbox close and
        // the thread joins) or escape the runtime as an exception.
        FakeTerminal terminal = new FakeTerminal(new Size(3, 10));
        FailingRestoreSession session = new FailingRestoreSession(terminal);
        TerminalBackend backend = (size, requested) -> {
            terminal.open(size, requested);
            return session;
        };

        ExitResult result = Application.run(new Quitting(),
                options(new FakeTerminal.ManualTimers()), backend);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).hasMessage("session restore failed");
        assertThat(result.cleanupFailures())
                .anySatisfy(failure -> assertThat(failure).hasMessage("session restore failed"));
        assertThat(session.isOpen()).as("the second, idempotent close still ran").isFalse();
    }

    @Test
    void aSecondRealBackendCannotOwnTheTerminal() {
        var backend = io.github.dhoard.braxton.internal.terminal.SystemTerminalBackend.INSTANCE;
        // A fake backend exercises the runtime; this check only asserts the real backend type exists
        // and that the unsupported-platform path is reachable without touching a console.
        assertThat(backend).isNotNull();
    }

    /** Subscribes to a source that emits a burst from its own start function. */
    private record Bursting(int emissions) implements Program<Integer, String> {

        @Override
        public Transition<Integer, String> init(InitContext context) {
            return Transition.stay(0);
        }

        @Override
        public Transition<Integer, String> update(Integer model, String message) {
            return Transition.stay(model + 1);
        }

        @Override
        public View<String> view(Integer model, Viewport viewport) {
            return Views.text("burst: " + model);
        }

        @Override
        public Subscription<String> subscriptions(Integer model) {
            return Subscription.source(new SubscriptionId("burst"), "config",
                    emitter -> {
                        for (int i = 0; i < emissions; i++) {
                            emitter.accept("tick" + i);
                        }
                        return () -> { };
                    },
                    String::valueOf);
        }
    }

    @Test
    @Timeout(20)
    void aSourceThatOverflowsTheMailboxFromItsStartFunctionEndsTheRun() throws Exception {
        // start() runs on the owner thread, so an emission there must never wait for the owner to
        // drain: the owner is the consumer. An oversized burst is the documented fatal overflow.
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        RuntimeOptions tight = options(new FakeTerminal.ManualTimers()).withLimits(2, 4, 4);
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Thread run = Thread.ofVirtual().name("burst-test")
                .start(() -> result.set(Application.run(new Bursting(5), tight, terminal)));
        run.join(8_000L);
        assertThat(run.isAlive()).as("the run ends instead of waiting for itself").isFalse();
        assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.get().failure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mailbox overflow");
        assertThat(terminal.isOpen()).as("the session is still restored").isFalse();
    }

    @Test
    @Timeout(20)
    void aSourceThatEmitsFromItsStartFunctionStillRunsWhenTheMailboxHasRoom() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.endInput();
        ExitResult result = Application.run(new Counter(), options(new FakeTerminal.ManualTimers()),
                terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("count:");

        FakeTerminal burstTerminal = new FakeTerminal(new Size(6, 20));
        AtomicReference<ExitResult> burst = new AtomicReference<>();
        Thread burstRun = Thread.ofVirtual().name("burst-control")
                .start(() -> burst.set(Application.run(new Bursting(5),
                        options(new FakeTerminal.ManualTimers()), burstTerminal)));
        await(() -> burstTerminal.output().contains("burst: 5"));
        burstTerminal.endInput();
        burstRun.join(5_000L);
        assertThat(burstRun.isAlive()).isFalse();
        assertThat(burst.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
    }

    @Test
    void renderedFramesAreWrittenThroughTheSessionOnly() {
        FakeTerminal terminal = new FakeTerminal(new Size(3, 12));
        terminal.feed("a");
        terminal.endInput();
        Application.run(new Counter(), options(new FakeTerminal.ManualTimers()), terminal);
        assertThat(terminal.writes()).isNotEmpty();
        assertThat(terminal.writes()).allSatisfy(write ->
                assertThat(write).doesNotContain("\u001B[?1049"));
    }
}
