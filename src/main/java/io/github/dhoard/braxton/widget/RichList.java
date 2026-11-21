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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
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
import io.github.dhoard.braxton.widget.RichList.Notice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * A filterable, paged list with stable item identities, groups, and multiple selection.
 *
 * <p>Pure transitions produce immutable {@link RichListModel} values and {@link
 * RichListOutput.Chosen} or {@link RichListOutput.Canceled} outputs. The application performs any
 * resulting I/O. Item ids must be nonblank and unique. Selection uses an id and survives filtering,
 * reordering, and page repacking; removal chooses the nearest surviving visible item, and an
 * invalid initial id falls back to the first item.</p>
 *
 * <p>The filter composes {@link TextInput}. Every whitespace-separated query term must match {@link
 * Item#filterText()}, using simple Unicode lowercase mapping. {@link FilterMode#PREFIX} uses
 * substring matching and preserves order; {@link FilterMode#FUZZY} uses subsequences ranked by
 * shortest span, earliest start, then original order. Title highlights independently match the
 * painted title and expand to grapheme boundaries.</p>
 *
 * <p>A group header precedes the first grouped item on every page, including pages beginning inside
 * a group. Ungrouped items end the preceding group. Space marks the selection and steps down;
 * {@link RichListMsg.MarkAll} marks visible items and {@link RichListMsg.ClearMarks} clears marks.
 * Marks survive filtering. Chosen output reports marked items in list order, or the selected item
 * when none are marked.</p>
 *
 * <p>Optional chrome comprises the title/filter, counts/status, pager, and help. {@link Renderer}
 * supplies item heights; pages pack whole items into remaining content rows, clipping oversized
 * items. Pages retain equal height. Initialization and a scoped resize {@link Subscription} derive
 * height from the viewport minus reserved rows. Resize preserves selection; changing pages selects
 * the destination page's first item.</p>
 *
 * <p>{@link RichListMsg.Notice} expires through an epoch-tagged delay, so an older timer cannot
 * clear a newer notice. Loading composes a {@link Spinner} whose timer exists only while shown.</p>
 *
 * <p>{@link #defaultKeys()} and {@link #keys(RichListModel)} drive routing and help. Filter editing
 * disables browsing and marking so printable input reaches the field; apply-filter and choose never
 * share an enabled Enter binding. {@link RichListConfig#extraHelp()} describes application-owned
 * gestures without routing them.</p>
 *
 * <p>A click selects an item from any row it occupies; a second click chooses it. Chrome and blank
 * rows select nothing. The wheel steps selection. Pointer mapping uses the same page and renderer
 * heights as painting.</p>
 */
public final class RichList
        implements Component<RichListModel, RichListMsg, RichListOutput>, Interactive<RichListModel, RichListMsg> {

    /**
     * The height a list paints when it has no terminal geometry to follow.
     */
    public static final int DEFAULT_ROWS = 10;

    /**
     * How long a transient status message stays by default.
     */
    public static final Duration DEFAULT_NOTICE_LIFETIME = Duration.ofSeconds(1);

    /**
     * The marker {@link RichListDefaultRenderer} paints on the selected item's row.
     */
    public static final String MARKER = "\u276F ";

    /**
     * The gap {@link RichListDefaultRenderer} indents every other row by, so titles line up.
     */
    public static final String MARKER_GAP = "  ";

    /**
     * The marker a {@link RichListDefaultRenderer} paints for an item the user marked but did not select.
     */
    public static final String MARKED = "\u2714 ";

    /**
     * Separates the counts from a transient message on the status row.
     */
    public static final String STATUS_SEPARATOR = " \u2022 ";

    /**
     * The empty-state text a list with no items paints instead of rows.
     */
    public static final String NO_ITEMS = "no items";

    /**
     * The empty-state text a query that matched nothing paints instead of rows.
     */
    public static final String NO_MATCHES = "no matches";

    /**
     * The identity of the transient-message effect, scoped to the owning mount.
     */
    static final EffectId NOTICE_EFFECT = EffectId.of("notice");

    /**
     * The identity of the terminal-size subscription, scoped to the owning mount.
     */
    static final SubscriptionId SIZE = SubscriptionId.of("size");

    /**
     * The width help is measured at when only its line count matters, which width never changes.
     */
    static final int HELP_WIDTH = 400;

    /**
     * The actions that only browse: they are disabled while a query is being edited.
     */
    static final List<String> BROWSING = List.of(
            "up",
            "down",
            "page-previous",
            "page-next",
            "first",
            "last",
            "filter",
            "filter-mode",
            "choose",
            "mark",
            "mark-all");

    /**
     * The chrome toggles: like browsing, they are disabled while a query is being edited.
     */
    static final List<String> CHROME = List.of("title", "status", "pagination", "help", "expand-help", "loading");

    /**
     * One row of the list: a stable identity, what it paints, and the text a query is matched
     * against.
     *
     * <p>The id is the row's identity across filtering, paging and reordering, so it must be stable
     * for the item's lifetime and unique within one list. A model that repeats an id is rejected.
     * The title and description are painted by the {@link Renderer}; a title is flattened to one row
     * by the default renderer, but a custom one may use a break in either.</p>
     *
     * @param id the stable identity; never blank
     * @param title the item's title; null is empty
     * @param description the item's description; null is empty
     * @param filterText the text a query is matched against; null is the title
     * @param group the group the item belongs to, empty for an ungrouped item
     */
    public record Item(String id, String title, String description, String filterText, String group) {

        /**
         * Rejects a blank id and substitutes empty text for a missing value.
         */
        public Item {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("an item needs a non-blank id");
            }
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            filterText = filterText == null ? title : filterText;
            group = group == null ? "" : group;
        }

        /**
         * An item with no group.
         *
         * @param id the stable identity; never blank
         * @param title the item's title
         * @param description the item's description
         * @param filterText the text a query is matched against
         */
        public Item(String id, String title, String description, String filterText) {
            this(id, title, description, filterText, "");
        }

        /**
         * The same item in a group.
         *
         * @param value the group's label; null or blank is no group
         * @return the new item
         */
        public Item withGroup(String value) {
            return new Item(id, title, description, filterText, value);
        }

        /**
         * An item with no description, matched on its title.
         *
         * @param id the stable identity; never blank
         * @param title the item's title
         * @return the item
         */
        public static Item of(String id, String title) {
            return new Item(id, title, "", title);
        }

        /**
         * An item matched on its title.
         *
         * @param id the stable identity; never blank
         * @param title the item's title
         * @param description the item's description
         * @return the item
         */
        public static Item of(String id, String title, String description) {
            return new Item(id, title, description, title);
        }
    }

    /**
     * How a query is matched against an item's filter text.
     */
    public enum FilterMode {

        /**
         * Plain, order-preserving matching: every term must appear in the item's filter text as a
         * case-insensitive substring, and matching items keep the order they are stored in.
         */
        PREFIX,

        /**
         * Subsequence matching with a deterministic rank: every term must appear in the item's
         * filter text as a subsequence, and items are ordered by the shortest matched span, then the
         * earliest match start, then item order.
         */
        FUZZY
    }

    /**
     * A half-open range of one item's title that matched the query.
     *
     * <p>Offsets are Java string offsets into the title as stored, widened to whole grapheme
     * clusters so a renderer can apply a style without splitting one. They describe the title only:
     * an item that matches through a distinct {@link Item#filterText()} paints without a
     * highlight.</p>
     *
     * @param start the first offset of the range
     * @param end the offset after its last one
     */
    public record Highlight(int start, int end) {

        /**
         * Rejects a negative or reversed range.
         */
        public Highlight {
            if (start < 0) {
                throw new IllegalArgumentException("start must not be negative");
            }
            if (end < start) {
                throw new IllegalArgumentException("end must not precede start");
            }
        }

        /**
         * The number of offsets the range covers.
         *
         * @return {@code end - start}
         */
        public int length() {
            return end - start;
        }
    }

    /**
     * One item's place in the painted list, as its renderer sees it.
     *
     * <p>It carries no terminal state, no model and no clock: a renderer is a pure function of this
     * value, which is what lets an application paint anything it likes without owning the list.</p>
     *
     * @param item the item being painted
     * @param selected whether it is the selected item
     * @param marked whether the user marked it for a multiple choice
     * @param width the columns the row may use
     * @param highlights the ranges of its title the query matched, in order
     */
    public record Row(Item item, boolean selected, boolean marked, int width, List<Highlight> highlights) {

        /**
         * Rejects a negative width and prepares the highlight list.
         */
        public Row {
            Objects.requireNonNull(item, "item");
            if (width < 0) {
                throw new IllegalArgumentException("width must not be negative");
            }
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
        }

        /**
         * The same row for an unmarked item.
         *
         * @param item the item being painted
         * @param selected whether it is the selected item
         * @param width the columns the row may use
         * @param highlights the ranges of its title the query matched
         */
        public Row(Item item, boolean selected, int width, List<Highlight> highlights) {
            this(item, selected, false, width, highlights);
        }
    }

    /**
     * Paints one item.
     *
     * <p>A renderer is the list's extension point for item layout: it decides how many rows an item
     * occupies and what those rows contain. Both methods are pure. {@link #height(Item)} packs
     * pages, so it must be a function of the item alone - the same item has the same height wherever
     * it lands - and it is clamped to at least one row. {@link #render(Row)} is called only for the
     * items of the page being painted, so a renderer never sees more than one page of a list,
     * however long the list is.</p>
     */
    public interface Renderer {

        /**
         * The rows this item occupies.
         *
         * @param item the item to measure; never null
         * @return the rows it is painted in; values below one are treated as one
         */
        int height(Item item);

        /**
         * The rows to paint for one item, in order.
         *
         * <p>At most {@link #height(Item)} rows are painted: further rows are ignored, and missing
         * ones are left blank. A row is cut to the width the list was given and never wraps, so a
         * value wider than the list cannot move the chrome below it.</p>
         *
         * @param row the item's place in the list; never null
         * @return the rows to paint; null paints nothing
         */
        List<StyledText> render(Row row);
    }

    /**
     * Which chrome the list paints.
     *
     * <p>Each section is a row budget as well as a painted section, so switching one off gives its
     * rows back to the items and repacks the pages. The title bar is painted while a query is being
     * edited even when {@code title} is off: a filter the user cannot see is a trap, not a
     * preference.</p>
     *
     * @param title whether the title bar is painted when no query is being edited
     * @param status whether the count-and-status row is painted
     * @param pagination whether the page indicator is painted
     * @param help whether help is painted
     * @param expandedHelp whether help is the full grouped listing instead of the compact line
     * @param loading whether the spinner is painted and running
     */
    public record Chrome(
            boolean title, boolean status, boolean pagination, boolean help, boolean expandedHelp, boolean loading) {

        /**
         * Everything on except the spinner, with compact help.
         *
         * @return the default chrome
         */
        public static Chrome defaults() {
            return new Chrome(true, true, true, true, false, false);
        }

        /**
         * The same chrome with the title bar on or off.
         *
         * @param value whether the title bar is painted
         * @return the new chrome
         */
        public Chrome withTitle(boolean value) {
            return new Chrome(value, status, pagination, help, expandedHelp, loading);
        }

        /**
         * The same chrome with the status row on or off.
         *
         * @param value whether the status row is painted
         * @return the new chrome
         */
        public Chrome withStatus(boolean value) {
            return new Chrome(title, value, pagination, help, expandedHelp, loading);
        }

        /**
         * The same chrome with the page indicator on or off.
         *
         * @param value whether the indicator is painted
         * @return the new chrome
         */
        public Chrome withPagination(boolean value) {
            return new Chrome(title, status, value, help, expandedHelp, loading);
        }

        /**
         * The same chrome with help on or off.
         *
         * @param value whether help is painted
         * @return the new chrome
         */
        public Chrome withHelp(boolean value) {
            return new Chrome(title, status, pagination, value, expandedHelp, loading);
        }

        /**
         * The same chrome with full or compact help.
         *
         * @param value whether help is the full listing
         * @return the new chrome
         */
        public Chrome withExpandedHelp(boolean value) {
            return new Chrome(title, status, pagination, help, value, loading);
        }

        /**
         * The same chrome with the spinner on or off.
         *
         * @param value whether the spinner is painted and running
         * @return the new chrome
         */
        public Chrome withLoading(boolean value) {
            return new Chrome(title, status, pagination, help, expandedHelp, value);
        }
    }

    /**
     * A transient message painted after the counts.
     *
     * <p>The epoch names the notice, not the message: a {@link RichListMsg.NoticeExpired} carrying an older
     * epoch is a message about a notice that no longer exists and is ignored, so a timer that fires
     * after a newer notice cannot clear it.</p>
     *
     * @param text the message; empty when there is none
     * @param epoch the notice's identity
     */
    public record Notice(String text, long epoch) {

        /**
         * Substitutes empty text for a missing value and rejects a negative epoch.
         */
        public Notice {
            text = text == null ? "" : text;
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must not be negative");
            }
        }

        /**
         * No message.
         *
         * @return the empty notice
         */
        public static Notice none() {
            return new Notice("", 0);
        }

        /**
         * Whether a message is painted.
         *
         * @return whether the text is non-empty
         */
        public boolean present() {
            return !text.isEmpty();
        }
    }

    /**
     * One item the query shows, with the ranges of its title the query matched.
     *
     * @param item the visible item
     * @param highlights the matched ranges of its title, in order
     */
    public record Match(Item item, List<Highlight> highlights) {

        /**
         * Rejects a missing item and prepares the highlight list.
         */
        public Match {
            Objects.requireNonNull(item, "item");
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
        }

        /**
         * The item's stable identity.
         *
         * @return the id
         */
        public String id() {
            return item.id();
        }
    }

    /**
     * The half-open range of visible items one page covers.
     *
     * @param start the first visible index of the page
     * @param end the visible index after its last one
     */
    public record Page(int start, int end) {

        /**
         * Rejects a range that is not a non-negative half-open interval.
         */
        public Page {
            if (start < 0) {
                throw new IllegalArgumentException("start must not be negative");
            }
            if (end < start) {
                throw new IllegalArgumentException("end must not precede start");
            }
        }

        /**
         * The number of items the page covers.
         *
         * @return {@code end - start}
         */
        public int length() {
            return end - start;
        }

        /**
         * Whether a visible index is on this page.
         *
         * @param index the visible index to test
         * @return whether {@code start <= index < end}
         */
        public boolean contains(int index) {
            return index >= start && index < end;
        }
    }

    /**
     * A reduction result.
     */
    record Result(RichListModel state, RichListOutput intent) implements WidgetResult<RichListModel, RichListOutput> {}

    /**
     * What one item produces for a query: its title's highlights and the rank keys of its match.
     */
    record Found(List<Highlight> highlights, int span, int first) {}

    private final RichListConfig config;
    private final TextInput filterField;
    private final Spinner spinner;
    private final RichListEditing richListEditing;

    /**
     * A list from its definition.
     *
     * @param config the immutable definition; required
     */
    public RichList(RichListConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.filterField = new TextInput(config.filterInput());
        this.spinner = new Spinner(config.spinner());
        this.richListEditing = new RichListEditing(this.config, this.filterField, this.spinner);
    }

    /**
     * The definition this list was built from.
     *
     * @return the definition
     */
    public RichListConfig config() {
        return config;
    }

    /**
     * The actions a list defines, before its state is taken into account.
     *
     * <p>The two {@code enter} actions are declared together and never enabled together: browsing
     * chooses, editing applies the query. {@link #keys(RichListModel)} is the map for one state, and it is
     * what the component routes and what help renders.</p>
     *
     * @return the default actions
     */
    public static KeyMap<RichListMsg> defaultKeys() {
        return RichListKeys.defaultKeys();
    }

    /**
     * The actions one state handles: the definition with the actions that state disables or
     * relabels.
     *
     * <p>While a query is being edited only the filter's own gestures are enabled, so a printable key
     * that also names a browsing action types its character instead of moving the selection. The
     * cancel action says what it will do - cancel the edit, clear the applied query, or cancel the
     * list - so help never describes a gesture by what it did in another state.</p>
     *
     * @param state the state the actions are built for; required
     * @return the key map
     */
    public static KeyMap<RichListMsg> keys(RichListModel state) {
        return RichListKeys.keys(state);
    }

    @Override
    public Update<RichListModel, RichListMsg, RichListOutput> init(InitContext context) {
        // The startup geometry is the only size the list can know before a resize arrives: what the
        // application paints outside the list is taken out of it, and the configured height is the
        // fallback a headless test or a preview uses.
        int rows = context.viewport().rows() > 0 ? context.viewport().rows() - config.reservedRows() : config.rows();
        RichListModel state = new RichListModel(
                config.items(),
                "",
                RichListFilter.of(config.mode()),
                Chrome.defaults(),
                rows,
                Spinner.Model.stopped(),
                Notice.none(),
                true);
        return Update.next(state);
    }

    @Override
    public Update<RichListModel, RichListMsg, RichListOutput> update(RichListModel model, RichListMsg message) {
        return WidgetUpdate.settled(
                model,
                message,
                action -> reduce(model, config, action),
                state -> state.withFocus(model.focused()),
                state -> noticeEffect(model, state));
    }

    @Override
    public Subscription<RichListMsg> subscriptions(RichListModel model) {
        Objects.requireNonNull(model, "model");
        // Two sources at most: the terminal's size, and the spinner's interval while it is shown. A
        // list that is not loading owns no timer at all.
        return Subscription.batch(
                Subscription.resize(SIZE, size -> new RichListMsg.RowsChanged(size.rows())),
                model.chrome().loading()
                        ? spinner.subscriptions(model.spinner()).map(RichListMsg.SpinnerMessage::new)
                        : Subscription.none());
    }

    @Override
    public Element<RichListMsg> view(RichListModel model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The list's own keys: the actions {@link #keys(RichListModel)} enables first, then the filter field.
     *
     * <p>The list's actions are disabled while the field is edited, so typing reaches the field and
     * browsing reaches the list, and both are the same map {@link Help} renders.</p>
     */
    @Override
    public void onKey(InputEvent.KeyInput key, RichListModel model, Consumer<RichListMsg> emit) {
        Optional<KeyAction<RichListMsg>> action = RichListKeys.keys(model).actionFor(key.key());
        if (action.isPresent()) {
            emit.accept(action.get().message());
            return;
        }
        typing(model, key).ifPresent(emit);
    }

    /**
     * Everything that is not a key gesture belongs to the filter field while it is being edited.
     */
    @Override
    public void onInput(InputEvent event, RichListModel model, Consumer<RichListMsg> emit) {
        typing(model, event).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(RichListModel model) {
        return model.focused();
    }

    /**
     * The list's pointer handler: a click selects the row it landed on, a second click chooses what
     * the first selected, and the wheel steps the selection.
     *
     * <p>The row is found the way it was painted - past the chrome, through the page's items at
     * their own heights - so a click cannot land on an item the list is not showing. A click on the
     * chrome, on a blank row below the last item, or on a page indicator selects nothing.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, RichListModel model, Consumer<RichListMsg> emit) {
        pointer(pointer.localTo(bounds.row(), bounds.col()), model, bounds.cols())
                .ifPresent(emit);
    }

    private Optional<RichListMsg> pointer(PointerEvent event, RichListModel model, int cols) {
        if (event.isWheel()) {
            return Optional.of(
                    switch (event.button()) {
                        case WHEEL_UP, WHEEL_LEFT -> new RichListMsg.Up();
                        default -> new RichListMsg.Down();
                    });
        }
        if (!event.isLeftPress()) {
            return Optional.empty();
        }
        String id = itemAt(model, cols, event.row());
        if (id == null) {
            return Optional.empty();
        }
        // The first press of a double click already selected this row, so the second press is
        // the click on an already selected row - which is what choosing means here.
        return Optional.of(event.clickCount() > 1 ? new RichListMsg.Choose() : new RichListMsg.Select(id));
    }

    /**
     * The id of the item painted on one row of the list, or null for a row no item covers.
     *
     * @param state the state the frame was painted from
     * @param cols the columns the list was painted in
     * @param row the row, in the list's own coordinates
     */
    private String itemAt(RichListModel state, int cols, int row) {
        int local = row - contentTop(state);
        if (local < 0 || local >= RichListPaging.contentRows(state, config)) {
            return null;
        }
        Resolved resolved = resolved(state);
        Page page = resolved.page();
        int used = 0;
        for (int index = page.start(); index < page.end(); index++) {
            Match match = resolved.visible().get(index);
            List<StyledText> painted = config.renderer()
                    .render(new Row(
                            match.item(),
                            match.id().equals(state.selectedId()),
                            state.isMarked(match.id()),
                            cols,
                            match.highlights()));
            if (painted == null) {
                continue;
            }
            int rows = Math.min(RichListPaging.height(config.renderer(), match.item()), painted.size());
            // The header row a group paints above its first item belongs to no item, so a click on
            // it selects nothing rather than the item it names.
            int header = headerRows(resolved.visible(), page.start(), index);
            if (local < used + header) {
                return null;
            }
            used += header;
            if (local < used + rows) {
                return match.id();
            }
            used += rows;
        }
        return null;
    }

    /**
     * How many rows of chrome are painted above the items.
     */
    static int contentTop(RichListModel state) {
        return (titleVisible(state) ? 1 : 0) + (state.chrome().status() ? 1 : 0);
    }

    /**
     * The message a typing gesture produces, or empty when the list is not editing or leaves it.
     */
    private Optional<RichListMsg> typing(RichListModel model, InputEvent event) {
        if (!model.filter().editing()) {
            return Optional.empty();
        }
        return TextInput.actionFor(model.filter().field(), event, config.filterInput())
                .map(RichListMsg.FilterInput::new);
    }

    // ── painting ─────────────────────────────────────────────────────────────

    /**
     * Every row the list paints, chrome included: exactly {@link RichListModel#rows()} rows.
     *
     * <p>A row is one cell high whatever the width, so each section is a fixed-height leaf that
     * renders its own text for the columns the layout gives it: the frame stays the size the model
     * says while the text inside it fills the width. The list's own interaction is the top layer of
     * the same stack, which is what a key, a pointer report and a focus walk reach.</p>
     */
    private Element<RichListMsg> scene(RichListModel model) {
        List<Match> visible = RichListPaging.visible(model);
        Resolved resolved = resolved(model);
        List<Element<RichListMsg>> sections = new ArrayList<>(8);
        if (titleVisible(model)) {
            sections.add(section(1, cols -> RichListMatching.oneRow(titleLine(model, cols), cols)));
        }
        if (model.chrome().status()) {
            sections.add(section(1, cols -> RichListMatching.oneRow(statusLine(model, visible.size()), cols)));
        }
        sections.add(section(RichListPaging.contentRows(model, config), cols -> contentBlock(model, resolved, cols)));
        if (model.chrome().pagination()) {
            sections.add(section(1, cols -> RichListMatching.oneRow(paginationLine(resolved, cols), cols)));
        }
        if (model.chrome().help()) {
            sections.add(section(helpRows(model, config), cols -> helpBlock(model, cols)));
        }
        return Elements.stack(Elements.column(sections), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * One section of the scene: exactly {@code rows} rows, rendered for the columns it is given.
     */
    static <Message> Element<Message> section(int rows, IntFunction<StyledText> body) {
        return ComputedBlock.of(rows, body);
    }

    /**
     * What a paint of this state resolves: the visible items, their pages and the selection.
     */
    private Resolved resolved(RichListModel model) {
        List<Match> visible = RichListPaging.visible(model);
        return new Resolved(
                visible,
                RichListPaging.pack(visible, config, RichListPaging.contentRows(model, config)),
                RichListPaging.selectedIndex(visible, model.selectedId()));
    }

    /**
     * What one paint resolved: the visible items, the pages they are packed into and the selection.
     */
    record Resolved(List<Match> visible, List<Page> pages, int selected) {

        Page page() {
            if (pages.isEmpty()) {
                return new Page(0, 0);
            }
            for (Page page : pages) {
                if (page.contains(selected)) {
                    return page;
                }
            }
            return pages.get(0);
        }
    }

    /**
     * The painted items of one page, then blank rows to fill the content exactly.
     *
     * <p>The block is rendered for the columns the layout resolved, once per item: a renderer that
     * sizes its own content - a bar, a truncated value - needs the width, and the width is only
     * known when the section is placed. Rows are joined with a line break, so the block's lines are
     * exactly the rows the model reserved for the window.</p>
     */
    private StyledText contentBlock(RichListModel model, Resolved resolved, int cols) {
        int remaining = RichListPaging.contentRows(model, config);
        Page page = resolved.page();
        StyledText block = null;
        for (int index = page.start(); index < page.end() && remaining > 0; index++) {
            Match match = resolved.visible().get(index);
            String header = header(resolved.visible(), page.start(), index);
            if (!header.isEmpty()) {
                block = appendRow(
                        block,
                        RichListMatching.oneRow(
                                StyledText.of(header, config.styles().group()), cols));
                remaining--;
                if (remaining <= 0) {
                    break;
                }
            }
            boolean selected = match.id().equals(model.selectedId());
            List<StyledText> painted = config.renderer()
                    .render(new Row(match.item(), selected, model.isMarked(match.id()), cols, match.highlights()));
            if (painted == null) {
                continue;
            }
            int height = RichListPaging.height(config.renderer(), match.item());
            for (int row = 0; row < Math.min(height, painted.size()) && remaining > 0; row++) {
                block = appendRow(block, RichListMatching.oneRow(painted.get(row), cols));
                remaining--;
            }
        }
        while (remaining > 0) {
            block = appendRow(block, StyledText.empty());
            remaining--;
        }
        return block == null ? StyledText.empty() : block;
    }

    /**
     * One row below the rows already in the block.
     */
    static StyledText appendRow(StyledText block, StyledText row) {
        return block == null ? row : block.append(StyledText.plain("\n")).append(row);
    }

    /**
     * The header row an item paints above it, or empty when it needs none.
     *
     * <p>A group header names the group of the item it opens. It is painted for the first item of a
     * group and for the first item of a page, because a page that started inside a group would
     * otherwise show rows whose group nothing on screen names. An ungrouped item has no header, and
     * an ungrouped item between two groups closes the one above it.</p>
     *
     * @param visible the visible items
     * @param pageStart the index the page starts at
     * @param index the index of the item in the page
     * @return the header label, empty when the item needs no header
     */
    static String header(List<Match> visible, int pageStart, int index) {
        Item item = visible.get(index).item();
        String group = item.group();
        if (group.isEmpty()) {
            return "";
        }
        return index == pageStart || !group.equals(visible.get(index - 1).item().group()) ? group : "";
    }

    /**
     * The rows a header costs the page that paints it, one or none.
     */
    static int headerRows(List<Match> visible, int pageStart, int index) {
        return header(visible, pageStart, index).isEmpty() ? 0 : 1;
    }

    /**
     * Whether the title bar is painted: it is what a query being typed is shown in.
     */
    static boolean titleVisible(RichListModel state) {
        return state.chrome().title() || state.filter().editing();
    }

    /**
     * The title bar's row: the filter field while a query is edited, otherwise the title.
     */
    private StyledText titleLine(RichListModel state, int cols) {
        if (state.filter().editing()) {
            return TextInput.render(state.filter().field(), config.filterInput(), true, cols);
        }
        StyledText title = StyledText.of(config.title(), config.styles().title());
        if (!state.chrome().loading()) {
            return title;
        }
        StyledText glyph = Spinner.render(state.spinner(), config.spinner(), cols);
        return glyph.isEmpty() ? title : StyledText.concat(glyph, StyledText.plain(" "), title);
    }

    /**
     * The status row: how many items the query shows, then any transient message.
     */
    private StyledText statusLine(RichListModel state, int shown) {
        String counts = counts(shown, state.items().size());
        if (!state.marked().isEmpty()) {
            counts += STATUS_SEPARATOR + marked(state.marked().size());
        }
        StyledText line = StyledText.of(counts, config.styles().status());
        if (!state.notice().present()) {
            return line;
        }
        return line.append(StyledText.of(STATUS_SEPARATOR, config.styles().status()))
                .append(StyledText.of(state.notice().text(), config.styles().notice()));
    }

    /**
     * The text that names how many items are marked.
     */
    static String marked(int count) {
        return count == 1 ? "1 marked" : count + " marked";
    }

    /**
     * The count-and-status text one paint shows, from the counts it already resolved.
     */
    static String counts(int shown, int total) {
        if (total == 0) {
            return NO_ITEMS;
        }
        if (shown == 0) {
            return NO_MATCHES;
        }
        String items = shown == 1 ? "1 item" : shown + " items";
        return shown == total ? items : shown + " of " + (total == 1 ? "1 item" : total + " items");
    }

    /**
     * The page indicator, painted by the composed {@link Paginator}.
     */
    private StyledText paginationLine(Resolved resolved, int cols) {
        Paginator.Config pager = new Paginator.Config(1, false, config.pagination());
        return Paginator.render(
                new Paginator.Model(
                        RichListPaging.pageOf(resolved), resolved.pages().size(), 1, false),
                pager,
                cols);
    }

    /**
     * The help rows for one state: the list's own block, then the application's.
     *
     * <p>They are two blocks rather than one merged line because the two maps belong to different
     * owners and have different message types; the list paints both and routes only its own.</p>
     */
    private StyledText helpBlock(RichListModel state, int cols) {
        StyledText lines = Help.render(RichListKeys.keys(state), state.chrome().expandedHelp(), cols);
        if (config.extraHelp().actions().isEmpty()) {
            return lines;
        }
        return lines.append(StyledText.plain("\n"))
                .append(Help.render(config.extraHelp(), state.chrome().expandedHelp(), cols));
    }

    /**
     * The rows help occupies, counted from the same renders that paint them. Help truncates a line
     * to its width instead of wrapping it, so a line count never depends on the width.
     */
    static int helpRows(RichListModel state, RichListConfig config) {
        if (!state.chrome().help()) {
            return 0;
        }
        RichListConfig opts = config == null ? RichListConfig.defaults() : config;
        int rows = Help.render(RichListKeys.keys(state), state.chrome().expandedHelp(), HELP_WIDTH)
                .lines()
                .size();
        if (!opts.extraHelp().actions().isEmpty()) {
            rows += Help.render(opts.extraHelp(), state.chrome().expandedHelp(), HELP_WIDTH)
                    .lines()
                    .size();
        }
        return rows;
    }

    // ── derived state ────────────────────────────────────────────────────────

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
        return RichListPaging.visible(state);
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
        return RichListPaging.pages(state, config);
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
        return RichListPaging.pageIndex(state, config);
    }

    /**
     * The rows the list's chrome occupies, whatever its items are.
     *
     * @param state the state to inspect; required
     * @param config the definition whose extra help is counted; null is {@link RichListConfig#defaults()}
     * @return the chrome rows
     */
    public static int chromeRows(RichListModel state, RichListConfig config) {
        return RichListPaging.chromeRows(state, config);
    }

    /**
     * The rows left for items after the chrome the state asks for.
     *
     * @param state the state to inspect; required
     * @param config the definition; null is {@link RichListConfig#defaults()}
     * @return the content rows, never negative
     */
    public static int contentRows(RichListModel state, RichListConfig config) {
        return RichListPaging.contentRows(state, config);
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
        return RichListPaging.pagination(state, config);
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
        return RichListPaging.matchHighlights(query, text, mode);
    }

    // ── reduction ────────────────────────────────────────────────────────────

    /**
     * Applies one message. Never performs I/O, never mutates the input state and never swallows.
     */
    private Result reduce(RichListModel state, RichListConfig config, RichListMsg message) {
        return richListEditing.reduce(state, config, message);
    }

    /**
     * The delay that clears a notice this reduction just set, or nothing when it set none.
     */
    private Command<RichListMsg> noticeEffect(RichListModel before, RichListModel after) {
        return richListEditing.noticeEffect(before, after);
    }

    // ── matching ─────────────────────────────────────────────────────────────

}
