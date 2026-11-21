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

import static io.github.dhoard.braxton.widget.RichList.DEFAULT_NOTICE_LIFETIME;
import static io.github.dhoard.braxton.widget.RichList.DEFAULT_ROWS;

import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.widget.RichList.FilterMode;
import io.github.dhoard.braxton.widget.RichList.Item;
import io.github.dhoard.braxton.widget.RichList.Renderer;
import java.time.Duration;
import java.util.List;

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
public record RichListConfig(
        List<Item> items,
        String title,
        Renderer renderer,
        FilterMode mode,
        TextInputConfig filterInput,
        Paginator.Appearance pagination,
        Spinner.Config spinner,
        KeyMap<?> extraHelp,
        Styles styles,
        Duration noticeLifetime,
        int rows,
        int reservedRows) {

    /**
     * The chrome styles: the title bar's text, the counts, a transient message and the group
     * headers.
     *
     * @param title the title bar's text style
     * @param status the counts style
     * @param notice the transient message's style
     * @param group the style of a group header row
     */
    public record Styles(Style title, Style status, Style notice, Style group) {

        /**
         * Substitutes no styling for a missing style.
         */
        public Styles {
            title = title == null ? Style.NONE : title;
            status = status == null ? Style.NONE : status;
            notice = notice == null ? Style.NONE : notice;
            group = group == null ? Style.NONE : group;
        }

        /**
         * The same styling with no group header style.
         *
         * @param title the title bar's text style
         * @param status the counts style
         * @param notice the transient message's style
         */
        public Styles(Style title, Style status, Style notice) {
            this(title, status, notice, Style.NONE);
        }

        /**
         * A bold title, plain counts, a bold transient message and a bold group header.
         *
         * @return the default styling
         */
        public static Styles defaults() {
            return new Styles(Style.NONE.withBold(), Style.NONE, Style.NONE.withBold(), Style.NONE.withBold());
        }

        /**
         * The same styling with a different title style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withTitle(Style value) {
            return new Styles(value, status, notice, group);
        }

        /**
         * The same styling with a different counts style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withStatus(Style value) {
            return new Styles(title, value, notice, group);
        }

        /**
         * The same styling with a different notice style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withNotice(Style value) {
            return new Styles(title, status, value, group);
        }

        /**
         * The same styling with a different group header style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withGroup(Style value) {
            return new Styles(title, status, notice, value);
        }
    }

    /**
     * Substitutes defaults for missing values and rejects an impossible height.
     */
    public RichListConfig {
        items = items == null ? List.of() : List.copyOf(items);
        title = title == null ? "" : title;
        renderer = renderer == null ? RichListDefaultRenderer.defaults() : renderer;
        mode = mode == null ? FilterMode.FUZZY : mode;
        filterInput = filterInput == null ? TextInputConfig.defaults() : filterInput;
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
    public static RichListConfig defaults() {
        return new RichListConfig(
                List.of(),
                "",
                RichListDefaultRenderer.defaults(),
                FilterMode.FUZZY,
                TextInputConfig.defaults(),
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
    public RichListConfig withItems(List<Item> value) {
        return new RichListConfig(
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
    public RichListConfig withTitle(String value) {
        return new RichListConfig(
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
    public RichListConfig withRenderer(Renderer value) {
        return new RichListConfig(
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
    public RichListConfig withMode(FilterMode value) {
        return new RichListConfig(
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
     * @param value the field's policy; null is {@link TextInputConfig#defaults()}
     * @return the new definition
     */
    public RichListConfig withFilterInput(TextInputConfig value) {
        return new RichListConfig(
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
    public RichListConfig withPagination(Paginator.Appearance value) {
        return new RichListConfig(
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
    public RichListConfig withSpinner(Spinner.Config value) {
        return new RichListConfig(
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
    public RichListConfig withExtraHelp(KeyMap<?> value) {
        return new RichListConfig(
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
    public RichListConfig withStyles(Styles value) {
        return new RichListConfig(
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
    public RichListConfig withNoticeLifetime(Duration value) {
        return new RichListConfig(
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
    public RichListConfig withRows(int value) {
        return new RichListConfig(
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
    public RichListConfig withReservedRows(int value) {
        return new RichListConfig(
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
