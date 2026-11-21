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
 * The line chart: its plot, its legend, its reader, its themes and its sizes.
 */
class LineChartTest {

    private final LineChart chart = new LineChart();

    @Test
    void aSeriesIsDrawnAsALineAcrossThePlotsSubCells() {
        LineChart.Model model = LineChart.Model.of(List.of(LineChart.Series.of(values(0.0, 1.0))));

        assertThat(model.size()).isEqualTo(2);
        assertThat(model.selected())
                .as("the reader starts on the newest sample")
                .isEqualTo(1);
        assertThat(ElementScreen.render(chart.element(model), 2, 2).lines())
                .as("the line runs from the bottom left sub-cell to the top right one")
                .containsExactly(" \u280C", "\u2850\u2502");
        // The plot fills the pane, so a wider or taller chart keeps the same shape at more cells.
        assertThat(ElementScreen.render(chart.element(model), 4, 4).lines())
                .hasSize(4)
                .allSatisfy(line -> assertThat(line).hasSize(4))
                .doesNotContain("    ");
        assertThat(ElementScreen.render(chart.element(model), 0, 4).lines()).isEmpty();
        assertThat(ElementScreen.render(chart.element(model), 4, 0).lines()).containsOnly("");
        assertThat(ElementScreen.render(chart.element(LineChart.Model.of(List.of())), 2, 4)
                        .lines())
                .as("a chart with no series paints an empty plot")
                .containsExactly("", "");
    }

    @Test
    void aFlatSeriesIsAFlatLineAndAGapBreaksTheLine() {
        LineChart.Model flat = LineChart.Model.of(List.of(LineChart.Series.of(values(5.0, 5.0, 5.0))));
        assertThat(flat.range())
                .as("a constant series collapses its range, and the chart paints the middle")
                .isEqualTo(new ChartRange(5.0, 5.0));
        assertThat(ElementScreen.render(chart.element(flat), 1, 3).lines()).containsExactly("\u2824\u2824\u2824");

        LineChart.Model gapped =
                LineChart.Model.of(List.of(LineChart.Series.of(new ArrayList<>(List.of(0.0, Double.NaN, 1.0)))));
        assertThat(gapped.series().get(0).values().get(1)).isNaN();
        assertThat(ElementScreen.render(chart.element(gapped), 1, 3).lines())
                .as("a gap is a hole in the line rather than a point at zero")
                .containsExactly("\u2840 \u2808");
    }

    @Test
    void aNamedSeriesGetsALegendRowAndAnUnnamedOneDoesNot() {
        LineChart.Model named = LineChart.Model.of(List.of(
                LineChart.Series.of("cpu", values(0.0, 1.0)),
                LineChart.Series.of("mem", ThemeToken.SUCCESS, values(1.0, 0.0))));
        assertThat(ElementScreen.render(chart.element(named), 3, 12).lines().get(0))
                .as("the legend names each series in order")
                .startsWith("\u25CF cpu  \u25CF mem");

        LineChart.Model unnamed = LineChart.Model.of(List.of(LineChart.Series.of(values(0.0, 1.0))));
        assertThat(ElementScreen.render(chart.element(unnamed), 3, 12).lines().get(0))
                .as("without a name the whole rectangle is plot")
                .isNotBlank();

        // A chart of one row has no room for a legend, so the plot keeps it.
        assertThat(ElementScreen.render(chart.element(named), 1, 12).lines())
                .hasSize(1)
                .allSatisfy(line -> assertThat(line).doesNotContain("cpu"));
        assertThat(ElementScreen.render(chart.element(named), 6, 12).lines()).hasSize(6);
    }

    @Test
    void theReaderPlacesAGuideAndReportsEverySeriesValue() {
        LineChart.Model model = LineChart.Model.of(
                List.of(LineChart.Series.of("cpu", values(0.0, 0.5, 1.0)), LineChart.Series.of("mem", values(1.0))));
        assertThat(model.size()).isEqualTo(3);
        assertThat(model.values(2))
                .as("a series with no sample at the index contributes a gap")
                .containsExactly(1.0, Double.NaN);
        assertThat(chart.update(model, new LineChart.Msg.Home()).outputs())
                .containsExactly(new LineChart.Output.Inspected(0, List.of(0.0, 1.0)));
        assertThat(apply(model, new LineChart.Msg.Home()).selected()).isZero();
        assertThat(chart.update(apply(model, new LineChart.Msg.Home()), new LineChart.Msg.Left()))
                .as("a gesture at the first sample moves nothing")
                .satisfies(update -> {
                    assertThat(update.outputs()).isEmpty();
                    assertThat(update.model().selected()).isZero();
                });
        LineChart.Model middle = apply(model, new LineChart.Msg.Left());
        assertThat(middle.selected()).isEqualTo(1);
        // The guide is a rule down the reader's column, so it shows wherever the line is not.
        LineChart.Model rising = LineChart.Model.of(List.of(LineChart.Series.of(values(0.0, 1.0))))
                .withSelected(0);
        assertThat(ElementScreen.render(chart.element(rising), 4, 2).lines())
                .as("the guide column is painted where the reader is")
                .containsExactly("\u2502\u2808", "\u2502\u2802", "\u2820", "\u2840");

        Element<LineChart.Msg> root = chart.element(middle);
        assertThat(route(root, "left")).containsExactly(new LineChart.Msg.Left());
        assertThat(route(root, "right")).containsExactly(new LineChart.Msg.Right());
        assertThat(route(root, "home")).containsExactly(new LineChart.Msg.Home());
        assertThat(route(root, "end")).containsExactly(new LineChart.Msg.End());
        assertThat(route(root, "x")).isEmpty();
        assertThat(chart.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("previous-sample", "next-sample", "first-sample", "last-sample");

        assertThat(press(root, 2, 3, 0, 0)).containsExactly(new LineChart.Msg.Select(0));
        LineChart.Model last = apply(model, new LineChart.Msg.End());
        assertThat(press(chart.element(last), 2, 3, 0, 2))
                .as("the last column reads the last sample")
                .containsExactly(new LineChart.Msg.Select(2));
        assertThat(press(root, 2, 3, 2, 0))
                .as("a press below the chart is not the chart's")
                .isEmpty();
        assertThat(report(root, 2, 3, PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, 0, 2)))
                .as("a drag reads the column it is over")
                .containsExactly(new LineChart.Msg.Select(2));
        assertThat(press(chart.element(LineChart.Model.of(List.of())), 2, 3, 0, 0))
                .as("a chart with no samples has nothing to read")
                .isEmpty();
        assertThat(LineChart.column(2, 3, 2)).isEqualTo(1);
        assertThat(LineChart.sampleAt(0, 3, 3)).isZero();
        assertThat(LineChart.sampleAt(2, 3, 3)).isEqualTo(2);
    }

    @Test
    void eachSeriesIsPaintedInItsOwnThemeRoleAcrossThemes() {
        LineChart.Model model = LineChart.Model.of(List.of(
                LineChart.Series.of(values(0.0, 1.0)), LineChart.Series.of("", ThemeToken.ERROR, values(1.0, 0.0))));
        ElementScreen dark = ElementScreen.render(chart.element(model.withFocus(false)), 2, 2, Themes.DARK);
        ElementScreen contrast =
                ElementScreen.render(chart.element(model.withFocus(false)), 2, 2, Themes.HIGH_CONTRAST);

        assertThat(dark.styles(1).get(0))
                .as("the first series is painted in the theme's accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(0).get(0))
                .as("the second series is painted in the role it asked for")
                .isEqualTo(Themes.DARK.style(ThemeToken.ERROR));
        assertThat(contrast.styles(1).get(0))
                .as("a live theme switch repaints the plot")
                .isEqualTo(Themes.HIGH_CONTRAST.style(ThemeToken.ACCENT))
                .isNotEqualTo(dark.styles(1).get(0));
    }

    @Test
    void modelsAreNormalizedAndValidated() {
        LineChart.Model model = new LineChart.Model(null, null, 3, false);
        assertThat(model.series()).isEmpty();
        assertThat(model.size()).isZero();
        assertThat(model.selected()).isEqualTo(-1);
        assertThat(model.values(0)).isEmpty();
        assertThat(model.range()).isEqualTo(ChartRange.of(List.of()));
        assertThat(LineChart.Model.of(List.of(LineChart.Series.of(values(1.0, 2.0))))
                        .withSelected(9)
                        .selected())
                .isEqualTo(1);
        assertThat(LineChart.Model.of(List.of(LineChart.Series.of(values(1.0, 2.0))))
                        .withSelected(-9)
                        .selected())
                .isEqualTo(-1);
        assertThat(LineChart.Model.of(List.of(LineChart.Series.of(values(1.0, 2.0))))
                        .withRange(new ChartRange(0.0, 9.0))
                        .range())
                .isEqualTo(new ChartRange(0.0, 9.0));
        assertThat(LineChart.Model.of(List.of(LineChart.Series.of(values(1.0, 2.0))))
                        .withSeries(List.of(LineChart.Series.of(values(1.0, 2.0, 3.0))))
                        .selected())
                .as("a refresh keeps the reader and clamps it into the new samples")
                .isEqualTo(1);
        assertThat(LineChart.Series.of((List<Double>) null).values()).isEmpty();
        assertThat(LineChart.Series.of(null, null).label()).isEmpty();
        assertThat(LineChart.Series.of("cpu", values(1.0))
                        .withToken(ThemeToken.WARNING)
                        .token())
                .isEqualTo(ThemeToken.WARNING);
        assertThat(LineChart.Series.of("cpu", values(1.0)).withLabel(null).label())
                .isEmpty();
        assertThat(LineChart.Series.of("cpu", values(1.0, null)).values().get(1))
                .isNaN();
        assertThat(LineChart.Series.of("cpu", values(1.0)).size()).isEqualTo(1);

        assertThatThrownBy(() -> chart.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.update(null, new LineChart.Msg.Left())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.update(model, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chart.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LineChart.Model.of(null).withSeries(List.of((LineChart.Series) null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(chart.init(new InitContext(null, null)).model().series()).isEmpty();
        assertThat(chart.view(LineChart.Model.of(List.of()), new ViewContext(new Viewport(2, 2))))
                .isNotNull();
    }

    private LineChart.Model apply(LineChart.Model model, LineChart.Msg message) {
        return chart.update(model, message).model();
    }

    private List<LineChart.Msg> route(Element<LineChart.Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                chart.keys().bindings());
    }

    private static List<LineChart.Msg> press(Element<LineChart.Msg> root, int rows, int cols, int row, int col) {
        return report(root, rows, cols, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col));
    }

    private static List<LineChart.Msg> report(Element<LineChart.Msg> root, int rows, int cols, PointerEvent event) {
        List<LineChart.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<LineChart.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, event, recorded::add);
        return recorded;
    }

    private static List<Double> values(Double... samples) {
        List<Double> values = new ArrayList<>(samples.length);
        for (Double sample : samples) {
            values.add(sample);
        }
        return values;
    }
}
