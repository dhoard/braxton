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
import io.github.dhoard.braxton.internal.text.CompletionEngine;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A filterable list of commands the user narrows by typing and chooses by pressing enter.
 *
 * <p>A palette is two rows of state and one question. It owns the query and the highlighted command;
 * the commands are the application's data, each with an identity, a title and a description. Every
 * keystroke narrows the list through the same matcher {@link Editor} and {@link TextInput} complete
 * with ({@link CompletionEngine}), so a query behaves in a palette exactly as it behaves in a field,
 * and the application's order is what an empty query shows.</p>
 *
 * <h2>Rows</h2>
 *
 * <p>The first row is the query field: a prompt, the query, and - through {@link io.github.dhoard.braxton.ui.Widget#cursor} - the
 * caret the runtime presents while the palette owns focus. The rows below it are the commands that
 * match, the highlighted one filled with the theme's selection. A command's title is painted with the
 * run the query matched in the theme's accent, so a reader can see why a row is in the list; a match
 * that falls only in a description keeps the row without a marked run, because the title is the row
 * and marking nothing is honest.</p>
 *
 * <p>{@link Model#rows()} is the whole widget: the query row plus the commands below it. A window
 * always shows the highlight ({@link RowWindow}), so a long list scrolls with the highlight instead
 * of losing it, and a palette with more rows than matches paints only the matches it has.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>Typing narrows the list; {@code backspace} deletes the last character, {@code ctrl+u} clears the
 * query, {@code up} and {@code down} move the highlight, {@code pageup}/{@code pagedown} and
 * {@code home}/{@code end} move further, {@code enter} chooses the highlighted command and {@code esc}
 * asks the application to dismiss the palette. A change to the query resets the highlight to the first
 * command that matches it, which is what makes typing a narrowing gesture rather than a search that
 * leaves the highlight behind on a row that is no longer in the list. The gestures are a
 * {@link KeyMap}, so {@link Help} describes what actually fires.</p>
 *
 * <p>A pointer press on a command chooses it; a press on the query row or below the last match
 * chooses nothing.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>The prompt is the theme's {@code ACCENT}, the query {@code TEXT}, a title {@code TEXT} with the
 * matched run in {@code ACCENT}, a description {@code MUTED}, a command that cannot be chosen
 * {@code DISABLED}, the highlighted row {@code SELECTED}, and a focused highlight merges
 * {@code FOCUS} into what it paints over that fill - the rule {@link Tree} and {@link Menu} rows
 * follow. A description follows its title after two separating columns and is clipped by the row,
 * so a narrow palette loses the tail of a description rather than the whole of it, and a title wider
 * than the row is clipped rather than wrapped.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A palette is one object per placed palette, held across frames the way a component is, because
 * the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#commandPalette(CommandPalette, CommandPalette.Model, java.util.function.Function)
 */
public final class CommandPalette
        implements Component<CommandPalette.Model, CommandPalette.Msg, CommandPalette.Output>,
                Interactive<CommandPalette.Model, CommandPalette.Msg> {

    /**
     * The rows a palette paints when it is initialized without a height of its own.
     */
    public static final int DEFAULT_ROWS = 10;

    /**
     * What the query row opens with, so the field reads as something to type into.
     */
    public static final String PROMPT = "> ";

    /**
     * The columns between a title and its description.
     */
    private static final int GAP = 2;

    private final Config config;

    /**
     * The query row's painter: the prompt, the query and the theme's own styles.
     */
    private final QueryRow<Msg> queryRow = new QueryRow<>();

    /**
     * The result rows' painter: a command, its description and the run the query matched.
     */
    private final ResultRow<Msg> resultRow = new ResultRow<>();

    /**
     * A palette with its default configuration.
     *
     * @param commands the commands the palette can run
     */
    public CommandPalette(List<Command> commands) {
        this(Config.of(commands));
    }

    /**
     * A palette over its configuration.
     *
     * @param config the commands and the window; required
     */
    public CommandPalette(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One command: its identity, what it is called, and what it does in a sentence.
     *
     * @param id the identity, unique across the palette; never blank
     * @param title the title painted on the row; null is empty
     * @param description the description painted after the title; null or empty for none
     * @param enabled whether the command can be chosen
     */
    public record Command(String id, String title, String description, boolean enabled) {

        /**
         * Rejects a command without an id.
         */
        public Command {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("command id is required");
            }
            title = title == null ? "" : title;
            description = description == null ? "" : description;
        }

        /**
         * A command that can be chosen.
         *
         * @param id the identity; never blank
         * @param title the title
         * @return the command
         */
        public static Command of(String id, String title) {
            return new Command(id, title, "", true);
        }

        /**
         * A command that can be chosen, with a description.
         *
         * @param id the identity; never blank
         * @param title the title
         * @param description the description; null or empty for none
         * @return the command
         */
        public static Command of(String id, String title, String description) {
            return new Command(id, title, description, true);
        }

        /**
         * This command with another description.
         *
         * @param value the description; null or empty for none
         * @return the command
         */
        public Command withDescription(String value) {
            return new Command(id, title, value, enabled);
        }

        /**
         * This command able or unable to be chosen.
         *
         * @param value whether the command can be chosen
         * @return the command
         */
        public Command withEnabled(boolean value) {
            return new Command(id, title, description, value);
        }

        /**
         * The text a query is matched against: the title and the description.
         *
         * @return the text a query is matched against: the title and the description
         */
        public String searchText() {
            return description.isEmpty() ? title : title + " " + description;
        }
    }

    /**
     * One painted result row.
     *
     * @param command the command the row paints
     * @param highlighted whether this row is the highlight
     * @param focused whether the palette owns focus
     * @param matchStart where the query matched the title, or -1 when it did not match there
     * @param matchLength the columns the match covers; zero when {@code matchStart} is -1
     */
    public record Row(Command command, boolean highlighted, boolean focused, int matchStart, int matchLength) {

        /**
         * The id of the command the row paints.
         *
         * @return the id of the command the row paints
         */
        public String id() {
            return command.id();
        }
    }

    /**
     * Immutable palette state.
     *
     * <p>The canonical constructor keeps the query one row, the highlight on a command that matches
     * the query, and the window over it, so a model the application builds obeys the same rules the
     * reductions do.</p>
     *
     * @param commands every command, in the application's order
     * @param query the text the user has typed; null is empty
     * @param highlighted the id of the highlighted command, or empty when nothing matches
     * @param focused whether the palette owns focus
     * @param offset the first visible result row; clamped into the matches
     * @param rows the rows the whole palette may paint, the query row included; never negative
     */
    public record Model(
            List<Command> commands, String query, String highlighted, boolean focused, int offset, int rows) {

        /**
         * Normalizes the query, resolves the highlight and admits the window.
         */
        public Model {
            commands = copy(commands);
            query = LineBreaks.singleRow(query == null ? "" : query);
            rows = Math.max(0, rows);
            List<Command> matches = Objects.requireNonNull(CommandPalette.matches(commands, query), "matches");
            highlighted = CommandPalette.resolve(matches, highlighted);
            offset = RowWindow.admit(
                    indexOf(matches, highlighted), matches.size(), Math.max(0, offset), Math.max(0, rows - 1));
        }

        /**
         * A palette showing every command, focused, with an empty query.
         *
         * @param commands the commands
         * @return the model
         */
        public static Model of(List<Command> commands) {
            return new Model(commands, "", "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This palette over other commands.
         *
         * @param value the commands; null is empty
         * @return the new model
         */
        public Model withCommands(List<Command> value) {
            return new Model(value, query, highlighted, focused, offset, rows);
        }

        /**
         * This palette with another query, which resolves the highlight against what it matches.
         *
         * @param value the query; null is empty
         * @return the new model
         */
        public Model withQuery(String value) {
            return new Model(commands, value, "", focused, offset, rows);
        }

        /**
         * This palette with another highlight, resolved as the constructor resolves it.
         *
         * @param value the id to highlight; null or a command that does not match falls to the first
         * @return the new model
         */
        public Model withHighlight(String value) {
            return new Model(commands, query, value, focused, offset, rows);
        }

        /**
         * The same palette, focused or not.
         *
         * @param value whether the palette owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(commands, query, highlighted, value, offset, rows);
        }

        /**
         * This palette with a window of a different height.
         *
         * @param value the rows the whole palette may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(commands, query, highlighted, focused, offset, value);
        }

        /**
         * The commands the query matches, in score order.
         *
         * @return the matching commands
         */
        public List<Command> matches() {
            return CommandPalette.matches(commands, query);
        }

        /**
         * The rows the query row leaves for results.
         *
         * @return the rows the query row leaves for results
         */
        public int resultRows() {
            return Math.max(0, rows - 1);
        }

        /**
         * The result rows this palette shows, top to bottom.
         *
         * @return the visible result rows
         */
        public List<Row> visibleRows() {
            List<Command> matches = matches();
            List<Row> rows = new ArrayList<>(matches.size());
            for (Command command : matches) {
                int start = matchStart(command, query);
                rows.add(new Row(
                        command, command.id().equals(highlighted), focused, start, start < 0 ? 0 : query.length()));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * The palette's messages.
     */
    public sealed interface Msg {

        /**
         * Highlights the previous command that can be chosen.
         */
        record Up() implements Msg {}

        /**
         * Highlights the next command that can be chosen.
         */
        record Down() implements Msg {}

        /**
         * Highlights one window of commands before the highlight.
         */
        record PageUp() implements Msg {}

        /**
         * Highlights one window of commands after the highlight.
         */
        record PageDown() implements Msg {}

        /**
         * Highlights the first command that can be chosen.
         */
        record Home() implements Msg {}

        /**
         * Highlights the last command that can be chosen.
         */
        record End() implements Msg {}

        /**
         * Appends text to the query.
         *
         * @param text the text; control characters are dropped
         */
        record Typed(String text) implements Msg {}

        /**
         * Deletes the last character of the query.
         */
        record Backspace() implements Msg {}

        /**
         * Empties the query.
         */
        record Clear() implements Msg {}

        /**
         * Chooses the highlighted command.
         */
        record Activate() implements Msg {}

        /**
         * Chooses a command by id, which is what a pointer press does.
         *
         * @param id the command to choose; a command the query does not match, one that cannot be
         *     chosen, and an unknown id choose nothing
         */
        record Choose(String id) implements Msg {}

        /**
         * Asks the application to dismiss the palette.
         */
        record Cancel() implements Msg {}

        /**
         * The window is a different height.
         *
         * @param rows the rows the whole palette may paint; never negative
         */
        record RowsChanged(int rows) implements Msg {}

        /**
         * The palette now holds other commands.
         *
         * @param commands the new commands; null is empty
         */
        record Replace(List<Command> commands) implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * A command was chosen.
         *
         * @param id the command's id
         * @param command the command itself
         */
        record Chosen(String id, Command command) implements Output {}

        /**
         * The user asked to dismiss the palette.
         */
        record Canceled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a palette is configured.
     *
     * @param commands every command, in the application's order
     * @param rows the rows the palette paints when no message names a height; never negative
     */
    public record Config(List<Command> commands, int rows) {

        /**
         * Substitutes the defaults and rejects a negative height.
         */
        public Config {
            commands = copy(commands);
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
        }

        /**
         * A configuration over its commands, with the default height.
         *
         * @param commands the commands
         * @return the configuration
         */
        public static Config of(List<Command> commands) {
            return new Config(commands, DEFAULT_ROWS);
        }

        /**
         * This configuration over other commands.
         *
         * @param value the commands
         * @return the new configuration
         */
        public Config withCommands(List<Command> value) {
            return new Config(value, rows);
        }

        /**
         * This configuration whose palette paints a different height.
         *
         * @param value the rows; never negative
         * @return the new configuration
         */
        public Config withRows(int value) {
            return new Config(commands, value);
        }
    }

    /**
     * How this palette is configured.
     *
     * @return how this palette is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The palette's element: the query row and the results, with the palette answering for them.
     *
     * @param model the state to paint; required
     * @return the element, emitting this palette's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation, the query gestures, choosing and dismissing.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "move up", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "move down", "down"),
                        KeyAction.<Msg>of("pageup", new Msg.PageUp(), "page up", "move up a page", "pageup"),
                        KeyAction.<Msg>of("pagedown", new Msg.PageDown(), "page down", "move down a page", "pagedown"),
                        KeyAction.<Msg>of("home", new Msg.Home(), "home", "first command", "home"),
                        KeyAction.<Msg>of("end", new Msg.End(), "end", "last command", "end")),
                KeyMap.group(
                        "Query",
                        KeyAction.<Msg>of(
                                "delete", new Msg.Backspace(), "backspace", "delete a character", "backspace"),
                        KeyAction.<Msg>of("clear", new Msg.Clear(), "ctrl+u", "clear the query", "ctrl+u")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("choose", new Msg.Activate(), "enter", "choose", "enter", "alt+enter"),
                        KeyAction.<Msg>of("dismiss", new Msg.Cancel(), "esc", "dismiss", "esc", "escape")));
    }

    /**
     * The actions this palette handles.
     *
     * @return the actions this palette handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(new Model(config.commands(), "", "", true, 0, config.rows()));
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
     * The palette's keys first, then whatever the query should take from the same gesture.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key and text its press did, so it never inserts anything a
            // second time.
            return;
        }
        Optional<KeyAction<Msg>> action = keys().actionFor(key.key());
        if (action.isPresent()) {
            emit.accept(action.get().message());
            return;
        }
        typed(key.text()).ifPresent(emit);
    }

    /**
     * A paste is text the query takes, never the gestures it happens to spell.
     */
    @Override
    public void onInput(InputEvent event, Model model, Consumer<Msg> emit) {
        if (event instanceof InputEvent.PasteInput paste) {
            typed(paste.text()).ifPresent(emit);
        }
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The caret sits after the query while the palette owns focus.
     */
    @Override
    public Cursor cursor(Rect bounds, Model model) {
        if (!model.focused() || bounds.isEmpty()) {
            return null;
        }
        int column = bounds.col() + TextMeasure.columns(PROMPT) + TextMeasure.columns(model.query());
        return Cursor.at(bounds.row(), Math.max(bounds.col(), Math.min(column, bounds.col() + bounds.cols() - 1)));
    }

    /**
     * A press on a result row chooses it; the query row and the rows below the matches do not.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        List<Row> rows = model.visibleRows();
        int painted = Math.max(0, Math.min(model.resultRows(), rows.size() - model.offset()));
        if (local.row() < 1 || local.row() - 1 >= painted) {
            return;
        }
        Row row = rows.get(model.offset() + local.row() - 1);
        if (row.command().enabled()) {
            emit.accept(new Msg.Choose(row.id()));
        }
    }

    /**
     * The query row and the matching commands, with the palette as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Row> rows = model.visibleRows();
        int first = Math.max(0, model.offset());
        int last = Math.min(rows.size(), first + model.resultRows());
        List<Element<Msg>> lines = new ArrayList<>(Math.max(0, last - first) + 1);
        lines.add(new Element.Leaf<Line, Msg>(queryRow, new Line(model.query(), model.focused()), Sizing.fixed(1)));
        for (int index = first; index < last; index++) {
            lines.add(new Element.Leaf<Row, Msg>(resultRow, rows.get(index), Sizing.fixed(1)));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    // ── reduction ─────────────────────────────────────────────────────────────

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Up up -> new Result(step(state, -1), null);
            case Msg.Down down -> new Result(step(state, 1), null);
            case Msg.PageUp pageUp -> new Result(step(state, -pageSize(state)), null);
            case Msg.PageDown pageDown -> new Result(step(state, pageSize(state)), null);
            case Msg.Home home -> new Result(extreme(state, true), null);
            case Msg.End end -> new Result(extreme(state, false), null);
            case Msg.Typed typed -> new Result(typed(state, typed.text()), null);
            case Msg.Backspace backspace -> new Result(typed(state, ""), null);
            case Msg.Clear clear -> new Result(state.query().isEmpty() ? state : state.withQuery(""), null);
            case Msg.Activate activate -> choose(state, state.highlighted());
            case Msg.Choose choose -> choose(state, choose.id());
            case Msg.Cancel cancel -> new Result(state, new Output.Canceled());
            case Msg.RowsChanged changed -> new Result(state.withRows(changed.rows()), null);
            case Msg.Replace replaced -> new Result(state.withCommands(replaced.commands()), null);
        };
    }

    /**
     * The page step: one window of results, and at least one row so a zero-height model still moves.
     */
    private static int pageSize(Model state) {
        return Math.max(1, state.resultRows());
    }

    /**
     * The highlight moved a number of choosable rows.
     */
    private static Model step(Model state, int delta) {
        List<Command> matches = state.matches();
        int current = indexOf(matches, state.highlighted());
        if (current < 0 || delta == 0) {
            return state;
        }
        int target = current;
        int remaining = Math.abs(delta);
        int direction = delta < 0 ? -1 : 1;
        while (remaining > 0) {
            int next = selectableAt(matches, target + direction, direction);
            if (next < 0) {
                break;
            }
            target = next;
            remaining--;
        }
        String id = matches.get(target).id();
        return id.equals(state.highlighted()) ? state : state.withHighlight(id);
    }

    /**
     * The first or the last choosable match.
     */
    private static Model extreme(Model state, boolean first) {
        List<Command> matches = state.matches();
        int target = selectableAt(matches, first ? 0 : matches.size() - 1, first ? 1 : -1);
        if (target < 0) {
            return state;
        }
        String id = matches.get(target).id();
        return id.equals(state.highlighted()) ? state : state.withHighlight(id);
    }

    /**
     * The query edited: {@code text} is appended, or the last character is deleted when it is empty.
     *
     * <p>A query that does not change the model - a backspace on an empty query, a keystroke that only
     * carries a control character - returns the incoming instance, so a gesture nothing answers leaves
     * the runtime's unchanged-reference rule intact.</p>
     */
    private static Model typed(Model state, String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            String query = state.query();
            if (query.isEmpty()) {
                return state;
            }
            return state.withQuery(query.substring(0, query.offsetByCodePoints(query.length(), -1)));
        }
        StringBuilder kept = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(kept::appendCodePoint);
        return kept.isEmpty() ? state : state.withQuery(state.query() + kept);
    }

    /**
     * A choice by id: an enabled command the query matches is reported, anything else is not.
     */
    private static Result choose(Model state, String id) {
        for (Command command : state.matches()) {
            if (command.id().equals(id) && command.enabled()) {
                return new Result(state, new Output.Chosen(command.id(), command));
            }
        }
        return new Result(state, null);
    }

    /**
     * The text a query appends, or empty when the gesture carries nothing a query wants.
     */
    private static Optional<Msg> typed(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Msg.Typed(text));
    }

    /**
     * The commands a query matches, in the application's order while the query is empty.
     */
    private static List<Command> matches(List<Command> commands, String query) {
        if (commands.isEmpty()) {
            return List.of();
        }
        if (query.isEmpty()) {
            return commands;
        }
        return CompletionEngine.filter(
                query, commands, Command::searchText, CompletionEngine.Mode.FUZZY, true, commands.size());
    }

    /**
     * The nearest choosable command from {@code from} in {@code direction}, or -1 when there is none.
     */
    private static int selectableAt(List<Command> matches, int from, int direction) {
        for (int index = from; index >= 0 && index < matches.size(); index += direction) {
            if (matches.get(index).enabled()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The position of a command in a match list, or -1.
     */
    private static int indexOf(List<Command> matches, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < matches.size(); index++) {
            Command command = matches.get(index);
            if (command.enabled() && command.id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The highlight a match list admits: the id when it matches and can be chosen, the first otherwise.
     */
    private static String resolve(List<Command> matches, String highlighted) {
        int index = indexOf(matches, highlighted);
        if (index >= 0) {
            return matches.get(index).id();
        }
        int first = selectableAt(matches, 0, 1);
        return first < 0 ? "" : matches.get(first).id();
    }

    /**
     * Where a query matched a command's title, or -1 when it did not match there.
     */
    private static int matchStart(Command command, String query) {
        if (query.isEmpty()) {
            return -1;
        }
        return command.title().toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
    }

    /**
     * A palette's commands, as an immutable list.
     */
    private static List<Command> copy(List<Command> commands) {
        if (commands == null) {
            return List.of();
        }
        List<Command> copy = new ArrayList<>(commands.size());
        for (Command command : commands) {
            copy.add(Objects.requireNonNull(command, "command"));
        }
        return List.copyOf(copy);
    }

    /**
     * The query row's model: the query and whether the caret is presented.
     */
    private record Line(String query, boolean focused) {}

    /**
     * The query row: the prompt, the query, and the caret the palette places.
     */
    private static final class QueryRow<Msg> implements ThemedWidget<Line, Msg> {

        @Override
        public Size measure(Size max, Line line) {
            if (line == null || max.rows() <= 0 || max.cols() <= 0) {
                return new Size(0, 0);
            }
            return new Size(1, Math.min(max.cols(), TextMeasure.columns(PROMPT) + TextMeasure.columns(line.query())));
        }

        /**
         * The whole width it is offered: the query row is one line of the palette.
         */
        @Override
        public Rect arrange(Rect available, Size measured, Line line) {
            return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
        }

        @Override
        public void paint(Surface surface, Rect bounds, Line line, Theme theme) {
            if (line == null || bounds.isEmpty()) {
                return;
            }
            Theme resolved = theme == null ? Themes.DARK : theme;
            Style prompt = resolved.style(ThemeToken.ACCENT);
            Style query = resolved.style(ThemeToken.TEXT);
            if (line.focused()) {
                Style focus = resolved.style(ThemeToken.FOCUS);
                prompt = prompt.merge(focus);
                query = query.merge(focus);
            }
            surface.write(
                    bounds,
                    StyledText.concat(
                            StyledText.of(PROMPT, prompt),
                            StyledText.of(
                                    RowText.singleRow(StyledText.plain(line.query()))
                                            .plainText(),
                                    query)),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * One result row: the title with the matched run marked, then the description.
     */
    private static final class ResultRow<Msg> implements ThemedWidget<Row, Msg> {

        @Override
        public Size measure(Size max, Row row) {
            if (row == null || max.rows() <= 0 || max.cols() <= 0) {
                return new Size(0, 0);
            }
            return new Size(1, Math.min(max.cols(), columns(row.command())));
        }

        /**
         * The whole width it is offered: a highlight is a row, and a description follows a title.
         */
        @Override
        public Rect arrange(Rect available, Size measured, Row row) {
            return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
        }

        @Override
        public void paint(Surface surface, Rect bounds, Row row, Theme theme) {
            if (row == null || bounds.isEmpty()) {
                return;
            }
            Theme resolved = theme == null ? Themes.DARK : theme;
            Style title =
                    row.command().enabled() ? resolved.style(ThemeToken.TEXT) : resolved.style(ThemeToken.DISABLED);
            Style description = resolved.style(ThemeToken.MUTED);
            Style matched = resolved.style(ThemeToken.ACCENT);
            if (row.highlighted()) {
                surface.fill(bounds, resolved.style(ThemeToken.SELECTED));
                if (row.focused()) {
                    // Focus colors the row's content, not the selection's background: the theme's
                    // focus style is a foreground style, and filling a row with it would color cells
                    // the title does not cover.
                    Style focus = resolved.style(ThemeToken.FOCUS);
                    title = title.merge(focus);
                    description = description.merge(focus);
                    matched = matched.merge(focus);
                }
            }
            StyledText text = titleLine(row, title, matched);
            surface.write(bounds, text, WidthPolicy.PINNED);
            String detail = RowText.singleRow(StyledText.plain(row.command().description()))
                    .plainText();
            int titleColumns = text.columns();
            int available = bounds.cols() - titleColumns - GAP;
            // The description follows two separating columns and is clipped by the row; it is only
            // left out when the row has nothing left for it, because a clipped description still
            // says more than no description at all.
            if (!detail.isEmpty() && available > 0) {
                surface.writeLine(
                        bounds.row(),
                        bounds.col() + titleColumns + GAP,
                        available,
                        StyledText.of(detail, description),
                        WidthPolicy.PINNED);
            }
        }

        /**
         * The title with the run the query matched in the accent style.
         */
        private static StyledText titleLine(Row row, Style title, Style matched) {
            String value =
                    RowText.singleRow(StyledText.plain(row.command().title())).plainText();
            int start = row.matchStart();
            int length = row.matchLength();
            if (start < 0 || length <= 0 || start >= value.length()) {
                return StyledText.of(value, title);
            }
            int end = Math.min(value.length(), start + length);
            return StyledText.concat(
                    StyledText.of(value.substring(0, start), title),
                    StyledText.of(value.substring(start, end), title.merge(matched)),
                    StyledText.of(value.substring(end), title));
        }

        /**
         * The columns a row asks for: its title, the gap, and its description.
         */
        private static int columns(Command command) {
            int title = TextMeasure.columns(
                    RowText.singleRow(StyledText.plain(command.title())).plainText());
            String description =
                    RowText.singleRow(StyledText.plain(command.description())).plainText();
            return description.isEmpty() ? title : title + GAP + TextMeasure.columns(description);
        }
    }
}
