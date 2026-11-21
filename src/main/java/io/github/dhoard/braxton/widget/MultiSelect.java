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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A multiple choice field: the options the user marked, and the list it marks them in.
 *
 * <p>A multi-select is {@link Select}'s sibling for a choice that is not exclusive. It owns two
 * facts - which options are marked and whether the list is open - and reports the marks as an
 * {@link Output.ChosenMany} carrying the options themselves, in the application's order, so an
 * application never has to map ids back into its own data. The options are the application's, an
 * option's identity is stable, and marking is by identity rather than by row.</p>
 *
 * <h2>Open and closed</h2>
 *
 * <p>The closed state is one row: the labels of the marked options, separated by commas, and the
 * {@link #CLOSED_GLYPH closed glyph} at the right edge. Nothing is marked yet is an empty value
 * rather than a placeholder, because the widget does not know what an application would want said
 * there. While the list is open the field row becomes the first row of a popup whose options are
 * each marked with a box, {@value #MARKED} or {@link #UNMARKED}, and the glyph turns over to
 * {@link #OPEN_GLYPH}. The popup is rows of the widget itself, so an open multi-select is taller:
 * placing it in a {@link Elements#stack stack} layer is what makes it overlay the content below, and
 * a parent that gives it one row clips the popup to the field.</p>
 *
 * <p>The model keeps the options, the marked ids, whether the list is open, the highlighted id,
 * focus, and the popup's window, which always shows the highlight ({@link RowWindow}). An option
 * that is not in the options list cannot stay marked, so replacing the options drops the marks that
 * no longer name one.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>Open, {@code up} and {@code down} move the highlight, {@code home} and {@code end} go to the
 * first and the last option, {@code space} and {@code enter} mark or unmark the highlighted option,
 * and {@code esc} closes the list. Closed, {@code enter} and {@code space} open the list and every
 * other gesture is the application's: a closed multi-select paints no popup, so an arrow that moved a
 * highlight nobody can see, or an {@code esc} that dismissed nothing, would only be a key taken away
 * from the application. The gestures are a {@link KeyMap} - {@link #defaultKeys()} defines them and
 * {@link #keys()} is what the widget routes, so {@link Help} describes what actually fires.</p>
 *
 * <p>A pointer press on the closed row opens the list, a second press on it closes the list again,
 * and a press on an option marks or unmarks that option and leaves the list open, because marking
 * several options is the point. A press below the last painted option marks nothing.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>The value is the theme's {@code TEXT} and the glyph its {@code ACCENT}; a box is
 * {@code ACCENT} when it is marked and {@code MUTED} when it is not, and an option's label is
 * {@code TEXT}. A highlighted option is filled with {@code SELECTED}, and a highlighted option in a
 * focused multi-select merges {@code FOCUS} into what it paints over that fill, the rule
 * {@link Tree} and {@link Menu} rows follow; a focused field merges {@code FOCUS} into its value and
 * glyph. A value wider than the row is clipped rather than wrapped, and the glyph is dropped whole
 * when the row is too narrow for it.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A multi-select is one object per placed field, held across frames the way a component is,
 * because the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#multiSelect(MultiSelect, MultiSelect.Model, java.util.function.Function)
 */
public final class MultiSelect
        implements Component<MultiSelect.Model, MultiSelect.Msg, MultiSelect.Output>,
                Interactive<MultiSelect.Model, MultiSelect.Msg> {

    /**
     * The popup rows a multi-select paints when it is configured without a height of its own.
     */
    public static final int DEFAULT_ROWS = 8;

    /**
     * The glyph that says a closed multi-select opens.
     */
    public static final String CLOSED_GLYPH = "\u25BE";

    /**
     * The glyph that says an open multi-select closes.
     */
    public static final String OPEN_GLYPH = "\u25B4";

    /**
     * The box of a marked option.
     */
    public static final String MARKED = "[\u2713]";

    /**
     * The box of an option that is not marked, as wide as {@link #MARKED}.
     */
    public static final String UNMARKED = "[ ]";

    /**
     * The columns between two marked labels in the closed field.
     */
    private static final String SEPARATOR = ", ";

    private final Config config;

    /**
     * The closed row's painter: the value and the glyph in the theme's styles.
     */
    private final FieldRow<Msg> fieldRow = new FieldRow<>();

    /**
     * The popup rows' painter: a box and a label in the theme's styles.
     */
    private final ChoiceRow<Msg> choiceRow = new ChoiceRow<>();

    /**
     * A multi-select over its options, with the default popup height.
     *
     * @param options the options, top to bottom
     */
    public MultiSelect(List<Option> options) {
        this(Config.of(options));
    }

    /**
     * A multi-select over its configuration.
     *
     * @param config the options and the popup height; required
     */
    public MultiSelect(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One painted popup row.
     *
     * @param option the option the row paints
     * @param marked whether this option is marked
     * @param highlighted whether this row is the highlight
     * @param focused whether the multi-select owns focus
     */
    public record Row(Option option, boolean marked, boolean highlighted, boolean focused) {

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
     * Immutable multi-select state.
     *
     * <p>The canonical constructor keeps the ids legal, so a model the application builds obeys the
     * same rules the reductions do: the marks name options and are held in the application's order,
     * the highlight names an option while the list is open, and the window shows it.</p>
     *
     * @param options the options, top to bottom
     * @param chosen the ids of the marked options; ids no option names are dropped
     * @param open whether the list is open
     * @param highlighted the id of the highlighted option while the list is open; resolved to the
     *     first marked option, or to the first one
     * @param focused whether the multi-select owns focus
     * @param offset the first option the popup paints; clamped into the options
     * @param rows the options the popup may paint; never negative
     */
    public record Model(
            List<Option> options,
            List<String> chosen,
            boolean open,
            String highlighted,
            boolean focused,
            int offset,
            int rows) {

        /**
         * Normalizes the marks, the highlight and the window.
         */
        public Model {
            options = Option.copyAll(options);
            chosen = marks(options, chosen);
            rows = Math.max(0, rows);
            String first = chosen.isEmpty() ? "" : chosen.get(0);
            String anchored = highlighted == null || highlighted.isBlank() ? first : highlighted;
            // A highlight that names nothing falls back to the first marked option before the first
            // one: opening a list is not a reason to lose the option the reader was looking at.
            highlighted = open ? resolve(options, indexOf(options, anchored) >= 0 ? anchored : first) : anchored;
            offset = RowWindow.admit(indexOf(options, highlighted), options.size(), Math.max(0, offset), rows);
        }

        /**
         * A closed multi-select with nothing marked, focused.
         *
         * @param options the options
         * @return the model
         */
        public static Model of(List<Option> options) {
            return new Model(options, List.of(), false, "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This field over other options, keeping the marks that still name one.
         *
         * @param value the options; null is empty
         * @return the new model
         */
        public Model withOptions(List<Option> value) {
            return new Model(value, chosen, open, highlighted, focused, offset, rows);
        }

        /**
         * This field with other options marked.
         *
         * @param value the ids to mark; ids no option names are dropped
         * @return the new model
         */
        public Model withChosen(List<String> value) {
            return new Model(options, value, open, highlighted, focused, offset, rows);
        }

        /**
         * This field open or closed; opening anchors the highlight on the first marked option.
         *
         * @param value whether the list is open
         * @return the new model
         */
        public Model withOpen(boolean value) {
            return value
                    ? new Model(options, chosen, true, "", focused, offset, rows)
                    : new Model(options, chosen, false, highlighted, focused, offset, rows);
        }

        /**
         * This field with another highlight, resolved as the constructor resolves it.
         *
         * @param value the id to highlight; null or an unknown id falls to the first marked option
         * @return the new model
         */
        public Model withHighlighted(String value) {
            return new Model(options, chosen, open, value, focused, offset, rows);
        }

        /**
         * The same field, focused or not.
         *
         * @param value whether the multi-select owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(options, chosen, open, highlighted, value, offset, rows);
        }

        /**
         * This field with a popup of a different height.
         *
         * @param value the options the popup may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(options, chosen, open, highlighted, focused, offset, value);
        }

        /**
         * The marked options, in the application's order.
         *
         * @return the marked options
         */
        public List<Option> chosenOptions() {
            List<Option> marked = new ArrayList<>(chosen.size());
            for (Option option : options) {
                if (chosen.contains(option.id())) {
                    marked.add(option);
                }
            }
            return List.copyOf(marked);
        }

        /**
         * The value the closed row paints: the marked labels, separated by commas.
         *
         * @return the value the closed row paints: the marked labels, separated by commas
         */
        public StyledText summary() {
            List<Option> marked = chosenOptions();
            if (marked.isEmpty()) {
                return StyledText.empty();
            }
            // One join rather than one append per option: a field with many marks is still one value
            // built in one pass, and the labels keep their own styles.
            List<StyledText> parts = new ArrayList<>(marked.size() * 2);
            for (int index = 0; index < marked.size(); index++) {
                if (index > 0) {
                    parts.add(StyledText.plain(SEPARATOR));
                }
                parts.add(marked.get(index).label());
            }
            return StyledText.concat(parts.toArray(new StyledText[0]));
        }

        /**
         * The popup rows this field shows, top to bottom.
         *
         * @return the option rows
         */
        public List<Row> visibleRows() {
            List<Row> rows = new ArrayList<>(options.size());
            for (Option option : options) {
                rows.add(new Row(
                        option,
                        chosen.contains(option.id()),
                        open && option.id().equals(highlighted),
                        focused));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * The multi-select's messages.
     */
    public sealed interface Msg {

        /**
         * Highlights the previous option.
         */
        record Up() implements Msg {}

        /**
         * Highlights the next option.
         */
        record Down() implements Msg {}

        /**
         * Highlights the first option.
         */
        record Home() implements Msg {}

        /**
         * Highlights the last option.
         */
        record End() implements Msg {}

        /**
         * Opens the list while closed, and marks or unmarks what is highlighted while open.
         */
        record Toggle() implements Msg {}

        /**
         * Opens the list, or closes it again, which is what a press on the field does.
         */
        record ToggleOpen() implements Msg {}

        /**
         * Marks or unmarks an option by id, which is what a press on a popup row does.
         *
         * @param id the option to mark; an unknown id marks nothing
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
         * The set of marked options is now this one.
         *
         * @param chosen the marked options in the application's order; never empty for a mark that
         *     was added, and empty when the last mark was removed
         */
        record ChosenMany(List<Option> chosen) implements Output {

            /**
             * Copies the marked options.
             */
            public ChosenMany {
                chosen = chosen == null ? List.of() : List.copyOf(chosen);
            }
        }

        /**
         * The user asked to dismiss the open list.
         */
        record Canceled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a multi-select is configured.
     *
     * @param options the options, top to bottom
     * @param rows the options the popup paints when no model set a height; never negative
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
     * How this multi-select is configured.
     *
     * @return how this multi-select is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The multi-select's element: its field row, the popup while it is open, and the multi-select
     * itself answering for both.
     *
     * @param model the state to paint; required
     * @return the element, emitting this multi-select's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation, marking, opening and dismissing.
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
                                "mark",
                                new Msg.Toggle(),
                                "space",
                                "open or mark an option",
                                "space",
                                "enter",
                                "alt+enter"),
                        KeyAction.<Msg>of("dismiss", new Msg.Cancel(), "esc", "close the list", "esc", "escape")));
    }

    /**
     * The actions this multi-select handles.
     *
     * @return the actions this multi-select handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(new Model(config.options(), List.of(), false, "", true, 0, config.rows()));
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
     * The multi-select's keys, from the same map {@link Help} renders.
     *
     * <p>A closed multi-select answers only the gesture that opens it: the popup is not painted, so
     * moving its highlight or dismissing it would be a key taken from the application for nothing.</p>
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
        Msg message = action.get().message();
        boolean opensOnly = message instanceof Msg.Up
                || message instanceof Msg.Down
                || message instanceof Msg.Home
                || message instanceof Msg.End
                || message instanceof Msg.Cancel;
        if (!model.open() && opensOnly) {
            return;
        }
        emit.accept(message);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A field with nothing to choose answers nothing, so it takes no turn in the focus walk.
     */
    @Override
    public boolean canFocus(Model model) {
        return !model.options().isEmpty();
    }

    /**
     * A press on the field opens or closes the list; a press on an option marks or unmarks it.
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
     * The field row and the popup rows, with the multi-select as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Element<Msg>> lines = new ArrayList<>();
        lines.add(new Element.Leaf<FieldRow.Row, Msg>(
                fieldRow,
                new FieldRow.Row(model.summary(), model.open() ? OPEN_GLYPH : CLOSED_GLYPH, model.focused()),
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
                                StyledText.plain(row.marked() ? MARKED : UNMARKED),
                                row.option().label(),
                                row.marked(),
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
            case Msg.Toggle toggle -> state.open() ? mark(state, state.highlighted()) : open(state);
            case Msg.ToggleOpen toggle -> state.open() ? new Result(state.withOpen(false), null) : open(state);
            case Msg.Choose choose -> mark(state, choose.id());
            case Msg.Cancel cancel ->
                state.open() ? new Result(state.withOpen(false), new Output.Canceled()) : new Result(state, null);
        };
    }

    /**
     * The list opened on the first marked option.
     */
    private static Result open(Model state) {
        return new Result(state.withOpen(true), null);
    }

    /**
     * The highlight moved a number of rows, inside the options.
     */
    private static Result step(Model state, int delta) {
        if (!state.open() || state.options().isEmpty()) {
            return new Result(state, null);
        }
        int current = indexOf(state.options(), state.highlighted());
        return moveTo(state, current < 0 ? 0 : current + delta);
    }

    /**
     * The first or the last option highlighted.
     */
    private static Result extreme(Model state, boolean first) {
        if (!state.open() || state.options().isEmpty()) {
            return new Result(state, null);
        }
        return moveTo(state, first ? 0 : state.options().size() - 1);
    }

    /**
     * The option at {@code target} highlighted.
     */
    private static Result moveTo(Model state, int target) {
        int index = Math.max(0, Math.min(state.options().size() - 1, target));
        return new Result(state.withHighlighted(state.options().get(index).id()), null);
    }

    /**
     * An option's mark flipped, reported as the whole set.
     *
     * <p>Reporting the set rather than the one id keeps the report honest: an application that stores
     * marks and then asks what is marked has one answer, not an accumulation of deltas it has to
     * replay.</p>
     */
    private static Result mark(Model state, String id) {
        int index = indexOf(state.options(), id);
        if (index < 0) {
            return new Result(state, null);
        }
        Option option = state.options().get(index);
        Set<String> marks = new LinkedHashSet<>(state.chosen());
        if (!marks.remove(option.id())) {
            marks.add(option.id());
        }
        Model next = new Model(
                state.options(),
                List.copyOf(marks),
                state.open(),
                option.id(),
                state.focused(),
                state.offset(),
                state.rows());
        return new Result(next, new Output.ChosenMany(next.chosenOptions()));
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

    /**
     * The marks a list of options admits: the ids it names, in its own order.
     */
    private static List<String> marks(List<Option> options, List<String> chosen) {
        if (chosen == null || chosen.isEmpty() || options.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>(chosen.size());
        for (Option option : options) {
            if (chosen.contains(option.id()) && !kept.contains(option.id())) {
                kept.add(option.id());
            }
        }
        return List.copyOf(kept);
    }
}
