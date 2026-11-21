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

package examples.harness.ai;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The harness view: one native Braxton view tree built from a model snapshot and explicit geometry.
 *
 * <p>There is no second renderer and no test-only composer path. The same tree the runtime paints is
 * what headless tests read back, so layout, focus, and copy assertions exercise production code.</p>
 */
final class HarnessView {

    private HarnessView() {}

    /**
     * The harness scene, for the tests and diagnostics that paint and route it directly.
     *
     * @param state the model the frame is built from
     * @return the scene as one view
     */
    static Element<Harness.Msg> view(Harness.Model state) {
        return scene(state);
    }

    /**
     * The harness scene as one element tree.
     *
     * <p>The transcript and the composer are mounted children, so their views are unwrapped into
     * this tree: the frame, the rows around them and the background behind them are elements.</p>
     *
     * @param state the model the frame is built from
     * @return the scene
     */
    static Element<Harness.Msg> scene(Harness.Model state) {
        int width = Math.max(1, state.viewport().cols());
        HarnessProjection.Layout layout = HarnessProjection.layout(state);

        List<Element<Harness.Msg>> parts = new ArrayList<>();
        if (layout.header() > 0) {
            parts.add(Elements.fixed(
                    layout.header(), rows(HarnessProjection.header(state, width).subList(0, layout.header()))));
        }
        if (layout.body() > 0) {
            // The transcript component paints its own window at the height the frame gives it.
            var context = new ViewContext(new Viewport(layout.body(), width));
            parts.add(Elements.fixed(layout.body(), Harness.SESSION.view(state, context)));
        }
        if (layout.queued() > 0) {
            parts.add(
                    Elements.fixed(layout.queued(), rows(HarnessProjection.queuedBand(state, width, layout.queued()))));
        }
        if (layout.gap() > 0) {
            parts.add(Elements.fixed(layout.gap(), Elements.text(HarnessProjection.backgroundRow(width))));
        }
        if (layout.statusRule() > 0) {
            parts.add(Elements.fixed(1, Elements.text(HarnessProjection.statusRule(state))));
        }
        if (layout.editorPad() > 0) {
            // The compact frame keeps the draft against the metrics row, as the full frame does.
            parts.add(Elements.spacer(layout.editorPad()));
        }
        if (layout.editor() > 0) {
            if (HarnessProjection.mirroredPrompt(state).isEmpty()) {
                // The editor keeps the caret inside the lines it draws, so it must draw no more
                // rows than this allocation gives it; otherwise the caret's line is clipped away.
                var context = new ViewContext(new Viewport(layout.editor(), width));
                parts.add(Elements.fixed(
                        layout.editor(), Harness.composer(layout.editor()).view(state, context)));
            } else {
                // A queued prompt owns the composer until its turn starts: no caret, no editing.
                parts.add(Elements.fixed(
                        layout.editor(), rows(HarnessProjection.queuedComposerRows(state, width, layout.editor()))));
            }
        }
        if (layout.bottomRule() > 0) {
            parts.add(Elements.fixed(1, Elements.text(HarnessProjection.rule(width))));
        }
        if (layout.footer() > 0) {
            parts.add(Elements.fixed(
                    layout.footer(), rows(HarnessProjection.footer(state, width).subList(0, layout.footer()))));
        }
        if (layout.stats() > 0) {
            parts.add(Elements.fixed(1, Elements.text(HarnessProjection.statsRow(state, width))));
        }

        Element<Harness.Msg> body = Elements.column(parts);
        return Elements.stack(Elements.fill(HarnessTheme.BACKGROUND), body);
    }

    private static Element<Harness.Msg> rows(List<StyledText> rows) {
        List<Element<Harness.Msg>> views = new ArrayList<>(rows.size());
        for (StyledText row : rows) {
            views.add(Elements.text(row));
        }
        return Elements.column(views);
    }

    /**
     * Root capture bindings. These intentionally override the focused editor.
     */
    static List<Binding<Harness.Msg>> bindings() {
        return List.of(
                Binding.of("up", new Harness.Msg.ScrollUp()),
                Binding.of("down", new Harness.Msg.ScrollDown()),
                Binding.of("wheelup", new Harness.Msg.ScrollUp()),
                Binding.of("wheeldown", new Harness.Msg.ScrollDown()),
                Binding.of("pageup", new Harness.Msg.PageUp()),
                Binding.of("pagedown", new Harness.Msg.PageDown()),
                Binding.of("end", new Harness.Msg.ScrollBottom()),
                Binding.of("alt+t", new Harness.Msg.CopyVisible()),
                Binding.of("alt+l", new Harness.Msg.CopyLastReply()),
                Binding.of("tab", new Harness.Msg.FocusComposer()));
    }

    /**
     * Time-based gestures are intercepted so they see the event's capture time: Escape for the
     * turn-cancel window and Ctrl+C for the two-press exit confirmation.
     */
    static Optional<Harness.Msg> intercept(InputEvent event) {
        if (event instanceof InputEvent.KeyInput key) {
            if (key.key().equals(Key.ESCAPE)) {
                return Optional.of(new Harness.Msg.Escape(key.capturedAtNanos()));
            }
            if (key.key().equals(Key.of("c", Modifiers.CTRL))) {
                return Optional.of(new Harness.Msg.QuitRequest(key.capturedAtNanos()));
            }
        }
        if (event instanceof InputEvent.KeyInput
                && Editor.actionFor(event, Harness.editorOptions()).isEmpty()) {
            return Optional.of(new Harness.Msg.UnhandledKey());
        }
        return Optional.empty();
    }

    /**
     * Headless rows for tests and diagnostics; the scene laid out and painted as a frame.
     */
    static List<String> renderLines(Harness.Model state) {
        Size size = state.viewport();
        return ElementScreen.render(scene(state), size.rows(), size.cols()).lines();
    }
}
