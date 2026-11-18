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
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A tab bar with stable identities, a keyboard-selected tab, and a frame for the page it selects.
 *
 * <p>A {@code Tabs} owns one fact - which tab is shown - and the labels of the tabs, not their
 * contents. Each tab has an id the application chose, a one-row label and an enabled flag; the
 * active tab is stored as an id rather than an index, so reordering or renaming a tab list keeps the
 * page the user is looking at, and a page's own state is never keyed by where the tab happens to sit.
 * Everything else is the parent's: page models, which pages are mounted, and what a page does. There
 * is no terminal I/O, no effect and no timer, so the state is a value and the view is a pure function
 * of it.</p>
 *
 * <h2>Identity, order and enablement</h2>
 *
 * <p>A model is {@code (tabs, activeId, focused)} plus the immutable {@link Config}. The canonical
 * constructor keeps the active id valid: an id that is absent, blank or belongs to a disabled tab is
 * replaced by the first enabled tab, and a model whose tabs are all disabled carries no active tab at
 * all. Two tabs may not share an id - that would make "which page is shown" ambiguous - so a
 * duplicate is rejected where the list is made rather than resolved by order.</p>
 *
 * <p>Navigation is {@link Msg.Next}, {@link Msg.Previous}, {@link Msg.First} and {@link Msg.Last},
 * and every step skips disabled tabs: a disabled tab is a page the user cannot reach with the
 * keyboard, not a page that is briefly shown on the way past it. Wrap-around is {@link Config#wrap()}
 * and is off by default, so {@code next} on the last enabled tab is a no-op instead of an invisible
 * jump to the beginning. {@link Msg.Activate} chooses a tab by id and is refused for a disabled or
 * unknown one, which is what makes activation and navigation agree about what is reachable.</p>
 *
 * <p>{@link Msg.SetTabs}, {@link Msg.InsertTab} and {@link Msg.RemoveTab} publish a new tab list. The
 * active tab survives a list change while its id survives and stays enabled; when it does not, the
 * nearest enabled survivor takes its place, which is the tab that now holds the removed tab's
 * position, or the closest enabled one before it. A message that changes nothing returns the
 * incoming model instance, which is the unchanged-reference rule every bundled widget follows.</p>
 *
 * <h2>The bar, the frame and the join</h2>
 *
 * <p>{@link #frame} paints the bar and frames the content the parent passes in, and the active tab
 * opens into that frame: its bottom edge is the gap the content is visible through, so the selected
 * tab reads as one shape with the page below it. The bar windows rather than overflowing - a bar
 * narrower than its tabs shows the tabs around the active one and marks each side where tabs were
 * left out - and the horizontal window follows the active tab, so a narrow viewport always reveals
 * the label the user selected. A label too wide for the cells it can have is cut on a grapheme
 * boundary and ends with {@value #ELLIPSIS}, never through the middle of a wide glyph.</p>
 *
 * <p>{@link #render} answers the same composition as styled text for a program that paints its own
 * content, {@link #view(Model, View, Function)} is that composition as a view and routes the bar's own keys
 * while it has focus, and {@link #view(Model, ViewContext)} is the bar alone, for a page area the
 * application draws itself. {@link #frame} paints the same composition without declaring a focus
 * target, for an application that routes {@link #keys()} itself.</p>
 *
 * <h2>Composition and keep-alive pages</h2>
 *
 * <p>A bar owns no page, so the parent owns the composition: which page models exist, which of them
 * are mounted, and what moving to another tab does to them. Mounting every page and painting the
 * active one is the default this widget is built for - a page that is not painted keeps its model,
 * its scoped element ids and its subscriptions, so a draft, a list selection and a running timer are
 * all still there when the user comes back. A parent that would rather free a hidden page declares
 * no child node for it, which cancels that page's scoped work and drops its state; the unmounted
 * page starts again from its own {@code init} when it is mounted again. {@link io.github.dhoard.braxton.mvu.Mount}
 * states the rule, and the demo behind this phase - {@code examples.parity.TabsDemo} - is one
 * worked example of the keep-alive policy.</p>
 *
 * <h2>Focus, keys and help</h2>
 *
 * <p>Focus is model data the application owns and a reduction never changes, exactly as in {@link
 * Selector} and {@link Paginator}. The component declares one focus element around its bar and
 * consumes only the gestures its {@link KeyMap} names, so an unfocused bar receives no keys and a
 * focused one leaves text input and every other gesture to its parent. {@link #defaultKeys()} moves
 * between tabs and jumps to the first or the last one; {@link #keys()} is the map the component
 * routes, so {@link Help} describes the gestures that actually fire.</p>
 *
 * <p>{@code tab} and {@code shift+tab} are deliberately <em>not</em> bound here. They are the keys a
 * text field completes on and a form moves on, and only the application knows which of its fields
 * wants them, so it is the application that decides where they go - a bubble binding around the
 * composition is matched after a focused field has had its chance, which is what keeps completion
 * working inside a field.</p>
 *
 * <h2>Pointer input</h2>
 *
 * <p>A click activates the tab whose cell it landed in, and the wheel steps the bar. The cell is
 * resolved from the width the bar was painted in, so the click and the painted cells cannot
 * disagree; a click on an overflow marker, or past the last cell, activates nothing. A press never
 * moves focus by itself: {@link Msg.Activate} is what the bar reports, and the application decides
 * where the keyboard goes.</p>
 */
public final class Tabs implements Component<Tabs.Model, Tabs.Msg, Tabs.Output> {

    /** The blank cells painted each side of a label. */
    public static final int PADDING = 1;

    /** The cells a tab cell spends on its own chrome: two borders and the padding inside them. */
    public static final int CHROME = 2 + 2 * PADDING;

    /** The narrowest cell a tab is painted in; below it the bar is only the frame's edge. */
    public static final int MIN_CELL = CHROME;

    /** The narrowest bar that can hold a window marker beside a tab. */
    public static final int MIN_OVERFLOW = MIN_CELL + 1 + 2;

    /** Ends a label cut by the cells it was given. */
    public static final String ELLIPSIS = "\u2026";

    /** Marks the tabs left out on the left of a window. */
    public static final String OVERFLOW_LEFT = "\u2039";

    /** Marks the tabs left out on the right of a window. */
    public static final String OVERFLOW_RIGHT = "\u203A";

    /** One cell of the bar: a tab, or the marker that says tabs were left out. */
    private record Cell(int tab, boolean overflow, String label, int width) {}

    /**
     * What a bar paints: the frame characters, the label styles and the frame's own style.
     *
     * <p>The cell chrome carries the cell's style, so a tab is one colored shape - border and label
     * together - and the frame around the page carries {@code frame} instead. A bar draws cells, so
     * a border that draws nothing is rejected rather than painted as a bar of blank edges.</p>
     *
     * @param border the characters of the cells and of the frame they join
     * @param activeTab the style of the shown tab, chrome and label
     * @param inactiveTab the style of every other enabled tab
     * @param disabledTab the style of a tab the keyboard cannot reach
     * @param frame the style of the frame's own edges
     */
    public record Appearance(Border border, Style activeTab, Style inactiveTab, Style disabledTab, Style frame) {

        /** Substitutes defaults for missing values and rejects a frame that draws nothing. */
        public Appearance {
            border = border == null ? Border.ROUNDED : border;
            if (!border.isVisible()) {
                throw new IllegalArgumentException("a tab bar draws bordered cells, so its border must be visible");
            }
            activeTab = activeTab == null ? Style.NONE : activeTab;
            inactiveTab = inactiveTab == null ? Style.NONE : inactiveTab;
            disabledTab = disabledTab == null ? Style.NONE : disabledTab;
            frame = frame == null ? Style.NONE : frame;
        }

        /**
         * Rounded cells, a bold active label, plain inactive ones and an italic disabled one.
         *
         * @return the default appearance
         */
        public static Appearance rounded() {
            return new Appearance(
                    Border.ROUNDED, Style.NONE.withBold(), Style.NONE, Style.NONE.withItalic(), Style.NONE);
        }

        /**
         * The same appearance with a different frame.
         *
         * @param value the characters of the cells and the frame; must draw a frame
         * @return the new appearance
         */
        public Appearance withBorder(Border value) {
            return new Appearance(value, activeTab, inactiveTab, disabledTab, frame);
        }

        /**
         * The same appearance with different label styles.
         *
         * @param active the style of the shown tab; null is no styling
         * @param inactive the style of every other enabled tab; null is no styling
         * @param disabled the style of a disabled tab; null is no styling
         * @return the new appearance
         */
        public Appearance withTabStyles(Style active, Style inactive, Style disabled) {
            return new Appearance(border, active, inactive, disabled, frame);
        }

        /**
         * The same appearance with a different frame style.
         *
         * @param value the style of the frame's own edges; null is no styling
         * @return the new appearance
         */
        public Appearance withFrameStyle(Style value) {
            return new Appearance(border, activeTab, inactiveTab, disabledTab, value);
        }
    }

    /**
     * Immutable tab-bar definition.
     *
     * @param wrap whether the last enabled tab's next is the first enabled one, and the first one's
     *     previous the last
     * @param appearance what the bar and the frame it joins paint
     */
    public record Config(boolean wrap, Appearance appearance) {

        /** Substitutes the default appearance for a missing one. */
        public Config {
            appearance = appearance == null ? Appearance.rounded() : appearance;
        }

        /**
         * No wrap-around and the default rounded appearance.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(false, Appearance.rounded());
        }

        /**
         * The same definition with a different wrap-around policy.
         *
         * @param value whether navigation wraps at either end
         * @return the new definition
         */
        public Config withWrap(boolean value) {
            return new Config(value, appearance);
        }

        /**
         * The same definition with a different appearance.
         *
         * @param value what the bar and its frame paint; null restores the default
         * @return the new definition
         */
        public Config withAppearance(Appearance value) {
            return new Config(wrap, value);
        }
    }

    /**
     * One tab: its stable identity, its one-row label and whether the keyboard can reach it.
     *
     * @param id the stable identity the model and the messages use; never blank
     * @param label the text painted in the tab, flattened to one row; never blank
     * @param enabled whether navigation and activation may select this tab
     */
    public record Tab(String id, String label, boolean enabled) {

        /** Validates the identity and flattens the label to the one row it is painted on. */
        public Tab {
            id = ComponentId.of(id).value();
            // A label is one painted row: a break would split the cell and push every later tab
            // down, and a tab stop would move the row's own cells to whatever stop the terminal
            // uses. Both are flattened here, before the span boundary, so the measured width and the
            // painted cells agree.
            label = LineBreaks.singleRowAndTabs(label);
            if (label.isBlank()) {
                throw new IllegalArgumentException("a tab needs a non-blank label");
            }
        }

        /**
         * A reachable tab.
         *
         * @param id the stable identity; never blank
         * @param label the text painted in the tab; never blank
         * @return the tab
         */
        public static Tab of(String id, String label) {
            return new Tab(id, label, true);
        }

        /**
         * A tab that may be disabled.
         *
         * @param id the stable identity; never blank
         * @param label the text painted in the tab; never blank
         * @param enabled whether the keyboard can reach it
         * @return the tab
         */
        public static Tab of(String id, String label, boolean enabled) {
            return new Tab(id, label, enabled);
        }

        /**
         * The same tab with a different label.
         *
         * @param value the text painted in the tab; never blank
         * @return the new tab
         */
        public Tab withLabel(String value) {
            return new Tab(id, value, enabled);
        }

        /**
         * The same tab, reachable or not.
         *
         * @param value whether the keyboard can reach it
         * @return the new tab
         */
        public Tab withEnabled(boolean value) {
            return new Tab(id, label, value);
        }
    }

    /**
     * Immutable tab-bar state.
     *
     * <p>{@code activeId} is the id of the shown tab, or null when no tab is enabled - the one state
     * in which there is nothing to show. A list of tabs and the id that names one of them are
     * validated together, so the id is always either null or an enabled tab of this list.</p>
     *
     * @param tabs the tabs, in the order they are painted
     * @param activeId the id of the shown tab, or null when no tab is enabled
     * @param focused whether the bar currently owns focus
     */
    public record Model(List<Tab> tabs, String activeId, boolean focused) {

        /** Rejects duplicate ids and keeps the active id pointing at an enabled tab. */
        public Model {
            tabs = List.copyOf(tabs == null ? List.of() : tabs);
            Set<String> ids = new LinkedHashSet<>();
            for (Tab tab : tabs) {
                if (!ids.add(tab.id())) {
                    throw new IllegalArgumentException("duplicate tab id: " + tab.id());
                }
            }
            activeId = resolve(tabs, activeId);
        }

        /**
         * The same tabs, with the first enabled one shown.
         *
         * @param tabs the tabs, in the order they are painted
         * @return the starting state, focused
         */
        public static Model of(List<Tab> tabs) {
            return new Model(tabs, null, true);
        }

        /** The enabled tab named by {@code wanted}, else the first enabled tab, else null. */
        private static String resolve(List<Tab> tabs, String wanted) {
            if (wanted != null) {
                for (Tab tab : tabs) {
                    if (tab.id().equals(wanted) && tab.enabled()) {
                        return wanted;
                    }
                }
            }
            for (Tab tab : tabs) {
                if (tab.enabled()) {
                    return tab.id();
                }
            }
            return null;
        }

        private static boolean reachable(List<Tab> tabs, String id) {
            for (Tab tab : tabs) {
                if (tab.id().equals(id) && tab.enabled()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The enabled tab nearest {@code from}: that position first, then the tabs after it, then
         * the ones before it.
         *
         * @param tabs the tabs to search; must not be null
         * @param from the position the search starts at
         * @return the id of the nearest enabled tab, or null when none is enabled
         */
        private static String nearest(List<Tab> tabs, int from) {
            for (int index = Math.max(0, from); index < tabs.size(); index++) {
                if (tabs.get(index).enabled()) {
                    return tabs.get(index).id();
                }
            }
            for (int index = Math.min(from, tabs.size()) - 1; index >= 0; index--) {
                if (tabs.get(index).enabled()) {
                    return tabs.get(index).id();
                }
            }
            return null;
        }

        /**
         * The same state with a different focus.
         *
         * @param value whether this bar owns focus
         * @return this instance when the focus did not change, otherwise the new state
         */
        public Model withFocus(boolean value) {
            return value == focused ? this : new Model(tabs, activeId, value);
        }

        /**
         * The same state over a different tab list.
         *
         * <p>The shown tab is kept while its id survives and stays enabled; otherwise the nearest
         * enabled survivor of this model's own position takes its place, which is what makes a
         * removal and a wholesale list replacement behave the same way.</p>
         *
         * @param value the tabs, in the order they are painted
         * @return this instance when the list did not change, otherwise the new state
         */
        public Model withTabs(List<Tab> value) {
            List<Tab> next = List.copyOf(value == null ? List.of() : value);
            if (next.equals(tabs)) {
                return this;
            }
            if (activeId == null) {
                return new Model(next, null, focused);
            }
            if (reachable(next, activeId)) {
                return new Model(next, activeId, focused);
            }
            return new Model(next, nearest(next, activeIndex()), focused);
        }

        /**
         * The index of the shown tab.
         *
         * @return the zero-based index, or -1 when no tab is shown
         */
        public int activeIndex() {
            if (activeId == null) {
                return -1;
            }
            for (int index = 0; index < tabs.size(); index++) {
                if (tabs.get(index).id().equals(activeId)) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * The shown tab.
         *
         * @return the tab, or empty when no tab is shown
         */
        public Optional<Tab> active() {
            int index = activeIndex();
            return index < 0 ? Optional.empty() : Optional.of(tabs.get(index));
        }

        /**
         * Whether there is a tab to show.
         *
         * @return whether at least one tab is enabled
         */
        public boolean hasActive() {
            return activeId != null;
        }

        /**
         * The number of tabs that can be reached.
         *
         * @return the enabled tab count
         */
        public int enabledCount() {
            int count = 0;
            for (Tab tab : tabs) {
                if (tab.enabled()) {
                    count++;
                }
            }
            return count;
        }
    }

    /** One navigation, activation or tab-list change. */
    public sealed interface Msg {

        /** Moves to the next enabled tab, wrapping to the first when the configuration allows it. */
        record Next() implements Msg {}

        /** Moves to the previous enabled tab, wrapping to the last when the configuration allows it. */
        record Previous() implements Msg {}

        /** Moves to the first enabled tab. */
        record First() implements Msg {}

        /** Moves to the last enabled tab. */
        record Last() implements Msg {}

        /**
         * Shows a tab by id. An unknown or disabled id is refused.
         *
         * @param id the id of the tab to show; never null
         */
        record Activate(String id) implements Msg {
            public Activate {
                Objects.requireNonNull(id, "id");
            }
        }

        /**
         * Replaces the tab list, keeping the shown tab while its id survives.
         *
         * @param tabs the tabs, in the order they are painted; null is no tabs
         */
        record SetTabs(List<Tab> tabs) implements Msg {
            public SetTabs {
                tabs = List.copyOf(tabs == null ? List.of() : tabs);
            }
        }

        /**
         * Inserts one tab.
         *
         * @param index the position to insert at, clamped to the list; negative is the front
         * @param tab the tab to insert; never null, and its id must not already be present
         */
        record InsertTab(int index, Tab tab) implements Msg {
            public InsertTab {
                Objects.requireNonNull(tab, "tab");
            }
        }

        /**
         * Removes the tab with this id; an id that is not present changes nothing.
         *
         * @param id the id of the tab to remove; never null
         */
        record RemoveTab(String id) implements Msg {
            public RemoveTab {
                Objects.requireNonNull(id, "id");
            }
        }
    }

    /** What the owner should do after an action. */
    public sealed interface Output {

        /**
         * The bar now shows this tab, after a message that changed it.
         *
         * <p>A message that leaves the selection where it was emits nothing, so an output is
         * evidence of a transition rather than of a keystroke. Removing the last enabled tab has no
         * id to name and emits nothing either: the model's {@code activeId()} becomes null.</p>
         *
         * @param id the id of the tab now shown; never null
         */
        record Activated(String id) implements Output {
            public Activated {
                Objects.requireNonNull(id, "id");
            }
        }
    }

    /** A reduction result; the intent is present only when the shown tab changed. */
    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /** The identity of the bar's focus element; a composition keeps it at the root scope. */
    private static final ElementId ELEMENT = ElementId.of("tabs");

    private final Config config;
    private final KeyMap<Msg> keys;

    /**
     * A bar with the default keys.
     *
     * @param config the immutable definition; null is {@link Config#defaults()}
     */
    public Tabs(Config config) {
        this(config, defaultKeys());
    }

    /**
     * A bar with its own keys.
     *
     * @param config the immutable definition; null is {@link Config#defaults()}
     * @param keys the actions this bar handles, matched while it is focused; required
     */
    public Tabs(Config config, KeyMap<Msg> keys) {
        this.config = config == null ? Config.defaults() : config;
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * The default actions: previous, next, first and last enabled tab.
     *
     * <p>{@code tab} and {@code shift+tab} are not among them. They are the keys a text field
     * completes on, and routing them is the application's decision rather than the bar's.</p>
     *
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(KeyMap.group(
                "Tabs",
                KeyAction.<Msg>of("previous", new Msg.Previous(), "\u2190", "previous tab", "left", "h"),
                KeyAction.<Msg>of("next", new Msg.Next(), "\u2192", "next tab", "right", "l"),
                KeyAction.<Msg>of("first", new Msg.First(), "home", "first tab", "home"),
                KeyAction.<Msg>of("last", new Msg.Last(), "end", "last tab", "end")));
    }

    /**
     * The actions this bar handles.
     *
     * @return the key map
     */
    public KeyMap<Msg> keys() {
        return keys;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        // The tabs are model data the application publishes, so a bar starts with none and the
        // first SetTabs (or the initial model) is what fills it.
        return Update.next(new Model(List.of(), null, true));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        return Views.focus(
                ELEMENT,
                model.focused(),
                List.of(),
                this::actionFor,
                pointer(model),
                Views.sized(size -> Views.text(standalone(model, config, size.cols()))));
    }

    /**
     * Paints the bar over the page it selects and routes the bar's own keys while it has focus.
     *
     * <p>This is {@link #frame} plus the focus target and handler {@link #view(Model, ViewContext)}
     * declares: the bar's rows are the focused element, so the page below them keeps its own focus
     * element and the two never compete for the same gesture. The bar's own messages are mapped
     * through {@code mapper}, which is what lets the bar and a child page share one message type.
     * Use this when the page is an interactive child of the composition; use the static
     * {@link #frame} when the application routes {@link #keys()} itself, and the static
     * {@link #render} when it paints text.</p>
     *
     * <p>The composed view belongs to the parent's tree rather than to the bar's own mount, so the
     * bar's focus element keeps the root scope: one composed bar per application. The pages inside
     * it are the stateful children and are scoped by their own mounts as usual.</p>
     *
     * @param state the bar's state; required
     * @param content the page to show inside the frame
     * @param mapper maps the bar's messages into the parent's message type; required
     * @param <Out> the message type the composition produces
     * @return the composed view
     */
    public <Out> View<Out> view(Model state, View<Out> content, Function<? super Msg, ? extends Out> mapper) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(mapper, "mapper");
        return compose(state, config, bar(state).map(mapper), content);
    }

    /** The bar's own rows as the focus target that routes this component's keys and clicks. */
    private View<Msg> bar(Model state) {
        return Views.focus(
                ELEMENT,
                state.focused(),
                List.of(),
                this::actionFor,
                pointer(state),
                Views.column(
                        Views.sized(size -> Views.text(tops(state, config, size.cols()))),
                        Views.sized(size -> Views.text(labels(state, config, size.cols())))));
    }

    /**
     * Paints the bar, frames {@code content} with it, and joins the shown tab to that frame.
     *
     * <p>The bar's rows are built from the columns the composition is given, so the cells, the frame
     * and the tab that opens into it stay aligned at any width. A model with no tabs frames the
     * content without a bar: an empty bar is a blank two rows and a rule, which is not what "this
     * page has no tabs" should look like.</p>
     *
     * @param state the bar's state; required
     * @param config the definition; null is {@link Config#defaults()}
     * @param content the page to show inside the frame
     * @param <Msg> the message type the content produces
     * @return the composed view
     */
    public static <Msg> View<Msg> frame(Model state, Config config, View<Msg> content) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(content, "content");
        Config opts = config == null ? Config.defaults() : config;
        return compose(
                state,
                opts,
                Views.column(
                        Views.sized(size -> Views.text(tops(state, opts, size.cols()))),
                        Views.sized(size -> Views.text(labels(state, opts, size.cols())))),
                content);
    }

    /**
     * The one composition: the bar's rows, the frame, and the shown tab's opening between them.
     *
     * <p>Both the painted and the routed form build their bar rows their own way and hand them here,
     * so the join, the frame and the empty-bar case cannot differ between them.</p>
     */
    private static <Msg> View<Msg> compose(Model state, Config config, View<Msg> bar, View<Msg> content) {
        Appearance look = config.appearance();
        if (state.tabs().isEmpty()) {
            return Views.border(look.border(), StyledText.empty(), look.frame(), content);
        }
        return Views.column(
                bar,
                Views.grow(Views.topEdge(
                        look.border(), look.frame(), cols -> bottom(state, config, cols, true), content)));
    }

    /**
     * Paints the bar and the framed content as one block of text.
     *
     * <p>It is the same composition {@link #frame} builds, for content that is already text: the
     * bar, the top edge the shown tab opens into, the content cut to the frame's inner width and
     * padded to it, and the bottom edge.</p>
     *
     * @param state the bar's state; required
     * @param config the definition; null is {@link Config#defaults()}
     * @param width the columns to paint in; zero or less paints nothing
     * @param content the page to show, or null for an empty one
     * @return the composed block
     */
    public static StyledText render(Model state, Config config, int width, StyledText content) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        Border border = opts.appearance().border();
        Style frameStyle = opts.appearance().frame();
        StyledText page = content == null ? StyledText.empty() : content;
        if (width <= 0) {
            return StyledText.empty();
        }
        if (width == 1) {
            // One column holds no edge, no cell and no frame, so the only thing that can be painted
            // is the content's own first column.
            StyledText column = StyledText.empty();
            for (StyledText line : page.lines()) {
                column = column.isEmpty() ? cut(line, 1) : column.append("\n").append(cut(line, 1));
            }
            return column;
        }
        int inner = width - 2;
        StyledText block = StyledText.empty();
        if (state.tabs().isEmpty()) {
            // No bar: the content is framed like any other, so the top edge is painted here too.
            block = block.append(StyledText.of(edge(border, width, false), frameStyle))
                    .append("\n");
        } else {
            for (StyledText row : List.of(tops(state, opts, width), labels(state, opts, width))) {
                if (!row.isEmpty()) {
                    block = block.append(row).append("\n");
                }
            }
            block = block.append(bottom(state, opts, width, true)).append("\n");
        }
        String vertical = Border.glyph(border.vertical());
        for (StyledText line : page.lines()) {
            StyledText clipped = cut(line, inner);
            int pad = inner - clipped.columns();
            block = block.append(StyledText.of(vertical, frameStyle))
                    .append(clipped)
                    .append(StyledText.of(" ".repeat(Math.max(0, pad)), Style.NONE))
                    .append(StyledText.of(vertical, frameStyle))
                    .append("\n");
        }
        return block.append(StyledText.of(edge(border, width, true), frameStyle));
    }

    /**
     * The bar's pointer handler: a click activates the tab whose cell it landed in, and the wheel
     * steps through the bar.
     *
     * <p>The cells are the ones the bar paints at this width, worked out again from the region the
     * report arrived in rather than remembered from a build: the region is what the user clicked on,
     * so the two can never disagree about which cell a column belongs to. A click on an overflow
     * marker, or past the last cell, activates nothing - a marker is not a tab.</p>
     */
    private View.PointerHandler<Msg> pointer(Model state) {
        return (event, region) -> {
            if (event.isWheel()) {
                // The wheel steps the bar the way the horizontal arrows step it: up and left go back,
                // down and right go on.
                return Optional.of(
                        switch (event.button()) {
                            case WHEEL_UP, WHEEL_LEFT -> new Msg.Previous();
                            default -> new Msg.Next();
                        });
            }
            if (!event.isLeftPress() || event.column() < 0) {
                return Optional.empty();
            }
            int used = 0;
            for (Cell cell : cells(state, config, region.cols())) {
                if (event.column() < used + cell.width()) {
                    return cell.overflow() || cell.tab() < 0
                            ? Optional.empty()
                            : Optional.of(new Msg.Activate(
                                    state.tabs().get(cell.tab()).id()));
                }
                used += cell.width();
            }
            return Optional.empty();
        };
    }

    /** Maps one input event to a tab message, or empty when the gesture is not a tab gesture. */
    private Optional<Msg> actionFor(InputEvent event) {
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        return keys.actionFor(keyInput.key()).map(KeyAction::message);
    }

    /** Applies one gesture or list change. Never performs I/O and never mutates the input state. */
    private Model applied(Model state, Msg action) {
        return switch (action) {
            case Msg.Next next -> moved(state, 1);
            case Msg.Previous previous -> moved(state, -1);
            case Msg.First first -> at(state, firstEnabled(state.tabs()));
            case Msg.Last last -> at(state, lastEnabled(state.tabs()));
            case Msg.Activate activate -> at(state, indexOf(state.tabs(), activate.id()));
            case Msg.SetTabs set -> state.withTabs(set.tabs());
            case Msg.InsertTab insert -> inserted(state, insert.index(), insert.tab());
            case Msg.RemoveTab remove -> removed(state, remove.id());
        };
    }

    /** The reduction plus the output a changed selection produces. */
    private Result reduce(Model model, Msg action) {
        Model reduced = applied(model, action);
        String shown = Objects.equals(reduced.activeId(), model.activeId()) ? null : reduced.activeId();
        return new Result(reduced, shown == null ? null : new Output.Activated(shown));
    }

    private static int firstEnabled(List<Tab> tabs) {
        for (int index = 0; index < tabs.size(); index++) {
            if (tabs.get(index).enabled()) {
                return index;
            }
        }
        return -1;
    }

    private static int lastEnabled(List<Tab> tabs) {
        for (int index = tabs.size() - 1; index >= 0; index--) {
            if (tabs.get(index).enabled()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * One step to the next enabled tab.
     *
     * <p>A disabled tab is never shown on the way past it: the step walks to the nearest enabled tab
     * in that direction, and stops at the end of the list unless the configuration wraps. A bar with
     * no active tab steps to the first or the last enabled one, so a key still does something
     * sensible before anything is selected.</p>
     */
    private Model moved(Model state, int direction) {
        List<Tab> tabs = state.tabs();
        int current = state.activeIndex();
        if (current < 0) {
            return at(state, direction > 0 ? firstEnabled(tabs) : lastEnabled(tabs));
        }
        for (int step = 1; step <= tabs.size(); step++) {
            int index = current + direction * step;
            if (index < 0 || index >= tabs.size()) {
                if (!config.wrap()) {
                    return state;
                }
                index = Math.floorMod(index, tabs.size());
            }
            if (tabs.get(index).enabled()) {
                return at(state, index);
            }
        }
        // Every other tab is disabled, so there is nowhere to go.
        return state;
    }

    /** A tab by index, guarded; the incoming instance when it is not a different reachable tab. */
    private static Model at(Model state, int target) {
        List<Tab> tabs = state.tabs();
        if (target < 0 || target >= tabs.size() || !tabs.get(target).enabled()) {
            return state;
        }
        String id = tabs.get(target).id();
        return id.equals(state.activeId()) ? state : new Model(tabs, id, state.focused());
    }

    private static int indexOf(List<Tab> tabs, String id) {
        for (int index = 0; index < tabs.size(); index++) {
            if (tabs.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /** Inserts one tab at a position, clamped to the list. */
    private static Model inserted(Model state, int index, Tab tab) {
        List<Tab> tabs = state.tabs();
        if (indexOf(tabs, tab.id()) >= 0) {
            throw new IllegalArgumentException("duplicate tab id: " + tab.id());
        }
        List<Tab> next = new ArrayList<>(tabs);
        next.add(Math.max(0, Math.min(index, next.size())), tab);
        return state.withTabs(next);
    }

    /** Removes one tab; the nearest enabled survivor takes over when the shown tab was removed. */
    private static Model removed(Model state, String id) {
        int index = indexOf(state.tabs(), id);
        if (index < 0) {
            return state;
        }
        List<Tab> next = new ArrayList<>(state.tabs());
        next.remove(index);
        return state.withTabs(next);
    }

    // ── rendering ────────────────────────────────────────────────────────────

    /**
     * The cells the bar paints at this width.
     *
     * <p>Every tab at its own width when they fit. Otherwise the widest window of consecutive tabs
     * that fits around the shown one, with a marker on each side where tabs were left out - so the
     * label the user selected is always on screen, and the cost of a frame follows the width
     * rather than the number of tabs. When not even the shown tab fits with its own label, it is
     * the only cell and its label is cut.</p>
     */
    private static List<Cell> cells(Model state, Config config, int width) {
        List<Tab> tabs = state.tabs();
        int count = tabs.size();
        if (count == 0 || width < MIN_CELL) {
            return List.of();
        }
        int anchor = Math.max(0, state.activeIndex());
        // Prefix sums keep the widest fitting window a linear scan rather than a quadratic one: a
        // list of thousands of tabs costs the same as a list of three.
        long[] prefix = new long[count + 1];
        for (int index = 0; index < count; index++) {
            prefix[index + 1] = prefix[index] + cellWidth(tabs.get(index).label());
        }
        for (int size = count; size >= 1; size--) {
            int start = windowStart(anchor, count, size);
            int end = start + size;
            long total = prefix[end] - prefix[start];
            if (start > 0) {
                total += overflowWidth();
            }
            if (end < count) {
                total += overflowWidth();
            }
            if (total <= width) {
                return window(tabs, start, end);
            }
        }
        return cut(tabs, anchor, width);
    }

    /** The window's cells, with a marker on each side that left tabs out. */
    private static List<Cell> window(List<Tab> tabs, int start, int end) {
        List<Cell> cells = new ArrayList<>(end - start + 2);
        if (start > 0) {
            cells.add(new Cell(-1, true, OVERFLOW_LEFT, overflowWidth()));
        }
        for (int index = start; index < end; index++) {
            cells.add(cell(tabs, index, tabs.get(index).label()));
        }
        if (end < tabs.size()) {
            cells.add(new Cell(-1, true, OVERFLOW_RIGHT, overflowWidth()));
        }
        return cells;
    }

    /** One tab's cell, with its label cut to whatever the markers beside it leave. */
    private static List<Cell> cut(List<Tab> tabs, int anchor, int width) {
        int markers = (anchor > 0 ? overflowWidth() : 0) + (anchor < tabs.size() - 1 ? overflowWidth() : 0);
        // A marker is worth a cell only when the shown label keeps a column to paint in: two markers
        // that leave an empty cell reveal nothing, which is the one thing a window must not do.
        if (width - markers < MIN_CELL + 1) {
            markers = 0;
        }
        Tab tab = tabs.get(anchor);
        int budget = Math.min(width - markers, cellWidth(tab.label()));
        List<Cell> cells = new ArrayList<>(3);
        if (anchor > 0 && markers > 0) {
            cells.add(new Cell(-1, true, OVERFLOW_LEFT, overflowWidth()));
        }
        cells.add(cell(tabs, anchor, fit(tab.label(), budget - CHROME)));
        if (anchor < tabs.size() - 1 && markers > 0) {
            cells.add(new Cell(-1, true, OVERFLOW_RIGHT, overflowWidth()));
        }
        return cells;
    }

    private static Cell cell(List<Tab> tabs, int index, String label) {
        return new Cell(index, false, label, cellWidth(label));
    }

    /** The first tab of a window of {@code size} tabs, centred on {@code anchor} where it can be. */
    private static int windowStart(int anchor, int count, int size) {
        int centred = anchor - (size - 1) / 2;
        return Math.max(0, Math.min(centred, count - size));
    }

    private static int cellWidth(String label) {
        return TextMeasure.columns(label) + CHROME;
    }

    private static int overflowWidth() {
        return 1 + 2;
    }

    /** A label cut to the columns it has, ending in an ellipsis when anything was dropped. */
    private static String fit(String label, int columns) {
        if (columns <= 0) {
            return "";
        }
        if (TextMeasure.columns(label) <= columns) {
            return label;
        }
        int ellipsis = TextMeasure.columns(ELLIPSIS);
        return columns <= ellipsis
                ? TextMeasure.truncate(label, columns)
                : TextMeasure.truncate(label, columns - ellipsis) + ELLIPSIS;
    }

    /** The bar alone: the cell tops, the labels, and the bar's own closed bottom edge. */
    private static StyledText standalone(Model state, Config config, int width) {
        if (width <= 0 || state.tabs().isEmpty()) {
            return StyledText.empty();
        }
        StyledText row = tops(state, config, width);
        StyledText labelRow = labels(state, config, width);
        StyledText bar = row;
        if (!labelRow.isEmpty()) {
            bar = bar.isEmpty() ? labelRow : bar.append("\n").append(labelRow);
        }
        return bar.isEmpty()
                ? bottom(state, config, width, false)
                : bar.append("\n").append(bottom(state, config, width, false));
    }

    /** The top row of every cell: its corners and the horizontal run between them. */
    private static StyledText tops(Model state, Config config, int width) {
        if (width <= 0) {
            return StyledText.empty();
        }
        List<Cell> cells = cells(state, config, width);
        if (cells.isEmpty()) {
            // No cell fits: the bar is only the frame's edge below the content, not two blank rows
            // where cells would have been.
            return StyledText.empty();
        }
        Border border = config.appearance().border();
        String horizontal = Border.glyph(border.horizontal());
        StyledText row = StyledText.empty();
        int used = 0;
        for (Cell cell : cells) {
            row = row.append(StyledText.of(
                    Border.glyph(border.topLeft())
                            + horizontal.repeat(Math.max(0, cell.width() - 2))
                            + Border.glyph(border.topRight()),
                    style(config, state, cell)));
            used += cell.width();
        }
        if (used < width) {
            row = row.append(StyledText.of(" ".repeat(width - used), Style.NONE));
        }
        return row;
    }

    /** The label row: one padded label per cell. */
    private static StyledText labels(Model state, Config config, int width) {
        if (width <= 0) {
            return StyledText.empty();
        }
        List<Cell> cells = cells(state, config, width);
        if (cells.isEmpty()) {
            return StyledText.empty();
        }
        Border border = config.appearance().border();
        String vertical = Border.glyph(border.vertical());
        StyledText row = StyledText.empty();
        int used = 0;
        for (Cell cell : cells) {
            int inner = Math.max(0, cell.width() - 2);
            // A marker has no padding to spend: its three columns are a border, the glyph and a
            // border. A tab's content is its label inside the padding, and `fit` is what keeps the
            // row exactly as wide as the cell it belongs to.
            String content = cell.overflow()
                    ? TextMeasure.fit(cell.label(), inner)
                    : TextMeasure.fit(" ".repeat(PADDING) + cell.label() + " ".repeat(PADDING), inner);
            row = row.append(StyledText.of(vertical + content + vertical, style(config, state, cell)));
            used += cell.width();
        }
        if (used < width) {
            row = row.append(StyledText.of(" ".repeat(width - used), Style.NONE));
        }
        return row;
    }

    /**
     * The bar's last row: the edge the shown tab opens into, or the bar's closed bottom.
     *
     * <p>Joined, the cells are the top edge of the frame below them and the shown tab's span is the
     * opening the content shows through, so the two read as one shape. Standalone, every cell is
     * closed and the rest of the row is the bar's own rule.</p>
     */
    private static StyledText bottom(Model state, Config config, int width, boolean joined) {
        if (width <= 0) {
            return StyledText.empty();
        }
        Border border = config.appearance().border();
        Style frameStyle = config.appearance().frame();
        List<Cell> cells = cells(state, config, width);
        if (cells.isEmpty()) {
            // No cell to join: a joined bar is the frame's top edge, and a standalone one is the
            // rule its cells would have drawn, open at both ends because there is no cell there.
            return StyledText.of(
                    joined
                            ? edge(border, width, false)
                            : Border.glyph(border.horizontal()).repeat(width),
                    frameStyle);
        }
        Joins joins = Joins.of(border);
        String horizontal = Border.glyph(border.horizontal());
        StyledText row = StyledText.empty();
        int used = 0;
        for (Cell cell : cells) {
            boolean shown = !cell.overflow() && isActive(state, cell);
            boolean opening = joined && shown;
            boolean first = used == 0;
            boolean last = used + cell.width() == width;
            String left = junction(border, joins, first, true, shown, joined);
            String right = junction(border, joins, last, false, shown, joined);
            String middle = opening
                    ? " ".repeat(Math.max(0, cell.width() - 2))
                    : horizontal.repeat(Math.max(0, cell.width() - 2));
            row = row.append(StyledText.of(left + middle + right, style(config, state, cell)));
            used += cell.width();
        }
        if (used < width) {
            int run = width - used;
            String rest = joined
                    ? horizontal.repeat(Math.max(0, run - 1)) + Border.glyph(border.topRight())
                    : horizontal.repeat(run);
            row = row.append(StyledText.of(rest, frameStyle));
        }
        return row;
    }

    /**
     * One end of a cell's bottom edge.
     *
     * <p>Four things meet there: the cell's own side, the cell beside it, the frame below, and
     * whether the shown tab is open. The character says which of them continue - the frame's own
     * vertical through a closed end at the edge, a tee where a closed cell meets its neighbour, and
     * an open corner where the shown tab's opening meets one.</p>
     */
    private static String junction(
            Border border, Joins joins, boolean atEdge, boolean left, boolean shown, boolean joined) {
        if (atEdge) {
            if (joined) {
                // The frame's own edge runs through this cell: down into the frame, and up into the
                // cell that owns the column.
                return shown ? Border.glyph(border.vertical()) : String.valueOf(left ? joins.left() : joins.right());
            }
            return Border.glyph(left ? border.bottomLeft() : border.bottomRight());
        }
        if (shown && joined) {
            return String.valueOf(left ? joins.openLeft() : joins.openRight());
        }
        return String.valueOf(joins.tee());
    }

    /** The frame's top or bottom edge: its corners and a full run of its horizontal character. */
    private static String edge(Border border, int width, boolean bottom) {
        if (width <= 0) {
            return "";
        }
        String horizontal = Border.glyph(border.horizontal());
        char first = bottom ? border.bottomLeft() : border.topLeft();
        char last = bottom ? border.bottomRight() : border.topRight();
        if (width == 1) {
            return Border.glyph(first);
        }
        return Border.glyph(first) + horizontal.repeat(width - 2) + Border.glyph(last);
    }

    private static boolean isActive(Model state, Cell cell) {
        if (cell.overflow() || cell.tab() < 0) {
            return false;
        }
        return state.tabs().get(cell.tab()).id().equals(state.activeId());
    }

    private static Style style(Config config, Model state, Cell cell) {
        Appearance look = config.appearance();
        if (cell.overflow() || cell.tab() < 0) {
            return look.inactiveTab();
        }
        Tab tab = state.tabs().get(cell.tab());
        if (!tab.enabled()) {
            return look.disabledTab();
        }
        return isActive(state, cell) ? look.activeTab() : look.inactiveTab();
    }

    /** Styled text cut to the columns it has, on a grapheme boundary. */
    private static StyledText cut(StyledText text, int columns) {
        if (columns <= 0) {
            return StyledText.empty();
        }
        String plain = text.plainText();
        if (TextMeasure.columns(plain) <= columns) {
            return text;
        }
        String truncated = TextMeasure.truncate(plain, columns);
        return text.slice(0, truncated.length());
    }

    /**
     * The junction glyphs a bar needs, chosen for the frame it joins.
     *
     * <p>A double frame joins with double junctions and every other frame with single ones, so the
     * cells and the frame below them read as one line rather than as a frame with mismatched
     * corners. A custom border keeps the single-line junctions: its own characters are still what a
     * cell and a frame draw, and the junction is the only part this widget chooses.</p>
     */
    private record Joins(char left, char right, char tee, char openLeft, char openRight) {

        private static Joins of(Border border) {
            if (Border.DOUBLE.equals(border)) {
                return new Joins('\u2560', '\u2563', '\u2569', '\u255D', '\u255A');
            }
            return new Joins('\u251C', '\u2524', '\u2534', '\u2518', '\u2514');
        }
    }
}
