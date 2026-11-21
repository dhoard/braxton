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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An ordered, bounded set of notifications the user reads, marks and chooses.
 *
 * <p>A notification center is the part of a screen that keeps what happened: a list of messages,
 * each with a severity and a read state, of which the reader sees a window. It owns three facts -
 * the notifications, which one the keyboard is on, and how much of the list is on screen - and it
 * answers the two questions such a list is asked: what to paint, and what the user chose.</p>
 *
 * <h2>Order and bound</h2>
 *
 * <p>The newest notification is first, so a window at offset zero is the window that follows the
 * newest. {@link Msg.Push} puts a notification at the front, and {@link Config#limit()} bounds the
 * set: the oldest notifications beyond the bound are dropped as the new ones arrive, so a long
 * session cannot grow the list without limit. The bound is applied by the message, not by {@link
 * Model}, because a model the application builds itself is its own business.</p>
 *
 * <p>Pushing an id that is already present refreshes that notification - its severity, its text and
 * its read state become the pushed ones - and it moves back to the front instead of being listed
 * twice: an application that re-reports one condition does not get two rows for it, and the thing
 * that just happened again is the newest thing that happened.</p>
 *
 * <h2>The window follows the newest while the reader is at it</h2>
 *
 * <p>A push moves the selection to the new notification only when the selection was already on the
 * newest one, which is the rule that makes the window follow the newest without yanking it away from
 * a reader who has scrolled back through older messages. The window is always the window over the
 * selection ({@link RowWindow}), so a list longer than the rows the center is given scrolls with the
 * selection instead of losing it.</p>
 *
 * <h2>Read state</h2>
 *
 * <p>A notification arrives unread. The reader marks it read by moving the selection onto it
 * ({@code up}, {@code down}, {@code home}, {@code end}), by pressing it, or by activating it; {@link
 * Msg.MarkRead} and {@link Msg.MarkAllRead} are the explicit forms, for a screen that marks what it
 * has shown. {@link Model#unread()} is the count an application badges its status bar with, and a
 * read notification is painted muted rather than losing its severity's marker.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>{@code up} and {@code down} move the selection up and down the painted list - to the newer and
 * the older notification - {@code home} and {@code end} go to the newest and the oldest, and {@code
 * enter} activates what is selected, which reports an {@link Output.Chosen}. The gestures are a {@link KeyMap} - {@link #defaultKeys()} defines them and {@link
 * #keys()} is what the widget routes, so {@link Help} describes what actually fires. Dismissing and
 * marking are messages rather than keys: which gesture removes a notification is the application's
 * decision, and it already owns the model.</p>
 *
 * <p>A pointer press selects the row it landed on and marks it read; a press on the row that is
 * already selected activates it, the rule {@link Tree} follows. A press below the last painted row
 * selects nothing.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>The severity's {@link Severity#marker() marker} is painted in the role its {@link
 * Severity#token()} names and the message in the theme's {@code TEXT}; a read notification is
 * painted muted. The selected row is filled with {@code SELECTED}, and a selected row in a focused
 * center merges {@code FOCUS} into what it paints over that fill, the rule {@link Menu} and {@link
 * Tree} rows follow. A message wider than the row is clipped rather than wrapped, and a center with
 * nothing in it paints nothing and takes no turn in the focus walk.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A notification center is one object per placed center, held across frames the way a component
 * is, because the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Toast
 * @see Elements#notificationCenter(NotificationCenter, NotificationCenter.Model, java.util.function.Function)
 */
public final class NotificationCenter
        implements Component<NotificationCenter.Model, NotificationCenter.Msg, NotificationCenter.Output>,
                Interactive<NotificationCenter.Model, NotificationCenter.Msg> {

    /**
     * How many notifications a center keeps unless its configuration says otherwise.
     */
    public static final int DEFAULT_LIMIT = 50;

    /**
     * The rows a center paints when it is configured without a height of its own.
     */
    public static final int DEFAULT_ROWS = 8;

    private final Config config;

    /**
     * The row painter: a severity marker and the message in the theme's styles.
     */
    private final SeverityRow<Msg> row = new SeverityRow<>();

    /**
     * A center with the default bound and window height.
     */
    public NotificationCenter() {
        this(Config.defaults());
    }

    /**
     * A center over its configuration.
     *
     * @param config the bound and the window height; required
     */
    public NotificationCenter(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One notification.
     *
     * <p>The identity is what a press, an activation and a dismissal name, so it is stable across a
     * reordering of the list. The text is {@link StyledText} so an application may style part of it;
     * the widget resolves the theme's base style underneath whatever the caller already set, so a
     * live theme switch still repaints the row.</p>
     *
     * @param id the identity, unique across one center; never blank
     * @param severity how urgent the message is; null is {@link Severity#INFO}
     * @param text the message painted after the severity's marker; null is empty
     * @param read whether the reader has already seen it
     */
    public record Notification(String id, Severity severity, StyledText text, boolean read) {

        /**
         * Rejects a notification without an id and substitutes the defaults.
         */
        public Notification {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("notification id is required");
            }
            severity = severity == null ? Severity.INFO : severity;
            text = text == null ? StyledText.empty() : text;
        }

        /**
         * An unread notification with a plain message.
         *
         * @param id the identity; never blank
         * @param severity how urgent the message is; null is {@link Severity#INFO}
         * @param text the plain message; null is empty
         * @return the notification
         */
        public static Notification of(String id, Severity severity, String text) {
            return new Notification(id, severity, StyledText.plain(text), false);
        }

        /**
         * An unread informational notification.
         *
         * @param id the identity; never blank
         * @param text the plain message; null is empty
         * @return the notification
         */
        public static Notification of(String id, String text) {
            return of(id, Severity.INFO, text);
        }

        /**
         * This notification read or unread.
         *
         * @param value whether the reader has already seen it
         * @return the new notification
         */
        public Notification withRead(boolean value) {
            return new Notification(id, severity, text, value);
        }

        /**
         * This notification with another message.
         *
         * @param value the message; null is empty
         * @return the new notification
         */
        public Notification withText(StyledText value) {
            return new Notification(id, severity, value, read);
        }
    }

    /**
     * One painted row.
     *
     * @param notification the notification painted by this row
     * @param selected whether this row is selected
     * @param focused whether the widget owns keyboard focus
     */
    public record Row(Notification notification, boolean selected, boolean focused) {

        /**
         * The id of the notification the row paints.
         *
         * @return the id of the notification the row paints
         */
        public String id() {
            return notification.id();
        }
    }

    /**
     * Immutable center state.
     *
     * <p>The canonical constructor keeps the ids unique, resolves the selection to a notification the
     * center shows (the newest when the named one is gone, and nothing when there is none), and
     * keeps the window over it, so a model the application builds obeys the same rules the
     * reductions do.</p>
     *
     * @param notifications the notifications, newest first
     * @param selected the id of the selected notification, or empty when there are none
     * @param focused whether the center owns focus
     * @param offset the first row the window paints; clamped into the notifications
     * @param rows the rows the window may paint; never negative
     */
    public record Model(List<Notification> notifications, String selected, boolean focused, int offset, int rows) {

        /**
         * Normalizes the notifications, resolves the selection and admits the window.
         */
        public Model {
            notifications = copy(notifications);
            rows = Math.max(0, rows);
            selected = resolve(notifications, selected);
            offset = RowWindow.admit(indexOf(notifications, selected), notifications.size(), Math.max(0, offset), rows);
        }

        /**
         * An empty center, focused.
         *
         * @return the model
         */
        public static Model empty() {
            return new Model(List.of(), "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This center with other notifications, with the selection resolved again.
         *
         * @param value the notifications, newest first; null is empty
         * @return the new model
         */
        public Model withNotifications(List<Notification> value) {
            return new Model(value, selected, focused, offset, rows);
        }

        /**
         * This center with another notification selected.
         *
         * @param value the id to select; null or an unknown id selects the newest
         * @return the new model
         */
        public Model withSelected(String value) {
            return new Model(notifications, value, focused, offset, rows);
        }

        /**
         * The same center, focused or not.
         *
         * @param value whether the center owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(notifications, selected, value, offset, rows);
        }

        /**
         * This center with a window of a different height.
         *
         * @param value the rows the window may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(notifications, selected, focused, offset, value);
        }

        /**
         * The notification with an id, if the center holds it.
         *
         * @param id the identity; null finds nothing
         * @return the notification, or empty
         */
        public Optional<Notification> find(String id) {
            int index = indexOf(notifications, id);
            return index < 0 ? Optional.empty() : Optional.of(notifications.get(index));
        }

        /**
         * The selected notification, if there is one.
         *
         * @return the notification, or empty
         */
        public Optional<Notification> selectedNotification() {
            return find(selected);
        }

        /**
         * How many notifications the reader has not seen yet.
         *
         * @return the unread count
         */
        public int unread() {
            int count = 0;
            for (Notification notification : notifications) {
                if (!notification.read()) {
                    count++;
                }
            }
            return count;
        }

        /**
         * The rows this center shows, newest first from the top of the window.
         *
         * @return the visible rows
         */
        public List<Row> visibleRows() {
            List<Row> rows = new ArrayList<>(notifications.size());
            for (Notification notification : notifications) {
                rows.add(new Row(notification, notification.id().equals(selected), focused));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * The center's messages.
     */
    public sealed interface Msg {

        /**
         * A new notification arrives, or an existing one is refreshed.
         *
         * @param notification the notification; required
         */
        record Push(Notification notification) implements Msg {

            /**
             * Rejects a missing notification.
             */
            public Push {
                notification = Objects.requireNonNull(notification, "notification");
            }
        }

        /**
         * Selects a notification and marks it read, which is what a press on its row does.
         *
         * @param id the notification to select; an unknown id selects nothing
         */
        record Select(String id) implements Msg {}

        /**
         * Reports the selected notification, marking it read.
         */
        record Activate() implements Msg {}

        /**
         * Moves the selection up the list, to the newer notification.
         */
        record Up() implements Msg {}

        /**
         * Moves the selection down the list, to the older notification.
         */
        record Down() implements Msg {}

        /**
         * Moves the selection to the newest notification.
         */
        record Home() implements Msg {}

        /**
         * Moves the selection to the oldest notification.
         */
        record End() implements Msg {}

        /**
         * Marks one notification read.
         *
         * @param id the notification; an unknown id marks nothing
         */
        record MarkRead(String id) implements Msg {}

        /**
         * Marks every notification read.
         */
        record MarkAllRead() implements Msg {}

        /**
         * Removes one notification.
         *
         * @param id the notification; an unknown id removes nothing
         */
        record Dismiss(String id) implements Msg {}

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
         * A notification was activated.
         *
         * @param id the notification's id
         * @param notification the notification itself
         */
        record Chosen(String id, Notification notification) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a center is configured.
     *
     * @param limit how many notifications it keeps, the newest first; positive
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
         * @return a center keeping {@link #DEFAULT_LIMIT} notifications in a {@link #DEFAULT_ROWS}
         *     row window
         */
        public static Config defaults() {
            return new Config(DEFAULT_LIMIT, DEFAULT_ROWS);
        }

        /**
         * A configuration with the default window height.
         *
         * @param limit how many notifications it keeps; positive
         * @return the configuration
         */
        public static Config of(int limit) {
            return new Config(limit, DEFAULT_ROWS);
        }

        /**
         * This configuration with another bound.
         *
         * @param value how many notifications it keeps; positive
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
     * How this center is configured.
     *
     * @return how this center is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The center's element: its painted rows, and the center itself answering for them.
     *
     * @param model the state to paint; required
     * @return the element, emitting this center's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation and activation.
     *
     * <p>There is no dismissal key and no mark key. Which gesture removes a notification is the
     * application's decision - {@code d}, {@code delete} and {@code ctrl+w} are all plausible - and
     * an application that binds one sends {@link Msg.Dismiss} or {@link Msg.MarkAllRead} itself.</p>
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "newer notification", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "older notification", "down"),
                        KeyAction.<Msg>of("home", new Msg.Home(), "home", "newest notification", "home"),
                        KeyAction.<Msg>of("end", new Msg.End(), "end", "oldest notification", "end")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("open", new Msg.Activate(), "enter", "open", "enter", "alt+enter")));
    }

    /**
     * The actions this center handles.
     *
     * @return the actions this center handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
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
     * The center's keys, from the same map {@link Help} renders.
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
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A center with nothing to read answers nothing, so it takes no turn in the focus walk.
     */
    @Override
    public boolean canFocus(Model model) {
        return !model.notifications().isEmpty();
    }

    /**
     * A press selects a row, and a press on the selected row activates it.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (pointer == null || !pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        int painted = Math.max(0, Math.min(model.rows(), model.notifications().size() - model.offset()));
        if (local.row() < 0 || local.row() >= painted) {
            return;
        }
        String id = model.notifications().get(model.offset() + local.row()).id();
        emit.accept(id.equals(model.selected()) ? new Msg.Activate() : new Msg.Select(id));
    }

    /**
     * The painted rows, with the center itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Row> rows = model.visibleRows();
        int first = Math.max(0, model.offset());
        int last = Math.min(rows.size(), first + model.rows());
        List<Element<Msg>> lines = new ArrayList<>(Math.max(0, last - first));
        for (int index = first; index < last; index++) {
            Row painted = rows.get(index);
            lines.add(new Element.Leaf<SeverityRow.Row, Msg>(
                    row,
                    new SeverityRow.Row(
                            painted.notification().severity(),
                            painted.notification().text(),
                            painted.notification().read(),
                            painted.selected(),
                            painted.focused()),
                    Sizing.fixed(1)));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Push push -> push(state, push.notification());
            case Msg.Select select -> select(state, select.id());
            case Msg.Activate activate -> activate(state);
            case Msg.Up up -> move(state, -1);
            case Msg.Down down -> move(state, 1);
            case Msg.Home home -> jump(state, 0);
            case Msg.End end -> jump(state, state.notifications().size() - 1);
            case Msg.MarkRead read -> markRead(state, read.id());
            case Msg.MarkAllRead all -> markAllRead(state);
            case Msg.Dismiss dismiss -> dismiss(state, dismiss.id());
            case Msg.RowsChanged changed -> new Result(state.withRows(changed.rows()), null);
        };
    }

    /**
     * A notification arrives at the front, or refreshes one that is already there and returns to it.
     *
     * <p>The selection follows the new notification only when it was already on the newest one, so
     * the window follows the newest while the reader is looking at it and stays where it is while
     * the reader is reading older messages. The bound drops the oldest notifications beyond it.</p>
     */
    private Result push(Model state, Notification notification) {
        boolean following = state.selected().isEmpty() || state.selected().equals(newestId(state.notifications()));
        List<Notification> next = new ArrayList<>(state.notifications().size() + 1);
        next.add(notification);
        for (Notification existing : state.notifications()) {
            if (!existing.id().equals(notification.id())) {
                next.add(existing);
            }
        }
        List<Notification> bounded = next.size() > config.limit() ? next.subList(0, config.limit()) : next;
        String selected = following ? notification.id() : state.selected();
        return new Result(new Model(bounded, selected, state.focused(), state.offset(), state.rows()), null);
    }

    /**
     * A press on a row: the notification is selected and marked read.
     */
    private static Result select(Model state, String id) {
        if (indexOf(state.notifications(), id) < 0) {
            return new Result(state, null);
        }
        return new Result(
                new Model(markRead(state.notifications(), id), id, state.focused(), state.offset(), state.rows()),
                null);
    }

    /**
     * The selected notification reported, and marked read.
     */
    private static Result activate(Model state) {
        Notification notification = state.selectedNotification().orElse(null);
        if (notification == null) {
            return new Result(state, null);
        }
        Model next = new Model(
                markRead(state.notifications(), notification.id()),
                notification.id(),
                state.focused(),
                state.offset(),
                state.rows());
        return new Result(next, new Output.Chosen(notification.id(), notification.withRead(true)));
    }

    /**
     * The selection moved a row up or down, marking the row it landed on read.
     */
    private static Result move(Model state, int delta) {
        int current = indexOf(state.notifications(), state.selected());
        if (current < 0) {
            return new Result(state, null);
        }
        return jump(state, current + delta);
    }

    /**
     * The selection moved to a row, marking it read; a row outside the list changes nothing.
     */
    private static Result jump(Model state, int target) {
        List<Notification> notifications = state.notifications();
        if (notifications.isEmpty()) {
            return new Result(state, null);
        }
        int index = Math.max(0, Math.min(notifications.size() - 1, target));
        Notification notification = notifications.get(index);
        if (notification.id().equals(state.selected()) && notification.read()) {
            return new Result(state, null);
        }
        return new Result(
                new Model(
                        markRead(notifications, notification.id()),
                        notification.id(),
                        state.focused(),
                        state.offset(),
                        state.rows()),
                null);
    }

    /**
     * One notification marked read.
     */
    private static Result markRead(Model state, String id) {
        if (indexOf(state.notifications(), id) < 0) {
            return new Result(state, null);
        }
        return new Result(state.withNotifications(markRead(state.notifications(), id)), null);
    }

    /**
     * Every notification marked read.
     */
    private static Result markAllRead(Model state) {
        List<Notification> next = new ArrayList<>(state.notifications().size());
        for (Notification notification : state.notifications()) {
            next.add(notification.withRead(true));
        }
        return new Result(state.withNotifications(next), null);
    }

    /**
     * One notification removed; the selection takes the row that followed it when it was selected.
     */
    private static Result dismiss(Model state, String id) {
        int index = indexOf(state.notifications(), id);
        if (index < 0) {
            return new Result(state, null);
        }
        List<Notification> next = new ArrayList<>(state.notifications());
        next.remove(index);
        String selected = state.selected();
        if (selected.equals(id)) {
            selected = next.isEmpty()
                    ? ""
                    : next.get(Math.min(index, next.size() - 1)).id();
        }
        return new Result(new Model(next, selected, state.focused(), state.offset(), state.rows()), null);
    }

    /**
     * The notification list with one entry marked read; the list is the same when it already was.
     */
    private static List<Notification> markRead(List<Notification> notifications, String id) {
        int index = indexOf(notifications, id);
        if (index < 0 || notifications.get(index).read()) {
            return notifications;
        }
        List<Notification> next = new ArrayList<>(notifications);
        next.set(index, notifications.get(index).withRead(true));
        return List.copyOf(next);
    }

    /**
     * The id of the newest notification, or empty when there is none.
     */
    private static String newestId(List<Notification> notifications) {
        return notifications.isEmpty() ? "" : notifications.get(0).id();
    }

    /**
     * The row of a notification, or -1.
     */
    private static int indexOf(List<Notification> notifications, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < notifications.size(); index++) {
            if (notifications.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The selection a notification list admits: the named one, the newest, or nothing.
     */
    private static String resolve(List<Notification> notifications, String id) {
        int index = indexOf(notifications, id);
        return index >= 0 ? notifications.get(index).id() : newestId(notifications);
    }

    /**
     * A notification list, as an immutable list with distinct ids.
     */
    private static List<Notification> copy(List<Notification> notifications) {
        if (notifications == null) {
            return List.of();
        }
        List<Notification> copy = new ArrayList<>(notifications.size());
        Set<String> ids = new HashSet<>(notifications.size());
        for (Notification notification : notifications) {
            Notification present = Objects.requireNonNull(notification, "notification");
            if (!ids.add(present.id())) {
                throw new IllegalArgumentException("duplicate notification id: " + present.id());
            }
            copy.add(present);
        }
        return List.copyOf(copy);
    }
}
