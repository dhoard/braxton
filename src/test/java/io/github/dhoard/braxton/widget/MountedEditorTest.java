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
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import org.junit.jupiter.api.Test;

/**
 * Two mounted stateful widgets: local element ids and messages stay inside their own instance, and
 * an unmounted instance cannot receive input. This is the widget-level companion of the mount
 * contract tests, which use synthetic children.
 */
class MountedEditorTest {

    record Model(Editor.Model left, Editor.Model right, boolean showRight) {}

    sealed interface Msg {
        record Left(Editor.Msg value) implements Msg {}

        record Right(Editor.Msg value) implements Msg {}

        record LeftOutput(Editor.Output value) implements Msg {}

        record RightOutput(Editor.Output value) implements Msg {}
    }

    private static final class TwoEditors implements Component<Model, Msg, Never> {
        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> left =
                Mount.of(
                        ComponentId.of("left"),
                        new Editor(Editor.Config.defaults()),
                        ModelLens.of(Model::left, (m, c) -> new Model(c, m.right(), m.showRight())),
                        Msg.Left::new,
                        OutputRoute.toMessage(Msg.LeftOutput::new));
        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> right =
                Mount.of(
                        ComponentId.of("right"),
                        new Editor(Editor.Config.defaults()),
                        ModelLens.of(Model::right, (m, c) -> new Model(m.left(), c, m.showRight())),
                        Msg.Right::new,
                        OutputRoute.toMessage(Msg.RightOutput::new));

        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(
                    new Model(
                            left.initialize(context).model(),
                            right.initialize(context).model(),
                            true));
        }

        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Left value -> left.update(model, value.value());
                case Msg.Right value -> right.update(model, value.value());
                case Msg.LeftOutput output -> Update.next(model);
                case Msg.RightOutput output -> Update.next(model);
            };
        }

        public View<Msg> view(Model model, ViewContext context) {
            return model.showRight()
                    ? Views.row(left.view(model, context), right.view(model, context))
                    : left.view(model, context);
        }

        public ComponentTree children(Model model) {
            return model.showRight()
                    ? ComponentTree.of(left.tree(model), right.tree(model))
                    : ComponentTree.of(left.tree(model));
        }
    }

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(1, 40));

    private static Model model(boolean leftFocused, boolean rightFocused, boolean showRight) {
        return new Model(
                Editor.Model.empty().withFocus(leftFocused),
                Editor.Model.empty().withFocus(rightFocused),
                showRight);
    }

    @Test
    void twoMountedEditorsKeepSiblingLocalElementIdsAndRouteTypedInputApart() {
        var component = new TwoEditors();
        var renderer = new ScreenRenderer();
        var typed = InputEvent.KeyInput.of(Key.of("x"), "x");

        var leftFocused = component.view(model(true, false, true), CONTEXT);
        var leftRegions = renderer.regions(leftFocused, CONTEXT.viewport());
        assertThat(leftRegions).hasSize(1);
        assertThat(leftRegions.get(0).id().value()).isEqualTo("input");
        assertThat(leftRegions.get(0).id().path())
                .isEqualTo(ComponentPath.ROOT.append(ComponentId.of("left")));
        assertThat(InputRouter.route(leftFocused, typed).orElseThrow())
                .isInstanceOf(Msg.Left.class);

        var rightFocused = component.view(model(false, true, true), CONTEXT);
        var rightRegions = renderer.regions(rightFocused, CONTEXT.viewport());
        assertThat(rightRegions).hasSize(1);
        assertThat(rightRegions.get(0).id().path())
                .isEqualTo(ComponentPath.ROOT.append(ComponentId.of("right")));
        assertThat(rightRegions.get(0).id()).isNotEqualTo(leftRegions.get(0).id());
        assertThat(InputRouter.route(rightFocused, typed).orElseThrow())
                .isInstanceOf(Msg.Right.class);
    }

    @Test
    void anUnmountedEditorReceivesNoInputAndLeavesItsLocalIdFreeForReuse() {
        var component = new TwoEditors();
        var renderer = new ScreenRenderer();
        var typed = InputEvent.KeyInput.of(Key.of("x"), "x");

        // The right editor declared focus last, then its parent removed it.
        var unmounted = component.view(model(true, true, false), CONTEXT);
        assertThat(InputRouter.route(unmounted, typed).orElseThrow())
                .isInstanceOf(Msg.Left.class);
        assertThat(renderer.regions(unmounted, CONTEXT.viewport()))
                .extracting(region -> region.id().path())
                .containsExactly(ComponentPath.ROOT.append(ComponentId.of("left")));
        assertThat(component.children(model(true, true, false)).nodes())
                .extracting(ComponentTree.Node::id)
                .containsExactly(ComponentId.of("left"));

        // Reinserted under the same component id: the local id is scoped again, not inherited.
        var reinserted = component.view(model(false, true, true), CONTEXT);
        assertThat(renderer.regions(reinserted, CONTEXT.viewport()))
                .extracting(region -> region.id().path())
                .containsExactly(ComponentPath.ROOT.append(ComponentId.of("right")));
    }
}
