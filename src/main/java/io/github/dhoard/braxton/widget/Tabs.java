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
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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
 * <p>{@link #render} paints the bar and frames the content the parent passes in, and the active tab
 * opens into that frame: its bottom edge is the gap the content is visible through, so the selected
 * tab reads as one shape with the page below it. The bar windows rather than overflowing - a bar
 * narrower than its tabs shows the tabs around the active one and marks each side where tabs were
 * left out - and the horizontal window follows the active tab, so a narrow viewport always reveals
 * the label the user selected. A label too wide for the cells it can have is cut on a grapheme
 * boundary and ends with {@value #ELLIPSIS}, never through the middle of a wide glyph.</p>
 *
 * <p>{@link #render} answers the same composition as styled text for a program that paints its own
 * content, {@link #element(Model, Element, Function)} is that composition as a view and routes the bar's own keys
 * while it has focus, and {@link #view(Model, ViewContext)} is the bar alone, for a page area the
 * application draws itself. {@link #render} paints the same composition without declaring a focus
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
 * disagree. The marker a window paints when it hides tabs is the bar's own control: pressing the
 * leading one steps back and the trailing one steps on, which is the move the horizontal arrows
 * make, and a click past the last cell does nothing. A press never moves focus by itself: {@link
 * Msg.Activate} is what the bar reports, and the application decides where the keyboard goes.</p>
 */
public final class Tabs implements Component<Tabs.Model, Tabs.Msg, Tabs.Output>, Interactive<Tabs.Model, Tabs.Msg> {

    /**
     * The blank cells painted each side of a label.
     */
    public static final int PADDING = 1;

    /**
     * The cells a tab cell spends on its own chrome: two borders and the padding inside them.
     */
    public static final int CHROME = 2 + 2 * PADDING;

    /**
     * The narrowest cell a tab is painted in; below it the bar is only the frame's edge.
     */
    public static final int MIN_CELL = CHROME;

    /**
     * The narrowest bar that can hold a window marker beside a tab.
     */
    public static final int MIN_OVERFLOW = MIN_CELL + 1 + 2;

    /**
     * Ends a label cut by the cells it was given.
     */
    public static final String ELLIPSIS = "\u2026";

    /**
     * Marks the tabs left out on the left of a window.
     */
    public static final String OVERFLOW_LEFT = "\u2039";

    /**
     * Marks the tabs left out on the right of a window.
     */
    public static final String OVERFLOW_RIGHT = "\u203A";

    /**
     * One cell of the bar: a tab, or the marker that says tabs were left out.
     */
    record Cell(int tab, boolean overflow, String label, int width) {}

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

        /**
         * Substitutes defaults for missing values and rejects a frame that draws nothing.
         */
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

        /**
         * Substitutes the default appearance for a missing one.
         */
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

        /**
         * Validates the identity and flattens the label to the one row it is painted on.
         */
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

        /**
         * Rejects duplicate ids and keeps the active id pointing at an enabled tab.
         */
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

        /**
         * The enabled tab named by {@code wanted}, else the first enabled tab, else null.
         */
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

    /**
     * One navigation, activation or tab-list change.
     */
    public sealed interface Msg {

        /**
         * Moves to the next enabled tab, wrapping to the first when the configuration allows it.
         */
        record Next() implements Msg {}

        /**
         * Moves to the previous enabled tab, wrapping to the last when the configuration allows it.
         */
        record Previous() implements Msg {}

        /**
         * Moves to the first enabled tab.
         */
        record First() implements Msg {}

        /**
         * Moves to the last enabled tab.
         */
        record Last() implements Msg {}

        /**
         * Shows a tab by id. An unknown or disabled id is refused.
         *
         * @param id the id of the tab to show; never null
         */
        record Activate(String id) implements Msg {
            /**
             * Creates a {@code Activate} value.
             *
             * @param id the id of the tab to show; never null
             */
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
            /**
             * Creates a {@code SetTabs} value.
             *
             * @param tabs the tabs, in the order they are painted; null is no tabs
             */
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
            /**
             * Creates a {@code InsertTab} value.
             *
             * @param index the position to insert at, clamped to the list; negative is the front
             * @param tab the tab to insert; never null, and its id must not already be present
             */
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
            /**
             * Creates a {@code RemoveTab} value.
             *
             * @param id the id of the tab to remove; never null
             */
            public RemoveTab {
                Objects.requireNonNull(id, "id");
            }
        }
    }

    /**
     * What the owner should do after an action.
     */
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
            /**
             * Creates a {@code Activated} value.
             *
             * @param id the id of the tab now shown; never null
             */
            public Activated {
                Objects.requireNonNull(id, "id");
            }
        }
    }

    /**
     * A reduction result; the intent is present only when the shown tab changed.
     */
    record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * The identity of the bar's focus element; a composition keeps it at the root scope.
     */
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
    public Element<Msg> view(Model model, ViewContext context) {
        return Elements.stack(
                RenderedText.<Msg>of(cols -> TabsRendering.standalone(model, config, cols)),
                new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The bar's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        keys.actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The bar's pointer handler: a click activates the tab whose cell it landed in, and the wheel
     * steps through the bar. The cells are resolved from the columns the bar was painted in, so the
     * click and the painted cells cannot disagree.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        pointer(pointer.localTo(bounds.row(), bounds.col()), model, bounds.cols())
                .ifPresent(emit);
    }

    /**
     * The bar and its framed page as one element tree.
     *
     * <p>The bar is the interaction leaf, mapped into the parent's message type, so the bar's keys
     * and clicks belong to the parent's tree; the page is the caller's, composed and never rebuilt.
     * The bar's own element keeps the root scope, so there is one composed bar per application.</p>
     *
     * @param <Out> the message type the composition produces
     * @param state the bar's state; required
     * @param content the page to show inside the frame; required
     * @param mapper maps the bar's messages into the parent's message type; required
     * @return the composed element
     */
    public <Out> Element<Out> element(Model state, Element<Out> content, Function<? super Msg, ? extends Out> mapper) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(mapper, "mapper");
        Appearance look = config.appearance();
        Element<Out> bar = Elements.stack(
                // The bar's open rows: the joined top edge below them closes the bar and opens into
                // the page in one row, which is what keeps the tabs and the frame aligned.
                RenderedText.<Out>of(cols -> TabsRendering.barRows(state, config, cols)),
                new Element.Leaf<Tabs.Model, Msg>(this, state, Sizing.measured()).map(mapper));
        if (state.tabs().isEmpty()) {
            return Elements.frame(look.border(), StyledText.empty(), look.frame(), content);
        }
        return Elements.column(
                bar,
                Elements.grow(Elements.topEdge(
                        look.border(),
                        look.frame(),
                        cols -> TabsRendering.bottom(state, config, cols, true),
                        content)));
    }

    /**
     * Paints the bar and the framed content as one block of text.
     *
     * <p>It is the same composition {@link #element} builds, for content that is already text: the
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
                column = column.isEmpty()
                        ? TabsRendering.cut(line, 1)
                        : column.append("\n").append(TabsRendering.cut(line, 1));
            }
            return column;
        }
        int inner = width - 2;
        StyledText block = StyledText.empty();
        if (state.tabs().isEmpty()) {
            // No bar: the content is framed like any other, so the top edge is painted here too.
            block = block.append(StyledText.of(TabsRendering.edge(border, width, false), frameStyle))
                    .append("\n");
        } else {
            for (StyledText row :
                    List.of(TabsRendering.tops(state, opts, width), TabsRendering.labels(state, opts, width))) {
                if (!row.isEmpty()) {
                    block = block.append(row).append("\n");
                }
            }
            block = block.append(TabsRendering.bottom(state, opts, width, true)).append("\n");
        }
        String vertical = Border.glyph(border.vertical());
        for (StyledText line : page.lines()) {
            StyledText clipped = TabsRendering.cut(line, inner);
            int pad = inner - clipped.columns();
            block = block.append(StyledText.of(vertical, frameStyle))
                    .append(clipped)
                    .append(StyledText.of(" ".repeat(Math.max(0, pad)), Style.NONE))
                    .append(StyledText.of(vertical, frameStyle))
                    .append("\n");
        }
        return block.append(StyledText.of(TabsRendering.edge(border, width, true), frameStyle));
    }

    /**
     * The bar's pointer handler: a click activates the tab whose cell it landed in, and the wheel
     * steps through the bar.
     *
     * <p>The cells are the ones the bar paints at this width, worked out again from the region the
     * report arrived in rather than remembered from a build: the region is what the user clicked on,
     * so the two can never disagree about which cell a column belongs to. A marker is not a tab, so
     * pressing one scrolls the window instead of activating what it stands for, and a click past the
     * last cell reports nothing.</p>
     */
    private Optional<Msg> pointer(PointerEvent event, Model state, int cols) {
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
        for (Cell cell : TabsRendering.cells(state, config, cols)) {
            if (event.column() < used + cell.width()) {
                if (cell.tab() >= 0) {
                    return Optional.of(
                            new Msg.Activate(state.tabs().get(cell.tab()).id()));
                }
                // The marker a window paints when it hides tabs is the bar's own control: the
                // leading one steps back and the trailing one steps on, which is the same move
                // the horizontal arrows make.
                if (!cell.overflow()) {
                    return Optional.empty();
                }
                return Optional.of(OVERFLOW_LEFT.equals(cell.label()) ? new Msg.Previous() : new Msg.Next());
            }
            used += cell.width();
        }
        return Optional.empty();
    }

    /**
     * Maps one input event to a tab message, or empty when the gesture is not a tab gesture.
     */
    private Optional<Msg> actionFor(InputEvent event) {
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        return keys.actionFor(keyInput.key()).map(KeyAction::message);
    }

    /**
     * Applies one gesture or list change. Never performs I/O and never mutates the input state.
     */
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

    /**
     * The reduction plus the output a changed selection produces.
     */
    private Result reduce(Model model, Msg action) {
        Model reduced = applied(model, action);
        String shown = Objects.equals(reduced.activeId(), model.activeId()) ? null : reduced.activeId();
        return new Result(reduced, shown == null ? null : new Output.Activated(shown));
    }

    static int firstEnabled(List<Tab> tabs) {
        for (int index = 0; index < tabs.size(); index++) {
            if (tabs.get(index).enabled()) {
                return index;
            }
        }
        return -1;
    }

    static int lastEnabled(List<Tab> tabs) {
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

    /**
     * A tab by index, guarded; the incoming instance when it is not a different reachable tab.
     */
    static Model at(Model state, int target) {
        List<Tab> tabs = state.tabs();
        if (target < 0 || target >= tabs.size() || !tabs.get(target).enabled()) {
            return state;
        }
        String id = tabs.get(target).id();
        return id.equals(state.activeId()) ? state : new Model(tabs, id, state.focused());
    }

    static int indexOf(List<Tab> tabs, String id) {
        for (int index = 0; index < tabs.size(); index++) {
            if (tabs.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Inserts one tab at a position, clamped to the list.
     */
    static Model inserted(Model state, int index, Tab tab) {
        List<Tab> tabs = state.tabs();
        if (indexOf(tabs, tab.id()) >= 0) {
            throw new IllegalArgumentException("duplicate tab id: " + tab.id());
        }
        List<Tab> next = new ArrayList<>(tabs);
        next.add(Math.max(0, Math.min(index, next.size())), tab);
        return state.withTabs(next);
    }

    /**
     * Removes one tab; the nearest enabled survivor takes over when the shown tab was removed.
     */
    static Model removed(Model state, String id) {
        int index = indexOf(state.tabs(), id);
        if (index < 0) {
            return state;
        }
        List<Tab> next = new ArrayList<>(state.tabs());
        next.remove(index);
        return state.withTabs(next);
    }

    // ── rendering ────────────────────────────────────────────────────────────

}
