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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
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
        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> left = Mount.of(
                ComponentId.of("left"),
                new Editor(EditorConfig.defaults()),
                ModelLens.of(Model::left, (m, c) -> new Model(c, m.right(), m.showRight())),
                Msg.Left::new,
                OutputRoute.toMessage(Msg.LeftOutput::new));
        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> right = Mount.of(
                ComponentId.of("right"),
                new Editor(EditorConfig.defaults()),
                ModelLens.of(Model::right, (m, c) -> new Model(m.left(), c, m.showRight())),
                Msg.Right::new,
                OutputRoute.toMessage(Msg.RightOutput::new));

        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(new Model(
                    left.initialize(context).model(), right.initialize(context).model(), true));
        }

        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Left value -> left.update(model, value.value());
                case Msg.Right value -> right.update(model, value.value());
                case Msg.LeftOutput output -> Update.next(model);
                case Msg.RightOutput output -> Update.next(model);
            };
        }

        public Element<Msg> view(Model model, ViewContext context) {
            return model.showRight()
                    ? Elements.row(left.view(model, context), right.view(model, context))
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

    /**
     * How many leaves report themselves focused: focus is model data, not a tree position.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long focusedLeaves(io.github.dhoard.braxton.ui.Element<?> tree) {
        var screen = io.github.dhoard.braxton.testing.ElementScreen.render(
                tree, CONTEXT.viewport().rows(), CONTEXT.viewport().cols());
        return io.github.dhoard.braxton.ui.InteractionRouter.focusables(screen.placement()).stream()
                .filter(leaf -> ((io.github.dhoard.braxton.ui.Widget) leaf.widget()).focused(leaf.model()))
                .count();
    }

    @Test
    void twoMountedEditorsKeepSiblingLocalElementIdsAndRouteTypedInputApart() {
        var component = new TwoEditors();
        var typed = InputEvent.KeyInput.of(Key.of("x"), "x");

        // Each editor is one leaf of its own: typed input reaches the one the model focused, and the
        // two are structurally distinct, so a mount cannot be confused with its sibling.
        var leftFocused = component.view(model(true, false, true), CONTEXT);
        // Both editors stay Tab stops; the model says which one holds the keyboard.
        assertThat(focusedLeaves(leftFocused)).isEqualTo(1);
        assertThat(InteractionRouter.message(leftFocused, typed).orElseThrow()).isInstanceOf(Msg.Left.class);

        var rightFocused = component.view(model(false, true, true), CONTEXT);
        assertThat(InteractionRouter.message(rightFocused, typed).orElseThrow()).isInstanceOf(Msg.Right.class);
    }

    @Test
    void anUnmountedEditorReceivesNoInputAndLeavesItsLocalIdFreeForReuse() {
        var component = new TwoEditors();
        var typed = InputEvent.KeyInput.of(Key.of("x"), "x");

        // The right editor declared focus last, then its parent removed it.
        var unmounted = component.view(model(true, true, false), CONTEXT);
        assertThat(InteractionRouter.message(unmounted, typed).orElseThrow()).isInstanceOf(Msg.Left.class);
        assertThat(focusedLeaves(unmounted))
                .as("only the remaining editor holds the keyboard")
                .isEqualTo(1);
        assertThat(component.children(model(true, true, false)).nodes())
                .extracting(ComponentTree.Node::id)
                .containsExactly(ComponentId.of("left"));

        // Reinserted under the same component id: it is a fresh leaf again, and the model still
        // decides which editor holds the keyboard.
        var reinserted = component.view(model(false, true, true), CONTEXT);
        assertThat(focusedLeaves(reinserted)).isEqualTo(1);
        assertThat(InteractionRouter.message(reinserted, typed).orElseThrow()).isInstanceOf(Msg.Right.class);
    }
}
