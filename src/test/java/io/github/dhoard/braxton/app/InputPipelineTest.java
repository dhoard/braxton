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

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.internal.runtime.JLineTestTerminal;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One keystroke path end to end: raw bytes through the decoder and router into an editor reducer,
 * back out through the renderer, observed in the bytes the session wrote.
 */
class InputPipelineTest {

    private static final Editor.Config OPTIONS = Editor.Config.defaults();

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
            boolean submit =
                    result.outputs().stream().anyMatch(Editor.Output.Submit.class::isInstance);
            return Update.next(
                    new Model(
                            submit ? Editor.Model.empty() : result.model(),
                            model.submitted() + (submit ? 1 : 0)));
        }

        @Override
        public View<Editor.Msg> view(Model model, ViewContext context) {
            return Views.keyed(
                    java.util.List.of(Binding.of("q", new Editor.Msg.Submit())),
                    Views.column(
                            Views.text(
                                    "draft=["
                                            + model.draft().text()
                                            + "] submitted="
                                            + model.submitted()),
                            new Editor(OPTIONS)
                                    .view(
                                            (model.draft()).withFocus(true),
                                            new io.github.dhoard.braxton.mvu.ViewContext(
                                                    io.github.dhoard.braxton.layout.Viewport.EMPTY))
                                    .map(action -> action)),
                    java.util.List.of());
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
    @Timeout(20)
    void keystrokesEditTheDraftAndSubmitThroughTheRuntime() throws Exception {
        JLineTestTerminal terminal = new JLineTestTerminal(new Size(4, 40));
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(64, 4, 4)
                ;
        RuntimeServices services = RuntimeServices.system()
                        .withTimers(new ManualTimers())
                        .withEffectExecutor(Runnable::run)
                        .withNanoTime(() -> 1_000L);
        Thread run = Thread.ofVirtual().name("input-pipeline")
                .start(() -> run(new DraftProgram(), options, terminal, services));
        await(() -> terminal.outputText().contains("draft=[] submitted=0"));
        terminal.feed("hi");
        await(() -> terminal.outputText().contains("draft=[hi] submitted=0"));
        terminal.feed("\u007f");
        await(() -> terminal.outputText().contains("draft=[h] submitted=0"));
        terminal.feed("\r");
        await(() -> terminal.outputText().contains("draft=[] submitted=1"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
    }
}
