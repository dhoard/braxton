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

import examples.parity.TabsDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.RichList;
import io.github.dhoard.braxton.widget.Tabs;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 16 acceptance: the tab bar's state, its painting, the composition it enables and the demo.
 *
 * <p>Every assertion is against public behavior: the model a message produces, the output it
 * reports, the rows a pure renderer paints, the focus regions a composed view declares, what a routed
 * key does, and what the demo's own scene reports on its status rows. The bar a test drives is the
 * shipped {@link Tabs}, the pages are the shipped {@link Editor}, {@link RichList} and
 * {@link Countdown}, and the composition a verifier runs is {@link TabsDemo}'s own scene.</p>
 */
@Timeout(20)
class ParityTabsTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int ROWS = 24;

    private static final int COLS = 80;

    private static final Duration TICKS = Duration.ofSeconds(30);

    private static final long MILLIS = 1_000_000L;

    private static final List<Tabs.Tab> THREE =
            List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta"), Tabs.Tab.of("c", "Gamma"));

    /**
     * One tab in the middle, one disabled, one at each end.
     */
    private static final List<Tabs.Tab> MIXED =
            List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta", false), Tabs.Tab.of("c", "Gamma"));

    private static final Tabs BAR = new Tabs(null);

    // ── the bar's own helpers ────────────────────────────────────────────────

    private static Update<Tabs.Model, Tabs.Msg, Tabs.Output> send(Tabs bar, Tabs.Model model, Tabs.Msg message) {
        return bar.update(model, message);
    }

    private static Update<Tabs.Model, Tabs.Msg, Tabs.Output> send(Tabs.Model model, Tabs.Msg message) {
        return send(BAR, model, message);
    }

    private static Tabs.Model apply(Tabs.Model model, Tabs.Msg message) {
        return send(model, message).model();
    }

    private static Tabs.Model model(String active, List<Tabs.Tab> tabs) {
        return new Tabs.Model(tabs, active, true);
    }

    /**
     * The composition's rows as painted text, each still padded to the width it was given.
     */
    private static List<String> rows(Tabs.Model model, Tabs.Config config, int width, StyledText content) {
        return List.of(Tabs.render(model, config, width, content).plainText().split("\n", -1));
    }

    private static List<String> rows(Tabs.Model model, Tabs.Config config, int width, String content) {
        return rows(model, config, width, StyledText.plain(content));
    }

    private static List<String> rows(Tabs.Model model, int width, String content) {
        return rows(model, null, width, content);
    }

    /**
     * A row padded to the width the composition was given, so an expected row states its width.
     */
    private static String padded(String row, int width) {
        return row + " ".repeat(Math.max(0, width - TextMeasure.columns(row)));
    }

    /**
     * The composed view's rows as a terminal would paint them at a fixed viewport.
     */
    private static List<String> lines(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols).lines();
    }

    private static List<String> lines(Element<?> view) {
        return lines(view, ROWS, COLS);
    }

    private static InputEvent.KeyInput key(String spec) {
        Key gesture = Key.parse(spec);
        return InputEvent.KeyInput.of(gesture, text(gesture));
    }

    /**
     * The characters a key press carries, as the decoder would report them.
     */
    private static String text(Key gesture) {
        String name = gesture.name();
        return name.length() == 1 ? name : "space".equals(name) ? " " : "";
    }

    private static Optional<Tabs.Msg> route(Element<Tabs.Msg> view, String spec) {
        return InteractionRouter.message(view, key(spec));
    }

    private static List<StyledText.Span> spans(StyledText text) {
        return text.spans();
    }

    /**
     * Every alias of a key map's actions, in declaration order.
     */
    private static List<String> aliases(KeyMap<?> keys) {
        List<String> names = new ArrayList<>();
        for (KeyMap.Group<?> group : keys.groups()) {
            for (KeyAction<?> action : group.actions()) {
                action.aliases().forEach(alias -> names.add(alias.name()));
            }
        }
        return names;
    }

    // ── the demo's own helpers ───────────────────────────────────────────────

    private static TabsDemo.Scene demo() {
        return new TabsDemo.Scene();
    }

    private static TabsDemo.Model initial(TabsDemo.Scene scene) {
        return scene.init(new InitContext(new Viewport(ROWS, COLS), null)).model();
    }

    private static List<String> painted(TabsDemo.Scene scene, TabsDemo.Model model, int rows, int cols) {
        return lines(scene.view(model, new ViewContext(new Viewport(rows, cols))), rows, cols);
    }

    private static List<String> painted(TabsDemo.Scene scene, TabsDemo.Model model) {
        return painted(scene, model, ROWS, COLS);
    }

    private static String screen(TabsDemo.Scene scene, TabsDemo.Model model) {
        return String.join("\n", painted(scene, model));
    }

    /**
     * What one key does to the demo, as the scene's own message.
     */
    private static Optional<TabsDemo.Msg> press(TabsDemo.Scene scene, TabsDemo.Model model, String spec) {
        return io.github.dhoard.braxton.testing.Routing.route(scene, model, CONTEXT, key(spec)).stream()
                .findFirst();
    }

    /**
     * One scene message and everything the runtime would deliver after it.
     */
    private static TabsDemo.Model deliver(
            TabsDemo.Scene scene, TabsDemo.Model model, TabsDemo.Msg message, long nowNanos) {
        ArrayDeque<TabsDemo.Msg> pending = new ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            Update<TabsDemo.Model, TabsDemo.Msg, Never> update = scene.update(model, pending.removeFirst());
            model = update.model();
            inspect(update.command(), pending, nowNanos);
        }
        return model;
    }

    private static TabsDemo.Model deliver(TabsDemo.Scene scene, TabsDemo.Model model, TabsDemo.Msg message) {
        return deliver(scene, model, message, 1_000L * MILLIS);
    }

    private static void inspect(Command<TabsDemo.Msg> command, ArrayDeque<TabsDemo.Msg> pending, long nowNanos) {
        switch (command) {
            case Command.None<TabsDemo.Msg> none -> {}
            case Command.Batch<TabsDemo.Msg> batch ->
                batch.commands().forEach(item -> inspect(item, pending, nowNanos));
            case Command.Scoped<TabsDemo.Msg> scoped -> inspect(scoped.command(), pending, nowNanos);
            case Command.MonotonicTime<TabsDemo.Msg> time ->
                pending.add(time.mapper().apply(nowNanos));
            case Command.Dispatch<TabsDemo.Msg> dispatch -> pending.add(dispatch.message());
            default -> {}
        }
    }

    /**
     * One routed key, applied with everything it dispatches.
     */
    private static TabsDemo.Model typed(TabsDemo.Scene scene, TabsDemo.Model model, String spec) {
        Optional<TabsDemo.Msg> message = press(scene, model, spec);
        assertThat(message).as("key %s reaches the scene", spec).isPresent();
        return deliver(scene, model, message.orElseThrow());
    }

    /**
     * Every character of a text typed into the focused page.
     */
    private static TabsDemo.Model typist(TabsDemo.Scene scene, TabsDemo.Model model, String text) {
        TabsDemo.Model state = model;
        for (int index = 0; index < text.length(); index++) {
            state = typed(scene, state, String.valueOf(text.charAt(index)));
        }
        return state;
    }

    private static TabsDemo.Msg show(String id) {
        return new TabsDemo.Msg.Show(id);
    }

    /**
     * The timer's reading as the runtime would deliver it at an absolute monotonic time.
     */
    private static TabsDemo.Msg tick(TabsDemo.Model model, long atMillis) {
        return new TabsDemo.Msg.Timer(new Countdown.Msg.Reading(model.timer().epoch(), atMillis * MILLIS));
    }

    private static String statusRow(TabsDemo.Scene scene, TabsDemo.Model model) {
        return painted(scene, model).get(0);
    }

    private static String pageRow(TabsDemo.Scene scene, TabsDemo.Model model) {
        return painted(scene, model).get(1);
    }

    private static String helpRow(TabsDemo.Scene scene, TabsDemo.Model model) {
        return painted(scene, model).get(ROWS - 2);
    }

    private static String sceneRow(TabsDemo.Scene scene, TabsDemo.Model model) {
        return painted(scene, model).get(ROWS - 1);
    }

    private static List<Subscription<?>> intervals(Subscription<?> subscriptions) {
        return Subscription.leaves(subscriptions).stream()
                .<Subscription<?>>map(subscription -> subscription)
                .filter(subscription -> subscription instanceof Subscription.Interval<?>)
                .toList();
    }

    // ══ R-01: stable identity, enablement and navigation ══════════════════════

    @Test
    void aBarStartsWithNoTabsUntilItsOwnerPublishesThem() {
        Tabs.Model empty = BAR.init(new InitContext(null, null)).model();
        assertThat(empty.tabs()).isEmpty();
        assertThat(empty.activeId()).isNull();
        assertThat(empty.hasActive()).isFalse();
        assertThat(empty.focused()).as("init leaves the bar ready for focus").isTrue();
        assertThat(Tabs.render(empty, null, 20, StyledText.plain("page")).plainText())
                .as("a bar with no tabs frames its content without a bar")
                .isEqualTo(
                        "\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E\n"
                                + "\u2502page              \u2502\n"
                                + "\u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256F");
    }

    @Test
    void theFirstEnabledTabIsShownWhenNothingWasChosen() {
        Tabs.Model model = Tabs.Model.of(MIXED);
        assertThat(model.activeId()).isEqualTo("a");
        assertThat(model.activeIndex()).isZero();
        assertThat(model.active().map(Tabs.Tab::label)).contains("Alpha");
        assertThat(model.enabledCount()).isEqualTo(2);
        assertThat(model.focused()).isTrue();
    }

    @Test
    void aBarWithNoEnabledTabHasNoActiveTab() {
        Tabs.Model model = Tabs.Model.of(List.of(Tabs.Tab.of("a", "Alpha", false)));
        assertThat(model.activeId()).isNull();
        assertThat(model.hasActive()).isFalse();
        assertThat(model.active()).isEmpty();
        assertThat(model.activeIndex()).isEqualTo(-1);
        assertThat(model.enabledCount()).isZero();
        assertThat(Tabs.Model.of(List.of()).hasActive()).isFalse();
    }

    @Test
    void anAbsentBlankOrDisabledActiveIdFallsToTheFirstEnabledTab() {
        assertThat(model("missing", THREE).activeId()).isEqualTo("a");
        assertThat(model("", MIXED).activeId()).isEqualTo("a");
        assertThat(model("b", MIXED).activeId())
                .as("a disabled tab cannot be shown")
                .isEqualTo("a");
        assertThat(model("c", MIXED).activeId()).isEqualTo("c");
    }

    @Test
    void nextAndPreviousSkipDisabledTabsInBothDirections() {
        assertThat(apply(model("a", MIXED), new Tabs.Msg.Next()).activeId())
                .as("the disabled middle tab is stepped over")
                .isEqualTo("c");
        assertThat(apply(model("c", MIXED), new Tabs.Msg.Previous()).activeId()).isEqualTo("a");
        assertThat(apply(model("c", THREE), new Tabs.Msg.Next()).activeId())
                .as("wrap is off by default, so the last tab is the end")
                .isEqualTo("c");
        assertThat(apply(model("a", THREE), new Tabs.Msg.Previous()).activeId()).isEqualTo("a");
    }

    @Test
    void wrapAroundMakesEitherEndLeadToTheOther() {
        Tabs wrapping = new Tabs(Tabs.Config.defaults().withWrap(true));
        List<Tabs.Tab> pair = List.of(Tabs.Tab.of("last", "Last"), Tabs.Tab.of("first", "First"));
        assertThat(send(wrapping, model("last", pair), new Tabs.Msg.Next())
                        .model()
                        .activeId())
                .isEqualTo("first");
        assertThat(send(wrapping, model("first", pair), new Tabs.Msg.Previous())
                        .model()
                        .activeId())
                .isEqualTo("last");
        assertThat(send(wrapping, model("c", MIXED), new Tabs.Msg.Next())
                        .model()
                        .activeId())
                .as("wrapping still steps over a disabled tab")
                .isEqualTo("a");
    }

    @Test
    void firstAndLastSkipDisabledEnds() {
        assertThat(apply(model("a", MIXED), new Tabs.Msg.Last()).activeId()).isEqualTo("c");
        List<Tabs.Tab> disabledEnds =
                List.of(Tabs.Tab.of("a", "Alpha", false), Tabs.Tab.of("b", "Beta"), Tabs.Tab.of("c", "Gamma", false));
        assertThat(apply(model("a", disabledEnds), new Tabs.Msg.Last()).activeId())
                .isEqualTo("b");
        assertThat(apply(model("c", disabledEnds), new Tabs.Msg.First()).activeId())
                .isEqualTo("b");
    }

    @Test
    void activateChoosesByStableIdAndRefusesWhatTheKeyboardCannotReach() {
        Tabs.Model model = model("a", MIXED);
        Update<Tabs.Model, Tabs.Msg, Tabs.Output> chosen = send(model, new Tabs.Msg.Activate("c"));
        assertThat(chosen.model().activeId()).isEqualTo("c");
        assertThat(chosen.outputs()).containsExactly(new Tabs.Output.Activated("c"));

        Update<Tabs.Model, Tabs.Msg, Tabs.Output> disabled = send(model, new Tabs.Msg.Activate("b"));
        assertThat(disabled.model()).as("a disabled id changes nothing").isSameAs(model);
        assertThat(disabled.outputs()).isEmpty();
        assertThat(send(model, new Tabs.Msg.Activate("missing")).model()).isSameAs(model);
        assertThat(send(model, new Tabs.Msg.Activate("a")).outputs())
                .as("activating the shown tab is not a transition")
                .isEmpty();
    }

    @Test
    void aChangedSelectionEmitsExactlyOneOutput() {
        Tabs.Model model = model("a", THREE);
        assertThat(send(model, new Tabs.Msg.Next()).outputs()).containsExactly(new Tabs.Output.Activated("b"));
        assertThat(send(model, new Tabs.Msg.Last()).outputs()).containsExactly(new Tabs.Output.Activated("c"));
        assertThat(send(send(model, new Tabs.Msg.Last()).model(), new Tabs.Msg.First())
                        .outputs())
                .containsExactly(new Tabs.Output.Activated("a"));
    }

    @Test
    void reorderingOrRenamingTabsKeepsTheShownPage() {
        Tabs.Model model = model("b", THREE);
        Tabs.Model reordered = apply(model, new Tabs.Msg.SetTabs(List.of(THREE.get(2), THREE.get(1), THREE.get(0))));
        assertThat(reordered.activeId())
                .as("the page is named by id, not by position")
                .isEqualTo("b");
        assertThat(reordered.activeIndex()).isEqualTo(1);
        assertThat(reordered.tabs()).extracting(Tabs.Tab::id).containsExactly("c", "b", "a");

        Tabs.Model renamed = apply(
                model,
                new Tabs.Msg.SetTabs(
                        List.of(Tabs.Tab.of("a", "Alpha!"), Tabs.Tab.of("b", "Beta!"), Tabs.Tab.of("c", "Gamma!"))));
        assertThat(renamed.activeId()).isEqualTo("b");
        assertThat(renamed.active().map(Tabs.Tab::label)).contains("Beta!");
        assertThat(send(model, new Tabs.Msg.SetTabs(THREE)).outputs())
                .as("the same list is not a transition")
                .isEmpty();
    }

    @Test
    void removingTheShownTabChoosesTheNearestEnabledSurvivor() {
        assertThat(apply(model("b", THREE), new Tabs.Msg.RemoveTab("b")).activeId())
                .as("the tab that now holds the removed position")
                .isEqualTo("c");
        assertThat(apply(model("c", THREE), new Tabs.Msg.RemoveTab("c")).activeId())
                .as("the closest enabled tab before it")
                .isEqualTo("b");
        assertThat(apply(model("c", MIXED), new Tabs.Msg.RemoveTab("c")).activeId())
                .as("a disabled neighbour is not a survivor")
                .isEqualTo("a");
        Tabs.Model model = model("b", THREE);
        assertThat(send(model, new Tabs.Msg.RemoveTab("missing")).model())
                .as("removing an id the bar does not hold changes nothing")
                .isSameAs(model);
    }

    @Test
    void removingTheLastEnabledTabLeavesNoActiveTabAndReportsNothing() {
        Tabs.Model model = model("a", List.of(Tabs.Tab.of("a", "Alpha")));
        Update<Tabs.Model, Tabs.Msg, Tabs.Output> removed = send(model, new Tabs.Msg.RemoveTab("a"));
        assertThat(removed.model().activeId()).isNull();
        assertThat(removed.model().hasActive()).isFalse();
        assertThat(removed.outputs()).as("there is no id to name").isEmpty();
    }

    @Test
    void aTabListWithADuplicateIdIsRejectedWhereItIsMade() {
        assertThatThrownBy(
                        () -> new Tabs.Model(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("a", "Again")), null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tab id: a");
        Tabs.Model model = model("a", THREE);
        assertThatThrownBy(() -> apply(
                        model, new Tabs.Msg.SetTabs(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("a", "Again")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tab id: a");
        assertThatThrownBy(() -> apply(model, new Tabs.Msg.InsertTab(1, Tabs.Tab.of("a", "Again"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tab id: a");
    }

    @Test
    void insertTabClampsItsIndexAndKeepsTheShownPage() {
        Tabs.Model model = model("b", THREE);
        assertThat(apply(model, new Tabs.Msg.InsertTab(-9, Tabs.Tab.of("first", "First")))
                        .tabs())
                .extracting(Tabs.Tab::id)
                .containsExactly("first", "a", "b", "c");
        assertThat(apply(model, new Tabs.Msg.InsertTab(99, Tabs.Tab.of("last", "Last")))
                        .tabs())
                .extracting(Tabs.Tab::id)
                .containsExactly("a", "b", "c", "last");
        assertThat(apply(model, new Tabs.Msg.InsertTab(0, Tabs.Tab.of("first", "First")))
                        .activeId())
                .isEqualTo("b");
    }

    @Test
    void aMessageThatChangesNothingKeepsTheIncomingInstance() {
        Tabs.Model first = model("a", MIXED);
        assertThat(send(first, new Tabs.Msg.First()).model()).isSameAs(first);
        assertThat(send(first, new Tabs.Msg.Previous()).model()).isSameAs(first);
        assertThat(send(first, new Tabs.Msg.Activate("a")).model()).isSameAs(first);
        assertThat(send(first, new Tabs.Msg.SetTabs(MIXED)).model()).isSameAs(first);
        Tabs.Model last = model("c", THREE);
        assertThat(send(last, new Tabs.Msg.Last()).model()).isSameAs(last);
        assertThat(send(last, new Tabs.Msg.Next()).model()).isSameAs(last);
        Tabs.Model none = new Tabs.Model(List.of(Tabs.Tab.of("a", "Alpha", false)), null, true);
        assertThat(send(none, new Tabs.Msg.Next()).model()).isSameAs(none);
        assertThat(send(none, new Tabs.Msg.Last()).model()).isSameAs(none);
    }

    @Test
    void everyReductionKeepsTheFocusTheApplicationOwns() {
        Tabs.Model unfocused = new Tabs.Model(MIXED, "a", false);
        for (Tabs.Msg message : List.of(
                new Tabs.Msg.Next(),
                new Tabs.Msg.Last(),
                new Tabs.Msg.First(),
                new Tabs.Msg.Activate("c"),
                new Tabs.Msg.RemoveTab("a"),
                new Tabs.Msg.SetTabs(THREE))) {
            assertThat(apply(unfocused, message).focused())
                    .as("%s must not take focus", message)
                    .isFalse();
        }
        assertThat(unfocused.withFocus(false)).isSameAs(unfocused);
        assertThat(unfocused.withFocus(true).focused()).isTrue();
        assertThat(apply(unfocused.withFocus(true), new Tabs.Msg.Next()).focused())
                .isTrue();
    }

    @Test
    void aTabNeedsANonBlankIdentityAndAOneRowLabel() {
        assertThat(Tabs.Tab.of("a", "one\ntwo\tthree").label())
                .as("a break or a tab stop would split the cell")
                .isEqualTo("one two three");
        assertThat(Tabs.Tab.of("a", "Alpha").withLabel("Beta").label()).isEqualTo("Beta");
        assertThat(Tabs.Tab.of("a", "Alpha").withEnabled(false).enabled()).isFalse();
        assertThatThrownBy(() -> Tabs.Tab.of(" ", "Alpha")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tabs.Tab.of("a", " \t "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank label");
    }

    // ══ R-02: the bar, the frame and the join ═════════════════════════════════

    @Test
    void theShownTabOpensIntoTheContentFrameWhereverItSits() {
        assertThat(rows(model("a", THREE), 40, "body"))
                .containsExactly(
                        padded(
                                "\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E",
                                40),
                        padded("\u2502 Alpha \u2502\u2502 Beta \u2502\u2502 Gamma \u2502", 40),
                        "\u2502       \u2514\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534"
                                + "\u2500".repeat(13) + "\u256E",
                        "\u2502body" + " ".repeat(34) + "\u2502",
                        "\u2570" + "\u2500".repeat(38) + "\u256F");
        assertThat(rows(model("b", THREE), 40, "body").get(2))
                .as("the middle tab's bottom edge is the opening above the content")
                .isEqualTo(
                        "\u251C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2518      \u2514\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534"
                                + "\u2500".repeat(13) + "\u256E");
        assertThat(rows(model("c", THREE), 40, "body").get(2))
                .as("the last tab's opening joins the frame's right edge")
                .isEqualTo(
                        "\u251C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2518       \u2514"
                                + "\u2500".repeat(13) + "\u256E");
        for (String active : List.of("a", "b", "c")) {
            assertThat(rows(model(active, THREE), 40, "body"))
                    .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isEqualTo(40));
        }
    }

    @Test
    void everyUnshownTabStaysAClosedCellAboveTheFrame() {
        List<String> painted = rows(model("b", THREE), 40, "body");
        assertThat(painted.get(0))
                .startsWith(
                        "\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u256E");
        assertThat(painted.get(2))
                .as("the closed cells meet the frame with one tee per boundary")
                .isEqualTo(
                        "\u251C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2518      \u2514\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534"
                                + "\u2500".repeat(13) + "\u256E");
        assertThat(painted.get(2)).doesNotContain("\u2518\u2518");
    }

    @Test
    void aNarrowBarWindowsAroundTheShownTabAndMarksWhatItLeftOut() {
        List<String> lastShown = rows(model("c", THREE), 24, "body");
        assertThat(lastShown.get(1))
                .as("the left marker says tabs were left out")
                .startsWith("\u2502\u2039\u2502");
        assertThat(lastShown.get(1)).contains("\u2502 Gamma \u2502");
        assertThat(lastShown.get(1)).doesNotContain("Alpha").doesNotContain("\u203A");

        List<String> firstShown = rows(model("a", THREE), 24, "body");
        assertThat(firstShown.get(1)).contains("\u2502 Alpha \u2502").contains("\u203A");
        assertThat(firstShown.get(1)).doesNotContain("\u2039").doesNotContain("Gamma");
    }

    @Test
    void theShownLabelIsVisibleAtEveryWidth() {
        for (int width = 4; width <= 60; width++) {
            String labelRow = rows(model("b", THREE), width, "body").get(1);
            assertThat(TextMeasure.columns(labelRow))
                    .as("width %d row [%s]", width, labelRow)
                    .isEqualTo(width);
            if (width < 26) {
                assertThat(labelRow)
                        .as("width %d windows rather than overflows", width)
                        .doesNotContain("Alpha");
            }
            if (width >= 14) {
                assertThat(labelRow)
                        .as("width %d keeps the shown label whole", width)
                        .contains("\u2502 Beta \u2502");
            } else if (width == Tabs.MIN_CELL) {
                assertThat(labelRow)
                        .as("width %d keeps the shown tab's cell", width)
                        .contains("\u2502  \u2502");
            } else {
                assertThat(labelRow)
                        .as("width %d paints at least the label's first column", width)
                        .contains("B");
            }
        }
    }

    @Test
    void aBarNarrowerThanOneCellPaintsOnlyTheFramesEdge() {
        for (int width = 2; width < Tabs.MIN_CELL; width++) {
            List<String> painted = rows(model("a", THREE), width, "body");
            assertThat(painted).as("width %d", width).hasSize(3);
            assertThat(painted.get(0))
                    .as("the bar's rows are gone at width %d", width)
                    .startsWith("\u256D");
        }
        assertThat(String.join("\n", rows(model("a", THREE), 3, "page")))
                .isEqualTo("\u256D\u2500\u256E\n\u2502p\u2502\n\u2570\u2500\u256F");
    }

    @Test
    void aLabelTooWideForItsCellsIsCutOnAGraphemeBoundary() {
        Tabs.Model wide = new Tabs.Model(
                List.of(Tabs.Tab.of("a", "\u65E5\u672C\u8A9E\u306E\u30BF\u30D6"), Tabs.Tab.of("b", "Beta")), "a", true);
        String cut = rows(wide, 12, "b").get(1);
        assertThat(cut)
                .as("a two-column glyph is kept whole before the ellipsis")
                .isEqualTo("\u2502 \u65E5\u672C\u2026 \u2502\u2502\u203A\u2502");
        assertThat(TextMeasure.columns(cut)).isEqualTo(12);

        Tabs.Model combining = new Tabs.Model(
                List.of(Tabs.Tab.of("a", "e\u0301e\u0301e\u0301e\u0301e\u0301e\u0301"), Tabs.Tab.of("b", "B")),
                "a",
                true);
        String marks = rows(combining, 12, "b").get(1);
        assertThat(marks)
                .as("a combining mark never starts a cell")
                .isEqualTo("\u2502 e\u0301e\u0301e\u0301e\u0301\u2026 \u2502\u2502\u203A\u2502");
        assertThat(TextMeasure.columns(marks)).isEqualTo(12);
    }

    @Test
    void everyPaintedRowIsExactlyTheWidthItWasGiven() {
        StyledText page = StyledText.of("one", Style.NONE.withBold()).append("\ntwo\nthree");
        for (int width = 1; width <= 60; width++) {
            List<String> painted = List.of(Tabs.render(model("b", THREE), null, width, page)
                    .plainText()
                    .split("\n", -1));
            for (String line : painted) {
                assertThat(TextMeasure.columns(line))
                        .as("width %d line [%s]", width, line)
                        .isEqualTo(width);
            }
        }
        assertThat(Tabs.render(model("b", THREE), null, 0, page).isEmpty()).isTrue();
        assertThat(Tabs.render(model("b", THREE), null, -4, page).isEmpty()).isTrue();
        assertThat(List.of(Tabs.render(model("b", THREE), null, 1, StyledText.plain("one\ntwo"))
                        .plainText()
                        .split("\n", -1)))
                .as("one column holds only the content's first column")
                .containsExactly("o", "t");
    }

    @Test
    void theBarCarriesTheStyleEachCellStateDeclares() {
        Style active = Style.NONE.withBold();
        Style frame = Style.NONE.withForeground(Colors.CYAN_400);
        Tabs.Appearance appearance = Tabs.Appearance.rounded()
                .withTabStyles(active, Style.NONE.withUnderline(), Style.NONE.withItalic())
                .withFrameStyle(frame);
        StyledText painted = Tabs.render(
                model("a", MIXED), Tabs.Config.defaults().withAppearance(appearance), 40, StyledText.plain("body"));

        assertThat(spans(painted)).as("the shown cell is one bold shape").anySatisfy(span -> {
            assertThat(span.text()).contains(" Alpha ");
            assertThat(span.style().bold()).isTrue();
        });
        assertThat(spans(painted)).as("an unshown cell is underlined").anySatisfy(span -> {
            assertThat(span.text()).contains(" Gamma ");
            assertThat(span.style().underline()).isTrue();
        });
        assertThat(spans(painted)).as("a disabled cell is italic").anySatisfy(span -> {
            assertThat(span.text()).contains(" Beta ");
            assertThat(span.style().italic()).isTrue();
        });
        assertThat(spans(painted))
                .as("the frame below the cells carries the frame style")
                .anySatisfy(span -> {
                    assertThat(span.text()).contains("\u2570");
                    assertThat(span.style().foreground()).isEqualTo(Colors.CYAN_400);
                });
    }

    @Test
    void aBarWithNoEnabledTabNeverOpensTheFrame() {
        Tabs.Model model = new Tabs.Model(List.of(Tabs.Tab.of("a", "Alpha", false)), null, true);
        List<String> painted = rows(model, 20, "body");
        assertThat(painted.get(2))
                .as("the only cell is closed above the frame")
                .isEqualTo("\u251C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534" + "\u2500".repeat(10) + "\u256E");
    }

    @Test
    void aDoubleFrameJoinsWithDoubleJunctionsAndACustomOneKeepsTheSingleOnes() {
        Tabs.Config doubled =
                Tabs.Config.defaults().withAppearance(Tabs.Appearance.rounded().withBorder(Border.DOUBLE));
        List<String> painted = rows(model("b", THREE), doubled, 40, "body");
        assertThat(painted.get(0)).startsWith("\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557");
        assertThat(painted.get(2)).startsWith("\u2560\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2569\u255D");
        assertThat(painted.get(2))
                .as("the next cell opens with the double junctions")
                .contains("\u255A\u2569");
        assertThat(painted.get(4)).endsWith("\u255D");

        Border custom = new Border('+', '+', '+', '+', '-', '|');
        Tabs.Config plain =
                Tabs.Config.defaults().withAppearance(Tabs.Appearance.rounded().withBorder(custom));
        List<String> dotted = rows(model("b", THREE), plain, 40, "body");
        assertThat(dotted.get(0)).as("a custom border keeps its own corners").startsWith("+-------+");
        assertThat(dotted.get(2))
                .as("and the single junctions this widget chooses for every frame")
                .startsWith("\u251C-------\u2534\u2518");
        assertThat(dotted.get(4))
                .as("the frame's own bottom edge is the custom one")
                .isEqualTo("+" + "-".repeat(38) + "+");
    }

    @Test
    void anAppearanceThatDrawsNothingIsRejectedRatherThanPaintedAsBlankCells() {
        assertThatThrownBy(() -> Tabs.Appearance.rounded().withBorder(Border.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("border must be visible");
        assertThat(Tabs.Appearance.rounded().border()).isEqualTo(Border.ROUNDED);
        assertThat(Tabs.Config.defaults().wrap()).isFalse();
        Tabs.Appearance nulls = new Tabs.Appearance(null, null, null, null, null);
        assertThat(nulls.activeTab()).isEqualTo(Style.NONE);
        assertThat(nulls.border()).isEqualTo(Border.ROUNDED);
        assertThat(Tabs.Appearance.rounded().withFrameStyle(null).frame()).isEqualTo(Style.NONE);
    }

    @Test
    void renderingIsAPureFunctionOfItsInputs() {
        Tabs.Model model = model("b", THREE);
        StyledText page = StyledText.of("body", Style.NONE.withBold());
        assertThat(Tabs.render(model, null, 30, page)).isEqualTo(Tabs.render(model, null, 30, page));
        assertThat(model.tabs()).hasSize(3);
        assertThat(model.activeId()).isEqualTo("b");
        assertThat(page.plainText()).isEqualTo("body");
        assertThat(BAR.element(model, Elements.text(page), message -> message).children())
                .isNotEmpty();
        assertThat(BAR.view(model, CONTEXT)).isNotNull();
        assertThat(BAR.element(model, Elements.text(page), message -> message).children())
                .hasSize(2);
    }

    @Test
    void theContentsSpansAndLinksSurviveTheFrameAndAreCutToItsInnerWidth() {
        StyledText content = StyledText.of("hot", Style.NONE.withBold().withForeground(Colors.ERROR_400))
                .append(StyledText.of("ter", Style.NONE, "https://example.test/x"));
        List<String> painted = rows(model("b", THREE), 16, content.plainText());
        assertThat(painted.get(3)).isEqualTo("\u2502hotter        \u2502");
        StyledText rendered = Tabs.render(model("b", THREE), null, 16, content);
        assertThat(spans(rendered)).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("hot");
            assertThat(span.style().foreground()).isEqualTo(Colors.ERROR_400);
        });
        assertThat(spans(rendered)).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("ter");
            assertThat(span.link()).isEqualTo("https://example.test/x");
        });

        StyledText longLine = StyledText.of("abcdefghij", Style.NONE.withBold());
        assertThat(rows(model("b", THREE), 10, longLine.plainText()).get(3)).isEqualTo("\u2502abcdefgh\u2502");
        assertThat(spans(Tabs.render(model("b", THREE), null, 10, longLine))).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("abcdefgh");
            assertThat(span.style().bold()).isTrue();
        });
    }

    @Test
    void theStandaloneBarPaintsItsCellsAndItsOwnRule() {
        List<String> painted = lines(BAR.view(model("b", THREE), new ViewContext(new Viewport(3, 20))), 3, 20);
        assertThat(painted.get(0))
                .isEqualTo(
                        "\u256D\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E");
        assertThat(painted.get(1)).isEqualTo("\u2502\u2039\u2502\u2502 Beta \u2502\u2502 Gamma \u2502");
        assertThat(painted.get(2))
                .as("every cell is closed and the last one closes the bar")
                .isEqualTo(
                        "\u2570\u2500\u2534\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256F");
        assertThat(lines(
                        BAR.view(BAR.init(new InitContext(null, null)).model(), new ViewContext(new Viewport(3, 20))),
                        3,
                        20))
                .as("a bar with no tabs paints nothing")
                .containsExactly("", "", "");
    }

    @Test
    void theBarRoutesItsOwnGesturesOnlyWhileItHoldsFocus() {
        Tabs.Model unfocused = new Tabs.Model(THREE, "a", false);
        assertThat(route(BAR.view(unfocused, CONTEXT), "right")).isEmpty();
        assertThat(route(BAR.view(unfocused, CONTEXT), "home")).isEmpty();

        Tabs.Model focused = unfocused.withFocus(true);
        assertThat(route(BAR.view(focused, CONTEXT), "right")).contains(new Tabs.Msg.Next());
        assertThat(route(BAR.view(focused, CONTEXT), "l")).contains(new Tabs.Msg.Next());
        assertThat(route(BAR.view(focused, CONTEXT), "left")).contains(new Tabs.Msg.Previous());
        assertThat(route(BAR.view(focused, CONTEXT), "home")).contains(new Tabs.Msg.First());
        assertThat(route(BAR.view(focused, CONTEXT), "end")).contains(new Tabs.Msg.Last());
        assertThat(route(BAR.view(focused, CONTEXT), "x")).isEmpty();
    }

    @Test
    void theBarLeavesTabCompletionToTheApplication() {
        Tabs.Model focused = new Tabs.Model(THREE, "a", true);
        assertThat(BAR.keys().groups())
                .containsExactlyElementsOf(Tabs.defaultKeys().groups());
        assertThat(aliases(BAR.keys()))
                .as("tab and shift+tab belong to the application, not the bar")
                .doesNotContain("tab", "shift+tab");
        assertThat(route(BAR.view(focused, CONTEXT), "tab")).isEmpty();
        assertThat(route(BAR.view(focused, CONTEXT), "shift+tab")).isEmpty();

        String help = Help.render(BAR.keys(), false, 80).plainText();
        assertThat(help).contains("previous tab", "next tab", "first tab", "last tab");
        assertThat(aliases(Tabs.defaultKeys())).contains("left", "right", "home", "end");
    }

    @Test
    void aComposedViewDeclaresOneFocusTargetForTheBarAndOneForThePage() {
        PagesScene scene = new PagesScene();
        Pages model =
                scene.init(new InitContext(new Viewport(ROWS, COLS), null)).model();
        var screen = io.github.dhoard.braxton.testing.ElementScreen.render(scene.view(model, CONTEXT), ROWS, COLS);
        // Two editable targets and no more: the bar is one, the mounted page's field is the other.
        // They are separate leaves, so a pointer or a tab reaches one of them at a time.
        var focusables = io.github.dhoard.braxton.ui.InteractionRouter.focusables(screen.placement());
        assertThat(focusables)
                .as("the bar and the page are the two focus targets")
                .hasSize(2);
        assertThat(focusables.get(0)).isNotSameAs(focusables.get(1));
    }

    @Test
    void switchingPagesRetainsEachMountedDraftAndItsScopedIdentity() {
        PagesScene scene = new PagesScene();
        Pages model =
                scene.init(new InitContext(new Viewport(ROWS, COLS), null)).model();
        assertThat(model.notes().text()).isEqualTo(PagesScene.NOTES_DRAFT);

        model = scene.typed(model, "abc");
        assertThat(model.notes().text()).isEqualTo(PagesScene.NOTES_DRAFT + "abc");

        model = scene.deliver(model, new PageMsg.Show("todo"));
        assertThat(model.bar().activeId()).isEqualTo("todo");
        model = scene.typed(model, "xy");
        assertThat(model.todo().text())
                .as("the second page owns its own draft")
                .isEqualTo(PagesScene.TODO_DRAFT + "xy");
        assertThat(model.notes().text()).as("the first draft is untouched").isEqualTo(PagesScene.NOTES_DRAFT + "abc");

        model = scene.deliver(model, new PageMsg.Show("notes"));
        assertThat(model.notes().text()).isEqualTo(PagesScene.NOTES_DRAFT + "abc");
        assertThat(model.todo().text()).isEqualTo(PagesScene.TODO_DRAFT + "xy");
        assertThat(model.notes().focused()).isTrue();
        assertThat(model.todo().focused())
                .as("a hidden page does not hold the keyboard")
                .isFalse();
        // Identity is structural: the visible page's field is one leaf, and the hidden page's field
        // is not in the tree at all.
        var screen = io.github.dhoard.braxton.testing.ElementScreen.render(scene.view(model, CONTEXT), ROWS, COLS);
        assertThat(io.github.dhoard.braxton.ui.InteractionRouter.focusables(screen.placement()))
                .as("the bar and the visible page take the keyboard, and the hidden page does not")
                .hasSize(2);
    }

    @Test
    void aHiddenPageKeepsItsTicksUntilTheParentUnmountsIt() {
        PagesScene scene = new PagesScene();
        Pages model =
                scene.init(new InitContext(new Viewport(ROWS, COLS), null)).model();
        model = scene.deliver(model, new PageMsg.Show("timer"));
        model = scene.deliver(model, new PageMsg.Clock(new Countdown.Msg.Start()));
        assertThat(model.clock().running()).isTrue();

        model = scene.deliver(model, new PageMsg.Show("notes"));
        assertThat(model.bar().activeId()).isEqualTo("notes");
        assertThat(intervals(scene.subscriptions(model)))
                .as("a mounted page's interval runs while another page is painted")
                .hasSize(1);

        long epoch = model.clock().epoch();
        model = scene.deliver(model, new PageMsg.Clock(new Countdown.Msg.Reading(epoch, 11_000L * MILLIS)));
        assertThat(model.clock().remaining())
                .as("a hidden page still reduces its readings")
                .isEqualTo(TICKS.minusSeconds(10));

        model = scene.deliver(model, new PageMsg.HideClock());
        assertThat(scene.children(model).nodes())
                .extracting(ComponentTree.Node::id)
                .containsExactly(ComponentId.of("bar"), ComponentId.of("notes"), ComponentId.of("todo"));
        assertThat(intervals(scene.subscriptions(model)))
                .as("an unmounted page's interval is canceled")
                .isEmpty();

        model = scene.deliver(model, new PageMsg.RestoreClock());
        assertThat(model.clock().running())
                .as("a remount starts from the page's own init")
                .isFalse();
        assertThat(model.clock().remaining()).isEqualTo(TICKS);
    }

    @Test
    void aModalLayerTakesTheKeyboardAndHandsItBackWhenItCloses() {
        PagesScene scene = new PagesScene();
        Pages model = scene.deliver(
                scene.init(new InitContext(new Viewport(ROWS, COLS), null)).model(), new PageMsg.FocusBar());
        assertThat(scene.route(model, "right")).contains(new PageMsg.Bar(new Tabs.Msg.Next()));

        model = scene.deliver(model, new PageMsg.OpenModal());
        assertThat(scene.route(model, "right"))
                .as("the modal layer is the whole route while it is open")
                .contains(new PageMsg.CloseModal());
        assertThat(scene.view(model, CONTEXT).children()).hasSize(2);

        model = scene.deliver(model, new PageMsg.CloseModal());
        assertThat(scene.route(model, "right"))
                .as("the bar still had focus, so it takes the gesture again")
                .contains(new PageMsg.Bar(new Tabs.Msg.Next()));
    }

    // ══ R-04: the demo ═══════════════════════════════════════════════════════

    @Test
    void theDemoStartsOnItsNotesPageWithEveryPageMounted() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        assertThat(model.activePage()).isEqualTo(TabsDemo.NOTES_PAGE);
        assertThat(model.pane()).isEqualTo(TabsDemo.Pane.PAGE);
        assertThat(model.notes().text()).isEqualTo(TabsDemo.DRAFT);
        assertThat(model.notes().focused()).isTrue();
        assertThat(model.bar().focused()).isFalse();
        assertThat(model.bar().tabs()).containsExactlyElementsOf(TabsDemo.TABS);
        assertThat(model.bar().enabledCount()).as("the archive tab is disabled").isEqualTo(3);
        assertThat(scene.children(model).nodes())
                .extracting(ComponentTree.Node::id)
                .containsExactly(
                        ComponentId.of("bar"),
                        ComponentId.of(TabsDemo.NOTES_PAGE),
                        ComponentId.of(TabsDemo.INBOX_PAGE),
                        ComponentId.of(TabsDemo.TIMER_PAGE));

        List<String> painted = painted(scene, model);
        assertThat(painted.get(0)).isEqualTo("tab=notes focus=page pages=3 switched=0 last=none");
        assertThat(painted.get(1))
                .isEqualTo("notes=" + TabsDemo.DRAFT.length() + " inbox=item-01 timer=02:00 run=off help=compact");
        assertThat(painted.get(2)).as("the bar joins the frame").endsWith("\u256E");
        assertThat(painted.get(3))
                .contains("\u2502 Notes \u2502\u2502 Inbox \u2502\u2502 Timer \u2502\u2502 Archive \u2502");
        assertThat(screen(scene, model))
                .as("the draft is painted inside the frame")
                .contains("A tab bar is a claim about identity");
    }

    @Test
    void typingReachesTheShownPageAndLeavesTheOtherPagesAlone() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        model = typist(scene, model, "abc");
        assertThat(model.notes().text()).isEqualTo(TabsDemo.DRAFT + "abc");
        assertThat(pageRow(scene, model)).contains("notes=" + (TabsDemo.DRAFT.length() + 3), "inbox=item-01");
        assertThat(screen(scene, model)).contains("survive the trip.abc");

        // The list page answers its own gestures; the editor's draft does not move.
        model = deliver(scene, model, show(TabsDemo.INBOX_PAGE));
        model = typed(scene, model, "down");
        assertThat(model.inbox().selectedId()).isEqualTo("item-02");
        assertThat(model.notes().text()).isEqualTo(TabsDemo.DRAFT + "abc");

        // The timer page answers space, which is a typed space on the editor page.
        model = deliver(scene, model, show(TabsDemo.TIMER_PAGE));
        model = typed(scene, model, "space");
        assertThat(model.timer().running()).isTrue();
        assertThat(model.last()).isEqualTo("timer-toggle");
        model = deliver(scene, model, show(TabsDemo.NOTES_PAGE));
        model = typed(scene, model, "space");
        assertThat(model.notes().text()).as("space marks the editor's text").endsWith(" ");
        assertThat(model.timer().running()).as("and does not touch the timer").isTrue();
    }

    @Test
    void switchingPagesKeepsEveryDraftAndTheRunningTimer() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        model = typist(scene, model, "one");
        model = deliver(scene, model, show(TabsDemo.INBOX_PAGE));
        model = typed(scene, model, "down");
        model = typed(scene, model, "down");
        model = deliver(scene, model, show(TabsDemo.TIMER_PAGE));
        model = typed(scene, model, "space");
        model = deliver(scene, model, tick(model, 1_000L + 20_000L));
        assertThat(pageRow(scene, model)).contains("timer=01:40");

        model = deliver(scene, model, show(TabsDemo.NOTES_PAGE));
        assertThat(model.notes().text()).isEqualTo(TabsDemo.DRAFT + "one");
        assertThat(pageRow(scene, model))
                .contains("notes=" + (TabsDemo.DRAFT.length() + 3), "inbox=item-03", "timer=01:40", "run=on");
        assertThat(statusRow(scene, model)).contains("tab=notes", "switched=3");

        model = deliver(scene, model, tick(model, 1_000L + 40_000L));
        assertThat(pageRow(scene, model)).as("a hidden page still ticks").contains("timer=01:20");
        assertThat(screen(scene, model)).contains("survive the trip.one");
    }

    @Test
    void aDirectPageActivationMovesTheKeyboardToThePageItShows() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        model = typed(scene, model, "tab");
        assertThat(model.barFocused()).as("tab moves the keyboard to the bar").isTrue();
        assertThat(model.bar().focused()).isTrue();
        assertThat(model.notes().focused()).isFalse();

        model = deliver(scene, model, show(TabsDemo.INBOX_PAGE));
        assertThat(model.activePage()).isEqualTo(TabsDemo.INBOX_PAGE);
        assertThat(model.barFocused())
                .as("the keyboard stays where the user put it")
                .isTrue();

        model = typed(scene, model, "tab");
        assertThat(model.pageFocused()).isTrue();
        assertThat(model.inbox().focused())
                .as("the page that just became visible holds it")
                .isTrue();
        assertThat(model.notes().focused()).isFalse();
        assertThat(statusRow(scene, model)).contains("tab=inbox", "focus=page");
    }

    @Test
    void theBarNavigatesTheDemoAndStopsAtTheDisabledTab() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        model = typed(scene, model, "tab");
        model = typed(scene, model, "right");
        assertThat(model.activePage()).isEqualTo(TabsDemo.INBOX_PAGE);
        model = typed(scene, model, "right");
        assertThat(model.activePage()).isEqualTo(TabsDemo.TIMER_PAGE);
        model = typed(scene, model, "right");
        assertThat(model.activePage()).as("the disabled tab is not reachable").isEqualTo(TabsDemo.TIMER_PAGE);
        assertThat(model.last()).as("a refused step is not a transition").isEqualTo("tab-timer");
        model = typed(scene, model, "end");
        assertThat(model.activePage()).isEqualTo(TabsDemo.TIMER_PAGE);
        model = typed(scene, model, "home");
        assertThat(model.activePage()).isEqualTo(TabsDemo.NOTES_PAGE);
        assertThat(statusRow(scene, model)).contains("switched=3");
        assertThat(statusRow(scene, model)).doesNotContain("archive");
    }

    @Test
    void theDisabledPageCannotBeShownByAnyRoute() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        Update<TabsDemo.Model, TabsDemo.Msg, Never> refused = scene.update(model, show(TabsDemo.ARCHIVE_PAGE));
        assertThat(refused.model()).isSameAs(model);
        assertThat(model.bar().tabs()).extracting(Tabs.Tab::id).contains(TabsDemo.ARCHIVE_PAGE);
        assertThat(model.bar().tabs().get(3).enabled()).isFalse();
        assertThat(painted(scene, model).get(3))
                .as("the disabled tab is still painted")
                .contains("\u2502 Archive \u2502");
    }

    @Test
    void helpFollowsTheKeyboardAndThePageItIsIn() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        assertThat(helpRow(scene, model)).contains("enter submit", "alt+c copy", "ctrl+v paste");
        assertThat(sceneRow(scene, model)).contains("focus bar/page", "alt+1 notes");
        assertThat(TabsDemo.sceneHelp(model, 200).plainText()).contains("alt+2 inbox", "alt+3 timer");

        model = deliver(scene, model, show(TabsDemo.INBOX_PAGE));
        assertThat(helpRow(scene, model))
                .as("the list page's own actions")
                .contains("up", "down", "enter choose", "esc cancel")
                .doesNotContain("submit");

        model = deliver(scene, model, show(TabsDemo.TIMER_PAGE));
        assertThat(helpRow(scene, model))
                .contains("space start/pause", "r reset")
                .doesNotContain("enter choose");

        model = typed(scene, model, "tab");
        assertThat(helpRow(scene, model))
                .as("the bar's actions while it holds the keyboard")
                .contains("previous tab", "next tab", "first tab", "last tab")
                .doesNotContain("start/pause");
        assertThat(helpRow(scene, model))
                .isEqualTo(TabsDemo.regionHelp(model, COLS).plainText());
    }

    @Test
    void fullHelpListsEveryGroupAndIsReachedWhereThePageDoesNotConsumeIt() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        model = typed(scene, model, "?");
        assertThat(model.notes().text())
                .as("? is a typed character in the editor")
                .endsWith("?");
        assertThat(model.expandedHelp()).isFalse();

        model = typed(scene, model, "tab");
        model = typed(scene, model, "?");
        assertThat(model.expandedHelp()).isTrue();
        assertThat(pageRow(scene, model)).contains("help=full");
        assertThat(screen(scene, model))
                .as("full help lists every group, one row each")
                .contains("Focus", "Pages", "Scene", "previous tab");
        assertThat(painted(scene, model)).hasSize(ROWS);
        model = typed(scene, model, "?");
        assertThat(model.expandedHelp()).isFalse();
        assertThat(sceneRow(scene, model)).contains("focus bar/page");
    }

    @Test
    void aNarrowTerminalWindowsTheBarAndKeepsTheShownPageVisible() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = deliver(scene, initial(scene), show(TabsDemo.TIMER_PAGE));
        assertThat(painted(scene, model).get(3))
                .contains("\u2502 Notes \u2502", "\u2502 Inbox \u2502", "\u2502 Timer \u2502", "\u2502 Archive \u2502");

        List<String> narrow = painted(scene, model, ROWS, 30);
        assertThat(narrow.get(3)).contains("\u2502\u2039\u2502").contains("\u2502 Timer \u2502");
        assertThat(narrow.get(3)).doesNotContain("Notes");
        assertThat(narrow)
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(30));
        assertThat(narrow.get(5)).as("the page below the bar still paints").contains("02:00");
    }

    @Test
    void resizingKeepsEveryPageAndItsDraft() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = typist(scene, initial(scene), "abc");
        model = deliver(scene, model, show(TabsDemo.INBOX_PAGE));
        model = typed(scene, model, "down");
        model = deliver(scene, model, show(TabsDemo.NOTES_PAGE));

        List<String> wide = painted(scene, model, ROWS, 80);
        List<String> narrow = painted(scene, model, 16, 40);
        assertThat(wide.get(5)).contains("A tab bar is a claim about identity");
        assertThat(narrow.get(5))
                .as("the editor re-wraps at the narrower frame")
                .contains("A tab bar is a claim");
        assertThat(narrow)
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(40));
        assertThat(pageRow(scene, model))
                .isEqualTo(
                        "notes=" + (TabsDemo.DRAFT.length() + 3) + " inbox=item-02 timer=02:00 run=off help=compact");
        assertThat(model.activePage()).isEqualTo(TabsDemo.NOTES_PAGE);
    }

    @Test
    void everyPaintedRowOfTheDemoFitsTheViewport() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        for (int cols : List.of(24, 32, 40, 60, 80, 120)) {
            List<String> painted = painted(scene, model, ROWS, cols);
            assertThat(painted).hasSize(ROWS);
            assertThat(painted)
                    .allSatisfy(line -> assertThat(TextMeasure.columns(line))
                            .as("width %d line [%s]", cols, line)
                            .isLessThanOrEqualTo(cols));
        }
        List<String> tiny = painted(scene, model, 12, 12);
        assertThat(tiny).hasSize(12);
        assertThat(tiny)
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(12));
    }

    @Test
    void theDemoQuitsOnlyFromItsOwnGesture() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        Update<TabsDemo.Model, TabsDemo.Msg, Never> quit = scene.update(model, new TabsDemo.Msg.Quit());
        assertThat(quit.command()).isInstanceOf(Command.Quit.class);
        assertThat(press(scene, model, "ctrl+c")).contains(new TabsDemo.Msg.Quit());
        assertThat(press(scene, model, "q"))
                .as("q is a typed character, not a quit")
                .map(message -> message.getClass().getSimpleName())
                .contains("Notes");
    }

    // ══ R-05: the preserved contracts ═════════════════════════════════════════

    @Test
    void theDemoExposesNoMutableState() {
        assertThat(TabsDemo.TABS).hasSize(4);
        assertThatThrownBy(() -> TabsDemo.TABS.add(Tabs.Tab.of("x", "X")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(TabsDemo.ITEMS).isNotEmpty();
        assertThatThrownBy(() -> TabsDemo.ITEMS.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(TabsDemo.barConfig()).isEqualTo(Tabs.Config.defaults());
        assertThat(TabsDemo.TIMER_DURATION).isEqualTo(Duration.ofMinutes(2));
        assertThat(TabsDemo.RESERVED_ROWS)
                .isEqualTo(TabsDemo.STATUS_ROWS + TabsDemo.BAR_ROWS + TabsDemo.FRAME_ROWS + TabsDemo.HELP_ROWS);
    }

    @Test
    void theDemoSubscriptionsCoverEveryMountedPage() {
        TabsDemo.Scene scene = demo();
        TabsDemo.Model model = initial(scene);
        assertThat(Subscription.leaves(scene.subscriptions(model)))
                .as("the list's size source is declared while another page is painted")
                .hasSize(1);
        model = deliver(scene, model, show(TabsDemo.TIMER_PAGE));
        model = typed(scene, model, "space");
        assertThat(Subscription.leaves(scene.subscriptions(model)))
                .as("the timer's interval joins it while the run is live")
                .hasSize(2);
        assertThat(intervals(scene.subscriptions(model))).hasSize(1);
    }

    // ── a test composition: a bar, two editor pages, a timer and a modal ─────

    /**
     * Pages for the composition tests.
     *
     * @param bar the bar's own state
     * @param notes the first editor page
     * @param todo the second editor page
     * @param clock the countdown page
     * @param keepTodo whether the second editor page is mounted
     * @param keepClock whether the countdown page is mounted
     * @param modal whether a modal layer covers the composition
     */
    record Pages(
            Tabs.Model bar,
            Editor.Model notes,
            Editor.Model todo,
            Countdown.Model clock,
            boolean keepTodo,
            boolean keepClock,
            boolean modal) {

        Pages {
            bar = bar == null ? Tabs.Model.of(PAGES) : bar;
        }
    }

    /**
     * Every message the test composition handles.
     */
    sealed interface PageMsg {
        record Bar(Tabs.Msg value) implements PageMsg {}

        record BarOut(Tabs.Output value) implements PageMsg {}

        record Notes(Editor.Msg value) implements PageMsg {}

        record NotesOut(Editor.Output value) implements PageMsg {}

        record Todo(Editor.Msg value) implements PageMsg {}

        record TodoOut(Editor.Output value) implements PageMsg {}

        record Clock(Countdown.Msg value) implements PageMsg {}

        record ClockOut(Countdown.Output value) implements PageMsg {}

        record Show(String id) implements PageMsg {}

        record HideClock() implements PageMsg {}

        record RestoreClock() implements PageMsg {}

        record FocusBar() implements PageMsg {}

        record OpenModal() implements PageMsg {}

        record CloseModal() implements PageMsg {}
    }

    /**
     * The composition's tabs: two editor pages and a timer.
     */
    private static final List<Tabs.Tab> PAGES =
            List.of(Tabs.Tab.of("notes", "Notes"), Tabs.Tab.of("todo", "Todo"), Tabs.Tab.of("timer", "Timer"));

    /**
     * A bar over two editor pages and a timer, with an explicit mount policy and a modal layer.
     */
    static final class PagesScene implements Component<Pages, PageMsg, Never> {

        static final String NOTES_DRAFT = "notes: ";

        static final String TODO_DRAFT = "todo: ";

        static final ViewContext VIEW = new ViewContext(new Viewport(ROWS, COLS));

        private final Tabs bar = new Tabs(null);
        private final Editor editor = Editor.textarea(EditorConfig.defaults());
        private final Countdown countdown = new Countdown(Countdown.Config.of(TICKS));
        private final Mount<Pages, PageMsg, Never, Tabs.Model, Tabs.Msg, Tabs.Output> barMount = Mount.of(
                ComponentId.of("bar"),
                bar,
                ModelLens.of(
                        Pages::bar,
                        (model, child) -> new Pages(
                                child,
                                model.notes(),
                                model.todo(),
                                model.clock(),
                                model.keepTodo(),
                                model.keepClock(),
                                model.modal())),
                PageMsg.Bar::new,
                OutputRoute.toMessage(PageMsg.BarOut::new));
        private final Mount<Pages, PageMsg, Never, Editor.Model, Editor.Msg, Editor.Output> notesMount = Mount.of(
                ComponentId.of("notes"),
                editor,
                ModelLens.of(
                        Pages::notes,
                        (model, child) -> new Pages(
                                model.bar(),
                                child,
                                model.todo(),
                                model.clock(),
                                model.keepTodo(),
                                model.keepClock(),
                                model.modal())),
                PageMsg.Notes::new,
                OutputRoute.toMessage(PageMsg.NotesOut::new));
        private final Mount<Pages, PageMsg, Never, Editor.Model, Editor.Msg, Editor.Output> todoMount = Mount.of(
                ComponentId.of("todo"),
                editor,
                ModelLens.of(
                        Pages::todo,
                        (model, child) -> new Pages(
                                model.bar(),
                                model.notes(),
                                child,
                                model.clock(),
                                model.keepTodo(),
                                model.keepClock(),
                                model.modal())),
                PageMsg.Todo::new,
                OutputRoute.toMessage(PageMsg.TodoOut::new));
        private final Mount<Pages, PageMsg, Never, Countdown.Model, Countdown.Msg, Countdown.Output> clockMount =
                Mount.of(
                        ComponentId.of("timer"),
                        countdown,
                        ModelLens.of(
                                Pages::clock,
                                (model, child) -> new Pages(
                                        model.bar(),
                                        model.notes(),
                                        model.todo(),
                                        child,
                                        model.keepTodo(),
                                        model.keepClock(),
                                        model.modal())),
                        PageMsg.Clock::new,
                        OutputRoute.toMessage(PageMsg.ClockOut::new));

        @Override
        public Update<Pages, PageMsg, Never> init(InitContext context) {
            Tabs.Model barState = barMount.initialize(context).model().withTabs(PAGES);
            return Update.next(focused(new Pages(
                    barState,
                    notesMount.initialize(context).model().withText(NOTES_DRAFT),
                    todoMount.initialize(context).model().withText(TODO_DRAFT),
                    clockMount.initialize(context).model(),
                    true,
                    true,
                    false)));
        }

        @Override
        public Update<Pages, PageMsg, Never> update(Pages model, PageMsg message) {
            return switch (message) {
                case PageMsg.Bar value -> barMount.update(model, value.value());
                case PageMsg.BarOut value -> Update.next(model);
                case PageMsg.Notes value -> notesMount.update(model, value.value());
                case PageMsg.NotesOut value -> Update.next(model);
                case PageMsg.Todo value -> todoMount.update(model, value.value());
                case PageMsg.TodoOut value -> Update.next(model);
                case PageMsg.Clock value -> clockMount.update(model, value.value());
                case PageMsg.ClockOut value -> Update.next(model);
                case PageMsg.Show show -> {
                    Update<Pages, PageMsg, Never> applied = barMount.update(model, new Tabs.Msg.Activate(show.id()));
                    // The page that becomes visible is the one that holds the keyboard.
                    yield Update.next(focused(applied.model()), applied.command());
                }
                case PageMsg.HideClock hide -> Update.next(withMounts(model, model.keepTodo(), false));
                case PageMsg.RestoreClock restore ->
                    Update.next(new Pages(
                            model.bar(),
                            model.notes(),
                            model.todo(),
                            clockMount.initialize(new InitContext(null, null)).model(),
                            model.keepTodo(),
                            true,
                            model.modal()));
                case PageMsg.FocusBar focus ->
                    Update.next(new Pages(
                            model.bar().withFocus(true),
                            model.notes().withFocus(false),
                            model.todo().withFocus(false),
                            model.clock(),
                            model.keepTodo(),
                            model.keepClock(),
                            model.modal()));
                case PageMsg.OpenModal open -> Update.next(withModal(model, true));
                case PageMsg.CloseModal close -> Update.next(withModal(model, false));
            };
        }

        @Override
        public Subscription<PageMsg> subscriptions(Pages model) {
            Subscription<PageMsg> subscriptions =
                    Subscription.batch(barMount.subscriptions(model), notesMount.subscriptions(model));
            if (model.keepTodo()) {
                subscriptions = Subscription.batch(subscriptions, todoMount.subscriptions(model));
            }
            if (model.keepClock()) {
                subscriptions = Subscription.batch(subscriptions, clockMount.subscriptions(model));
            }
            return subscriptions;
        }

        @Override
        public ComponentTree children(Pages model) {
            List<ComponentTree.Node> nodes = new ArrayList<>();
            nodes.add(barMount.tree(model));
            nodes.add(notesMount.tree(model));
            if (model.keepTodo()) {
                nodes.add(todoMount.tree(model));
            }
            if (model.keepClock()) {
                nodes.add(clockMount.tree(model));
            }
            return new ComponentTree(nodes);
        }

        @Override
        public Element<PageMsg> view(Pages model, ViewContext context) {
            Element<PageMsg> page = switch (model.bar().activeId()) {
                case "notes" -> notesMount.view(model, context);
                case "todo" -> model.keepTodo() ? todoMount.view(model, context) : Elements.spacer();
                case "timer" -> model.keepClock() ? clockMount.view(model, context) : Elements.spacer();
                default -> Elements.spacer();
            };
            Element<PageMsg> composed = bar.element(model.bar(), page, PageMsg.Bar::new);
            if (!model.modal()) {
                return composed;
            }
            Element<PageMsg> layer =
                    Elements.frame(Border.ROUNDED, StyledText.plain("modal"), Style.NONE, Elements.text("close me"));
            return Elements.stack(composed, Elements.modal(layer));
        }

        /**
         * The gestures the frame answers before any leaf: the modal's own key while its layer is
         * open, and the bar's keys otherwise. A modal layer is the whole route, so nothing under it
         * is asked first.
         */
        @Override
        public java.util.List<Binding<PageMsg>> claims(Pages model) {
            if (model.modal()) {
                return List.of(Binding.of("right", new PageMsg.CloseModal()));
            }
            return barMount.claims(model);
        }

        /**
         * The gestures the composed pages answer once the frame has declined them.
         */
        @Override
        public java.util.List<Binding<PageMsg>> bindings(Pages model) {
            java.util.List<Binding<PageMsg>> routes = new ArrayList<>(barMount.bindings(model));
            switch (model.bar().activeId()) {
                case "notes" -> routes.addAll(notesMount.bindings(model));
                case "todo" -> routes.addAll(todoMount.bindings(model));
                case "timer" -> routes.addAll(clockMount.bindings(model));
                default -> {}
            }
            return java.util.List.copyOf(routes);
        }

        /**
         * One routed key, as the composition's own message.
         */
        Optional<PageMsg> route(Pages model, String spec) {
            // The runtime's own steps: claims, then the tree, then the last tier.
            return io.github.dhoard.braxton.testing.Routing.route(this, model, VIEW, key(spec)).stream()
                    .findFirst();
        }

        /**
         * One message and everything the runtime would deliver after it.
         */
        Pages deliver(Pages model, PageMsg message) {
            ArrayDeque<PageMsg> pending = new ArrayDeque<>();
            pending.add(message);
            while (!pending.isEmpty()) {
                Update<Pages, PageMsg, Never> update = update(model, pending.removeFirst());
                model = update.model();
                inspect(update.command(), pending);
            }
            return model;
        }

        Pages typed(Pages model, String text) {
            Pages state = model;
            for (int index = 0; index < text.length(); index++) {
                String spec = String.valueOf(text.charAt(index));
                Optional<PageMsg> message = route(state, spec);
                assertThat(message).as("key %s reaches the composition", spec).isPresent();
                state = deliver(state, message.orElseThrow());
            }
            return state;
        }

        private static void inspect(Command<PageMsg> command, ArrayDeque<PageMsg> pending) {
            switch (command) {
                case Command.None<PageMsg> none -> {}
                case Command.Batch<PageMsg> batch -> batch.commands().forEach(item -> inspect(item, pending));
                case Command.Scoped<PageMsg> scoped -> inspect(scoped.command(), pending);
                case Command.MonotonicTime<PageMsg> time ->
                    pending.add(time.mapper().apply(1_000L * MILLIS));
                case Command.Dispatch<PageMsg> dispatch -> pending.add(dispatch.message());
                default -> {}
            }
        }

        /**
         * Exactly one element holds the keyboard: the bar, or the visible editor page.
         */
        private static Pages focused(Pages model) {
            String active = model.bar().activeId();
            return new Pages(
                    model.bar().withFocus(false),
                    model.notes().withFocus("notes".equals(active)),
                    model.todo().withFocus("todo".equals(active)),
                    model.clock(),
                    model.keepTodo(),
                    model.keepClock(),
                    model.modal());
        }

        private static Pages withMounts(Pages model, boolean keepTodo, boolean keepClock) {
            return new Pages(
                    model.bar(), model.notes(), model.todo(), model.clock(), keepTodo, keepClock, model.modal());
        }

        private static Pages withModal(Pages model, boolean modal) {
            return new Pages(
                    model.bar(),
                    model.notes(),
                    model.todo(),
                    model.clock(),
                    model.keepTodo(),
                    model.keepClock(),
                    modal);
        }
    }
}
