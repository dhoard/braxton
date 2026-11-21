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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A single-choice list: immutable state, pure actions, and a focusable view.
 *
 * <p>Confirming and canceling are intents; the owning application decides whether they close a
 * dialog, advance a workflow, or exit.</p>
 *
 * <p>The view draws two independent signals. The marker is selection - which option is chosen - and
 * is always painted. The active style is interaction - that keys land here - and is painted only
 * while the view is focused, matching {@link TableSelector} and the {@link Editor}'s caret. An
 * unfocused selector therefore still shows its selection, in the inactive style.</p>
 *
 * <p>Every option is one painted row, so the marker always names a whole option. A line break in an
 * option is painted as a space rather than splitting the row and pushing every later option down,
 * the same one-line rule {@link TableSelector} applies to its cells.</p>
 *
 * <p>A pointer press selects the option it landed on, and a second press on the selected option
 * confirms it - the pointer form of {@code enter}. A press below the last option selects nothing.</p>
 *
 * <p>The gestures this selector handles are a {@link KeyMap}: {@link #defaultKeys()} defines the
 * defaults, {@link #keys()} is the definition a caller can render through {@link Help}, and the
 * {@link #Selector(Config, KeyMap)} constructor replaces it. Removing or disabling an action removes
 * the behavior it named rather than leaving a key that fires a message nothing handles.</p>
 */
public final class Selector
        implements Component<Selector.Model, Selector.Msg, Selector.Output>, Interactive<Selector.Model, Selector.Msg> {

    /**
     * Immutable selector state. {@code focused} is focus, not selection.
     *
     * @param options the selectable options, one painted row each
     * @param selected the index of the selected option
     * @param focused whether the selector currently owns focus
     */
    public record Model(List<String> options, int selected, boolean focused) {

        /**
         * Returns the same value with focus replaced.
         *
         * @param value the requested focus
         * @return the value with the requested focus
         */
        public Model withFocus(boolean value) {
            // Matching the editor: unchanged focus keeps the instance, so a message that changed
            // nothing stays a no-op for the runtime's unchanged-reference view rule.
            return value == focused ? this : new Model(options, selected, value);
        }

        /**
         * Creates a {@code Model} value.
         *
         * @param options the selectable options, one painted row each
         * @param selected the index of the selected option
         * @param focused whether the selector currently owns focus
         */
        public Model {
            options = List.copyOf(options);
            selected = options.isEmpty() ? -1 : Math.max(0, Math.min(selected, options.size() - 1));
        }

        /**
         * Creates a {@code Model} value.
         *
         * @param options the selectable options, one painted row each
         * @return the normalized selector state
         */
        public static Model of(List<String> options) {
            return new Model(options, options.isEmpty() ? -1 : 0, true);
        }

        /**
         * Returns the selected option text, or an empty string when no option exists.
         *
         * @return the selected option text, or an empty string when no option exists
         */
        public String current() {
            return selected < 0 ? "" : options.get(selected);
        }
    }

    /**
     * One navigation or confirmation gesture.
     */
    public sealed interface Msg {
        /**
         * Moves the selection up.
         */
        record Up() implements Msg {}

        /**
         * Selects one option by its index: a pointer press.
         *
         * @param index the option to select; an index outside the options is ignored
         */
        record Select(int index) implements Msg {}

        /**
         * Moves the selection down.
         */
        record Down() implements Msg {}

        /**
         * Confirms the current selection.
         */
        record Confirm() implements Msg {}

        /**
         * Requests cancellation.
         */
        record Cancel() implements Msg {}
    }

    /**
     * What the owner should do after an action.
     */
    public sealed interface Output {
        /**
         * Reports the selected coordinates and display value.
         *
         * @param index the zero-based item index
         * @param value the display value of the chosen item
         */
        record Chosen(int index, String value) implements Output {}

        /**
         * Reports that the user canceled the selection.
         */
        record Cancelled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * The initial selector options and focused/unfocused row styles.
     *
     * @param options the immutable configuration
     * @param activeStyle the selected item style
     * @param inactiveStyle the unselected item style
     */
    public record Config(List<String> options, Style activeStyle, Style inactiveStyle) {
        /**
         * Creates a {@code Config} value.
         *
         * @param options the immutable configuration
         * @param activeStyle the selected item style
         * @param inactiveStyle the unselected item style
         */
        public Config {
            options = List.copyOf(options);
            activeStyle = activeStyle == null ? Style.NONE : activeStyle;
            inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
        }

        /**
         * Returns the default selector configuration.
         *
         * @return the default selector configuration
         */
        public static Config defaults() {
            return new Config(List.of(), Style.NONE.withInvert(), Style.NONE);
        }
    }

    private final Config config;
    private final KeyMap<Msg> keys;

    /**
     * A selector with the default keys.
     *
     * @param config the rendering policy; required
     */
    public Selector(Config config) {
        this(config, defaultKeys());
    }

    /**
     * A selector with its own keys.
     *
     * @param config the rendering policy; required
     * @param keys the actions this selector handles, matched while it is focused; required
     */
    public Selector(Config config, KeyMap<Msg> keys) {
        this.config = Objects.requireNonNull(config, "config");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * The default actions: up/down navigation, confirm and cancel.
     *
     * <p>They are the definition this widget routes and the definition {@link Help} renders, so a
     * caller that remaps one action through {@link KeyMap#withAliases} changes both.</p>
     *
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "move up", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "move down", "down")),
                KeyMap.group(
                        "Actions",
                        // A terminal reports Meta+Enter as ESC CR, which the decoder normalizes to
                        // alt+enter; the name-only switch this replaced also accepted it.
                        KeyAction.<Msg>of("confirm", new Msg.Confirm(), "enter", "choose", "enter", "alt+enter"),
                        KeyAction.<Msg>of("cancel", new Msg.Cancel(), "esc", "cancel", "escape")));
    }

    /**
     * The actions this selector handles.
     *
     * @return the key map
     */
    public KeyMap<Msg> keys() {
        return keys;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        return Update.next(Model.of(config.options()));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        return scene(model);
    }

    /**
     * The option rows, with the selector itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Element<Msg>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < model.options().size(); i++) {
            rows.add(Elements.text(line(model, i)));
        }
        return Elements.stack(Elements.column(rows), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * One option row: the marker is the selection, the style is where the keys land.
     */
    private StyledText line(Model model, int index) {
        String marker = index == model.selected() ? "\u276F " : "  ";
        Style style = index == model.selected() && model.focused() ? config.activeStyle() : config.inactiveStyle();
        return StyledText.of(marker + singleLine(model.options().get(index)), style);
    }

    /**
     * The selector's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        keys.actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A press selects the option it landed on; a second press on the selected option confirms it,
     * which is the pointer form of enter.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (!local.isLeftPress()
                || local.row() < 0
                || local.row() >= model.options().size()) {
            return;
        }
        emit.accept(
                local.clickCount() > 1 && local.row() == model.selected()
                        ? new Msg.Confirm()
                        : new Msg.Select(local.row()));
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Select select -> {
                int index = Math.max(
                        0, Math.min(select.index(), Math.max(0, state.options().size() - 1)));
                yield new Result(
                        new Model(state.options(), state.options().isEmpty() ? 0 : index, state.focused()), null);
            }
            case Msg.Up up ->
                new Result(new Model(state.options(), Math.max(0, state.selected() - 1), state.focused()), null);
            case Msg.Down down ->
                new Result(
                        new Model(
                                state.options(),
                                Math.min(Math.max(0, state.options().size() - 1), state.selected() + 1),
                                state.focused()),
                        null);
            // With no options there is nothing to confirm; Chosen(-1, "") would not be a choice.
            case Msg.Confirm confirm ->
                state.selected() < 0
                        ? new Result(state, null)
                        : new Result(state, new Output.Chosen(state.selected(), state.current()));
            case Msg.Cancel cancel -> new Result(state, new Output.Cancelled());
        };
    }

    /**
     * Flattens an option to the one row it is painted on: CRLF, CR and LF each become one space,
     * so no break can split the row and the styled-text boundary cannot drop a CR and merge the
     * words on either side of it.
     */
    private static String singleLine(String value) {
        return LineBreaks.singleRow(value);
    }
}
