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

import examples.parity.MouseDemo;
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyboardEnhancements;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerAction;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.DataTableConfig;
import io.github.dhoard.braxton.widget.DataTableContent;
import io.github.dhoard.braxton.widget.DataTableModel;
import io.github.dhoard.braxton.widget.DataTableMsg;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.FilePickerConfig;
import io.github.dhoard.braxton.widget.FilePickerModel;
import io.github.dhoard.braxton.widget.RichList;
import io.github.dhoard.braxton.widget.RichListConfig;
import io.github.dhoard.braxton.widget.RichListModel;
import io.github.dhoard.braxton.widget.RichListMsg;
import io.github.dhoard.braxton.widget.RichListOutput;
import io.github.dhoard.braxton.widget.Tabs;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextInputConfig;
import io.github.dhoard.braxton.widget.TextViewport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 17 acceptance: pointer reports, hit testing against the committed frame, and the wiring of
 * every completed interactive widget.
 *
 * <p>Every assertion is against public behavior: the message a report produces, the model that
 * message reduces to, the rows a widget paints, the widgets a frame makes reachable, and the
 * terminal sequences a reporting mode asks for. The hit test is driven through the shipped
 * {@link InteractionRouter} over the shipped widgets, so a pass means the pointer path and the paint
 * path agree about where a cell is.</p>
 */
@Timeout(20)
class ParityMouseTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int ROWS = 24;

    private static final int COLS = 80;

    private static final long MILLIS = TimeUnit.MILLISECONDS.toNanos(1L);

    // ── the pointer-driving helpers ──────────────────────────────────────────

    /**
     * The pointer state a gesture keeps across reports.
     *
     * <p>The runtime holds one of these for the session: capture and the double-click window belong
     * to a gesture, not to a frame, so a drag is two reports through the same instance and a fresh
     * instance is a fresh gesture. Each report is hit-tested against the frame that tree paints at
     * the geometry it is given.</p>
     */
    private static final class Pointer {
        private InteractionRouter.Interaction state = InteractionRouter.Interaction.NONE;

        <Msg> List<Msg> send(Element<Msg> tree, int rows, int cols, PointerEvent event) {
            ElementTree.Placement<Msg> placement = ElementTree.layout(tree, new Rect(0, 0, rows, cols));
            List<Msg> messages = new ArrayList<>(1);
            state = InteractionRouter.pointer(placement, state, event, messages::add);
            return List.copyOf(messages);
        }

        <Msg> Optional<Msg> once(Element<Msg> tree, int rows, int cols, PointerEvent event) {
            List<Msg> messages = send(tree, rows, cols, event);
            assertThat(messages)
                    .as("a widget answers one report at most once, so a gesture produces one message")
                    .hasSizeLessThanOrEqualTo(1);
            return messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(0));
        }

        /**
         * The widget that owns the gesture, or null.
         */
        Object captured() {
            return state.pressed();
        }
    }

    private static <Msg> Optional<Msg> send(Element<Msg> view, int rows, int cols, PointerEvent event) {
        return new Pointer().once(view, rows, cols, event);
    }

    private static <Msg> Optional<Msg> send(Element<Msg> view, PointerEvent event) {
        return send(view, ROWS, COLS, event);
    }

    private static PointerEvent leftPress(int row, int column) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, column);
    }

    private static PointerEvent leftPress(int row, int column, long nanos) {
        return leftPress(row, column).stamped(nanos);
    }

    private static PointerEvent leftDrag(int row, int column) {
        return PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, row, column);
    }

    private static PointerEvent leftRelease(int row, int column) {
        return PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, row, column);
    }

    private static PointerEvent wheel(PointerButton direction, int row, int column) {
        return PointerEvent.wheel(direction, Modifiers.NONE, row, column);
    }

    private static List<String> lines(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols).lines();
    }

    // ── R-01: decodable reports and reporting modes ──────────────────────────

    @Test
    void aReportCarriesItsActionButtonModifiersAndZeroBasedCell() {
        PointerEvent event = new PointerEvent(
                PointerAction.PRESS, PointerButton.RIGHT, new Modifiers(true, false, true), 4, 9, 1, 0L);
        assertThat(event.action()).isEqualTo(PointerAction.PRESS);
        assertThat(event.button()).isEqualTo(PointerButton.RIGHT);
        assertThat(event.modifiers().ctrl()).isTrue();
        assertThat(event.modifiers().shift()).isTrue();
        assertThat(event.isLeftPress()).isFalse();
        assertThat(event.clickCount()).isEqualTo(1);
    }

    @Test
    void aWheelActionAndAWheelButtonAlwaysBelongTogether() {
        assertThat(new PointerEvent(PointerAction.WHEEL, PointerButton.WHEEL_DOWN, Modifiers.NONE, 0, 0).isWheel())
                .isTrue();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> new PointerEvent(PointerAction.WHEEL, PointerButton.LEFT, Modifiers.NONE, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> new PointerEvent(PointerAction.PRESS, PointerButton.WHEEL_UP, Modifiers.NONE, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyReportingModeAsksForItsOwnTrackingLevelAndIsUndoneInReverse() {
        assertThat(AnsiEncoder.enableMouse(MouseMode.NONE)).isEmpty();
        assertThat(AnsiEncoder.disableMouse(MouseMode.NONE)).isEmpty();
        assertThat(AnsiEncoder.enableMouse(MouseMode.CLICK)).isEqualTo("\u001B[?1000h\u001B[?1006h");
        assertThat(AnsiEncoder.enableMouse(MouseMode.CELL_MOTION)).isEqualTo("\u001B[?1002h\u001B[?1006h");
        assertThat(AnsiEncoder.enableMouse(MouseMode.ALL_MOTION)).isEqualTo("\u001B[?1003h\u001B[?1006h");
        for (MouseMode mode : List.of(MouseMode.CLICK, MouseMode.CELL_MOTION, MouseMode.ALL_MOTION)) {
            assertThat(AnsiEncoder.disableMouse(mode))
                    .as("the encoding is disabled before the tracking level it encodes")
                    .startsWith("\u001B[?1006l\u001B[?1");
            assertThat(AnsiEncoder.enableMouse(mode)).contains("\u001B[?1006h");
        }
    }

    @Test
    void theLegacyMouseOptionStillWorksAndNamesTheClickMode() {
        assertThat(TerminalOptions.DEFAULT.mouse()).isFalse();
        assertThat(TerminalOptions.DEFAULT.mouseMode()).isEqualTo(MouseMode.NONE);
        assertThat(TerminalOptions.DEFAULT.withMouse(true).mouse()).isTrue();
        assertThat(TerminalOptions.DEFAULT.withMouse(true).mouseMode()).isEqualTo(MouseMode.CLICK);
        assertThat(new TerminalOptions(true, MouseMode.CLICK, true, KeyboardEnhancements.NONE, false))
                .isEqualTo(TerminalOptions.DEFAULT.withMouse(true));
        assertThat(TerminalOptions.DEFAULT.withMouseMode(MouseMode.ALL_MOTION).mouse())
                .isTrue();
        assertThat(TerminalOptions.DEFAULT
                        .withMouseMode(MouseMode.ALL_MOTION)
                        .withMouse(false)
                        .mouseMode())
                .isEqualTo(MouseMode.NONE);
        assertThat(TerminalOptions.DEFAULT.withMouseMode(MouseMode.ALL_MOTION).mouseMode())
                .isEqualTo(MouseMode.ALL_MOTION);
        assertThat(TerminalOptions.DEFAULT.withMouseMode(MouseMode.NONE)).isSameAs(TerminalOptions.DEFAULT);
    }
    // ── R-02: hit testing the committed frame ────────────────────────────────

    /**
     * A target that reports the report it was handed, so a test can see the translation.
     *
     * <p>It is a widget rather than a scene because what a report must reach is the widget that
     * answers for the cell: the tree only decides which one that is.</p>
     */
    private static Element<PointerEvent> target(String text) {
        return new Element.Leaf<>(new Reporter(), text, Sizing.measured());
    }

    @Test
    void aReportGoesToTheTopmostLeafUnderItAndArrivesInThatLeavesOwnCells() {
        Element<PointerEvent> view =
                Elements.stack(Elements.column(target("aaaaaaaa")), Elements.pad(1, target("bbbb")));
        PointerEvent delivered = send(view, leftPress(2, 3)).orElseThrow();
        // The padded layer starts at (1,1), so its own (1,2) is where the report landed.
        assertThat(delivered.row()).isEqualTo(1);
        assertThat(delivered.column()).isEqualTo(2);
        assertThat(delivered.button()).isEqualTo(PointerButton.LEFT);
    }

    @Test
    void aReportOnBlankSpaceReachesNoElement() {
        Element<PointerEvent> view = Elements.column(Elements.text("ab"), Elements.spacer(6));
        assertThat(send(view, leftPress(4, 0))).isEmpty();
    }

    @Test
    void aHitTestStopsWhereTheScreenStops() {
        // Six columns of screen, a ten-column target: a report past the screen belongs to nothing.
        Element<PointerEvent> view = target("0123456789");
        assertThat(send(view, 1, 6, leftPress(0, 5))).isPresent();
        assertThat(ElementTree.hit(ElementTree.layout(view, new Rect(0, 0, 1, 6)), 0, 6))
                .isNull();
    }

    @Test
    void aModalLayerMakesEverythingUnderItUnreachable() {
        Element<PointerEvent> base = Elements.column(target("back"), Elements.spacer(4));
        Element<PointerEvent> view =
                Elements.stack(base, new Element.Modal<>(Elements.pad(2, Elements.column(target("dlg")))));
        ElementTree.Placement<PointerEvent> placement = ElementTree.layout(view, new Rect(0, 0, 6, 10));
        // Only the dialog's own content is reachable while the layer is present: the base layer is
        // painted, but a cell it paints has no target and is not a stop on the focus walk.
        assertThat(InteractionRouter.focusables(placement)).hasSize(1);
        assertThat(ElementTree.hitPlacement(placement, 0, 0))
                .as("the covered layer's own cell")
                .isNull();
        assertThat(ElementTree.hitPlacement(placement, 2, 2))
                .as("the dialog's cell")
                .isNotNull();
    }

    @Test
    void theSameCellHitsAnotherElementAfterAResize() {
        Element<PointerEvent> view = Elements.row(Elements.fixed(4, target(" ")), Elements.grow(target(" ")));
        assertThat(new Pointer().once(view, 1, 10, leftPress(0, 1))).isPresent();
        // A narrower terminal moves the grown element's first column to cell zero.
        Pointer router = new Pointer();
        assertThat(router.once(view, 1, 4, leftPress(0, 1))).isPresent();
        assertThat(router.once(view, 1, 4, leftPress(0, 3))).isPresent();
    }

    // ── R-02: capture ────────────────────────────────────────────────────────

    @Test
    void aDragKeepsBeingDeliveredToTheWidgetThatCapturedThePress() {
        Element<PointerEvent> view = Elements.column(Elements.fixed(1, target("ab")), Elements.spacer(4));
        Pointer router = new Pointer();
        assertThat(router.once(view, 6, 10, leftPress(0, 0))).isPresent();
        // Outside its own rectangle, and still the field's gesture.
        PointerEvent dragged = router.once(view, 6, 10, leftDrag(4, 8)).orElseThrow();
        assertThat(dragged.row()).isEqualTo(4);
        assertThat(dragged.column()).isEqualTo(8);
    }

    @Test
    void aReleaseEndsTheCaptureAndTheNextReportHitsWhateverIsUnderIt() {
        Element<PointerEvent> view =
                Elements.column(Elements.fixed(1, target("ab")), Elements.spacer(1), Elements.fixed(1, target("cd")));
        Pointer router = new Pointer();
        assertThat(router.once(view, 6, 10, leftPress(0, 0))).isPresent();
        assertThat(router.captured()).isInstanceOf(Reporter.class);
        assertThat(router.once(view, 6, 10, leftRelease(4, 4))).isPresent();
        assertThat(router.captured()).isNull();
        assertThat(router.once(view, 6, 10, leftDrag(1, 0)))
                .as("nothing is at that cell any more")
                .isEmpty();
    }

    @Test
    void aWidgetThatDisappearsLosesItsCapture() {
        Reporter field = new Reporter();
        Pointer router = new Pointer();
        assertThat(router.once(new Element.Leaf<>(field, "ab", Sizing.measured()), 4, 10, leftPress(0, 0)))
                .isPresent();
        assertThat(router.captured()).isSameAs(field);
        // The next frame no longer paints the widget: the gesture ends with it.
        assertThat(router.send(Elements.text("ab"), 4, 10, leftDrag(0, 0))).isEmpty();
        assertThat(router.captured()).isNull();
    }

    @Test
    void aModalOpeningOverACapturedWidgetEndsTheGestureInsteadOfRetargetingIt() {
        Reporter field = new Reporter();
        Reporter dialog = new Reporter();
        Element<PointerEvent> base = new Element.Leaf<>(field, "ab", Sizing.measured());
        Element<PointerEvent> modal =
                new Element.Modal<>(Elements.column(new Element.Leaf<>(dialog, "dlg", Sizing.measured())));
        Pointer router = new Pointer();
        assertThat(router.once(base, 4, 10, leftPress(0, 0))).isPresent();
        assertThat(router.captured()).isSameAs(field);
        // The dialog is a route of its own: the gesture that belonged to the covered field ends.
        assertThat(router.once(modal, 4, 10, leftDrag(0, 0))).isEmpty();
        assertThat(router.captured()).isNull();
        assertThat(router.once(modal, 4, 10, leftPress(0, 0)))
                .as("the dialog itself is reachable")
                .isPresent();
    }

    @Test
    void aSecondClickInsideTheWindowIsTheSecondClickOfOneGesture() {
        Element<PointerEvent> view = target("ab");
        Pointer router = new Pointer();
        long start = 5_000L * MILLIS;
        assertThat(router.once(view, 4, 10, leftPress(0, 0, start))
                        .orElseThrow()
                        .clickCount())
                .isEqualTo(1);
        assertThat(router.once(view, 4, 10, leftPress(0, 0, start + 100 * MILLIS))
                        .orElseThrow()
                        .clickCount())
                .isEqualTo(2);
        long after = start + 100 * MILLIS + InteractionRouter.DOUBLE_CLICK_NANOS + 1;
        assertThat(router.once(view, 4, 10, leftPress(0, 0, after))
                        .orElseThrow()
                        .clickCount())
                .as("a press after the window is a first click again")
                .isEqualTo(1);
        assertThat(router.once(view, 4, 10, leftPress(0, 3, after + MILLIS))
                        .orElseThrow()
                        .clickCount())
                .as("a press at another cell is a first click")
                .isEqualTo(1);
        assertThat(router.once(view, 4, 10, leftPress(0, 3, after + 2 * MILLIS))
                        .orElseThrow()
                        .clickCount())
                .as("a third press of one gesture is still a double click")
                .isEqualTo(2);
    }

    @Test
    void anUnstampedPressIsNeverTheSecondClickOfAGesture() {
        Element<PointerEvent> view = target("ab");
        Pointer router = new Pointer();
        assertThat(router.once(view, 4, 10, leftPress(0, 0)).orElseThrow().clickCount())
                .isEqualTo(1);
        assertThat(router.once(view, 4, 10, leftPress(0, 0)).orElseThrow().clickCount())
                .isEqualTo(1);
    }

    // ── R-01/R-02: wheel compatibility ───────────────────────────────────────

    @Test
    void aWheelNoElementTakesIsLeftToTheRuntimeAndOfferedOnce() {
        List<String> offered = new ArrayList<>();
        Element<String> view = Elements.column(new Element.Leaf<>(new WheelWatcher(offered), "x", Sizing.measured()));
        // The pointer path reports only what a widget made of the report. A wheel nothing took is
        // left alone here: the runtime is what turns it into the gesture it used to arrive as, and
        // routes that through the same tiers a key takes, so it is delivered exactly once.
        assertThat(send(view, wheel(PointerButton.WHEEL_DOWN, 0, 0))).isEmpty();
        assertThat(offered).as("the widget saw the report once").containsExactly("pointer");
    }

    @Test
    void aWheelAnElementConsumesNeverAlsoReachesItsKeyBinding() {
        Element<String> view = new Element.Leaf<>(new WheelEater(), "x", Sizing.measured());
        assertThat(send(view, wheel(PointerButton.WHEEL_DOWN, 0, 0))).contains("consumed");
    }

    @Test
    void aHorizontalWheelHasNoLegacyGestureToFallBackTo() {
        Element<String> view = new Element.Leaf<>(new WheelWatcher(new ArrayList<>()), "x", Sizing.measured());
        assertThat(send(view, wheel(PointerButton.WHEEL_LEFT, 0, 0))).isEmpty();
    }

    @Test
    void aPointerReportNeverRunsAKeyBinding() {
        Element<String> view =
                Elements.column(new Element.Leaf<>(new WheelWatcher(new ArrayList<>()), "x", Sizing.measured()));
        assertThat(InteractionRouter.message(view, new InputEvent.PointerInput(leftPress(0, 0))))
                .isEmpty();
    }

    // ── R-03: the widgets ────────────────────────────────────────────────────

    @Test
    void clickingATabCellActivatesThatTab() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta")), "a");
        Element<Tabs.Msg> view = tabs.view(model, CONTEXT);
        assertThat(lines(view, ROWS, COLS).get(1)).startsWith("\u2502 Alpha \u2502\u2502 Beta \u2502");
        assertThat(send(view, leftPress(1, 2))).contains(new Tabs.Msg.Activate("a"));
        assertThat(send(view, leftPress(1, 11))).contains(new Tabs.Msg.Activate("b"));
        assertThat(send(view, leftPress(1, 60)))
                .as("past the last cell there is nothing to activate")
                .isEmpty();
    }

    @Test
    void clickingADisabledTabMovesNothing() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta", false)), "a");
        Element<Tabs.Msg> view = tabs.view(model, CONTEXT);
        Optional<Tabs.Msg> message = send(view, leftPress(1, 11));
        assertThat(message).contains(new Tabs.Msg.Activate("b"));
        assertThat(tabs.update(model, message.orElseThrow()).model().activeId()).isEqualTo("a");
    }

    @Test
    void theWheelStepsTheTabBar() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta")), "a");
        Element<Tabs.Msg> view = tabs.view(model, CONTEXT);
        assertThat(send(view, wheel(PointerButton.WHEEL_DOWN, 0, 0))).contains(new Tabs.Msg.Next());
        assertThat(send(view, wheel(PointerButton.WHEEL_UP, 0, 0))).contains(new Tabs.Msg.Previous());
    }

    private static Tabs.Model bar(List<Tabs.Tab> tabs, String active) {
        return new Tabs.Model(tabs, active, true);
    }

    // ── R-03: lists ──────────────────────────────────────────────────────────

    private static final List<RichList.Item> ITEMS =
            List.of(RichList.Item.of("item-1", "One"), RichList.Item.of("item-2", "Two"));

    private static RichList list() {
        return new RichList(RichListConfig.defaults());
    }

    private static RichListModel listModel(int rows) {
        RichListModel model = RichListModel.of(ITEMS, rows);
        // Title and status are the first two rows, then the items: the geometry is what the rows
        // below assert, so nothing here depends on the help or pager rows.
        return model.withChrome(model.chrome().withPagination(false).withHelp(false));
    }

    @Test
    void clickingAListRowSelectsIt() {
        RichList widget = list();
        RichListModel model = listModel(6);
        Element<RichListMsg> view = widget.view(model, new ViewContext(new Viewport(6, 40)));
        List<String> painted = lines(view, 6, 40);
        assertThat(painted.get(2)).contains("One");
        assertThat(painted.get(3)).contains("Two");
        assertThat(send(view, 6, 40, leftPress(2, 1))).contains(new RichListMsg.Select("item-1"));
        assertThat(send(view, 6, 40, leftPress(3, 1))).contains(new RichListMsg.Select("item-2"));
    }

    @Test
    void clickingAChromeRowOrABlankRowOfAListSelectsNothing() {
        RichList widget = list();
        Element<RichListMsg> view = widget.view(listModel(6), new ViewContext(new Viewport(6, 40)));
        assertThat(send(view, 6, 40, leftPress(0, 1))).as("the title").isEmpty();
        assertThat(send(view, 6, 40, leftPress(1, 1))).as("the status row").isEmpty();
        assertThat(send(view, 6, 40, leftPress(5, 1))).as("below the last item").isEmpty();
    }

    @Test
    void aSecondClickOnAListRowChoosesWhatTheFirstSelected() {
        RichList widget = list();
        RichListModel model = listModel(6);
        Element<RichListMsg> view = widget.view(model, new ViewContext(new Viewport(6, 40)));
        Pointer router = new Pointer();
        long start = 9_000L * MILLIS;
        RichListMsg first = router.once(view, 6, 40, leftPress(3, 1, start)).orElseThrow();
        assertThat(first).isEqualTo(new RichListMsg.Select("item-2"));
        Update<RichListModel, RichListMsg, RichListOutput> selected = widget.update(model, first);
        RichListMsg second =
                router.once(view, 6, 40, leftPress(3, 1, start + 120 * MILLIS)).orElseThrow();
        assertThat(second).isEqualTo(new RichListMsg.Choose());
        Update<RichListModel, RichListMsg, RichListOutput> chosen = widget.update(selected.model(), second);
        assertThat(chosen.outputs()).containsExactly(new RichListOutput.Chosen(List.of(ITEMS.get(1))));
    }

    @Test
    void selectingTheAlreadySelectedListRowIsAValueEqualNoOp() {
        RichList widget = list();
        RichListModel model = listModel(6);
        Update<RichListModel, RichListMsg, RichListOutput> update =
                widget.update(model, new RichListMsg.Select("item-1"));
        assertThat(update.model()).isSameAs(model);
    }

    @Test
    void aTwoRowListItemIsSelectedFromEitherOfItsRows() {
        RichList widget = new RichList(RichListConfig.defaults().withRenderer(new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 2;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                return List.of(StyledText.plain(row.item().title()), StyledText.plain("second row"));
            }
        }));
        Element<RichListMsg> view = widget.view(listModel(8), new ViewContext(new Viewport(8, 40)));
        List<String> painted = lines(view, 8, 40);
        assertThat(painted.get(2)).contains("One");
        assertThat(painted.get(3)).contains("second row");
        assertThat(send(view, 8, 40, leftPress(2, 1))).contains(new RichListMsg.Select("item-1"));
        assertThat(send(view, 8, 40, leftPress(3, 1)))
                .as("the second painted row of the same item")
                .contains(new RichListMsg.Select("item-1"));
        assertThat(send(view, 8, 40, leftPress(4, 1))).contains(new RichListMsg.Select("item-2"));
    }

    @Test
    void theWheelStepsAListsSelection() {
        RichList widget = list();
        Element<RichListMsg> view = widget.view(listModel(6), new ViewContext(new Viewport(6, 40)));
        assertThat(send(view, 6, 40, wheel(PointerButton.WHEEL_DOWN, 2, 0))).contains(new RichListMsg.Down());
        assertThat(send(view, 6, 40, wheel(PointerButton.WHEEL_UP, 2, 0))).contains(new RichListMsg.Up());
    }

    // ── R-03: tables ─────────────────────────────────────────────────────────

    private static final DataTableContent TABLE = DataTableContent.of(
            List.of(DataTable.Column.of("id", "ID"), DataTable.Column.of("name", "Name")),
            List.of(DataTable.Row.of("r1", "1", "one"), DataTable.Row.of("r2", "2", "two")));

    private static DataTable table() {
        return new DataTable(DataTableConfig.defaults());
    }

    private static DataTableModel tableModel(DataTable.Mode mode) {
        return new DataTableModel(TABLE, new DataTable.Selection(mode, 0, 0), 0, 0, 6, 30, true);
    }

    @Test
    void clickingATableRowSelectsItAndTheHeaderOrdersByItsColumn() {
        DataTable widget = table();
        Element<DataTableMsg> view = widget.view(tableModel(DataTable.Mode.ROW), new ViewContext(new Viewport(6, 30)));
        List<String> painted = lines(view, 6, 30);
        assertThat(painted.get(0)).contains("ID").contains("Name");
        assertThat(send(view, 6, 30, leftPress(0, 1))).as("the header row").contains(new DataTableMsg.ToggleSort("id"));
        assertThat(send(view, 6, 30, leftPress(0, painted.get(0).indexOf("Name"))))
                .contains(new DataTableMsg.ToggleSort("name"));
        assertThat(send(view, 6, 30, leftPress(1, 1))).contains(new DataTableMsg.Select("r1"));
        assertThat(send(view, 6, 30, leftPress(2, 1))).contains(new DataTableMsg.Select("r2"));
        assertThat(send(view, 6, 30, leftPress(7, 1))).as("past the content").isEmpty();
    }

    @Test
    void clickingACellwiseTableSelectsTheCellUnderThePointer() {
        DataTable widget = table();
        Element<DataTableMsg> view = widget.view(tableModel(DataTable.Mode.CELL), new ViewContext(new Viewport(6, 30)));
        String header = lines(view, 6, 30).get(1);
        int nameColumn = header.indexOf("one");
        assertThat(nameColumn).isPositive();
        assertThat(send(view, 6, 30, leftPress(1, nameColumn))).contains(new DataTableMsg.SelectCell("r1", 1));
        assertThat(send(view, 6, 30, leftPress(1, 1))).contains(new DataTableMsg.SelectCell("r1", 0));
    }

    @Test
    void theWheelStepsATablesSelection() {
        DataTable widget = table();
        Element<DataTableMsg> view = widget.view(tableModel(DataTable.Mode.ROW), new ViewContext(new Viewport(6, 30)));
        assertThat(send(view, 6, 30, wheel(PointerButton.WHEEL_DOWN, 1, 0))).contains(new DataTableMsg.Down());
        assertThat(send(view, 6, 30, wheel(PointerButton.WHEEL_UP, 1, 0))).contains(new DataTableMsg.Up());
    }

    // ── R-03: the file picker ────────────────────────────────────────────────

    private static final Path DIRECTORY = Path.of("/tmp/picker");

    private static List<FilePicker.Entry> entries() {
        return List.of(
                new FilePicker.Entry(DIRECTORY.resolve("docs"), FilePicker.Kind.DIRECTORY, -1, "", "", true, true),
                new FilePicker.Entry(DIRECTORY.resolve("notes.txt"), FilePicker.Kind.FILE, 12, "", "", true, false));
    }

    private static FilePicker picker() {
        return new FilePicker(FilePickerConfig.defaults());
    }

    private static FilePickerModel pickerModel() {
        FilePickerModel model = FilePickerModel.of(DIRECTORY, entries()).withRows(8);
        return model.withExpanded(false);
    }

    @Test
    void clickingAFilePickerEntrySelectsIt() {
        FilePicker widget = picker();
        Element<FilePicker.Msg> view = widget.view(pickerModel(), new ViewContext(new Viewport(8, 40)));
        List<String> painted = lines(view, 8, 40);
        assertThat(painted.get(0)).contains("/tmp/picker");
        assertThat(painted.get(1)).contains("docs");
        assertThat(send(view, 8, 40, leftPress(1, 1))).contains(new FilePicker.Msg.Select("docs"));
        assertThat(send(view, 8, 40, leftPress(2, 1))).contains(new FilePicker.Msg.Select("notes.txt"));
        assertThat(send(view, 8, 40, leftPress(0, 1))).as("the path row").isEmpty();
    }

    @Test
    void aSecondClickOnAFilePickerEntryOpensOrChoosesIt() {
        FilePicker widget = picker();
        Element<FilePicker.Msg> view = widget.view(pickerModel(), new ViewContext(new Viewport(8, 40)));
        Pointer router = new Pointer();
        long start = 3_000L * MILLIS;
        assertThat(router.once(view, 8, 40, leftPress(1, 1, start))).contains(new FilePicker.Msg.Select("docs"));
        assertThat(router.once(view, 8, 40, leftPress(1, 1, start + 90 * MILLIS)))
                .as("a directory is entered")
                .contains(new FilePicker.Msg.Open());
        Pointer second = new Pointer();
        assertThat(second.once(view, 8, 40, leftPress(2, 1, start))).contains(new FilePicker.Msg.Select("notes.txt"));
        assertThat(second.once(view, 8, 40, leftPress(2, 1, start + 90 * MILLIS)))
                .as("a file is chosen")
                .contains(new FilePicker.Msg.Confirm());
    }

    @Test
    void theWheelStepsAFilePickersSelection() {
        FilePicker widget = picker();
        Element<FilePicker.Msg> view = widget.view(pickerModel(), new ViewContext(new Viewport(8, 40)));
        assertThat(send(view, 8, 40, wheel(PointerButton.WHEEL_DOWN, 1, 0))).contains(new FilePicker.Msg.Down());
    }

    // ── R-03: the text viewport ──────────────────────────────────────────────

    @Test
    void theWheelScrollsTheViewportUnderThePointer() {
        TextViewport viewport = new TextViewport(TextViewport.Config.defaults());
        TextViewport.Model model = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                4,
                20,
                TextViewport.Content.of(List.of(StyledText.plain("one"), StyledText.plain("two"))),
                List.of(),
                -1);
        Element<TextViewport.Msg> view = viewport.view(model, CONTEXT);
        assertThat(send(view, 4, 20, wheel(PointerButton.WHEEL_DOWN, 1, 1))).contains(new TextViewport.Msg.Down(3));
        assertThat(send(view, 4, 20, wheel(PointerButton.WHEEL_UP, 1, 1))).contains(new TextViewport.Msg.Up(3));
        assertThat(send(view, 4, 20, leftPress(1, 1)))
                .as("a pane has no selection of its own")
                .isEmpty();
    }

    // ── R-03: the editor ─────────────────────────────────────────────────────

    private static Editor editor() {
        return new Editor(EditorConfig.defaults());
    }

    private static Editor.Model draft(String text) {
        return Editor.Model.of(text);
    }

    private static Editor.Model placed(Editor editor, Editor.Model model, Optional<Editor.Msg> message) {
        return editor.update(model, message.orElseThrow()).model();
    }

    @Test
    void clickingTheEditorPutsTheCaretOnTheClusterUnderThePointer() {
        Editor editor = editor();
        Editor.Model model = draft("abcdef");
        Element<Editor.Msg> view = editor.view(model, CONTEXT);
        assertThat(lines(view, 3, 20).get(0)).startsWith("abcdef");
        assertThat(placed(editor, model, send(view, 3, 20, leftPress(0, 2))).caret())
                .isEqualTo(2);
        assertThat(placed(editor, model, send(view, 3, 20, leftPress(0, 6))).caret())
                .as("past the last cluster the caret is the end of the draft")
                .isEqualTo(6);
    }

    @Test
    void aClickNeverSplitsAWideGlyphInTheEditor() {
        Editor editor = editor();
        Editor.Model model = draft("a\u4e16b");
        Element<Editor.Msg> view = editor.view(model, CONTEXT);
        assertThat(lines(view, 3, 10).get(0)).startsWith("a\u4e16b");
        assertThat(placed(editor, model, send(view, 3, 10, leftPress(0, 0))).caret())
                .isEqualTo(0);
        assertThat(placed(editor, model, send(view, 3, 10, leftPress(0, 1))).caret())
                .as("the first cell of the wide glyph sits before it")
                .isEqualTo(1);
        assertThat(placed(editor, model, send(view, 3, 10, leftPress(0, 2))).caret())
                .as("a cell inside the glyph is a boundary of it, never its middle")
                .isEqualTo(1);
        assertThat(placed(editor, model, send(view, 3, 10, leftPress(0, 3))).caret())
                .as("the next cluster's cell sits before that cluster")
                .isEqualTo(2);
        assertThat(placed(editor, model, send(view, 3, 10, leftPress(0, 4))).caret())
                .as("the blank cell after the text is the end of the draft")
                .isEqualTo(3);
    }

    @Test
    void aClickIsCountedFromTheEditorsOwnGutter() {
        Editor editor = new Editor(EditorConfig.defaults().withPrompt("> ", Style.NONE));
        Editor.Model model = draft("abc");
        Element<Editor.Msg> view = editor.view(model, new ViewContext(new Viewport(3, 20)));
        assertThat(lines(view, 3, 20).get(0)).startsWith("> abc");
        assertThat(placed(editor, model, send(view, 3, 20, leftPress(0, 2))).caret())
                .isEqualTo(0);
        assertThat(placed(editor, model, send(view, 3, 20, leftPress(0, 4))).caret())
                .isEqualTo(2);
    }

    @Test
    void aClickInAWrappedEditorLandsOnTheRowItWasPaintedOn() {
        Editor editor = editor();
        Editor.Model model = draft("abcdefghij");
        Element<Editor.Msg> view = editor.view(model, new ViewContext(new Viewport(4, 5)));
        assertThat(lines(view, 4, 5).get(0)).isEqualTo("abcde");
        assertThat(lines(view, 4, 5).get(1)).isEqualTo("fghij");
        assertThat(placed(editor, model, send(view, 4, 5, leftPress(1, 1))).caret())
                .isEqualTo(6);
        assertThat(placed(editor, model, send(view, 4, 5, leftPress(0, 4))).caret())
                .isEqualTo(4);
    }

    @Test
    void aDragInTheEditorExtendsTheSelectionFromWhereItStarted() {
        Editor editor = editor();
        Editor.Model model = draft("abcdef");
        Element<Editor.Msg> view = editor.view(model, CONTEXT);
        Pointer router = new Pointer();
        Editor.Model pressed = placed(editor, model, router.once(view, 3, 20, leftPress(0, 1)));
        assertThat(pressed.anchor()).isEqualTo(1);
        assertThat(pressed.caret()).isEqualTo(1);
        Editor.Model dragged = placed(editor, pressed, router.once(view, 3, 20, leftDrag(0, 4)));
        assertThat(dragged.anchor())
                .as("the anchor stays where the press put it")
                .isEqualTo(1);
        assertThat(dragged.caret()).isEqualTo(4);
        assertThat(dragged.hasSelection()).isTrue();
        assertThat(router.once(view, 3, 20, leftRelease(0, 4)))
                .as("a release ends the gesture without editing anything")
                .isEmpty();
        assertThat(router.captured()).isNull();
    }

    @Test
    void aSecondClickInTheEditorSelectsTheWordUnderThePointer() {
        Editor editor = editor();
        Editor.Model model = draft("alpha beta gamma");
        Element<Editor.Msg> view = editor.view(model, CONTEXT);
        long start = 7_000L * MILLIS;
        Pointer router = new Pointer();
        assertThat(router.once(view, 3, 30, leftPress(0, 7, start))).contains(new Editor.Msg.PlaceCaret(7));
        Optional<Editor.Msg> second = router.once(view, 3, 30, leftPress(0, 7, start + 80 * MILLIS));
        assertThat(second).contains(new Editor.Msg.SelectWord(7));
        Editor.Model selected = editor.update(model, second.orElseThrow()).model();
        assertThat(selected.anchor()).isEqualTo(6);
        assertThat(selected.caret()).isEqualTo(10);
        assertThat(selected.text().substring(selected.anchor(), selected.caret()))
                .isEqualTo("beta");
    }

    @Test
    void clickingAnEditorNeverChangesItsFocusByItself() {
        Editor editor = editor();
        Editor.Model model = new Editor.Model("abc", 0, 0, 0, false);
        Element<Editor.Msg> view = editor.view(model, CONTEXT);
        Editor.Model clicked = placed(editor, model, send(view, 3, 20, leftPress(0, 2)));
        assertThat(clicked.caret()).isEqualTo(2);
        assertThat(clicked.focused())
                .as("focus is the application's decision, not a side effect of a click")
                .isFalse();
    }

    // ── R-03: the single-line field ──────────────────────────────────────────

    private static TextInput field(TextInputConfig config) {
        return new TextInput(config);
    }

    @Test
    void clickingAFieldPutsTheCaretWhereThePointerWas() {
        TextInput widget = field(TextInputConfig.defaults());
        TextInput.Model model = TextInput.Model.of("abcdef");
        Element<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        TextInput.Model clicked = widget.update(
                        model, send(view, 1, 20, leftPress(0, 2)).orElseThrow())
                .model();
        assertThat(clicked.draft().caret()).isEqualTo(2);
    }

    @Test
    void clickingAMaskedFieldCountsItsOwnClusters() {
        TextInput widget = field(TextInputConfig.defaults().withMasking(EditorConfig.Masking.PASSWORD));
        TextInput.Model model = TextInput.Model.of("abc").withFocus(false);
        Element<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        assertThat(lines(view, 1, 20).get(0)).isEqualTo("\u2022\u2022\u2022");
        TextInput.Model clicked = widget.update(
                        model, send(view, 1, 20, leftPress(0, 1)).orElseThrow())
                .model();
        assertThat(clicked.draft().caret()).isEqualTo(1);
    }

    @Test
    void clickingAHiddenFieldPlacesNoCaret() {
        TextInput widget = field(TextInputConfig.defaults().withMasking(EditorConfig.Masking.HIDDEN));
        TextInput.Model model = TextInput.Model.of("abc").withFocus(false);
        Element<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        assertThat(lines(view, 1, 20).get(0)).isEmpty();
        assertThat(send(view, 1, 20, leftPress(0, 1)))
                .as("a field that paints no glyph has nothing to point at")
                .isEmpty();
    }

    @Test
    void aClickOnAScrolledFieldLandsOnTheCellItPainted() {
        TextInput widget = field(TextInputConfig.defaults());
        TextInput.Model model = TextInput.Model.of("0123456789abcdefghij");
        Element<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 10)));
        String painted = lines(view, 1, 10).get(0);
        assertThat(painted).hasSize(10);
        TextInput.Model end = widget.update(
                        model, send(view, 1, 10, leftPress(0, 9)).orElseThrow())
                .model();
        assertThat(end.draft().caret())
                .as("the rightmost visible cell is the end of the draft")
                .isEqualTo(20);
        TextInput.Model start = widget.update(
                        model, send(view, 1, 10, leftPress(0, 0)).orElseThrow())
                .model();
        assertThat(start.draft().caret())
                .as("the leftmost visible cell is the first cluster the window shows")
                .isEqualTo(11);
    }

    @Test
    void aDragInAFieldExtendsTheSelection() {
        TextInput widget = field(TextInputConfig.defaults());
        TextInput.Model model = TextInput.Model.of("abcdef");
        Element<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        Pointer router = new Pointer();
        TextInput.Model pressed = widget.update(
                        model, router.once(view, 1, 20, leftPress(0, 1)).orElseThrow())
                .model();
        TextInput.Model dragged = widget.update(
                        pressed, router.once(view, 1, 20, leftDrag(0, 3)).orElseThrow())
                .model();
        assertThat(dragged.draft().anchor()).isEqualTo(1);
        assertThat(dragged.draft().caret()).isEqualTo(3);
        assertThat(dragged.draft().hasSelection()).isTrue();
    }

    // ── R-04: the demo ───────────────────────────────────────────────────────

    /**
     * The demo scene driven the way the runtime drives it: one report through the router, then every
     * message it produced applied in order.
     */
    private static final class Demo {

        private final MouseDemo.Scene scene = new MouseDemo.Scene();
        private final Pointer router = new Pointer();
        private MouseDemo.Model model;

        Demo() {
            model = scene.init(new InitContext(new Viewport(ROWS, COLS), null)).model();
        }

        List<String> rows() {
            return lines(scene.view(model, CONTEXT), ROWS, COLS);
        }

        String status() {
            return rows().get(0);
        }

        String pointer() {
            return rows().get(1);
        }

        void send(PointerEvent event) {
            // The runtime's two steps: the report reaches the element under it, then the scene is
            // shown what arrived.
            for (MouseDemo.Msg message : router.send(scene.view(model, CONTEXT), ROWS, COLS, event)) {
                model = scene.update(model, message).model();
            }
            for (MouseDemo.Msg message : scene.observed(new InputEvent.PointerInput(event))) {
                model = scene.update(model, message).model();
            }
        }

        void send(MouseDemo.Msg message) {
            model = scene.update(model, message).model();
        }

        Object captured() {
            return router.captured();
        }
    }

    @Test
    void theDemoPaintsThePanesSideBySideAndReportsItsState() {
        Demo demo = new Demo();
        List<String> rows = demo.rows();
        assertThat(rows.get(0))
                .isEqualTo("pane=left dialog=closed draft=" + MouseDemo.DRAFT.length() + " caret="
                        + MouseDemo.DRAFT.length() + " sel=item-01 dlg=dialog-01 last=none");
        assertThat(rows.get(1)).isEqualTo("pointer=none reports=0");
        assertThat(rows.get(2))
                .as("the editor's first row and the list's title")
                .contains("Click in this pane");
        assertThat(rows.get(2)).contains("\u2502Items");
    }

    @Test
    void theDemoLogsEveryReportAndRoutesItIntoThePaneUnderThePointer() {
        Demo demo = new Demo();
        demo.send(leftPress(2, 5));
        assertThat(demo.status()).contains("caret=5");
        assertThat(demo.pointer()).isEqualTo("pointer=press:left@2,5 reports=1");
        demo.send(leftRelease(2, 5));
        assertThat(demo.pointer()).isEqualTo("pointer=release:left@2,5 reports=2");
        // The same scene, another pane: the list selects the item whose two rows the cell falls in.
        demo.send(leftPress(6, 45));
        assertThat(demo.status()).contains("sel=item-02");
        assertThat(demo.status()).contains("caret=5").as("the editor was not touched");
        assertThat(demo.status())
                .as("the pane that acted took the keyboard: the scene consumed the click's intent")
                .contains("pane=right");
        demo.send(leftRelease(6, 45));
        demo.send(leftDrag(6, 45));
        assertThat(demo.pointer()).isEqualTo("pointer=motion:left@6,45 reports=5");
    }

    @Test
    void aDragInTheDemoKeepsSelectingAfterItLeavesThePaneThatCapturedIt() {
        Demo demo = new Demo();
        demo.send(leftPress(2, 2));
        assertThat(demo.status()).contains("caret=2");
        // Two rows down and past the pane's own columns: the editor still owns the gesture.
        demo.send(leftDrag(4, 60));
        assertThat(demo.model.draft())
                .as("the draft is unchanged: a drag selects, it does not delete")
                .isEqualTo(MouseDemo.DRAFT);
        assertThat(demo.model.left().hasSelection()).isTrue();
        demo.send(leftRelease(4, 60));
        assertThat(demo.captured()).isNull();
    }

    @Test
    void theDialogTakesThePointerWhileThePanesUnderItAreUnreachable() {
        Demo demo = new Demo();
        demo.send(new MouseDemo.Msg.ToggleModal());
        assertThat(demo.status()).contains("dialog=open");
        // A cell the left pane paints, outside the dialog's box: the pane is not reachable.
        demo.send(leftPress(15, 5));
        assertThat(demo.status()).contains("caret=" + MouseDemo.DRAFT.length());
        assertThat(demo.pointer()).as("the report was still observed").isEqualTo("pointer=press:left@15,5 reports=1");
        // The dialog's first and second entries.
        demo.send(leftPress(5, 25));
        assertThat(demo.status()).contains("dlg=dialog-01");
        demo.send(leftPress(6, 25));
        assertThat(demo.status()).contains("dlg=dialog-02");
        // Closing it gives the panes back.
        demo.send(new MouseDemo.Msg.ToggleModal());
        demo.send(leftPress(2, 3));
        assertThat(demo.status()).contains("caret=3");
    }

    @Test
    void theWheelActsOnThePaneItIsOver() {
        Demo demo = new Demo();
        demo.send(wheel(PointerButton.WHEEL_DOWN, 6, 45));
        assertThat(demo.status()).contains("sel=item-02");
        assertThat(demo.status()).contains("caret=" + MouseDemo.DRAFT.length());
        demo.send(wheel(PointerButton.WHEEL_DOWN, 2, 5));
        assertThat(demo.status())
                .as("an editor has no wheel gesture, and the wheel never becomes a typed key")
                .contains("caret=" + MouseDemo.DRAFT.length());
    }

    // ── preserved behavior ─────────────────────────────────────────────────

    @Test
    void aFrameIsAFunctionOfItsInputsAndAHitTestChangesNothing() {
        RichList widget = list();
        Element<RichListMsg> view = widget.view(listModel(6), new ViewContext(new Viewport(6, 40)));
        List<String> first = lines(view, 6, 40);
        send(view, 6, 40, leftPress(2, 1));
        assertThat(lines(view, 6, 40)).isEqualTo(first);
    }

    @Test
    void aKeyStillReachesTheElementThatOwnsFocusWithPointerWiringInPlace() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta")), "a");
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                tabs, model, CONTEXT, InputEvent.KeyInput.of(Key.RIGHT))
                        .stream()
                        .findFirst())
                .contains(new Tabs.Msg.Next());
    }

    @Test
    void anEmptyFrameDismissesEveryReport() {
        Element<PointerEvent> view = Elements.spacer();
        assertThat(send(view, 0, 0, leftPress(0, 0))).isEmpty();
        assertThat(send(view, 5, 5, wheel(PointerButton.WHEEL_UP, 0, 0))).isEmpty();
    }

    // ── the test widgets ────────────────────────────────────────────────────

    /**
     * A widget that fills the rectangle it is offered and reports the report it was handed.
     *
     * <p>Filling the rectangle is what makes the cell the report lands on the widget's own, which is
     * what these tests assert: a widget that painted one row would leave the rest of its rectangle
     * belonging to whatever is behind it.</p>
     */
    private static final class Reporter implements Widget<String, PointerEvent> {

        @Override
        public Size measure(Size max, String model) {
            return max;
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {
            surface.write(bounds, StyledText.plain(model), WidthPolicy.PINNED);
        }

        @Override
        public boolean canFocus(String model) {
            return true;
        }

        @Override
        public void onPointer(PointerEvent pointer, Rect bounds, String model, Consumer<PointerEvent> emit) {
            emit.accept(pointer.localTo(bounds.row(), bounds.col()));
        }
    }

    /**
     * A widget that records the reports it sees and answers none of them.
     */
    private static final class WheelWatcher implements Widget<String, String> {

        private final List<String> seen;

        WheelWatcher(List<String> seen) {
            this.seen = seen;
        }

        @Override
        public Size measure(Size max, String model) {
            return new Size(1, Math.min(max.cols(), Math.max(1, model.length())));
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {
            surface.write(bounds, StyledText.plain(model), WidthPolicy.PINNED);
        }

        @Override
        public boolean canFocus(String model) {
            return true;
        }

        @Override
        public void onPointer(PointerEvent pointer, Rect bounds, String model, Consumer<String> emit) {
            seen.add("pointer");
        }
    }

    /**
     * A widget that takes every wheel report it is handed.
     */
    private static final class WheelEater implements Widget<String, String> {

        @Override
        public Size measure(Size max, String model) {
            return new Size(1, Math.min(max.cols(), Math.max(1, model.length())));
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {
            surface.write(bounds, StyledText.plain(model), WidthPolicy.PINNED);
        }

        @Override
        public boolean canFocus(String model) {
            return true;
        }

        @Override
        public void onPointer(PointerEvent pointer, Rect bounds, String model, Consumer<String> emit) {
            emit.accept("consumed");
        }
    }
}
