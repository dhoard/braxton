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
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
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
 * The gauge: its bar, its readout, its narrow sizes and the gestures it does not answer.
 */
class GaugeTest {

    private final Gauge<String> gauge = new Gauge<>();

    @Test
    void aGaugePaintsAFilledBarATrackAndItsPercentage() {
        Element<String> element = gauge.element(Gauge.Model.of(0.5));

        assertThat(ElementScreen.render(element, 1, 16).lines())
                .as("half of eleven cells, the track that is left, and the percentage on the right")
                .containsExactly("\u2588\u2588\u2588\u2588\u2588\u258C\u2591\u2591\u2591\u2591\u2591  50%");
        assertThat(ElementScreen.render(element, 1, 16, Themes.DARK).lines())
                .isEqualTo(ElementScreen.render(element, 1, 16).lines());
        // A full and an empty gauge, and one whose value is past the end of its range.
        assertThat(ElementScreen.render(gauge.element(Gauge.Model.of(1.0)), 1, 16)
                        .lines())
                .containsExactly("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 100%");
        assertThat(ElementScreen.render(gauge.element(Gauge.Model.of(0.0)), 1, 16)
                        .lines())
                .containsExactly("\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591   0%");
        assertThat(ElementScreen.render(gauge.element(Gauge.Model.of(4.0)), 1, 16)
                        .lines())
                .as("a value past the range is clamped onto the end it is past")
                .isEqualTo(ElementScreen.render(gauge.element(Gauge.Model.of(1.0)), 1, 16)
                        .lines());
        assertThat(ElementScreen.render(element, 2, 16).lines())
                .as("a gauge is one row of every row it is offered")
                .containsExactly("\u2588\u2588\u2588\u2588\u2588\u258C\u2591\u2591\u2591\u2591\u2591  50%", "");
        assertThat(ElementScreen.render(element, 0, 16).lines()).isEmpty();
    }

    @Test
    void aLabelPaintsInFrontOfTheBarAndTheReadoutCanBeTurnedOff() {
        Gauge<String> labelled = new Gauge<>(Gauge.Config.of("usage"));
        assertThat(ElementScreen.render(labelled.element(Gauge.Model.of(0.5)), 1, 16)
                        .lines())
                .containsExactly("usage \u2588\u2588\u258C\u2591\u2591  50%");
        assertThat(ElementScreen.render(
                                new Gauge<String>(Gauge.Config.of("usage").withShowValue(false))
                                        .element(Gauge.Model.of(0.5)),
                                1,
                                16)
                        .lines())
                .as("a gauge configured without a readout keeps the columns for the bar")
                .containsExactly("usage \u2588\u2588\u2588\u2588\u2588\u2591\u2591\u2591\u2591\u2591");
        assertThat(new Gauge<String>(Gauge.Config.of("x").withLabel(null))
                        .config()
                        .label()
                        .plainText())
                .isEmpty();
        assertThat(Gauge.Config.of(null).label().plainText()).isEmpty();
        assertThat(Gauge.Config.defaults().showValue()).isTrue();
    }

    @Test
    void aNarrowGaugeKeepsTheBarAndGivesUpTheReadoutThenTheLabel() {
        Gauge<String> labelled = new Gauge<>(Gauge.Config.of("usage"));

        assertThat(Gauge.layout(labelled.config().label(), true, 16)).isEqualTo(new Gauge.Layout(5, 5, 4));
        assertThat(Gauge.layout(labelled.config().label(), true, 6))
                .as("the readout goes before the label does")
                .isEqualTo(new Gauge.Layout(2, 3, 0));
        assertThat(Gauge.layout(labelled.config().label(), true, 2)).isEqualTo(new Gauge.Layout(0, 2, 0));
        assertThat(Gauge.layout(labelled.config().label(), true, 0)).isEqualTo(new Gauge.Layout(0, 0, 0));
        assertThat(ElementScreen.render(labelled.element(Gauge.Model.of(0.5)), 1, 6)
                        .lines())
                .containsExactly("us \u2588\u258C\u2591");
        assertThat(ElementScreen.render(labelled.element(Gauge.Model.of(0.5)), 1, 2)
                        .lines())
                .containsExactly("\u2588\u2591");
        for (int cols = 0; cols <= 20; cols++) {
            int width = cols;
            Gauge.Layout layout = Gauge.layout(labelled.config().label(), true, width);
            assertThat(layout.label() + layout.bar() + layout.readout())
                    .as("the parts of a %d-column row never claim more than the row", width)
                    .isLessThanOrEqualTo(width);
            assertThat(ElementScreen.render(labelled.element(Gauge.Model.of(0.42)), 1, width)
                            .lines())
                    .as("a gauge painted at %d columns", width)
                    .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(width));
        }
    }

    @Test
    void aGaugeIsAnIndicatorSoItAnswersNoGesture() {
        Element<String> element = gauge.element(Gauge.Model.of(0.5));

        assertThat(ElementScreen.render(element, 1, 16).focusables())
                .as("a gauge takes no turn in the focus walk")
                .isEmpty();
        List<String> messages = new ArrayList<>();
        ElementTree.Placement<String> placement = ElementTree.layout(element, new Rect(0, 0, 1, 16));
        InteractionRouter.pointer(
                placement,
                InteractionRouter.Interaction.NONE,
                PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 3),
                messages::add);
        assertThat(messages)
                .as("a click on a gauge belongs to what it is drawn over")
                .isEmpty();
        assertThat(InteractionRouter.route(element, InputEvent.KeyInput.of(Key.parse("left")), List.of(), List.of()))
                .isEmpty();
        assertThat(gauge.hit(new Rect(0, 0, 1, 16), 0, 3, Gauge.Model.of(0.5))).isFalse();
    }

    @Test
    void theBarAndTheTrackArePaintedInTheThemesStyles() {
        ElementScreen dark = ElementScreen.render(gauge.element(Gauge.Model.of(0.5)), 1, 16, Themes.DARK);
        ElementScreen contrast = ElementScreen.render(gauge.element(Gauge.Model.of(0.5)), 1, 16, Themes.HIGH_CONTRAST);

        assertThat(dark.styles(0).get(0))
                .as("a filled cell is the theme's accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(0).get(6))
                .as("a track cell is the theme's muted style")
                .isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(0).get(15))
                .as("the readout is the theme's text")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(contrast.styles(0).get(0))
                .as("a live theme switch repaints the bar")
                .isEqualTo(Themes.HIGH_CONTRAST.style(ThemeToken.ACCENT))
                .isNotEqualTo(dark.styles(0).get(0));
    }

    @Test
    void modelsAreNormalizedAndValidated() {
        assertThat(Gauge.Model.of(0.42).percent()).isEqualTo(42);
        assertThat(Gauge.Model.of(0.42).range()).isEqualTo(new ChartRange(0.0, 1.0));
        assertThat(Gauge.Model.of(5.0, 0.0, 10.0).fraction()).isEqualTo(0.5);
        assertThat(Gauge.Model.of(3.0, 10.0, 0.0).range())
                .as("reversed bounds are ordered, not rejected")
                .isEqualTo(new ChartRange(0.0, 10.0));
        assertThat(Gauge.Model.of(1.0, 2.0, 2.0).percent())
                .as("a flat range puts the reading in the middle of the bar")
                .isEqualTo(50);
        assertThat(Gauge.Model.of(Double.NaN).percent()).isZero();
        assertThat(new Gauge.Model(0.5, null).range()).isEqualTo(new ChartRange(0.0, 1.0));
        assertThatThrownBy(() -> new Gauge<String>(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gauge.element(null)).isInstanceOf(NullPointerException.class);
        assertThat(Gauge.FILLED).hasSize(1);
        assertThat(Gauge.EMPTY).hasSize(1);
        assertThat(Gauge.READOUT_COLUMNS).isEqualTo(4);
        // The entry point paints the same row the gauge's own element does, and refuses a missing gauge.
        assertThat(ElementScreen.render(Elements.gauge(gauge, Gauge.Model.of(0.5)), 1, 16)
                        .lines())
                .isEqualTo(ElementScreen.render(gauge.element(Gauge.Model.of(0.5)), 1, 16)
                        .lines());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Elements.gauge(null, Gauge.Model.of(0.5)))
                .isInstanceOf(NullPointerException.class);
    }
}
