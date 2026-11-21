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
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bar chart: its bars, its sign, its window, its reader, its themes and its sizes.
 */
class BarChartTest {

    private static final List<BarChart.Category> DISK = List.of(
            BarChart.Category.of("root", "Alpha", 10.0),
            BarChart.Category.of("home", "Beta", 40.0),
            BarChart.Category.of("var", "Gamma", 20.0));

    private final BarChart chart = new BarChart();

    @Test
    void aBarChartPaintsALabelABarAndAReadoutPerCategory() {
        BarChart.Model model = BarChart.Model.of(DISK);

        assertThat(model.selected())
                .as("a chart shows its first category until the reader moves")
                .isEqualTo("root");
        assertThat(model.range())
                .as("the axis spans the values and always includes zero")
                .isEqualTo(new ChartRange(0.0, 40.0));
        assertThat(ElementScreen.render(chart.element(model), 3, 24).lines())
                .containsExactly(
                        "Alpha \u2588\u2588\u2588\u258A            10",
                        "Beta  \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 40",
                        "Gamma \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u258C        20");
        assertThat(ElementScreen.render(chart.view(model, new ViewContext(new Viewport(3, 24))), 3, 24)
                        .lines())
                .isEqualTo(ElementScreen.render(chart.element(model), 3, 24).lines());
    }

    @Test
    void aNegativeValueGrowsLeftOfTheZeroColumn() {
        BarChart signed = new BarChart(BarChart.Config.defaults().withShowValues(false));
        BarChart.Model model = BarChart.Model.of(
                List.of(BarChart.Category.of("up", "", 10.0), BarChart.Category.of("down", "", -10.0)));

        assertThat(model.range()).isEqualTo(new ChartRange(-10.0, 10.0));
        assertThat(ElementScreen.render(signed.element(model), 2, 20).lines())
                .as("the two bars meet on the zero column in the middle of the row")
                .containsExactly(
                        "          \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588",
                        "\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588");
        // The zero column is where the two directions meet, so a chart of one sign starts there.
        assertThat(BarChart.origin(BarChart.Model.of(DISK), 10)).isZero();
        assertThat(BarChart.origin(model, 10)).isEqualTo(5);
        assertThat(BarChart.eighths(model, 10.0, 10)).isEqualTo(40);
        assertThat(BarChart.eighths(model, -10.0, 10)).isEqualTo(-40);
        assertThat(BarChart.eighths(model, Double.NaN, 10)).isZero();
    }

    @Test
    void theWindowFollowsTheReaderAndKeepsItselfInsideTheCategories() {
        List<BarChart.Category> many = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            many.add(BarChart.Category.of("c" + index, "C" + index, index + 1));
        }
        BarChart chart = new BarChart(BarChart.Config.defaults().withRows(3));
        BarChart.Model model = BarChart.Model.of(many).withRows(3);

        assertThat(apply(model, new BarChart.Msg.Down()).offset()).isZero();
        BarChart.Model last = model;
        for (int steps = 0; steps < 4; steps++) {
            last = apply(last, new BarChart.Msg.Down());
        }
        assertThat(last.selected()).isEqualTo("c4");
        assertThat(last.offset()).as("the window follows the reader").isEqualTo(2);
        assertThat(ElementScreen.render(chart.element(last), 3, 20).lines())
                .as("the first row the window paints is the one the reader reached minus two")
                .hasSize(3)
                .allSatisfy(line -> assertThat(line).contains("C"));
        assertThat(apply(last, new BarChart.Msg.RowsChanged(5)).offset())
                .as("a taller window shows everything again")
                .isZero();
        assertThat(apply(last, new BarChart.Msg.Home()).selected()).isEqualTo("c0");
        assertThat(apply(last, new BarChart.Msg.End()).selected()).isEqualTo("c4");
        // A selection that names nothing falls back to the first category, as a menu does.
        assertThat(new BarChart.Model(many, null, "gone", true, 0, 3).selected())
                .isEqualTo("c0");
        assertThat(new BarChart.Model(many, null, null, true, 0, 3).selected()).isEqualTo("c0");
        assertThat(new BarChart.Model(List.of(), null, "c0", true, 0, 3).selectedCategory())
                .isNull();
    }

    @Test
    void theReaderReportsTheCategoryItLandsOnAndNothingAtTheEnds() {
        BarChart.Model model = BarChart.Model.of(DISK);

        assertThat(apply(model, new BarChart.Msg.Down()).selected()).isEqualTo("home");
        assertThat(chart.update(model, new BarChart.Msg.Down()).outputs())
                .containsExactly(new BarChart.Output.Inspected("home", DISK.get(1)));
        assertThat(chart.update(model, new BarChart.Msg.Up()))
                .as("a gesture at the first category moves nothing and reports nothing")
                .satisfies(update -> {
                    assertThat(update.model()).isSameAs(model);
                    assertThat(update.outputs()).isEmpty();
                });
        BarChart.Model last = apply(model, new BarChart.Msg.End());
        assertThat(last.selected()).isEqualTo("var");
        assertThat(chart.update(last, new BarChart.Msg.Down()).outputs()).isEmpty();
        assertThat(chart.update(model, new BarChart.Msg.Select("var")).outputs())
                .containsExactly(new BarChart.Output.Inspected("var", DISK.get(2)));
        assertThat(chart.update(model, new BarChart.Msg.Select("gone")).outputs())
                .as("a press that names no category reports nothing")
                .isEmpty();
        assertThat(chart.update(model, new BarChart.Msg.RowsChanged(4)).outputs())
                .isEmpty();

        Element<BarChart.Msg> root = chart.element(model);
        assertThat(route(root, "down")).containsExactly(new BarChart.Msg.Down());
        assertThat(route(root, "up")).containsExactly(new BarChart.Msg.Up());
        assertThat(route(root, "home")).containsExactly(new BarChart.Msg.Home());
        assertThat(route(root, "end")).containsExactly(new BarChart.Msg.End());
        assertThat(route(root, "x")).isEmpty();
        assertThat(chart.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("previous-category", "next-category", "first-category", "last-category");

        assertThat(press(root, 3, 24, 1, 0)).containsExactly(new BarChart.Msg.Select("home"));
        assertThat(press(root, 3, 24, 2, 20)).containsExactly(new BarChart.Msg.Select("var"));
        assertThat(press(root, 3, 24, 3, 0))
                .as("a press below the last row reads nothing")
                .isEmpty();
        // A drag selects as it moves, which is what makes sweeping down a chart read it.
        assertThat(report(root, 3, 24, PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, 1, 3)))
                .containsExactly(new BarChart.Msg.Select("home"));
        assertThat(report(root, 3, 24, PointerEvent.wheel(PointerButton.WHEEL_DOWN, Modifiers.NONE, 1, 3)))
                .as("the wheel is not a reader gesture here")
                .isEmpty();
    }

    @Test
    void theRowsArePaintedInTheThemesStyles() {
        BarChart.Model model = BarChart.Model.of(DISK);
        BarChart.Model second = apply(model, new BarChart.Msg.Down());
        ElementScreen dark = ElementScreen.render(chart.element(second), 3, 24, Themes.DARK);
        ElementScreen light = ElementScreen.render(chart.element(second), 3, 24, Themes.LIGHT);

        assertThat(dark.styles(0).get(0))
                .as("a label that is not selected is the theme's text")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(dark.styles(0).get(6))
                .as("a bar is the theme's accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(0).get(22)).as("a readout is muted").isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(1).get(0))
                .as("the selected row's label is the text style over the selection, with focus")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(1).get(23).background())
                .as("the selection fills the whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED).background());
        assertThat(light.styles(1).get(23).background())
                .as("a live theme switch repaints the selection")
                .isEqualTo(Themes.LIGHT.style(ThemeToken.SELECTED).background())
                .isNotEqualTo(dark.styles(1).get(23).background());
        assertThat(ElementScreen.render(chart.element(second.withFocus(false)), 3, 24, Themes.DARK)
                        .styles(1)
                        .get(0))
                .as("a row selected without focus keeps the selection and drops the focus")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED).merge(Themes.DARK.style(ThemeToken.TEXT)));
    }

    @Test
    void aNarrowRowKeepsTheBarAndGivesUpTheRest() {
        BarChart.Model model = BarChart.Model.of(DISK);

        BarChart.Layout wide = BarChart.layout(model, true, 24);
        assertThat(wide).isEqualTo(new BarChart.Layout(5, 15, 2));
        BarChart.Layout narrow = BarChart.layout(model, true, 8);
        assertThat(narrow.value()).as("the readout goes first").isZero();
        assertThat(narrow.label()).isEqualTo(2);
        assertThat(narrow.bar()).isEqualTo(5);
        BarChart.Layout tiny = BarChart.layout(model, true, 2);
        assertThat(tiny).isEqualTo(new BarChart.Layout(0, 2, 0));
        assertThat(BarChart.layout(model, false, 24).value())
                .as("a chart configured without readouts reserves no columns for them")
                .isZero();
        // A configured chart without readouts reserves no columns for them.
        assertThat(new BarChart(BarChart.Config.defaults().withShowValues(false))
                        .config()
                        .showValues())
                .isFalse();
        for (int cols = 0; cols <= 24; cols++) {
            int width = cols;
            BarChart.Layout layout = BarChart.layout(model, true, width);
            assertThat(layout.label() + layout.bar() + layout.value())
                    .as("the parts of a %d-column row never claim more than the row", width)
                    .isLessThanOrEqualTo(width);
            assertThat(ElementScreen.render(chart.element(model), 3, width).lines())
                    .as("a chart painted at %d columns", width)
                    .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(width));
        }
        assertThat(ElementScreen.render(chart.element(model), 0, 24).lines()).isEmpty();
        assertThat(ElementScreen.render(chart.element(model), 3, 0).lines()).containsOnly("");
    }

    @Test
    void categoriesAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> BarChart.Category.of("", "label", 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> BarChart.Category.of(null, "label", 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BarChart.Config(-1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> new BarChart(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BarChart.Model.of(null).withCategories(Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class);

        assertThat(BarChart.Category.of("a", null, 1.0).label().plainText()).isEmpty();
        assertThat(BarChart.Category.of("a", "A", 1.0).withValue(2.0).value()).isEqualTo(2.0);
        assertThat(BarChart.Category.of("a", "A", 1.0).withLabel(null).label().plainText())
                .isEmpty();
        assertThat(new BarChart(BarChart.Config.defaults().withRows(4)).config().rows())
                .isEqualTo(4);
        assertThat(BarChart.Model.of(DISK)
                        .withCategories(List.of(BarChart.Category.of("x", "X", 1.0)))
                        .selected())
                .as("a refresh that drops the selected category falls back to the first")
                .isEqualTo("x");
        assertThat(BarChart.Model.of(DISK).withRange(new ChartRange(0.0, 100.0)).range())
                .isEqualTo(new ChartRange(0.0, 100.0));
        assertThat(BarChart.Model.of(DISK).withRows(-2).rows()).isZero();

        BarChart.Model model = BarChart.Model.of(DISK);
        assertThatThrownBy(() -> chart.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.update(null, new BarChart.Msg.Down())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.update(model, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.element(null)).isInstanceOf(NullPointerException.class);
        assertThat(chart.init(new InitContext(null, null)).model().categories()).isEmpty();
    }

    private BarChart.Model apply(BarChart.Model model, BarChart.Msg message) {
        return chart.update(model, message).model();
    }

    private List<BarChart.Msg> route(Element<BarChart.Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                chart.keys().bindings());
    }

    private static List<BarChart.Msg> press(Element<BarChart.Msg> root, int rows, int cols, int row, int col) {
        return report(root, rows, cols, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col));
    }

    private static List<BarChart.Msg> report(Element<BarChart.Msg> root, int rows, int cols, PointerEvent event) {
        List<BarChart.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<BarChart.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, event, recorded::add);
        return recorded;
    }
}
