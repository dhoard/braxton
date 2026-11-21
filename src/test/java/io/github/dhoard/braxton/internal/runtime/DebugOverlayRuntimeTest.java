/*
 * Copyright (c) 2026-present Douglas Hoard
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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.IoOptions;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DebugOverlayRuntimeTest {
    private record Counter(boolean debug) implements Component<Integer, String, Never> {
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0, Command.debugOverlay(debug));
        }

        public Update<Integer, String, Never> update(Integer model, String message) {
            return switch (message) {
                case "a" -> Update.next(model + 1);
                case "n" -> Update.next(model);
                case "d" -> Update.next(model, Command.debugOverlay(false));
                case "e" -> Update.next(model, Command.debugOverlay(true));
                default -> Update.next(model, Command.quit());
            };
        }

        public Element<String> view(Integer model, ViewContext context) {
            return Elements.text("counter=" + model);
        }

        public List<Binding<String>> claims(Integer model) {
            return List.of(
                    Binding.of("a", "a"),
                    Binding.of("d", "d"),
                    Binding.of("e", "e"),
                    Binding.of("n", "n"),
                    Binding.of("q", "q"));
        }
    }

    @Test
    @Timeout(5)
    void aDiagnosticMessagePaintsEvenWhenTheUpdateKeepsTheSameModel() {
        TestTerminal terminal = new TestTerminal();
        AtomicInteger stage = new AtomicInteger();
        terminal.onOutput(text -> {
            if (!text.contains("\u001b[?2026h")) return;
            String visible = terminal.screenText();
            if (stage.get() == 0 && visible.contains("DEBUG") && stage.compareAndSet(0, 1)) terminal.feed("n");
            else if (stage.get() == 1 && visible.contains("msg=n") && stage.compareAndSet(1, 2)) terminal.feed("q");
        });
        ExitResult result = TestTerminal.run(new Counter(true), RuntimeOptions.defaults(), terminal);
        assertThat(result.ok()).as(result.toString()).isTrue();
        assertThat(stage.get()).isEqualTo(2);
        assertThat(terminal.screenText()).contains("counter=0", "msg=n");
    }

    @Test
    @Timeout(10)
    void togglesRepaintEvenWithoutAModelChangeAndHistoryDoesNotSurviveDisabling() {
        TestTerminal terminal = new TestTerminal();
        AtomicInteger stage = new AtomicInteger();
        terminal.onOutput(text -> {
            if (!text.contains("\u001b[?2026h")) return;
            String visible = terminal.screenText();
            if (stage.get() == 0 && visible.contains("DEBUG") && stage.compareAndSet(0, 1)) terminal.feed("a");
            else if (stage.get() == 1
                    && visible.contains("counter=1")
                    && visible.contains("msg=a")
                    && stage.compareAndSet(1, 2)) terminal.feed("d");
            else if (stage.get() == 2
                    && !visible.contains("DEBUG")
                    && visible.contains("counter=1")
                    && stage.compareAndSet(2, 3)) terminal.feed("e");
            else if (stage.get() == 3
                    && visible.contains("DEBUG")
                    && visible.contains("msg=(none)")
                    && stage.compareAndSet(3, 4)) terminal.feed("q");
        });
        ExitResult result = TestTerminal.run(new Counter(true), RuntimeOptions.defaults(), terminal);
        assertThat(result.ok()).as(result.toString()).isTrue();
        assertThat(stage.get()).isEqualTo(4);
        // The observer pinned the empty history before q; q itself may be rendered as a new
        // delivered message before the quit envelope is consumed.
        assertThat(terminal.screenText()).contains("counter=1", "DEBUG").doesNotContain("msg=a", "msg=d");
    }

    @Test
    void disabledDiagnosticsDoNotSampleTheInjectedClockOrAffectOutput() {
        TestTerminal terminal = new TestTerminal();
        terminal.endInput();
        RuntimeServices services = RuntimeServices.system()
                .withTimers(new ManualTimers())
                .withEffectExecutor(Runnable::run)
                .withNanoTime(() -> {
                    throw new AssertionError("disabled diagnostics sampled time");
                });
        ExitResult result = TestTerminal.run(new Counter(false), RuntimeOptions.defaults(), terminal, services);
        assertThat(result.ok()).as(result.toString()).isTrue();
        assertThat(terminal.screenText()).contains("counter=0").doesNotContain("DEBUG");
    }

    @Test
    void aRendererDisabledSessionEmitsNoDiagnosticOutput() {
        TestTerminal terminal = new TestTerminal();
        terminal.endInput();
        ExitResult result = new RuntimeLoop<>(
                        new Counter(true),
                        RuntimeOptions.defaults(),
                        terminal.terminal(),
                        RuntimeServices.system().withTimers(new ManualTimers()).withNanoTime(() -> {
                            throw new AssertionError("renderer-disabled diagnostics sampled time");
                        }),
                        null,
                        IoOptions.defaults().withRenderer(false))
                .run();
        assertThat(result.ok()).as(result.toString()).isTrue();
        assertThat(terminal.outputText()).isEmpty();
    }
}
