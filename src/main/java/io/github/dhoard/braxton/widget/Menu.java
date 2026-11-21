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
import java.util.function.Consumer;

/**
 * A vertical menu of items the user highlights and chooses.
 *
 * <p>A menu owns one fact - which entry is highlighted - and answers the two questions a menu is
 * asked: what to paint, and what the user chose. The entries are the application's data, an item's
 * identity is stable, and a choice is reported as an {@link Output.Chosen} carrying the item rather
 * than its index, so an application that reorders its menu cannot be handed the wrong item.</p>
 *
 * <h2>Entries</h2>
 *
 * <p>A menu is a list of {@link Entry} values: an {@link Item} with a label, an optional hint
 * painted at the right edge, and whether it can be chosen, or a {@link Separator} that groups the
 * items around it. The highlight is never on a separator or on an item that cannot be chosen, and a
 * menu whose entries are all unselectable has no highlight at all - which is also when it takes no
 * turn in the focus walk.</p>
 *
 * <p>The model keeps the entries, the highlighted id, focus, and the window: the first row it paints
 * and how many rows it may paint. A window always shows the highlight ({@link RowWindow}), so a menu
 * taller than the rows it is given scrolls with the highlight instead of losing it, and a menu given
 * more rows than it has entries paints only the entries it has.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>{@code up} and {@code down} move the highlight past separators and unselectable items,
 * {@code home} and {@code end} go to the first and last choosable item, {@code enter} chooses what
 * is highlighted, and {@code esc} asks the application to dismiss the menu. The gestures are a
 * {@link KeyMap} - {@link #defaultKeys()} defines them and {@link #keys()} is what the widget routes,
 * so {@link Help} describes what actually fires.</p>
 *
 * <p>A pointer press on a choosable item chooses it, which is what a menu does: the click <em>is</em>
 * the choice, so no second press is needed. A press on a separator or on an item that cannot be
 * chosen chooses nothing.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>An item's label is the theme's {@code TEXT}, its hint {@code MUTED}, and an item that cannot be
 * chosen {@code DISABLED}. The highlighted row is filled with {@code SELECTED}, and a focused menu
 * merges {@code FOCUS} into the label and hint it paints over that fill, the rule {@link Tree} rows
 * follow. The hint is right-aligned while the row is wide enough to hold the label, one separating
 * column and the hint; a narrower row drops the hint rather than crowding the label, and a label
 * wider than the row is clipped rather than wrapped.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A menu is one object per placed menu, held across frames the way a component is, because the
 * router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#menu(Menu, Menu.Model, java.util.function.Function)
 */
public final class Menu implements Component<Menu.Model, Menu.Msg, Menu.Output>, Interactive<Menu.Model, Menu.Msg> {

    /**
     * The rows a menu paints when it is initialized without a height of its own.
     */
    public static final int DEFAULT_ROWS = 10;

    /**
     * The glyph a {@link Separator} paints across its row.
     */
    public static final String SEPARATOR = "\u2500";

    private final Config config;

    /**
     * A menu with its default configuration.
     *
     * @param entries the entries, top to bottom
     */
    public Menu(List<Entry> entries) {
        this(Config.of(entries));
    }

    /**
     * A menu over its configuration.
     *
     * @param config the entries and the window; required
     */
    public Menu(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One line of a menu.
     */
    public sealed interface Entry {

        /**
         * The id this entry would be chosen by, or empty for an entry nothing can choose.
         *
         * @return the id this entry would be chosen by, or empty for an entry nothing can choose
         */
        String id();
    }

    /**
     * A choosable line: a label, an optional hint painted at the right edge, and whether it can be
     * chosen.
     *
     * @param id the identity, unique across the menu; never blank
     * @param label the label painted on the row; null is empty
     * @param hint the hint painted at the right edge, such as an accelerator; null or empty for none
     * @param enabled whether the item can be chosen
     */
    public record Item(String id, StyledText label, StyledText hint, boolean enabled) implements Entry {

        /**
         * Rejects an item without an id.
         */
        public Item {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("item id is required");
            }
            label = label == null ? StyledText.empty() : label;
            hint = hint == null ? StyledText.empty() : hint;
        }

        /**
         * An item that can be chosen.
         *
         * @param id the identity; never blank
         * @param label the plain label; null is empty
         * @return the item
         */
        public static Item of(String id, String label) {
            return new Item(id, StyledText.plain(label), StyledText.empty(), true);
        }

        /**
         * An item with a hint.
         *
         * @param id the identity; never blank
         * @param label the plain label; null is empty
         * @param hint the plain hint; null or empty for none
         * @return the item
         */
        public static Item of(String id, String label, String hint) {
            return new Item(id, StyledText.plain(label), StyledText.plain(hint), true);
        }

        /**
         * This item with another label.
         *
         * @param value the label; null is empty
         * @return the item
         */
        public Item withLabel(StyledText value) {
            return new Item(id, value, hint, enabled);
        }

        /**
         * This item with another hint.
         *
         * @param value the hint; null or empty for none
         * @return the item
         */
        public Item withHint(StyledText value) {
            return new Item(id, label, value, enabled);
        }

        /**
         * This item able or unable to be chosen.
         *
         * @param value whether the item can be chosen
         * @return the item
         */
        public Item withEnabled(boolean value) {
            return new Item(id, label, hint, value);
        }
    }

    /**
     * A line that groups the items around it and cannot be chosen.
     *
     * @param id an identity for the separator, so two menus can be compared; never blank
     */
    public record Separator(String id) implements Entry {

        /**
         * Substitutes the default id and rejects a blank one.
         */
        public Separator {
            id = id == null || id.isBlank() ? "-" : id;
        }

        /**
         * A separator with the default identity.
         */
        public Separator() {
            this("-");
        }
    }

    /**
     * One painted row.
     *
     * @param entry the entry the row paints
     * @param highlighted whether this row is the highlight
     * @param focused whether the menu owns focus
     */
    public record Row(Entry entry, boolean highlighted, boolean focused) {

        /**
         * The entry's id, which names a separator as well as an item.
         *
         * @return the entry's id, which names a separator as well as an item
         */
        public String id() {
            return entry.id();
        }

        /**
         * Whether the row can be chosen.
         *
         * @return {@code true} if the row can be chosen; otherwise {@code false}
         */
        public boolean selectable() {
            return entry instanceof Item item && item.enabled();
        }
    }

    /**
     * Immutable menu state.
     *
     * <p>The canonical constructor keeps the highlight on a choosable item and keeps the window over
     * it, so a model the application builds obeys the same rules the reductions do.</p>
     *
     * @param entries the entries, top to bottom
     * @param highlighted the id of the highlighted item, or empty when nothing can be highlighted
     * @param focused whether the menu owns focus
     * @param offset the first visible row; clamped into the entries
     * @param rows the rows the window may paint; never negative
     */
    public record Model(List<Entry> entries, String highlighted, boolean focused, int offset, int rows) {

        /**
         * Normalizes the entries, resolves the highlight and admits the window.
         */
        public Model {
            entries = copy(entries);
            rows = Math.max(0, rows);
            highlighted = resolve(entries, highlighted);
            offset = RowWindow.admit(indexOf(entries, highlighted), entries.size(), Math.max(0, offset), rows);
        }

        /**
         * A menu showing its first choosable item, focused.
         *
         * @param entries the entries
         * @return the model
         */
        public static Model of(List<Entry> entries) {
            return new Model(entries, "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This menu with another highlight, resolved as the constructor resolves it.
         *
         * @param value the id to highlight; null or an unselectable id falls to the first item
         * @return the new model
         */
        public Model withHighlight(String value) {
            return new Model(entries, value, focused, offset, rows);
        }

        /**
         * The same menu, focused or not.
         *
         * @param value whether the menu owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(entries, highlighted, value, offset, rows);
        }

        /**
         * This menu with a window of a different height.
         *
         * @param value the rows the window may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(entries, highlighted, focused, offset, value);
        }

        /**
         * The highlighted item, or null when nothing can be highlighted.
         *
         * @return the highlighted item, or null when nothing can be highlighted
         */
        public Item highlightedItem() {
            return entries.stream()
                    .filter(entry -> entry instanceof Item && entry.id().equals(highlighted))
                    .map(entry -> (Item) entry)
                    .findFirst()
                    .orElse(null);
        }

        /**
         * The rows this menu shows, top to bottom.
         *
         * @return the visible rows
         */
        public List<Row> visibleRows() {
            List<Row> rows = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                rows.add(new Row(entry, entry.id().equals(highlighted), focused));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * The menu's messages.
     */
    public sealed interface Msg {

        /**
         * Highlights the previous choosable item.
         */
        record Up() implements Msg {}

        /**
         * Highlights the next choosable item.
         */
        record Down() implements Msg {}

        /**
         * Highlights the first choosable item.
         */
        record Home() implements Msg {}

        /**
         * Highlights the last choosable item.
         */
        record End() implements Msg {}

        /**
         * Chooses the highlighted item.
         */
        record Activate() implements Msg {}

        /**
         * Chooses an item by id, which is what a pointer press does.
         *
         * @param id the item to choose; a separator, an item that cannot be chosen, and an unknown
         *     id choose nothing
         */
        record Choose(String id) implements Msg {}

        /**
         * Asks the application to dismiss the menu.
         */
        record Cancel() implements Msg {}

        /**
         * The window is a different height.
         *
         * @param rows the rows the window may paint; never negative
         */
        record RowsChanged(int rows) implements Msg {}

        /**
         * The menu now holds other entries.
         *
         * @param entries the new entries; null is empty
         */
        record Replace(List<Entry> entries) implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * An item was chosen.
         *
         * @param id the item's id
         * @param item the item itself
         */
        record Chosen(String id, Item item) implements Output {}

        /**
         * The user asked to dismiss the menu.
         */
        record Canceled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a menu is configured.
     *
     * @param entries the entries, top to bottom
     * @param rows the rows the window paints when no message names a height; never negative
     */
    public record Config(List<Entry> entries, int rows) {

        /**
         * Substitutes the defaults and rejects a negative height.
         */
        public Config {
            entries = copy(entries);
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
        }

        /**
         * A configuration over its entries, with the default height.
         *
         * @param entries the entries
         * @return the configuration
         */
        public static Config of(List<Entry> entries) {
            return new Config(entries, DEFAULT_ROWS);
        }

        /**
         * This configuration over other entries.
         *
         * @param value the entries
         * @return the new configuration
         */
        public Config withEntries(List<Entry> value) {
            return new Config(value, rows);
        }

        /**
         * This configuration whose window paints a different height.
         *
         * @param value the rows; never negative
         * @return the new configuration
         */
        public Config withRows(int value) {
            return new Config(entries, value);
        }
    }

    /**
     * How this menu is configured.
     *
     * @return how this menu is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The menu's element: its painted rows, and the menu itself answering for them.
     *
     * @param model the state to paint; required
     * @return the element, emitting this menu's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation, choosing, and dismissing.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "move up", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "move down", "down"),
                        KeyAction.<Msg>of("home", new Msg.Home(), "home", "first item", "home"),
                        KeyAction.<Msg>of("end", new Msg.End(), "end", "last item", "end")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("choose", new Msg.Activate(), "enter", "choose", "enter", "alt+enter"),
                        KeyAction.<Msg>of("dismiss", new Msg.Cancel(), "esc", "dismiss", "esc", "escape")));
    }

    /**
     * The actions this menu handles.
     *
     * @return the actions this menu handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        Model state = new Model(config.entries(), "", true, 0, config.rows());
        return Update.next(state);
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
     * The menu's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
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
     * A menu with nothing to choose answers nothing, so it takes no turn in the focus walk.
     */
    @Override
    public boolean canFocus(Model model) {
        return indexOf(model.entries(), model.highlighted()) >= 0;
    }

    /**
     * A press on a choosable row chooses it; a press anywhere else chooses nothing.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        int painted = Math.max(0, Math.min(model.rows(), model.entries().size() - model.offset()));
        if (local.row() < 0 || local.row() >= painted) {
            return;
        }
        Row row = model.visibleRows().get(model.offset() + local.row());
        if (row.selectable()) {
            emit.accept(new Msg.Choose(row.id()));
        }
    }

    /**
     * The painted rows, with the menu itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        MenuRow<Msg> painter = new MenuRow<>();
        List<Row> rows = model.visibleRows();
        int first = Math.max(0, model.offset());
        int last = Math.min(rows.size(), first + model.rows());
        List<Element<Msg>> lines = new ArrayList<>(Math.max(0, last - first));
        for (int index = first; index < last; index++) {
            lines.add(new Element.Leaf<Row, Msg>(painter, rows.get(index), Sizing.fixed(1)));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Up up -> new Result(step(state, -1), null);
            case Msg.Down down -> new Result(step(state, 1), null);
            case Msg.Home home -> new Result(extreme(state, true), null);
            case Msg.End end -> new Result(extreme(state, false), null);
            case Msg.Activate activate -> choose(state, state.highlighted());
            case Msg.Choose choose -> choose(state, choose.id());
            case Msg.Cancel cancel -> new Result(state, new Output.Canceled());
            case Msg.RowsChanged changed -> new Result(state.withRows(changed.rows()), null);
            case Msg.Replace replaced ->
                new Result(
                        new Model(
                                replaced.entries(), state.highlighted(), state.focused(), state.offset(), state.rows()),
                        null);
        };
    }

    /**
     * The highlight moved a number of choosable rows, wrapping at neither end.
     */
    private static Model step(Model state, int delta) {
        int current = indexOf(state.entries(), state.highlighted());
        if (current < 0) {
            return state;
        }
        int target = current;
        int remaining = Math.abs(delta);
        int direction = delta < 0 ? -1 : 1;
        while (remaining > 0) {
            int next = selectableAt(state.entries(), target + direction, direction);
            if (next < 0) {
                break;
            }
            target = next;
            remaining--;
        }
        return target == current
                ? state
                : state.withHighlight(state.entries().get(target).id());
    }

    /**
     * The first or the last choosable row.
     */
    private static Model extreme(Model state, boolean first) {
        int target = selectableAt(state.entries(), first ? 0 : state.entries().size() - 1, first ? 1 : -1);
        if (target < 0) {
            return state;
        }
        String id = state.entries().get(target).id();
        return id.equals(state.highlighted()) ? state : state.withHighlight(id);
    }

    /**
     * A choice by id: an item that is enabled and present is reported, anything else is not.
     */
    private static Result choose(Model state, String id) {
        Item item = highlightedItem(state.entries(), id);
        if (item == null || !item.enabled()) {
            return new Result(state, null);
        }
        return new Result(state, new Output.Chosen(item.id(), item));
    }

    /**
     * The nearest choosable row from {@code from} in {@code direction}, or -1 when there is none.
     */
    private static int selectableAt(List<Entry> entries, int from, int direction) {
        for (int index = from; index >= 0 && index < entries.size(); index += direction) {
            if (selectable(entries.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Whether an entry can be chosen.
     */
    private static boolean selectable(Entry entry) {
        return entry instanceof Item item && item.enabled();
    }

    /**
     * The item with an id, or null.
     */
    private static Item highlightedItem(List<Entry> entries, String id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry instanceof Item item && item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * The row of an entry, or -1.
     */
    private static int indexOf(List<Entry> entries, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (selectable(entries.get(index)) && entries.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The highlight a list of entries admits: the id when it can be chosen, the first item otherwise.
     */
    private static String resolve(List<Entry> entries, String highlighted) {
        int index = indexOf(entries, highlighted);
        if (index >= 0) {
            return entries.get(index).id();
        }
        int first = selectableAt(entries, 0, 1);
        return first < 0 ? "" : entries.get(first).id();
    }

    /**
     * A menu's entries, as an immutable list with distinct ids.
     */
    private static List<Entry> copy(List<Entry> entries) {
        if (entries == null) {
            return List.of();
        }
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            copy.add(Objects.requireNonNull(entry, "entry"));
        }
        return List.copyOf(copy);
    }
}
