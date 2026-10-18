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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.testkit.FakeTerminal;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorAction;
import io.github.dhoard.braxton.widget.EditorIntent;
import io.github.dhoard.braxton.widget.EditorOptions;
import io.github.dhoard.braxton.widget.EditorResult;
import io.github.dhoard.braxton.widget.EditorState;

/**
 * One keystroke path end to end: raw bytes through the decoder and router into an editor reducer,
 * back out through the renderer, observed in the bytes the session wrote.
 */
class InputPipelineTest {

    private static final EditorOptions OPTIONS = EditorOptions.defaults();

    private record Model(EditorState draft, int submitted) {
    }

    private static final class DraftProgram implements Program<Model, EditorAction> {

        @Override
        public Transition<Model, EditorAction> init(InitContext context) {
            return Transition.stay(new Model(EditorState.empty(), 0));
        }

        @Override
        public Transition<Model, EditorAction> update(Model model, EditorAction action) {
            EditorResult result = Editor.update(model.draft(), OPTIONS, action);
            boolean submit = result.intent() instanceof EditorIntent.Submit;
            return Transition.stay(new Model(submit ? EditorState.empty() : result.state(),
                    model.submitted() + (submit ? 1 : 0)));
        }

        @Override
        public View<EditorAction> view(Model model, Viewport viewport) {
            return Views.keyed(java.util.List.of(Binding.of("q", new EditorAction.Submit())),
                    Views.column(
                            Views.text("draft=[" + model.draft().text() + "] submitted="
                                    + model.submitted()),
                            Editor.view(ElementId.of("editor"), model.draft(), OPTIONS, true,
                                    action -> action)),
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
        FakeTerminal terminal = new FakeTerminal(new Size(4, 40));
        RuntimeOptions options = RuntimeOptions.defaults().withLimits(64, 4, 4)
                .withEnvironment(RuntimeEnvironment.system()
                        .withTimers(new FakeTerminal.ManualTimers())
                        .withEffectExecutor(Runnable::run)
                        .withNanoTime(() -> 1_000L));
        Thread run = Thread.ofVirtual().name("input-pipeline")
                .start(() -> Application.run(new DraftProgram(), options, terminal));
        await(() -> terminal.output().contains("draft=[] submitted=0"));
        terminal.feed("hi");
        await(() -> terminal.output().contains("draft=[hi] submitted=0"));
        terminal.feed("\u007f");
        await(() -> terminal.output().contains("draft=[h] submitted=0"));
        terminal.feed("\r");
        await(() -> terminal.output().contains("draft=[] submitted=1"));
        terminal.endInput();
        run.join(5_000L);
        assertThat(run.isAlive()).isFalse();
    }
}
