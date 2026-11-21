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

package io.github.dhoard.braxton.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The simulated user driver: one composed application, driven headlessly through real MVU.
 */
class PilotTest {

    private record Counter() implements Component<Integer, String, Never> {
        private static final EffectId TIMER = EffectId.of("counter-timer");

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return switch (message) {
                case "inc" -> Update.next(model + 1);
                case "later" -> Update.next(model, Command.delay(TIMER, Duration.ofSeconds(1), "inc", "rejected"));
                case "cancel" -> Update.next(model, Command.cancel(TIMER));
                default -> Update.next(model);
            };
        }

        @Override
        public Element<String> view(Integer model, ViewContext context) {
            return Elements.text("count: " + model, Style.fg(new Color(255, 0, 0)));
        }
    }

    private record AppModel(int child, String last, String size) {}

    private record App() implements Component<AppModel, String, Never> {
        private static final Counter COUNTER = new Counter();

        @Override
        public Update<AppModel, String, Never> init(InitContext context) {
            return Update.next(new AppModel(0, "init", context.viewport().toString()));
        }

        @Override
        public Update<AppModel, String, Never> update(AppModel model, String message) {
            return switch (message) {
                case "inc", "later", "cancel" -> {
                    var child = COUNTER.update(model.child(), message);
                    yield Update.next(new AppModel(child.model(), model.last(), model.size()), child.command());
                }
                case "print" -> Update.next(model, Command.println("a printed line"));
                case "task" ->
                    Update.next(
                            model,
                            Command.task(
                                    EffectId.of("answer"),
                                    () -> 42,
                                    value -> "task:" + value,
                                    failure -> "task:failed"));
                case "clipboard" -> Update.next(model, Command.readClipboard(text -> "clipboard"));
                case "quit" -> Update.next(model, Command.quit(7));
                default ->
                    Update.next(new AppModel(
                            model.child(),
                            switch (message) {
                                case "paste" -> "pasted";
                                case "pointer" -> "clicked";
                                default -> message;
                            },
                            model.size()));
            };
        }

        @Override
        public Element<String> view(AppModel model, ViewContext context) {
            return Elements.column(
                    Elements.text("last: " + model.last()),
                    Elements.text("size: " + context.viewport()),
                    COUNTER.view(model.child(), context));
        }
    }

    private static Pilot<AppModel, String> pilot() {
        return Pilot.of(
                new App(),
                event -> switch (event) {
                    case InputEvent.KeyInput key ->
                        switch (key.key().name()) {
                            case "a" -> "inc";
                            case "d" -> "later";
                            case "c" -> "cancel";
                            case "p" -> "print";
                            case "t" -> "task";
                            case "q" -> "quit";
                            default -> key.key().name();
                        };
                    case InputEvent.PasteInput ignored -> "paste";
                    case InputEvent.PointerInput ignored -> "pointer";
                    case InputEvent.ResizeInput ignored -> "resize";
                    default -> "other";
                },
                new Viewport(8, 40));
    }

    @Test
    void drivesKeysPointerPasteAndResizeWithTextAndStyleSnapshots() {
        Pilot<AppModel, String> pilot =
                pilot().press("a").click(1, 2).paste("hello").resize(6, 24);

        assertThat(pilot.model().child()).isEqualTo(1);
        assertThat(pilot.model().last()).isEqualTo("resize");
        assertThat(pilot.viewport()).isEqualTo(new Viewport(6, 24));
        assertThat(pilot.snapshot().text())
                .contains("last: resize")
                .contains("size: 6x24")
                .contains("count: 1");
        assertThat(pilot.snapshot().styles().get(2).get(0)).contains("red=255");
        assertThat(pilot.trace())
                .extracting(Pilot.Step::label)
                .containsExactly("init", "key:a", "pointer:PRESS", "pointer:RELEASE", "paste", "resize:6x24");
        assertThat(pilot.snapshot().rows()).isEqualTo(6);
    }

    @Test
    void executesDelayedPrintedTaskAndQuitCommands() {
        Pilot<AppModel, String> pilot = pilot();

        pilot.dispatch("later").advance(Duration.ofMillis(999));
        assertThat(pilot.model().child()).isZero();
        pilot.advance(Duration.ofMillis(1));
        assertThat(pilot.model().child()).isEqualTo(1);

        pilot.dispatch("later").dispatch("cancel").advance(Duration.ofSeconds(2));
        assertThat(pilot.model().child()).isEqualTo(1);

        pilot.dispatch("print").dispatch("task").dispatch("quit");
        assertThat(pilot.printedLines()).containsExactly("a printed line");
        assertThat(pilot.model().last()).isEqualTo("task:42");
        assertThat(pilot.quitStatus()).hasValue(7);
        assertThat(pilot.nowNanos()).isEqualTo(Duration.ofSeconds(3).toNanos());
    }

    @Test
    void rejectsCommandsThatNeedARealSession() {
        Pilot<AppModel, String> pilot = pilot();

        assertThatThrownBy(() -> pilot.dispatch("clipboard"))
                .isInstanceOf(Pilot.UnsupportedCommandException.class)
                .hasMessage("ReadClipboard");
    }

    @Test
    void rejectsABackwardsClock() {
        assertThatThrownBy(() -> pilot().advance(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void pasteInsertsTextWithoutRunningKeyBindings() {
        Pilot<AppModel, String> pilot = pilot().input(InputEvent.KeyInput.of(Key.of("z"), "z", 0L));

        assertThat(pilot.model().last()).isEqualTo("z");
        assertThat(pilot.model().child()).isZero();
    }
}
