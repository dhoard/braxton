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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sparkline: its trend, its reader, its themes and its sizes.
 */
class SparklineTest {

    private final Sparkline sparkline = new Sparkline();

    @Test
    void aSparklinePaintsItsSamplesAsOneRowOfBlocks() {
        Sparkline.Model model = Sparkline.Model.of(values(0, 1, 2, 3, 4, 5, 6, 7));

        assertThat(ElementScreen.render(sparkline.element(model), 1, 8).lines())
                .as("one block per sample, from an eighth of a cell to the whole one")
                .containsExactly("\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588");
        // The view paints the same row the element does, because they are the same leaf.
        assertThat(ElementScreen.render(sparkline.view(model, new ViewContext(new Viewport(1, 8))), 1, 8)
                        .lines())
                .isEqualTo(ElementScreen.render(sparkline.element(model), 1, 8).lines());
        // A wider pane leaves the cells past the samples blank; a narrower one compresses.
        assertThat(ElementScreen.render(sparkline.element(model), 1, 12).lines())
                .as("a wider pane stretches the same samples across it")
                .containsExactly("\u2581\u2581\u2582\u2583\u2583\u2584\u2585\u2585\u2586\u2587\u2587\u2588");
        assertThat(ElementScreen.render(sparkline.element(model), 1, 4).lines())
                .as("four columns cover two samples each, so each paints the higher of the two")
                .containsExactly("\u2582\u2584\u2586\u2588");
        assertThat(ElementScreen.render(sparkline.element(model), 3, 8).lines())
                .as("a sparkline is one row of every row it is offered")
                .containsExactly("\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588", "", "");
        assertThat(ElementScreen.render(sparkline.element(model), 0, 8).lines()).isEmpty();
    }

    @Test
    void aShortSeriesPaintsAcrossTheWholeWidthAndAGapStaysBlank() {
        Sparkline.Model model = Sparkline.Model.of(List.of(1.0, 5.0, 2.0));

        assertThat(ElementScreen.render(sparkline.element(model), 1, 6).lines())
                .as("every sample keeps its share of the width")
                .containsExactly("\u2581\u2581\u2588\u2588\u2583\u2583");

        // A reading nobody has is a gap rather than a zero: its cell is blank and its neighbor is not.
        Sparkline.Model gapped = Sparkline.Model.of(samples(1.0, null, 3.0));
        assertThat(gapped.values().get(1)).isNaN();
        assertThat(ElementScreen.render(sparkline.element(gapped), 1, 3).lines())
                .containsExactly("\u2581 \u2588");
        assertThat(ElementScreen.render(sparkline.element(Sparkline.Model.of(List.of())), 1, 4)
                        .lines())
                .as("a sparkline with no samples reserves its row and paints nothing")
                .containsExactly("");
    }

    @Test
    void theReaderStepsThroughTheSamplesAndReportsEachOne() {
        Sparkline.Model model = Sparkline.Model.of(values(0, 1, 2));
        assertThat(model.selected())
                .as("the reader starts on the newest sample")
                .isEqualTo(2);
        assertThat(model.value()).isEqualTo(2.0);

        assertThat(sparkline.update(model, new Sparkline.Msg.Left()).outputs())
                .containsExactly(new Sparkline.Output.Inspected(1, 1.0));
        Sparkline.Model first = apply(apply(model, new Sparkline.Msg.Left()), new Sparkline.Msg.Home());
        assertThat(first.selected()).isZero();
        assertThat(sparkline.update(first, new Sparkline.Msg.Left()))
                .as("a gesture at the first sample moves nothing")
                .satisfies(update -> {
                    assertThat(update.model()).isSameAs(first);
                    assertThat(update.outputs()).isEmpty();
                });
        Sparkline.Model last = apply(model, new Sparkline.Msg.End());
        assertThat(last.selected()).isEqualTo(2);
        assertThat(sparkline.update(last, new Sparkline.Msg.Right()).outputs()).isEmpty();
        assertThat(apply(first, new Sparkline.Msg.Right()).selected()).isEqualTo(1);

        // A press reports the sample it lands on, and so does a press on the one already selected.
        assertThat(sparkline.update(model, new Sparkline.Msg.Select(0)).outputs())
                .containsExactly(new Sparkline.Output.Inspected(0, 0.0));
        assertThat(sparkline.update(model, new Sparkline.Msg.Select(2)).outputs())
                .as("a press on the selected sample reports it again")
                .containsExactly(new Sparkline.Output.Inspected(2, 2.0));
        assertThat(apply(model, new Sparkline.Msg.Select(9)).selected())
                .as("a press past the samples clamps onto the last one")
                .isEqualTo(2);

        Sparkline.Model empty = Sparkline.Model.of(List.of());
        assertThat(empty.selected()).isEqualTo(-1);
        assertThat(empty.value()).isNaN();
        assertThat(sparkline.update(empty, new Sparkline.Msg.Select(0)).outputs())
                .isEmpty();
        assertThat(sparkline.update(empty, new Sparkline.Msg.Right()).model()).isSameAs(empty);
    }

    @Test
    void theKeysAndPressesReachTheFocusedSparkline() {
        Sparkline.Model model = Sparkline.Model.of(values(0, 1, 2));
        Element<Sparkline.Msg> root = sparkline.element(model);

        assertThat(route(root, "left")).containsExactly(new Sparkline.Msg.Left());
        assertThat(route(root, "right")).containsExactly(new Sparkline.Msg.Right());
        assertThat(route(root, "home")).containsExactly(new Sparkline.Msg.Home());
        assertThat(route(root, "end")).containsExactly(new Sparkline.Msg.End());
        assertThat(route(root, "x"))
                .as("a key the reader does not own falls through to the application")
                .isEmpty();
        assertThat(sparkline.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("previous-sample", "next-sample", "first-sample", "last-sample");

        assertThat(press(root, 1, 8, 0, 3))
                .as("three samples over eight columns puts the press on the second one")
                .containsExactly(new Sparkline.Msg.Select(1));
        Element<Sparkline.Msg> full = sparkline.element(Sparkline.Model.of(values(0, 1, 2, 3, 4, 5, 6, 7)));
        assertThat(press(full, 1, 8, 0, 3)).containsExactly(new Sparkline.Msg.Select(3));
        assertThat(press(full, 1, 8, 0, 7))
                .as("the last cell of a full row reads the last sample")
                .containsExactly(new Sparkline.Msg.Select(7));
        assertThat(press(full, 1, 8, 1, 3))
                .as("a press outside the row is not the sparkline's")
                .isEmpty();
        // A drag keeps reading, which is what lets the reader sweep along the row.
        assertThat(drag(full, 1, 8, 0, 5)).containsExactly(new Sparkline.Msg.Select(5));
        // A sparkline with no samples is not a focus stop and answers nothing.
        Sparkline.Model nothing = Sparkline.Model.of(List.of());
        Element<Sparkline.Msg> blank = sparkline.element(nothing);
        assertThat(ElementScreen.render(blank, 1, 8).focusables())
                .as("a sparkline with nothing to read takes no turn in the focus walk")
                .isEmpty();
        assertThat(sparkline.update(nothing, new Sparkline.Msg.Left()).outputs())
                .as("and a reader gesture on it reports nothing")
                .isEmpty();
        assertThat(press(blank, 1, 8, 0, 3)).isEmpty();
    }

    @Test
    void theSamplesAndTheReadercellArePaintedInTheThemesStyles() {
        Sparkline.Model model = Sparkline.Model.of(values(0, 1, 2, 3, 4, 5, 6, 7));
        ElementScreen dark = ElementScreen.render(sparkline.element(model), 1, 8, Themes.DARK);
        ElementScreen light = ElementScreen.render(sparkline.element(model), 1, 8, Themes.LIGHT);
        ElementScreen idle = ElementScreen.render(sparkline.element(model.withFocus(false)), 1, 8, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("a sample is the theme's accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(0).get(7))
                .as("the reader's cell is the accent over the selection, with focus")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.ACCENT)
                        .merge(Themes.DARK.style(ThemeToken.SELECTED))
                        .merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(idle.styles(0).get(7))
                .as("an unfocused reader keeps its selection and drops the focus")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.SELECTED)));
        assertThat(light.styles(0).get(7))
                .as("a live theme switch repaints the row's selection")
                .isEqualTo(Themes.LIGHT
                        .style(ThemeToken.ACCENT)
                        .merge(Themes.LIGHT.style(ThemeToken.SELECTED))
                        .merge(Themes.LIGHT.style(ThemeToken.FOCUS)))
                .isNotEqualTo(dark.styles(0).get(7));
        // A model scaled to its data and one given a fixed axis place the same value differently.
        Sparkline.Model fixed = model.withRange(new ChartRange(0.0, 14.0));
        assertThat(ElementScreen.render(sparkline.element(fixed), 1, 8).lines())
                .as("a fixed axis puts the same samples lower")
                .containsExactly("\u2581\u2582\u2582\u2583\u2583\u2584\u2584\u2585");
    }

    @Test
    void modelsAreNormalizedAndValidated() {
        Sparkline.Model model = new Sparkline.Model(null, null, 4, false);
        assertThat(model.values()).isEmpty();
        assertThat(model.selected()).isEqualTo(-1);
        assertThat(model.range())
                .as("a chart with nothing to measure gets the empty range")
                .isEqualTo(ChartRange.of(List.of()));
        assertThat(Sparkline.Model.of(values(1, 2)).withSelected(7).selected()).isEqualTo(1);
        assertThat(Sparkline.Model.of(values(1, 2)).withSelected(-4).selected())
                .as("-1 means no reader, and anything below it clamps to it")
                .isEqualTo(-1);
        assertThat(Sparkline.Model.of(values(1, 2))
                        .withValues(values(1, 2, 3, 4))
                        .selected())
                .as("a refresh keeps the reader and clamps it into the new samples")
                .isEqualTo(1);
        assertThat(Sparkline.Model.of(values(1, 2))
                        .withRange(new ChartRange(0, 10))
                        .range())
                .isEqualTo(new ChartRange(0, 10));

        assertThatThrownBy(() -> sparkline.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sparkline.update(null, new Sparkline.Msg.Left()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sparkline.update(model, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sparkline.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sparkline.element(null)).isInstanceOf(NullPointerException.class);
        assertThat(sparkline.init(new InitContext(null, null)).model().values())
                .as("an initialized sparkline has no samples until the application sends a model")
                .isEmpty();
    }

    private Sparkline.Model apply(Sparkline.Model model, Sparkline.Msg message) {
        return sparkline.update(model, message).model();
    }

    private List<Sparkline.Msg> route(Element<Sparkline.Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                sparkline.keys().bindings());
    }

    private static List<Sparkline.Msg> press(Element<Sparkline.Msg> root, int rows, int cols, int row, int col) {
        return report(root, rows, cols, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col));
    }

    private static List<Sparkline.Msg> drag(Element<Sparkline.Msg> root, int rows, int cols, int row, int col) {
        return report(root, rows, cols, PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, row, col));
    }

    private static List<Sparkline.Msg> report(Element<Sparkline.Msg> root, int rows, int cols, PointerEvent event) {
        List<Sparkline.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<Sparkline.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, event, recorded::add);
        return recorded;
    }

    private static List<Double> values(double... samples) {
        List<Double> values = new ArrayList<>(samples.length);
        for (double sample : samples) {
            values.add(sample);
        }
        return values;
    }

    private static List<Double> samples(Double... samples) {
        List<Double> values = new ArrayList<>(samples.length);
        for (Double sample : samples) {
            values.add(sample);
        }
        return values;
    }
}
