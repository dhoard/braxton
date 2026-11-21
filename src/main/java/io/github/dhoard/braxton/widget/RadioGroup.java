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
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A radio group: a column of options of which exactly one is chosen.
 *
 * <p>The group owns one fact - which option is chosen - and its options are the application's data,
 * each with a stable identity. A press, an arrow, {@code home} or {@code end} moves the dot, and the
 * choice is reported as an {@link Output.Chosen} carrying the option rather than its row number, so
 * an application that reorders its options cannot be handed the wrong one.</p>
 *
 * <h2>Rows</h2>
 *
 * <p>Every option is one row: a dot in a fixed column, one separating column, then the label. The
 * chosen row's dot is filled and the others are hollow, and the chosen row is filled with the
 * theme's selection, so the group reads as one choice even at a glance. A label wider than the row
 * is clipped rather than wrapped, because a radio row is one line.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>{@code up} and {@code down} choose the previous and the next option and {@code home} and
 * {@code end} the first and the last: a radio group is one value the user changes in place, so an
 * arrow <em>is</em> the choice and is reported as one. {@code space} and {@code enter} choose the
 * option that is already chosen, which is how an application learns that the user accepted the
 * default rather than merely left it. The gestures are a {@link KeyMap} -
 * {@link #defaultKeys()} defines them and {@link #keys()} is what the widget routes, so
 * {@link Help} describes what actually fires.</p>
 *
 * <p>A pointer press chooses the row it landed on, including the row that is already chosen. A press
 * below the last option chooses nothing.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>A chosen row's dot and its label are the theme's {@code ACCENT} over a {@code SELECTED} fill,
 * and a row that is not chosen is the theme's {@code MUTED} dot and {@code TEXT} label. A chosen row
 * in a focused group merges {@code FOCUS} into what it paints over that fill, the rule {@link Tree}
 * and {@link Menu} rows follow, and an unfocused group still shows which option is chosen.</p>
 *
 * <p>A radio group is one object per placed group, held across frames the way a component is,
 * because the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#radioGroup(RadioGroup, RadioGroup.Model, java.util.function.Function)
 */
public final class RadioGroup
        implements Component<RadioGroup.Model, RadioGroup.Msg, RadioGroup.Output>,
                Interactive<RadioGroup.Model, RadioGroup.Msg> {

    /**
     * The dot of the chosen option.
     */
    public static final String CHOSEN = "\u25CF";

    /**
     * The dot of an option that is not chosen, as wide as {@link #CHOSEN}.
     */
    public static final String UNCHOSEN = "\u25CB";

    private final Config config;

    /**
     * The row's painter: the dot and the label in the theme's styles.
     */
    private final ChoiceRow<Msg> row = new ChoiceRow<>();

    /**
     * A radio group over its options.
     *
     * @param options the options, top to bottom
     */
    public RadioGroup(List<Option> options) {
        this(Config.of(options));
    }

    /**
     * A radio group over its configuration.
     *
     * @param config the options; required
     */
    public RadioGroup(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One painted row.
     *
     * @param option the option the row paints
     * @param selected whether this option is the chosen one
     * @param focused whether the group owns focus
     */
    public record Row(Option option, boolean selected, boolean focused) {

        /**
         * The id of the option the row paints.
         *
         * @return the id of the option the row paints
         */
        public String id() {
            return option.id();
        }
    }

    /**
     * Immutable radio group state.
     *
     * <p>The canonical constructor resolves the chosen id, so a model the application builds obeys
     * the same rules the reductions do: the id names an option, or it is empty because there are no
     * options.</p>
     *
     * @param options the options, top to bottom
     * @param selected the id of the chosen option, or empty when there are none
     * @param focused whether the group owns focus
     */
    public record Model(List<Option> options, String selected, boolean focused) {

        /**
         * Normalizes the options and resolves the chosen id.
         */
        public Model {
            options = Option.copyAll(options);
            selected = resolve(options, selected);
        }

        /**
         * A focused group with its first option chosen.
         *
         * @param options the options
         * @return the model
         */
        public static Model of(List<Option> options) {
            return new Model(options, "", true);
        }

        /**
         * This group over other options, with the chosen id resolved again.
         *
         * @param value the options; null is empty
         * @return the new model
         */
        public Model withOptions(List<Option> value) {
            return new Model(value, selected, focused);
        }

        /**
         * This group with another option chosen.
         *
         * @param value the id to choose; an unknown id falls to the first option
         * @return the new model
         */
        public Model withSelected(String value) {
            return new Model(options, value, focused);
        }

        /**
         * The same group, focused or not.
         *
         * @param value whether the group owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(options, selected, value);
        }

        /**
         * The chosen option, or null when there are no options.
         *
         * @return the chosen option, or null when there are no options
         */
        public Option selectedOption() {
            int index = indexOf(options, selected);
            return index < 0 ? null : options.get(index);
        }

        /**
         * The rows this group shows, top to bottom.
         *
         * @return the option rows
         */
        public List<Row> visibleRows() {
            List<Row> rows = new ArrayList<>(options.size());
            for (Option option : options) {
                rows.add(new Row(option, option.id().equals(selected), focused));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * The radio group's messages.
     */
    public sealed interface Msg {

        /**
         * Chooses the previous option.
         */
        record Up() implements Msg {}

        /**
         * Chooses the next option.
         */
        record Down() implements Msg {}

        /**
         * Chooses the first option.
         */
        record Home() implements Msg {}

        /**
         * Chooses the last option.
         */
        record End() implements Msg {}

        /**
         * Chooses what is already chosen, which is how an application learns the default was accepted.
         */
        record Activate() implements Msg {}

        /**
         * Chooses an option by id, which is what a press does.
         *
         * @param id the option to choose; an unknown id chooses nothing
         */
        record Choose(String id) implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * An option was chosen.
         *
         * @param id the option's id
         * @param option the option itself
         */
        record Chosen(String id, Option option) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a radio group is configured.
     *
     * @param options the options, top to bottom
     */
    public record Config(List<Option> options) {

        /**
         * Copies the options.
         */
        public Config {
            options = Option.copyAll(options);
        }

        /**
         * A configuration over its options.
         *
         * @param options the options; null is empty
         * @return the configuration
         */
        public static Config of(List<Option> options) {
            return new Config(options);
        }

        /**
         * This configuration over other options.
         *
         * @param value the options; null is empty
         * @return the new configuration
         */
        public Config withOptions(List<Option> value) {
            return new Config(value);
        }
    }

    /**
     * How this radio group is configured.
     *
     * @return how this radio group is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The radio group's element: its rows, and the group itself answering for them.
     *
     * @param model the state to paint; required
     * @return the element, emitting this group's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation and choosing.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "previous option", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "next option", "down"),
                        KeyAction.<Msg>of("home", new Msg.Home(), "home", "first option", "home"),
                        KeyAction.<Msg>of("end", new Msg.End(), "end", "last option", "end")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of(
                                "choose", new Msg.Activate(), "enter", "choose", "enter", "space", "alt+enter")));
    }

    /**
     * The actions this radio group handles.
     *
     * @return the actions this radio group handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(new Model(config.options(), "", true));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The group's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key its press did, so it never fires the press a second time.
            return;
        }
        Optional<KeyAction<Msg>> action = keys().actionFor(key.key());
        action.ifPresent(value -> emit.accept(value.message()));
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A group with nothing to choose answers nothing, so it takes no turn in the focus walk.
     */
    @Override
    public boolean canFocus(Model model) {
        return !model.options().isEmpty();
    }

    /**
     * A press chooses the row it landed on; a press below the last option chooses nothing.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (local.row() < 0
                || local.row() >= Math.min(bounds.rows(), model.options().size())) {
            return;
        }
        emit.accept(new Msg.Choose(model.options().get(local.row()).id()));
    }

    /**
     * The option rows, with the group as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Element<Msg>> lines = new ArrayList<>(model.options().size());
        for (Row painted : model.visibleRows()) {
            lines.add(new Element.Leaf<ChoiceRow.Row, Msg>(
                    row,
                    new ChoiceRow.Row(
                            StyledText.plain(painted.selected() ? CHOSEN : UNCHOSEN),
                            painted.option().label(),
                            painted.selected(),
                            painted.selected(),
                            painted.focused()),
                    Sizing.fixed(1)));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Up up -> moveTo(state, indexOf(state.options(), state.selected()) - 1);
            case Msg.Down down -> moveTo(state, indexOf(state.options(), state.selected()) + 1);
            case Msg.Home home -> moveTo(state, 0);
            case Msg.End end -> moveTo(state, state.options().size() - 1);
            case Msg.Activate activate -> choose(state, state.selected());
            case Msg.Choose choose -> choose(state, choose.id());
        };
    }

    /**
     * The option at {@code target} chosen.
     *
     * <p>An arrow that cannot move - the first option's {@code up} - reports nothing, so the
     * application is never handed a choice the user did not make.</p>
     */
    private static Result moveTo(Model state, int target) {
        if (state.options().isEmpty()) {
            return new Result(state, null);
        }
        int index = Math.max(0, Math.min(state.options().size() - 1, target));
        if (index == indexOf(state.options(), state.selected())) {
            return new Result(state, null);
        }
        return choose(state, state.options().get(index).id());
    }

    /**
     * A choice by id: an option that is present is chosen and reported, anything else is not.
     */
    private static Result choose(Model state, String id) {
        int index = indexOf(state.options(), id);
        if (index < 0) {
            return new Result(state, null);
        }
        Option option = state.options().get(index);
        return new Result(
                new Model(state.options(), option.id(), state.focused()), new Output.Chosen(option.id(), option));
    }

    /**
     * The row of an option, or -1.
     */
    private static int indexOf(List<Option> options, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The id a list of options admits: the named one when it exists, the first one otherwise.
     */
    private static String resolve(List<Option> options, String id) {
        int index = indexOf(options, id);
        return index >= 0
                ? options.get(index).id()
                : options.isEmpty() ? "" : options.get(0).id();
    }
}
