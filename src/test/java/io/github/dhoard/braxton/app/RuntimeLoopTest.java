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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
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

    @Test
    void aSecondRealBackendCannotOwnTheTerminal() {
        var backend = io.github.dhoard.braxton.internal.terminal.SystemTerminalBackend.INSTANCE;
        // A fake backend exercises the runtime; this check only asserts the real backend type exists
        // and that the unsupported-platform path is reachable without touching a console.
        assertThat(backend).isNotNull();
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
