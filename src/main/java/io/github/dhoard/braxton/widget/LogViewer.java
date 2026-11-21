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
import io.github.dhoard.braxton.input.PointerButton;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An append-only log the user reads: bounded, chronological, and following the tail while the reader
 * is at it.
 *
 * <p>A log viewer is the part of a screen that shows what a program is doing as it does it. Lines
 * arrive in the order they happened, the newest at the bottom, and the reader sees a window over
 * them that starts at the tail. It owns three facts - the lines, which one the keyboard is on, and
 * how much of the list is on screen - and it answers the two questions such a pane is asked: what to
 * paint, and which line the user wants to look at more closely.</p>
 *
 * <h2>Appending and the bound</h2>
 *
 * <p>{@link Msg.Append} and {@link Msg.AppendAll} add lines at the bottom; a log never reorders or
 * deduplicates, because two identical lines are two things that happened. {@link Config#limit()}
 * bounds the buffer: the oldest lines beyond the bound are dropped as new ones arrive, so a long
 * session cannot grow the list without limit. Unlike a notification center, a log does not
 * collapse a repeated id: an id names one line, and appending a line whose id is already held is an
 * application error rather than a refresh.</p>
 *
 * <h2>The window follows the tail while the reader is at it</h2>
 *
 * <p>A new line moves the selection to it only when the selection was already on the newest line.
 * That is what makes the window follow the tail while the user is watching it, and what leaves the
 * window alone while the user has scrolled back to read something: the lines below keep arriving
 * without dragging the view away. The window is always the window over the selection ({@link
 * RowWindow}), so the reader is never scrolled out of sight. When the bound drops the line the
 * selection was on, the selection moves to the oldest line still held, which is the nearest context
 * the log still has.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>{@code up} and {@code down} move one line, {@code pageup} and {@code pagedown} one window, and
 * {@code home} and {@code end} to the oldest and the newest line - {@code end} is how a reader
 * resumes following the tail. {@code enter} reports the selected line as an {@link Output.Chosen},
 * which is how an application opens a detail pane for it. The gestures are a {@link KeyMap} -
 * {@link #defaultKeys()} defines them and {@link #keys()} is what the widget routes, so {@link
 * Help} describes what actually fires.</p>
 *
 * <p>A press selects the line it landed on and a second press on the selected line chooses it, the
 * rule {@link Tree} and {@link NotificationCenter} follow; a press below the last painted line
 * selects nothing. The wheel moves the reader one line, which is what a reader of a one-column list
 * expects.</p>
 *
 * <h2>Themes and narrow panes</h2>
 *
 * <p>A line is a severity's marker in the role its {@link Severity#token()} names, one separating
 * column, and the message in the theme's {@code TEXT}; {@link SeverityRow} paints it, the same row
 * the notification widgets paint. The selected line is filled with {@code SELECTED} and a selected
 * line in a focused log merges {@code FOCUS} into what it paints over that fill. A message wider
 * than the pane is clipped rather than wrapped, so one long line cannot push the others out of the
 * window, and a log with nothing in it paints nothing and takes no turn in the focus walk.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A log viewer is one object per placed viewer, held across frames the way a component is,
 * because the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see NotificationCenter
 * @see Elements#logViewer(LogViewer, LogViewer.Model, java.util.function.Function)
 */
public final class LogViewer
        implements Component<LogViewer.Model, LogViewer.Msg, LogViewer.Output>,
                Interactive<LogViewer.Model, LogViewer.Msg> {

    /**
     * How many lines a log keeps unless its configuration says otherwise.
     */
    public static final int DEFAULT_LIMIT = 1_000;

    /**
     * The rows a log paints when it is configured without a height of its own.
     */
    public static final int DEFAULT_ROWS = 10;

    private static final KeyMap<Msg> KEYS = KeyMap.of(
            KeyMap.group(
                    "Reader",
                    KeyAction.<Msg>of("previous-line", new Msg.Up(), "\u2191", "previous line", "up"),
                    KeyAction.<Msg>of("next-line", new Msg.Down(), "\u2193", "next line", "down"),
                    KeyAction.<Msg>of("previous-page", new Msg.PageUp(), "pgup", "previous page", "pageup"),
                    KeyAction.<Msg>of("next-page", new Msg.PageDown(), "pgdn", "next page", "pagedown"),
                    KeyAction.<Msg>of("oldest", new Msg.Home(), "home", "oldest line", "home"),
                    KeyAction.<Msg>of("newest", new Msg.End(), "end", "newest line", "end")),
            KeyMap.group("Actions", KeyAction.<Msg>of("open", new Msg.Activate(), "enter", "open", "enter")));

    private final Config config;

    /**
     * The row painter: a severity marker and the message in the theme's styles.
     */
    private final SeverityRow<Msg> row = new SeverityRow<>();

    /**
     * A log with the default bound and window height.
     */
    public LogViewer() {
        this(Config.defaults());
    }

    /**
     * A log over its configuration.
     *
     * @param config the bound and the window height; required
     */
    public LogViewer(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One line.
     *
     * <p>The identity is what a press and an activation name, so it is stable for as long as the
     * line is held. The text is {@link StyledText} so an application may style part of it - a
     * timestamp, a source - and the widget resolves the theme's base style underneath whatever the
     * caller already set, so a live theme switch still repaints the line.</p>
     *
     * @param id the identity, unique across one log; never blank
     * @param severity how urgent the line is; null is {@link Severity#INFO}
     * @param text the message painted after the severity's marker; null is empty
     */
    public record Entry(String id, Severity severity, StyledText text) {

        /**
         * Rejects a line without an id and substitutes the defaults.
         */
        public Entry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("entry id is required");
            }
            severity = severity == null ? Severity.INFO : severity;
            text = text == null ? StyledText.empty() : text;
        }

        /**
         * A line with a plain message.
         *
         * @param id the identity; never blank
         * @param severity how urgent the line is; null is {@link Severity#INFO}
         * @param text the plain message; null is empty
         * @return the line
         */
        public static Entry of(String id, Severity severity, String text) {
            return new Entry(id, severity, StyledText.plain(text));
        }

        /**
         * An informational line.
         *
         * @param id the identity; never blank
         * @param text the plain message; null is empty
         * @return the line
         */
        public static Entry of(String id, String text) {
            return of(id, Severity.INFO, text);
        }

        /**
         * This line with another message.
         *
         * @param value the message; null is empty
         * @return the new line
         */
        public Entry withText(StyledText value) {
            return new Entry(id, severity, value);
        }

        /**
         * This line with another severity.
         *
         * @param value how urgent the line is; null is {@link Severity#INFO}
         * @return the new line
         */
        public Entry withSeverity(Severity value) {
            return new Entry(id, value, text);
        }
    }

    /**
     * Immutable log state.
     *
     * <p>The canonical constructor copies the lines, rejects a duplicated id, resolves the selection
     * to a line the log holds (the newest when the named one is gone, and nothing when the log is
     * empty), and keeps the window over it, so a model the application builds obeys the same rules
     * the reductions do.</p>
     *
     * @param entries the lines, oldest first
     * @param selected the id of the selected line, or empty when there are none
     * @param focused whether the log owns focus
     * @param offset the first line the window paints; clamped into the lines
     * @param rows the rows the window may paint; never negative
     */
    public record Model(List<Entry> entries, String selected, boolean focused, int offset, int rows) {

        /**
         * Normalizes the lines, resolves the selection and admits the window.
         */
        public Model {
            entries = copy(entries);
            rows = Math.max(0, rows);
            selected = resolve(entries, selected);
            offset = RowWindow.admit(indexOf(entries, selected), entries.size(), Math.max(0, offset), rows);
        }

        /**
         * A frozen log over its lines, focused, with the window at the tail.
         *
         * @param entries the lines, oldest first
         * @return the model
         */
        public static Model of(List<Entry> entries) {
            return new Model(entries, "", true, 0, DEFAULT_ROWS);
        }

        /**
         * An empty log, focused.
         *
         * @return the model
         */
        public static Model empty() {
            return new Model(List.of(), "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This log over other lines, with the selection resolved again.
         *
         * @param value the lines, oldest first; null is empty
         * @return the new model
         */
        public Model withEntries(List<Entry> value) {
            return new Model(value, selected, focused, offset, rows);
        }

        /**
         * This log with another line selected.
         *
         * @param value the id to select; null or an unknown id selects the newest line
         * @return the new model
         */
        public Model withSelected(String value) {
            return new Model(entries, value, focused, offset, rows);
        }

        /**
         * The same log, focused or not.
         *
         * @param value whether the log owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(entries, selected, value, offset, rows);
        }

        /**
         * This log with a window of a different height.
         *
         * @param value the rows the window may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(entries, selected, focused, offset, value);
        }

        /**
         * The line with an id, if the log holds it.
         *
         * @param id the identity; null finds nothing
         * @return the line, or empty
         */
        public Optional<Entry> find(String id) {
            int index = indexOf(entries, id);
            return index < 0 ? Optional.empty() : Optional.of(entries.get(index));
        }

        /**
         * The selected line, if there is one.
         *
         * @return the line, or empty
         */
        public Optional<Entry> selectedEntry() {
            return find(selected);
        }

        /**
         * The first line the window paints.
         *
         * @return the offset into the lines
         */
        public int firstPainted() {
            return offset;
        }

        /**
         * The lines the window paints, oldest first.
         *
         * @return the visible lines
         */
        public List<Entry> visibleEntries() {
            int first = Math.min(offset, entries.size());
            int last = Math.min(entries.size(), first + Math.max(0, rows));
            return entries.subList(first, last);
        }

        /**
         * Copies the lines, rejecting a null entry and a duplicated id.
         */
        private static List<Entry> copy(List<Entry> entries) {
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            List<Entry> copy = new ArrayList<>(entries.size());
            Set<String> ids = new HashSet<>(entries.size());
            for (Entry entry : entries) {
                Entry present = Objects.requireNonNull(entry, "entry");
                if (!ids.add(present.id())) {
                    throw new IllegalArgumentException("duplicate entry id: " + present.id());
                }
                copy.add(present);
            }
            return List.copyOf(copy);
        }

        /**
         * The selection a log admits: the named line, the newest, or nothing.
         */
        private static String resolve(List<Entry> entries, String id) {
            int index = indexOf(entries, id);
            return index >= 0
                    ? entries.get(index).id()
                    : entries.isEmpty() ? "" : entries.get(entries.size() - 1).id();
        }
    }

    /**
     * The log's messages.
     */
    public sealed interface Msg {

        /**
         * One line arrives at the bottom.
         *
         * @param entry the line; required
         */
        record Append(Entry entry) implements Msg {

            /**
             * Rejects a missing line.
             */
            public Append {
                entry = Objects.requireNonNull(entry, "entry");
            }
        }

        /**
         * Several lines arrive at the bottom, in order.
         *
         * @param entries the lines; null is none
         */
        record AppendAll(List<Entry> entries) implements Msg {}

        /**
         * Removes every line.
         */
        record Clear() implements Msg {}

        /**
         * Selects a line, which is what a press on it does.
         *
         * @param id the line to select; an unknown id selects nothing
         */
        record Select(String id) implements Msg {}

        /**
         * Reports the selected line.
         */
        record Activate() implements Msg {}

        /**
         * Moves the selection to the previous line, toward the oldest.
         */
        record Up() implements Msg {}

        /**
         * Moves the selection to the next line, toward the newest.
         */
        record Down() implements Msg {}

        /**
         * Moves the selection a window toward the oldest line.
         */
        record PageUp() implements Msg {}

        /**
         * Moves the selection a window toward the newest line.
         */
        record PageDown() implements Msg {}

        /**
         * Moves the selection to the oldest line.
         */
        record Home() implements Msg {}

        /**
         * Moves the selection to the newest line, which resumes following the tail.
         */
        record End() implements Msg {}

        /**
         * The window is a different height.
         *
         * @param rows the rows the window may paint; never negative
         */
        record RowsChanged(int rows) implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * A line was chosen.
         *
         * @param id the line's id
         * @param entry the line itself
         */
        record Chosen(String id, Entry entry) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a log is configured.
     *
     * @param limit how many lines it keeps, the newest first to survive; positive
     * @param rows the rows the window paints when no message or model set a height; never negative
     */
    public record Config(int limit, int rows) {

        /**
         * Rejects an unusable bound or window height.
         */
        public Config {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
        }

        /**
         * The default configuration.
         *
         * @return a log keeping {@link #DEFAULT_LIMIT} lines in a {@link #DEFAULT_ROWS} row window
         */
        public static Config defaults() {
            return new Config(DEFAULT_LIMIT, DEFAULT_ROWS);
        }

        /**
         * A configuration with the default window height.
         *
         * @param limit how many lines it keeps; positive
         * @return the configuration
         */
        public static Config of(int limit) {
            return new Config(limit, DEFAULT_ROWS);
        }

        /**
         * This configuration with another bound.
         *
         * @param value how many lines it keeps; positive
         * @return the new configuration
         */
        public Config withLimit(int value) {
            return new Config(value, rows);
        }

        /**
         * This configuration whose window paints a different height.
         *
         * @param value the rows; never negative
         * @return the new configuration
         */
        public Config withRows(int value) {
            return new Config(limit, value);
        }
    }

    /**
     * How this log is configured.
     *
     * @return how this log is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The log's element: its painted lines, and the log itself answering for them.
     *
     * @param model the state to paint; required
     * @return the element, emitting this log's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: move through the lines and open one.
     *
     * <p>There is no clear key. Whether the user may throw away the log is the application's
     * decision, and an application that allows it sends {@link Msg.Clear} itself.</p>
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KEYS;
    }

    /**
     * The actions this log handles.
     *
     * @return the actions this log handles
     */
    public KeyMap<Msg> keys() {
        return KEYS;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(new Model(List.of(), "", true, 0, config.rows()));
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
     * The log's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key its press did, so it never fires the press a second time.
            return;
        }
        keys().actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * A press selects a line, a press on the selected line chooses it, and the wheel moves.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (pointer == null) {
            return;
        }
        if (pointer.isWheel()) {
            if (pointer.button() == PointerButton.WHEEL_UP) {
                emit.accept(new Msg.Up());
            } else if (pointer.button() == PointerButton.WHEEL_DOWN) {
                emit.accept(new Msg.Down());
            }
            return;
        }
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        int painted = paintedRows(model, bounds.rows());
        if (local.row() < 0 || local.row() >= painted) {
            return;
        }
        String id = model.entries().get(model.offset() + local.row()).id();
        emit.accept(id.equals(model.selected()) ? new Msg.Activate() : new Msg.Select(id));
    }

    /**
     * A log with lines can take the keyboard; one with nothing to read cannot.
     */
    @Override
    public boolean canFocus(Model model) {
        return model != null && !model.entries().isEmpty();
    }

    /**
     * Focus is model data: the application sets it and the log only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model != null && model.focused();
    }

    /**
     * The painted lines, with the log itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        int first = Math.max(0, model.offset());
        int last = Math.min(model.entries().size(), first + model.rows());
        List<Element<Msg>> lines = new ArrayList<>(Math.max(0, last - first));
        for (int index = first; index < last; index++) {
            Entry entry = model.entries().get(index);
            boolean selected = entry.id().equals(model.selected());
            lines.add(new Element.Leaf<SeverityRow.Row, Msg>(
                    row,
                    new SeverityRow.Row(entry.severity(), entry.text(), false, selected, model.focused()),
                    Sizing.fixed(1)));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Append append -> append(state, append.entry());
            case Msg.AppendAll all -> appendAll(state, all.entries());
            case Msg.Clear clear -> clear(state);
            case Msg.Select select -> select(state, select.id());
            case Msg.Activate activate -> activate(state);
            case Msg.Up up -> move(state, -1);
            case Msg.Down down -> move(state, 1);
            case Msg.PageUp page -> move(state, -Math.max(1, state.rows()));
            case Msg.PageDown page -> move(state, Math.max(1, state.rows()));
            case Msg.Home home -> jump(state, 0);
            case Msg.End end -> jump(state, state.entries().size() - 1);
            case Msg.RowsChanged changed -> new Result(state.withRows(changed.rows()), null);
        };
    }

    /**
     * A line arrives at the bottom.
     *
     * <p>The selection follows the new line only when it was already on the newest one, so the
     * window follows the tail while the reader is at it and stays where it is while the reader is
     * reading. When the bound drops the line the selection was on, the selection moves to the
     * oldest line still held.</p>
     */
    private Result append(Model state, Entry entry) {
        boolean following = state.selected().isEmpty() || state.selected().equals(newestId(state.entries()));
        List<Entry> next = new ArrayList<>(state.entries().size() + 1);
        next.addAll(state.entries());
        next.add(entry);
        List<Entry> bounded =
                next.size() > config.limit() ? next.subList(next.size() - config.limit(), next.size()) : next;
        String selected = state.selected();
        if (following) {
            selected = entry.id();
        } else if (indexOf(bounded, selected) < 0) {
            // The line the reader was on fell out of the buffer; the oldest line kept is the nearest
            // context the log still has.
            selected = bounded.isEmpty() ? "" : bounded.get(0).id();
        }
        return new Result(new Model(bounded, selected, state.focused(), state.offset(), state.rows()), null);
    }

    /**
     * Several lines arrive in order; a null batch adds nothing.
     */
    private Result appendAll(Model state, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new Result(state, null);
        }
        Model next = state;
        for (Entry entry : entries) {
            next = append(next, Objects.requireNonNull(entry, "entry")).state();
        }
        return new Result(next, null);
    }

    /**
     * Every line removed; the window starts at the top again.
     */
    private static Result clear(Model state) {
        if (state.entries().isEmpty()) {
            return new Result(state, null);
        }
        return new Result(new Model(List.of(), "", state.focused(), 0, state.rows()), null);
    }

    /**
     * A press on a line selects it.
     */
    private static Result select(Model state, String id) {
        if (indexOf(state.entries(), id) < 0) {
            return new Result(state, null);
        }
        return new Result(state.withSelected(id), null);
    }

    /**
     * The selected line reported.
     */
    private static Result activate(Model state) {
        Entry entry = state.selectedEntry().orElse(null);
        return entry == null ? new Result(state, null) : new Result(state, new Output.Chosen(entry.id(), entry));
    }

    /**
     * The selection moved a number of lines, which reports nothing when there is nowhere to go.
     */
    private static Result move(Model state, int delta) {
        int current = indexOf(state.entries(), state.selected());
        if (current < 0) {
            return new Result(state, null);
        }
        return jump(state, current + delta);
    }

    /**
     * The selection moved to a line, clamped into the log; a move that lands where it was changes nothing.
     */
    private static Result jump(Model state, int target) {
        if (state.entries().isEmpty()) {
            return new Result(state, null);
        }
        int index = Math.max(0, Math.min(state.entries().size() - 1, target));
        return select(state, state.entries().get(index).id());
    }

    /**
     * The rows the window paints inside a pane of a height.
     */
    private static int paintedRows(Model model, int rows) {
        if (model == null) {
            return 0;
        }
        int window = Math.max(0, Math.min(model.rows(), rows));
        return Math.max(0, Math.min(window, model.entries().size() - model.offset()));
    }

    /**
     * The id of the newest line, or empty when there is none.
     */
    private static String newestId(List<Entry> entries) {
        return entries.isEmpty() ? "" : entries.get(entries.size() - 1).id();
    }

    /**
     * The row of a line, or -1.
     */
    private static int indexOf(List<Entry> entries, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }
}
