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

import static io.github.dhoard.braxton.widget.RichList.NOTICE_EFFECT;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.widget.RichList.FilterMode;
import io.github.dhoard.braxton.widget.RichList.Item;
import io.github.dhoard.braxton.widget.RichList.Match;
import io.github.dhoard.braxton.widget.RichList.Notice;
import io.github.dhoard.braxton.widget.RichList.Page;
import io.github.dhoard.braxton.widget.RichList.Result;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure list selection, paging, and notice transitions.
 */
final class RichListEditing {
    private final RichListConfig config;
    private final TextInput filterField;
    private final Spinner spinner;

    RichListEditing(RichListConfig config, TextInput filterField, Spinner spinner) {
        this.config = config;
        this.filterField = filterField;
        this.spinner = spinner;
    }

    /**
     * Applies one message. Never performs I/O, never mutates the input state and never swallows.
     */
    Result reduce(RichListModel state, RichListConfig config, RichListMsg message) {
        return switch (message) {
            case RichListMsg.Up up -> moved(state, -1);
            case RichListMsg.Down down -> moved(state, 1);
            case RichListMsg.First first -> new Result(state.withSelectedId(firstVisible(state)), null);
            case RichListMsg.Last last -> new Result(state.withSelectedId(lastVisible(state)), null);
            case RichListMsg.NextPage next -> turned(state, config, 1);
            case RichListMsg.PreviousPage previous -> turned(state, config, -1);
            case RichListMsg.BeginFilter begin -> editing(state);
            case RichListMsg.AcceptFilter accept -> applied(state);
            case RichListMsg.Cancel cancel -> canceled(state);
            case RichListMsg.ClearFilter clear -> cleared(state);
            case RichListMsg.ToggleFilterMode toggle ->
                settled(state.withFilter(state.filter()
                        .withMode(state.filter().mode() == FilterMode.FUZZY ? FilterMode.PREFIX : FilterMode.FUZZY)));
            case RichListMsg.SetFilterMode mode ->
                settled(state.withFilter(state.filter().withMode(mode.value())));
            case RichListMsg.FilterInput input -> typed(state, input.value());
            case RichListMsg.Choose choose -> chosen(state);
            case RichListMsg.ToggleMark toggle -> markToggled(state);
            case RichListMsg.MarkAll all -> markedAll(state);
            case RichListMsg.ClearMarks clear ->
                state.marked().isEmpty() ? settled(state) : settled(state.withMarked(List.of()));
            case RichListMsg.Select select -> settled(selectedTo(state, select.id()));
            case RichListMsg.SetItems items -> settled(state.withItems(items.items()));
            case RichListMsg.InsertItem insert -> inserted(state, insert.index(), insert.item());
            case RichListMsg.ReplaceItem replace -> replaced(state, replace.item());
            case RichListMsg.RemoveItem remove -> removed(state, remove.id());
            case RichListMsg.RowsChanged rows -> settled(state.withRows(rows.rows() - config.reservedRows()));
            case RichListMsg.ToggleTitle title ->
                settled(state.withChrome(
                        state.chrome().withTitle(!state.chrome().title())));
            case RichListMsg.ToggleStatus status ->
                settled(state.withChrome(
                        state.chrome().withStatus(!state.chrome().status())));
            case RichListMsg.TogglePagination pagination ->
                settled(state.withChrome(
                        state.chrome().withPagination(!state.chrome().pagination())));
            case RichListMsg.ToggleHelp help ->
                settled(state.withChrome(state.chrome().withHelp(!state.chrome().help())));
            case RichListMsg.ToggleExpandedHelp expanded ->
                settled(state.withChrome(
                        state.chrome().withExpandedHelp(!state.chrome().expandedHelp())));
            case RichListMsg.ToggleLoading loading -> toggled(state);
            case RichListMsg.SpinnerMessage animation ->
                settled(state.withSpinner(
                        spinner.update(state.spinner(), animation.value()).model()));
            case RichListMsg.Notice notice -> noticed(state, notice.text());
            case RichListMsg.NoticeExpired expired -> expired(state, expired.epoch());
        };
    }

    /**
     * A reduction that changed state without asking the application for anything.
     */
    static Result settled(RichListModel state) {
        return new Result(state, null);
    }

    /**
     * The state selecting one id, leaving the selection alone when the query does not show it.
     */
    static RichListModel selectedTo(RichListModel state, String id) {
        for (Match match : RichListPaging.visible(state)) {
            if (match.id().equals(id)) {
                return state.withSelectedId(id);
            }
        }
        return state;
    }

    /**
     * One visible item up or down, clamped to the visible list.
     */
    static Result moved(RichListModel state, int delta) {
        List<Match> visible = RichListPaging.visible(state);
        if (visible.isEmpty()) {
            return new Result(state, null);
        }
        int selected = Math.max(0, RichListPaging.selectedIndex(visible, state.selectedId()));
        int target = Math.max(0, Math.min(selected + delta, visible.size() - 1));
        return new Result(state.withSelectedId(visible.get(target).id()), null);
    }

    /**
     * One page forward or back, selecting the first item of the page arrived at.
     */
    static Result turned(RichListModel state, RichListConfig config, int delta) {
        List<Match> visible = RichListPaging.visible(state);
        List<Page> pages = RichListPaging.pack(visible, config, RichListPaging.contentRows(state, config));
        if (pages.isEmpty()) {
            return new Result(state, null);
        }
        int current = RichListPaging.selectedPage(visible, pages, state.selectedId());
        int target = Math.max(0, Math.min(current + delta, pages.size() - 1));
        if (target == current) {
            return new Result(state, null);
        }
        return new Result(
                state.withSelectedId(visible.get(pages.get(target).start()).id()), null);
    }

    /**
     * Starts editing the query, remembering what was selected so a cancel can restore it.
     */
    static Result editing(RichListModel state) {
        if (state.filter().editing()) {
            return new Result(state, null);
        }
        return new Result(
                state.withFilter(state.filter().withEditing(true).withPrefilterSelection(state.selectedId())), null);
    }

    /**
     * Applies the live query, which is already what the list shows.
     */
    static Result applied(RichListModel state) {
        if (!state.filter().editing()) {
            return new Result(state, null);
        }
        RichListFilter filter =
                state.filter().withEditing(false).withApplied(state.filter().query());
        return new Result(state.withFilter(filter), null);
    }

    /**
     * Cancels: the edit being typed, then the applied query, then (with nothing left to undo) the
     * list itself. Restoring the remembered selection only moves the selection back when that item
     * is still one the query shows.
     */
    static Result canceled(RichListModel state) {
        if (state.filter().editing()) {
            RichListFilter filter = state.filter()
                    .withField(TextInput.Model.of(state.filter().applied()).withFocus(false))
                    .withPrefilterSelection("");
            return new Result(restored(state.withFilter(filter), state.filter().prefilterSelection()), null);
        }
        if (!RichListMatching.terms(state.filter().query()).isEmpty()) {
            return cleared(state);
        }
        return new Result(state, new RichListOutput.Canceled());
    }

    /**
     * Clears the query, keeping the selection when it survives and choosing the first item otherwise.
     */
    static Result cleared(RichListModel state) {
        if (state.filter().query().isEmpty() && state.filter().applied().isEmpty()) {
            return new Result(state, null);
        }
        RichListFilter filter = state.filter()
                .withField(RichListFilter.blurred())
                .withApplied("")
                .withPrefilterSelection("");
        return new Result(state.withFilter(filter), null);
    }

    /**
     * One message from the composed field, or the accept gesture it reports.
     */
    Result typed(RichListModel state, TextInput.Msg message) {
        if (message instanceof TextInput.Msg.Submit) {
            return applied(state);
        }
        TextInput.Model field =
                filterField.update(state.filter().field(), message).model();
        if (field.equals(state.filter().field())) {
            return new Result(state, null);
        }
        return new Result(state.withFilter(state.filter().withField(field)), null);
    }

    /**
     * Chooses the marked items, or the selected item when nothing is marked.
     *
     * <p>A marked choice is reported as it stands: an item the user marked and a query later hid is
     * still part of the choice they built. Without marks, an item the query no longer shows cannot
     * be chosen, because there is nothing on screen to have chosen.</p>
     */
    static Result chosen(RichListModel state) {
        if (!state.marked().isEmpty()) {
            return new Result(state, new RichListOutput.Chosen(state.markedItems()));
        }
        List<Match> visible = RichListPaging.visible(state);
        int index = RichListPaging.selectedIndex(visible, state.selectedId());
        return index < 0
                ? new Result(state, null)
                : new Result(
                        state,
                        new RichListOutput.Chosen(List.of(visible.get(index).item())));
    }

    /**
     * Marks or unmarks the selected item and steps down to the next one.
     */
    static Result markToggled(RichListModel state) {
        String id = state.selectedId();
        if (id.isEmpty()) {
            return new Result(state, null);
        }
        List<String> marked = new ArrayList<>(state.marked());
        if (!marked.remove(id)) {
            marked.add(id);
        }
        RichListModel next = state.withMarked(marked);
        List<Match> visible = RichListPaging.visible(next);
        int index = RichListPaging.selectedIndex(visible, id);
        return new Result(
                index < 0 || index + 1 >= visible.size()
                        ? next
                        : next.withSelectedId(visible.get(index + 1).id()),
                null);
    }

    /**
     * Marks every item the query shows, keeping the marks already made.
     */
    static Result markedAll(RichListModel state) {
        List<String> marked = new ArrayList<>(state.marked());
        for (Match match : RichListPaging.visible(state)) {
            if (!marked.contains(match.id())) {
                marked.add(match.id());
            }
        }
        return new Result(state.withMarked(marked), null);
    }

    /**
     * Inserts an item into the stored list, keeping the selection.
     */
    static Result inserted(RichListModel state, int index, Item item) {
        List<Item> items = new ArrayList<>(state.items());
        items.add(Math.max(0, Math.min(index, items.size())), item);
        return new Result(state.withItems(items), null);
    }

    /**
     * Replaces the item with the same id, keeping the selection when it survives.
     */
    static Result replaced(RichListModel state, Item item) {
        int index = RichListPaging.itemIndex(state.items(), item.id());
        if (index < 0) {
            return new Result(state, null);
        }
        List<Item> items = new ArrayList<>(state.items());
        items.set(index, item);
        return new Result(state.withItems(items), null);
    }

    /**
     * Removes one item and selects the nearest surviving visible item: the one that took its place
     * in the visible order, or the one before it when the removed item was last.
     */
    static Result removed(RichListModel state, String id) {
        int index = RichListPaging.itemIndex(state.items(), id);
        if (index < 0) {
            return new Result(state, null);
        }
        int position = Math.max(0, RichListPaging.selectedIndex(RichListPaging.visible(state), id));
        List<Item> items = new ArrayList<>(state.items());
        items.remove(index);
        RichListModel reduced = state.withItems(items);
        List<Match> after = RichListPaging.visible(reduced);
        return new Result(
                after.isEmpty()
                        ? reduced
                        : reduced.withSelectedId(
                                after.get(Math.min(position, after.size() - 1)).id()),
                null);
    }

    /**
     * Turns the spinner on or off with the loading chrome that paints it.
     */
    Result toggled(RichListModel state) {
        boolean loading = !state.chrome().loading();
        RichListModel next = state.withChrome(state.chrome().withLoading(loading));
        Spinner.Msg action = loading ? new Spinner.Msg.Start() : new Spinner.Msg.Stop();
        return new Result(
                next.withSpinner(spinner.update(next.spinner(), action).model()), null);
    }

    /**
     * A new transient message with a fresh epoch, or a cleared one when the text is blank.
     */
    static Result noticed(RichListModel state, String text) {
        if (text == null || text.isBlank()) {
            return state.notice().present()
                    ? new Result(state.withNotice(new Notice("", state.notice().epoch() + 1)), null)
                    : new Result(state, null);
        }
        return new Result(state.withNotice(new Notice(text, state.notice().epoch() + 1)), null);
    }

    /**
     * Clears the transient message an expiry was scheduled for, ignoring a stale one.
     */
    static Result expired(RichListModel state, long epoch) {
        if (!state.notice().present() || epoch != state.notice().epoch()) {
            return new Result(state, null);
        }
        return new Result(state.withNotice(new Notice("", state.notice().epoch())), null);
    }

    /**
     * The delay that clears a notice this reduction just set, or nothing when it set none.
     */
    Command<RichListMsg> noticeEffect(RichListModel before, RichListModel after) {
        if (after.notice().epoch() == before.notice().epoch() || !after.notice().present()) {
            return Command.none();
        }
        RichListMsg expiry = new RichListMsg.NoticeExpired(after.notice().epoch());
        return Command.delay(NOTICE_EFFECT, config.noticeLifetime(), expiry, expiry);
    }

    /**
     * The first id the query shows, or empty.
     */
    static String firstVisible(RichListModel state) {
        List<Match> visible = RichListPaging.visible(state);
        return visible.isEmpty() ? "" : visible.get(0).id();
    }

    /**
     * The last id the query shows, or empty.
     */
    static String lastVisible(RichListModel state) {
        List<Match> visible = RichListPaging.visible(state);
        return visible.isEmpty() ? "" : visible.get(visible.size() - 1).id();
    }

    /**
     * The state with a remembered selection restored when that item is still visible.
     */
    static RichListModel restored(RichListModel state, String id) {
        if (id.isEmpty()) {
            return state;
        }
        for (Match match : RichListPaging.visible(state)) {
            if (match.id().equals(id)) {
                return state.withSelectedId(id);
            }
        }
        return state;
    }

    /**
     * The id a selection should carry: the one it names when the query shows it, otherwise the first
     * id the query shows, and otherwise the selection it was given.
     */
    static String revealed(List<Item> items, RichListFilter filter, String selectedId) {
        List<int[]> terms = RichListMatching.terms(filter.query());
        if (terms.isEmpty()) {
            return selectedId;
        }
        String first = "";
        for (Item item : items) {
            if (RichListMatching.found(terms, filter.mode(), item) == null) {
                continue;
            }
            if (first.isEmpty()) {
                first = item.id();
            }
            if (item.id().equals(selectedId)) {
                return selectedId;
            }
        }
        return first.isEmpty() ? selectedId : first;
    }
}
