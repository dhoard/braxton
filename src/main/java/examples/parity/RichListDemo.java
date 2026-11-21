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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Paginator;
import io.github.dhoard.braxton.widget.RichList;
import io.github.dhoard.braxton.widget.RichListConfig;
import io.github.dhoard.braxton.widget.RichListDefaultRenderer;
import io.github.dhoard.braxton.widget.RichListFilter;
import io.github.dhoard.braxton.widget.RichListModel;
import io.github.dhoard.braxton.widget.RichListMsg;
import io.github.dhoard.braxton.widget.RichListOutput;
import io.github.dhoard.braxton.widget.Spinner;
import io.github.dhoard.braxton.widget.TextInputConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 11 parity demo: a large filterable list in one of three item presets.
 *
 * <p>The scene owns the three status rows, the preset, and the counter that names an added item; a
 * mounted {@link RichList} owns the items, the query, the selection and the chrome. That split is
 * the composition the widget is built for: the application sends the list a message - add, remove,
 * a query, a chrome toggle - and reacts to the choice or cancellation the list reports.</p>
 *
 * <p>Three presets paint the same fixture differently, and each one is a different renderer rather
 * than a different list: {@code simple} is one row an item, {@code default} adds a description row,
 * and {@code fancy} paints three rows an item with its own marker for a row the query matched. The
 * fixture is large enough (240 items) that filtering and paging are worth watching, and the status
 * rows report every fact a terminal verifier needs without decoding styling.</p>
 *
 * <p>The keys are split the way the widget documents: the list routes its own gestures while it has
 * focus, and the scene's actions route around the mount, so {@code a} and {@code x} reach the scene
 * while browsing and type their characters while a query is being edited.</p>
 */
public final class RichListDemo {

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style TITLE = Style.NONE.withBold();

    private static final Style DESCRIPTION = Style.NONE.withItalic();

    private static final Style FANCY_MARK = Style.NONE.withBold();

    private static final List<String> TITLES = List.of(
            "Raspberry jam",
            "Hazelnut spread",
            "Bitter greens",
            "Wool socks",
            "Deep sleep",
            "Cats",
            "Plant music",
            "Pour over coffee",
            "Pottery",
            "Shampoo",
            "Table tennis",
            "Milk crates",
            "Afternoon tea",
            "Stickers",
            "Warm light",
            "Gaffer tape",
            "Terrycloth",
            "Noguchi lamps",
            "Clean hair",
            "Slow mornings");

    private static final List<String> DESCRIPTIONS = List.of(
            "On toast, mostly",
            "Better than the sum of its parts",
            "Cools you down",
            "No holes in these",
            "I had this once",
            "Usually",
            "The plants love it too",
            "Takes forever, worth it",
            "Wet clay is a great feeling",
            "Nothing like it",
            "Surprisingly exhausting",
            "Great for packing",
            "Especially the sandwiches",
            "The thicker the vinyl the better",
            "Around 2700 Kelvin",
            "Basically sticky fabric",
            "In other words, towel fabric",
            "Pleasing organic forms",
            "A small luxury",
            "The best part of the day");

    /**
     * The rows the scene paints above the mounted list.
     */
    public static final int STATUS_ROWS = 3;

    /**
     * The items the demo filters and pages: a static fixture, so nothing is read or fetched.
     */
    public static final List<RichList.Item> ITEMS = items(240);

    /**
     * The number of items the fixture holds.
     */
    public static final int ITEM_COUNT = ITEMS.size();

    /**
     * The three ways the demo paints its items.
     */
    public enum Preset {

        /**
         * One row an item: the title only.
         */
        SIMPLE,

        /**
         * Two rows an item: the title and its description.
         */
        DEFAULT,

        /**
         * Three rows an item, painted by the demo's own renderer.
         */
        FANCY
    }

    private RichListDemo() {}

    /**
     * The scene state.
     *
     * @param list the mounted list's own state
     * @param preset how the items are painted
     * @param added how many items the scene has added, so an added id is never reused
     * @param last what the scene or the list last did
     */
    public record Model(RichListModel list, Preset preset, int added, String last) {

        /**
         * Substitutes the default preset for a missing one.
         */
        public Model {
            list = list == null ? RichListModel.of(ITEMS, 24 - STATUS_ROWS) : list;
            preset = preset == null ? Preset.DEFAULT : preset;
            last = last == null ? "none" : last;
        }

        Model withList(RichListModel value) {
            return new Model(value, preset, added, last);
        }

        Model withPreset(Preset value) {
            return new Model(list, value, added, last);
        }

        Model withAdded(int value) {
            return new Model(list, preset, value, last);
        }

        Model withLast(String value) {
            return new Model(list, preset, added, value);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * A message from the mounted list.
         *
         * @param value the list's message
         */
        record List(RichListMsg value) implements Msg {}

        /**
         * An output the mounted list routed to the scene.
         *
         * @param value the list's output
         */
        record Output(RichListOutput value) implements Msg {}

        /**
         * Switches the preset the items are painted with.
         *
         * @param value the preset to show
         */
        record Use(Preset value) implements Msg {}

        /**
         * Inserts one item at the top of the list.
         */
        record Add() implements Msg {}

        /**
         * Removes the selected item, if there is one.
         */
        record Remove() implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions: the ones that change the application's data rather than the list's.
     *
     * <p>They are bubble bindings, so a focused list sees every key first: the gestures it owns
     * never reach this map, and every other gesture does. The map is also what the list paints as
     * its application block, so the help line and these bindings cannot drift apart.</p>
     *
     * @return the scene's actions, in help order
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Items",
                        KeyAction.<Msg>of("add", new Msg.Add(), "a", "add item", "a"),
                        KeyAction.<Msg>of("remove", new Msg.Remove(), "x", "delete item", "x")),
                KeyMap.group(
                        "Preset",
                        KeyAction.<Msg>of("simple", new Msg.Use(Preset.SIMPLE), "1", "simple", "1")
                                .withCompact(false),
                        KeyAction.<Msg>of("default", new Msg.Use(Preset.DEFAULT), "2", "default", "2")
                                .withCompact(false),
                        KeyAction.<Msg>of("fancy", new Msg.Use(Preset.FANCY), "3", "fancy", "3")
                                .withCompact(false)),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
    }

    /**
     * Runs the demo against the owned terminal.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The list definition the scene's current state asks for.
     *
     * <p>The renderer and the chrome the scene advertises follow the preset; everything else is one
     * fixed policy, so the mounted component's identity, lens and route stay the same values on
     * every use.</p>
     *
     * @param model the scene state
     * @return the list definition
     */
    public static RichListConfig config(Model model) {
        return RichListConfig.defaults()
                .withItems(ITEMS)
                .withTitle("Favorite things")
                .withRenderer(renderer(model.preset()))
                .withMode(RichList.FilterMode.FUZZY)
                .withFilterInput(TextInputConfig.defaults().withPrompt("Filter: ", Style.NONE.withBold()))
                .withPagination(Paginator.Appearance.dots())
                .withSpinner(Spinner.Config.preset(Spinner.Preset.DOT))
                .withExtraHelp(keys())
                .withStyles(RichListConfig.Styles.defaults())
                .withRows(24 - STATUS_ROWS)
                .withReservedRows(STATUS_ROWS);
    }

    /**
     * The renderer one preset paints its items with.
     *
     * @param preset the preset to paint
     * @return the renderer
     */
    public static RichList.Renderer renderer(Preset preset) {
        return switch (preset) {
            case SIMPLE -> new RichListDefaultRenderer(null, false, 0);
            case DEFAULT -> RichListDefaultRenderer.defaults();
            case FANCY -> new FancyRenderer();
        };
    }

    /**
     * The items the demo filters and pages.
     *
     * @param count how many items to make
     * @return the items
     */
    public static List<RichList.Item> items(int count) {
        List<RichList.Item> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String title = TITLES.get(index % TITLES.size());
            if (index >= TITLES.size()) {
                title = title + " " + (index / TITLES.size() + 1);
            }
            items.add(new RichList.Item(id(index + 1), title, DESCRIPTIONS.get(index % DESCRIPTIONS.size()), title));
        }
        return List.copyOf(items);
    }

    /**
     * A stable item id for one-based position, so a verifier can name an item.
     */
    private static String id(int number) {
        return number < 10 ? "item-00" + number : number < 100 ? "item-0" + number : "item-" + number;
    }

    /**
     * The scene's own renderer: three rows an item, and a mark on a row the query matched.
     */
    private static final class FancyRenderer implements RichList.Renderer {

        @Override
        public int height(RichList.Item item) {
            return 3;
        }

        @Override
        public List<StyledText> render(RichList.Row row) {
            String mark = row.selected() ? RichList.MARKER : RichList.MARKER_GAP;
            Style title = row.selected() ? TITLE.merge(Style.NONE.withInvert()) : TITLE;
            // The badge is what proves this renderer sees the query's matches: it is painted from
            // the highlight ranges the list hands over, not from a query it re-runs itself.
            String badge = row.highlights().isEmpty() ? RichList.MARKER_GAP : "\u25C6 ";
            List<StyledText> rows = new ArrayList<>(3);
            rows.add(StyledText.concat(
                    StyledText.of(badge, FANCY_MARK),
                    StyledText.of(mark, title),
                    StyledText.of(row.item().title(), title)));
            rows.add(StyledText.of(
                    RichList.MARKER_GAP + RichList.MARKER_GAP + row.item().description(), DESCRIPTION));
            rows.add(StyledText.empty());
            return rows;
        }
    }

    /**
     * The status rows, the mounted list and the scene's own help.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(new Model(null, Preset.DEFAULT, 0, "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted list reduces its own message into the scene's slot.
                case Msg.List value -> mount(model).update(model, value.value());
                case Msg.Output value -> output(model, value.value());
                case Msg.Use value -> {
                    Model changed = model.withPreset(value.value());
                    yield Update.next(changed.withLast("preset-" + name(value.value())));
                }
                case Msg.Add add -> added(model);
                case Msg.Remove remove -> removed(model);
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * The list's own scope: its size subscription and, while shown, its spinner interval.
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return mount(model).subscriptions(model);
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(mount(model).tree(model));
        }

        @Override
        public Element<Msg> view(Model model, ViewContext context) {
            return scene(model, context);
        }

        /**
         * The gestures this scene answers once no widget has taken them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            return keys().bindings();
        }

        /**
         * The scene's rows as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            KeyMap<Msg> keys = keys();
            List<Element<Msg>> rows = new ArrayList<>(STATUS_ROWS + 1);
            // Status rows are one row each however narrow the terminal is: a wrapped diagnostic
            // would push the list's chrome and its items down and move what a verifier asserts.
            rows.add(Elements.fixed(1, Elements.text(fixture(model), STATUS)));
            rows.add(Elements.fixed(1, Elements.text(query(model), STATUS)));
            rows.add(Elements.fixed(1, Elements.text(chrome(model), STATUS)));
            rows.add(mount(model).view(model, context));
            return Elements.column(rows);
        }

        /**
         * The application's own data: what the fixture holds and what is selected in it.
         */
        private static String fixture(Model model) {
            return "preset=" + name(model.preset())
                    + " items=" + model.list().items().size()
                    + " selected=" + orNone(model.list().selectedId())
                    + " added=" + model.added()
                    + " last=" + model.last();
        }

        /**
         * The query and what it shows, from the list's own public projections.
         */
        private static String query(Model model) {
            RichListFilter filter = model.list().filter();
            return "filter=" + (filter.query().isEmpty() ? "-" : filter.query())
                    + " mode=" + filter.mode().name().toLowerCase(Locale.ROOT)
                    + " editing=" + onOff(filter.editing())
                    + " applied=" + (filter.applied().isEmpty() ? "-" : filter.applied())
                    + " matches=" + RichList.visible(model.list()).size()
                    + " page=" + (RichList.pageIndex(model.list(), config(model)) + 1)
                    + "/"
                    + Math.max(1, RichList.pages(model.list(), config(model)).size());
        }

        /**
         * The chrome the list paints and whether its spinner runs.
         */
        private static String chrome(Model model) {
            RichList.Chrome chrome = model.list().chrome();
            return "title=" + onOff(chrome.title())
                    + " status=" + onOff(chrome.status())
                    + " page=" + onOff(chrome.pagination())
                    + " help=" + (chrome.help() ? (chrome.expandedHelp() ? "full" : "compact") : "off")
                    + " spinner=" + onOff(chrome.loading())
                    + " rows=" + model.list().rows()
                    + " content=" + RichList.contentRows(model.list(), config(model));
        }

        /**
         * One item is added at the top, so the list has to keep a selection that moved.
         */
        private Update<Model, Msg, Never> added(Model model) {
            int number = model.added() + 1;
            RichList.Item item =
                    new RichList.Item("added-" + number, "added item " + number, "added by the demo", null);
            Model changed = model.withAdded(number);
            Update<Model, Msg, Never> applied = mount(changed).update(changed, new RichListMsg.InsertItem(0, item));
            return Update.next(applied.model().withLast("added-" + number), applied.command());
        }

        /**
         * The selected item is removed, and the list chooses the nearest surviving item.
         */
        private Update<Model, Msg, Never> removed(Model model) {
            String selected = model.list().selectedId();
            if (selected.isEmpty()) {
                return Update.next(model.withLast("removed-none"));
            }
            Update<Model, Msg, Never> applied = mount(model).update(model, new RichListMsg.RemoveItem(selected));
            return Update.next(applied.model().withLast("removed-" + selected), applied.command());
        }

        /**
         * What the list asked the scene to do: a choice is reported and a notice is dispatched back
         * through the mount, so the notice's own timer is the list's effect and not a second one.
         */
        private Update<Model, Msg, Never> output(Model model, RichListOutput value) {
            return switch (value) {
                case RichListOutput.Chosen chosen -> {
                    Model reported = model.withLast("chose-" + chosen.item().id());
                    yield Update.next(
                            reported,
                            Command.dispatch(new Msg.List(new RichListMsg.Notice(
                                    "chose " + chosen.item().title()))));
                }
                case RichListOutput.Canceled cancelled -> Update.next(model.withLast("canceled"), Command.quit());
            };
        }

        /**
         * One list, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's policy on every use. Nothing else about the
         * mount changes: its identity, its lens and its route are the same values every time.</p>
         */
        private Mount<Model, Msg, Never, RichListModel, RichListMsg, RichListOutput> mount(Model model) {
            return Mount.of(
                    ComponentId.of("list"),
                    new RichList(config(model)),
                    ModelLens.of(Model::list, (Model state, RichListModel child) -> state.withList(child)),
                    Msg.List::new,
                    OutputRoute.toMessage(Msg.Output::new));
        }

        private static String name(Preset preset) {
            return preset.name().toLowerCase(Locale.ROOT);
        }

        private static String onOff(boolean value) {
            return value ? "on" : "off";
        }

        private static String orNone(String value) {
            return value == null || value.isEmpty() ? "none" : value;
        }
    }
}
