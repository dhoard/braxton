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
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Page state and a page indicator over items the application owns.
 *
 * <p>A {@code Paginator} owns one fact - which page is shown - and answers the questions a paged
 * list needs to paint that page: how many pages there are, whether there is a next or a previous
 * one, and the half-open item range the current page covers. It deliberately owns no items,
 * filters nothing and sorts nothing. The application keeps its own list; it slices that list with
 * {@link Model#bounds()} and publishes a new count when the list changes. There is no terminal
 * I/O, no effect and no timer, so the state is a value and the view is a pure function of it.</p>
 *
 * <h2>Page and bounds</h2>
 *
 * <p>A model is {@code (page, itemCount, perPage)} plus the focus flag the application owns. The
 * page index is zero-based and is kept valid by the canonical constructor: a page past the end is
 * clamped to the last page, a negative page to the first, and a negative item count to zero. A page
 * size is a definition, not a value to repair, so a non-positive one is rejected where it is made
 * - by {@link Model}, {@link Config} and {@link Msg.PageSizeChanged} - because a page that can
 * never hold an item would be a phantom page.</p>
 *
 * <p>Empty content has zero pages and an empty range: {@code bounds()} is {@code [0,0)} and the
 * page index is zero, so a consumer never has to special-case a phantom page. The bounds are a
 * {@link Bounds} half-open interval, {@code [start,end)}, always inside {@code [0,itemCount]}.
 * Splitting a list by those ranges covers every item exactly once and never reads past the end, so
 * {@code items.subList(bounds.start(), bounds.end())} is always legal.</p>
 *
 * <p>All page arithmetic is done in {@code long} and clamped before it narrows, so a count or a
 * page size near {@link Integer#MAX_VALUE} cannot overflow into a negative index or a page that
 * does not exist.</p>
 *
 * <h2>Navigation</h2>
 *
 * <p>{@link Msg.Next}, {@link Msg.Previous}, {@link Msg.First}, {@link Msg.Last} and {@link
 * Msg.GoTo} move the page; {@link Msg.ItemCountChanged} and {@link Msg.PageSizeChanged} publish a
 * new fact and let the model clamp the page. Wrap-around navigation is {@link Config#wrap()} and
 * is off by default: {@code next} on the last page is then a no-op, not an invisible jump to the
 * beginning. A message that changes nothing returns the incoming model instance, which is the
 * unchanged-reference rule every bundled widget follows.</p>
 *
 * <p>The parent owns everything the paginator deliberately does not: item storage, filtering,
 * selection, and which page a selection should be brought to. Moving the page to follow a
 * selection is a parent decision expressed as {@link Msg.GoTo}.</p>
 *
 * <h2>Indicators</h2>
 *
 * <p>{@link #render} paints {@link Appearance#mode()} as either dots or a numeric
 * {@code current/total} label. The active dot and the inactive dots carry their own styles, so a
 * page indicator can be coloured without a second widget. A dot glyph is a display decision and may
 * be a wide emoji; it is normalized to one row when the appearance is built.</p>
 *
 * <p>Dots are windowed, never one per page: at most {@link Appearance#maxDots()} dots are painted
 * around the current page, and an ellipsis marks each side where pages are hidden. The cost of a
 * frame therefore follows the configured window, not the number of pages, and a list with millions
 * of pages paints exactly as many cells as a short one. Zero pages has one explicit indicator,
 * {@value #EMPTY_INDICATOR}, in both modes.</p>
 *
 * <p>An indicator is truncated to the width it is given by display columns and never through a
 * grapheme cluster, so a narrow pane gets a shorter window, then an ellipsis, rather than half a
 * wide glyph. A width of zero or less paints nothing.</p>
 *
 * <h2>Focus, keys and help</h2>
 *
 * <p>Focus is model data the application owns and a reduction never changes, exactly as in {@link
 * Selector} and {@link ScrollView}. The component declares one focus element around its indicator
 * and consumes only the gestures its {@link KeyMap} names, so an unfocused paginator receives no
 * keys and a focused one leaves text input and every other gesture to its parent. {@link
 * #defaultKeys()} moves a page and jumps to the first or the last one; {@link #keys()} is the map
 * the component routes, so {@link Help} describes the gestures that actually fire.</p>
 *
 * <p>This component has no semantic outputs; it is {@link Never}-typed, like any component whose
 * interactions are all state changes the parent can observe.</p>
 */
public final class Paginator implements Component<Paginator.Model, Paginator.Msg, Never> {

    /** The page size a paginator uses unless it is configured otherwise. */
    public static final int DEFAULT_PER_PAGE = 10;

    /** The number of dots a default indicator paints at most. */
    public static final int DEFAULT_MAX_DOTS = 7;

    /** The glyph a default indicator paints for the page being shown. */
    public static final String DEFAULT_ACTIVE_DOT = "\u25CF";

    /** The glyph a default indicator paints for a page that is not the current one. */
    public static final String DEFAULT_INACTIVE_DOT = "\u25CB";

    /** Ends an indicator cut by the available width, and marks hidden pages beside the window. */
    public static final String ELLIPSIS = "\u2026";

    /** The indicator zero pages paints, in both modes, instead of leaving the row blank. */
    public static final String EMPTY_INDICATOR = "0/0";

    /** Separates the cells of a dot indicator. */
    public static final String CELL_SEPARATOR = " ";

    /**
     * What an indicator paints instead of dots: the active and inactive dot glyphs and styles.
     *
     * @param mode whether pages are painted as dots or as one numeric label
     * @param activeDot the glyph of the current page; at least one column wide
     * @param inactiveDot the glyph of every other page; at least one column wide
     * @param activeStyle the style of the current page's glyph and of the numeric label
     * @param inactiveStyle the style of every other dot, of the ellipses and of the separators
     * @param maxDots the largest number of dots a window paints, however many pages there are
     */
    public record Appearance(
            Mode mode, String activeDot, String inactiveDot, Style activeStyle, Style inactiveStyle, int maxDots) {

        /** Rejects a glyph that cannot be painted and a window that cannot hold one page. */
        public Appearance {
            mode = mode == null ? Mode.DOTS : mode;
            activeDot = glyph(activeDot, DEFAULT_ACTIVE_DOT);
            inactiveDot = glyph(inactiveDot, DEFAULT_INACTIVE_DOT);
            activeStyle = activeStyle == null ? Style.NONE : activeStyle;
            inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
            if (maxDots < 1) {
                throw new IllegalArgumentException("maxDots must be positive");
            }
        }

        /**
         * The default dot indicator: a bold active dot, plain inactive dots, seven dots at most.
         *
         * @return the default appearance
         */
        public static Appearance dots() {
            return new Appearance(
                    Mode.DOTS,
                    DEFAULT_ACTIVE_DOT,
                    DEFAULT_INACTIVE_DOT,
                    Style.NONE.withBold(),
                    Style.NONE,
                    DEFAULT_MAX_DOTS);
        }

        /**
         * The default numeric indicator: the current page and the total, in the active style.
         *
         * @return the default appearance
         */
        public static Appearance numeric() {
            return new Appearance(
                    Mode.NUMERIC,
                    DEFAULT_ACTIVE_DOT,
                    DEFAULT_INACTIVE_DOT,
                    Style.NONE.withBold(),
                    Style.NONE,
                    DEFAULT_MAX_DOTS);
        }

        /**
         * The same appearance with a different mode.
         *
         * @param value what the indicator paints
         * @return the new appearance
         */
        public Appearance withMode(Mode value) {
            return new Appearance(value, activeDot, inactiveDot, activeStyle, inactiveStyle, maxDots);
        }

        /**
         * The same appearance with a different active dot.
         *
         * @param value the glyph of the current page; null falls back to the default, and a blank
         *     glyph or one that is only a line break is rejected
         * @return the new appearance
         */
        public Appearance withActiveDot(String value) {
            return new Appearance(mode, value, inactiveDot, activeStyle, inactiveStyle, maxDots);
        }

        /**
         * The same appearance with a different inactive dot.
         *
         * @param value the glyph of every other page; null falls back to the default, and a blank
         *     glyph or one that is only a line break is rejected
         * @return the new appearance
         */
        public Appearance withInactiveDot(String value) {
            return new Appearance(mode, activeDot, value, activeStyle, inactiveStyle, maxDots);
        }

        /**
         * The same appearance with different styles.
         *
         * @param active the style of the current page and of the numeric label; null is no styling
         * @param inactive the style of every other dot, the ellipses and the separators; null is no
         *     styling
         * @return the new appearance
         */
        public Appearance withStyles(Style active, Style inactive) {
            return new Appearance(mode, activeDot, inactiveDot, active, inactive, maxDots);
        }

        /**
         * The same appearance with a different window.
         *
         * @param value the largest number of dots to paint; must be positive
         * @return the new appearance
         */
        public Appearance withMaxDots(int value) {
            return new Appearance(mode, activeDot, inactiveDot, activeStyle, inactiveStyle, value);
        }

        /** A glyph as one painted cell: one row, at least one column, never blank. */
        private static String glyph(String value, String fallback) {
            // A break cannot be painted as one dot and a blank glyph would make a page invisible, so
            // both are normalized here rather than left to produce a cell nothing can read.
            String glyph =
                    LineBreaks.singleRow(value == null ? fallback : value).strip();
            if (glyph.isEmpty()) {
                throw new IllegalArgumentException("a dot glyph must not be blank");
            }
            return glyph;
        }
    }

    /** What a page indicator paints. */
    public enum Mode {
        /** One glyph per visible page, in the configured window. */
        DOTS,
        /** One label: the displayed page, a slash, and the page count. */
        NUMERIC
    }

    /**
     * Immutable pagination definition.
     *
     * @param perPage the size of a page, in items
     * @param wrap whether the last page's next goes to the first and the first page's previous to
     *     the last
     * @param appearance what the indicator paints
     */
    public record Config(int perPage, boolean wrap, Appearance appearance) {

        /** Rejects a page size that cannot hold an item and substitutes the default appearance. */
        public Config {
            if (perPage <= 0) {
                throw new IllegalArgumentException("perPage must be positive");
            }
            appearance = appearance == null ? Appearance.dots() : appearance;
        }

        /**
         * Ten items a page, no wrap-around, and the default dot indicator.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(DEFAULT_PER_PAGE, false, Appearance.dots());
        }

        /**
         * The same definition with a different page size.
         *
         * @param value the size of a page, in items; must be positive
         * @return the new definition
         */
        public Config withPerPage(int value) {
            return new Config(value, wrap, appearance);
        }

        /**
         * The same definition with a different wrap-around policy.
         *
         * @param value whether navigation wraps at either end
         * @return the new definition
         */
        public Config withWrap(boolean value) {
            return new Config(perPage, value, appearance);
        }

        /**
         * The same definition with a different indicator.
         *
         * @param value what the indicator paints; null restores the default dots
         * @return the new definition
         */
        public Config withAppearance(Appearance value) {
            return new Config(perPage, wrap, value);
        }
    }

    /**
     * The half-open item range one page covers.
     *
     * <p>{@code [start,end)} is always inside {@code [0,itemCount]} and is empty when the content
     * has no pages. It is the value a consumer slices its own list with: {@code
     * items.subList(bounds.start(), bounds.end())}.</p>
     *
     * @param start the first item of the page
     * @param end the item after the last one of the page
     */
    public record Bounds(int start, int end) {

        /** Rejects a range that is not a non-negative half-open interval. */
        public Bounds {
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
         * @return {@code end - start}, never negative
         */
        public int length() {
            return end - start;
        }

        /**
         * Whether the range covers no item at all.
         *
         * @return whether the page is empty
         */
        public boolean isEmpty() {
            return start == end;
        }

        /**
         * Whether an item index is on this page.
         *
         * @param index the item index to test
         * @return whether {@code start <= index < end}
         */
        public boolean contains(int index) {
            return index >= start && index < end;
        }
    }

    /**
     * Immutable page state.
     *
     * <p>The canonical constructor keeps every page valid: a negative count is zero, a negative
     * page is the first, and a page past the end is the last. A non-positive page size is a
     * definition error and is rejected, not repaired.</p>
     *
     * @param page the zero-based index of the page being shown
     * @param itemCount the number of items the pages cover
     * @param perPage the size of a page, in items
     * @param focused whether the application has focused this paginator
     */
    public record Model(int page, int itemCount, int perPage, boolean focused) {

        /** Normalizes the count and the page and rejects an impossible page size. */
        public Model {
            if (perPage <= 0) {
                throw new IllegalArgumentException("perPage must be positive");
            }
            itemCount = Math.max(0, itemCount);
            page = clamp(page, itemCount, perPage);
        }

        /** The page clamped to the pages the content has, zero when it has none. */
        private static int clamp(int page, int itemCount, int perPage) {
            // The same ceiling division totalPages() uses, widened before it is narrowed.
            int pages = (int) (((long) itemCount + perPage - 1) / perPage);
            return pages == 0 ? 0 : Math.max(0, Math.min(page, pages - 1));
        }

        /**
         * The first page of {@code itemCount} items, focused.
         *
         * @param itemCount the number of items the pages cover; a negative count is zero
         * @param perPage the size of a page, in items; must be positive
         * @return the starting state
         */
        public static Model of(int itemCount, int perPage) {
            return new Model(0, itemCount, perPage, true);
        }

        /**
         * The same state with a different focus.
         *
         * @param value whether this paginator owns focus
         * @return this instance when the focus did not change, otherwise the new state
         */
        public Model withFocus(boolean value) {
            return value == focused ? this : new Model(page, itemCount, perPage, value);
        }

        /**
         * The number of pages the content has.
         *
         * @return the page count, zero when there are no items
         */
        public int totalPages() {
            // Widened so a count near Integer.MAX_VALUE cannot overflow the ceiling division.
            return (int) (((long) itemCount + perPage - 1) / perPage);
        }

        /**
         * Whether there is a page to show at all.
         *
         * @return whether the content has at least one page
         */
        public boolean hasPages() {
            return itemCount > 0;
        }

        /**
         * Whether a previous page exists.
         *
         * @return whether the current page is not the first
         */
        public boolean hasPrevious() {
            return page > 0;
        }

        /**
         * Whether a next page exists.
         *
         * @return whether the current page is not the last
         */
        public boolean hasNext() {
            return page + 1 < totalPages();
        }

        /**
         * Whether the current page is the first one.
         *
         * <p>Empty content has one position and no pages, so it is both first and last.</p>
         *
         * @return whether no page precedes this one
         */
        public boolean isFirstPage() {
            return page == 0;
        }

        /**
         * Whether the current page is the last one.
         *
         * <p>Empty content has one position and no pages, so it is both first and last.</p>
         *
         * @return whether no page follows this one
         */
        public boolean isLastPage() {
            return page == Math.max(0, totalPages() - 1);
        }

        /**
         * The item range the current page covers.
         *
         * @return the half-open bounds, empty when the content has no pages
         */
        public Bounds bounds() {
            if (itemCount == 0) {
                return new Bounds(0, 0);
            }
            // The page is always below the page count, so the start is below the item count and
            // fits; the end is computed wide and only then narrowed against the count.
            long start = (long) page * perPage;
            long end = Math.min((long) itemCount, start + perPage);
            return new Bounds((int) start, (int) end);
        }
    }

    /** One navigation gesture or one fact about the content or the page size. */
    public sealed interface Msg {

        /** Moves to the next page, wrapping to the first when the configuration allows it. */
        record Next() implements Msg {}

        /** Moves to the previous page, wrapping to the last when the configuration allows it. */
        record Previous() implements Msg {}

        /** Moves to the first page. */
        record First() implements Msg {}

        /** Moves to the last page. */
        record Last() implements Msg {}

        /**
         * Moves to a page, clamped to the pages that exist.
         *
         * @param page the zero-based page to show
         */
        record GoTo(int page) implements Msg {}

        /**
         * Publishes a new item count, clamping the current page.
         *
         * @param itemCount the number of items the pages cover; a negative count is zero
         */
        record ItemCountChanged(int itemCount) implements Msg {}

        /**
         * Publishes a new page size, clamping the current page.
         *
         * @param perPage the size of a page, in items; must be positive
         */
        record PageSizeChanged(int perPage) implements Msg {
            public PageSizeChanged {
                if (perPage <= 0) {
                    throw new IllegalArgumentException("perPage must be positive");
                }
            }
        }
    }

    /** A reduction result. The paginator has no semantic outputs, so the intent is always absent. */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    /** The identity of the paginator's focus element; a mount scopes it to its own path. */
    private static final ElementId ELEMENT = ElementId.of("paginator");

    private final Config config;
    private final KeyMap<Msg> keys;

    /**
     * A paginator with the default keys.
     *
     * @param config the immutable pagination definition; required
     */
    public Paginator(Config config) {
        this(config, defaultKeys());
    }

    /**
     * A paginator with its own keys.
     *
     * @param config the immutable pagination definition; required
     * @param keys the actions this paginator handles, matched while it is focused; required
     */
    public Paginator(Config config, KeyMap<Msg> keys) {
        this.config = Objects.requireNonNull(config, "config");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * The default actions: next, previous, first and last page.
     *
     * <p>They are the definition this widget routes and the definition {@link Help} renders, so a
     * caller that remaps one action through {@link KeyMap#withAliases} changes both.</p>
     *
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Paging",
                        KeyAction.<Msg>of(
                                "previous", new Msg.Previous(), "\u2190", "previous page", "left", "h", "pageup"),
                        KeyAction.<Msg>of("next", new Msg.Next(), "\u2192", "next page", "right", "l", "pagedown")),
                KeyMap.group(
                        "Jump",
                        KeyAction.<Msg>of("first", new Msg.First(), "home", "first page", "home"),
                        KeyAction.<Msg>of("last", new Msg.Last(), "end", "last page", "end")));
    }

    /**
     * The actions this paginator handles.
     *
     * @return the key map
     */
    public KeyMap<Msg> keys() {
        return keys;
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        return Update.next(new Model(0, 0, config.perPage(), true));
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        // The shared widget rule applies: a message that changes nothing keeps the incoming
        // instance, and a reduction never moves the focus the application owns.
        return WidgetUpdate.settled(
                model,
                message,
                action -> new Result(reduce(model, config, action), null),
                state -> state.withFocus(model.focused()));
    }

    /**
     * Describes the indicator this state paints.
     *
     * <p>The indicator is built from the columns the view is actually given, so a paginator in a
     * narrow pane paints fewer dots instead of overflowing its row.</p>
     */
    @Override
    public View<Msg> view(Model model, ViewContext context) {
        View.InputHandler<Msg> handler = this::actionFor;
        return Views.focus(
                ELEMENT,
                model.focused(),
                List.of(),
                handler,
                Views.sized(size -> Views.text(render(model, config, size.cols()))));
    }

    /**
     * Paints the indicator for one state.
     *
     * @param state the page state; required
     * @param config the definition; null is {@link Config#defaults()}
     * @param width the available display columns; zero or less paints nothing
     * @return the indicator, never wider than {@code width}
     */
    public static StyledText render(Model state, Config config, int width) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        int columns = Math.max(0, width);
        return switch (opts.appearance().mode()) {
            case DOTS -> dots(state, opts.appearance(), columns);
            case NUMERIC -> numeric(state, opts.appearance(), columns);
        };
    }

    /** Maps one input event to a paging message, or empty when the gesture is not a pager gesture. */
    private Optional<Msg> actionFor(InputEvent event) {
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        return keys.actionFor(keyInput.key()).map(KeyAction::message);
    }

    /** Applies one gesture. Never performs I/O and never mutates the input state. */
    private static Model reduce(Model state, Config config, Msg action) {
        return switch (action) {
            case Msg.Next next -> moved(state, state.page() + 1, config.wrap());
            case Msg.Previous previous -> moved(state, state.page() - 1, config.wrap());
            case Msg.First first -> at(state, 0);
            case Msg.Last last -> at(state, state.totalPages() - 1);
            case Msg.GoTo go -> at(state, go.page());
            // Both facts keep the page index they carry and let the model clamp it: a shorter list
            // or a larger page can leave the current page past the end.
            case Msg.ItemCountChanged changed ->
                new Model(state.page(), changed.itemCount(), state.perPage(), state.focused());
            case Msg.PageSizeChanged changed ->
                new Model(state.page(), state.itemCount(), changed.perPage(), state.focused());
        };
    }

    /** One step, wrapping only where the configuration asks for it and pages exist. */
    private static Model moved(Model state, int target, boolean wrap) {
        int pages = state.totalPages();
        if (pages == 0) {
            return state;
        }
        if (target < 0) {
            return wrap ? at(state, pages - 1) : state;
        }
        // A target past the last page is the arithmetic of `next` at the end; nothing wider than a
        // page index can be produced here, so the comparison cannot overflow.
        return target >= pages ? (wrap ? at(state, 0) : state) : at(state, target);
    }

    /** A page, clamped; the incoming instance when the page does not change. */
    private static Model at(Model state, int page) {
        int pages = state.totalPages();
        int target = pages == 0 ? 0 : Math.max(0, Math.min(page, pages - 1));
        return target == state.page() ? state : new Model(target, state.itemCount(), state.perPage(), state.focused());
    }

    /** The dot indicator: a bounded window around the current page, with an ellipsis each side. */
    private static StyledText dots(Model state, Appearance look, int width) {
        if (width <= 0) {
            return StyledText.empty();
        }
        int pages = state.totalPages();
        if (pages == 0) {
            return fit(StyledText.of(EMPTY_INDICATOR, look.inactiveStyle()), width);
        }
        // A cell costs at least one column and a separator, so this bound is reached before the
        // loop starts: neither the page count nor a large configured window makes it long.
        int largest = Math.min(Math.min(look.maxDots(), pages), width / 2 + 1);
        for (int size = largest; size >= 1; size--) {
            int start = windowStart(state.page(), pages, size);
            int end = start + size;
            StyledText line = windowLine(start, end, state.page(), start > 0, end < pages, look);
            if (line.columns() <= width) {
                return line;
            }
        }
        // One dot is still wider than the row - a wide glyph in a one-cell pane - so the row gets
        // the ellipsis a truncated indicator ends with rather than half a glyph.
        return fit(StyledText.of(look.activeDot(), look.activeStyle()), width);
    }

    /** The first page of a window of {@code size} pages, centred on the current page where it can be. */
    private static int windowStart(int page, int pages, int size) {
        int centred = page - (size - 1) / 2;
        return Math.max(0, Math.min(centred, pages - size));
    }

    /** One row of cells: ellipsis, the window's dots, ellipsis. */
    private static StyledText windowLine(
            int start, int end, int active, boolean leading, boolean trailing, Appearance look) {
        StyledText line = StyledText.empty();
        if (leading) {
            line = append(line, StyledText.of(ELLIPSIS, look.inactiveStyle()), look);
        }
        for (int index = start; index < end; index++) {
            boolean current = index == active;
            line = append(
                    line,
                    StyledText.of(
                            current ? look.activeDot() : look.inactiveDot(),
                            current ? look.activeStyle() : look.inactiveStyle()),
                    look);
        }
        if (trailing) {
            line = append(line, StyledText.of(ELLIPSIS, look.inactiveStyle()), look);
        }
        return line;
    }

    /** Appends one cell with the separator a non-first cell is preceded by. */
    private static StyledText append(StyledText line, StyledText cell, Appearance look) {
        return line.isEmpty()
                ? cell
                : line.append(StyledText.of(CELL_SEPARATOR, look.inactiveStyle()))
                        .append(cell);
    }

    /** The numeric indicator: the displayed page, a slash, and the page count. */
    private static StyledText numeric(Model state, Appearance look, int width) {
        if (width <= 0) {
            return StyledText.empty();
        }
        String label = state.totalPages() == 0 ? EMPTY_INDICATOR : (state.page() + 1) + "/" + state.totalPages();
        return fit(StyledText.of(label, look.activeStyle()), width);
    }

    /**
     * Cuts an indicator to the available columns, ending it with {@link #ELLIPSIS} when anything
     * was dropped. The ellipsis replaces the last column rather than overflowing it, and a row too
     * narrow to hold even the ellipsis paints nothing, so the result never exceeds the width and
     * never contains part of a grapheme cluster.
     */
    private static StyledText fit(StyledText text, int width) {
        if (text.columns() <= width) {
            return text;
        }
        int ellipsis = TextMeasure.columns(ELLIPSIS);
        if (width < ellipsis) {
            return StyledText.empty();
        }
        Style style = text.spans().isEmpty()
                ? Style.NONE
                : text.spans().get(text.spans().size() - 1).style();
        return StyledText.of(TextMeasure.truncate(text.plainText(), width - ellipsis) + ELLIPSIS, style);
    }
}
