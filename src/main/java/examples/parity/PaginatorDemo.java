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
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Paginator;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 06 parity demo: a paged static list with dots or a numeric label.
 *
 * <p>The scene owns the items and the selection; a mounted {@link Paginator} owns the page, the
 * count and the page size, and paints the indicator. That split is the composition a later rich
 * list uses: the application keeps its data, asks the paginator for {@link Paginator.Model#bounds}
 * and slices its own list, and moves the page itself when a selection has to stay visible.</p>
 *
 * <p>Ten items a page. The pager's own gestures move between pages; the scene's gestures move the
 * selection, switch the indicator, toggle wrap-around, and shrink or grow the list by a page so
 * the clamping is visible - down to zero items, where the page count and the bounds are empty and
 * there is nothing to select.</p>
 *
 * <p>Everything the scene reports is on status rows, so a terminal verifier can assert the scene
 * without decoding styling. The scene's own gestures are bubble bindings and the paginator's are
 * its focus element's, so a focused paginator consumes paging keys and every other key reaches the
 * scene.</p>
 */
public final class PaginatorDemo {

    private static final Style STATUS = Style.NONE.withBold();

    /** The items the scene pages over: a static list, so nothing is read or fetched. */
    public static final List<String> ITEMS = items(84);

    /** The size of a page, in items. */
    public static final int PER_PAGE = Paginator.DEFAULT_PER_PAGE;

    /** The rows the scene reserves for items, whatever the current page holds. */
    public static final int ITEM_ROWS = PER_PAGE;

    /** The paginator's own actions, used for routing and for its help line. */
    public static final KeyMap<Paginator.Msg> PAGER_KEYS = Paginator.defaultKeys();

    private PaginatorDemo() {}

    /**
     * The scene state.
     *
     * <p>The canonical constructor keeps the selection valid: it is an index into the pager's item
     * count, clamped to the last item, and absent when there are no items. A count that shrinks
     * therefore never leaves the scene pointing past its own list.</p>
     *
     * @param pager the mounted paginator's own state
     * @param selected the index of the selected item, or -1 when there are no items
     * @param numeric whether the indicator paints the numeric label instead of dots
     * @param wrap whether paging wraps at either end
     * @param last what the scene last did
     */
    public record Model(Paginator.Model pager, int selected, boolean numeric, boolean wrap, String last) {

        public Model {
            pager = pager == null ? Paginator.Model.of(0, PER_PAGE) : pager;
            // The pager's count is a prefix of ITEMS, so a restored model cannot point the pages
            // past the list the scene slices.
            int count = Math.min(pager.itemCount(), ITEMS.size());
            pager = new Paginator.Model(pager.page(), count, pager.perPage(), pager.focused());
            selected = count == 0 ? -1 : Math.max(0, Math.min(selected, count - 1));
            last = last == null ? "none" : last;
        }

        Model withPager(Paginator.Model value) {
            return new Model(value, selected, numeric, wrap, last);
        }

        Model withSelected(int value) {
            return new Model(pager, value, numeric, wrap, last);
        }

        Model withNumeric(boolean value) {
            return new Model(pager, selected, value, wrap, last);
        }

        Model withWrap(boolean value) {
            return new Model(pager, selected, numeric, value, last);
        }

        Model withLast(String value) {
            return new Model(pager, selected, numeric, wrap, value);
        }

        /**
         * The items the current page shows, in paint order.
         *
         * <p>This is the composition the demo exists to show: the scene slices its own list with
         * the paginator's bounds. An empty page slices to an empty list, never to an error.</p>
         *
         * @return the visible items
         */
        public List<String> visibleItems() {
            Paginator.Bounds bounds = pager.bounds();
            return ITEMS.subList(bounds.start(), bounds.end());
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the mounted paginator.
         *
         * @param value the paginator's message
         */
        record Pager(Paginator.Msg value) implements Msg {}

        /**
         * Moves the selection by one item, bringing its page into view.
         *
         * @param delta the direction: -1 for the previous item, 1 for the next
         */
        record Select(int delta) implements Msg {}

        /** Switches the indicator between dots and the numeric label. */
        record ToggleMode() implements Msg {}

        /** Switches wrap-around paging on or off. */
        record ToggleWrap() implements Msg {}

        /** Removes one page's worth of items, down to zero. */
        record RemovePage() implements Msg {}

        /** Restores one page's worth of items, up to the whole list. */
        record AddPage() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>They are bubble bindings, so the focused paginator sees every key first: the paging
     * gestures it owns never reach this map, and every other gesture does.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Paging",
                        KeyAction.<Msg>of("toggle-mode", new Msg.ToggleMode(), "g", "dots/numeric", "g"),
                        KeyAction.<Msg>of("toggle-wrap", new Msg.ToggleWrap(), "w", "wrap", "w"),
                        KeyAction.<Msg>of("remove-page", new Msg.RemovePage(), "r", "remove-items", "r"),
                        KeyAction.<Msg>of("add-page", new Msg.AddPage(), "a", "add-items", "a")),
                KeyMap.group(
                        "Selection",
                        KeyAction.<Msg>of("select-previous", new Msg.Select(-1), "\u2191", "select", "up"),
                        KeyAction.<Msg>of("select-next", new Msg.Select(1), "\u2193", "select", "down")),
                KeyMap.group("General", KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal. */
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
     * The pager definition the scene's current state asks for.
     *
     * <p>The indicator and the wrap policy are the scene's model data, so the mounted component is
     * built from them on every use. Its identity, lens and routes are the same values every time.</p>
     *
     * @param model the scene state
     * @return the paginator definition
     */
    public static Paginator.Config config(Model model) {
        return Paginator.Config.defaults()
                .withPerPage(PER_PAGE)
                .withWrap(model.wrap())
                .withAppearance(model.numeric() ? Paginator.Appearance.numeric() : Paginator.Appearance.dots());
    }

    /**
     * The item labels the scene pages over.
     *
     * @param count how many items to make
     * @return the item labels
     */
    public static List<String> items(int count) {
        List<String> items = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            items.add(index < 10 ? "item-0" + index : "item-" + index);
        }
        return List.copyOf(items);
    }

    /** The paged list scene: status rows, the current page's items, the indicator and the help. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(new Model(Paginator.Model.of(ITEMS.size(), PER_PAGE), 0, false, false, "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted paginator reduces its own message into the scene's slot; the scene's
                // model normalizes the selection against the count it just published.
                case Msg.Pager value -> mount(model).update(model, value.value());
                case Msg.Select move -> selected(model, move.delta());
                case Msg.ToggleMode toggle -> {
                    Model flipped = model.withNumeric(!model.numeric());
                    yield Update.next(flipped.withLast(flipped.numeric() ? "mode-numeric" : "mode-dots"));
                }
                case Msg.ToggleWrap toggle -> {
                    Model flipped = model.withWrap(!model.wrap());
                    yield Update.next(flipped.withLast(flipped.wrap() ? "wrap-on" : "wrap-off"));
                }
                case Msg.RemovePage remove -> count(model, -PER_PAGE);
                case Msg.AddPage add -> count(model, PER_PAGE);
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            List<View<Msg>> rows = new ArrayList<>();
            // Status rows are one row each however narrow the terminal is: a wrapped diagnostic
            // would push the items and the indicator down and move what a verifier can assert.
            rows.add(Views.fixed(1, Views.text(headline(model), STATUS)));
            rows.add(Views.fixed(1, Views.text("last=" + model.last(), STATUS)));
            rows.add(Views.gap(1));
            for (int row = 0; row < ITEM_ROWS; row++) {
                // One row per item as well: a long label is clipped, never wrapped into the next
                // item's row or into the indicator's.
                rows.add(Views.fixed(1, Views.text(itemRow(model, row), STATUS)));
            }
            rows.add(Views.gap(1));
            rows.add(mount(model).view(model, context));
            rows.add(Views.gap(1));
            // The pager's help is its own key map, mapped into the scene's message type: help and
            // routing read one definition, and the scene's actions get a second line.
            rows.add(Help.<Paginator.Msg>view(PAGER_KEYS, false, cols).map(Msg.Pager::new));
            rows.add(Help.view(keys, false, cols));
            return Views.keyed(List.of(), Views.column(rows), keys.bindings());
        }

        /**
         * One paginator, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's policy on every use. Nothing else about the
         * mount changes: its identity, its lens and its route are the same values every time.</p>
         */
        private Mount<Model, Msg, Never, Paginator.Model, Paginator.Msg, Never> mount(Model model) {
            return Mount.of(
                    ComponentId.of("pager"),
                    new Paginator(config(model), PAGER_KEYS),
                    ModelLens.of(Model::pager, (Model state, Paginator.Model child) -> state.withPager(child)),
                    Msg.Pager::new,
                    OutputRoute.toOutput(value -> value));
        }

        /**
         * One deliberate selection move. The page follows the selection because the scene decides
         * it should: the paginator is told where to go, it is not asked to guess.
         */
        private Update<Model, Msg, Never> selected(Model model, int delta) {
            int count = model.pager().itemCount();
            if (count == 0) {
                return Update.next(model.withLast("selected-none"));
            }
            int selected = Math.max(0, Math.min(model.selected() + delta, count - 1));
            Model moved = model.withSelected(selected);
            int page = selected / moved.pager().perPage();
            if (page != moved.pager().page()) {
                moved = mount(moved).update(moved, new Paginator.Msg.GoTo(page)).model();
            }
            return Update.next(moved.withLast("selected-" + selected));
        }

        /** Removes or restores items, leaving the pager to clamp the page it was showing. */
        private Update<Model, Msg, Never> count(Model model, int delta) {
            int count = Math.max(0, Math.min(ITEMS.size(), model.pager().itemCount() + delta));
            Model next = mount(model)
                    .update(model, new Paginator.Msg.ItemCountChanged(count))
                    .model();
            return Update.next(next.withLast("items-" + count));
        }

        /**
         * The first status row: the page, the count, the page size, the half-open bounds, the
         * selection and the two policies a verifier can flip.
         */
        private static String headline(Model model) {
            Paginator.Model pager = model.pager();
            Paginator.Bounds bounds = pager.bounds();
            return "page=" + (pager.hasPages() ? pager.page() + 1 : 0) + "/" + pager.totalPages()
                    + " items=" + pager.itemCount()
                    + " per=" + pager.perPage()
                    + " bounds=[" + bounds.start() + "," + bounds.end() + ")"
                    + " selected=" + (model.selected() < 0 ? "none" : Integer.toString(model.selected()))
                    + " mode=" + (model.numeric() ? "numeric" : "dots")
                    + " wrap=" + (model.wrap() ? "on" : "off");
        }

        /**
         * One item row: the marker the scene owns, then the item the paginator's bounds selected.
         * A row past the page's end is padded, so the indicator stays on the same screen row
         * whether the last page holds ten items or none.
         */
        private static String itemRow(Model model, int row) {
            Paginator.Bounds bounds = model.pager().bounds();
            int index = bounds.start() + row;
            if (index >= bounds.end()) {
                return " ";
            }
            return (index == model.selected() ? "> " : "  ") + ITEMS.get(index);
        }
    }
}
