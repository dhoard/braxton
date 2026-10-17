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

package examples.harness.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.widget.Editor;

/**
 * The harness view: one native Braxton view tree built from a model snapshot and explicit geometry.
 *
 * <p>There is no second renderer and no test-only composer path. The same tree the runtime paints is
 * what headless tests read back, so layout, focus, and copy assertions exercise production code.</p>
 */
final class HarnessView {

    private static final ElementId COMPOSER = ElementId.of("composer");

    private HarnessView() {
    }

    static View<HarnessMsg> view(HarnessState state) {
        int width = state.viewport().cols();
        int height = state.viewport().rows();
        HarnessProjection.Allocation allocation =
                HarnessProjection.allocate(height, state.editorRows());

        List<View<HarnessMsg>> parts = new ArrayList<>();
        if (allocation.transcript() > 0) {
            List<View<HarnessMsg>> rows = new ArrayList<>();
            for (StyledText row : HarnessProjection.window(state, width, height,
                    state.editorRows())) {
                rows.add(Views.text(row));
            }
            parts.add(Views.fixed(allocation.transcript(), Views.column(rows)));
        }
        if (allocation.gap() > 0) {
            parts.add(Views.fixed(allocation.gap(), Views.text(
                    HarnessProjection.row(width, HarnessTheme.BACKGROUND_COLOR))));
        }
        if (allocation.topRule() > 0) {
            parts.add(Views.fixed(allocation.topRule(),
                    Views.text(HarnessProjection.topRule(state))));
        }
        if (allocation.composer() > 0) {
            parts.add(Views.fixed(allocation.composer(),
                    Editor.view(COMPOSER, state.draft(), HarnessProgram.editorOptions(), true,
                            HarnessMsg.Editor::new)));
        }
        if (allocation.bottomRule() > 0) {
            parts.add(Views.fixed(allocation.bottomRule(),
                    Views.text(HarnessProjection.bottomRule(state))));
        }
        if (allocation.footer() > 0) {
            List<StyledText> footer = HarnessProjection.footer(state);
            List<View<HarnessMsg>> rows = new ArrayList<>();
            for (int i = 0; i < allocation.footer() && i < footer.size(); i++) {
                rows.add(Views.text(footer.get(i)));
            }
            while (rows.size() < allocation.footer()) {
                rows.add(Views.text(HarnessProjection.row(width, HarnessTheme.BACKGROUND_COLOR)));
            }
            parts.add(Views.fixed(allocation.footer(), Views.column(rows)));
        }

        View<HarnessMsg> body = Views.column(parts);
        View<HarnessMsg> root = Views.overlay(List.of(
                Views.fill(HarnessTheme.BACKGROUND), body), false);
        return Views.keyed(bindings(), HarnessView::intercept, root, List.of());
    }

    /** Root capture bindings. These intentionally override the focused editor. */
    static List<Binding<HarnessMsg>> bindings() {
        return List.of(
                Binding.of("up", new HarnessMsg.ScrollUp()),
                Binding.of("down", new HarnessMsg.ScrollDown()),
                Binding.of("wheelup", new HarnessMsg.ScrollUp()),
                Binding.of("wheeldown", new HarnessMsg.ScrollDown()),
                Binding.of("pageup", new HarnessMsg.PageUp()),
                Binding.of("pagedown", new HarnessMsg.PageDown()),
                Binding.of("end", new HarnessMsg.ScrollBottom()),
                Binding.of("alt+t", new HarnessMsg.CopyVisible()),
                Binding.of("alt+l", new HarnessMsg.CopyLastReply()),
                Binding.of("tab", new HarnessMsg.FocusComposer()));
    }

    /**
     * Time-based gestures are intercepted so they see the event's capture time: Escape for the
     * turn-cancel window and Ctrl+C for the two-press exit confirmation.
     */
    private static Optional<HarnessMsg> intercept(InputEvent event) {
        if (event instanceof InputEvent.KeyInput key) {
            if (key.key().equals(Key.ESCAPE)) {
                return Optional.of(new HarnessMsg.Escape(key.capturedAtNanos()));
            }
            if (key.key().equals(Key.of("c", io.github.dhoard.braxton.input.Modifiers.CTRL))) {
                return Optional.of(new HarnessMsg.QuitRequest(key.capturedAtNanos()));
            }
        }
        return Optional.empty();
    }

    /** Headless rows for tests and diagnostics; uses the production view and renderer. */
    static List<String> renderLines(HarnessState state) {
        Size size = state.viewport();
        return new ScreenRenderer().plainLines(view(state), new Viewport(size.rows(), size.cols()));
    }
}
