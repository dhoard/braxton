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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The command palette: its query row, its matches, its keys, its caret and its window.
 */
class CommandPaletteTest {

    private static final List<CommandPalette.Command> COMMANDS = List.of(
            CommandPalette.Command.of("open", "Open File", "open a file"),
            CommandPalette.Command.of("save", "Save File", "save this file"),
            CommandPalette.Command.of("quit", "Quit", "leave the application"));

    private final CommandPalette palette = new CommandPalette(COMMANDS);

    @Test
    void theQueryRowComesFirstAndTheMatchesBelowIt() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();

        assertThat(ElementScreen.render(palette.element(model), 4, 30).lines())
                .containsExactly(
                        ">", "Open File  open a file", "Save File  save this file", "Quit  leave the application");
    }

    @Test
    void typingNarrowsTheListAndTheHighlightFollowsTheQuery() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();

        CommandPalette.Model typed = typed(palette, model, "sa");
        assertThat(typed.query()).isEqualTo("sa");
        assertThat(typed.matches().stream().map(CommandPalette.Command::id)).containsExactly("save");
        assertThat(typed.highlighted()).isEqualTo("save");

        // A query nothing matches leaves no highlight, and the palette keeps taking keys so the
        // query can be corrected.
        CommandPalette.Model none = typed(palette, model, "zzz");
        assertThat(none.matches()).isEmpty();
        assertThat(none.highlighted()).isEmpty();
        assertThat(palette.canFocus(none)).isTrue();
        assertThat(ElementScreen.render(palette.element(none), 3, 20).lines()).containsExactly("> zzz", "", "");

        // An empty query shows every command in the application's order, not in score order.
        assertThat(none.withQuery("").matches()).containsExactlyElementsOf(COMMANDS);
    }

    @Test
    void theQueryIsEditedByTypingBackspaceAndClear() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();

        assertThat(apply(palette, model, new CommandPalette.Msg.Typed("sav")).query())
                .isEqualTo("sav");
        assertThat(apply(palette, model, new CommandPalette.Msg.Typed("sav\u0001x"))
                        .query())
                .as("a control character is never part of a query")
                .isEqualTo("savx");
        assertThat(apply(palette, model, new CommandPalette.Msg.Typed(null))).isSameAs(model);

        CommandPalette.Model typed = apply(palette, model, new CommandPalette.Msg.Typed("sav"));
        assertThat(apply(palette, typed, new CommandPalette.Msg.Backspace()).query())
                .isEqualTo("sa");
        assertThat(apply(palette, typed, new CommandPalette.Msg.Clear()).query())
                .isEmpty();
        assertThat(apply(palette, model, new CommandPalette.Msg.Backspace()))
                .as("a backspace on an empty query changes nothing")
                .isSameAs(model);
        assertThat(apply(palette, model, new CommandPalette.Msg.Clear())).isSameAs(model);

        // A query is one row: a control character is never part of it, so a break cannot arrive.
        assertThat(apply(palette, model, new CommandPalette.Msg.Typed("a\nb")).query())
                .isEqualTo("ab");
    }

    @Test
    void theRunTheQueryMatchedIsMarkedInTheTitle() {
        CommandPalette.Model model =
                typed(palette, palette.init(new InitContext(null, null)).model(), "File");

        ElementScreen screen = ElementScreen.render(palette.element(model), 3, 24, Themes.DARK);

        assertThat(screen.line(1)).isEqualTo("Open File  open a file");
        // "File" starts at column 5 and is painted in the accent style over the title's own style and
        // the selection's background.
        Style background = Themes.DARK.style(ThemeToken.SELECTED);
        Style focus = Themes.DARK.style(ThemeToken.FOCUS);
        Style title = Themes.DARK.style(ThemeToken.TEXT).merge(focus);
        assertThat(screen.styles(1).get(0))
                .as("the title is the theme's text style on the selection")
                .isEqualTo(background.merge(title));
        assertThat(screen.styles(1).get(5))
                .as("the matched run is the accent over the title's own style")
                .isEqualTo(background.merge(
                        title.merge(Themes.DARK.style(ThemeToken.ACCENT).merge(focus))));
        assertThat(screen.styles(1).get(14))
                .as("a description is muted, and focus colors it over the selection")
                .isEqualTo(background.merge(Themes.DARK.style(ThemeToken.MUTED).merge(focus)));
    }

    @Test
    void theHighlightStepsPagesAndJumpsSkippingCommandsThatCannotBeChosen() {
        List<CommandPalette.Command> commands = List.of(
                CommandPalette.Command.of("a", "Alpha"),
                CommandPalette.Command.of("b", "Bravo").withEnabled(false),
                CommandPalette.Command.of("c", "Charlie"),
                CommandPalette.Command.of("d", "Delta"));
        CommandPalette palette = new CommandPalette(commands);
        CommandPalette.Model model =
                palette.init(new InitContext(null, null)).model().withRows(3);

        assertThat(model.highlighted()).isEqualTo("a");
        assertThat(apply(palette, model, new CommandPalette.Msg.Down()).highlighted())
                .isEqualTo("c");
        assertThat(apply(palette, model, new CommandPalette.Msg.End()).highlighted())
                .isEqualTo("d");
        assertThat(apply(palette, model, new CommandPalette.Msg.PageDown()).highlighted())
                .as("a page of two rows from the first command lands on the last one")
                .isEqualTo("d");
        assertThat(apply(palette, model, new CommandPalette.Msg.PageUp()).highlighted())
                .isEqualTo("a");
        assertThat(apply(palette, model, new CommandPalette.Msg.Home()).highlighted())
                .isEqualTo("a");
        assertThat(apply(palette, model, new CommandPalette.Msg.Up()))
                .as("a step with nowhere to go changes nothing")
                .isSameAs(model);

        // The window keeps the highlight on screen: a window of three rows holds the query and two.
        CommandPalette.Model last = apply(palette, model, new CommandPalette.Msg.End());
        assertThat(last.offset()).isEqualTo(2);
        assertThat(ElementScreen.render(palette.element(last), 3, 12).lines()).containsExactly(">", "Charlie", "Delta");
    }

    @Test
    void enterChoosesTheHighlightedCommandAndEscapeCancels() {
        CommandPalette.Model model =
                typed(palette, palette.init(new InitContext(null, null)).model(), "save");

        assertThat(palette.update(model, new CommandPalette.Msg.Activate()).outputs())
                .containsExactly(new CommandPalette.Output.Chosen(
                        "save", CommandPalette.Command.of("save", "Save File", "save this file")));
        assertThat(palette.update(model, new CommandPalette.Msg.Cancel()).outputs())
                .containsExactly(new CommandPalette.Output.Canceled());

        CommandPalette.Model none =
                typed(palette, palette.init(new InitContext(null, null)).model(), "zzz");
        assertThat(palette.update(none, new CommandPalette.Msg.Activate()).outputs())
                .isEmpty();
        assertThat(palette.update(model, new CommandPalette.Msg.Choose("nope")).outputs())
                .isEmpty();
        assertThat(palette.update(model, new CommandPalette.Msg.Choose("quit")).outputs())
                .as("a command the query hides cannot be chosen through the pointer either")
                .isEmpty();
    }

    @Test
    void aPressChoosesTheRowItLandedOnAndNothingElse() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();

        assertThat(pointer(palette, model, press(2, 4))).containsExactly(new CommandPalette.Msg.Choose("save"));
        assertThat(pointer(palette, model, press(0, 4)))
                .as("the query row is not a choice")
                .isEmpty();
        assertThat(pointer(palette, model, press(9, 4)))
                .as("a row below the matches is not a choice")
                .isEmpty();

        CommandPalette palette =
                new CommandPalette(List.of(CommandPalette.Command.of("x", "X").withEnabled(false)));
        assertThat(pointer(palette, palette.init(new InitContext(null, null)).model(), press(1, 0)))
                .isEmpty();
    }

    @Test
    void theCaretSitsAfterTheQueryAndOnlyWhileFocused() {
        CommandPalette.Model model =
                typed(palette, palette.init(new InitContext(null, null)).model(), "sav");
        Element<CommandPalette.Msg> root = Elements.commandPalette(palette, model, message -> message);

        assertThat(InteractionRouter.cursor(ElementTree.layout(root, new Rect(0, 0, 4, 20))))
                .isEqualTo(Cursor.at(0, 5));

        Element<CommandPalette.Msg> unfocused =
                Elements.commandPalette(palette, model.withFocus(false), message -> message);
        assertThat(InteractionRouter.cursor(ElementTree.layout(unfocused, new Rect(0, 0, 4, 20))))
                .isNull();
        assertThat(palette.cursor(new Rect(0, 0, 4, 20), model.withFocus(false)))
                .isNull();
        assertThat(palette.cursor(new Rect(0, 0, 0, 0), model)).isNull();

        // A caret is never outside the widget it belongs to.
        assertThat(palette.cursor(new Rect(2, 0, 1, 3), typed(palette, model, "a-long-query")))
                .isEqualTo(Cursor.at(2, 2));
    }

    @Test
    void rowsArePaintedInTheThemesStyles() {
        List<CommandPalette.Command> commands = List.of(
                CommandPalette.Command.of("a", "Alpha", "first"),
                CommandPalette.Command.of("b", "Bravo").withEnabled(false));
        CommandPalette palette = new CommandPalette(commands);
        CommandPalette.Model model = CommandPalette.Model.of(commands);

        ElementScreen dark = ElementScreen.render(palette.element(model), 3, 18, Themes.DARK);
        ElementScreen light = ElementScreen.render(palette.element(model), 3, 18, Themes.LIGHT);

        assertThat(dark.styles(0).get(0))
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(0).get(1))
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(1).get(16))
                .as("the highlight is the whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(dark.styles(2).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.DISABLED));
        assertThat(light.styles(2).get(0)).isEqualTo(Themes.LIGHT.style(ThemeToken.DISABLED));

        // An unfocused palette paints the same rows without the focus style.
        ElementScreen unfocused = ElementScreen.render(palette.element(model.withFocus(false)), 3, 18, Themes.DARK);
        assertThat(unfocused.styles(0).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        // The query's own cells are the theme's text style, and the caret the palette places is the
        // only thing the frame marks.
        assertThat(unfocused.styles(0).get(1)).isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
    }

    @Test
    void aNarrowPaletteDropsTheDescriptionBeforeTheTitle() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();

        // Twenty-two columns hold the title, the gap and the whole description.
        assertThat(ElementScreen.render(palette.element(model), 2, 22).line(1)).isEqualTo("Open File  open a file");
        // Fewer hold the title, the gap and the start of the description, which is clipped.
        assertThat(ElementScreen.render(palette.element(model), 2, 19).line(1)).isEqualTo("Open File  open a f");
        // Eleven hold only the title: the description is left out rather than the title crowded.
        assertThat(ElementScreen.render(palette.element(model), 2, 11).lines()).containsExactly(">", "Open File");
        assertThat(ElementScreen.render(palette.element(model), 2, 4).line(1)).isEqualTo("Open");
        assertThat(ElementScreen.render(palette.element(model), 0, 0).lines()).isEmpty();
    }

    @Test
    void aPasteIsTextTheQueryTakes() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();
        List<CommandPalette.Msg> produced = new ArrayList<>(1);

        palette.onInput(new InputEvent.PasteInput("qu"), model, produced::add);

        assertThat(produced).containsExactly(new CommandPalette.Msg.Typed("qu"));
        produced.clear();
        palette.onInput(InputEvent.KeyInput.of(Key.of("x")), model, produced::add);
        assertThat(produced).isEmpty();
    }

    @Test
    void typingIsOfferedOnlyForKeysTheMapDoesNotClaim() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();
        List<CommandPalette.Msg> produced = new ArrayList<>(1);

        // A printable key becomes the text it carries; a key the map claims is that action instead.
        palette.onKey(InputEvent.KeyInput.of(Key.of("s"), "s"), model, produced::add);
        assertThat(produced).containsExactly(new CommandPalette.Msg.Typed("s"));

        produced.clear();
        palette.onKey(InputEvent.KeyInput.of(Key.UP), model, produced::add);
        assertThat(produced).containsExactly(new CommandPalette.Msg.Up());

        produced.clear();
        palette.onKey(InputEvent.KeyInput.of(Key.of("a")), model, produced::add);
        assertThat(produced).isEmpty();

        produced.clear();
        palette.onKey(InputEvent.KeyInput.of(Key.of("s"), "s", KeyEventType.RELEASE), model, produced::add);
        assertThat(produced)
                .as("a release carries the press's text but never types it again")
                .isEmpty();

        assertThat(palette.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly(
                        "up", "down", "pageup", "pagedown", "home", "end", "delete", "clear", "choose", "dismiss");
    }

    @Test
    void thePaletteIsAFocusStopWhileSomethingMatchesAndAnswersItsOwnKeys() {
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();
        Element<CommandPalette.Msg> root = Elements.commandPalette(palette, model, message -> message);

        assertThat(palette.focused(model)).isTrue();
        assertThat(palette.focused(model.withFocus(false))).isFalse();
        assertThat(palette.canFocus(model)).isTrue();
        assertThat(ElementScreen.render(root, 4, 20).focusables()).containsExactly(new Rect(0, 0, 4, 20));

        List<CommandPalette.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new CommandPalette.Msg.Down());
    }

    @Test
    void commandsAndConfigurationAreValidatedAndReplaced() {
        assertThatThrownBy(() -> CommandPalette.Command.of(" ", "title"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command id");
        assertThatThrownBy(() -> new CommandPalette.Config(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThat(CommandPalette.Model.of(List.of()).withRows(-1).resultRows())
                .as("a window is a value to clamp, not a definition to reject")
                .isZero();

        assertThat(new CommandPalette.Config(null, 2).commands()).isEmpty();
        assertThat(CommandPalette.Config.of(COMMANDS).withCommands(List.of()).commands())
                .isEmpty();
        assertThat(CommandPalette.Config.of(COMMANDS).withRows(2).rows()).isEqualTo(2);
        assertThat(CommandPalette.Command.of("a", null, null).title()).isEmpty();
        assertThat(CommandPalette.Command.of("a", "A").withDescription("d").searchText())
                .isEqualTo("A d");
        assertThat(CommandPalette.Command.of("a", "A", "d").withDescription("").searchText())
                .isEqualTo("A");
        assertThat(CommandPalette.Command.of("a", "A").withEnabled(false).enabled())
                .isFalse();

        CommandPalette palette = new CommandPalette(COMMANDS);
        CommandPalette.Model model = palette.init(new InitContext(null, null)).model();
        CommandPalette.Model replaced =
                apply(palette, model, new CommandPalette.Msg.Replace(List.of(CommandPalette.Command.of("x", "X"))));
        assertThat(replaced.matches()).containsExactly(CommandPalette.Command.of("x", "X"));
        assertThat(replaced.highlighted()).isEqualTo("x");
        assertThat(apply(palette, model, new CommandPalette.Msg.Replace(null)).matches())
                .isEmpty();

        CommandPalette.Model narrow = typed(palette, model, "open");
        assertThat(narrow.withCommands(List.of()).matches()).isEmpty();
        assertThat(narrow.withHighlight("nope").highlighted()).isEqualTo("open");
        assertThat(model.withHighlight(null).highlighted()).isEqualTo("open");
        assertThat(model.visibleRows().get(0).matchStart()).isEqualTo(-1);
        assertThat(new CommandPalette(new CommandPalette.Config(List.of(), 0))
                        .config()
                        .commands())
                .isEmpty();
    }

    private static CommandPalette.Model typed(CommandPalette palette, CommandPalette.Model model, String text) {
        return apply(palette, model, new CommandPalette.Msg.Typed(text));
    }

    private static CommandPalette.Model apply(
            CommandPalette palette, CommandPalette.Model model, CommandPalette.Msg message) {
        return palette.update(model, message).model();
    }

    private static List<CommandPalette.Msg> pointer(
            CommandPalette palette, CommandPalette.Model model, PointerEvent report) {
        List<CommandPalette.Msg> produced = new ArrayList<>(1);
        palette.onPointer(report, new Rect(0, 0, 4, 30), model, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
