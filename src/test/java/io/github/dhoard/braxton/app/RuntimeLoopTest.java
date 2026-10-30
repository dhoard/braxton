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

import static io.github.dhoard.braxton.internal.runtime.JLineTestTerminal.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.internal.runtime.JLineTestTerminal;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.Timers;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end runtime behaviour over an in-memory terminal: one owner, no native access. */
class RuntimeLoopTest {

    /** Increments on "a", quits on "q", and renders the count. */
    private record Counter() implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return switch (message) {
                case "inc" -> Update.next(model + 1);
                case "quit" -> Update.next(model, Command.quit(3));
                default -> Update.next(model);
            };
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            return Views.keyed(List.of(
                    Binding.of("a", "inc"),
                    Binding.of("space", "inc"),
                    Binding.of("q", "quit")), Views.text("count: " + model), List.of());
        }
    }

    private static RuntimeOptions options() {
        return RuntimeOptions.defaults().withLimits(32,8,8);
    }

    /** A sealed message type with no null case, so a null message fails the run. */
    private sealed interface ResizeMessage permits SizeChanged {
    }

    private record SizeChanged(Size size) implements ResizeMessage {
    }

    /** Renders the viewport it was given, so a resize is visible in the output. */
    private record Measured() implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return Update.next(model);
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            Viewport viewport = context.viewport();
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
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions options = options();
        Thread run = Thread.ofVirtual().name("resize-test")
                .start(() -> run(new Measured(), options, terminal));
        await(() -> terminal.outputText().contains("size: 6x20"));

        terminal.resize(new Size(4, 10));

        await(() -> terminal.outputText().contains("size: 4x10"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(terminal.outputText()).contains("size: 4x10");
    }

    @Test
    @Timeout(15)
    void aResizeMapperThatDeclinesASizeDoesNotEndTheRun() throws Exception {
        // The mapper declines the smaller size; a declined emission is not a message, so nothing
        // reaches update and the run keeps going. Delivering it as a null message instead makes a
        // sealed-message dispatch throw and ends the run with a failure.
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions options = options();
        Component<Integer, ResizeMessage, Never> program =
                new Component<>() {
                    @Override
                    public Update<Integer, ResizeMessage, Never> init(InitContext context) {
                        return Update.next(0);
                    }

                    @Override
                    public Update<Integer, ResizeMessage, Never> update(
                            Integer model, ResizeMessage message) {
                        return switch (message) {
                            case SizeChanged changed -> Update.next(changed.size().rows());
                        };
                    }

                    @Override
                    public View<ResizeMessage> view(Integer model, ViewContext context) {
                        Viewport viewport = context.viewport();
                        return Views.text("size: " + viewport.rows() + "x" + viewport.cols());
                    }

                    @Override
                    public Subscription<ResizeMessage> subscriptions(Integer model) {
                        return Subscription.resize(
                                SubscriptionId.of("resize"),
                                size -> size.rows() > 4 ? new SizeChanged(size) : null);
                    }
                };
        java.util.concurrent.atomic.AtomicReference<ExitResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = Thread.ofVirtual().name("declining-resize")
                .start(() -> result.set(run(program, options, terminal)));
        await(() -> terminal.outputText().contains("size: 6x20"));

        terminal.resize(new Size(4, 10));

        await(() -> terminal.outputText().contains("size: 4x10"));
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
    void unavailableSizeUsesFallbackAndTheRunRemainsUsable() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6,20));
        terminal.failSize(new IllegalStateException("temporary size failure"));
        terminal.feed("q");
        ExitResult result = run(new Counter(), RuntimeOptions.defaults(), terminal);
        assertThat(result.kind()).as("%s %s", result.failure(), result.failure() == null ? "" : java.util.Arrays.toString(result.failure().getStackTrace())).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    @Timeout(15)
    void theInputReaderIsVirtualAndTheOwnerMeasuresTheTerminal() throws Exception {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions options = options();
        Thread run = Thread.ofVirtual().name("reader-kinds")
                .start(() -> run(new Counter(), options, terminal));
        await(() -> terminal.readOnVirtualThread() && terminal.measured());
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(terminal.readOnVirtualThread()).as("input reader").isTrue();
        assertThat(terminal.measured()).as("terminal size was measured").isTrue();
    }

    @Test
    void inputIsRoutedAndRenderedBeforeEndOfInput() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions options = options();
        terminal.feed("aa");
        terminal.endInput();

        ExitResult result = run(new Counter(), options, terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.outputText()).contains("count: 2");
        assertThat(terminal.outputText()).contains("\u001B[");
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    @Timeout(5)
    void typingASpaceDoesNotKillTheInputReader() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        terminal.feed(" ");
        terminal.endInput();

        ExitResult result = run(new Counter(), options(), terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.outputText()).contains("count: 1");
    }

    /** Blocks its first update until the test releases it, so the owner is busy at end-of-input. */
    private static final class SlowStart implements Component<Integer, String, Never> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            if ("a".equals(message)) {
                entered.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return Update.next(model + 1);
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            return Views.keyed(List.of(Binding.of("a", "a"), Binding.of("b", "b"),
                    Binding.of("c", "c")), Views.text("count: " + model), List.of());
        }
    }

    @Test
    @Timeout(15)
    void inputIsNotLostWhenTheOwnerIsBusy() throws Exception {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions options = options().withLimits(1, 8, 8);
        SlowStart program = new SlowStart();
        java.util.concurrent.atomic.AtomicReference<ExitResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(
                () -> outcome.set(run(program, options, terminal)));
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
        assertThat(terminal.outputText())
                .as("no key is dropped while the owner is busy")
                .contains("count: 3");
    }

    @Test
    @Timeout(15)
    void aCleanEndOfInputIsNotReportedAsAFailureWhenTheMailboxWasFull() throws Exception {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions options = options().withLimits(1, 8, 8);
        SlowStart program = new SlowStart();
        java.util.concurrent.atomic.AtomicReference<ExitResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(
                () -> outcome.set(run(program, options, terminal)));
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
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        terminal.failInput(new IllegalStateException("decoder boom"));

        ExitResult result = run(new Counter(), options(), terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).hasMessageContaining("decoder boom");
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    void aMouseClickIsIgnoredInsteadOfEndingTheProgram() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        terminal.feed("\u001B[<0;10;5M\u001B[<0;10;5ma");
        terminal.endInput();

        ExitResult result = run(new Counter(), options(), terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.outputText()).contains("count: 1");
    }

    @Test
    void aQuitCommandEndsTheRunWithItsStatus() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        terminal.feed("q");
        terminal.endInput();

        ExitResult result = run(new Counter(), options(), terminal);

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
        JLineTestTerminal first = new JLineTestTerminal(new Size(6, 20));
        first.feed("q");
        first.endInput();
        ExitResult firstResult = run(new Counter(), options(), first);
        assertThat(firstResult.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(firstResult.status()).isEqualTo(3);
        assertThat(first.isOpen()).as("the first session is closed").isFalse();

        JLineTestTerminal second = new JLineTestTerminal(new Size(6, 20));
        second.feed("a");
        second.feed(" ");
        second.feed("q");
        second.endInput();
        ExitResult secondResult = run(new Counter(), options(), second);
        assertThat(secondResult.kind()).as("the second run still quits").isEqualTo(ExitResult.Kind.QUIT);
        assertThat(secondResult.status()).isEqualTo(3);
        assertThat(second.isOpen()).isFalse();
        // The second run received its own input and painted its own model, not the first one's.
        assertThat(second.outputText()).as("the second run counts its own keys").contains("count: 2");
        assertThat(second.outputText()).doesNotContain("count: 5");
    }

    @Test
    @Timeout(30)
    void aSecondRunAfterAFailedFirstRunStillWorks() {
        JLineTestTerminal broken = new JLineTestTerminal(new Size(6, 20));
        broken.endInput();
        ExitResult first = run(new Exploding(), options(), broken);
        assertThat(first.kind()).isEqualTo(ExitResult.Kind.FAILURE);

        JLineTestTerminal healthy = new JLineTestTerminal(new Size(6, 20));
        healthy.feed("q");
        healthy.endInput();
        ExitResult second = run(new Counter(), options(), healthy);
        assertThat(second.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(healthy.outputText()).contains("count: 0");
    }

    /** Fails during init, so the run ends in a failure that must not poison the next one. */
    private record Exploding() implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return Update.next(model);
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            return Views.text("never");
        }
    }

    /** Starts a task that ignores interruption, then asks to quit after one tick. */
    private record HangingEffect(CountDownLatch started)
            implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
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
            return Update.next(0, work);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return "tick".equals(message) ? Update.next(model, Command.quit()) : Update.next(model);
        }

        @Override
        public Subscription<String> subscriptions(Integer model) {
            return Subscription.interval(io.github.dhoard.braxton.effect.SubscriptionId.of("tick"),
                    java.time.Duration.ofMillis(20), time -> "tick");
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            return Views.text("hanging");
        }
    }

    @Test
    @Timeout(15)
    void shutdownAbandonsAHangingEffectAfterTheGracePeriod() throws Exception {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 20));
        CountDownLatch started = new CountDownLatch(1);
        List<String> diagnostics = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ManualTimers ignored = new ManualTimers();
        RuntimeOptions options = RuntimeOptions.defaults()
                .withLimits(32, 8, 8)
                ;
        RuntimeServices services = RuntimeServices.system()
                        .withTimers(Timers.system())
                        .withDiagnostics(diagnostics::add);
        Thread run = Thread.ofVirtual().name("hanging-effect")
                .start(() -> run(new HangingEffect(started), options, terminal, services));
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        run.join(10_000L);
        assertThat(run.isAlive()).as("shutdown must not wait for a task that ignores interruption")
                .isFalse();
        assertThat(diagnostics).anySatisfy(message ->
                assertThat(message).contains("still running"));
    }

    /** Throws from update on "boom" and from view while the model says "explode". */
    private record Crashing() implements Component<String, String, Never> {

        @Override
        public Update<String, String, Never> init(InitContext context) {
            return Update.next("ok");
        }

        @Override
        public Update<String, String, Never> update(String model, String message) {
            if ("boom".equals(message)) {
                throw new IllegalStateException("update failed");
            }
            return Update.next("explode".equals(message) ? "explode" : model);
        }

        @Override
        public View<String> view(String model, ViewContext context) {
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
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(3, 12));
        RuntimeOptions options = options();
        Thread run = Thread.ofVirtual().name("crashing-update")
                .start(() -> run(new Crashing(), options, terminal));
        await(() -> terminal.outputText().contains("safe"));
        terminal.feed("x");
        run.join(5_000L);
        assertThat(run.isAlive()).as("a throwing update must end the run").isFalse();
        assertThat(terminal.isOpen()).as("the session is closed on failure").isFalse();

        JLineTestTerminal second = new JLineTestTerminal(new Size(3, 12));
        Thread crashingView = Thread.ofVirtual().name("crashing-view")
                .start(() -> run(new Crashing(), options, second));
        await(() -> second.outputText().contains("safe"));
        second.feed("y");
        crashingView.join(5_000L);
        assertThat(crashingView.isAlive()).as("a throwing view must end the run").isFalse();
        assertThat(second.isOpen()).as("the session is closed on failure").isFalse();
    }

    @Test
    void anEmptyProgramStillPresentsOneFrame() {
        Component<Integer, String, Never> empty =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(0);
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        return Update.next(model);
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        return Views.text("hello");
                    }
                };
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 10));
        terminal.endInput();
        ExitResult result = run(empty, options(), terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.outputText()).contains("hello");
    }

    @Test
    @Timeout(15)
    void anIdleApplicationDoesNoWorkAndWritesNothing() throws Exception {
        // The documented rendering model: "an idle application does no work". With no envelope to
        // process, the owner must neither call update nor rebuild the view nor repaint, however
        // many poll and resize intervals elapse.
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 12));
        java.util.concurrent.atomic.AtomicInteger updates =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger views =
                new java.util.concurrent.atomic.AtomicInteger();
        Component<Integer, String, Never> program =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(0);
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        updates.incrementAndGet();
                        return Update.next(model + 1);
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        views.incrementAndGet();
                        return Views.keyed(
                                List.of(Binding.of("a", "inc")),
                                Views.text("idle " + model),
                                List.of());
                    }
                };
        Thread run = Thread.ofVirtual().name("idle-test")
                .start(() -> run(program, options(), terminal));
        // The owner writes its first frame after buildView returns, so waiting for the write
        // (not for the view counter) is what makes the baseline frame complete: sampling the log
        // while the owner is still inside its first pass records an empty frame and then reports
        // the initial paint as idle output.
        await(() -> terminal.outputText().contains("idle 0"));
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
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 20));
        Thread caller = Thread.currentThread();
        List<Thread> callbacks = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Component<Integer, String, Never> program =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        callbacks.add(Thread.currentThread());
                        return Update.next(0);
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        callbacks.add(Thread.currentThread());
                        return Update.next(model + 1);
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        callbacks.add(Thread.currentThread());
                        return Views.keyed(
                                List.of(Binding.of("a", "inc")),
                                Views.text("n " + model),
                                List.of());
                    }
                };
        terminal.feed("a");
        terminal.endInput();
        ExitResult result = run(program, options(), terminal);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(callbacks).as("init, update and view all run on the caller's thread")
                .isNotEmpty().allSatisfy(thread -> assertThat(thread).isSameAs(caller));
        assertThat(terminal.readOnVirtualThread()).as("the input reader is a virtual thread")
                .isTrue();
        // Viewport sampling is now performed by the model owner through JLine.
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
        Component<Integer, String, Never> program =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(
                                0,
                                Command.task(
                                        EffectId.of("stubborn"),
                                        () -> {
                                            started.countDown();
                                            while (true) {
                                                try {
                                                    release.await();
                                                    break;
                                                } catch (InterruptedException ignored) {
                                                    // Deliberately uncooperative: the runtime must
                                                    // not force it.
                                                }
                                            }
                                            finished.countDown();
                                            return "late";
                                        },
                                        value -> "done",
                                        failure -> "failed"));
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        updates.incrementAndGet();
                        return Update.next(model);
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        return Views.text("working");
                    }
                };
        java.util.List<String> diagnostics =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 20));
        RuntimeServices environment = RuntimeServices.system()
                .withTimers(new ManualTimers())
                .withEffectExecutor(executor)
                .withDiagnostics(diagnostics::add)
                .withNanoTime(() -> 1_000L);
        RuntimeOptions options =
                new RuntimeOptions(
                        java.time.Duration.ofMillis(200),
                        32,
                        8,
                        8,
                        RuntimeOptions.DEFAULT_MAX_COMPONENTS,
                        RuntimeOptions.DEFAULT_MAX_COMPONENT_DEPTH,
                        RuntimeOptions.DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                        io.github.dhoard.braxton.clipboard.ClipboardConfig.defaults(),
                        io.github.dhoard.braxton.app.TerminalOptions.DEFAULT,
                        null);
        java.util.concurrent.atomic.AtomicReference<ExitResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = Thread.ofVirtual().name("grace-test")
                .start(() -> result.set(run(program, options, terminal, environment)));
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
        ManualTimers timers = new ManualTimers();
        Component<Integer, String, Never> program =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(
                                0,
                                Command.batch(
                                        Command.delay(
                                                EffectId.of("before"),
                                                java.time.Duration.ofSeconds(1),
                                                "before",
                                                "rejected"),
                                        Command.quit(5),
                                        Command.task(
                                                EffectId.of("after"),
                                                () -> {
                                                    tasks.incrementAndGet();
                                                    return "after";
                                                },
                                                value -> "after",
                                                failure -> "after")));
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        updates.incrementAndGet();
                        return Update.next(model);
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        return Views.text("batch");
                    }
                };
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(3, 10));
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(32, 8, 8)
                ;
        RuntimeServices services = RuntimeServices.system()
                        .withTimers(timers)
                        .withEffectExecutor(Runnable::run)
                        .withDiagnostics(diagnostics::add)
                        .withNanoTime(() -> 1_000L);
        ExitResult result = run(program, options, terminal, services);

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
                ;
        RuntimeServices services = RuntimeServices.system()
                        .withTimers(uncancellable)
                        .withEffectExecutor(Runnable::run)
                        .withNanoTime(() -> 1_000L);
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(3, 10));
        Component<Integer, String, Never> program =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(
                                0,
                                Command.delay(
                                        EffectId.of("slow"),
                                        java.time.Duration.ofSeconds(30),
                                        "late",
                                        "rejected"));
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        throw new IllegalStateException("update broke");
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        return Views.keyed(
                                List.of(Binding.of("a", "inc")), Views.text("safe"), List.of());
                    }
                };
        java.util.concurrent.atomic.AtomicReference<ExitResult> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = Thread.ofVirtual().name("cleanup-failure-test")
                .start(() -> result.set(run(program, options, terminal, services)));
        await(() -> terminal.outputText().contains("safe"));
        terminal.feed("a");
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(result.get().kind()).as("a throwing update ends the run").isEqualTo(
                ExitResult.Kind.FAILURE);
        assertThat(result.get().failure())
                .isInstanceOf(ComponentFailure.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(result.get().failure().getCause())
                .as("the primary cause is not replaced")
                .hasMessage("update broke");
        assertThat(result.get().cleanupFailures()).as("the cleanup failure is still reported")
                .anySatisfy(failure -> assertThat(failure).hasMessage("cancel failed"));
    }

    @Test
    void theSessionIsClosedEvenWhenStartupFails() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 10));
        Component<Integer, String, Never> broken =
                new Component<>() {
                    @Override
                    public Update<Integer, String, Never> init(InitContext context) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public Update<Integer, String, Never> update(Integer model, String message) {
                        return Update.next(model);
                    }

                    @Override
                    public View<String> view(Integer model, ViewContext context) {
                        return Views.text("");
                    }
                };
        ExitResult result = run(broken, options(), terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).isInstanceOf(ComponentFailure.class);
        assertThat(result.failure().getCause()).hasMessageContaining("boom");
        assertThat(terminal.isOpen()).isFalse();
    }

    /** Quits as soon as it starts, so the only failure in the run is a cleanup failure. */
    private record Quitting() implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0, Command.quit());
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return Update.next(model);
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            return Views.text("quit");
        }
    }

    @Test
    void aFailingJLineCloseIsCarriedAsACleanupFailure() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(3,10));
        terminal.failClose(new IllegalStateException("terminal restore failed"));
        ExitResult result = run(new Quitting(), RuntimeOptions.defaults(), terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.cleanupFailures()).anySatisfy(f -> assertThat(f).hasMessage("terminal restore failed"));
        assertThat(terminal.isOpen()).isFalse();
    }

    @Test
    void pureApiInitializationDoesNotOpenNativeResources() {
        assertThat(RuntimeOptions.defaults().input()).isNotNull();
    }

    /** Subscribes to a source that emits a burst from its own start function. */
    private record Bursting(int emissions) implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return Update.next(model + 1);
        }

        @Override
        public View<String> view(Integer model, ViewContext context) {
            return Views.text("burst: " + model);
        }

        @Override
        public Subscription<String> subscriptions(Integer model) {
            return Subscription.source(
                    SubscriptionId.of("burst"),
                    "config",
                    emitter -> {
                        for (int i = 0; i < emissions; i++) {
                            emitter.accept("tick" + i);
                        }
                        return () -> {};
                    },
                    String::valueOf);
        }
    }

    @Test
    @Timeout(20)
    void aSourceThatOverflowsTheMailboxFromItsStartFunctionEndsTheRun() throws Exception {
        // start() runs on the owner thread, so an emission there must never wait for the owner to
        // drain: the owner is the consumer. An oversized burst is the documented fatal overflow.
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        RuntimeOptions tight = options().withLimits(2, 4, 4);
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Thread run = Thread.ofVirtual().name("burst-test")
                .start(() -> result.set(run(new Bursting(5), tight, terminal)));
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
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(6, 20));
        terminal.endInput();
        ExitResult result = run(new Counter(), options(), terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.outputText()).contains("count:");

        JLineTestTerminal burstTerminal = new JLineTestTerminal(new Size(6, 20));
        AtomicReference<ExitResult> burst = new AtomicReference<>();
        Thread burstRun = Thread.ofVirtual().name("burst-control")
                .start(() -> burst.set(run(new Bursting(5), options(), burstTerminal)));
        await(() -> burstTerminal.outputText().contains("burst: 5"));
        burstTerminal.endInput();
        burstRun.join(5_000L);
        assertThat(burstRun.isAlive()).isFalse();
        assertThat(burst.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
    }

    @Test
    void renderedFramesAreWrittenThroughTheSessionOnly() {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(3, 12));
        terminal.feed("a");
        terminal.endInput();
        run(new Counter(), options(), terminal);
        assertThat(terminal.writes()).isNotEmpty();
        assertThat(terminal.outputText()).contains("\u001B[?1049h").contains("\u001B[?1049l").contains("count: 1");
        assertThat(terminal.writes().stream().filter(write -> write.contains("count:")))
                .allSatisfy(write -> assertThat(write).doesNotContain("\u001B[?1049"));
    }
}
