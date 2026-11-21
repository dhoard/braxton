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

import io.github.dhoard.braxton.widget.RichList.FilterMode;
import io.github.dhoard.braxton.widget.RichList.Found;
import io.github.dhoard.braxton.widget.RichList.Highlight;
import io.github.dhoard.braxton.widget.RichList.Item;
import io.github.dhoard.braxton.widget.RichList.Match;
import io.github.dhoard.braxton.widget.RichList.Page;
import io.github.dhoard.braxton.widget.RichList.Renderer;
import io.github.dhoard.braxton.widget.RichList.Resolved;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Derived list pages, selection indices, and chrome budgets.
 */
final class RichListPaging {

    /**
     * The items one state shows, in paint order, with the ranges of each title the query matched.
     *
     * <p>A blank query shows every item in item order and highlights nothing. {@link
     * FilterMode#PREFIX} keeps item order; {@link FilterMode#FUZZY} sorts by the shortest matched
     * span, then the earliest match start, then item order, so the ranking is total and stable.</p>
     *
     * <p>Matching is a linear scan of the item titles, which is why the list repaints only the page
     * it shows: a query costs the same whether the list is one page long or a thousand, and no
     * hidden item is ever rendered.</p>
     *
     * @param state the state to resolve; required
     * @return the visible items, never null
     */
    public static List<Match> visible(RichListModel state) {
        Objects.requireNonNull(state, "state");
        List<int[]> terms = RichListMatching.terms(state.filter().query());
        if (terms.isEmpty()) {
            List<Match> all = new ArrayList<>(state.items().size());
            for (Item item : state.items()) {
                all.add(new Match(item, List.of()));
            }
            return List.copyOf(all);
        }
        List<Found> results = new ArrayList<>(state.items().size());
        List<Item> matched = new ArrayList<>(state.items().size());
        for (Item item : state.items()) {
            Found result = RichListMatching.found(terms, state.filter().mode(), item);
            if (result == null) {
                continue;
            }
            results.add(result);
            matched.add(item);
        }
        List<Match> ordered = new ArrayList<>(matched.size());
        if (state.filter().mode() == FilterMode.FUZZY) {
            // Sorted by the three keys, with the arrival order as the last one: a comparison that
            // never returns zero makes the ranking total, so equal spans cannot be reordered by the
            // sort implementation.
            Integer[] order = new Integer[matched.size()];
            for (int index = 0; index < order.length; index++) {
                order[index] = index;
            }
            Arrays.sort(order, (left, right) -> {
                Found a = results.get(left);
                Found b = results.get(right);
                int bySpan = Integer.compare(a.span(), b.span());
                if (bySpan != 0) {
                    return bySpan;
                }
                int byFirst = Integer.compare(a.first(), b.first());
                return byFirst != 0 ? byFirst : Integer.compare(left, right);
            });
            for (Integer index : order) {
                ordered.add(new Match(matched.get(index), results.get(index).highlights()));
            }
            return List.copyOf(ordered);
        }
        for (int index = 0; index < matched.size(); index++) {
            ordered.add(new Match(matched.get(index), results.get(index).highlights()));
        }
        return List.copyOf(ordered);
    }

    /**
     * The pages one state is packed into.
     *
     * <p>Items keep their order and whole items fill a page until the content rows are used up, so a
     * tall item simply occupies more of its page. One item is always placed on a page, even when it
     * is taller than the whole content: a page loop that could not advance would not terminate, and
     * an item nobody can reach is worse than one that is clipped.</p>
     *
     * @param state the state to pack; required
     * @param config the definition whose renderer measures the items; null is {@link RichListConfig#defaults()}
     * @return the pages, empty when no item is visible
     */
    public static List<Page> pages(RichListModel state, RichListConfig config) {
        Objects.requireNonNull(state, "state");
        RichListConfig opts = config == null ? RichListConfig.defaults() : config;
        return pack(visible(state), opts, contentRows(state, opts));
    }

    /**
     * The index of the page the selection is on.
     *
     * <p>The page is derived from the selection rather than stored, so a query, a resize or a
     * changed renderer can repack the pages without the list ever pointing at a page that no longer
     * exists.</p>
     *
     * @param state the state to inspect; required
     * @param config the definition; null is {@link RichListConfig#defaults()}
     * @return the zero-based page index, zero when no item is visible
     */
    public static int pageIndex(RichListModel state, RichListConfig config) {
        Objects.requireNonNull(state, "state");
        List<Match> visible = visible(state);
        RichListConfig opts = config == null ? RichListConfig.defaults() : config;
        return selectedPage(visible, pack(visible, opts, contentRows(state, opts)), state.selectedId());
    }

    /**
     * The rows the list's chrome occupies, whatever its items are.
     *
     * @param state the state to inspect; required
     * @param config the definition whose extra help is counted; null is {@link RichListConfig#defaults()}
     * @return the chrome rows
     */
    public static int chromeRows(RichListModel state, RichListConfig config) {
        Objects.requireNonNull(state, "state");
        int rows = RichList.titleVisible(state) ? 1 : 0;
        rows += state.chrome().status() ? 1 : 0;
        rows += state.chrome().pagination() ? 1 : 0;
        return rows + RichList.helpRows(state, config);
    }

    /**
     * The rows left for items after the chrome the state asks for.
     *
     * @param state the state to inspect; required
     * @param config the definition; null is {@link RichListConfig#defaults()}
     * @return the content rows, never negative
     */
    public static int contentRows(RichListModel state, RichListConfig config) {
        Objects.requireNonNull(state, "state");
        return Math.max(0, state.rows() - chromeRows(state, config));
    }

    /**
     * The pager state that paints this list's indicator.
     *
     * <p>The indicator is the composed {@link Paginator}, and it describes pages, not items: the
     * pager is given one item per page so that its own page count is the list's page count and its
     * page index is the page the selection is on.</p>
     *
     * @param state the state to inspect; required
     * @param config the definition; null is {@link RichListConfig#defaults()}
     * @return the pager state
     */
    public static Paginator.Model pagination(RichListModel state, RichListConfig config) {
        Objects.requireNonNull(state, "state");
        List<Match> visible = visible(state);
        RichListConfig opts = config == null ? RichListConfig.defaults() : config;
        List<Page> pages = pack(visible, opts, contentRows(state, opts));
        return new Paginator.Model(selectedPage(visible, pages, state.selectedId()), pages.size(), 1, false);
    }

    /**
     * The ranges of one text a query matches, for a renderer that paints its own item.
     *
     * <p>This is the same matcher filtering uses. The ranges are widened to whole grapheme clusters
     * and are offsets into {@code text} as given. A query that does not match produces no ranges,
     * which is what an item that matched only through its filter text paints.</p>
     *
     * @param query the query; null or blank matches everything
     * @param text the text to highlight; null is empty
     * @param mode how the query is matched; null is {@link FilterMode#FUZZY}
     * @return the highlighted ranges, in order
     */
    public static List<Highlight> matchHighlights(String query, String text, FilterMode mode) {
        List<int[]> terms = RichListMatching.terms(query);
        if (terms.isEmpty() || text == null || text.isEmpty()) {
            return List.of();
        }
        List<int[]> ranges = RichListMatching.codePointRanges(
                terms, RichListMatching.folded(text), mode == null ? FilterMode.FUZZY : mode);
        return ranges == null ? List.of() : RichListMatching.highlights(ranges, text);
    }

    /**
     * The rows one item occupies, never fewer than one.
     */
    static int height(Renderer renderer, Item item) {
        return Math.max(1, renderer.height(item));
    }

    /**
     * Packs visible items into pages of whole items that fit the budget.
     */
    static List<Page> pack(List<Match> visible, RichListConfig config, int budget) {
        if (visible.isEmpty()) {
            return List.of();
        }
        Renderer renderer = config.renderer();
        List<Page> pages = new ArrayList<>();
        int start = 0;
        while (start < visible.size()) {
            long used = 0;
            int end = start;
            while (end < visible.size()) {
                long rows = height(renderer, visible.get(end).item()) + RichList.headerRows(visible, start, end);
                if (end > start && used + rows > budget) {
                    break;
                }
                used += rows;
                end++;
            }
            pages.add(new Page(start, end));
            start = end;
        }
        return List.copyOf(pages);
    }

    /**
     * The index of one id in a visible list, or -1.
     */
    static int selectedIndex(List<Match> visible, String id) {
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The page one visible index is on, or zero when there are no pages.
     */
    static int selectedPage(List<Match> visible, List<Page> pages, String id) {
        int selected = selectedIndex(visible, id);
        for (int index = 0; selected >= 0 && index < pages.size(); index++) {
            if (pages.get(index).contains(selected)) {
                return index;
            }
        }
        return 0;
    }

    /**
     * The page one painted resolution shows.
     */
    static int pageOf(Resolved resolved) {
        for (int index = 0; index < resolved.pages().size(); index++) {
            if (resolved.pages().get(index).contains(resolved.selected())) {
                return index;
            }
        }
        return 0;
    }

    /**
     * The index of one id in an item list, or -1.
     */
    static int itemIndex(List<Item> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }
}
