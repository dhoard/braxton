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
 * The select: what it paints closed and open, keys, presses, its window and its themes.
 */
class SelectTest {

    private static final List<Option> OPTIONS =
            List.of(Option.of("name", "Name"), Option.of("size", "Size"), Option.of("date", "Date"));

    private final Select select = new Select(OPTIONS);

    @Test
    void aClosedSelectPaintsTheChosenValueAndOpensOntoItsOptions() {
        Select.Model model = select.init(new InitContext(null, null)).model();

        assertThat(model.selected()).isEqualTo("name");
        assertThat(model.open()).isFalse();
        assertThat(ElementScreen.render(select.element(model), 1, 12).lines())
                .containsExactly("Name       " + Select.CLOSED_GLYPH);

        Select.Model open = apply(select, model, new Select.Msg.ToggleOpen());
        assertThat(open.open()).isTrue();
        assertThat(ElementScreen.render(select.element(open), 4, 12).lines())
                .containsExactly("Name       " + Select.OPEN_GLYPH, Select.CHOSEN + " Name", "  Size", "  Date");
        // A screen taller than the popup leaves the rows below it blank.
        assertThat(ElementScreen.render(select.element(open), 5, 12).lines().get(4))
                .isEmpty();
        assertThat(ElementScreen.render(select.element(open.withOpen(false)), 1, 12)
                        .lines())
                .containsExactly("Name       " + Select.CLOSED_GLYPH);
    }

    @Test
    void aNarrowFieldKeepsTheValueAndDropsTheGlyphBeforeItClipsIt() {
        Select.Model model = select.init(new InitContext(null, null)).model();

        assertThat(ElementScreen.render(select.element(model), 1, 4).line(0))
                .as("four columns hold the value and the glyph")
                .isEqualTo("Na " + Select.CLOSED_GLYPH);
        assertThat(ElementScreen.render(select.element(model), 1, 3).line(0))
                .as("three columns hold the value instead of half a glyph")
                .isEqualTo("Nam");
        assertThat(ElementScreen.render(select.element(model), 1, 1).line(0)).isEqualTo("N");
        assertThat(ElementScreen.render(select.element(model), 0, 0).lines()).isEmpty();

        // The popup is clipped to the rows the widget is given, so a one-row field shows no options.
        Select.Model open = model.withOpen(true);
        assertThat(ElementScreen.render(select.element(open), 1, 12).lines())
                .containsExactly("Name       " + Select.OPEN_GLYPH);
        assertThat(open.withRows(0).visibleRows()).hasSize(3);
        assertThat(ElementScreen.render(select.element(open.withRows(0)), 4, 12).lines())
                .containsExactly("Name       " + Select.OPEN_GLYPH, "", "", "");
    }

    @Test
    void arrowsChooseWhileClosedAndOnlyHighlightWhileOpen() {
        Select.Model closed = select.init(new InitContext(null, null)).model();

        assertThat(select.update(closed, new Select.Msg.Down()).model().selected())
                .isEqualTo("size");
        assertThat(select.update(closed, new Select.Msg.Down()).outputs())
                .containsExactly(new Select.Output.Chosen("size", Option.of("size", "Size")));
        assertThat(apply(select, closed, new Select.Msg.End()).selected()).isEqualTo("date");
        assertThat(apply(select, closed, new Select.Msg.Home()).selected()).isEqualTo("name");

        // An arrow that cannot move reports nothing and keeps the instance the runtime compares.
        assertThat(select.update(closed, new Select.Msg.Up()).outputs()).isEmpty();
        assertThat(select.update(closed, new Select.Msg.Up()).model()).isSameAs(closed);
        assertThat(apply(select, apply(select, closed, new Select.Msg.End()), new Select.Msg.End())
                        .selected())
                .isEqualTo("date");

        Select.Model open = closed.withOpen(true);
        assertThat(select.update(open, new Select.Msg.Down()).outputs()).isEmpty();
        assertThat(select.update(open, new Select.Msg.Down()).model().highlighted())
                .isEqualTo("size");
        assertThat(select.update(open, new Select.Msg.Down()).model().selected())
                .isEqualTo("name");
        assertThat(apply(select, open, new Select.Msg.End()).highlighted()).isEqualTo("date");
        assertThat(apply(select, open, new Select.Msg.Home()).highlighted()).isEqualTo("name");
    }

    @Test
    void enterOpensThenChoosesAndEscapeClosesOnlyWhileOpen() {
        Select.Model closed = select.init(new InitContext(null, null)).model();

        Select.Model open = apply(select, closed, new Select.Msg.Activate());
        assertThat(open.open()).isTrue();
        assertThat(open.highlighted()).isEqualTo("name");

        Select.Model highlighted = apply(select, open, new Select.Msg.Down());
        var chosen = select.update(highlighted, new Select.Msg.Activate());
        assertThat(chosen.model().selected()).isEqualTo("size");
        assertThat(chosen.model().open()).isFalse();
        assertThat(chosen.outputs()).containsExactly(new Select.Output.Chosen("size", Option.of("size", "Size")));

        var dismissed = select.update(open, new Select.Msg.Cancel());
        assertThat(dismissed.model().open()).isFalse();
        assertThat(dismissed.model().selected()).isEqualTo("name");
        assertThat(dismissed.outputs()).containsExactly(new Select.Output.Canceled());

        // A closed select has nothing to dismiss: the gesture is the application's, and a message the
        // application sends anyway changes nothing and reports nothing.
        var idle = select.update(closed, new Select.Msg.Cancel());
        assertThat(idle.model()).isSameAs(closed);
        assertThat(idle.outputs()).isEmpty();
        List<Select.Msg> produced = new ArrayList<>(1);
        select.onKey(InputEvent.KeyInput.of(Key.ESCAPE), closed, produced::add);
        assertThat(produced).isEmpty();
        select.onKey(InputEvent.KeyInput.of(Key.ESCAPE), open, produced::add);
        assertThat(produced).containsExactly(new Select.Msg.Cancel());
    }

    @Test
    void aPressOnTheFieldTogglesTheListAndAPressOnAnOptionChoosesIt() {
        Select.Model closed = select.init(new InitContext(null, null)).model();
        Element<Select.Msg> element = select.element(closed.withOpen(true));

        assertThat(pointer(element, 6, 12, press(0, 2))).containsExactly(new Select.Msg.ToggleOpen());
        assertThat(pointer(select.element(closed), 1, 12, press(0, 0))).containsExactly(new Select.Msg.ToggleOpen());
        assertThat(pointer(element, 6, 12, press(2, 0))).containsExactly(new Select.Msg.Choose("size"));
        assertThat(pointer(element, 6, 12, press(1, 0))).containsExactly(new Select.Msg.Choose("name"));
        assertThat(pointer(element, 6, 12, press(5, 0)))
                .as("a row past the last option chooses nothing")
                .isEmpty();
        assertThat(pointer(element, 6, 12, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 2, 0)))
                .isEmpty();
        assertThat(pointer(element, 6, 12, PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, 2, 0)))
                .isEmpty();
    }

    @Test
    void thePopupWindowFollowsTheHighlightAndIsClampedIntoTheOptions() {
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            options.add(Option.of("i" + index, "item " + index));
        }
        Select eight = new Select(options);
        Select.Model model =
                eight.init(new InitContext(null, null)).model().withRows(3).withOpen(true);

        Select.Model last = apply(eight, model, new Select.Msg.End());
        assertThat(last.highlighted()).isEqualTo("i7");
        assertThat(last.offset()).isEqualTo(5);
        // The field keeps showing the chosen option while the popup scrolls with the highlight.
        assertThat(ElementScreen.render(eight.element(last), 4, 10).lines())
                .containsExactly("item 0   " + Select.OPEN_GLYPH, "  item 5", "  item 6", "  item 7");
        Select.Model chosen = apply(eight, last, new Select.Msg.Activate());
        assertThat(chosen.selected()).isEqualTo("i7");
        assertThat(chosen.open()).isFalse();
        assertThat(ElementScreen.render(eight.element(chosen), 1, 10).lines())
                .containsExactly("item 7   " + Select.CLOSED_GLYPH);

        // A taller window admits the same highlight and clamps the window into the options.
        assertThat(apply(eight, last, new Select.Msg.Home()).offset()).isZero();
        assertThat(model.withRows(8).offset()).isZero();
        assertThat(model.withRows(-1).rows()).isZero();
    }

    @Test
    void theFieldAndTheOptionsArePaintedInTheThemesStyles() {
        Select.Model chosen = new Select.Model(OPTIONS, "name", true, "name", true, 0, 8);
        Select.Model elsewhere = new Select.Model(OPTIONS, "name", true, "size", true, 0, 8);

        ElementScreen dark = ElementScreen.render(select.element(chosen), 4, 12, Themes.DARK);
        ElementScreen light = ElementScreen.render(select.element(chosen), 4, 12, Themes.LIGHT);
        ElementScreen unfocused = ElementScreen.render(select.element(chosen.withFocus(false)), 4, 12, Themes.DARK);
        ElementScreen highlighted = ElementScreen.render(select.element(elsewhere), 4, 12, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("the focused value")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(0).get(11))
                .as("the focused glyph is the accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(1).get(0))
                .as("a chosen row's marker is the accent, over the fill")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(1).get(2))
                .as("a chosen row's label is text, over the fill")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(1).get(11))
                .as("the highlight fills the whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(dark.styles(2).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(2).get(2)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(highlighted.styles(2).get(0))
                .as("an unchosen highlighted row's marker keeps the muted style")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.MUTED).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(highlighted.styles(2).get(11)).isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(highlighted.styles(1).get(0))
                .as("a chosen row that is not highlighted has no focus style")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(unfocused.styles(0).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(unfocused.styles(1).get(0))
                .as("an unfocused highlight keeps the fill and loses the focus style")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED).merge(Themes.DARK.style(ThemeToken.ACCENT)));
        assertThat(unfocused.styles(1).get(11)).isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(light.styles(1).get(0))
                .isEqualTo(Themes.LIGHT
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.LIGHT.style(ThemeToken.ACCENT).merge(Themes.LIGHT.style(ThemeToken.FOCUS))));
    }

    @Test
    void theSelectIsAFocusStopOnlyWhileItHasOptions() {
        Select.Model model = select.init(new InitContext(null, null)).model();
        Element<Select.Msg> root = Elements.select(select, model, message -> message);

        assertThat(select.focused(model)).isTrue();
        assertThat(select.focused(model.withFocus(false))).isFalse();
        assertThat(select.canFocus(model)).isTrue();
        assertThat(new Select(List.of())
                        .init(new InitContext(null, null))
                        .model()
                        .options())
                .isEmpty();
        assertThat(select.canFocus(
                        new Select(List.of()).init(new InitContext(null, null)).model()))
                .isFalse();
        assertThat(ElementScreen.render(root, 1, 12).focusables()).containsExactly(new Rect(0, 0, 1, 12));

        List<Select.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new Select.Msg.Down());
        produced.clear();
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.of("x")), produced::add))
                .isFalse();
        assertThat(select.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("up", "down", "home", "end", "choose", "dismiss");
        assertThat(select.keys().actionFor(Key.ESCAPE)).map(KeyAction::message).contains(new Select.Msg.Cancel());
        assertThat(select.keys().actionFor(Key.SPACE)).map(KeyAction::message).contains(new Select.Msg.Activate());
        assertThat(select.config().options()).isEqualTo(OPTIONS);
    }

    @Test
    void optionsAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> Option.of(" ", "label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("option id");
        assertThatThrownBy(() -> new Option(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Select.Config(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> new Select((Select.Config) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> select.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> select.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> select.update(null, new Select.Msg.Up())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> select.update(Select.Model.of(OPTIONS), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> select.view(null, null)).isInstanceOf(NullPointerException.class);

        assertThat(Option.copyAll(null)).isEmpty();
        assertThatThrownBy(() -> Option.copyAll(java.util.Arrays.asList(Option.of("a", "A"), null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(Option.of("a", null).label().isEmpty()).isTrue();
        assertThat(Option.of("a", "A").withLabel(null).label().isEmpty()).isTrue();

        // The ids a model is built with are resolved, so a stale one cannot paint nothing.
        Select.Model model = new Select.Model(OPTIONS, "gone", false, "", true, 0, 3);
        assertThat(model.selected()).isEqualTo("name");
        assertThat(model.highlighted()).isEqualTo("name");
        assertThat(new Select.Model(OPTIONS, "size", true, "gone", true, 0, 3).highlighted())
                .isEqualTo("size");
        assertThat(new Select.Model(List.of(), "", true, "", true, 0, 3).selected())
                .isEmpty();
        assertThat(new Select.Model(List.of(), "", true, "", true, 0, 3).highlighted())
                .isEmpty();
        assertThat(new Select.Model(List.of(), "", true, "", true, 0, 3).selectedOption())
                .isNull();
        assertThat(Select.Model.of(null).options()).isEmpty();
        assertThat(model.withOptions(List.of(Option.of("x", "X"))).selected()).isEqualTo("x");
        assertThat(model.withOptions(null).options()).isEmpty();
        assertThat(model.withSelected("date").selected()).isEqualTo("date");
        assertThat(model.withHighlighted("date").highlighted()).isEqualTo("name");
        assertThat(Select.Config.of(null).options()).isEmpty();
        assertThat(Select.Config.of(OPTIONS).withOptions(List.of()).options()).isEmpty();
        assertThat(Select.Config.of(OPTIONS).withRows(2).rows()).isEqualTo(2);
        assertThat(model.selectedOption()).isEqualTo(Option.of("name", "Name"));
        assertThat(model.visibleRows()).extracting(Select.Row::id).containsExactly("name", "size", "date");
        assertThat(model.visibleRows().get(0).chosen()).isTrue();
        assertThat(model.visibleRows().get(1).chosen()).isFalse();
        assertThat(StyledText.plain(Select.CHOSEN).columns())
                .isEqualTo(StyledText.plain(Select.UNCHOSEN).columns());
    }

    private static Select.Model apply(Select select, Select.Model model, Select.Msg message) {
        return select.update(model, message).model();
    }

    private static List<Select.Msg> pointer(Element<Select.Msg> root, int rows, int cols, PointerEvent report) {
        List<Select.Msg> produced = new ArrayList<>(1);
        ElementTree.Placement<Select.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
