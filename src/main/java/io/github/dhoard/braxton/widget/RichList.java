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
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A filterable, paged list of items that keep their identity: the rich counterpart of {@link
 * Selector}.
 *
 * <p>The list owns its data, its query, its selection and its chrome; the application owns the keys
 * that do something with a choice. That split is the whole contract. A {@code RichList} filters,
 * pages, paints and reports {@link Output.Chosen} or {@link Output.Cancelled}; it never inserts,
 * removes, fetches or navigates on the application's behalf, and it performs no I/O, holds no clock
 * and starts no thread. Every message is reduced to an immutable {@link Model} and every paint is a
 * pure function of that model.</p>
 *
 * <h2>Identity, not position</h2>
 *
 * <p>Selection is a stable item id, never an index. An item can be inserted above the selection, a
 * query can hide it and show it again, a page can be repacked by a resize, and the selection is
 * still the same item - a filtered or reordered list cannot silently select a different row. The id
 * is required and unique within a list; building a model with two items that share one, or with a
 * blank one, is rejected where the mistake was made. A selection that no longer names an item is
 * normalized: a removal selects the nearest surviving visible item explicitly, and a model built
 * with an absent id falls back to the first item.</p>
 *
 * <h2>Filtering</h2>
 *
 * <p>The filter is a single-line field composed from {@link TextInput}, so editing a query has the
 * gestures that field already defines. Matching is case-insensitive by Unicode code point with the
 * simple lowercase mapping, so folding never changes an offset inside a value. The query is split on
 * whitespace and every term must match. {@link FilterMode#PREFIX} keeps item order and takes each
 * term as a plain substring; {@link FilterMode#FUZZY} takes each term as a subsequence and ranks by
 * the shortest matched span, then the earliest match start, then item order, so the ranking is total
 * and independent of the sort implementation.</p>
 *
 * <p>An item's {@link Item#filterText()} decides whether it matches; its title is highlighted where
 * the same query matches the title, so a highlight range is always an offset into text that is
 * actually painted. Ranges are widened to whole grapheme clusters, so a combining mark, a selector
 * or a surrogate pair is never split by a style boundary.</p>
 *
 * <h2>Pages, chrome and height</h2>
 *
 * <p>The list paints, from the top: a title bar that becomes the filter field while the query is
 * being edited, a count-and-status row, its items, a page indicator and help. Each section is chrome
 * the model can switch off, and the content rows are what remains after the chrome the model asks
 * for. Items are not one row each: a {@link Renderer} decides an item's height, and items are packed
 * into pages of whole items that fit those rows, so an item taller than the content is clipped
 * rather than silently replaced by the next one. Every page is filled to the same height, which is
 * what keeps the indicator and the help on the same screen rows however many items the last page
 * holds.</p>
 *
 * <p>The height the list paints is the startup viewport minus {@link Config#reservedRows()} (what
 * the application paints outside the list), and it follows the terminal's own resizes through one
 * scoped {@link Subscription}. A resize repacks the pages and keeps the selection: the page a
 * selection is on is derived from the selection, never stored, so it cannot go stale. Paging moves
 * the selection to the first item of the page it moves to - an explicit, documented move rather than
 * a silent re-selection.</p>
 *
 * <h2>Feedback</h2>
 *
 * <p>A {@link Msg.Notice} paints a transient message after the counts. It is cleared by a scoped
 * {@link Command.Delay} that carries the notice's epoch, so an expiry that arrives after a newer
 * notice was set is ignored, and a timer that outlives its notice cannot clear the next one. Turning
 * loading on starts a composed {@link Spinner}; its interval exists only while the spinner is shown,
 * so a list that is not loading owns no timer.</p>
 *
 * <h2>Keys and help</h2>
 *
 * <p>{@link #defaultKeys()} is the definition the component routes and the definition {@link Help}
 * renders. The map is built from the state ({@link #keys(Model)}): while a query is being edited the
 * browsing actions are disabled so every printable key reaches the field, and the two actions bound
 * to {@code enter} - apply the filter and choose an item - are never enabled at the same time.
 * {@link Config#extraHelp()} adds the application's own actions to the painted help without claiming
 * their gestures; the application routes those itself, which keeps help and routing one definition
 * per owner.</p>
 *
 * <h2>Pointer input</h2>
 *
 * <p>A click selects the item painted on that row ({@link Msg.Select}) and a second click chooses it
 * ({@link Msg.Choose}), so a click never takes the choice away from the application on its own. An
 * item taller than one row is selected from any of its rows, because the row is resolved through the
 * same page and the same renderer heights the frame was painted from. A click on the title, the
 * status row, the pager, the help or a blank row below the last item selects nothing, and the wheel
 * steps the selection.</p>
 */
public final class RichList implements Component<RichList.Model, RichList.Msg, RichList.Output> {

    /** The height a list paints when it has no terminal geometry to follow. */
    public static final int DEFAULT_ROWS = 10;

    /** How long a transient status message stays by default. */
    public static final Duration DEFAULT_NOTICE_LIFETIME = Duration.ofSeconds(1);

    /** The marker {@link DefaultRenderer} paints on the selected item's row. */
    public static final String MARKER = "\u276F ";

    /** The gap {@link DefaultRenderer} indents every other row by, so titles line up. */
    public static final String MARKER_GAP = "  ";

    /** Separates the counts from a transient message on the status row. */
    public static final String STATUS_SEPARATOR = " \u2022 ";

    /** The empty-state text a list with no items paints instead of rows. */
    public static final String NO_ITEMS = "no items";

    /** The empty-state text a query that matched nothing paints instead of rows. */
    public static final String NO_MATCHES = "no matches";

    /** The identity of the list's focus element; a mount scopes it to its own path. */
    private static final ElementId ELEMENT = ElementId.of("rich-list");

    /** The identity of the transient-message effect, scoped to the owning mount. */
    private static final EffectId NOTICE_EFFECT = EffectId.of("notice");

    /** The identity of the terminal-size subscription, scoped to the owning mount. */
    private static final SubscriptionId SIZE = SubscriptionId.of("size");

    /** The width help is measured at when only its line count matters, which width never changes. */
    private static final int HELP_WIDTH = 400;

    /** The actions that only browse: they are disabled while a query is being edited. */
    private static final List<String> BROWSING =
            List.of("up", "down", "page-previous", "page-next", "first", "last", "filter", "filter-mode", "choose");

    /** The chrome toggles: like browsing, they are disabled while a query is being edited. */
    private static final List<String> CHROME =
            List.of("title", "status", "pagination", "help", "expand-help", "loading");

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
     */
    public record Item(String id, String title, String description, String filterText) {

        /** Rejects a blank id and substitutes empty text for a missing value. */
        public Item {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("an item needs a non-blank id");
            }
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            filterText = filterText == null ? title : filterText;
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

    /** How a query is matched against an item's filter text. */
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

        /** Rejects a negative or reversed range. */
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
     * @param width the columns the row may use
     * @param highlights the ranges of its title the query matched, in order
     */
    public record Row(Item item, boolean selected, int width, List<Highlight> highlights) {

        /** Rejects a negative width and prepares the highlight list. */
        public Row {
            Objects.requireNonNull(item, "item");
            if (width < 0) {
                throw new IllegalArgumentException("width must not be negative");
            }
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
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
     * The item renderer a list uses unless it is given another: a marker, a title, and a description
     * under it.
     *
     * <p>The marker names the selected item and is painted whether or not the list has focus, the
     * rule {@link Selector} follows. A highlighted part of the title carries the match style as well
     * as the title style, so a theme only has to say what a match looks like. The description is
     * optional and its rows are bounded, which is what lets an item be taller than one row without
     * being unbounded.</p>
     */
    public static final class DefaultRenderer implements Renderer {

        /**
         * The item styles a {@link DefaultRenderer} paints with.
         *
         * @param title the title of an unselected item
         * @param description the description of an unselected item
         * @param selectedTitle the title of the selected item
         * @param selectedDescription the description of the selected item
         * @param match the additional style of the part of a title the query matched
         */
        public record Styles(
                Style title, Style description, Style selectedTitle, Style selectedDescription, Style match) {

            /** Substitutes no styling for a missing style. */
            public Styles {
                title = title == null ? Style.NONE : title;
                description = description == null ? Style.NONE : description;
                selectedTitle = selectedTitle == null ? Style.NONE : selectedTitle;
                selectedDescription = selectedDescription == null ? Style.NONE : selectedDescription;
                match = match == null ? Style.NONE : match;
            }

            /**
             * A plain title, a plain description, a bold selected title and an underlined match.
             *
             * @return the default styling
             */
            public static Styles defaults() {
                return new Styles(
                        Style.NONE, Style.NONE, Style.NONE.withBold(), Style.NONE, Style.NONE.withUnderline());
            }

            /**
             * The same styling with a different title style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withTitle(Style value) {
                return new Styles(value, description, selectedTitle, selectedDescription, match);
            }

            /**
             * The same styling with a different description style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withDescription(Style value) {
                return new Styles(title, value, selectedTitle, selectedDescription, match);
            }

            /**
             * The same styling with a different selected-title style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withSelectedTitle(Style value) {
                return new Styles(title, description, value, selectedDescription, match);
            }

            /**
             * The same styling with a different selected-description style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withSelectedDescription(Style value) {
                return new Styles(title, description, selectedTitle, value, match);
            }

            /**
             * The same styling with a different match style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withMatch(Style value) {
                return new Styles(title, description, selectedTitle, selectedDescription, value);
            }
        }

        private final Styles styles;
        private final boolean showDescription;
        private final int maxDescriptionRows;

        /**
         * A renderer from its policy.
         *
         * @param styles the item styles; null is {@link Styles#defaults()}
         * @param showDescription whether a description is painted under the title
         * @param maxDescriptionRows the most description rows an item may occupy, breaks included;
         *     never negative
         */
        public DefaultRenderer(Styles styles, boolean showDescription, int maxDescriptionRows) {
            if (maxDescriptionRows < 0) {
                throw new IllegalArgumentException("maxDescriptionRows must not be negative");
            }
            this.styles = styles == null ? Styles.defaults() : styles;
            this.showDescription = showDescription;
            this.maxDescriptionRows = showDescription ? maxDescriptionRows : 0;
        }

        /**
         * The default renderer: a title and one description row.
         *
         * @return the default renderer
         */
        public static DefaultRenderer defaults() {
            return new DefaultRenderer(Styles.defaults(), true, 1);
        }

        /**
         * The same policy with different item styles.
         *
         * @param value the new styles; null is the default styling
         * @return the new renderer
         */
        public DefaultRenderer withStyles(Styles value) {
            return new DefaultRenderer(value, showDescription, maxDescriptionRows);
        }

        /**
         * The same policy with a different description policy.
         *
         * @param show whether a description is painted
         * @param rows the most description rows an item may occupy; never negative
         * @return the new renderer
         */
        public DefaultRenderer withDescription(boolean show, int rows) {
            return new DefaultRenderer(styles, show, rows);
        }

        /**
         * One row for the title, then as many description rows as the description has, up to the
         * configured bound. An item's height therefore follows its own content.
         */
        @Override
        public int height(Item item) {
            return 1 + Math.min(maxDescriptionRows, descriptionLines(item));
        }

        @Override
        public List<StyledText> render(Row row) {
            Item item = row.item();
            Style titleStyle = row.selected() ? styles.selectedTitle() : styles.title();
            StyledText body = row.highlights().isEmpty()
                    ? StyledText.of(item.title(), titleStyle)
                    : source(item.title(), row.highlights(), titleStyle, styles.match());
            List<StyledText> rows = new ArrayList<>(1 + maxDescriptionRows);
            rows.add(StyledText.concat(StyledText.of(row.selected() ? MARKER : MARKER_GAP, titleStyle), body));
            if (maxDescriptionRows > 0) {
                Style descriptionStyle = row.selected() ? styles.selectedDescription() : styles.description();
                List<String> lines = lines(item.description());
                for (int index = 0; index < Math.min(maxDescriptionRows, lines.size()); index++) {
                    rows.add(StyledText.of(MARKER_GAP + lines.get(index), descriptionStyle));
                }
            }
            return rows;
        }

        /**
         * One title with the matched ranges in the match style: the title is split at the highlight
         * boundaries, which are cluster boundaries, so no styled run splits a grapheme.
         */
        private static StyledText source(String text, List<Highlight> highlights, Style title, Style match) {
            Style matched = title.merge(match);
            StyledText styled = StyledText.empty();
            int offset = 0;
            for (Highlight highlight : highlights) {
                int start = Math.max(offset, Math.min(highlight.start(), text.length()));
                int end = Math.max(start, Math.min(highlight.end(), text.length()));
                if (start > offset) {
                    styled = styled.append(StyledText.of(text.substring(offset, start), title));
                }
                if (end > start) {
                    styled = styled.append(StyledText.of(text.substring(start, end), matched));
                }
                offset = end;
            }
            if (offset < text.length()) {
                styled = styled.append(StyledText.of(text.substring(offset), title));
            }
            return styled;
        }

        /** How many rows an item's description occupies. */
        private static int descriptionLines(Item item) {
            return item.description().isEmpty() ? 0 : lines(item.description()).size();
        }

        /** The logical lines of a value, with the platform's own breaks normalized first. */
        private static List<String> lines(String value) {
            return List.of(LineBreaks.normalize(value).split("\n", -1));
        }
    }

    /**
     * The filter: the field a query is typed into, the query that was applied, and the mode.
     *
     * <p>The query is the field's own text, so there is exactly one place the current query lives
     * and a filter cannot disagree with the value the field paints. Editing is the field's focus.
     * Accepting keeps the live query and remembers it as the applied one; cancelling restores the
     * applied query and the item that was selected when the edit began, which is why both are model
     * data rather than transient state.</p>
     *
     * @param field the composed field's state; never null
     * @param applied the query an accept last applied
     * @param mode how a query is matched
     * @param prefilterSelection the selected id when the current edit began, or empty
     */
    public record Filter(TextInput.Model field, String applied, FilterMode mode, String prefilterSelection) {

        /** Substitutes an empty field and the default mode for a missing value. */
        public Filter {
            // A filter starts blurred: whether a query is being edited is model data the caller
            // asks for, not something constructing a value can decide.
            field = field == null ? Filter.blurred() : field;
            applied = applied == null ? "" : applied;
            mode = mode == null ? FilterMode.FUZZY : mode;
            prefilterSelection = prefilterSelection == null ? "" : prefilterSelection;
        }

        /**
         * No query, no applied query and no remembered selection.
         *
         * @param mode how a query is matched
         * @return the empty filter
         */
        public static Filter of(FilterMode mode) {
            return new Filter(blurred(), "", mode, "");
        }

        /** An empty field that is not being typed into. */
        private static TextInput.Model blurred() {
            return TextInput.Model.empty().withFocus(false);
        }

        /**
         * The query being edited or shown.
         *
         * @return the field's text
         */
        public String query() {
            return field.text();
        }

        /**
         * Whether a query is being typed.
         *
         * @return whether the field owns focus
         */
        public boolean editing() {
            return field.focused();
        }

        /**
         * The same filter with a different field.
         *
         * @param value the new field state; null is the empty field
         * @return the new filter
         */
        public Filter withField(TextInput.Model value) {
            return new Filter(value, applied, mode, prefilterSelection);
        }

        /**
         * The same filter with a different applied query.
         *
         * @param value the new applied query; null is empty
         * @return the new filter
         */
        public Filter withApplied(String value) {
            return new Filter(field, value, mode, prefilterSelection);
        }

        /**
         * The same filter with a different mode.
         *
         * @param value the new mode; null is {@link FilterMode#FUZZY}
         * @return the new filter
         */
        public Filter withMode(FilterMode value) {
            return new Filter(field, applied, value, prefilterSelection);
        }

        /**
         * The same filter with a different remembered selection.
         *
         * @param value the selected id to restore on cancel; null is none
         * @return the new filter
         */
        public Filter withPrefilterSelection(String value) {
            return new Filter(field, applied, mode, value);
        }

        /**
         * The same filter with the field focused or blurred, which starts and stops an edit.
         *
         * @param value whether a query is being typed
         * @return the new filter
         */
        public Filter withEditing(boolean value) {
            return withField(field.withFocus(value));
        }
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
     * <p>The epoch names the notice, not the message: a {@link Msg.NoticeExpired} carrying an older
     * epoch is a message about a notice that no longer exists and is ignored, so a timer that fires
     * after a newer notice cannot clear it.</p>
     *
     * @param text the message; empty when there is none
     * @param epoch the notice's identity
     */
    public record Notice(String text, long epoch) {

        /** Substitutes empty text for a missing value and rejects a negative epoch. */
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

        /** Rejects a missing item and prepares the highlight list. */
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

        /** Rejects a range that is not a non-negative half-open interval. */
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
     */
    public record Model(
            List<Item> items,
            String selectedId,
            Filter filter,
            Chrome chrome,
            int rows,
            Spinner.Model spinner,
            Notice notice,
            boolean focused) {

        /** Checks the ids, prepares the selection and substitutes defaults for missing values. */
        public Model {
            items = items == null ? List.of() : List.copyOf(items);
            Set<String> ids = new HashSet<>(items.size() * 2);
            for (Item item : items) {
                if (!ids.add(item.id())) {
                    throw new IllegalArgumentException("duplicate item id: " + item.id());
                }
            }
            filter = filter == null ? Filter.of(FilterMode.FUZZY) : filter;
            // The selection names an item the query shows: an id that is absent (a message removed
            // the item) cannot be left dangling, and an id the query hides falls to the first item
            // it shows. A query that hides everything keeps the selection it had, so narrowing and
            // widening a query is reversible and a cancel can restore it.
            selectedId = selectedId == null ? "" : selectedId;
            if (items.isEmpty()) {
                selectedId = "";
            } else if (!ids.contains(selectedId)) {
                selectedId = revealed(items, filter, items.get(0).id());
            } else {
                selectedId = revealed(items, filter, selectedId);
            }
            chrome = chrome == null ? Chrome.defaults() : chrome;
            rows = Math.max(0, rows);
            spinner = spinner == null ? Spinner.Model.stopped() : spinner;
            notice = notice == null ? Notice.none() : notice;
        }

        /**
         * A list of items showing the first of them, focused, with the default filter and chrome.
         *
         * @param items the items; null is empty
         * @return the starting state
         */
        public static Model of(List<Item> items) {
            return of(items, DEFAULT_ROWS);
        }

        /**
         * A list of items showing the first of them, focused, with the default filter and chrome.
         *
         * @param items the items; null is empty
         * @param rows the rows the list may paint; negative is zero
         * @return the starting state
         */
        public static Model of(List<Item> items, int rows) {
            return new Model(
                    items,
                    "",
                    Filter.of(FilterMode.FUZZY),
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
        public Model withFocus(boolean value) {
            return value == focused ? this : new Model(items, selectedId, filter, chrome, rows, spinner, notice, value);
        }

        /**
         * The same state with a different item list. A selection the new list does not hold becomes
         * its first item.
         *
         * @param value the items; null is empty
         * @return the new state
         */
        public Model withItems(List<Item> value) {
            return new Model(value, selectedId, filter, chrome, rows, spinner, notice, focused);
        }

        /**
         * The same state with a different selection.
         *
         * @param value the id to select; null or an absent id becomes the first item
         * @return the new state
         */
        public Model withSelectedId(String value) {
            return new Model(items, value, filter, chrome, rows, spinner, notice, focused);
        }

        /**
         * The same state with a different filter.
         *
         * @param value the new filter; null is the default filter
         * @return the new state
         */
        public Model withFilter(Filter value) {
            return new Model(items, selectedId, value, chrome, rows, spinner, notice, focused);
        }

        /**
         * The same state with different chrome.
         *
         * @param value the new chrome; null is the default chrome
         * @return the new state
         */
        public Model withChrome(Chrome value) {
            return new Model(items, selectedId, filter, value, rows, spinner, notice, focused);
        }

        /**
         * The same state with a different height.
         *
         * @param value the rows the list may paint; negative is zero
         * @return this instance when the height did not change
         */
        public Model withRows(int value) {
            int height = Math.max(0, value);
            return height == rows
                    ? this
                    : new Model(items, selectedId, filter, chrome, height, spinner, notice, focused);
        }

        /**
         * The same state with a different spinner.
         *
         * @param value the new spinner state; null is a stopped spinner
         * @return this instance when the state did not change
         */
        public Model withSpinner(Spinner.Model value) {
            Spinner.Model next = value == null ? Spinner.Model.stopped() : value;
            return next.equals(spinner)
                    ? this
                    : new Model(items, selectedId, filter, chrome, rows, next, notice, focused);
        }

        /**
         * The same state with a different transient message.
         *
         * @param value the new notice; null clears it
         * @return this instance when the notice did not change
         */
        public Model withNotice(Notice value) {
            Notice next = value == null ? Notice.none() : value;
            return next.equals(notice)
                    ? this
                    : new Model(items, selectedId, filter, chrome, rows, spinner, next, focused);
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
    }

    /**
     * Immutable list definition.
     *
     * <p>It holds what does not change while the list runs: the items it starts with, how items and
     * chrome are painted, and the policies it follows. The initial items are definition in the same
     * sense {@link Selector.Config} carries its options - the model is what the messages change - so
     * an application builds the definition once and lets the model evolve.</p>
     *
     * @param items the items the list starts with
     * @param title the title bar's text
     * @param renderer how items are painted
     * @param mode the filter mode the list starts with
     * @param filterInput the composed filter field's policy
     * @param pagination how the page indicator is painted
     * @param spinner the composed spinner's definition
     * @param extraHelp the application's own actions, painted in help but not routed here
     * @param styles the chrome styles
     * @param noticeLifetime how long a transient message stays
     * @param rows the height used when the terminal reports none
     * @param reservedRows the rows the application paints outside the list
     */
    public record Config(
            List<Item> items,
            String title,
            Renderer renderer,
            FilterMode mode,
            TextInput.Config filterInput,
            Paginator.Appearance pagination,
            Spinner.Config spinner,
            KeyMap<?> extraHelp,
            Styles styles,
            Duration noticeLifetime,
            int rows,
            int reservedRows) {

        /**
         * The chrome styles: the title bar's text, the counts, and a transient message.
         *
         * @param title the title bar's text style
         * @param status the counts style
         * @param notice the transient message's style
         */
        public record Styles(Style title, Style status, Style notice) {

            /** Substitutes no styling for a missing style. */
            public Styles {
                title = title == null ? Style.NONE : title;
                status = status == null ? Style.NONE : status;
                notice = notice == null ? Style.NONE : notice;
            }

            /**
             * A bold title, plain counts and a bold transient message.
             *
             * @return the default styling
             */
            public static Styles defaults() {
                return new Styles(Style.NONE.withBold(), Style.NONE, Style.NONE.withBold());
            }

            /**
             * The same styling with a different title style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withTitle(Style value) {
                return new Styles(value, status, notice);
            }

            /**
             * The same styling with a different counts style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withStatus(Style value) {
                return new Styles(title, value, notice);
            }

            /**
             * The same styling with a different notice style.
             *
             * @param value the new style; null is no styling
             * @return the new styling
             */
            public Styles withNotice(Style value) {
                return new Styles(title, status, value);
            }
        }

        /** Substitutes defaults for missing values and rejects an impossible height. */
        public Config {
            items = items == null ? List.of() : List.copyOf(items);
            title = title == null ? "" : title;
            renderer = renderer == null ? DefaultRenderer.defaults() : renderer;
            mode = mode == null ? FilterMode.FUZZY : mode;
            filterInput = filterInput == null ? TextInput.Config.defaults() : filterInput;
            pagination = pagination == null ? Paginator.Appearance.dots() : pagination;
            spinner = spinner == null ? Spinner.Config.defaults() : spinner;
            extraHelp = extraHelp == null ? KeyMap.empty() : extraHelp;
            styles = styles == null ? Styles.defaults() : styles;
            noticeLifetime = noticeLifetime == null ? DEFAULT_NOTICE_LIFETIME : noticeLifetime;
            if (noticeLifetime.isNegative()) {
                throw new IllegalArgumentException("noticeLifetime must not be negative");
            }
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
            if (reservedRows < 0) {
                throw new IllegalArgumentException("reservedRows must not be negative");
            }
        }

        /**
         * The default definition: no items, an empty title, the default renderer, fuzzy matching,
         * dots, the default spinner and no extra help.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(
                    List.of(),
                    "",
                    DefaultRenderer.defaults(),
                    FilterMode.FUZZY,
                    TextInput.Config.defaults(),
                    Paginator.Appearance.dots(),
                    Spinner.Config.defaults(),
                    KeyMap.empty(),
                    Styles.defaults(),
                    DEFAULT_NOTICE_LIFETIME,
                    DEFAULT_ROWS,
                    0);
        }

        /**
         * The same definition with the items the list starts with.
         *
         * @param value the items; null is empty
         * @return the new definition
         */
        public Config withItems(List<Item> value) {
            return new Config(
                    value,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different title.
         *
         * @param value the title bar's text; null is empty
         * @return the new definition
         */
        public Config withTitle(String value) {
            return new Config(
                    items,
                    value,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different item renderer.
         *
         * @param value how items are painted; null is the default renderer
         * @return the new definition
         */
        public Config withRenderer(Renderer value) {
            return new Config(
                    items,
                    title,
                    value,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different filter mode.
         *
         * @param value the mode the list starts with; null is {@link FilterMode#FUZZY}
         * @return the new definition
         */
        public Config withMode(FilterMode value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    value,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different filter field policy.
         *
         * @param value the field's policy; null is {@link TextInput.Config#defaults()}
         * @return the new definition
         */
        public Config withFilterInput(TextInput.Config value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    value,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different page indicator.
         *
         * @param value the indicator's appearance; null is dots
         * @return the new definition
         */
        public Config withPagination(Paginator.Appearance value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    value,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different spinner.
         *
         * @param value the spinner's definition; null is {@link Spinner.Config#defaults()}
         * @return the new definition
         */
        public Config withSpinner(Spinner.Config value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    value,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with the application's own help actions.
         *
         * <p>Their gestures are not routed by the list: the application routes them around the
         * mounted component, and this map only adds what the list paints.</p>
         *
         * @param value the application's actions; null is none
         * @return the new definition
         */
        public Config withExtraHelp(KeyMap<?> value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    value,
                    styles,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with different chrome styles.
         *
         * @param value the styles; null is {@link Styles#defaults()}
         * @return the new definition
         */
        public Config withStyles(Styles value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    value,
                    noticeLifetime,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different notice lifetime.
         *
         * @param value how long a transient message stays; null is one second
         * @return the new definition
         */
        public Config withNoticeLifetime(Duration value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    value,
                    rows,
                    reservedRows);
        }

        /**
         * The same definition with a different default height.
         *
         * @param value the rows used when the terminal reports none; never negative
         * @return the new definition
         */
        public Config withRows(int value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    value,
                    reservedRows);
        }

        /**
         * The same definition with a different reservation outside the list.
         *
         * @param value the rows the application paints outside the list; never negative
         * @return the new definition
         */
        public Config withReservedRows(int value) {
            return new Config(
                    items,
                    title,
                    renderer,
                    mode,
                    filterInput,
                    pagination,
                    spinner,
                    extraHelp,
                    styles,
                    noticeLifetime,
                    rows,
                    value);
        }
    }

    /** Every message the list handles. */
    public sealed interface Msg {

        /** Moves the selection to the previous visible item. */
        record Up() implements Msg {}

        /** Moves the selection to the next visible item. */
        record Down() implements Msg {}

        /** Moves to the page before the one the selection is on. */
        record PreviousPage() implements Msg {}

        /** Moves to the page after the one the selection is on. */
        record NextPage() implements Msg {}

        /** Moves the selection to the first visible item. */
        record First() implements Msg {}

        /** Moves the selection to the last visible item. */
        record Last() implements Msg {}

        /** Starts editing the query. */
        record BeginFilter() implements Msg {}

        /** Applies the query being edited. */
        record AcceptFilter() implements Msg {}

        /**
         * Cancels the query being edited, clears an applied query, or reports that there is nothing
         * left to cancel - in that order.
         */
        record Cancel() implements Msg {}

        /** Clears the query and the selection it narrowed. */
        record ClearFilter() implements Msg {}

        /** Switches the filter between its plain and subsequence modes. */
        record ToggleFilterMode() implements Msg {}

        /**
         * Sets the filter mode.
         *
         * @param value the new mode
         */
        record SetFilterMode(FilterMode value) implements Msg {

            /** Rejects a missing mode. */
            public SetFilterMode {
                Objects.requireNonNull(value, "value");
            }
        }

        /**
         * A message from the composed filter field.
         *
         * @param value the field's message
         */
        record FilterInput(TextInput.Msg value) implements Msg {

            /** Rejects a missing message. */
            public FilterInput {
                Objects.requireNonNull(value, "value");
            }
        }

        /** Chooses the selected item. */
        record Choose() implements Msg {}

        /**
         * Selects one item, by id.
         *
         * <p>This is what a click on a row means. It selects rather than chooses because choosing is
         * the application's decision - a click that both moved the selection and asked the
         * application to act on it would take that decision away - so a second click on the row is
         * what asks for {@link Choose}, exactly as a second press of a double click does.</p>
         *
         * @param id the id of the item to select; null, or an id the query hides, leaves the
         *     selection where it is
         */
        record Select(String id) implements Msg {

            /** Rejects no id; a missing one names no item. */
            public Select {
                id = id == null ? "" : id;
            }
        }

        /**
         * Replaces every item.
         *
         * @param items the new items; null is empty
         */
        record SetItems(List<Item> items) implements Msg {}

        /**
         * Inserts an item into the stored list.
         *
         * @param index the position in the stored list; past the end appends
         * @param item the item to insert
         */
        record InsertItem(int index, Item item) implements Msg {

            /** Rejects a missing item. */
            public InsertItem {
                Objects.requireNonNull(item, "item");
            }
        }

        /**
         * Replaces the item with the same id.
         *
         * @param item the item to store; an id the list does not hold is a no-op
         */
        record ReplaceItem(Item item) implements Msg {

            /** Rejects a missing item. */
            public ReplaceItem {
                Objects.requireNonNull(item, "item");
            }
        }

        /**
         * Removes one item by its stable id and selects the nearest surviving visible item.
         *
         * @param id the id to remove; an id the list does not hold is a no-op
         */
        record RemoveItem(String id) implements Msg {}

        /**
         * Publishes a new height for the whole list, chrome included.
         *
         * @param rows the rows the list may paint
         */
        record RowsChanged(int rows) implements Msg {}

        /** Switches the title bar on or off. */
        record ToggleTitle() implements Msg {}

        /** Switches the status row on or off. */
        record ToggleStatus() implements Msg {}

        /** Switches the page indicator on or off. */
        record TogglePagination() implements Msg {}

        /** Switches help on or off. */
        record ToggleHelp() implements Msg {}

        /** Switches help between its compact line and its full grouped listing. */
        record ToggleExpandedHelp() implements Msg {}

        /** Switches the loading spinner on or off. */
        record ToggleLoading() implements Msg {}

        /**
         * A message from the composed spinner.
         *
         * @param value the spinner's message
         */
        record SpinnerMessage(Spinner.Msg value) implements Msg {

            /** Rejects a missing message. */
            public SpinnerMessage {
                Objects.requireNonNull(value, "value");
            }
        }

        /**
         * Paints a transient message for the notice lifetime that starts now.
         *
         * @param text the message; null or blank clears the current one
         */
        record Notice(String text) implements Msg {}

        /**
         * Clears the transient message with this epoch, if it is still the current one.
         *
         * @param epoch the notice the expiry was scheduled for
         */
        record NoticeExpired(long epoch) implements Msg {}
    }

    /** What the application should do about a choice. */
    public sealed interface Output {

        /**
         * The user chose an item.
         *
         * @param item the chosen item
         */
        record Chosen(Item item) implements Output {

            /** Rejects a missing item. */
            public Chosen {
                Objects.requireNonNull(item, "item");
            }
        }

        /** The user cancelled a browsing list that had nothing left to cancel. */
        record Cancelled() implements Output {}
    }

    /** A reduction result. */
    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /** What one item produces for a query: its title's highlights and the rank keys of its match. */
    private record Found(List<Highlight> highlights, int span, int first) {}

    private final Config config;
    private final TextInput filterField;
    private final Spinner spinner;

    /**
     * A list from its definition.
     *
     * @param config the immutable definition; required
     */
    public RichList(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.filterField = new TextInput(config.filterInput());
        this.spinner = new Spinner(config.spinner());
    }

    /**
     * The definition this list was built from.
     *
     * @return the definition
     */
    public Config config() {
        return config;
    }

    /**
     * The actions a list defines, before its state is taken into account.
     *
     * <p>The two {@code enter} actions are declared together and never enabled together: browsing
     * chooses, editing applies the query. {@link #keys(Model)} is the map for one state, and it is
     * what the component routes and what help renders.</p>
     *
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "up", "up", "k"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "down", "down", "j")),
                KeyMap.group(
                        "Page",
                        KeyAction.<Msg>of(
                                "page-previous", new Msg.PreviousPage(), "\u2190", "previous page", "left", "pageup"),
                        KeyAction.<Msg>of(
                                "page-next", new Msg.NextPage(), "\u2192", "next page", "right", "l", "pagedown"),
                        KeyAction.<Msg>of("first", new Msg.First(), "home", "first item", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("last", new Msg.Last(), "end", "last item", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Filter",
                        KeyAction.<Msg>of("filter", new Msg.BeginFilter(), "/", "filter", "/")
                                .withCompact(false),
                        KeyAction.<Msg>of("filter-mode", new Msg.ToggleFilterMode(), "ctrl+f", "plain/fuzzy", "ctrl+f")
                                .withCompact(false),
                        KeyAction.<Msg>of("accept-filter", new Msg.AcceptFilter(), "enter", "apply filter", "enter")
                                .withEnabled(false)),
                KeyMap.group("Actions", KeyAction.<Msg>of("choose", new Msg.Choose(), "enter", "choose", "enter")),
                KeyMap.group(
                        "Chrome",
                        KeyAction.<Msg>of("title", new Msg.ToggleTitle(), "t", "title", "t")
                                .withCompact(false),
                        KeyAction.<Msg>of("status", new Msg.ToggleStatus(), "c", "counts", "c")
                                .withCompact(false),
                        KeyAction.<Msg>of("pagination", new Msg.TogglePagination(), "p", "pagination", "p")
                                .withCompact(false),
                        KeyAction.<Msg>of("help", new Msg.ToggleHelp(), "h", "help", "h")
                                .withCompact(false),
                        KeyAction.<Msg>of("expand-help", new Msg.ToggleExpandedHelp(), "?", "full help", "?")
                                .withCompact(false),
                        KeyAction.<Msg>of("loading", new Msg.ToggleLoading(), "s", "loading", "s")
                                .withCompact(false)),
                KeyMap.group("General", KeyAction.<Msg>of("cancel", new Msg.Cancel(), "esc", "cancel", "escape")));
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
    public static KeyMap<Msg> keys(Model state) {
        Objects.requireNonNull(state, "state");
        boolean editing = state.filter().editing();
        boolean browsing = !editing && !state.items().isEmpty();
        KeyMap<Msg> keys = defaultKeys();
        for (String id : BROWSING) {
            keys = keys.withEnabled(id, browsing);
        }
        for (String id : CHROME) {
            keys = keys.withEnabled(id, !editing);
        }
        keys = keys.withEnabled("accept-filter", editing);
        // One gesture cancels everything: the edit being typed, then the applied query, then the
        // list itself. Binding a second action to it would make the winner depend on declaration
        // order, which is exactly what a key map refuses to do.
        KeyAction<Msg> cancel = keys.action("cancel").orElseThrow();
        return keys.with(cancel.withDescription(
                editing ? "cancel edit" : terms(state.filter().query()).isEmpty() ? "cancel" : "clear filter"));
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        // The startup geometry is the only size the list can know before a resize arrives: what the
        // application paints outside the list is taken out of it, and the configured height is the
        // fallback a headless test or a preview uses.
        int rows = context.viewport().rows() > 0 ? context.viewport().rows() - config.reservedRows() : config.rows();
        Model state = new Model(
                config.items(),
                "",
                Filter.of(config.mode()),
                Chrome.defaults(),
                rows,
                Spinner.Model.stopped(),
                Notice.none(),
                true);
        return Update.next(state);
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model,
                message,
                action -> reduce(model, config, action),
                state -> state.withFocus(model.focused()),
                state -> noticeEffect(model, state));
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        // Two sources at most: the terminal's size, and the spinner's interval while it is shown. A
        // list that is not loading owns no timer at all.
        return Subscription.batch(
                Subscription.resize(SIZE, size -> new Msg.RowsChanged(size.rows())),
                model.chrome().loading()
                        ? spinner.subscriptions(model.spinner()).map(Msg.SpinnerMessage::new)
                        : Subscription.none());
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        View.InputHandler<Msg> handler = event -> typing(model, event);
        // The list's own actions are the focused element's bindings, so they win over the field
        // while browsing and are disabled while editing; everything the bindings do not take is
        // offered to the field, and only then to the application around the mount.
        return Views.focus(
                ELEMENT,
                model.focused(),
                keys(model).bindings(),
                handler,
                pointer(model),
                Views.sized(size -> paint(model, size.cols())));
    }

    /**
     * The list's pointer handler: a click selects the row it landed on, a second click chooses what
     * the first selected, and the wheel steps the selection.
     *
     * <p>The row is found the way it was painted - past the chrome, through the page's items at
     * their own heights - so a click cannot land on an item the list is not showing. A click on the
     * chrome, on a blank row below the last item, or on a page indicator selects nothing.</p>
     */
    private View.PointerHandler<Msg> pointer(Model model) {
        return (event, region) -> {
            if (event.isWheel()) {
                return Optional.of(
                        switch (event.button()) {
                            case WHEEL_UP, WHEEL_LEFT -> new Msg.Up();
                            default -> new Msg.Down();
                        });
            }
            if (!event.isLeftPress()) {
                return Optional.empty();
            }
            String id = itemAt(model, region.cols(), event.row());
            if (id == null) {
                return Optional.empty();
            }
            // The first press of a double click already selected this row, so the second press is
            // the click on an already selected row - which is what choosing means here.
            return Optional.of(event.clickCount() > 1 ? new Msg.Choose() : new Msg.Select(id));
        };
    }

    /**
     * The id of the item painted on one row of the list, or null for a row no item covers.
     *
     * @param state the state the frame was painted from
     * @param cols the columns the list was painted in
     * @param row the row, in the list's own coordinates
     */
    private String itemAt(Model state, int cols, int row) {
        int local = row - contentTop(state);
        if (local < 0 || local >= contentRows(state, config)) {
            return null;
        }
        Resolved resolved = resolved(state);
        Page page = resolved.page();
        int used = 0;
        for (int index = page.start(); index < page.end(); index++) {
            Match match = resolved.visible().get(index);
            List<StyledText> painted = config.renderer()
                    .render(new Row(match.item(), match.id().equals(state.selectedId()), cols, match.highlights()));
            if (painted == null) {
                continue;
            }
            int rows = Math.min(height(config.renderer(), match.item()), painted.size());
            if (local < used + rows) {
                return match.id();
            }
            used += rows;
        }
        return null;
    }

    /** How many rows of chrome are painted above the items. */
    private static int contentTop(Model state) {
        return (titleVisible(state) ? 1 : 0) + (state.chrome().status() ? 1 : 0);
    }

    /** The message a typing gesture produces, or empty when the list is not editing or leaves it. */
    private Optional<Msg> typing(Model model, InputEvent event) {
        if (!model.filter().editing()) {
            return Optional.empty();
        }
        return TextInput.actionFor(model.filter().field(), event, config.filterInput())
                .map(Msg.FilterInput::new);
    }

    // ── painting ─────────────────────────────────────────────────────────────

    /** Every row the list paints, chrome included: exactly {@link Model#rows()} rows. */
    private View<Msg> paint(Model model, int columns) {
        int cols = Math.max(0, columns);
        List<Match> visible = visible(model);
        int contentRows = contentRows(model, config);
        Resolved resolved = resolved(model);
        List<View<Msg>> sections = new ArrayList<>(8);
        if (titleVisible(model)) {
            sections.add(Views.fixed(1, Views.text(oneRow(titleLine(model, cols), cols))));
        }
        if (model.chrome().status()) {
            sections.add(Views.fixed(1, Views.text(oneRow(statusLine(model, visible.size()), cols))));
        }
        sections.add(Views.fixed(contentRows, Views.column(contentRows(model, resolved, cols))));
        if (model.chrome().pagination()) {
            sections.add(Views.fixed(1, Views.text(oneRow(paginationLine(resolved, cols), cols))));
        }
        if (model.chrome().help()) {
            sections.add(Views.fixed(helpRows(model, config), Views.column(helpLines(model, cols))));
        }
        return Views.column(sections);
    }

    /** What a paint of this state resolves: the visible items, their pages and the selection. */
    private Resolved resolved(Model model) {
        List<Match> visible = visible(model);
        return new Resolved(
                visible, pack(visible, config, contentRows(model, config)), selectedIndex(visible, model.selectedId()));
    }

    /** What one paint resolved: the visible items, the pages they are packed into and the selection. */
    private record Resolved(List<Match> visible, List<Page> pages, int selected) {

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

    /** The painted items of one page, then blank rows to fill the content exactly. */
    private List<View<Msg>> contentRows(Model model, Resolved resolved, int cols) {
        List<View<Msg>> rows = new ArrayList<>();
        int remaining = contentRows(model, config);
        Page page = resolved.page();
        for (int index = page.start(); index < page.end() && remaining > 0; index++) {
            Match match = resolved.visible().get(index);
            boolean selected = match.id().equals(model.selectedId());
            List<StyledText> painted =
                    config.renderer().render(new Row(match.item(), selected, cols, match.highlights()));
            if (painted == null) {
                continue;
            }
            int height = height(config.renderer(), match.item());
            for (int row = 0; row < Math.min(height, painted.size()) && remaining > 0; row++) {
                rows.add(Views.fixed(1, Views.text(oneRow(painted.get(row), cols))));
                remaining--;
            }
        }
        if (remaining > 0) {
            rows.add(Views.gap(remaining));
        }
        return rows;
    }

    /** Whether the title bar is painted: it is what a query being typed is shown in. */
    private static boolean titleVisible(Model state) {
        return state.chrome().title() || state.filter().editing();
    }

    /** The title bar's row: the filter field while a query is edited, otherwise the title. */
    private StyledText titleLine(Model state, int cols) {
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

    /** The status row: how many items the query shows, then any transient message. */
    private StyledText statusLine(Model state, int shown) {
        StyledText line = StyledText.of(
                counts(shown, state.items().size()), config.styles().status());
        if (!state.notice().present()) {
            return line;
        }
        return line.append(StyledText.of(STATUS_SEPARATOR, config.styles().status()))
                .append(StyledText.of(state.notice().text(), config.styles().notice()));
    }

    /** The count-and-status text one paint shows, from the counts it already resolved. */
    private static String counts(int shown, int total) {
        if (total == 0) {
            return NO_ITEMS;
        }
        if (shown == 0) {
            return NO_MATCHES;
        }
        String items = shown == 1 ? "1 item" : shown + " items";
        return shown == total ? items : shown + " of " + (total == 1 ? "1 item" : total + " items");
    }

    /** The page indicator, painted by the composed {@link Paginator}. */
    private StyledText paginationLine(Resolved resolved, int cols) {
        Paginator.Config pager = new Paginator.Config(1, false, config.pagination());
        return Paginator.render(
                new Paginator.Model(pageOf(resolved), resolved.pages().size(), 1, false), pager, cols);
    }

    /**
     * The help rows for one state: the list's own block, then the application's.
     *
     * <p>They are two blocks rather than one merged line because the two maps belong to different
     * owners and have different message types; the list paints both and routes only its own.</p>
     */
    private List<View<Msg>> helpLines(Model state, int cols) {
        List<View<Msg>> lines = new ArrayList<>(2);
        lines.add(Views.text(Help.render(keys(state), state.chrome().expandedHelp(), cols)));
        if (!config.extraHelp().actions().isEmpty()) {
            lines.add(Views.text(Help.render(config.extraHelp(), state.chrome().expandedHelp(), cols)));
        }
        return lines;
    }

    /**
     * The rows help occupies, counted from the same renders that paint them. Help truncates a line
     * to its width instead of wrapping it, so a line count never depends on the width.
     */
    private static int helpRows(Model state, Config config) {
        if (!state.chrome().help()) {
            return 0;
        }
        Config opts = config == null ? Config.defaults() : config;
        int rows = Help.render(keys(state), state.chrome().expandedHelp(), HELP_WIDTH)
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
    public static List<Match> visible(Model state) {
        Objects.requireNonNull(state, "state");
        List<int[]> terms = terms(state.filter().query());
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
            Found result = found(terms, state.filter().mode(), item);
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
     * @param config the definition whose renderer measures the items; null is {@link Config#defaults()}
     * @return the pages, empty when no item is visible
     */
    public static List<Page> pages(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
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
     * @param config the definition; null is {@link Config#defaults()}
     * @return the zero-based page index, zero when no item is visible
     */
    public static int pageIndex(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        List<Match> visible = visible(state);
        Config opts = config == null ? Config.defaults() : config;
        return selectedPage(visible, pack(visible, opts, contentRows(state, opts)), state.selectedId());
    }

    /**
     * The rows the list's chrome occupies, whatever its items are.
     *
     * @param state the state to inspect; required
     * @param config the definition whose extra help is counted; null is {@link Config#defaults()}
     * @return the chrome rows
     */
    public static int chromeRows(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        int rows = titleVisible(state) ? 1 : 0;
        rows += state.chrome().status() ? 1 : 0;
        rows += state.chrome().pagination() ? 1 : 0;
        return rows + helpRows(state, config);
    }

    /**
     * The rows left for items after the chrome the state asks for.
     *
     * @param state the state to inspect; required
     * @param config the definition; null is {@link Config#defaults()}
     * @return the content rows, never negative
     */
    public static int contentRows(Model state, Config config) {
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
     * @param config the definition; null is {@link Config#defaults()}
     * @return the pager state
     */
    public static Paginator.Model pagination(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        List<Match> visible = visible(state);
        Config opts = config == null ? Config.defaults() : config;
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
        List<int[]> terms = terms(query);
        if (terms.isEmpty() || text == null || text.isEmpty()) {
            return List.of();
        }
        List<int[]> ranges = codePointRanges(terms, folded(text), mode == null ? FilterMode.FUZZY : mode);
        return ranges == null ? List.of() : highlights(ranges, text);
    }

    /** The rows one item occupies, never fewer than one. */
    private static int height(Renderer renderer, Item item) {
        return Math.max(1, renderer.height(item));
    }

    /** Packs visible items into pages of whole items that fit the budget. */
    private static List<Page> pack(List<Match> visible, Config config, int budget) {
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
                long rows = height(renderer, visible.get(end).item());
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

    /** The index of one id in a visible list, or -1. */
    private static int selectedIndex(List<Match> visible, String id) {
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /** The page one visible index is on, or zero when there are no pages. */
    private static int selectedPage(List<Match> visible, List<Page> pages, String id) {
        int selected = selectedIndex(visible, id);
        for (int index = 0; selected >= 0 && index < pages.size(); index++) {
            if (pages.get(index).contains(selected)) {
                return index;
            }
        }
        return 0;
    }

    /** The page one painted resolution shows. */
    private static int pageOf(Resolved resolved) {
        for (int index = 0; index < resolved.pages().size(); index++) {
            if (resolved.pages().get(index).contains(resolved.selected())) {
                return index;
            }
        }
        return 0;
    }

    /** The index of one id in an item list, or -1. */
    private static int itemIndex(List<Item> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    // ── reduction ────────────────────────────────────────────────────────────

    /** Applies one message. Never performs I/O, never mutates the input state and never swallows. */
    private Result reduce(Model state, Config config, Msg message) {
        return switch (message) {
            case Msg.Up up -> moved(state, -1);
            case Msg.Down down -> moved(state, 1);
            case Msg.First first -> new Result(state.withSelectedId(firstVisible(state)), null);
            case Msg.Last last -> new Result(state.withSelectedId(lastVisible(state)), null);
            case Msg.NextPage next -> turned(state, config, 1);
            case Msg.PreviousPage previous -> turned(state, config, -1);
            case Msg.BeginFilter begin -> editing(state);
            case Msg.AcceptFilter accept -> applied(state);
            case Msg.Cancel cancel -> cancelled(state);
            case Msg.ClearFilter clear -> cleared(state);
            case Msg.ToggleFilterMode toggle ->
                settled(state.withFilter(state.filter()
                        .withMode(state.filter().mode() == FilterMode.FUZZY ? FilterMode.PREFIX : FilterMode.FUZZY)));
            case Msg.SetFilterMode mode ->
                settled(state.withFilter(state.filter().withMode(mode.value())));
            case Msg.FilterInput input -> typed(state, input.value());
            case Msg.Choose choose -> chosen(state);
            case Msg.Select select -> settled(selectedTo(state, select.id()));
            case Msg.SetItems items -> settled(state.withItems(items.items()));
            case Msg.InsertItem insert -> inserted(state, insert.index(), insert.item());
            case Msg.ReplaceItem replace -> replaced(state, replace.item());
            case Msg.RemoveItem remove -> removed(state, remove.id());
            case Msg.RowsChanged rows -> settled(state.withRows(rows.rows() - config.reservedRows()));
            case Msg.ToggleTitle title ->
                settled(state.withChrome(
                        state.chrome().withTitle(!state.chrome().title())));
            case Msg.ToggleStatus status ->
                settled(state.withChrome(
                        state.chrome().withStatus(!state.chrome().status())));
            case Msg.TogglePagination pagination ->
                settled(state.withChrome(
                        state.chrome().withPagination(!state.chrome().pagination())));
            case Msg.ToggleHelp help ->
                settled(state.withChrome(state.chrome().withHelp(!state.chrome().help())));
            case Msg.ToggleExpandedHelp expanded ->
                settled(state.withChrome(
                        state.chrome().withExpandedHelp(!state.chrome().expandedHelp())));
            case Msg.ToggleLoading loading -> toggled(state);
            case Msg.SpinnerMessage animation ->
                settled(state.withSpinner(
                        spinner.update(state.spinner(), animation.value()).model()));
            case Msg.Notice notice -> noticed(state, notice.text());
            case Msg.NoticeExpired expired -> expired(state, expired.epoch());
        };
    }

    /** A reduction that changed state without asking the application for anything. */
    private static Result settled(Model state) {
        return new Result(state, null);
    }

    /** The state selecting one id, leaving the selection alone when the query does not show it. */
    private static Model selectedTo(Model state, String id) {
        for (Match match : visible(state)) {
            if (match.id().equals(id)) {
                return state.withSelectedId(id);
            }
        }
        return state;
    }

    /** One visible item up or down, clamped to the visible list. */
    private static Result moved(Model state, int delta) {
        List<Match> visible = visible(state);
        if (visible.isEmpty()) {
            return new Result(state, null);
        }
        int selected = Math.max(0, selectedIndex(visible, state.selectedId()));
        int target = Math.max(0, Math.min(selected + delta, visible.size() - 1));
        return new Result(state.withSelectedId(visible.get(target).id()), null);
    }

    /** One page forward or back, selecting the first item of the page arrived at. */
    private static Result turned(Model state, Config config, int delta) {
        List<Match> visible = visible(state);
        List<Page> pages = pack(visible, config, contentRows(state, config));
        if (pages.isEmpty()) {
            return new Result(state, null);
        }
        int current = selectedPage(visible, pages, state.selectedId());
        int target = Math.max(0, Math.min(current + delta, pages.size() - 1));
        if (target == current) {
            return new Result(state, null);
        }
        return new Result(
                state.withSelectedId(visible.get(pages.get(target).start()).id()), null);
    }

    /** Starts editing the query, remembering what was selected so a cancel can restore it. */
    private static Result editing(Model state) {
        if (state.filter().editing()) {
            return new Result(state, null);
        }
        return new Result(
                state.withFilter(state.filter().withEditing(true).withPrefilterSelection(state.selectedId())), null);
    }

    /** Applies the live query, which is already what the list shows. */
    private static Result applied(Model state) {
        if (!state.filter().editing()) {
            return new Result(state, null);
        }
        Filter filter =
                state.filter().withEditing(false).withApplied(state.filter().query());
        return new Result(state.withFilter(filter), null);
    }

    /**
     * Cancels: the edit being typed, then the applied query, then (with nothing left to undo) the
     * list itself. Restoring the remembered selection only moves the selection back when that item
     * is still one the query shows.
     */
    private static Result cancelled(Model state) {
        if (state.filter().editing()) {
            Filter filter = state.filter()
                    .withField(TextInput.Model.of(state.filter().applied()).withFocus(false))
                    .withPrefilterSelection("");
            return new Result(restored(state.withFilter(filter), state.filter().prefilterSelection()), null);
        }
        if (!terms(state.filter().query()).isEmpty()) {
            return cleared(state);
        }
        return new Result(state, new Output.Cancelled());
    }

    /** Clears the query, keeping the selection when it survives and choosing the first item otherwise. */
    private static Result cleared(Model state) {
        if (state.filter().query().isEmpty() && state.filter().applied().isEmpty()) {
            return new Result(state, null);
        }
        Filter filter =
                state.filter().withField(Filter.blurred()).withApplied("").withPrefilterSelection("");
        return new Result(state.withFilter(filter), null);
    }

    /** One message from the composed field, or the accept gesture it reports. */
    private Result typed(Model state, TextInput.Msg message) {
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

    /** Chooses the selected item, if the query still shows it. */
    private static Result chosen(Model state) {
        List<Match> visible = visible(state);
        int index = selectedIndex(visible, state.selectedId());
        return index < 0
                ? new Result(state, null)
                : new Result(state, new Output.Chosen(visible.get(index).item()));
    }

    /** Inserts an item into the stored list, keeping the selection. */
    private static Result inserted(Model state, int index, Item item) {
        List<Item> items = new ArrayList<>(state.items());
        items.add(Math.max(0, Math.min(index, items.size())), item);
        return new Result(state.withItems(items), null);
    }

    /** Replaces the item with the same id, keeping the selection when it survives. */
    private static Result replaced(Model state, Item item) {
        int index = itemIndex(state.items(), item.id());
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
    private static Result removed(Model state, String id) {
        int index = itemIndex(state.items(), id);
        if (index < 0) {
            return new Result(state, null);
        }
        int position = Math.max(0, selectedIndex(visible(state), id));
        List<Item> items = new ArrayList<>(state.items());
        items.remove(index);
        Model reduced = state.withItems(items);
        List<Match> after = visible(reduced);
        return new Result(
                after.isEmpty()
                        ? reduced
                        : reduced.withSelectedId(
                                after.get(Math.min(position, after.size() - 1)).id()),
                null);
    }

    /** Turns the spinner on or off with the loading chrome that paints it. */
    private Result toggled(Model state) {
        boolean loading = !state.chrome().loading();
        Model next = state.withChrome(state.chrome().withLoading(loading));
        Spinner.Msg action = loading ? new Spinner.Msg.Start() : new Spinner.Msg.Stop();
        return new Result(
                next.withSpinner(spinner.update(next.spinner(), action).model()), null);
    }

    /** A new transient message with a fresh epoch, or a cleared one when the text is blank. */
    private static Result noticed(Model state, String text) {
        if (text == null || text.isBlank()) {
            return state.notice().present()
                    ? new Result(state.withNotice(new Notice("", state.notice().epoch() + 1)), null)
                    : new Result(state, null);
        }
        return new Result(state.withNotice(new Notice(text, state.notice().epoch() + 1)), null);
    }

    /** Clears the transient message an expiry was scheduled for, ignoring a stale one. */
    private static Result expired(Model state, long epoch) {
        if (!state.notice().present() || epoch != state.notice().epoch()) {
            return new Result(state, null);
        }
        return new Result(state.withNotice(new Notice("", state.notice().epoch())), null);
    }

    /** The delay that clears a notice this reduction just set, or nothing when it set none. */
    private Command<Msg> noticeEffect(Model before, Model after) {
        if (after.notice().epoch() == before.notice().epoch() || !after.notice().present()) {
            return Command.none();
        }
        Msg expiry = new Msg.NoticeExpired(after.notice().epoch());
        return Command.delay(NOTICE_EFFECT, config.noticeLifetime(), expiry, expiry);
    }

    /** The first id the query shows, or empty. */
    private static String firstVisible(Model state) {
        List<Match> visible = visible(state);
        return visible.isEmpty() ? "" : visible.get(0).id();
    }

    /** The last id the query shows, or empty. */
    private static String lastVisible(Model state) {
        List<Match> visible = visible(state);
        return visible.isEmpty() ? "" : visible.get(visible.size() - 1).id();
    }

    /** The state with a remembered selection restored when that item is still visible. */
    private static Model restored(Model state, String id) {
        if (id.isEmpty()) {
            return state;
        }
        for (Match match : visible(state)) {
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
    private static String revealed(List<Item> items, Filter filter, String selectedId) {
        List<int[]> terms = terms(filter.query());
        if (terms.isEmpty()) {
            return selectedId;
        }
        String first = "";
        for (Item item : items) {
            if (found(terms, filter.mode(), item) == null) {
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

    // ── matching ─────────────────────────────────────────────────────────────

    /** One item's match: its title's highlights and the rank keys of the match in its filter text. */
    private static Found found(List<int[]> terms, FilterMode mode, Item item) {
        List<int[]> ranges = codePointRanges(terms, folded(item.filterText()), mode);
        if (ranges == null) {
            return null;
        }
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (int[] range : ranges) {
            first = Math.min(first, range[0]);
            last = Math.max(last, range[1] - 1);
        }
        String title = item.title();
        List<int[]> titleRanges =
                title.equals(item.filterText()) ? ranges : codePointRanges(terms, folded(title), mode);
        return new Found(titleRanges == null ? List.of() : highlights(titleRanges, title), last - first + 1, first);
    }

    /**
     * The ranges a query selects in one folded text, or null when a term does not match. Ranges are
     * code point indices, sorted and merged so two terms that overlap highlight once.
     */
    private static List<int[]> codePointRanges(List<int[]> terms, int[] text, FilterMode mode) {
        List<int[]> found = new ArrayList<>(terms.size());
        for (int[] term : terms) {
            if (mode == FilterMode.PREFIX) {
                int at = indexOf(text, term, 0);
                if (at < 0) {
                    return null;
                }
                found.add(new int[] {at, at + term.length});
                continue;
            }
            List<Integer> positions = fuzzyPositions(text, term);
            if (positions.isEmpty()) {
                return null;
            }
            int start = positions.get(0);
            int previous = start;
            for (int index = 1; index < positions.size(); index++) {
                int position = positions.get(index);
                if (position == previous + 1) {
                    previous = position;
                    continue;
                }
                found.add(new int[] {start, previous + 1});
                start = position;
                previous = position;
            }
            found.add(new int[] {start, previous + 1});
        }
        found.sort((left, right) ->
                left[0] != right[0] ? Integer.compare(left[0], right[0]) : Integer.compare(left[1], right[1]));
        List<int[]> merged = new ArrayList<>(found.size());
        for (int[] range : found) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
                continue;
            }
            merged.add(new int[] {range[0], range[1]});
        }
        return merged;
    }

    /** The first code point index of {@code needle} in {@code haystack} at or after {@code from}, or -1. */
    private static int indexOf(int[] haystack, int[] needle, int from) {
        if (needle.length == 0) {
            return Math.max(0, from);
        }
        for (int start = Math.max(0, from); start + needle.length <= haystack.length; start++) {
            int matched = 0;
            while (matched < needle.length && haystack[start + matched] == needle[matched]) {
                matched++;
            }
            if (matched == needle.length) {
                return start;
            }
        }
        return -1;
    }

    /**
     * The code point indices of the shortest window of {@code text} that spells {@code term} as a
     * subsequence, or an empty list when it does not.
     *
     * <p>The window is contracted from both ends: the forward scan finds the first complete match,
     * and the backward scan moves the start as late as it can go while still matching, which is what
     * makes the span the shortest one for that match. The next search starts one code point later,
     * so every possible window is considered once and the total work stays linear in the text for a
     * fixed term.</p>
     */
    private static List<Integer> fuzzyPositions(int[] text, int[] term) {
        if (term.length == 0) {
            return List.of();
        }
        int bestStart = -1;
        int bestEnd = -1;
        int start = 0;
        while (start + term.length <= text.length) {
            int index = start;
            int matched = 0;
            while (index < text.length && matched < term.length) {
                if (text[index] == term[matched]) {
                    matched++;
                }
                index++;
            }
            if (matched < term.length) {
                break;
            }
            int end = index;
            int back = end - 1;
            matched = term.length - 1;
            while (back >= start) {
                if (text[back] == term[matched]) {
                    matched--;
                    if (matched < 0) {
                        break;
                    }
                }
                back--;
            }
            if (bestStart < 0 || end - back < bestEnd - bestStart) {
                bestStart = back;
                bestEnd = end;
            }
            start = back + 1;
        }
        if (bestStart < 0) {
            return List.of();
        }
        List<Integer> positions = new ArrayList<>(term.length);
        int matched = 0;
        for (int index = bestStart; index < bestEnd && matched < term.length; index++) {
            if (text[index] == term[matched]) {
                positions.add(index);
                matched++;
            }
        }
        return positions;
    }

    /** The query's terms, folded to code points. */
    private static List<int[]> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<int[]> terms = new ArrayList<>(4);
        for (String term : query.strip().split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(folded(term));
            }
        }
        return terms;
    }

    /** One value as lowercased code points, with the simple mapping that never changes a length. */
    private static int[] folded(String text) {
        String value = text == null ? "" : text;
        int[] points = new int[value.codePointCount(0, value.length())];
        int index = 0;
        for (int offset = 0; offset < value.length(); ) {
            int point = value.codePointAt(offset);
            points[index++] = Character.toLowerCase(point);
            offset += Character.charCount(point);
        }
        return points;
    }

    /** The utf-16 offset of every code point of one value, plus the value's length. */
    private static int[] offsetsOf(String text) {
        int[] offsets = new int[text.codePointCount(0, text.length()) + 1];
        int index = 0;
        for (int offset = 0; offset < text.length(); ) {
            offsets[index++] = offset;
            offset += Character.charCount(text.codePointAt(offset));
        }
        offsets[index] = text.length();
        return offsets;
    }

    /** The highlighted ranges of one value: offsets widened to whole clusters, then merged. */
    private static List<Highlight> highlights(List<int[]> ranges, String text) {
        if (ranges.isEmpty() || text.isEmpty()) {
            return List.of();
        }
        int[] offsets = offsetsOf(text);
        List<Highlight> highlights = new ArrayList<>(ranges.size());
        for (int[] range : ranges) {
            int start = offsets[Math.min(range[0], offsets.length - 1)];
            int end = offsets[Math.min(range[1], offsets.length - 1)];
            if (end <= start) {
                continue;
            }
            // A style boundary inside a cluster would split it, so the range is widened to the
            // boundaries on either side of what matched.
            int from = TextMeasure.boundaryBefore(text, start);
            int to = TextMeasure.boundaryAfter(text, Math.max(from, end - 1));
            if (to <= from) {
                continue;
            }
            Highlight last = highlights.isEmpty() ? null : highlights.get(highlights.size() - 1);
            if (last != null && from <= last.end()) {
                highlights.set(highlights.size() - 1, new Highlight(last.start(), Math.max(last.end(), to)));
                continue;
            }
            highlights.add(new Highlight(from, to));
        }
        return List.copyOf(highlights);
    }

    /** One painted row: the value's line breaks become spaces and it is cut to the width. */
    private static StyledText oneRow(StyledText value, int width) {
        StyledText row = StyledText.empty();
        int used = 0;
        for (StyledText.Span span : value.spans()) {
            String text = LineBreaks.singleRow(span.text());
            int columns = TextMeasure.columns(text);
            if (used + columns <= width) {
                row = row.append(StyledText.of(text, span.style(), span.link()));
                used += columns;
                continue;
            }
            row = row.append(
                    StyledText.of(TextMeasure.truncate(text, Math.max(0, width - used)), span.style(), span.link()));
            break;
        }
        return row;
    }
}
