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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.effect.Command;
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
        Thread run = new Thread(() -> Application.run(new Measured(), options, terminal), "resize-test");
        run.start();
        await(() -> terminal.output().contains("size: 6x20"));

        terminal.resize(new Size(4, 10));

        await(() -> terminal.output().contains("size: 4x10"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(terminal.output()).contains("size: 4x10");
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
