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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;

import examples.parity.ElementKernelDemo;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.testing.Routing;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 27 acceptance: an application's own widget on the public kernel.
 *
 * <p>Everything here is asserted through the public surface the demo uses: the model the scene's
 * messages reduce to, the rows an element tree paints, and the messages routing produces when the
 * widget or the application answers a gesture.</p>
 */
class ElementKernelTest {

    private static final Viewport VIEWPORT = new Viewport(24, 80);

    private static final ViewContext CONTEXT = new ViewContext(VIEWPORT);

    private static ElementKernelDemo.Scene scene() {
        return new ElementKernelDemo.Scene();
    }

    private static ElementKernelDemo.Model start() {
        return scene().init(new InitContext(VIEWPORT, null)).model();
    }

    private static ElementKernelDemo.Model apply(ElementKernelDemo.Model model, ElementKernelDemo.Msg message) {
        return scene().update(model, message).model();
    }

    private static List<String> rows(ElementKernelDemo.Model model) {
        return ElementScreen.render(scene().view(model, CONTEXT), VIEWPORT.rows(), VIEWPORT.cols())
                .lines();
    }

    /**
     * The row the widget itself paints on.
     *
     * <p>The scene also spells the bar out on a status row, so the answer is the last row holding
     * the filled cells: the one inside the frame, below the rows that describe the state.</p>
     */
    private static int rowOfTheBar(List<String> lines) {
        for (int row = lines.size() - 1; row >= 0; row--) {
            if (lines.get(row).contains(ElementKernelDemo.FULL.repeat(4))) {
                return row;
            }
        }
        throw new AssertionError("the meter painted no filled cells");
    }

    @Test
    void theScenePaintsItsStatusRowsAndTheBarAWidgetholds() {
        ElementKernelDemo.Model model = start();
        List<String> rows = rows(model);
        assertThat(rows.get(0))
                .isEqualTo("kernel widget=painted cells=4/" + model.width() + " focus=meter reports=0 last=none");
        assertThat(rows.get(1))
                .isEqualTo("meter " + ElementKernelDemo.FULL.repeat(4)
                        + ElementKernelDemo.EMPTY.repeat(model.width() - 4));
    }

    @Test
    void theWidgetAnswersTheKeysItOwnsAndTheApplicationAnswersTheRest() {
        ElementKernelDemo.Model model = start();
        // The meter is focused, so it answers the arrow keys itself.
        assertThat(Routing.route(scene(), model, CONTEXT, InputEvent.KeyInput.of(Key.RIGHT)))
                .containsExactly(new ElementKernelDemo.Msg.Filled(5));
        assertThat(apply(model, new ElementKernelDemo.Msg.Filled(5)).cells()).isEqualTo(5);
        // It declines Tab, so the scene's own binding answers it.
        assertThat(Routing.route(scene(), model, CONTEXT, InputEvent.KeyInput.of(Key.TAB)))
                .containsExactly(new ElementKernelDemo.Msg.ToggleFocus());
    }

    @Test
    void theCaretIsTheWidgetsOwnAnswerAndOnlyWhileItHoldsFocus() {
        ElementKernelDemo.Model model = start();
        ElementScreen screen = ElementScreen.render(scene().view(model, CONTEXT), VIEWPORT.rows(), VIEWPORT.cols());
        int barRow = rowOfTheBar(screen.lines());
        int barColumn = screen.lines().get(barRow).indexOf(ElementKernelDemo.FULL);
        assertThat(screen.cursor()).isNotNull();
        assertThat(screen.cursor().row()).isEqualTo(barRow);
        assertThat(screen.cursor().column())
                .as("the cell after the four filled cells")
                .isEqualTo(barColumn + 4);

        ElementKernelDemo.Model blurred = apply(model, new ElementKernelDemo.Msg.ToggleFocus());
        assertThat(ElementScreen.render(scene().view(blurred, CONTEXT), VIEWPORT.rows(), VIEWPORT.cols())
                        .cursor())
                .as("a widget that does not hold focus places no caret")
                .isNull();
    }

    @Test
    void aClickFillsTheBarToTheCellItLandedOn() {
        ElementKernelDemo.Scene scene = scene();
        ElementKernelDemo.Model model = start();
        // The bar sits four cells into its row, on row one; a press six cells further in is the
        // tenth cell of the meter's own rectangle, so the widget answers with ten filled cells.
        ElementScreen screen = ElementScreen.render(scene.view(model, CONTEXT), VIEWPORT.rows(), VIEWPORT.cols());
        int barRow = rowOfTheBar(screen.lines());
        int barColumn = screen.lines().get(barRow).indexOf(ElementKernelDemo.FULL);
        PointerEvent press = PointerEvent.press(PointerButton.LEFT, null, barRow, barColumn + 6);
        var placement = ElementTree.layout(scene.view(model, CONTEXT), VIEWPORT.bounds());
        List<ElementKernelDemo.Msg> produced = new java.util.ArrayList<>();
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, press, produced::add);

        assertThat(produced).containsExactly(new ElementKernelDemo.Msg.Filled(7));
        assertThat(apply(model, new ElementKernelDemo.Msg.Filled(7)).cells()).isEqualTo(7);
    }

    @Test
    void aWiderTerminalResizesTheMeterAndResetStartsOver() {
        ElementKernelDemo.Model wide = start();
        ElementKernelDemo.Model narrowed = apply(wide, new ElementKernelDemo.Msg.Resize(-4));
        assertThat(narrowed.width()).isEqualTo(wide.width() - 4);
        ElementKernelDemo.Model reset = apply(narrowed, new ElementKernelDemo.Msg.Reset());
        assertThat(reset.cells()).isEqualTo(Math.min(ElementKernelDemo.START_CELLS, reset.width()));
        assertThat(reset.last()).isEqualTo("none");
    }

    @Test
    void theDemoDeclaresAFrameTitleAndItsOwnQuit() {
        assertThat(scene().presentation(start()).title()).isEqualTo("Braxton element kernel");
        assertThat(ElementKernelDemo.defaultKeys().actions()).isNotEmpty();
        assertThat(java.util.Arrays.stream(ElementKernelDemo.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("main"))
                        .count())
                .as("the packaged demo needs an entry point")
                .isEqualTo(1);
    }
}
