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
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dialog: its box, its mode, its buttons, keys, presses, themes and narrow sizes.
 */
class DialogTest {

    private static final List<Option> ACTIONS = List.of(Option.of("ok", "OK"), Option.of("cancel", "Cancel"));

    private final Dialog dialog =
            new Dialog(Dialog.Config.of("Delete file?", ACTIONS).withWidth(30));

    @Test
    void aDialogPaintsATitledBoxInTheMiddleWithItsContentAndItsButtons() {
        Dialog.Model model = dialog.init(new InitContext(null, null)).model();

        assertThat(model.focusedAction()).isEqualTo("ok");
        assertThat(model.focused()).isTrue();
        Element<Dialog.Msg> root = dialog.element(model, Elements.text("Remove report.txt?"), message -> message);
        assertThat(ElementScreen.render(root, 10, 40).lines())
                .containsExactly(
                        "",
                        "",
                        "     \u256D\u2500Delete file?\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E",
                        "     \u2502Remove report.txt?          \u2502",
                        "     \u2502                            \u2502",
                        "     \u2502[OK]  [Cancel]              \u2502",
                        "     \u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256F",
                        "",
                        "",
                        "");
        assertThat(ElementScreen.render(root, 10, 40).focusables())
                .as("the dialog is one focus stop over the whole box")
                .hasSize(1);
        // A dialog with no content is a question with an answer row, and the view paints the same box.
        assertThat(ElementScreen.render(dialog.element(model), 7, 40).lines())
                .containsExactly(
                        "",
                        "",
                        "     \u256D\u2500Delete file?\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E",
                        "     \u2502[OK]  [Cancel]              \u2502",
                        "     \u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256F",
                        "",
                        "");
        assertThat(ElementScreen.render(dialog.view(model, new ViewContext(new Viewport(7, 40))), 7, 40)
                        .lines())
                .isEqualTo(ElementScreen.render(dialog.element(model), 7, 40).lines());
        // The configured width is a maximum: a narrower screen clips the box, and its content is
        // painted at the width that is really there instead of being cut off.
        assertThat(ElementScreen.render(root, 8, 20).lines())
                .containsExactly(
                        "",
                        "\u256D\u2500Delete file?\u2500\u2500\u2500\u2500\u2500\u256E",
                        "\u2502Remove report.txt?\u2502",
                        "\u2502                  \u2502",
                        "\u2502[OK]  [Cancel]    \u2502",
                        "\u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256F",
                        "",
                        "");
        assertThat(ElementScreen.render(root, 0, 0).lines()).isEmpty();
        assertThat(ElementScreen.render(root, 0, 40).lines()).isEmpty();
    }

    @Test
    void theKeysWrapBetweenButtonsActivateOneAndAskToDismiss() {
        Dialog.Model model = dialog.init(new InitContext(null, null)).model();
        Element<Dialog.Msg> root = dialog.element(model, null, message -> message);

        assertThat(apply(model, new Dialog.Msg.Next()).focusedAction()).isEqualTo("cancel");
        assertThat(apply(apply(model, new Dialog.Msg.Next()), new Dialog.Msg.Next())
                        .focusedAction())
                .as("the next button after the last is the first")
                .isEqualTo("ok");
        assertThat(apply(model, new Dialog.Msg.Previous()).focusedAction())
                .as("the previous button before the first is the last")
                .isEqualTo("cancel");
        assertThat(dialog.update(model, new Dialog.Msg.Activate()).outputs())
                .containsExactly(new Dialog.Output.Activated("ok", Option.of("ok", "OK")));
        assertThat(apply(model, new Dialog.Msg.Choose("cancel")).focusedAction())
                .isEqualTo("cancel");
        assertThat(dialog.update(model, new Dialog.Msg.Choose("cancel")).outputs())
                .containsExactly(new Dialog.Output.Activated("cancel", Option.of("cancel", "Cancel")));
        assertThat(dialog.update(model, new Dialog.Msg.Cancel()).outputs())
                .containsExactly(new Dialog.Output.Canceled());

        // Every key the map names is answered by the dialog, which is what traps it.
        assertThat(route(root, "right")).containsExactly(new Dialog.Msg.Next());
        assertThat(route(root, "tab")).containsExactly(new Dialog.Msg.Next());
        assertThat(route(root, "left")).containsExactly(new Dialog.Msg.Previous());
        assertThat(route(root, "shift+tab")).containsExactly(new Dialog.Msg.Previous());
        assertThat(route(root, "enter")).containsExactly(new Dialog.Msg.Activate());
        assertThat(route(root, "space")).containsExactly(new Dialog.Msg.Activate());
        assertThat(route(root, "esc")).containsExactly(new Dialog.Msg.Cancel());
        assertThat(route(root, "q"))
                .as("a key the dialog does not own falls through to the application")
                .isEmpty();
        assertThat(dialog.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("next-button", "previous-button", "activate", "dismiss");
        // A release carries the press's key and never fires the gesture a second time.
        assertThat(InteractionRouter.route(
                        root,
                        InputEvent.KeyInput.of(Key.parse("right"), "", KeyEventType.RELEASE),
                        List.of(),
                        dialog.keys().bindings()))
                .as("a released key is not a press")
                .isEmpty();
    }

    @Test
    void aPressOnAButtonActivatesItAndAPressElsewhereAnswersNothing() {
        Dialog.Model model = dialog.init(new InitContext(null, null)).model();
        Element<Dialog.Msg> root = dialog.element(model, Elements.text("Remove report.txt?"), message -> message);

        assertThat(press(root, 7, 40, 4, 6)).containsExactly(new Dialog.Msg.Choose("ok"));
        assertThat(press(root, 7, 40, 4, 13)).containsExactly(new Dialog.Msg.Choose("cancel"));
        assertThat(press(root, 7, 40, 4, 10))
                .as("the gap between two buttons belongs to nobody")
                .isEmpty();
        assertThat(press(root, 7, 40, 2, 8))
                .as("a press inside the content belongs to the content")
                .isEmpty();
        assertThat(press(root, 7, 40, 1, 8))
                .as("a press on the border is the dialog's and answers nothing")
                .isEmpty();
        assertThat(press(root, 7, 40, 0, 0))
                .as("a click beside the dialog belongs to nobody, not to the layer behind it")
                .isEmpty();
        assertThat(press(root, 7, 40, 4, 13))
                .describedAs("the same press twice")
                .isEqualTo(press(root, 7, 40, 4, 13))
                .isNotEmpty();
    }

    @Test
    void theDialogOwnsTheScreenWhileItIsOpen() {
        Menu menu = new Menu(List.of(Menu.Item.of("a", "Alpha"), Menu.Item.of("b", "Beta")));
        Menu.Model entries = menu.init(new InitContext(null, null)).model();
        Element<String> under = Elements.menu(menu, entries, message -> "menu:" + message);
        Element<String> layer = Elements.dialog(
                dialog,
                dialog.init(new InitContext(null, null)).model(),
                Elements.text("Sure?"),
                message -> "dialog:" + message);
        Element<String> scene = Elements.stack(under, layer);

        // The dialog is the only focus stop, because a modal layer hides the walk behind it.
        assertThat(ElementScreen.render(scene, 7, 40).focusables()).hasSize(1);
        List<String> messages = new ArrayList<>();
        ElementTree.Placement<String> placement = ElementTree.layout(scene, new Rect(0, 0, 7, 40));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, press(0, 0), messages::add);
        assertThat(messages)
                .as("the menu under the dialog cannot be clicked through it")
                .isEmpty();
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, press(4, 6), messages::add);
        assertThat(messages)
                .as("the dialog's own messages are mapped into the scene's type")
                .containsExactly("dialog:" + new Dialog.Msg.Choose("ok"));

        // Without the layer the same cell reaches the menu, which is what the layer is covering.
        messages.clear();
        InteractionRouter.pointer(
                ElementTree.layout(under, new Rect(0, 0, 7, 40)),
                InteractionRouter.Interaction.NONE,
                press(0, 0),
                messages::add);
        assertThat(messages).containsExactly("menu:" + new Menu.Msg.Choose("a"));
        // A dialog can always take the keyboard, so it is a focus stop even while it does not hold
        // it; a dialog that does not hold it leaves nothing focused, because the layer it hides is
        // not a fallback.
        Element<String> idle = Elements.dialog(
                dialog,
                dialog.init(new InitContext(null, null)).model().withFocus(false),
                Elements.text("Sure?"),
                message -> "dialog:" + message);
        Element<String> idleScene = Elements.stack(under, idle);
        assertThat(ElementScreen.render(idleScene, 7, 40).focusables()).hasSize(1);
        assertThat(InteractionRouter.focused(idleScene)).isNull();
        assertThat(InteractionRouter.focused(scene)).isNotNull();
    }

    @Test
    void theBoxAndItsButtonsArePaintedInTheThemesStyles() {
        Dialog.Model model = dialog.init(new InitContext(null, null)).model();
        Element<Dialog.Msg> root = dialog.element(model, Elements.text("Remove?"), message -> message);

        ElementScreen dark = ElementScreen.render(root, 7, 40, Themes.DARK);
        ElementScreen light = ElementScreen.render(root, 7, 40, Themes.LIGHT);

        assertThat(dark.styles(1).get(5))
                .as("the border is the theme's border style")
                .isEqualTo(Themes.DARK.style(ThemeToken.BORDER));
        assertThat(light.styles(1).get(5)).isEqualTo(Themes.LIGHT.style(ThemeToken.BORDER));
        assertThat(dark.styles(4).get(6))
                .as("the button the keys are on is filled with the selection and carries focus")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(4).get(12))
                .as("a button the keys are not on keeps the border style on its bracket")
                .isEqualTo(Themes.DARK.style(ThemeToken.BORDER));
        assertThat(dark.styles(4).get(13))
                .as("and paints its label in the text style, unfilled")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(light.styles(4).get(6))
                .as("a live theme switch repaints the box and its buttons")
                .isNotEqualTo(dark.styles(4).get(6));
        assertThat(Themes.LIGHT.style(ThemeToken.BORDER))
                .as("the two themes really differ, so the assertions above mean something")
                .isNotEqualTo(Themes.DARK.style(ThemeToken.BORDER));
    }

    @Test
    void buttonsAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> Dialog.Config.of("T", ACTIONS).withWidth(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> new Dialog.Config(null, ACTIONS, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Dialog(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new Dialog.Config(StyledText.plain("T"), Arrays.asList(Option.of("a", "A"), null), 20, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(Dialog.Config.of("T", null).actions()).isEmpty();
        assertThat(Dialog.Config.of((String) null, ACTIONS).title().isEmpty()).isTrue();
        assertThat(Dialog.Config.of("T", ACTIONS).withActions(null).actions()).isEmpty();
        assertThat(Dialog.Config.of("T", ACTIONS).withTitle(null).title().isEmpty())
                .isTrue();
        assertThat(Dialog.Config.of("T", ACTIONS).withBorder(null).border()).isEqualTo(Border.ROUNDED);
        assertThat(Dialog.Config.of("T", ACTIONS).withBorder(Border.DOUBLE).border())
                .isEqualTo(Border.DOUBLE);
        assertThat(Dialog.Config.of("T", ACTIONS).withWidth(10).width()).isEqualTo(10);
        assertThat(new Dialog.Config(null, null, 20, Border.NONE).title().isEmpty())
                .isTrue();
        assertThat(new Dialog.Config(StyledText.plain("T"), null, 20, Border.NONE).actions())
                .isEmpty();

        // A model resolves its focused button, and a dialog without buttons focuses nothing.
        assertThat(new Dialog.Model(ACTIONS, "gone", true).focusedAction()).isEqualTo("ok");
        assertThat(new Dialog.Model(ACTIONS, null, true).focusedAction()).isEqualTo("ok");
        assertThat(Dialog.Model.of(null).focusedAction()).isEmpty();
        assertThat(Dialog.Model.of(null).focusedOption()).isEmpty();
        assertThat(new Dialog.Model(ACTIONS, "cancel", true).focusedOption()).contains(Option.of("cancel", "Cancel"));
        assertThat(Dialog.Model.of(ACTIONS).withFocusedAction("cancel").focusedAction())
                .isEqualTo("cancel");
        assertThat(Dialog.Model.of(ACTIONS).withFocus(false).focused()).isFalse();
        assertThat(Dialog.Model.of(ACTIONS)
                        .withActions(List.of(Option.of("only", "Only")))
                        .focusedAction())
                .isEqualTo("only");

        Dialog empty = new Dialog(Dialog.Config.of("T", List.of()));
        Dialog.Model none = empty.init(new InitContext(null, null)).model();
        assertThat(none.actions()).isEmpty();
        assertThat(empty.update(none, new Dialog.Msg.Next()).model()).isSameAs(none);
        assertThat(empty.update(none, new Dialog.Msg.Previous()).model()).isSameAs(none);
        assertThat(empty.update(none, new Dialog.Msg.Activate()).model()).isSameAs(none);
        assertThat(empty.update(none, new Dialog.Msg.Choose("ok")).model()).isSameAs(none);
        assertThat(empty.update(none, new Dialog.Msg.Activate()).outputs()).isEmpty();
        assertThat(empty.update(none, new Dialog.Msg.Cancel()).outputs()).containsExactly(new Dialog.Output.Canceled());
        assertThat(ElementScreen.render(empty.element(none), 3, 20).lines())
                .containsExactly(
                        "\u256D\u2500T\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E",
                        "\u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256F",
                        "");
        // A width narrower than the screen is clipped by the layout instead of overflowing it.
        Dialog narrow = new Dialog(Dialog.Config.of("T", ACTIONS).withWidth(4));
        assertThat(ElementScreen.render(narrow.element(Dialog.Model.of(ACTIONS)), 3, 40)
                        .lines())
                .containsExactly(
                        "                  \u256D\u2500\u2500\u256E",
                        "                  \u2502[O\u2502",
                        "                  \u2570\u2500\u2500\u256F");

        Dialog.Model present = Dialog.Model.of(ACTIONS);
        assertThatThrownBy(() -> dialog.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dialog.update(null, new Dialog.Msg.Next())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dialog.update(present, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dialog.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dialog.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dialog.element(present, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dialog.element(present, Elements.text("x"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DialogButton.Model<Dialog.Msg>(null, false, false, new Dialog.Msg.Next()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBorderlessDialogDrawsNoChromeAndItsContentIsNotInset() {
        Dialog plain = new Dialog(Dialog.Config.of("T", ACTIONS).withWidth(12).withBorder(Border.NONE));
        Dialog.Model model = Dialog.Model.of(ACTIONS);

        Element<Dialog.Msg> root = plain.element(model, Elements.text("ask"), message -> message);
        assertThat(ElementScreen.render(root, 4, 20).lines()).containsExactly("    ask", "", "    [OK]  [Cance", "");
        // A border that is not drawn costs the content no column, so the buttons start at the layer's
        // own left edge and a press there is the first button's.
        assertThat(press(root, 4, 20, 2, 4)).containsExactly(new Dialog.Msg.Choose("ok"));
        assertThat(press(root, 4, 20, 2, 0))
                .as("the cells the box leaves over belong to nobody")
                .isEmpty();
    }

    private Dialog.Model apply(Dialog.Model model, Dialog.Msg message) {
        return dialog.update(model, message).model();
    }

    private List<Dialog.Msg> route(Element<Dialog.Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                dialog.keys().bindings());
    }

    private static List<Dialog.Msg> press(Element<Dialog.Msg> root, int rows, int cols, int row, int col) {
        return press(root, rows, cols, press(row, col));
    }

    private static List<Dialog.Msg> press(Element<Dialog.Msg> root, int rows, int cols, PointerEvent report) {
        List<Dialog.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<Dialog.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, recorded::add);
        return recorded;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
