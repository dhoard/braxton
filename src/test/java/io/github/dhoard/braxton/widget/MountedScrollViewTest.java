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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Two mounted panes and an editor under one parent: the composition this widget exists for. This
 * fixture is also the usage example - each pane is a mount with its own model slot, its own scoped
 * element identity and its own offset, and the parent keeps the focus decision.
 */
class MountedScrollViewTest {

    private static final int PANE_ROWS = 3;
    private static final int PANE_COLS = 6;

    record Model(ScrollView.Model log, ScrollView.Model trace, Editor.Model draft,
            boolean showTrace) {}

    sealed interface Msg {
        record Log(ScrollView.Msg value) implements Msg {}

        record Trace(ScrollView.Msg value) implements Msg {}

        record Draft(Editor.Msg value) implements Msg {}

        record DraftOutput(Editor.Output value) implements Msg {}
    }

    /** The parent: two transcript panes side by side over a composer. */
    private static final class PanesAndEditor implements Component<Model, Msg, Never> {

        private final Mount<Model, Msg, Never, ScrollView.Model, ScrollView.Msg, Never> log =
                Mount.of(
                        ComponentId.of("log"),
                        new ScrollView(new ScrollView.Config(PANE_ROWS, false)),
                        ModelLens.of(Model::log,
                                (model, child) -> new Model(child, model.trace(), model.draft(),
                                        model.showTrace())),
                        Msg.Log::new,
                        OutputRoute.toOutput(value -> value));

        private final Mount<Model, Msg, Never, ScrollView.Model, ScrollView.Msg, Never> trace =
                Mount.of(
                        ComponentId.of("trace"),
                        new ScrollView(new ScrollView.Config(PANE_ROWS, false)),
                        ModelLens.of(Model::trace,
                                (model, child) -> new Model(model.log(), child, model.draft(),
                                        model.showTrace())),
                        Msg.Trace::new,
                        OutputRoute.toOutput(value -> value));

        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> draft =
                Mount.of(
                        ComponentId.of("draft"),
                        new Editor(Editor.Config.defaults().withPrompt("> ", Style.NONE)),
                        ModelLens.of(Model::draft,
                                (model, child) -> new Model(model.log(), model.trace(), child,
                                        model.showTrace())),
                        Msg.Draft::new,
                        OutputRoute.toMessage(Msg.DraftOutput::new));

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Model model =
                    new Model(log.initialize(context).model(), trace.initialize(context).model(),
                            Editor.Model.empty().withFocus(false), true);
            // Content is published through the mounts, exactly as an application's own update
            // would after reading a log file or appending a turn.
            model = log.update(model, new ScrollView.Msg.ContentChanged(rows("log", 8))).model();
            model = trace.update(model,
                    new ScrollView.Msg.ContentChanged(rows("trace", 8))).model();
            return Update.next(model);
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Log value -> log.update(model, value.value());
                case Msg.Trace value -> trace.update(model, value.value());
                case Msg.Draft value -> draft.update(model, value.value());
                case Msg.DraftOutput output -> Update.next(model);
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            List<View<Msg>> parts = new ArrayList<>();
            parts.add(
                    Views.row(
                            Views.fixed(PANE_COLS, log.view(model, context)),
                            model.showTrace()
                                    ? Views.fixed(PANE_COLS, trace.view(model, context))
                                    : Views.blank()));
            parts.add(Views.fixed(1, draft.view(model, context)));
            return Views.column(parts);
        }

        @Override
        public ComponentTree children(Model model) {
            return model.showTrace()
                    ? ComponentTree.of(log.tree(model), trace.tree(model), draft.tree(model))
                    : ComponentTree.of(log.tree(model), draft.tree(model));
        }
    }

    private static ScrollView.Content rows(String prefix, int count) {
        List<StyledText> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(StyledText.plain(prefix + index));
        }
        return ScrollView.Content.of(lines);
    }

    private static final ViewContext CONTEXT =
            new ViewContext(new Viewport(PANE_ROWS + 1, PANE_COLS * 2));

    private static InputEvent.KeyInput key(String name) {
        return InputEvent.KeyInput.of(Key.of(name), "");
    }

    private static Model focused(Model model, boolean logFocused, boolean traceFocused,
            boolean draftFocused) {
        return new Model(model.log().withFocus(logFocused),
                model.trace().withFocus(traceFocused),
                model.draft().withFocus(draftFocused),
                model.showTrace());
    }

    private static Model start() {
        var component = new PanesAndEditor();
        return component.init(new InitContext(null, null)).model();
    }

    @Test
    void theTwoPanesScrollIndependently() {
        var component = new PanesAndEditor();
        Model model = start();
        assertThat(model.log().topLine()).isZero();
        assertThat(model.trace().topLine()).isZero();

        model = component.update(model, new Msg.Log(new ScrollView.Msg.Down(2))).model();
        assertThat(model.log().topLine()).isEqualTo(2);
        assertThat(model.trace().topLine()).isZero();

        model = component.update(model, new Msg.Trace(new ScrollView.Msg.PageDown())).model();
        assertThat(model.trace().topLine()).isEqualTo(PANE_ROWS);
        assertThat(model.log().topLine()).isEqualTo(2);

        model = component.update(model, new Msg.Log(new ScrollView.Msg.End())).model();
        assertThat(model.log().topLine()).isEqualTo(5);
        assertThat(model.trace().topLine()).isEqualTo(PANE_ROWS);
    }

    @Test
    void onlyTheFocusedPaneConsumesNavigationKeys() {
        var component = new PanesAndEditor();
        Model model = focused(start(), true, false, false);

        var logView = component.view(model, CONTEXT);
        assertThat(InputRouter.route(logView, key("down")).orElseThrow())
                .isInstanceOfSatisfying(Msg.Log.class,
                        message -> assertThat(message.value())
                                .isEqualTo(new ScrollView.Msg.Down(1)));
        assertThat(InputRouter.route(logView, key("pagedown")).orElseThrow())
                .isInstanceOfSatisfying(Msg.Log.class,
                        message -> assertThat(message.value())
                                .isEqualTo(new ScrollView.Msg.PageDown()));

        var traceView = component.view(focused(model, false, true, false), CONTEXT);
        assertThat(InputRouter.route(traceView, key("down")).orElseThrow())
                .isInstanceOfSatisfying(Msg.Trace.class,
                        message -> assertThat(message.value())
                                .isEqualTo(new ScrollView.Msg.Down(1)));

        var unfocused = component.view(focused(model, false, false, false), CONTEXT);
        assertThat(InputRouter.route(unfocused, key("down")))
                .as("no pane owns focus, so no pane may scroll")
                .isEmpty();
    }

    @Test
    void aPaneDoesNotStealTextInputOrAnnounceFocusToTheEditor() {
        var component = new PanesAndEditor();
        var paneFocused = component.view(focused(start(), true, false, false), CONTEXT);

        assertThat(InputRouter.route(paneFocused, InputEvent.KeyInput.of(Key.of("x"), "x")))
                .as("a scroll pane never consumes printable text")
                .isEmpty();
        assertThat(InputRouter.route(paneFocused, key("end")).orElseThrow())
                .isInstanceOf(Msg.Log.class);
    }

    @Test
    void theAdjacentEditorKeepsItsOwnBindings() {
        var component = new PanesAndEditor();
        var draftFocused = component.view(focused(start(), false, false, true), CONTEXT);

        assertThat(InputRouter.route(draftFocused, key("down")).orElseThrow())
                .isInstanceOfSatisfying(Msg.Draft.class,
                        message -> assertThat(message.value())
                                .isEqualTo(new Editor.Msg.MoveDown()));
        assertThat(InputRouter.route(draftFocused, key("end")).orElseThrow())
                .isInstanceOfSatisfying(Msg.Draft.class,
                        message -> assertThat(message.value())
                                .isEqualTo(new Editor.Msg.MoveEnd()));
        assertThat(InputRouter.route(draftFocused, InputEvent.KeyInput.of(Key.of("x"), "x"))
                .orElseThrow())
                .isInstanceOfSatisfying(Msg.Draft.class,
                        message -> assertThat(message.value())
                                .isEqualTo(new Editor.Msg.Insert("x")));
        assertThat(InputRouter.route(draftFocused, key("pagedown")))
                .as("the editor does not handle paging, and no pane is focused")
                .isEmpty();
    }

    @Test
    void siblingRegionsKeepTheirScopedIdentities() {
        var component = new PanesAndEditor();
        var renderer = new ScreenRenderer();
        Model model = focused(start(), true, false, false);

        var regions = renderer.regions(component.view(model, CONTEXT), CONTEXT.viewport());
        assertThat(regions)
                .extracting(region -> region.id().value())
                .containsExactly("scroll", "scroll", "input");
        assertThat(regions)
                .extracting(region -> region.id().path())
                .containsExactly(
                        ComponentPath.ROOT.append(ComponentId.of("log")),
                        ComponentPath.ROOT.append(ComponentId.of("trace")),
                        ComponentPath.ROOT.append(ComponentId.of("draft")));
    }

    @Test
    void anUnmountedPaneReceivesNoInputAndKeepsItsOffsetWhenReinserted() {
        var component = new PanesAndEditor();
        Model model = focused(start(), false, true, false);
        model = component.update(model, new Msg.Trace(new ScrollView.Msg.Down(3))).model();
        assertThat(model.trace().topLine()).isEqualTo(3);

        Model hidden = new Model(model.log(), model.trace(), model.draft(), false);
        var hiddenView = component.view(hidden, CONTEXT);
        assertThat(InputRouter.route(hiddenView, key("down")))
                .as("the trace pane still declares focus but is not mounted")
                .isEmpty();
        assertThat(new ScreenRenderer().regions(hiddenView, CONTEXT.viewport()))
                .extracting(region -> region.id().path())
                .doesNotContain(ComponentPath.ROOT.append(ComponentId.of("trace")));
        assertThat(component.children(hidden).nodes())
                .extracting(ComponentTree.Node::id)
                .containsExactly(ComponentId.of("log"), ComponentId.of("draft"));

        Model shown = new Model(model.log(), model.trace(), model.draft(), true);
        assertThat(new ScreenRenderer().regions(component.view(shown, CONTEXT),
                CONTEXT.viewport()))
                .extracting(region -> region.id().path())
                .contains(ComponentPath.ROOT.append(ComponentId.of("trace")));
        assertThat(shown.trace().topLine())
                .as("an unmounted pane keeps the state its model owns")
                .isEqualTo(3);
    }

    @Test
    void aStaleChildMessageIsStillAPureStateChangeOfItsOwnSlot() {
        var component = new PanesAndEditor();
        Model model = focused(start(), false, true, false);
        Model hidden = new Model(model.log(), model.trace(), model.draft(), false);

        // The parent may still hold a routed message value from before the unmount. Applying it is
        // an ordinary state change of that slot; it renders nothing and cannot reach sibling state.
        Model updated =
                component.update(hidden,
                        new Msg.Trace(new ScrollView.Msg.PageDown())).model();

        assertThat(updated.showTrace()).isFalse();
        assertThat(updated.trace().topLine()).isEqualTo(PANE_ROWS);
        assertThat(updated.log().topLine()).isZero();
    }

    @Test
    void thePanesPaintTheirOwnWindowsSideBySide() {
        var component = new PanesAndEditor();
        Model model = focused(start(), true, false, false);
        model = component.update(model, new Msg.Trace(new ScrollView.Msg.Down(2))).model();
        model = component.update(model, new Msg.Log(new ScrollView.Msg.Down(1))).model();

        View<Msg> view = component.view(model, CONTEXT);
        assertThat(new ScreenRenderer().plainLines(view, CONTEXT.viewport()).subList(0, 4))
                .containsExactly("log1  trace2", "log2  trace3", "log3  trace4", ">");
    }
}
