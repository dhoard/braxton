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
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.InitContext;
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
 * The radio group: its dots, its one choice, keys, presses and themes.
 */
class RadioGroupTest {

    private static final List<Option> OPTIONS = List.of(Option.of("small", "Small"), Option.of("large", "Large"));

    private final RadioGroup group = new RadioGroup(OPTIONS);

    @Test
    void aGroupPaintsOneDotPerOptionAndFillsTheChosenRow() {
        RadioGroup.Model model = group.init(new InitContext(null, null)).model();

        assertThat(model.selected()).isEqualTo("small");
        assertThat(ElementScreen.render(group.element(model), 2, 12).lines())
                .containsExactly(RadioGroup.CHOSEN + " Small", RadioGroup.UNCHOSEN + " Large");
        assertThat(ElementScreen.render(group.element(model.withSelected("large")), 2, 12)
                        .lines())
                .containsExactly(RadioGroup.UNCHOSEN + " Small", RadioGroup.CHOSEN + " Large");
        assertThat(ElementScreen.render(group.element(model), 4, 12).lines())
                .containsExactly(RadioGroup.CHOSEN + " Small", RadioGroup.UNCHOSEN + " Large", "", "");
    }

    @Test
    void aNarrowGroupClipsTheLabelAndKeepsTheDot() {
        RadioGroup.Model model = RadioGroup.Model.of(OPTIONS);

        assertThat(ElementScreen.render(group.element(model), 1, 4).line(0)).isEqualTo(RadioGroup.CHOSEN + " Sm");
        assertThat(ElementScreen.render(group.element(model), 1, 1).line(0)).isEqualTo(RadioGroup.CHOSEN);
        assertThat(ElementScreen.render(
                                new RadioGroup(List.of()).element(new RadioGroup.Model(List.of(), "", true)), 0, 0)
                        .lines())
                .isEmpty();
    }

    @Test
    void arrowsChooseAndReportWhileAnArrowThatCannotMoveReportsNothing() {
        RadioGroup.Model model = RadioGroup.Model.of(OPTIONS);

        var chosen = group.update(model, new RadioGroup.Msg.Down());
        assertThat(chosen.model().selected()).isEqualTo("large");
        assertThat(chosen.outputs())
                .containsExactly(new RadioGroup.Output.Chosen("large", Option.of("large", "Large")));
        assertThat(group.update(chosen.model(), new RadioGroup.Msg.Down()).outputs())
                .as("the last option's down reports nothing")
                .isEmpty();
        assertThat(group.update(chosen.model(), new RadioGroup.Msg.Down()).model())
                .isSameAs(chosen.model());
        assertThat(group.update(model, new RadioGroup.Msg.Up()).outputs())
                .as("the first option's up reports nothing")
                .isEmpty();
        assertThat(apply(group, chosen.model(), new RadioGroup.Msg.Home()).selected())
                .isEqualTo("small");
        assertThat(apply(group, model, new RadioGroup.Msg.End()).selected()).isEqualTo("large");
        assertThat(group.update(model, new RadioGroup.Msg.End()).outputs())
                .containsExactly(new RadioGroup.Output.Chosen("large", Option.of("large", "Large")));

        // Space or enter reports the option that is already chosen: the default was accepted.
        var accepted = group.update(model, new RadioGroup.Msg.Activate());
        assertThat(accepted.model()).isSameAs(model);
        assertThat(accepted.outputs())
                .containsExactly(new RadioGroup.Output.Chosen("small", Option.of("small", "Small")));
        assertThat(group.update(model, new RadioGroup.Msg.Choose("gone")).outputs())
                .isEmpty();
    }

    @Test
    void aPressChoosesTheRowItLandedOn() {
        RadioGroup.Model model = RadioGroup.Model.of(OPTIONS);
        Element<RadioGroup.Msg> element = group.element(model);

        assertThat(pointer(element, 2, 12, press(0, 3))).containsExactly(new RadioGroup.Msg.Choose("small"));
        assertThat(pointer(element, 2, 12, press(1, 0))).containsExactly(new RadioGroup.Msg.Choose("large"));
        assertThat(pointer(element, 4, 12, press(3, 0)))
                .as("a row past the last option chooses nothing")
                .isEmpty();
        assertThat(pointer(element, 2, 12, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 1, 0)))
                .isEmpty();

        RadioGroup.Model chosen = apply(group, model, new RadioGroup.Msg.Choose("large"));
        assertThat(chosen.selected()).isEqualTo("large");
        assertThat(chosen.focused()).isTrue();
    }

    @Test
    void theDotsAndTheLabelsArePaintedInTheThemesStyles() {
        RadioGroup.Model model = RadioGroup.Model.of(OPTIONS);

        ElementScreen dark = ElementScreen.render(group.element(model), 2, 12, Themes.DARK);
        ElementScreen light = ElementScreen.render(group.element(model), 2, 12, Themes.LIGHT);
        ElementScreen unfocused = ElementScreen.render(group.element(model.withFocus(false)), 2, 12, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("the chosen dot is the accent over the selection")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(0).get(2))
                .as("the chosen label is the theme's text over the selection")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(0).get(11))
                .as("the chosen row is filled across its width")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(dark.styles(1).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(1).get(2)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(dark.styles(1).get(11).background())
                .as("a row that is not chosen is not filled")
                .isNull();
        assertThat(unfocused.styles(0).get(0))
                .as("an unfocused group still shows its choice, without the focus style")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED).merge(Themes.DARK.style(ThemeToken.ACCENT)));
        assertThat(light.styles(0).get(0))
                .isEqualTo(Themes.LIGHT
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.LIGHT.style(ThemeToken.ACCENT).merge(Themes.LIGHT.style(ThemeToken.FOCUS))));
    }

    @Test
    void theGroupIsAFocusStopOnlyWhileItHasOptionsAndAnswersItsKeys() {
        RadioGroup.Model model = RadioGroup.Model.of(OPTIONS);
        Element<RadioGroup.Msg> root = Elements.radioGroup(group, model, message -> message);

        assertThat(group.focused(model)).isTrue();
        assertThat(group.focused(model.withFocus(false))).isFalse();
        assertThat(group.canFocus(model)).isTrue();
        assertThat(group.canFocus(new RadioGroup(List.of())
                        .init(new InitContext(null, null))
                        .model()))
                .isFalse();
        assertThat(ElementScreen.render(root, 2, 12).focusables()).containsExactly(new Rect(0, 0, 2, 12));

        List<RadioGroup.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new RadioGroup.Msg.Down());
        produced.clear();
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.SPACE), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new RadioGroup.Msg.Activate());
        produced.clear();
        group.onKey(InputEvent.KeyInput.of(Key.SPACE, "", KeyEventType.RELEASE), model, produced::add);
        assertThat(produced).as("a release never fires the press again").isEmpty();
        produced.clear();
        group.onKey(InputEvent.KeyInput.of(Key.of("x")), model, produced::add);
        assertThat(produced).isEmpty();

        assertThat(group.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("up", "down", "home", "end", "choose");
        assertThat(group.keys().actionFor(Key.ENTER)).map(KeyAction::message).contains(new RadioGroup.Msg.Activate());
        assertThat(group.config().options()).isEqualTo(OPTIONS);
    }

    @Test
    void optionsAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> new RadioGroup((RadioGroup.Config) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> group.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> group.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> group.update(null, new RadioGroup.Msg.Up())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> group.view(null, null)).isInstanceOf(NullPointerException.class);

        assertThat(new RadioGroup.Model(OPTIONS, "gone", true).selected()).isEqualTo("small");
        assertThat(new RadioGroup.Model(List.of(), "small", true).selected()).isEmpty();
        assertThat(new RadioGroup.Model(List.of(), "small", true).selectedOption())
                .isNull();
        assertThat(new RadioGroup.Model(List.of(), "small", true).visibleRows()).isEmpty();
        assertThat(RadioGroup.Model.of(null).options()).isEmpty();
        assertThat(group.update(new RadioGroup.Model(List.of(), "", true), new RadioGroup.Msg.Up())
                        .outputs())
                .as("an empty group has nothing to choose")
                .isEmpty();
        assertThat(RadioGroup.Model.of(OPTIONS)
                        .withOptions(List.of(Option.of("x", "X")))
                        .selected())
                .isEqualTo("x");
        assertThat(RadioGroup.Model.of(OPTIONS).withOptions(null).options()).isEmpty();
        assertThat(RadioGroup.Config.of(null).options()).isEmpty();
        assertThat(RadioGroup.Config.of(OPTIONS).withOptions(List.of()).options())
                .isEmpty();
        assertThat(RadioGroup.Model.of(OPTIONS).selectedOption()).isEqualTo(Option.of("small", "Small"));
        assertThat(RadioGroup.Model.of(OPTIONS).visibleRows())
                .extracting(RadioGroup.Row::id)
                .containsExactly("small", "large");
        assertThat(RadioGroup.Model.of(OPTIONS).visibleRows().get(0).selected()).isTrue();
        assertThat(RadioGroup.Model.of(OPTIONS).visibleRows().get(1).selected()).isFalse();
    }

    private static RadioGroup.Model apply(RadioGroup group, RadioGroup.Model model, RadioGroup.Msg message) {
        return group.update(model, message).model();
    }

    private static List<RadioGroup.Msg> pointer(Element<RadioGroup.Msg> root, int rows, int cols, PointerEvent report) {
        List<RadioGroup.Msg> produced = new ArrayList<>(1);
        ElementTree.Placement<RadioGroup.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
