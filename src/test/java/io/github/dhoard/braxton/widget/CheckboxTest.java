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
import io.github.dhoard.braxton.style.StyledText;
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
 * The checkbox: its box, its state, its one gesture, its presses and its themes.
 */
class CheckboxTest {

    private final Checkbox checkbox = new Checkbox(StyledText.plain("Remember"));

    @Test
    void aCheckboxPaintsItsBoxAndItsLabel() {
        Checkbox.Model model = checkbox.init(new InitContext(null, null)).model();

        assertThat(model.checked()).isFalse();
        assertThat(model.focused()).isTrue();
        assertThat(ElementScreen.render(checkbox.element(model), 1, 16).lines()).containsExactly("[ ] Remember");
        assertThat(ElementScreen.render(checkbox.element(model.withChecked(true)), 1, 16)
                        .lines())
                .containsExactly(Checkbox.CHECKED + " Remember");
        // A screen wider or taller than the row leaves the rest of the frame blank.
        assertThat(ElementScreen.render(checkbox.element(model), 2, 20).lines()).containsExactly("[ ] Remember", "");
    }

    @Test
    void aNarrowRowKeepsTheBoxAndClipsTheLabel() {
        Checkbox.Model model = Checkbox.Model.of(true);

        assertThat(ElementScreen.render(checkbox.element(model), 1, 5).line(0)).isEqualTo(Checkbox.CHECKED + " R");
        assertThat(ElementScreen.render(checkbox.element(model), 1, 3).line(0)).isEqualTo(Checkbox.CHECKED);
        assertThat(ElementScreen.render(checkbox.element(model), 1, 2).line(0))
                .isEqualTo(Checkbox.CHECKED.substring(0, 2));
        assertThat(ElementScreen.render(checkbox.element(model), 0, 0).lines()).isEmpty();
        assertThat(new Checkbox(StyledText.empty()).label().isEmpty()).isTrue();
        assertThat(ElementScreen.render(new Checkbox(null).element(model), 1, 4).line(0))
                .as("a checkbox without a label is its box")
                .isEqualTo(Checkbox.CHECKED);
    }

    @Test
    void spaceFlipsTheBoxAndReportsTheNewState() {
        Checkbox.Model unchecked = Checkbox.Model.of(false);

        var checked = checkbox.update(unchecked, new Checkbox.Msg.Toggle());
        assertThat(checked.model().checked()).isTrue();
        assertThat(checked.outputs()).containsExactly(new Checkbox.Output.Changed(true));

        var cleared = checkbox.update(checked.model(), new Checkbox.Msg.Toggle());
        assertThat(cleared.model().checked()).isFalse();
        assertThat(cleared.outputs()).containsExactly(new Checkbox.Output.Changed(false));

        List<Checkbox.Msg> produced = new ArrayList<>(1);
        checkbox.onKey(InputEvent.KeyInput.of(Key.SPACE), unchecked, produced::add);
        assertThat(produced).containsExactly(new Checkbox.Msg.Toggle());
        for (Key key : List.of(Key.ENTER, Key.UP, Key.DOWN, Key.of("x"))) {
            produced.clear();
            checkbox.onKey(InputEvent.KeyInput.of(key), unchecked, produced::add);
            assertThat(produced)
                    .as("a checkbox answers only space, leaving %s to the application", key)
                    .isEmpty();
        }
        produced.clear();
        checkbox.onKey(InputEvent.KeyInput.of(Key.SPACE, "", KeyEventType.RELEASE), unchecked, produced::add);
        assertThat(produced).as("a release never fires the press again").isEmpty();
    }

    @Test
    void aPressAnywhereOnTheRowFlipsTheBox() {
        Checkbox.Model model = Checkbox.Model.of(false);
        Element<Checkbox.Msg> root = checkbox.element(model);

        assertThat(pointer(root, 1, 16, press(0, 0))).containsExactly(new Checkbox.Msg.Toggle());
        assertThat(pointer(root, 1, 16, press(0, 15))).containsExactly(new Checkbox.Msg.Toggle());
        assertThat(pointer(root, 1, 16, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 0, 4)))
                .isEmpty();
        assertThat(pointer(root, 1, 16, PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, 0, 4)))
                .isEmpty();
    }

    @Test
    void theBoxAndTheLabelArePaintedInTheThemesStyles() {
        Checkbox.Model checked = Checkbox.Model.of(true);
        Checkbox.Model unchecked = checked.withChecked(false);

        ElementScreen dark = ElementScreen.render(checkbox.element(checked), 1, 16, Themes.DARK);
        ElementScreen light = ElementScreen.render(checkbox.element(checked), 1, 16, Themes.LIGHT);
        ElementScreen idle = ElementScreen.render(checkbox.element(unchecked), 1, 16, Themes.DARK);
        ElementScreen unfocused =
                ElementScreen.render(checkbox.element(unchecked.withFocus(false)), 1, 16, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("a focused checked box is the accent over the selection")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(0).get(4))
                .as("the label is the theme's text over the selection")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(0).get(15))
                .as("a focused box fills its whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(light.styles(0).get(0))
                .isEqualTo(Themes.LIGHT
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.LIGHT.style(ThemeToken.ACCENT).merge(Themes.LIGHT.style(ThemeToken.FOCUS))));
        assertThat(idle.styles(0).get(0))
                .as("an unchecked box is muted")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.MUTED).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(unfocused.styles(0).get(0))
                .as("an unfocused box carries no focus style and no fill")
                .isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(unfocused.styles(0).get(15).background())
                .as("an unfocused box paints no highlight")
                .isNull();
        assertThat(unfocused.styles(0).get(4)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
    }

    @Test
    void aCheckboxIsAlwaysAFocusStopAndAnswersItsOneKey() {
        Checkbox.Model model = Checkbox.Model.of(false);
        Element<Checkbox.Msg> root = Elements.checkbox(checkbox, model, message -> message);

        assertThat(checkbox.focused(model)).isTrue();
        assertThat(checkbox.focused(model.withFocus(false))).isFalse();
        assertThat(ElementScreen.render(root, 1, 16).focusables()).containsExactly(new Rect(0, 0, 1, 16));
        List<Checkbox.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.SPACE), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new Checkbox.Msg.Toggle());
        produced.clear();
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.ENTER), produced::add))
                .isFalse();
        assertThat(checkbox.keys().actions()).extracting(KeyAction::id).containsExactly("toggle");
        assertThat(checkbox.label().plainText()).isEqualTo("Remember");
    }

    @Test
    void modelsAreValuesAndArgumentsAreRequired() {
        assertThat(Checkbox.Model.of(true).focused()).isTrue();
        assertThat(Checkbox.Model.of(true).withChecked(false).checked()).isFalse();
        assertThat(Checkbox.Model.of(true).withFocus(false).focused()).isFalse();

        assertThatThrownBy(() -> checkbox.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checkbox.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checkbox.update(null, new Checkbox.Msg.Toggle()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checkbox.view(Checkbox.Model.of(true), null)).isInstanceOf(NullPointerException.class);
    }

    private static List<Checkbox.Msg> pointer(Element<Checkbox.Msg> root, int rows, int cols, PointerEvent report) {
        List<Checkbox.Msg> produced = new ArrayList<>(1);
        ElementTree.Placement<Checkbox.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
