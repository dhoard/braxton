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

import static io.github.dhoard.braxton.widget.RichList.DEFAULT_ROWS;

import io.github.dhoard.braxton.widget.RichList.Chrome;
import io.github.dhoard.braxton.widget.RichList.FilterMode;
import io.github.dhoard.braxton.widget.RichList.Item;
import io.github.dhoard.braxton.widget.RichList.Notice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable list state.
 *
 * <p>Every component is a value the application can read and replace. The canonical constructor
 * keeps the selection naming an item: an empty selection, or one that names no item at all,
 * becomes the first item, and an empty list has no selection. Filtering, paging and painting are
 * pure functions of this value, so two renders of one model are the same cells and a message
 * that changes nothing can return the instance it was given.</p>
 *
 * @param items every item, in item order
 * @param selectedId the id of the selected item, or empty when there are no items
 * @param filter the query, its applied value and its mode
 * @param chrome which chrome is painted
 * @param rows the rows the whole list may paint, chrome included; never negative
 * @param spinner the composed spinner's state
 * @param notice the transient message and its epoch
 * @param focused whether the list owns focus
 * @param marked the ids the user marked, in the order they were marked
 */
public record RichListModel(
        List<Item> items,
        String selectedId,
        RichListFilter filter,
        Chrome chrome,
        int rows,
        Spinner.Model spinner,
        Notice notice,
        boolean focused,
        List<String> marked) {

    /**
     * Checks the ids, prepares the selection and substitutes defaults for missing values.
     */
    public RichListModel {
        items = items == null ? List.of() : List.copyOf(items);
        Set<String> ids = new HashSet<>(items.size() * 2);
        for (Item item : items) {
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("duplicate item id: " + item.id());
            }
        }
        filter = filter == null ? RichListFilter.of(FilterMode.FUZZY) : filter;
        // The selection names an item the query shows: an id that is absent (a message removed
        // the item) cannot be left dangling, and an id the query hides falls to the first item
        // it shows. A query that hides everything keeps the selection it had, so narrowing and
        // widening a query is reversible and a cancel can restore it.
        selectedId = selectedId == null ? "" : selectedId;
        if (items.isEmpty()) {
            selectedId = "";
        } else if (!ids.contains(selectedId)) {
            selectedId = RichListEditing.revealed(items, filter, items.get(0).id());
        } else {
            selectedId = RichListEditing.revealed(items, filter, selectedId);
        }
        chrome = chrome == null ? Chrome.defaults() : chrome;
        rows = Math.max(0, rows);
        spinner = spinner == null ? Spinner.Model.stopped() : spinner;
        notice = notice == null ? Notice.none() : notice;
        // A mark claims an item that exists. Marks are kept in the order they were made, so a
        // multiple choice is reported in the order the user built it, and the first occurrence
        // of an id is the one that survives.
        List<String> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : marked == null ? List.<String>of() : marked) {
            if (id != null && ids.contains(id) && seen.add(id)) {
                kept.add(id);
            }
        }
        marked = List.copyOf(kept);
    }

    /**
     * The same state with nothing marked.
     *
     * @param items the items the list holds
     * @param selectedId the selected id
     * @param filter the filter
     * @param chrome the chrome
     * @param rows the height
     * @param spinner the spinner state
     * @param notice the transient message
     * @param focused whether the list owns focus
     */
    public RichListModel(
            List<Item> items,
            String selectedId,
            RichListFilter filter,
            Chrome chrome,
            int rows,
            Spinner.Model spinner,
            Notice notice,
            boolean focused) {
        this(items, selectedId, filter, chrome, rows, spinner, notice, focused, List.of());
    }

    /**
     * A list of items showing the first of them, focused, with the default filter and chrome.
     *
     * @param items the items; null is empty
     * @return the starting state
     */
    public static RichListModel of(List<Item> items) {
        return of(items, DEFAULT_ROWS);
    }

    /**
     * A list of items showing the first of them, focused, with the default filter and chrome.
     *
     * @param items the items; null is empty
     * @param rows the rows the list may paint; negative is zero
     * @return the starting state
     */
    public static RichListModel of(List<Item> items, int rows) {
        return new RichListModel(
                items,
                "",
                RichListFilter.of(FilterMode.FUZZY),
                Chrome.defaults(),
                rows,
                Spinner.Model.stopped(),
                Notice.none(),
                true);
    }

    /**
     * The same state with a different focus.
     *
     * @param value whether the list owns focus
     * @return this instance when the focus did not change
     */
    public RichListModel withFocus(boolean value) {
        return value == focused
                ? this
                : new RichListModel(items, selectedId, filter, chrome, rows, spinner, notice, value, marked);
    }

    /**
     * The same state with a different item list. A selection the new list does not hold becomes
     * its first item.
     *
     * @param value the items; null is empty
     * @return the new state
     */
    public RichListModel withItems(List<Item> value) {
        return new RichListModel(value, selectedId, filter, chrome, rows, spinner, notice, focused, marked);
    }

    /**
     * The same state with a different selection.
     *
     * @param value the id to select; null or an absent id becomes the first item
     * @return the new state
     */
    public RichListModel withSelectedId(String value) {
        return new RichListModel(items, value, filter, chrome, rows, spinner, notice, focused, marked);
    }

    /**
     * The same state with a different filter.
     *
     * @param value the new filter; null is the default filter
     * @return the new state
     */
    public RichListModel withFilter(RichListFilter value) {
        return new RichListModel(items, selectedId, value, chrome, rows, spinner, notice, focused, marked);
    }

    /**
     * The same state with different chrome.
     *
     * @param value the new chrome; null is the default chrome
     * @return the new state
     */
    public RichListModel withChrome(Chrome value) {
        return new RichListModel(items, selectedId, filter, value, rows, spinner, notice, focused, marked);
    }

    /**
     * The same state with a different height.
     *
     * @param value the rows the list may paint; negative is zero
     * @return this instance when the height did not change
     */
    public RichListModel withRows(int value) {
        int height = Math.max(0, value);
        return height == rows
                ? this
                : new RichListModel(items, selectedId, filter, chrome, height, spinner, notice, focused, marked);
    }

    /**
     * The same state with a different spinner.
     *
     * @param value the new spinner state; null is a stopped spinner
     * @return this instance when the state did not change
     */
    public RichListModel withSpinner(Spinner.Model value) {
        Spinner.Model next = value == null ? Spinner.Model.stopped() : value;
        return next.equals(spinner)
                ? this
                : new RichListModel(items, selectedId, filter, chrome, rows, next, notice, focused, marked);
    }

    /**
     * The same state with a different transient message.
     *
     * @param value the new notice; null clears it
     * @return this instance when the notice did not change
     */
    public RichListModel withNotice(Notice value) {
        Notice next = value == null ? Notice.none() : value;
        return next.equals(notice)
                ? this
                : new RichListModel(items, selectedId, filter, chrome, rows, spinner, next, focused, marked);
    }

    /**
     * The item the selection names.
     *
     * @return the selected item, or empty when there are no items
     */
    public Optional<Item> selectedItem() {
        for (Item item : items) {
            if (item.id().equals(selectedId)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /**
     * The same state with a different set of marks.
     *
     * <p>Marks that name no item are dropped, so a state built from a stale list is repaired
     * where it is built rather than painted as a mark on nothing.</p>
     *
     * @param value the ids to mark, in the order they were marked; null clears every mark
     * @return this instance when the marks did not change, otherwise the new state
     */
    public RichListModel withMarked(List<String> value) {
        List<String> next = value == null ? List.of() : List.copyOf(value);
        return next.equals(marked)
                ? this
                : new RichListModel(items, selectedId, filter, chrome, rows, spinner, notice, focused, next);
    }

    /**
     * Whether one item is marked.
     *
     * @param id the id to test
     * @return true when the item is marked
     */
    public boolean isMarked(String id) {
        return id != null && marked.contains(id);
    }

    /**
     * The marked items in the list's own order.
     *
     * @return the marked items, in the order the list holds them
     */
    public List<Item> markedItems() {
        List<Item> chosen = new ArrayList<>(marked.size());
        for (Item item : items) {
            if (marked.contains(item.id())) {
                chosen.add(item);
            }
        }
        return List.copyOf(chosen);
    }
}
