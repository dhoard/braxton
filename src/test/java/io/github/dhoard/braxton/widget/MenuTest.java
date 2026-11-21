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
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The menu: what it paints, what it highlights, keys, presses and its window.
 */
class MenuTest {

    private static final List<Menu.Entry> ENTRIES = List.of(
            Menu.Item.of("open", "Open", "ctrl+o"),
            Menu.Item.of("save", "Save", "ctrl+s"),
            new Menu.Separator(),
            Menu.Item.of("quit", "Quit", "ctrl+q"));

    private final Menu menu = new Menu(ENTRIES);

    @Test
    void aMenuPaintsOneLabelPerRowWithItsHintsAtTheRightEdge() {
        Menu.Model model = menu.init(new InitContext(null, null)).model();

        assertThat(ElementScreen.render(menu.element(model), 4, 20).lines())
                .isEqualTo(List.of(
                        "Open          ctrl+o", "Save          ctrl+s", "\u2500".repeat(20), "Quit          ctrl+q"));
    }

    @Test
    void aNarrowMenuDropsTheHintBeforeTheLabel() {
        Menu.Model model = Menu.Model.of(ENTRIES);

        // Seventeen columns hold the label, one separating column and the whole hint.
        assertThat(ElementScreen.render(menu.element(model), 2, 17).line(0)).isEqualTo("Open       ctrl+o");
        // Eight do not, so the hint is dropped rather than crowding the label.
        assertThat(ElementScreen.render(menu.element(model), 2, 8).lines()).containsExactly("Open", "Save");
        assertThat(ElementScreen.render(menu.element(model), 0, 0).lines()).isEmpty();
        assertThat(ElementScreen.render(menu.element(model), 2, 3).line(0)).isEqualTo("Ope");
    }

    @Test
    void theHighlightSkipsSeparatorsAndItemsThatCannotBeChosen() {
        List<Menu.Entry> entries = List.of(
                Menu.Item.of("a", "Alpha"),
                Menu.Item.of("b", "Beta").withEnabled(false),
                new Menu.Separator(),
                Menu.Item.of("c", "Charlie"));
        Menu menu = new Menu(entries);
        Menu.Model model = menu.init(new InitContext(null, null)).model();

        assertThat(model.highlighted()).isEqualTo("a");
        assertThat(apply(menu, model, new Menu.Msg.Down()).highlighted()).isEqualTo("c");
        assertThat(apply(menu, model, new Menu.Msg.End()).highlighted()).isEqualTo("c");
        assertThat(apply(menu, model, new Menu.Msg.Home()).highlighted()).isEqualTo("a");

        // A highlight that names nothing choosable falls to the first item that is.
        assertThat(model.withHighlight("b").highlighted()).isEqualTo("a");
        assertThat(model.withHighlight("nowhere").highlighted()).isEqualTo("a");
        assertThat(model.withHighlight(null).highlighted()).isEqualTo("a");
        assertThat(new Menu(List.of(new Menu.Separator()))
                        .init(new InitContext(null, null))
                        .model()
                        .highlighted())
                .isEmpty();
    }

    @Test
    void enterChoosesTheHighlightedItemAndEscapeAsksToDismiss() {
        Menu.Model model = menu.init(new InitContext(null, null)).model();

        assertThat(menu.update(model, new Menu.Msg.Activate()).outputs())
                .containsExactly(new Menu.Output.Chosen("open", Menu.Item.of("open", "Open", "ctrl+o")));
        assertThat(menu.update(model, new Menu.Msg.Down()).outputs()).isEmpty();
        assertThat(menu.update(model, new Menu.Msg.Cancel()).outputs()).containsExactly(new Menu.Output.Canceled());

        // Nothing choosable produces no intent at all, so an application is never handed a phantom.
        Menu empty = new Menu(List.of());
        Menu.Model nothing = empty.init(new InitContext(null, null)).model();
        assertThat(empty.update(nothing, new Menu.Msg.Activate()).outputs()).isEmpty();
        assertThat(empty.update(nothing, new Menu.Msg.Choose("open")).outputs()).isEmpty();
        assertThat(nothing).isEqualTo(new Menu.Model(List.of(), "", true, 0, Menu.DEFAULT_ROWS));
    }

    @Test
    void aPressChoosesTheItemItLandedOnAndNothingElse() {
        Menu.Model model = menu.init(new InitContext(null, null)).model();

        assertThat(pointer(menu, model, press(1, 4))).containsExactly(new Menu.Msg.Choose("save"));
        assertThat(pointer(menu, model, press(2, 4)))
                .as("a separator chooses nothing")
                .isEmpty();
        assertThat(pointer(menu, model, press(9, 0)))
                .as("a row past the end chooses nothing")
                .isEmpty();
        assertThat(pointer(menu, model, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 1, 0)))
                .isEmpty();

        List<Menu.Entry> disabled = List.of(Menu.Item.of("x", "X").withEnabled(false));
        Menu menu = new Menu(disabled);
        assertThat(pointer(menu, menu.init(new InitContext(null, null)).model(), press(0, 0)))
                .isEmpty();
    }

    @Test
    void theWindowFollowsTheHighlightAndIsClampedIntoTheEntries() {
        List<Menu.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            entries.add(Menu.Item.of("i" + index, "item " + index));
        }
        Menu menu = new Menu(entries);
        Menu.Model model = menu.init(new InitContext(null, null)).model().withRows(3);

        Menu.Model last = apply(menu, model, new Menu.Msg.End());
        assertThat(last.highlighted()).isEqualTo("i7");
        assertThat(last.offset()).isEqualTo(5);
        assertThat(ElementScreen.render(menu.element(last), 3, 10).line(2)).isEqualTo("item 7");

        // A taller window admits the same highlight and clamps the window into the entries.
        assertThat(apply(menu, last, new Menu.Msg.RowsChanged(8)).offset()).isZero();
        assertThat(apply(menu, model, new Menu.Msg.RowsChanged(0)).offset()).isZero();
        assertThat(model.withRows(-1).rows()).isZero();
    }

    @Test
    void rowsArePaintedInTheThemesStyles() {
        Menu.Model model = Menu.Model.of(
                List.of(Menu.Item.of("a", "Alpha"), Menu.Item.of("b", "Beta").withEnabled(false)));

        ElementScreen dark = ElementScreen.render(menu.element(model), 2, 10, Themes.DARK);
        ElementScreen light = ElementScreen.render(menu.element(model), 2, 10, Themes.LIGHT);

        assertThat(dark.styles(0).get(0))
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(0).get(9))
                .as("the highlight is the whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(dark.styles(1).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.DISABLED));
        assertThat(light.styles(1).get(0)).isEqualTo(Themes.LIGHT.style(ThemeToken.DISABLED));

        // An unfocused menu shows its highlight without the focus style.
        ElementScreen unfocused = ElementScreen.render(menu.element(model.withFocus(false)), 2, 10, Themes.DARK);
        assertThat(unfocused.styles(0).get(9)).isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
    }

    @Test
    void theMenuIsAFocusStopOnlyWhileSomethingCanBeChosen() {
        Menu.Model model = menu.init(new InitContext(null, null)).model();
        Element<Menu.Msg> root = Elements.menu(menu, model, message -> message);

        assertThat(menu.focused(model)).isTrue();
        assertThat(menu.focused(model.withFocus(false))).isFalse();
        assertThat(menu.canFocus(model)).isTrue();
        assertThat(menu.canFocus(
                        new Menu(List.of()).init(new InitContext(null, null)).model()))
                .isFalse();
        assertThat(ElementScreen.render(root, 4, 10).focusables()).containsExactly(new Rect(0, 0, 4, 10));

        List<Menu.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new Menu.Msg.Down());

        produced.clear();
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.of("x")), produced::add))
                .isFalse();
        assertThat(menu.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("up", "down", "home", "end", "choose", "dismiss");
        assertThat(menu.keys().actionFor(Key.ESCAPE)).map(KeyAction::message).contains(new Menu.Msg.Cancel());
    }

    @Test
    void entriesAndConfigurationAreValidatedAndReplaced() {
        assertThatThrownBy(() -> Menu.Item.of(" ", "label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item id");
        assertThatThrownBy(() -> new Menu.Config(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> new Menu((Menu.Config) null)).isInstanceOf(NullPointerException.class);

        assertThat(new Menu.Separator(null).id()).isEqualTo("-");
        assertThat(Menu.Item.of("a", "A").withHint(StyledText.plain("h")).hint().plainText())
                .isEqualTo("h");
        assertThat(Menu.Item.of("a", "A").withLabel(null).label().isEmpty()).isTrue();
        assertThat(new Menu.Config(null, 2).entries()).isEmpty();
        assertThat(Menu.Config.of(ENTRIES).withEntries(List.of()).entries()).isEmpty();
        assertThat(Menu.Config.of(ENTRIES).withRows(2).rows()).isEqualTo(2);

        Menu menu = new Menu(ENTRIES);
        Menu.Model model = menu.init(new InitContext(null, null)).model();
        Menu.Model replaced = apply(menu, model, new Menu.Msg.Replace(List.of(Menu.Item.of("x", "X"))));
        assertThat(replaced.entries()).containsExactly(Menu.Item.of("x", "X"));
        assertThat(replaced.highlighted()).isEqualTo("x");
        assertThat(apply(menu, model, new Menu.Msg.Replace(null)).entries()).isEmpty();
        assertThat(model.highlightedItem()).isEqualTo(Menu.Item.of("open", "Open", "ctrl+o"));
        assertThat(new Menu.Model(List.of(), "", true, 0, 3).highlightedItem()).isNull();
    }

    @Test
    void aMenuRowOfABreakStaysOnOneLine() {
        Menu menu = new Menu(List.of(Menu.Item.of("a", "one\ntwo")));

        assertThat(ElementScreen.render(menu.element(Menu.Model.of(List.of(Menu.Item.of("a", "one\ntwo")))), 2, 12)
                        .line(0))
                .isEqualTo("one two");
    }

    private static Menu.Model apply(Menu menu, Menu.Model model, Menu.Msg message) {
        return menu.update(model, message).model();
    }

    private static List<Menu.Msg> pointer(Menu menu, Menu.Model model, PointerEvent report) {
        List<Menu.Msg> produced = new ArrayList<>(1);
        menu.onPointer(report, new Rect(0, 0, 4, 20), model, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
