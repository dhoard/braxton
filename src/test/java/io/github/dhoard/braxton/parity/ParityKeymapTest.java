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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.KeymapHelpDemo;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Selector;
import io.github.dhoard.braxton.widget.TableSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 01 acceptance: one action definition drives routing and help, help renders compact and
 * grouped, the existing widgets accept overridden actions without losing their defaults, and the
 * phase demo toggles help, remaps an action and routes focus.
 */
class ParityKeymapTest {

    private static final int WIDE = 80;

    private static KeyMap<String> navigation() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.of("up", "UP", "\u2191/k", "move up", "up", "k"),
                        KeyAction.of("down", "DOWN", "\u2193/j", "move down", "down", "j")),
                KeyMap.group(
                        "General",
                        KeyAction.of("help", "HELP", "?", "toggle help", "?"),
                        KeyAction.of("quit", "QUIT", "q", "quit", "q", "ctrl+c")));
    }

    private static InputEvent gesture(String spec) {
        return InputEvent.KeyInput.of(Key.parse(spec));
    }

    // ── R-01: aliases, disabled actions, duplicates, consume-once ────────────

    @Test
    void everyAliasOfAnActionProducesItsOneMessage() {
        KeyMap<String> keys = navigation();
        assertThat(keys.actionFor(Key.of("up"))).map(KeyAction::id).contains("up");
        assertThat(keys.actionFor(Key.of("k"))).map(KeyAction::id).contains("up");
        assertThat(keys.actionFor(Key.parse("ctrl+c"))).map(KeyAction::id).contains("quit");
        assertThat(keys.actionFor(Key.of("z"))).isEmpty();

        // The compiled bindings produce the same message for both aliases.
        assertThat(keys.bindings())
                .filteredOn(binding -> binding.message().equals("UP"))
                .hasSize(2);
    }

    @Test
    void routingThroughTheCompiledBindingsAnswersOneGestureOnce() {
        // An application's shortcuts are its own bindings, and they answer only a gesture the tree
        // declined: a field that handles a key keeps it, and an alias the tree does not answer
        // reaches the application's map exactly once.
        Element<String> quiet = Elements.column(Elements.text("x"));
        List<Binding<String>> bindings = navigation().bindings();

        assertThat(InteractionRouter.route(quiet, gesture("up"), List.of(), bindings))
                .containsExactly("UP");
        assertThat(InteractionRouter.route(quiet, gesture("k"), List.of(), bindings))
                .containsExactly("UP");
        // A gesture the map does not name reaches nobody.
        assertThat(InteractionRouter.route(quiet, gesture("z"), List.of(), bindings))
                .isEmpty();
    }

    @Test
    void aDisabledActionMatchesNothingAndLeavesTheBindingsAndHelp() {
        KeyMap<String> keys = navigation().withEnabled("help", false);
        assertThat(keys.actionFor(Key.of("?"))).isEmpty();
        assertThat(keys.bindings()).extracting(Binding::message).doesNotContain("HELP");
        assertThat(Help.render(keys, false, WIDE).plainText()).doesNotContain("toggle help");
        assertThat(Help.render(keys, true, WIDE).plainText()).doesNotContain("toggle help");
        // The definition survives, so enabling it again restores both routing and help.
        assertThat(keys.withEnabled("help", true)).isEqualTo(navigation());
    }

    @Test
    void overlappingEnabledAliasesAreRejectedWhenTheMapIsBuilt() {
        assertThatThrownBy(() -> KeyMap.of(
                        KeyMap.group("One", KeyAction.of("a", "A", "x", "first", "x")),
                        KeyMap.group("Two", KeyAction.of("b", "B", "y", "second", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate key alias x");

        // Re-enabling an action that would overlap reports the same conflict, while leaving it
        // disabled keeps the gesture unambiguous.
        KeyMap<String> disabled = KeyMap.of(
                KeyMap.group("One", KeyAction.of("a", "A", "x", "first", "x")),
                KeyMap.group("Two", KeyAction.of("b", "B", "y", "second", "x").withEnabled(false)));
        assertThat(disabled.actionFor(Key.of("x"))).map(KeyAction::id).contains("a");
        assertThatThrownBy(() -> disabled.withEnabled("b", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate key alias x");
    }

    @Test
    void duplicateActionIdentitiesAreRejected() {
        assertThatThrownBy(() -> KeyMap.of(
                        KeyMap.group("One", KeyAction.of("a", "A", "x", "first", "x")),
                        KeyMap.group("Two", KeyAction.of("a", "B", "y", "second", "y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate action id: a");
    }

    @Test
    void anIncompleteActionIsRejected() {
        assertThatThrownBy(() -> KeyAction.of(" ", "M", "x", "desc", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action id is required");
        assertThatThrownBy(() -> new KeyAction<String>("id", List.of(Key.of("x")), "x", "desc", null, true, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }

    @Test
    void aBlankLabelFallsBackToTheAliases() {
        assertThat(KeyAction.of("up", "UP", "", "move up", "up", "k").label()).isEqualTo("up/k");
    }

    @Test
    void remappingReturnsANewMapAndLeavesTheOriginalIntact() {
        KeyMap<String> remapped = navigation().withAliases("up", "w");
        assertThat(remapped.actionFor(Key.of("w"))).map(KeyAction::id).contains("up");
        assertThat(remapped.actionFor(Key.of("up"))).isEmpty();
        assertThat(navigation().actionFor(Key.of("up"))).map(KeyAction::id).contains("up");
        assertThat(navigation().actionFor(Key.of("w"))).isEmpty();
        assertThat(remapped).isNotEqualTo(navigation());

        assertThatThrownBy(() -> navigation().withAliases("missing", "w"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown action id: missing");
        assertThatThrownBy(() -> navigation().with(KeyAction.of("missing", "M", "m", "m", "m")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown action id: missing");
    }

    // ── R-02: compact and expanded help ──────────────────────────────────────

    @Test
    void compactHelpIsOneLineOfLabelsAndDescriptions() {
        assertThat(Help.render(navigation(), false, WIDE).plainText())
                .isEqualTo("\u2191/k move up \u2022 \u2193/j move down \u2022 ? toggle help \u2022 q quit");
    }

    @Test
    void compactHelpHonoursTheCompactFlag() {
        KeyMap<String> keys = navigation()
                .with(KeyAction.of("help", "HELP", "?", "toggle help", "?").withCompact(false));
        assertThat(Help.render(keys, false, WIDE).plainText())
                .isEqualTo("\u2191/k move up \u2022 \u2193/j move down \u2022 q quit");
    }

    @Test
    void expandedHelpIsOneLinePerGroupInMapOrder() {
        assertThat(Help.render(navigation(), true, WIDE).plainText())
                .isEqualTo("Navigation    \u2191/k move up    \u2193/j move down\nGeneral    ? toggle help    q quit");
    }

    @Test
    void aGroupWithNothingEnabledDisappearsFromExpandedHelp() {
        KeyMap<String> keys = navigation().withEnabled("help", false).withEnabled("quit", false);
        assertThat(Help.render(keys, true, WIDE).plainText())
                .isEqualTo("Navigation    \u2191/k move up    \u2193/j move down");
    }

    @Test
    void anEmptyMapRendersNothingAtEveryWidth() {
        for (int width : new int[] {0, 1, 2, 40, WIDE}) {
            assertThat(Help.render(KeyMap.empty(), false, width).isEmpty()).isTrue();
            assertThat(Help.render(KeyMap.empty(), true, width).isEmpty()).isTrue();
        }
        assertThat(Help.render(null, true, WIDE).isEmpty()).isTrue();

        // An action with no label and no description says nothing, so it leaves no separator behind.
        KeyMap<String> silent =
                KeyMap.of(KeyMap.group("", new KeyAction<>("silent", List.of(), "", "", "SILENT", true, true)));
        assertThat(Help.render(silent, false, WIDE).isEmpty()).isTrue();
        assertThat(Help.render(silent, true, WIDE).isEmpty()).isTrue();
    }

    @Test
    void helpNeverPaintsPastTheWidthItWasGiven() {
        String full = Help.render(navigation(), false, WIDE).plainText();
        for (int width = 0; width <= TextMeasure.columns(full) + 2; width++) {
            StyledText rendered = Help.render(navigation(), false, width);
            assertThat(TextMeasure.columns(rendered.plainText()))
                    .as("width %s", width)
                    .isLessThanOrEqualTo(width);
        }
        assertThat(Help.render(navigation(), false, 0).isEmpty()).isTrue();
        // One column fits the ellipsis that stands for what was dropped.
        assertThat(Help.render(navigation(), false, 1).plainText()).isEqualTo(Help.ELLIPSIS);
        assertThat(Help.render(navigation(), true, 1).plainText()).isEqualTo(Help.ELLIPSIS + "\n" + Help.ELLIPSIS);
    }

    @Test
    void truncationCutsBetweenGraphemesAndDropsAWideClusterItCannotFit() {
        KeyMap<String> wide =
                KeyMap.of(KeyMap.group("Wide", KeyAction.of("wide", "WIDE", "\u754C\u754C\u754C", "cells", "w")));
        String full = Help.render(wide, false, WIDE).plainText();
        assertThat(full).isEqualTo("\u754C\u754C\u754C cells");
        // A two-column cluster cannot share the budget with the ellipsis, so it is dropped whole
        // rather than half-painted.
        assertThat(Help.render(wide, false, 1).plainText()).isEqualTo(Help.ELLIPSIS);
        assertThat(Help.render(wide, false, 2).plainText()).isEqualTo(Help.ELLIPSIS);
        assertThat(Help.render(wide, false, 3).plainText()).isEqualTo("\u754C" + Help.ELLIPSIS);
        for (int width = 0; width <= TextMeasure.columns(full); width++) {
            String rendered = Help.render(wide, false, width).plainText();
            assertThat(TextMeasure.columns(rendered)).isLessThanOrEqualTo(width);
            if (rendered.endsWith(Help.ELLIPSIS)) {
                String kept = rendered.substring(0, rendered.length() - Help.ELLIPSIS.length());
                assertThat(full).as("width %s kept whole clusters", width).startsWith(kept);
            }
        }
    }

    @Test
    void anEmojiLabelIsMeasuredAndCutAsTheWideClusterItIs() {
        KeyMap<String> keys = KeyMap.of(
                KeyMap.group("General", KeyAction.of("launch", "LAUNCH", "\uD83D\uDE80/r", "launch it", "r")));
        assertThat(Help.render(keys, false, WIDE).plainText()).isEqualTo("\uD83D\uDE80/r launch it");
        // The rocket occupies two columns, so the budget is spent on whole clusters only.
        assertThat(Help.render(keys, false, 2).plainText()).isEqualTo(Help.ELLIPSIS);
        assertThat(Help.render(keys, false, 3).plainText()).isEqualTo("\uD83D\uDE80" + Help.ELLIPSIS);
        for (int width = 0; width <= TextMeasure.columns("\uD83D\uDE80/r launch it"); width++) {
            String rendered = Help.render(keys, false, width).plainText();
            assertThat(TextMeasure.columns(rendered)).as("width %s", width).isLessThanOrEqualTo(width);
            assertThat(hasUnpairedSurrogate(rendered))
                    .as("width %s cut between the halves of the emoji", width)
                    .isFalse();
        }
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(value)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void helpKeepsTheStyleOfEveryRunItKeeps() {
        Help.Config config = Help.Config.defaults()
                .withKeyStyle(Style.NONE.withBold())
                .withDescriptionStyle(Style.NONE.withItalic())
                .withSeparatorStyle(Style.NONE.withUnderline())
                .withTitleStyle(Style.NONE.withInvert());
        StyledText compact = Help.render(navigation(), config, false, WIDE);
        assertThat(compact.spans())
                .anySatisfy(span -> assertThat(span.text()).isEqualTo("\u2191/k"))
                .anySatisfy(span -> assertThat(span.text()).isEqualTo("move up"));
        assertThat(compact.spans())
                .filteredOn(span -> span.text().equals("\u2191/k"))
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE.withBold()));
        assertThat(compact.spans())
                .filteredOn(span -> span.text().equals("move up"))
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE.withItalic()));

        StyledText expanded = Help.render(navigation(), config, true, WIDE);
        assertThat(expanded.spans())
                .filteredOn(span -> span.text().equals("Navigation"))
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE.withInvert()));
    }

    @Test
    void renderingHelpIsPureAndDoesNotChangeTheMap() {
        KeyMap<String> keys = navigation();
        StyledText first = Help.render(keys, true, 40);
        StyledText second = Help.render(keys, true, 40);
        assertThat(first).isEqualTo(second);
        assertThat(keys).isEqualTo(navigation());
        assertThat(keys.actions()).extracting(KeyAction::id).containsExactly("up", "down", "help", "quit");
    }

    // ── R-03: widgets accept overridden actions and keep their defaults ──────

    private static Optional<Selector.Msg> selectorAction(Selector selector, Key key) {
        List<Selector.Msg> produced = new ArrayList<>(1);
        selector.onKey(InputEvent.KeyInput.of(key, ""), Selector.Model.of(List.of("a", "b")), produced::add);
        return produced.stream().findFirst();
    }

    private static Optional<TableSelector.Msg> tableAction(TableSelector selector, Key key) {
        TableSelector.Model model = TableSelector.Model.of(List.of("h"), List.of(List.of("c")));
        List<TableSelector.Msg> produced = new ArrayList<>(1);
        selector.onKey(InputEvent.KeyInput.of(key, ""), model, produced::add);
        return produced.stream().findFirst();
    }

    private static Optional<Editor.Msg> editorAction(Editor editor, InputEvent event) {
        List<Editor.Msg> produced = new ArrayList<>(1);
        Editor.Model model = Editor.Model.of("ab");
        if (event instanceof InputEvent.KeyInput key) {
            editor.onKey(key, model, produced::add);
        } else {
            editor.onInput(event, model, produced::add);
        }
        return produced.stream().findFirst();
    }

    @Test
    void aSelectorKeepsItsDefaultShortcuts() {
        Selector selector = new Selector(Selector.Config.defaults());
        assertThat(selectorAction(selector, Key.UP)).contains(new Selector.Msg.Up());
        assertThat(selectorAction(selector, Key.DOWN)).contains(new Selector.Msg.Down());
        assertThat(selectorAction(selector, Key.ENTER)).contains(new Selector.Msg.Confirm());
        assertThat(selectorAction(selector, Key.ESCAPE)).contains(new Selector.Msg.Cancel());
        // A terminal reports Meta+Enter as ESC CR; the name-only switch this replaced accepted it.
        assertThat(selectorAction(selector, Key.of("enter", Modifiers.ALT))).contains(new Selector.Msg.Confirm());
        assertThat(selectorAction(selector, Key.of("x"))).isEmpty();
    }

    @Test
    void aSelectorHandlesTheActionsItWasGivenInsteadOfItsDefaults() {
        Selector selector =
                new Selector(Selector.Config.defaults(), Selector.defaultKeys().withAliases("up", "k"));
        assertThat(selectorAction(selector, Key.of("k"))).contains(new Selector.Msg.Up());
        assertThat(selectorAction(selector, Key.UP)).isEmpty();
        assertThat(selectorAction(selector, Key.DOWN)).contains(new Selector.Msg.Down());
        assertThat(selector.keys().action("up")).map(KeyAction::aliases).contains(List.of(Key.of("k")));
    }

    @Test
    void aTableSelectorKeepsItsDefaultsAndAcceptsOverrides() {
        TableSelector defaults = new TableSelector(TableSelector.Config.defaults());
        assertThat(tableAction(defaults, Key.LEFT)).contains(new TableSelector.Msg.Left());
        assertThat(tableAction(defaults, Key.RIGHT)).contains(new TableSelector.Msg.Right());
        assertThat(tableAction(defaults, Key.ENTER)).contains(new TableSelector.Msg.Confirm());
        assertThat(tableAction(defaults, Key.ESCAPE)).contains(new TableSelector.Msg.Cancel());

        TableSelector remapped = new TableSelector(
                TableSelector.Config.defaults(), TableSelector.defaultKeys().withAliases("right", "l"));
        assertThat(tableAction(remapped, Key.of("l"))).contains(new TableSelector.Msg.Right());
        assertThat(tableAction(remapped, Key.RIGHT)).isEmpty();
    }

    @Test
    void anEditorKeepsItsDefaultShortcuts() {
        Editor editor = new Editor(EditorConfig.defaults());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.ENTER))).contains(new Editor.Msg.Submit());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.BACKSPACE))).contains(new Editor.Msg.Backspace());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.of("backspace", Modifiers.ALT))))
                .contains(new Editor.Msg.Backspace());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.DELETE))).contains(new Editor.Msg.Delete());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.LEFT))).contains(new Editor.Msg.MoveLeft());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.END))).contains(new Editor.Msg.MoveEnd());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.of("j", Modifiers.CTRL))))
                .contains(new Editor.Msg.Newline());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.of("a", Modifiers.CTRL))))
                .contains(new Editor.Msg.SelectAll());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.of("c", Modifiers.ALT))))
                .contains(new Editor.Msg.Copy());
        assertThat(editorAction(editor, InputEvent.KeyInput.of(Key.of("v", Modifiers.CTRL))))
                .contains(new Editor.Msg.PasteRequest());
        assertThat(editorAction(editor, new InputEvent.KeyInput(Key.of("x"), "x", 0L)))
                .contains(new Editor.Msg.Insert("x"));
        assertThat(editorAction(editor, new InputEvent.PasteInput("pasted"))).contains(new Editor.Msg.Paste("pasted"));
    }

    @Test
    void anEditorActionWinsOverTheTextItsGestureCarries() {
        EditorConfig config = EditorConfig.defaults();
        Editor remapped = new Editor(config, Editor.defaultKeys(config).withAliases("submit", "s"));
        assertThat(editorAction(remapped, new InputEvent.KeyInput(Key.of("s"), "s", 0L)))
                .contains(new Editor.Msg.Submit());
        // The same gesture under the default keys is still printable text.
        assertThat(editorAction(new Editor(config), new InputEvent.KeyInput(Key.of("s"), "s", 0L)))
                .contains(new Editor.Msg.Insert("s"));
        // The static mapping is the default map's answer, and it still fires.
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.ENTER), config)).contains(new Editor.Msg.Submit());
    }

    @Test
    void aWidgetsOwnKeyMapIsWhatHelpRenders() {
        Selector selector = new Selector(Selector.Config.defaults());
        assertThat(Help.render(selector.keys(), false, WIDE).plainText())
                .isEqualTo("\u2191 move up \u2022 \u2193 move down \u2022 enter choose \u2022 esc cancel");
        assertThat(Help.render(TableSelector.defaultKeys(), true, WIDE).plainText())
                .isEqualTo("Navigation    \u2191 move up    \u2193 move down    \u2190 move left    \u2192 move right\n"
                        + "Actions    enter choose    esc cancel");
        assertThat(Help.render(Editor.defaultKeys(EditorConfig.defaults()), false, WIDE)
                        .plainText())
                .isEqualTo("enter submit \u2022 alt+c copy \u2022 ctrl+v paste");
    }

    // ── R-04: the demo scene ────────────────────────────────────────────────

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    /**
     * One event routed the way the runtime routes it: claims, tree, then the component's map.
     */
    private static Optional<KeymapHelpDemo.Msg> route(
            io.github.dhoard.braxton.testing.ComponentDriver<
                            KeymapHelpDemo.Model, KeymapHelpDemo.Msg, io.github.dhoard.braxton.mvu.Never>
                    driver,
            InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(driver.component(), driver.model(), CONTEXT, event)
                .stream()
                .findFirst();
    }

    private static ComponentDriver<KeymapHelpDemo.Model, KeymapHelpDemo.Msg, io.github.dhoard.braxton.mvu.Never>
            demo() {
        return ComponentDriver.initialize(new KeymapHelpDemo.Scene(), new InitContext(null, null));
    }

    private static List<String> lines(ComponentDriver<KeymapHelpDemo.Model, KeymapHelpDemo.Msg, ?> driver) {
        return ElementScreen.render(
                        driver.view(CONTEXT),
                        CONTEXT.viewport().rows(),
                        CONTEXT.viewport().cols())
                .lines();
    }

    @Test
    void theDemoStartsCompactWithTheDefaultKeys() {
        var driver = demo();
        List<String> screen = lines(driver);
        assertThat(screen.get(0)).isEqualTo("left=alpha right=alpha focus=left help=compact keys=default last=none");
        assertThat(screen).anySatisfy(line -> assertThat(line).startsWith("\u276F alpha"));
        // Only the focused field is active, so only it paints the active style.
        assertThat(driver.model().left().focused()).isTrue();
        assertThat(driver.model().right().focused()).isFalse();
        assertThat(String.join("\n", screen))
                .contains("? toggle help \u2022 r remap help key \u2022 tab next field \u2022 q quit")
                .contains("\u2191 move up \u2022 \u2193 move down \u2022 enter choose \u2022 esc cancel");
    }

    @Test
    void theDemoExpandsAndCollapsesHelp() {
        var driver = demo();
        driver.send(new KeymapHelpDemo.Msg.ToggleHelp());
        List<String> screen = lines(driver);
        assertThat(screen.get(0)).contains("help=full").contains("last=toggle-help");
        String text = String.join("\n", screen);
        assertThat(text)
                .contains("Help    ? toggle help    r remap help key")
                .contains("Focus    tab next field")
                .contains("Navigation    \u2191 move up    \u2193 move down");

        driver.send(new KeymapHelpDemo.Msg.ToggleHelp());
        assertThat(String.join("\n", lines(driver))).doesNotContain("Help    ? toggle help");
    }

    @Test
    void theDemoRemapsAndDisablesAnActionInBothRoutingAndHelp() {
        KeymapHelpDemo.Scene scene = new KeymapHelpDemo.Scene();
        var driver = ComponentDriver.initialize(scene, new InitContext(null, null));
        driver.send(new KeymapHelpDemo.Msg.Remap());

        List<String> screen = lines(driver);
        assertThat(screen.get(0)).contains("keys=remapped").contains("last=remap");
        String text = String.join("\n", screen);
        assertThat(text).contains("?/h toggle help").doesNotContain("remap help key");

        // The remapped alias fires the same message, and the disabled action fires nothing. The
        // scene declares its map, so the component is what the driver routes through.
        assertThat(route(driver, gesture("h"))).contains(new KeymapHelpDemo.Msg.ToggleHelp());
        assertThat(route(driver, gesture("?"))).contains(new KeymapHelpDemo.Msg.ToggleHelp());
        assertThat(route(driver, gesture("r"))).isEmpty();
    }

    @Test
    void theDemoRoutesNavigationKeysToTheFocusedSelectorOnly() {
        var driver = demo();

        // Focus starts on the left selector.
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("down"))
                        .stream()
                        .findFirst())
                .contains(new KeymapHelpDemo.Msg.Left(new Selector.Msg.Down()));
        driver.send(new KeymapHelpDemo.Msg.Left(new Selector.Msg.Down()));
        assertThat(lines(driver).get(0)).contains("left=beta").contains("right=alpha");

        // Tab moves focus; the same gesture now reaches the right selector and leaves the left one.
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("tab"))
                        .stream()
                        .findFirst())
                .contains(new KeymapHelpDemo.Msg.ToggleFocus());
        Update<KeymapHelpDemo.Model, KeymapHelpDemo.Msg, io.github.dhoard.braxton.mvu.Never> focused =
                driver.send(new KeymapHelpDemo.Msg.ToggleFocus());
        assertThat(focused.model().leftFocused()).isFalse();
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("down"))
                        .stream()
                        .findFirst())
                .contains(new KeymapHelpDemo.Msg.Right(new Selector.Msg.Down()));
        driver.send(new KeymapHelpDemo.Msg.Right(new Selector.Msg.Down()));
        assertThat(lines(driver).get(0))
                .contains("left=beta")
                .contains("right=beta")
                .contains("focus=right")
                .contains("last=down");
    }

    @Test
    void theDemoQuitsOnItsQuitAction() {
        var driver = demo();
        assertThat(driver.send(new KeymapHelpDemo.Msg.Quit()).command()).isNotNull();
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("q"))
                        .stream()
                        .findFirst())
                .contains(new KeymapHelpDemo.Msg.Quit());
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("ctrl+c"))
                        .stream()
                        .findFirst())
                .contains(new KeymapHelpDemo.Msg.Quit());
    }
}
