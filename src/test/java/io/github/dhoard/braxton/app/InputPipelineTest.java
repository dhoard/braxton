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

package io.github.dhoard.braxton.app;

import static io.github.dhoard.braxton.internal.runtime.TestTerminal.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One keystroke path end to end: raw bytes through the decoder and router into an editor reducer,
 * back out through the renderer, observed in the bytes the session wrote.
 */
class InputPipelineTest {

    private static final EditorConfig OPTIONS = EditorConfig.defaults();

    private record Model(Editor.Model draft, int submitted) {}

    private static final class DraftProgram implements Component<Model, Editor.Msg, Never> {

        @Override
        public Update<Model, Editor.Msg, Never> init(InitContext context) {
            return Update.next(new Model(Editor.Model.empty(), 0));
        }

        @Override
        public Update<Model, Editor.Msg, Never> update(Model model, Editor.Msg action) {
            io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> result =
                    new Editor(OPTIONS).update(model.draft(), action);
            boolean submit = result.outputs().stream().anyMatch(Editor.Output.Submit.class::isInstance);
            return Update.next(
                    new Model(submit ? Editor.Model.empty() : result.model(), model.submitted() + (submit ? 1 : 0)));
        }

        @Override
        public Element<Editor.Msg> view(Model model, ViewContext context) {
            return Elements.column(
                    Elements.text("draft=[" + model.draft().text() + "] submitted=" + model.submitted()),
                    new Editor(OPTIONS)
                            .view(
                                    (model.draft()).withFocus(true),
                                    new io.github.dhoard.braxton.mvu.ViewContext(
                                            io.github.dhoard.braxton.layout.Viewport.EMPTY))
                            .map(action -> action));
        }

        /**
         * The gestures this fixture answers, once no leaf has taken them.
         */
        @Override
        public java.util.List<Binding<Editor.Msg>> claims(Model model) {
            return java.util.List.of(Binding.of("q", new Editor.Msg.Submit()));
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

    /**
     * A scene that binds the two wheel keys and counts what it was told, and nothing else.
     */
    private record WheelModel(int up, int down) {}

    private static final class WheelProgram implements Component<WheelModel, String, Never> {

        @Override
        public Update<WheelModel, String, Never> init(InitContext context) {
            return Update.next(new WheelModel(0, 0));
        }

        @Override
        public Update<WheelModel, String, Never> update(WheelModel model, String action) {
            return Update.next(
                    switch (action) {
                        case "up" -> new WheelModel(model.up() + 1, model.down());
                        case "down" -> new WheelModel(model.up(), model.down() + 1);
                        default -> model;
                    });
        }

        @Override
        public Element<String> view(WheelModel model, ViewContext context) {
            return Elements.column(Elements.text("wheel up=" + model.up() + " down=" + model.down()));
        }

        /**
         * The wheel keys are the application's: nothing in the tree answers a report it was shown.
         */
        @Override
        public java.util.List<Binding<String>> bindings(WheelModel model) {
            return java.util.List.of(Binding.of("wheelup", "up"), Binding.of("wheeldown", "down"));
        }
    }

    @Test
    @Timeout(20)
    void aWheelNothingConsumesIsOfferedOnceAsTheKeyItUsedToBe() throws Exception {
        TestTerminal terminal = new TestTerminal(new Size(4, 40));
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(64, 4, 4);
        RuntimeServices services = RuntimeServices.system()
                .withTimers(new ManualTimers())
                .withEffectExecutor(Runnable::run)
                .withNanoTime(() -> 1_000L);
        Thread run = Thread.ofVirtual()
                .name("wheel-pipeline")
                .start(() -> run(new WheelProgram(), options, terminal, services));
        await(() -> terminal.screenText().contains("wheel up=0 down=0"));
        // A wheel report is a pointer report first: nothing in the tree takes it, so the runtime
        // turns it into the wheel key an application has always been able to bind.
        terminal.feed("\u001B[<65;4;2M");
        await(() -> terminal.screenText().contains("wheel up=0 down=1"));
        terminal.feed("\u001B[<64;4;2M");
        await(() -> terminal.screenText().contains("wheel up=1 down=1"));
        // A horizontal wheel has no key to become, so it reaches nobody and changes nothing.
        terminal.feed("\u001B[<66;4;2M");
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
        assertThat(terminal.screenText()).contains("wheel up=1 down=1");
    }

    @Test
    @Timeout(20)
    void keystrokesEditTheDraftAndSubmitThroughTheRuntime() throws Exception {
        TestTerminal terminal = new TestTerminal(new Size(4, 40));
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(64, 4, 4);
        RuntimeServices services = RuntimeServices.system()
                .withTimers(new ManualTimers())
                .withEffectExecutor(Runnable::run)
                .withNanoTime(() -> 1_000L);
        Thread run = Thread.ofVirtual()
                .name("input-pipeline")
                .start(() -> run(new DraftProgram(), options, terminal, services));
        await(() -> terminal.screenText().contains("draft=[] submitted=0"));
        terminal.feed("hi");
        await(() -> terminal.screenText().contains("draft=[hi] submitted=0"));
        terminal.feed("\u007f");
        await(() -> terminal.screenText().contains("draft=[h] submitted=0"));
        terminal.feed("\r");
        await(() -> terminal.screenText().contains("draft=[] submitted=1"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
    }
}
