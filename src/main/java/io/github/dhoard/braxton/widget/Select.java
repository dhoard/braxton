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
 * A single choice field: what is chosen, and the list of choices it opens onto.
 *
 * <p>A select is the closed row of a form - the chosen option's label and a glyph that says the row
 * opens - plus the options below it while it is open. It owns two facts, which option is chosen and
 * whether the list is open, and it answers the two questions a choice field is asked: what to paint,
 * and what the user chose. The options are the application's data, an option's identity is stable,
 * and a choice is reported as an {@link Output.Chosen} carrying the option rather than its index, so
 * an application that reorders its options cannot be handed the wrong one.</p>
 *
 * <h2>Open and closed</h2>
 *
 * <p>The closed state is one row: the chosen option's label, and a {@link #CLOSED_GLYPH closed glyph} pushed to the right edge.
 * While the list is open the chosen row becomes the first row of a popup of options, each marked
 * with the {@link #CHOSEN chosen marker} or a blank one, and the glyph turns over to the
 * {@link #OPEN_GLYPH open glyph}. The popup is rows of the widget itself, so a select that opens is taller:
 * placing it in a {@link Elements#stack stack} layer is what makes it overlay the content below,
 * and a parent that gives it one row simply clips the popup to the field.</p>
 *
 * <p>The model keeps the options, the chosen id, whether the list is open, the highlighted id,
 * focus, and the popup's window: the first option it paints and how many options it may paint. A
 * window always shows the highlight ({@link RowWindow}), so a list longer than the popup scrolls
 * with the highlight instead of losing it, and a popup with more rows than options paints only the
 * options it has.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>Closed, {@code up} and {@code down} choose the previous or the next option and {@code home} and
 * {@code end} choose the first and the last, because a select is a value the user changes in place;
 * {@code enter} and {@code space} open the list on the chosen option. Open, the four navigation keys
 * move the highlight without choosing, {@code enter} and {@code space} choose the highlighted option
 * and close the list, and {@code esc} closes it again. {@code esc} is the application's while the
 * list is closed: a select with no popup showing has nothing to dismiss, so the gesture falls through
 * rather than being swallowed. The gestures are a {@link KeyMap} - {@link #defaultKeys()} defines
 * them and {@link #keys()} is what the widget routes, so {@link Help} describes what actually
 * fires.</p>
 *
 * <p>A pointer press on the closed row opens the list, a second press on it closes it again, and a
 * press on an option chooses that option and closes the list. A press below the last painted option
 * chooses nothing.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>The value is the theme's {@code TEXT}, the glyph its {@code ACCENT}, an option's label
 * {@code TEXT}, the marker of the chosen option {@code ACCENT} and the marker of every other option
 * {@code MUTED}. A highlighted option is filled with {@code SELECTED}, and a highlighted option in a
 * focused select merges {@code FOCUS} into what it paints over that fill, the rule {@link Tree} and
 * {@link Menu} rows follow; a focused field merges {@code FOCUS} into its value and glyph. A value
 * wider than the row is clipped rather than wrapped, and the glyph is dropped whole when the row is
 * too narrow for it, because a value is worth more than half an affordance.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A select is one object per placed select, held across frames the way a component is, because
 * the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#select(Select, Select.Model, java.util.function.Function)
 */
public final class Select
        implements Component<Select.Model, Select.Msg, Select.Output>, Interactive<Select.Model, Select.Msg> {

    /**
     * The popup rows a select paints when it is configured without a height of its own.
     */
    public static final int DEFAULT_ROWS = 8;

    /**
     * The glyph that says a closed select opens.
     */
    public static final String CLOSED_GLYPH = "\u25BE";

    /**
     * The glyph that says an open select closes.
     */
    public static final String OPEN_GLYPH = "\u25B4";

    /**
     * The marker of the chosen option.
     */
    public static final String CHOSEN = "\u2713";

    /**
     * The marker of an option that is not chosen: one blank column, so the labels line up.
     */
    public static final String UNCHOSEN = " ";

    private final Config config;

    /**
     * The closed row's painter: the value and the glyph in the theme's styles.
     */
    private final FieldRow<Msg> fieldRow = new FieldRow<>();

    /**
     * The popup rows' painter: a marker and a label in the theme's styles.
     */
    private final ChoiceRow<Msg> choiceRow = new ChoiceRow<>();

    /**
     * A select over its options, with the default popup height.
     *
     * @param options the options, top to bottom
     */
    public Select(List<Option> options) {
        this(Config.of(options));
    }

    /**
     * A select over its configuration.
     *
     * @param config the options and the popup height; required
     */
    public Select(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One painted popup row.
     *
     * @param option the option the row paints
     * @param chosen whether this option is the chosen one
     * @param highlighted whether this row is the highlight
     * @param focused whether the select owns focus
     */
    public record Row(Option option, boolean chosen, boolean highlighted, boolean focused) {

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
     * Immutable select state.
     *
     * <p>The canonical constructor keeps the ids legal, so a model the application builds
     * obeys the same rules the reductions do: the chosen id names an option or is empty, the
     * highlight names an option while the list is open, and the window shows it.</p>
     *
     * @param options the options, top to bottom
     * @param selected the id of the chosen option, or empty when there are no options
     * @param open whether the list is open
     * @param highlighted the id of the highlighted option while the list is open; resolved to the
     *     chosen option or to the first one
     * @param focused whether the select owns focus
     * @param offset the first option the popup paints; clamped into the options
     * @param rows the options the popup may paint; never negative
     */
    public record Model(
            List<Option> options,
            String selected,
            boolean open,
            String highlighted,
            boolean focused,
            int offset,
            int rows) {

        /**
         * Normalizes the ids, the highlight and the window.
         */
        public Model {
            options = Option.copyAll(options);
            rows = Math.max(0, rows);
            selected = resolve(options, selected);
            String anchored = highlighted == null || highlighted.isBlank() ? selected : highlighted;
            // A highlight that names nothing falls back to the chosen option before the first one:
            // opening a list is not a reason to lose the option the reader was looking at.
            highlighted = open ? resolve(options, indexOf(options, anchored) >= 0 ? anchored : selected) : selected;
            offset = RowWindow.admit(indexOf(options, highlighted), options.size(), Math.max(0, offset), rows);
        }

        /**
         * A closed select showing its first option, focused.
         *
         * @param options the options
         * @return the model
         */
        public static Model of(List<Option> options) {
            return new Model(options, "", false, "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This select over other options, with the chosen and highlighted ids resolved again.
         *
         * @param value the options; null is empty
         * @return the new model
         */
        public Model withOptions(List<Option> value) {
            return new Model(value, selected, open, highlighted, focused, offset, rows);
        }

        /**
         * This select with another option chosen.
         *
         * @param value the id to choose; an unknown id falls to the first option
         * @return the new model
         */
        public Model withSelected(String value) {
            return new Model(options, value, open, highlighted, focused, offset, rows);
        }

        /**
         * This select open or closed; opening anchors the highlight on the chosen option.
         *
         * @param value whether the list is open
         * @return the new model
         */
        public Model withOpen(boolean value) {
            return value
                    ? new Model(options, selected, true, selected, focused, offset, rows)
                    : new Model(options, selected, false, highlighted, focused, offset, rows);
        }

        /**
         * This select with another highlight, resolved as the constructor resolves it.
         *
         * @param value the id to highlight; null or an unknown id falls to the chosen option
         * @return the new model
         */
        public Model withHighlighted(String value) {
            return new Model(options, selected, open, value, focused, offset, rows);
        }

        /**
         * The same select, focused or not.
         *
         * @param value whether the select owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(options, selected, open, highlighted, value, offset, rows);
        }

        /**
         * This select with a popup of a different height.
         *
         * @param value the options the popup may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(options, selected, open, highlighted, focused, offset, value);
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
         * The popup rows this select shows, top to bottom.
         *
         * @return the option rows
         */
        public List<Row> visibleRows() {
            List<Row> rows = new ArrayList<>(options.size());
            for (Option option : options) {
                rows.add(new Row(
                        option,
                        option.id().equals(selected),
                        open && option.id().equals(highlighted),
                        focused));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * The select's messages.
     */
    public sealed interface Msg {

        /**
         * Chooses the previous option while closed, and highlights it while open.
         */
        record Up() implements Msg {}

        /**
         * Chooses the next option while closed, and highlights it while open.
         */
        record Down() implements Msg {}

        /**
         * Chooses the first option while closed, and highlights it while open.
         */
        record Home() implements Msg {}

        /**
         * Chooses the last option while closed, and highlights it while open.
         */
        record End() implements Msg {}

        /**
         * Opens the list while closed, and chooses what is highlighted while open.
         */
        record Activate() implements Msg {}

        /**
         * Opens the list, or closes it again, which is what a press on the field does.
         */
        record ToggleOpen() implements Msg {}

        /**
         * Chooses an option by id, which is what a press on a popup row does.
         *
         * @param id the option to choose; an unknown id chooses nothing
         */
        record Choose(String id) implements Msg {}

        /**
         * Closes the list again, which only an open list answers.
         */
        record Cancel() implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * An option is now the chosen one.
         *
         * @param id the option's id
         * @param option the option itself
         */
        record Chosen(String id, Option option) implements Output {}

        /**
         * The user asked to dismiss the open list.
         */
        record Canceled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a select is configured.
     *
     * @param options the options, top to bottom
     * @param rows the options the popup paints when no message or model set a height; never negative
     */
    public record Config(List<Option> options, int rows) {

        /**
         * Copies the options and rejects a negative popup height.
         */
        public Config {
            options = Option.copyAll(options);
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
        }

        /**
         * A configuration over its options, with the default popup height.
         *
         * @param options the options
         * @return the configuration
         */
        public static Config of(List<Option> options) {
            return new Config(options, DEFAULT_ROWS);
        }

        /**
         * This configuration over other options.
         *
         * @param value the options; null is empty
         * @return the new configuration
         */
        public Config withOptions(List<Option> value) {
            return new Config(value, rows);
        }

        /**
         * This configuration whose popup paints a different height.
         *
         * @param value the options the popup may paint; never negative
         * @return the new configuration
         */
        public Config withRows(int value) {
            return new Config(options, value);
        }
    }

    /**
     * How this select is configured.
     *
     * @return how this select is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The select's element: its field row, the popup while it is open, and the select itself
     * answering for both.
     *
     * @param model the state to paint; required
     * @return the element, emitting this select's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation, opening and choosing, and dismissing.
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
                                "choose", new Msg.Activate(), "enter", "open or choose", "enter", "space", "alt+enter"),
                        KeyAction.<Msg>of("dismiss", new Msg.Cancel(), "esc", "close the list", "esc", "escape")));
    }

    /**
     * The actions this select handles.
     *
     * @return the actions this select handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(new Model(config.options(), "", false, "", true, 0, config.rows()));
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
     * The select's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key its press did, so it never fires the press a second time.
            return;
        }
        Optional<KeyAction<Msg>> action = keys().actionFor(key.key());
        if (action.isEmpty()) {
            return;
        }
        // A closed select has nothing to dismiss, so esc stays the application's gesture rather than
        // being swallowed by a widget with no popup to close.
        if (action.get().message() instanceof Msg.Cancel && !model.open()) {
            return;
        }
        emit.accept(action.get().message());
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A select with nothing to choose answers nothing, so it takes no turn in the focus walk.
     */
    @Override
    public boolean canFocus(Model model) {
        return !model.options().isEmpty();
    }

    /**
     * A press on the field opens or closes the list; a press on an option chooses it.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (local.row() < 0 || local.row() >= bounds.rows()) {
            return;
        }
        if (!model.open() || local.row() == 0) {
            emit.accept(new Msg.ToggleOpen());
            return;
        }
        int painted = Math.max(0, Math.min(model.rows(), model.options().size() - model.offset()));
        if (local.row() - 1 >= painted) {
            return;
        }
        emit.accept(new Msg.Choose(
                model.options().get(model.offset() + local.row() - 1).id()));
    }

    /**
     * The field row and the popup rows, with the select as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        Option chosen = model.selectedOption();
        List<Element<Msg>> lines = new ArrayList<>();
        lines.add(new Element.Leaf<FieldRow.Row, Msg>(
                fieldRow,
                new FieldRow.Row(
                        chosen == null ? StyledText.empty() : chosen.label(),
                        model.open() ? OPEN_GLYPH : CLOSED_GLYPH,
                        model.focused()),
                Sizing.fixed(1)));
        if (model.open()) {
            List<Row> rows = model.visibleRows();
            int first = Math.max(0, model.offset());
            int last = Math.min(rows.size(), first + model.rows());
            for (int index = first; index < last; index++) {
                Row row = rows.get(index);
                lines.add(new Element.Leaf<ChoiceRow.Row, Msg>(
                        choiceRow,
                        new ChoiceRow.Row(
                                StyledText.plain(row.chosen() ? CHOSEN : UNCHOSEN),
                                row.option().label(),
                                row.chosen(),
                                row.highlighted(),
                                row.focused()),
                        Sizing.fixed(1)));
            }
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Up up -> step(state, -1);
            case Msg.Down down -> step(state, 1);
            case Msg.Home home -> extreme(state, true);
            case Msg.End end -> extreme(state, false);
            case Msg.Activate activate -> state.open() ? choose(state, state.highlighted()) : open(state);
            case Msg.ToggleOpen toggle -> state.open() ? new Result(state.withOpen(false), null) : open(state);
            case Msg.Choose choose -> choose(state, choose.id());
            case Msg.Cancel cancel ->
                state.open() ? new Result(state.withOpen(false), new Output.Canceled()) : new Result(state, null);
        };
    }

    /**
     * The list opened on the chosen option.
     */
    private static Result open(Model state) {
        Model opened = state.withOpen(true);
        return new Result(opened, null);
    }

    /**
     * The chosen option or the highlight moved by a number of rows, inside the options.
     */
    private static Result step(Model state, int delta) {
        int current = indexOf(state.options(), state.open() ? state.highlighted() : state.selected());
        return moveTo(state, current < 0 ? 0 : current + delta);
    }

    /**
     * The first or the last option chosen or highlighted.
     */
    private static Result extreme(Model state, boolean first) {
        return moveTo(state, first ? 0 : state.options().size() - 1);
    }

    /**
     * The option at {@code target} chosen while the list is closed and highlighted while it is open.
     *
     * <p>A closed select reports the change: the chosen value is what the application stores, so a
     * gesture that changed it without saying so would leave the application behind. An open one does
     * not - the highlight is not the choice yet - and a gesture that could not move reports nothing
     * in either state, so an application is never handed a choice the user did not make.</p>
     */
    private static Result moveTo(Model state, int target) {
        List<Option> options = state.options();
        if (options.isEmpty()) {
            return new Result(state, null);
        }
        int index = Math.max(0, Math.min(options.size() - 1, target));
        if (index == indexOf(options, state.open() ? state.highlighted() : state.selected())) {
            return new Result(state, null);
        }
        Option option = options.get(index);
        if (state.open()) {
            return new Result(state.withHighlighted(option.id()), null);
        }
        return new Result(
                new Model(options, option.id(), false, option.id(), state.focused(), state.offset(), state.rows()),
                new Output.Chosen(option.id(), option));
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
                new Model(
                        state.options(),
                        option.id(),
                        false,
                        option.id(),
                        state.focused(),
                        state.offset(),
                        state.rows()),
                new Output.Chosen(option.id(), option));
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
