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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import examples.parity.RichListDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.RichList;
import io.github.dhoard.braxton.widget.RichListConfig;
import io.github.dhoard.braxton.widget.RichListDefaultRenderer;
import io.github.dhoard.braxton.widget.RichListFilter;
import io.github.dhoard.braxton.widget.RichListModel;
import io.github.dhoard.braxton.widget.RichListMsg;
import io.github.dhoard.braxton.widget.RichListOutput;
import io.github.dhoard.braxton.widget.TextInput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 acceptance: item identity, filtering, paging, chrome, feedback and the demo scene.
 *
 * <p>Every assertion is against public behavior: the model a message produces, the pages it
 * reports, the cells a width paints, the subscriptions a state owns and what a routed key does to
 * the demo scene. The list a test drives is the shipped component with the demo's own definition
 * where the demo is the subject, so a mounted integration is the composition an application
 * actually writes rather than a second copy of it.</p>
 */
class ParityListTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    /**
     * The rows the demo's own status block occupies, which its list definition reserves.
     */
    private static final int DEMO_STATUS_ROWS = RichListDemo.STATUS_ROWS;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static RichList list() {
        return new RichList(RichListConfig.defaults().withRows(21).withItems(items()));
    }

    private static RichList list(RichListConfig config) {
        return new RichList(config);
    }

    /**
     * A small fixture: one row an item, so a page holds as many items as it has rows.
     */
    private static List<RichList.Item> items() {
        return List.of(
                RichList.Item.of("a", "alpha", "first"),
                RichList.Item.of("b", "beta", "second"),
                RichList.Item.of("c", "gamma", "third"),
                RichList.Item.of("d", "delta", "fourth"),
                RichList.Item.of("e", "epsilon", "fifth"),
                RichList.Item.of("f", "zeta", "sixth"));
    }

    private static RichListModel model(List<RichList.Item> items, int rows) {
        return RichListModel.of(items, rows);
    }

    private static RichListModel apply(RichList component, RichListModel state, RichListMsg message) {
        return component.update(state, message).model();
    }

    private static RichListModel apply(RichListModel state, RichListMsg message) {
        return list().update(state, message).model();
    }

    private static InputEvent.KeyInput key(String spec) {
        Key parsed = Key.parse(spec);
        return InputEvent.KeyInput.of(parsed, parsed.name());
    }

    /**
     * A key with the text a terminal reports for it, which is what types into the filter field.
     */
    private static InputEvent.KeyInput typed(String text) {
        Key parsed = Key.parse(text);
        return InputEvent.KeyInput.of(parsed, text);
    }

    private static List<String> painted(Element<?> view) {
        return painted(view, SCREEN_ROWS, SCREEN_COLS);
    }

    private static List<String> painted(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols).lines();
    }

    private static List<String> painted(RichList component, RichListModel state, int rows, int cols) {
        return painted(component.view(state, new ViewContext(new Viewport(rows, cols))), rows, cols);
    }

    private static List<String> ids(List<RichList.Match> matches) {
        return matches.stream().map(RichList.Match::id).toList();
    }

    private static String screen(RichList component, RichListModel state) {
        return String.join("\n", painted(component, state, SCREEN_ROWS, SCREEN_COLS));
    }

    /**
     * The demo the packaged launch runs, driven as its own component.
     */
    private static RichListDemo.Scene demo() {
        return new RichListDemo.Scene();
    }

    private static RichListDemo.Model start() {
        return demo().init(new InitContext(new Viewport(SCREEN_ROWS, SCREEN_COLS), null))
                .model();
    }

    private static RichListDemo.Model apply(RichListDemo.Model state, RichListDemo.Msg message) {
        return demo().update(state, message).model();
    }

    /**
     * What one key does to the demo's screen, as the scene's own message.
     */
    private static Optional<RichListDemo.Msg> route(RichListDemo.Model state, InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(demo(), state, CONTEXT, event).stream()
                .findFirst();
    }

    private static RichListDemo.Model send(RichListDemo.Model state, String keySpec) {
        return apply(
                state, route(state, key(keySpec)).orElseThrow(() -> new AssertionError("no message for " + keySpec)));
    }

    private static RichListDemo.Model type(RichListDemo.Model state, String text) {
        RichListDemo.Model typed = state;
        for (char character : text.toCharArray()) {
            String value = String.valueOf(character);
            typed = apply(
                    typed, route(typed, typed(value)).orElseThrow(() -> new AssertionError("no message for " + value)));
        }
        return typed;
    }

    private static List<String> demoLines(RichListDemo.Model state) {
        return painted(demo().view(state, CONTEXT));
    }

    private static String demoRow(RichListDemo.Model state, int row) {
        return demoLines(state).get(row).stripTrailing();
    }

    // ── R-01: identity, items and renderers ─────────────────────────────────

    @Test
    void anEmptyListHasNoSelectionAndPaintsItsEmptyState() {
        RichList component = list();
        RichListModel state = model(List.of(), 10);
        assertThat(state.items()).isEmpty();
        assertThat(state.selectedId()).isEmpty();
        assertThat(state.selectedItem()).isEmpty();
        assertThat(RichList.visible(state)).isEmpty();
        assertThat(RichList.pages(state, component.config())).isEmpty();
        assertThat(RichList.pageIndex(state, component.config())).isZero();
        assertThat(screen(component, state)).contains(RichList.NO_ITEMS);
        // A choice with nothing selected is not a choice, so the list reports nothing at all.
        assertThat(component.update(state, new RichListMsg.Choose()).outputs()).isEmpty();
    }

    @Test
    void anItemRejectsABlankId() {
        assertThatThrownBy(() -> RichList.Item.of("  ", "title"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank id");
        assertThatThrownBy(() -> RichList.Item.of(null, "title"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank id");
    }

    @Test
    void aModelRejectsTwoItemsThatShareAnId() {
        List<RichList.Item> duplicated = List.of(RichList.Item.of("a", "one"), RichList.Item.of("a", "two"));
        assertThatThrownBy(() -> model(duplicated, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate item id");
    }

    @Test
    void anItemMatchesOnItsTitleUnlessItDeclaresAFilterText() {
        assertThat(RichList.Item.of("a", "alpha", "first").filterText()).isEqualTo("alpha");
        RichList.Item byDescription = new RichList.Item("a", "alpha", "first", "alpha first");
        assertThat(byDescription.filterText()).isEqualTo("alpha first");
        assertThat(ids(RichList.visible(apply(
                                model(List.of(byDescription), 10),
                                new RichListMsg.SetFilterMode(RichList.FilterMode.PREFIX))
                        .withFilter(new RichListFilter(
                                TextInput.Model.of("first").withFocus(false), "", RichList.FilterMode.PREFIX, "")))))
                .containsExactly("a");
    }

    @Test
    void theDefaultRendererMeasuresAnItemByItsDescription() {
        RichListDefaultRenderer renderer = RichListDefaultRenderer.defaults();
        assertThat(renderer.height(RichList.Item.of("a", "alpha"))).isEqualTo(1);
        assertThat(renderer.height(RichList.Item.of("a", "alpha", "one line"))).isEqualTo(2);
        assertThat(renderer.height(RichList.Item.of("a", "alpha", "one\ntwo")))
                .as("a description bound of one row keeps the item two rows tall")
                .isEqualTo(2);
        assertThat(new RichListDefaultRenderer(null, true, 3)
                        .height(RichList.Item.of("a", "alpha", "one\ntwo\nthree\nfour")))
                .isEqualTo(4);
        assertThat(new RichListDefaultRenderer(null, false, 3).height(RichList.Item.of("a", "alpha", "one\ntwo")))
                .as("a hidden description takes no rows")
                .isEqualTo(1);
        assertThat(new RichListDefaultRenderer(null, true, 2).height(RichList.Item.of("a", "alpha", "")))
                .as("an empty description takes no rows")
                .isEqualTo(1);
    }

    @Test
    void theDefaultRendererPaintsAMarkerTitleAndDescription() {
        RichListDefaultRenderer renderer = RichListDefaultRenderer.defaults();
        RichList.Item item = RichList.Item.of("a", "alpha", "first");
        assertThat(renderer.render(new RichList.Row(item, true, 40, List.of())))
                .extracting(StyledText::plainText)
                .containsExactly(RichList.MARKER + "alpha", RichList.MARKER_GAP + "first");
        assertThat(renderer.render(new RichList.Row(item, false, 40, List.of())))
                .extracting(StyledText::plainText)
                .containsExactly(RichList.MARKER_GAP + "alpha", RichList.MARKER_GAP + "first");
    }

    @Test
    void aCustomRendererChoosesItsRowsAndItsStyles() {
        RichList.Renderer tall = new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 3;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                return List.of(
                        StyledText.of("[one] " + row.item().title(), Style.NONE.withBold()),
                        StyledText.of("[two] " + row.item().description(), Style.NONE),
                        StyledText.of("[three]", Style.NONE));
            }
        };
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(9).withRenderer(tall));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        List<String> lines = painted(component, state, 12, 40);
        assertThat(lines).anyMatch(line -> line.contains("[one] alpha"));
        assertThat(lines).anyMatch(line -> line.contains("[two] first"));
        assertThat(lines).anyMatch(line -> line.contains("[three]"));
        assertThat(RichList.pages(state, component.config()))
                .as("three rows an item leaves room for one item in nine content rows")
                .containsExactly(
                        new RichList.Page(0, 1),
                        new RichList.Page(1, 2),
                        new RichList.Page(2, 3),
                        new RichList.Page(3, 4),
                        new RichList.Page(4, 5),
                        new RichList.Page(5, 6));
    }

    @Test
    void insertingAboveTheSelectionKeepsItSelected() {
        RichListModel state = apply(model(items(), 10), new RichListMsg.Down());
        assertThat(state.selectedId()).isEqualTo("b");
        RichListModel inserted = apply(state, new RichListMsg.InsertItem(0, RichList.Item.of("new", "new item")));
        assertThat(inserted.items()).hasSize(7);
        assertThat(inserted.items().get(0).id())
                .as("the new item is where it was asked for")
                .isEqualTo("new");
        assertThat(inserted.selectedId())
                .as("the selection is an item, not a position")
                .isEqualTo("b");
    }

    @Test
    void replacingTheSelectedItemKeepsTheSelection() {
        RichListModel state = apply(model(items(), 10), new RichListMsg.Down());
        RichListModel replaced =
                apply(state, new RichListMsg.ReplaceItem(RichList.Item.of("b", "beta two", "changed")));
        assertThat(replaced.selectedId()).isEqualTo("b");
        assertThat(replaced.selectedItem())
                .get()
                .extracting(RichList.Item::title)
                .isEqualTo("beta two");
        assertThat(apply(replaced, new RichListMsg.ReplaceItem(RichList.Item.of("zz", "absent"))))
                .as("replacing an item the list does not hold is a no-op")
                .isSameAs(replaced);
    }

    @Test
    void removingTheSelectedItemSelectsTheNearestSurvivingItem() {
        RichListModel state = apply(model(items(), 10), new RichListMsg.Down());
        assertThat(state.selectedId()).isEqualTo("b");
        RichListModel removed = apply(state, new RichListMsg.RemoveItem("b"));
        assertThat(removed.items()).hasSize(5);
        assertThat(removed.selectedId())
                .as("the next item takes the place the removed one held")
                .isEqualTo("c");
        RichListModel lastRemoved = apply(apply(removed, new RichListMsg.Last()), new RichListMsg.RemoveItem("f"));
        assertThat(lastRemoved.selectedId())
                .as("removing the last item selects the one before it")
                .isEqualTo("e");
        assertThat(apply(removed, new RichListMsg.RemoveItem("zz"))).isSameAs(removed);
    }

    @Test
    void removingTheOnlyItemLeavesNothingSelected() {
        RichListModel single = model(List.of(RichList.Item.of("only", "only")), 10);
        RichListModel removed = apply(single, new RichListMsg.RemoveItem("only"));
        assertThat(removed.items()).isEmpty();
        assertThat(removed.selectedId()).isEmpty();
        assertThat(RichList.visible(removed)).isEmpty();
    }

    @Test
    void aModelBuiltWithAnAbsentSelectionSelectsTheFirstItem() {
        RichListModel state = new RichListModel(
                items(),
                "zz",
                RichListFilter.of(RichList.FilterMode.FUZZY),
                RichList.Chrome.defaults(),
                10,
                null,
                null,
                true);
        assertThat(state.selectedId()).isEqualTo("a");
        assertThat(new RichListModel(items(), "c", null, null, 10, null, null, true).selectedId())
                .as("a selection the list holds is kept")
                .isEqualTo("c");
    }

    @Test
    void aMultilineTitleIsPaintedOnOneRow() {
        RichList component = list(RichListConfig.defaults()
                .withItems(List.of(new RichList.Item("a", "alpha\nbeta", "first\r\nsecond", null)))
                .withRows(8)
                .withRenderer(new RichListDefaultRenderer(null, true, 2)));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        List<String> lines = painted(component, state, 10, 40);
        assertThat(lines).anyMatch(line -> line.contains(RichList.MARKER + "alpha beta"));
        assertThat(lines).anyMatch(line -> line.contains("first"));
        assertThat(lines).anyMatch(line -> line.contains("second"));
        assertThat(lines).noneMatch(line -> line.contains("\r"));
    }

    @Test
    void anUnfocusedListStillMarksItsSelectionAndRoutesNothing() {
        RichList component = list();
        RichListModel state = model(items(), 10).withFocus(false);
        assertThat(screen(component, state)).contains(RichList.MARKER + "alpha");
        assertThat(routeList(component, state, key("down"))).isEmpty();
    }

    // ── R-02: filtering and pagination ──────────────────────────────────────

    private static Optional<RichListMsg> routeList(RichList component, RichListModel state, InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(component, state, CONTEXT, event).stream()
                .findFirst();
    }

    /**
     * A list with a query already applied, built the way the component builds it.
     */
    private static RichListModel filtered(RichListModel state, String query, RichList.FilterMode mode) {
        return state.withFilter(new RichListFilter(TextInput.Model.of(query).withFocus(false), query, mode, ""));
    }

    @Test
    void matchingIsCaseInsensitiveByCodePoint() {
        RichListModel state = model(items(), 10);
        assertThat(ids(RichList.visible(filtered(state, "GAMMA", RichList.FilterMode.PREFIX))))
                .containsExactly("c");
        assertThat(ids(RichList.visible(filtered(state, "gAm", RichList.FilterMode.FUZZY))))
                .containsExactly("c");
        // U+0130 lowercases to a single 'i' code point under the simple mapping, so folding never
        // moves an offset inside the value.
        RichListModel dotted = model(List.of(RichList.Item.of("i", "İstanbul")), 10);
        assertThat(ids(RichList.visible(filtered(dotted, "istanbul", RichList.FilterMode.PREFIX))))
                .containsExactly("i");
    }

    @Test
    void everyTermOfAQueryMustMatch() {
        RichListModel state = model(
                List.of(new RichList.Item("a", "alpha", "first", "alpha first"), RichList.Item.of("b", "beta")), 10);
        assertThat(ids(RichList.visible(filtered(state, "alpha first", RichList.FilterMode.PREFIX))))
                .as("every term has to match, in any position")
                .containsExactly("a");
        assertThat(ids(RichList.visible(filtered(state, "alpha zzz", RichList.FilterMode.PREFIX))))
                .isEmpty();
        assertThat(ids(RichList.visible(filtered(state, "lph fir", RichList.FilterMode.FUZZY))))
                .as("subsequence terms match across the whole filter text")
                .containsExactly("a");
        assertThat(ids(RichList.visible(filtered(state, "  ", RichList.FilterMode.PREFIX))))
                .as("a blank query filters nothing")
                .containsExactly("a", "b");
    }

    @Test
    void prefixModeKeepsItemOrder() {
        List<RichList.Item> fixture =
                List.of(RichList.Item.of("1", "abc"), RichList.Item.of("2", "xabc"), RichList.Item.of("3", "abc x"));
        RichListModel state = model(fixture, 10);
        assertThat(ids(RichList.visible(filtered(state, "abc", RichList.FilterMode.PREFIX))))
                .containsExactly("1", "2", "3");
    }

    @Test
    void fuzzyModeRanksByShortestSpanThenEarliestStartThenItemOrder() {
        List<RichList.Item> fixture = List.of(
                RichList.Item.of("wide", "a b c"),
                RichList.Item.of("late", "xabc"),
                RichList.Item.of("exact", "abc"),
                RichList.Item.of("second", "ab c"));
        RichListModel state = model(fixture, 10);
        assertThat(ids(RichList.visible(filtered(state, "abc", RichList.FilterMode.FUZZY))))
                .as("shortest span first, then earliest start, then item order")
                .containsExactly("exact", "late", "second", "wide");
    }

    @Test
    void fuzzyModeRanksEqualMatchesByItemOrder() {
        List<RichList.Item> fixture = List.of(
                RichList.Item.of("1", "abc one"), RichList.Item.of("2", "abc two"), RichList.Item.of("3", "abc three"));
        RichListModel state = model(fixture, 10);
        assertThat(ids(RichList.visible(filtered(state, "abc", RichList.FilterMode.FUZZY))))
                .containsExactly("1", "2", "3");
    }

    @Test
    void theStatusRowDistinguishesNoItemsFromNoMatches() {
        RichList component = list();
        assertThat(screen(component, model(items(), 10))).contains("6 items");
        assertThat(screen(component, filtered(model(items(), 10), "zzz", RichList.FilterMode.PREFIX)))
                .contains(RichList.NO_MATCHES);
        assertThat(screen(component, model(List.of(), 10))).contains(RichList.NO_ITEMS);
        assertThat(screen(component, filtered(model(items(), 10), "alpha", RichList.FilterMode.PREFIX)))
                .contains("1 of 6 items");
    }

    @Test
    void beginningAFilterKeepsTheSelectionUntilTheQueryHidesIt() {
        RichList component = list();
        RichListModel state = apply(component, model(items(), 10), new RichListMsg.Down());
        RichListModel editing = apply(component, state, new RichListMsg.BeginFilter());
        assertThat(editing.filter().editing()).isTrue();
        assertThat(editing.selectedId()).as("starting an edit moves nothing").isEqualTo("b");
        assertThat(editing.filter().prefilterSelection()).isEqualTo("b");
        RichListModel typed = apply(component, editing, new RichListMsg.FilterInput(new TextInput.Msg.Insert("gam")));
        assertThat(ids(RichList.visible(typed))).containsExactly("c");
        assertThat(typed.selectedId())
                .as("a hidden selection falls to the first item the query shows")
                .isEqualTo("c");
    }

    @Test
    void cancellingAFilterRestoresThePrefilterSelectionAndQuery() {
        RichList component = list();
        RichListModel state = apply(component, model(items(), 10), new RichListMsg.Down());
        RichListModel accepted = apply(
                component,
                apply(
                        component,
                        apply(component, state, new RichListMsg.BeginFilter()),
                        new RichListMsg.FilterInput(new TextInput.Msg.Insert("alpha"))),
                new RichListMsg.AcceptFilter());
        assertThat(accepted.filter().query()).isEqualTo("alpha");
        assertThat(accepted.filter().applied()).isEqualTo("alpha");
        assertThat(accepted.filter().editing()).isFalse();
        assertThat(accepted.selectedId()).isEqualTo("a");

        RichListModel editing = apply(component, accepted, new RichListMsg.BeginFilter());
        RichListModel extended = apply(component, editing, new RichListMsg.FilterInput(new TextInput.Msg.Insert("x")));
        assertThat(ids(RichList.visible(extended))).isEmpty();
        RichListModel cancelled = apply(component, extended, new RichListMsg.Cancel());
        assertThat(cancelled.filter().editing()).isFalse();
        assertThat(cancelled.filter().query())
                .as("cancel restores the applied query")
                .isEqualTo("alpha");
        assertThat(ids(RichList.visible(cancelled))).containsExactly("a");
    }

    @Test
    void cancellingAFreshFilterRestoresTheSelectionItHid() {
        RichList component = list();
        RichListModel state = apply(component, model(items(), 10), new RichListMsg.Last());
        assertThat(state.selectedId()).isEqualTo("f");
        RichListModel editing = apply(component, state, new RichListMsg.BeginFilter());
        RichListModel typed = apply(component, editing, new RichListMsg.FilterInput(new TextInput.Msg.Insert("alpha")));
        assertThat(typed.selectedId()).isEqualTo("a");
        RichListModel cancelled = apply(component, typed, new RichListMsg.Cancel());
        assertThat(cancelled.filter().query()).isEmpty();
        assertThat(cancelled.selectedId())
                .as("the item selected before the edit is selected again")
                .isEqualTo("f");
    }

    @Test
    void aCancelWithNothingLeftToUndoReportsCancellation() {
        RichList component = list();
        Update<RichListModel, RichListMsg, RichListOutput> update =
                component.update(model(items(), 10), new RichListMsg.Cancel());
        assertThat(update.outputs()).containsExactly(new RichListOutput.Canceled());
    }

    @Test
    void clearingAnAppliedQueryKeepsASelectionThatSurvives() {
        RichList component = list();
        RichListModel state = filtered(model(items(), 10), "gamma", RichList.FilterMode.PREFIX);
        assertThat(state.selectedId()).isEqualTo("c");
        RichListModel cleared = apply(component, state, new RichListMsg.ClearFilter());
        assertThat(cleared.filter().query()).isEmpty();
        assertThat(cleared.selectedId()).isEqualTo("c");
        assertThat(ids(RichList.visible(cleared))).containsExactly("a", "b", "c", "d", "e", "f");
    }

    @Test
    void aFilterModeCanBeSwitchedAndSet() {
        RichList component = list();
        RichListModel state = apply(component, model(items(), 10), new RichListMsg.ToggleFilterMode());
        assertThat(state.filter().mode()).isEqualTo(RichList.FilterMode.PREFIX);
        assertThat(apply(component, state, new RichListMsg.ToggleFilterMode())
                        .filter()
                        .mode())
                .isEqualTo(RichList.FilterMode.FUZZY);
        assertThat(apply(component, state, new RichListMsg.SetFilterMode(RichList.FilterMode.FUZZY))
                        .filter()
                        .mode())
                .isEqualTo(RichList.FilterMode.FUZZY);
    }

    @Test
    void highlightsAreAlignedToWholeGraphemeClusters() {
        assertThat(RichList.matchHighlights("e", "e\u0301x", RichList.FilterMode.PREFIX))
                .as("a combining mark keeps its base's highlight")
                .containsExactly(new RichList.Highlight(0, 2));
        assertThat(RichList.matchHighlights("x", "\uD83D\uDE00x", RichList.FilterMode.PREFIX))
                .as("a surrogate pair is two offsets")
                .containsExactly(new RichList.Highlight(2, 3));
        String joined = "\uD83D\uDC69\u200D\uD83D\uDCBBab";
        for (RichList.Highlight highlight :
                RichList.matchHighlights("\uD83D\uDC69", joined, RichList.FilterMode.PREFIX)) {
            assertThat(TextMeasure.isBoundary(joined, highlight.start())).isTrue();
            assertThat(TextMeasure.isBoundary(joined, highlight.end())).isTrue();
        }
        assertThat(RichList.matchHighlights("zzz", "alpha", RichList.FilterMode.PREFIX))
                .isEmpty();
        assertThat(RichList.matchHighlights("", "alpha", RichList.FilterMode.PREFIX))
                .isEmpty();
    }

    @Test
    void anItemThatMatchesOnlyThroughItsFilterTextPaintsNoHighlight() {
        RichListModel state = model(List.of(new RichList.Item("a", "alpha", "first", "alpha first")), 10);
        RichList.Match match = RichList.visible(filtered(state, "first", RichList.FilterMode.PREFIX))
                .get(0);
        assertThat(match.highlights()).as("the title has nothing to highlight").isEmpty();
        RichList.Match titleMatch = RichList.visible(filtered(state, "alp", RichList.FilterMode.PREFIX))
                .get(0);
        assertThat(titleMatch.highlights()).containsExactly(new RichList.Highlight(0, 3));
    }

    @Test
    void pagesCoverEveryVisibleItemExactlyOnce() {
        RichList component = list(RichListConfig.defaults()
                .withItems(RichListDemo.ITEMS)
                .withRows(
                        16 + RichList.chromeRows(RichListModel.of(RichListDemo.ITEMS, 21), RichListConfig.defaults())));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        List<RichList.Page> pages = RichList.pages(state, component.config());
        assertThat(pages).hasSize(30);
        int next = 0;
        for (RichList.Page page : pages) {
            assertThat(page.start()).as("pages are contiguous").isEqualTo(next);
            next = page.end();
        }
        assertThat(next).isEqualTo(RichListDemo.ITEM_COUNT);
        assertThat(pages.get(0)).isEqualTo(new RichList.Page(0, 8));
    }

    @Test
    void pageMovementSelectsTheFirstItemOfThePageArrivedAt() {
        RichList component =
                list(RichListConfig.defaults().withItems(RichListDemo.ITEMS).withRows(21));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        RichListModel second = apply(component, state, new RichListMsg.NextPage());
        assertThat(second.selectedId()).isEqualTo("item-009");
        assertThat(RichList.pageIndex(second, component.config())).isEqualTo(1);
        RichListModel first = apply(component, second, new RichListMsg.PreviousPage());
        assertThat(first.selectedId()).isEqualTo("item-001");
        assertThat(apply(component, first, new RichListMsg.PreviousPage()))
                .as("paging before the first page changes nothing")
                .isSameAs(first);
        RichListModel last = apply(component, first, new RichListMsg.Last());
        assertThat(RichList.pageIndex(last, component.config())).isEqualTo(29);
        assertThat(apply(component, last, new RichListMsg.NextPage())).isSameAs(last);
    }

    @Test
    void movingTheSelectionAcrossAPageBoundaryFollowsIt() {
        RichList component =
                list(RichListConfig.defaults().withItems(RichListDemo.ITEMS).withRows(21));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        RichListModel eighth = state;
        for (int step = 0; step < 7; step++) {
            eighth = apply(component, eighth, new RichListMsg.Down());
        }
        assertThat(eighth.selectedId()).isEqualTo("item-008");
        assertThat(RichList.pageIndex(eighth, component.config())).isZero();
        RichListModel ninth = apply(component, eighth, new RichListMsg.Down());
        assertThat(ninth.selectedId()).isEqualTo("item-009");
        assertThat(RichList.pageIndex(ninth, component.config()))
                .as("the page follows the selection instead of being stored")
                .isEqualTo(1);
    }

    @Test
    void aResizeRepacksThePagesAndKeepsTheSelection() {
        RichList component =
                list(RichListConfig.defaults().withItems(RichListDemo.ITEMS).withRows(21));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        for (int step = 0; step < 100; step++) {
            state = apply(component, state, new RichListMsg.Down());
        }
        assertThat(state.selectedId()).isEqualTo("item-101");
        int before = RichList.pages(state, component.config()).size();
        RichListModel shorter = apply(component, state, new RichListMsg.RowsChanged(12));
        assertThat(shorter.rows()).isEqualTo(12);
        assertThat(shorter.selectedId()).as("a resize keeps the selected item").isEqualTo("item-101");
        assertThat(RichList.pages(shorter, component.config()))
                .as("a shorter list holds fewer items a page")
                .hasSizeGreaterThan(before);
        assertThat(RichList.pageIndex(shorter, component.config()))
                .as("two-row items fill four a page of the eight content rows")
                .isEqualTo(25);
    }

    @Test
    void tallItemsTakeMoreOfAPage() {
        RichList tall = list(RichListConfig.defaults()
                .withItems(items())
                .withRows(8)
                .withRenderer(new RichListDefaultRenderer(null, true, 3)));
        RichListModel state =
                tall.init(new InitContext(new Viewport(0, 0), null)).model();
        // Four rows are left after the chrome (title, counts, dots and one compact help line), and
        // a two-row item means two of them fit a page.
        assertThat(RichList.contentRows(state, tall.config())).isEqualTo(4);
        assertThat(RichList.pages(state, tall.config()).get(0)).isEqualTo(new RichList.Page(0, 2));

        List<RichList.Item> tallItems = List.of(
                RichList.Item.of("a", "alpha", "one\ntwo\nthree"), RichList.Item.of("b", "beta", "one\ntwo\nthree"));
        RichList four = list(RichListConfig.defaults()
                .withItems(tallItems)
                .withRows(8)
                .withRenderer(new RichListDefaultRenderer(null, true, 3)));
        RichListModel fourState =
                four.init(new InitContext(new Viewport(0, 0), null)).model();
        assertThat(RichList.pages(fourState, four.config()).get(0))
                .as("a four-row item fills the page on its own")
                .isEqualTo(new RichList.Page(0, 1));
    }

    @Test
    void anItemTallerThanTheContentIsClippedRatherThanSkipped() {
        RichList.Renderer tall = new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 9;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                List<StyledText> rows = new ArrayList<>();
                for (int index = 0; index < 9; index++) {
                    rows.add(StyledText.plain(row.item().title() + " " + index));
                }
                return rows;
            }
        };
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(7).withRenderer(tall));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        assertThat(RichList.contentRows(state, component.config())).isEqualTo(3);
        assertThat(RichList.pages(state, component.config())).hasSize(6);
        List<String> lines = painted(component, state, 8, 40);
        assertThat(lines.stream().filter(line -> line.contains("alpha")))
                .as("the item is clipped to the content rows it was given")
                .hasSize(3);
        assertThat(lines.stream().filter(line -> line.contains("beta")))
                .as("the next item is on the next page, not clipped into this one")
                .isEmpty();
    }

    @Test
    void insertingWhileFilteringKeepsTheSelectionWhenItStillMatches() {
        RichListModel state = filtered(model(items(), 10), "a", RichList.FilterMode.PREFIX);
        assertThat(ids(RichList.visible(state))).containsExactly("a", "b", "c", "d", "f");
        RichListModel inserted = apply(state, new RichListMsg.InsertItem(0, RichList.Item.of("new", "an item")));
        assertThat(inserted.selectedId()).isEqualTo("a");
    }

    // ── R-03: chrome, height and feedback ──────────────────────────────────

    @Test
    void eachChromeToggleGivesItsRowsBackToTheContent() {
        RichList component = list();
        RichListModel state = model(items(), 21);
        assertThat(RichList.chromeRows(state, component.config())).isEqualTo(4);
        assertThat(RichList.contentRows(state, component.config())).isEqualTo(17);
        assertThat(RichList.contentRows(apply(component, state, new RichListMsg.ToggleTitle()), component.config()))
                .isEqualTo(18);
        assertThat(RichList.contentRows(apply(component, state, new RichListMsg.ToggleStatus()), component.config()))
                .isEqualTo(18);
        assertThat(RichList.contentRows(
                        apply(component, state, new RichListMsg.TogglePagination()), component.config()))
                .isEqualTo(18);
        assertThat(RichList.contentRows(apply(component, state, new RichListMsg.ToggleHelp()), component.config()))
                .isEqualTo(18);
        RichListModel bare = apply(
                component,
                apply(
                        component,
                        apply(
                                component,
                                apply(component, state, new RichListMsg.ToggleTitle()),
                                new RichListMsg.ToggleStatus()),
                        new RichListMsg.TogglePagination()),
                new RichListMsg.ToggleHelp());
        assertThat(RichList.chromeRows(bare, component.config())).isZero();
        assertThat(RichList.contentRows(bare, component.config())).isEqualTo(21);
    }

    @Test
    void theFilterBarIsPaintedEvenWhenTheTitleIsSwitchedOff() {
        RichList component = list();
        RichListModel state = apply(
                component,
                apply(component, model(items(), 10), new RichListMsg.ToggleTitle()),
                new RichListMsg.BeginFilter());
        assertThat(RichList.chromeRows(state, component.config())).isEqualTo(4);
        assertThat(String.join("\n", painted(component, state, 12, 40))).contains("alpha");
        assertThat(painted(component, state, 12, 40).get(0))
                .as("the filter field is the first row")
                .contains(TextInput.CURSOR);
        assertThat(screen(component, state)).doesNotContain("alpha first");
    }

    @Test
    void zeroAvailableRowsPaintsNoContentAndNoError() {
        RichList component = list();
        RichListModel state = model(items(), 0);
        assertThat(RichList.contentRows(state, component.config())).isZero();
        assertThat(RichList.pages(state, component.config())).hasSize(6);
        String painted = screen(component, state);
        for (RichList.Item item : items()) {
            assertThat(painted).as("no item is painted in zero content rows").doesNotContain(item.title());
        }
        assertThat(painted).contains("6 items");
    }

    @Test
    void theNoticeIsPaintedAfterTheCountsAndExpires() {
        RichList component = list();
        Update<RichListModel, RichListMsg, RichListOutput> update =
                component.update(model(items(), 10), new RichListMsg.Notice("added one"));
        RichListModel noticed = update.model();
        assertThat(noticed.notice().text()).isEqualTo("added one");
        assertThat(noticed.notice().epoch()).isEqualTo(1);
        assertThat(screen(component, noticed)).contains("6 items" + RichList.STATUS_SEPARATOR + "added one");
        assertThat(update.command())
                .as("the notice owns the scope that clears it")
                .isInstanceOf(Command.Delay.class);
        RichListModel expired = apply(component, noticed, new RichListMsg.NoticeExpired(1));
        assertThat(expired.notice().present()).isFalse();
        assertThat(screen(component, expired)).doesNotContain("added one");
    }

    @Test
    void aStaleExpiryDoesNotClearANewerNotice() {
        RichList component = list();
        RichListModel first = component
                .update(model(items(), 10), new RichListMsg.Notice("one"))
                .model();
        RichListModel second =
                component.update(first, new RichListMsg.Notice("two")).model();
        assertThat(second.notice().epoch()).isEqualTo(2);
        RichListModel stale = apply(component, second, new RichListMsg.NoticeExpired(1));
        assertThat(stale)
                .as("an expiry for a notice that no longer exists changes nothing")
                .isSameAs(second);
        assertThat(apply(component, second, new RichListMsg.NoticeExpired(2))
                        .notice()
                        .present())
                .isFalse();
    }

    @Test
    void aNoticeIsScheduledOnce() {
        RichList component = list();
        Update<RichListModel, RichListMsg, RichListOutput> noticed =
                component.update(model(items(), 10), new RichListMsg.Notice("one"));
        assertThat(noticed.command()).isInstanceOf(Command.Delay.class);
        Update<RichListModel, RichListMsg, RichListOutput> moved =
                component.update(noticed.model(), new RichListMsg.Down());
        assertThat(moved.command())
                .as("a state that did not set a notice schedules nothing")
                .isInstanceOf(Command.None.class);
    }

    @Test
    void theSpinnerRunsOnlyWhileLoading() {
        RichList component = list();
        RichListModel state = model(items(), 10);
        List<Subscription<RichListMsg>> idle = Subscription.leaves(component.subscriptions(state));
        assertThat(idle).hasSize(1);
        assertThat(idle.get(0)).isInstanceOf(Subscription.Resize.class);
        RichListModel loading = apply(component, state, new RichListMsg.ToggleLoading());
        assertThat(loading.chrome().loading()).isTrue();
        assertThat(loading.spinner().running()).isTrue();
        List<Subscription<RichListMsg>> busy = Subscription.leaves(component.subscriptions(loading));
        assertThat(busy).hasSize(2);
        assertThat(busy).anyMatch(leaf -> leaf instanceof Subscription.Interval<?>);
        RichListModel stopped = apply(component, loading, new RichListMsg.ToggleLoading());
        assertThat(stopped.chrome().loading()).isFalse();
        assertThat(stopped.spinner().running()).isFalse();
        assertThat(Subscription.leaves(component.subscriptions(stopped)))
                .as("stopping the spinner closes its interval")
                .hasSize(1);
    }

    @Test
    void theTitleBarPaintsTheSpinnerOnlyWhileLoading() {
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(10).withTitle("Things"));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        assertThat(painted(component, state, 12, 40).get(0)).isEqualTo("Things");
        RichListModel loading = apply(component, state, new RichListMsg.ToggleLoading());
        assertThat(painted(component, loading, 12, 40).get(0))
                .contains("Things")
                .isNotEqualTo("Things");
    }

    @Test
    void fullHelpListsEveryGroupAndCompactHelpOneLine() {
        RichList component = list();
        RichListModel state = model(items(), 21);
        List<String> compact = painted(component, state, 24, 120);
        assertThat(compact.stream().filter(line -> line.contains("up \u2022"))).hasSize(1);
        RichListModel full = apply(component, state, new RichListMsg.ToggleExpandedHelp());
        List<String> expanded = painted(component, full, 32, 120);
        assertThat(expanded).anyMatch(line -> line.contains("Move"));
        assertThat(expanded).anyMatch(line -> line.contains("Chrome"));
        assertThat(expanded).anyMatch(line -> line.contains("General"));
        assertThat(RichList.chromeRows(full, component.config()))
                .as("full help takes one row per group with entries")
                .isGreaterThan(RichList.chromeRows(state, component.config()));
    }

    @Test
    void helpDescribesTheGesturesOneStateHandles() {
        RichList component = list();
        RichListModel state = model(items(), 10);
        List<String> browsing = helpLines(component, state);
        assertThat(browsing).anyMatch(line -> line.contains("enter choose"));
        assertThat(browsing).anyMatch(line -> line.contains("esc cancel"));
        RichListModel editing = apply(component, state, new RichListMsg.BeginFilter());
        List<String> filtering = helpLines(component, editing);
        assertThat(filtering).anyMatch(line -> line.contains("enter apply filter"));
        assertThat(filtering).anyMatch(line -> line.contains("esc cancel edit"));
        assertThat(filtering).noneMatch(line -> line.contains("choose"));
        RichListModel applied = filtered(state, "alpha", RichList.FilterMode.PREFIX);
        assertThat(helpLines(component, applied)).anyMatch(line -> line.contains("esc clear filter"));
    }

    private static List<String> helpLines(RichList component, RichListModel state) {
        return painted(component, state, 24, 120).stream()
                .filter(line -> line.contains("\u2022") || line.contains("Move"))
                .toList();
    }

    @Test
    void theApplicationAddsItsOwnHelpWithoutClaimingItsGestures() {
        KeyMap<RichListDemo.Msg> app = RichListDemo.keys();
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(16).withExtraHelp(app));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        assertThat(painted(component, state, 20, 120)).anyMatch(line -> line.contains("a add item"));
        assertThat(routeList(component, state, key("a")))
                .as("the list does not route the application's own gestures")
                .isEmpty();
    }

    @Test
    void theResizeSubscriptionReportsTheTerminalHeight() {
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(21).withReservedRows(3));
        List<Subscription<RichListMsg>> leaves = Subscription.leaves(component.subscriptions(model(items(), 21)));
        Subscription.Resize<RichListMsg> resize = (Subscription.Resize<RichListMsg>) leaves.get(0);
        RichListMsg message = resize.onResize().apply(new io.github.dhoard.braxton.layout.Size(24, 80));
        RichListModel state = apply(component, model(items(), 21), message);
        assertThat(state.rows())
                .as("the application's reserved rows are taken out")
                .isEqualTo(21);
    }

    // ── R-04: the demo scene ───────────────────────────────────────────────

    @Test
    void theDemoStartsWithTheWholeFixture() {
        RichListDemo.Model state = start();
        assertThat(state.list().items()).hasSize(RichListDemo.ITEM_COUNT);
        assertThat(state.list().selectedId()).isEqualTo("item-001");
        assertThat(demoRow(state, 0))
                .contains("preset=default")
                .contains("items=240")
                .contains("selected=item-001");
        assertThat(demoRow(state, 1))
                .contains("filter=-")
                .contains("mode=fuzzy")
                .contains("matches=240");
        assertThat(demoRow(state, 3)).isEqualTo("Favorite things");
        assertThat(demoRow(state, 4)).isEqualTo("240 items");
        assertThat(demoRow(state, 5)).contains(RichList.MARKER + "Raspberry jam");
        assertThat(demoRow(state, 22)).contains("\u2191 up");
        assertThat(demoRow(state, 23)).contains("a add item");
    }

    @Test
    void theDemoFiltersAsTheQueryIsTypedAndCancelsBack() {
        RichListDemo.Model state = send(start(), "/");
        assertThat(state.list().filter().editing()).isTrue();
        assertThat(demoRow(state, 3)).contains("Filter: ");
        RichListDemo.Model typed = type(state, "jam");
        assertThat(typed.list().filter().query()).isEqualTo("jam");
        assertThat(demoRow(typed, 1)).contains("filter=jam").contains("editing=on");
        int matches = RichList.visible(typed.list()).size();
        assertThat(matches).isEqualTo(12);
        assertThat(demoRow(typed, 4)).isEqualTo("12 of 240 items");
        RichListDemo.Model accepted = send(typed, "enter");
        assertThat(accepted.list().filter().editing()).isFalse();
        assertThat(accepted.list().filter().applied()).isEqualTo("jam");
        assertThat(RichList.visible(accepted.list())).hasSize(matches);
        RichListDemo.Model cleared = send(accepted, "escape");
        assertThat(cleared.list().filter().query()).isEmpty();
        assertThat(RichList.visible(cleared.list())).hasSize(RichListDemo.ITEM_COUNT);
        assertThat(cleared.last()).isEqualTo("none");
    }

    @Test
    void theDemoPagesThroughTheFixtureAndKeepsItsSelection() {
        RichListDemo.Model state = send(start(), "right");
        assertThat(demoRow(state, 1)).contains("page=2/30");
        assertThat(state.list().selectedId()).isEqualTo("item-009");
        assertThat(demoRow(state, 5)).contains(RichList.MARKER + "Pottery");
        RichListDemo.Model last = send(state, "end");
        assertThat(demoRow(last, 1)).contains("page=30/30");
        assertThat(last.list().selectedId()).isEqualTo("item-240");
        assertThat(send(last, "home").list().selectedId()).isEqualTo("item-001");
    }

    @Test
    void theDemoAddsAndRemovesItemsWithoutLosingItsSelection() {
        RichListDemo.Model added = send(start(), "a");
        assertThat(added.list().items()).hasSize(RichListDemo.ITEM_COUNT + 1);
        assertThat(demoRow(added, 0)).contains("items=241").contains("added=1").contains("last=added-1");
        assertThat(added.list().selectedId())
                .as("an item added above the selection leaves it selected")
                .isEqualTo("item-001");
        assertThat(demoRow(added, 4)).isEqualTo("241 items");

        RichListDemo.Model removed = send(added, "x");
        assertThat(removed.list().items()).hasSize(RichListDemo.ITEM_COUNT);
        assertThat(removed.list().selectedId())
                .as("removing the selection selects the nearest surviving item")
                .isEqualTo("item-002");
        assertThat(demoRow(removed, 0)).contains("selected=item-002").contains("last=removed-item-001");
    }

    @Test
    void theDemoSwitchesPresetsAndKeepsItsSelection() {
        RichListDemo.Model simple = send(start(), "1");
        assertThat(demoRow(simple, 0)).contains("preset=simple");
        assertThat(simple.list().selectedId()).isEqualTo("item-001");
        RichListDemo.Model fancy = send(send(simple, "3"), "down");
        assertThat(demoRow(fancy, 0)).contains("preset=fancy");
        assertThat(fancy.list().selectedId()).isEqualTo("item-002");
        List<String> lines = demoLines(fancy);
        assertThat(lines).anyMatch(line -> line.contains(RichList.MARKER + "Hazelnut spread"));
        assertThat(lines).anyMatch(line -> line.contains("Better than the sum of its parts"));
        assertThat(lines.get(11))
                .as("three rows an item: the third title follows the second item's blank row")
                .contains("Bitter greens");
    }

    @Test
    void theDemoChoosesAnItemAndDispatchesANoticeThroughTheMount() {
        Update<RichListDemo.Model, RichListDemo.Msg, Never> update =
                demo().update(start(), new RichListDemo.Msg.List(new RichListMsg.Choose()));
        assertThat(update.outputs()).isEmpty();
        assertThat(update.command())
                .as("the choice is routed to the scene as a message")
                .isInstanceOf(Command.Dispatch.class);
        RichListDemo.Msg chosen = ((Command.Dispatch<RichListDemo.Msg>) update.command()).message();
        assertThat(chosen)
                .isEqualTo(new RichListDemo.Msg.Output(new RichListOutput.Chosen(
                        List.of(start().list().selectedItem().orElseThrow()))));
        Update<RichListDemo.Model, RichListDemo.Msg, Never> reported = demo().update(update.model(), chosen);
        assertThat(reported.model().last()).isEqualTo("chose-item-001");
        assertThat(reported.command())
                .as("the notice is dispatched back through the mount")
                .isInstanceOf(Command.Dispatch.class);
        RichListDemo.Msg dispatched = ((Command.Dispatch<RichListDemo.Msg>) reported.command()).message();
        assertThat(dispatched).isInstanceOf(RichListDemo.Msg.List.class);
        RichListDemo.Model noticed = apply(reported.model(), dispatched);
        assertThat(noticed.list().notice().text()).isEqualTo("chose Raspberry jam");
        assertThat(demoRow(noticed, 4)).isEqualTo("240 items" + RichList.STATUS_SEPARATOR + "chose Raspberry jam");
    }

    @Test
    void theDemoQuitsWhenTheListReportsCancellation() {
        Update<RichListDemo.Model, RichListDemo.Msg, Never> cancelled =
                demo().update(start(), new RichListDemo.Msg.List(new RichListMsg.Cancel()));
        RichListDemo.Msg routed = ((Command.Dispatch<RichListDemo.Msg>) cancelled.command()).message();
        assertThat(routed).isEqualTo(new RichListDemo.Msg.Output(new RichListOutput.Canceled()));
        Update<RichListDemo.Model, RichListDemo.Msg, Never> quit = demo().update(cancelled.model(), routed);
        assertThat(quit.command()).isInstanceOf(Command.Quit.class);
        assertThat(quit.model().last()).isEqualTo("canceled");
    }

    @Test
    void theDemoFollowsAResizeThroughTheMountedList() {
        RichListDemo.Model state = send(start(), "down");
        int rows = state.list().rows();
        RichListDemo.Model shorter =
                apply(state, new RichListDemo.Msg.List(new RichListMsg.RowsChanged(rows + DEMO_STATUS_ROWS - 6)));
        assertThat(shorter.list().rows()).isEqualTo(rows - 6);
        assertThat(shorter.list().selectedId())
                .as("a resize keeps the selected item")
                .isEqualTo("item-002");
        assertThat(demoRow(shorter, 2)).contains("rows=" + (rows - 6));
        assertThat(demoLines(shorter).stream().filter(line -> line.contains("Raspberry jam")))
                .as("the shorter list still paints its selected item")
                .isNotEmpty();
    }

    @Test
    void theDemoChromeTogglesRepackTheList() {
        RichListDemo.Model state = start();
        int content = RichList.contentRows(state.list(), RichListDemo.config(state));
        RichListDemo.Model bare = send(send(send(send(state, "t"), "c"), "p"), "h");
        assertThat(demoRow(bare, 2))
                .contains("title=off")
                .contains("status=off")
                .contains("page=off")
                .contains("help=off");
        assertThat(RichList.contentRows(bare.list(), RichListDemo.config(bare)))
                .isEqualTo(content + RichList.chromeRows(state.list(), RichListDemo.config(state)));
        assertThat(demoLines(bare)).anyMatch(line -> line.contains(RichList.MARKER + "Raspberry jam"));
    }

    @Test
    void theDemoLoadingSpinnerAnimatesAndStops() {
        RichListDemo.Model state = send(start(), "s");
        assertThat(demoRow(state, 2)).contains("spinner=on");
        assertThat(state.list().chrome().loading()).isTrue();
        assertThat(Subscription.leaves(demo().subscriptions(state)))
                .as("the mounted list owns the spinner interval while loading")
                .hasSize(2);
        RichListDemo.Model stopped = send(state, "s");
        assertThat(demoRow(stopped, 2)).contains("spinner=off");
        assertThat(Subscription.leaves(demo().subscriptions(stopped))).hasSize(1);
    }

    // ── R-05: preserved contracts, bounds and lifecycle ─────────────────────

    @Test
    void aMessageThatChangesNothingKeepsTheIncomingInstance() {
        RichList component = list();
        RichListModel state = model(items(), 10);
        assertThat(apply(component, state, new RichListMsg.Up())).isSameAs(state);
        assertThat(apply(component, state, new RichListMsg.BeginFilter()).selectedId())
                .isEqualTo("a");
        assertThat(apply(component, state, new RichListMsg.SetItems(items()))).isSameAs(state);
        assertThat(apply(
                        component,
                        apply(component, state, new RichListMsg.ToggleTitle()),
                        new RichListMsg.ToggleTitle()))
                .isEqualTo(state);
    }

    @Test
    void theViewIsAPureFunctionOfTheModel() {
        RichList component = list();
        RichListModel state = filtered(model(items(), 12), "a", RichList.FilterMode.FUZZY);
        List<String> first = painted(component, state, SCREEN_ROWS, SCREEN_COLS);
        List<String> second = painted(component, state, SCREEN_ROWS, SCREEN_COLS);
        assertThat(second).isEqualTo(first);
        assertThat(state.filter().query())
                .as("painting does not change the model")
                .isEqualTo("a");
    }

    @Test
    void onlyThePageBeingShownIsRendered() {
        List<RichList.Item> items = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            items.add(RichList.Item.of("i" + index, "item " + index));
        }
        int[] rendered = {0};
        int[] measured = {0};
        RichList.Renderer counting = new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                measured[0]++;
                return 1;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                rendered[0]++;
                return List.of(StyledText.plain(row.item().title()));
            }
        };
        RichList component =
                list(RichListConfig.defaults().withItems(items).withRows(21).withRenderer(counting));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        assertTimeout(Duration.ofSeconds(2), () -> painted(component, state, SCREEN_ROWS, SCREEN_COLS));
        assertThat(rendered[0]).as("only one page of items is rendered").isEqualTo(17);
        assertThat(measured[0])
                .as("measuring stays linear in the fixture and never touches a hidden row")
                .isLessThan(2 * items.size());
    }

    @Test
    void aRowWiderThanTheListIsCutInsteadOfWrapping() {
        RichList.Renderer wide = new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 1;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                return List.of(StyledText.plain("x".repeat(200)));
            }
        };
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(8).withRenderer(wide));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        for (String line : painted(component, state, 12, 40)) {
            assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(40);
        }
    }

    @Test
    void aRendererThatReportsNoRowsOrTooManyIsBounded() {
        RichList.Renderer silent = new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 2;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                return List.of(StyledText.plain("first " + row.item().title()));
            }
        };
        RichList component =
                list(RichListConfig.defaults().withItems(items()).withRows(9).withRenderer(silent));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        assertThat(painted(component, state, 12, 40)).anyMatch(line -> line.contains("first alpha"));

        RichList.Renderer chatty = new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 1;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                return List.of(
                        StyledText.plain("a " + row.item().title()),
                        StyledText.plain("b " + row.item().title()));
            }
        };
        RichList tall = new RichList(
                RichListConfig.defaults().withItems(items()).withRows(9).withRenderer(chatty));
        RichListModel chattyState =
                tall.init(new InitContext(new Viewport(0, 0), null)).model();
        List<String> lines = painted(tall, chattyState, 12, 40);
        assertThat(lines.stream().filter(line -> line.startsWith("a alpha"))).hasSize(1);
        assertThat(lines.stream().filter(line -> line.startsWith("b alpha")))
                .as("a row past the item's declared height is dropped")
                .isEmpty();
    }

    @Test
    void aTickFromAStoppedSpinnerIsIgnored() {
        RichList component = list();
        RichListModel state = model(items(), 10);
        RichListMsg stale = new RichListMsg.SpinnerMessage(new io.github.dhoard.braxton.widget.Spinner.Msg.Tick(0, 5));
        assertThat(apply(component, state, stale)).isSameAs(state);
    }

    @Test
    void theListNeverPaintsOutsideTheBoxItWasGiven() {
        java.util.Random random = new java.util.Random(11_011L);
        String[] titles = {
            "alpha",
            "\u4E2D\u6587 mixed",
            "e\u0301",
            "\uD83D\uDE00 done",
            "",
            "tab\there",
            "line1\nline2",
            "\uD83D\uDC69\u200D\uD83D\uDCBB join",
            "x".repeat(30)
        };
        RichListMsg[] browse = {
            new RichListMsg.Up(),
            new RichListMsg.Down(),
            new RichListMsg.NextPage(),
            new RichListMsg.PreviousPage(),
            new RichListMsg.First(),
            new RichListMsg.Last(),
            new RichListMsg.ToggleTitle(),
            new RichListMsg.ToggleStatus(),
            new RichListMsg.TogglePagination(),
            new RichListMsg.ToggleHelp(),
            new RichListMsg.ToggleExpandedHelp(),
            new RichListMsg.ToggleLoading(),
            new RichListMsg.BeginFilter(),
            new RichListMsg.AcceptFilter(),
            new RichListMsg.Cancel(),
            new RichListMsg.ClearFilter(),
            new RichListMsg.ToggleFilterMode(),
            new RichListMsg.FilterInput(new TextInput.Msg.Insert("a")),
            new RichListMsg.FilterInput(new TextInput.Msg.Backspace()),
            new RichListMsg.Notice("a transient message"),
            new RichListMsg.NoticeExpired(0),
            new RichListMsg.RowsChanged(1 + random.nextInt(30))
        };
        for (int round = 0; round < 200; round++) {
            List<RichList.Item> items = new ArrayList<>();
            for (int index = 0; index < 1 + random.nextInt(8); index++) {
                String title = titles[random.nextInt(titles.length)];
                items.add(new RichList.Item("id-" + index, title, titles[random.nextInt(titles.length)], null));
            }
            RichList component = list(RichListConfig.defaults()
                    .withItems(items)
                    .withRows(1 + random.nextInt(24))
                    .withRenderer(
                            random.nextInt(3) == 0
                                    ? new RichListDefaultRenderer(null, true, 2)
                                    : RichListDefaultRenderer.defaults()));
            RichListModel state =
                    component.init(new InitContext(new Viewport(0, 0), null)).model();
            for (int step = 1 + random.nextInt(8); step > 0; step--) {
                state = component
                        .update(state, browse[random.nextInt(browse.length)])
                        .model();
            }
            int rows = 1 + random.nextInt(20);
            int cols = 1 + random.nextInt(40);
            List<String> lines = painted(component, state, rows, cols);
            assertThat(lines)
                    .as("a %dx%d paint of %s", rows, cols, state.rows())
                    .hasSize(rows);
            for (String line : lines) {
                assertThat(TextMeasure.columns(line))
                        .as("a row [%s] of a %dx%d paint", line, rows, cols)
                        .isLessThanOrEqualTo(cols);
            }
            assertThat(painted(component, state, rows, cols))
                    .as("painting twice gave different cells")
                    .isEqualTo(lines);
        }
    }

    @Test
    void theDefinitionRejectsAnImpossibleHeightOrLifetime() {
        assertThatThrownBy(() -> RichListConfig.defaults().withRows(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> RichListConfig.defaults().withReservedRows(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservedRows");
        assertThatThrownBy(() -> RichListConfig.defaults().withNoticeLifetime(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noticeLifetime");
        assertThat(RichListConfig.defaults().withRows(3).rows()).isEqualTo(3);
    }

    @Test
    void theModelRejectsANegativeEpochAndDefaultsItsParts() {
        assertThatThrownBy(() -> new RichList.Notice("x", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
        RichListModel state = new RichListModel(null, null, null, null, -5, null, null, true);
        assertThat(state.items()).isEmpty();
        assertThat(state.rows()).isZero();
        assertThat(state.notice()).isEqualTo(RichList.Notice.none());
        assertThat(state.chrome()).isEqualTo(RichList.Chrome.defaults());
    }

    @Test
    void oneCellViewportsAndWideGlyphsStayInsideTheirColumns() {
        RichList component = list(RichListConfig.defaults()
                .withItems(List.of(RichList.Item.of("a", "\u4E2D\u6587", "\uD83D\uDE00 tail")))
                .withRows(10));
        RichListModel state =
                component.init(new InitContext(new Viewport(0, 0), null)).model();
        for (String line : painted(component, state, 12, 1)) {
            assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(1);
        }
        List<String> narrow = painted(component, state, 12, 3);
        assertThat(narrow).isNotEmpty();
        assertThat(String.join("\n", narrow)).doesNotContain("\uFFFD");
    }

    @Test
    void aKeyTheListConsumesNeverReachesTheApplication() {
        RichList component = list();
        RichListModel state = model(items(), 10);
        assertThat(routeList(component, state, key("down"))).contains(new RichListMsg.Down());
        assertThat(routeList(component, state, key("/"))).contains(new RichListMsg.BeginFilter());
        assertThat(routeList(component, state, typed("a")))
                .as("a printable key that is not a list gesture is left for the application")
                .isEmpty();
        RichListModel editing = apply(component, state, new RichListMsg.BeginFilter());
        assertThat(routeList(component, editing, typed("j")))
                .as("while editing, a printable key types instead of moving")
                .contains(new RichListMsg.FilterInput(new TextInput.Msg.Insert("j")));
        assertThat(routeList(component, editing, key("escape"))).contains(new RichListMsg.Cancel());
        assertThat(routeList(component, editing, key("enter"))).contains(new RichListMsg.AcceptFilter());
    }

    @Test
    void helpNeverAdvertisesAGestureTheStateDisabled() {
        RichListModel editing = apply(list(), model(items(), 10), new RichListMsg.BeginFilter());
        KeyMap<RichListMsg> keys = RichList.keys(editing);
        assertThat(keys.action("choose").orElseThrow().enabled()).isFalse();
        assertThat(keys.action("up").orElseThrow().enabled()).isFalse();
        assertThat(keys.action("accept-filter").orElseThrow().enabled()).isTrue();
        assertThat(keys.bindings())
                .as("the disabled actions contribute no bindings")
                .noneMatch(binding -> binding.key().equals(Key.parse("down")));
        assertThat(Help.render(keys, false, 120).plainText()).doesNotContain("choose");
        assertThat(RichList.keys(model(items(), 10))
                        .action("accept-filter")
                        .orElseThrow()
                        .enabled())
                .as("the two enter actions are never enabled together")
                .isFalse();
    }

    @Test
    void aMountedListScopesItsOwnEffects() {
        RichListDemo.Scene scene = demo();
        RichListDemo.Model state = start();
        Update<RichListDemo.Model, RichListDemo.Msg, ?> update =
                scene.update(state, new RichListDemo.Msg.List(new RichListMsg.Notice("scoped")));
        assertThat(update.command()).isInstanceOf(Command.Scoped.class);
        Command.Scoped<RichListDemo.Msg> scoped = (Command.Scoped<RichListDemo.Msg>) update.command();
        assertThat(scoped.path().toString()).contains("list");
        assertThat(scoped.command()).isInstanceOf(Command.Delay.class);
    }

    @Test
    void theDriverRunsTheShippedDefinitionWithoutATerminal() {
        ComponentDriver<RichListModel, RichListMsg, RichListOutput> driver = ComponentDriver.initialize(
                list(RichListConfig.defaults().withItems(items()).withRows(12)),
                new InitContext(new Viewport(12, 40), null));
        driver.send(new RichListMsg.Down());
        driver.send(new RichListMsg.BeginFilter());
        driver.send(new RichListMsg.FilterInput(new TextInput.Msg.Insert("a")));
        assertThat(driver.model().filter().query()).isEqualTo("a");
        assertThat(ids(RichList.visible(driver.model())))
                .as("fuzzy ranking orders the matches by where the query matched")
                .containsExactly("a", "c", "b", "f", "d");
        assertThat(driver.model().selectedId()).isEqualTo("b");
        assertThat(driver.screen(new ViewContext(new Viewport(12, 40))).lines()).isNotEmpty();
        assertThat(driver.children().nodes()).isEmpty();
    }
}
