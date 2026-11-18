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
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerAction;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.input.PointerRouter;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.RichList;
import io.github.dhoard.braxton.widget.Tabs;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextViewport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 17 acceptance: pointer reports, hit testing against the committed frame, and the wiring of
 * every completed interactive widget.
 *
 * <p>Every assertion is against public behaviour: the message a report produces, the model that
 * message reduces to, the rows a widget paints, the regions a layout declares, and the terminal
 * sequences a reporting mode asks for. The hit test is driven through the shipped
 * {@link PointerRouter} and the shipped widgets, laid out by the shipped {@link LayoutEngine}, so a
 * pass means the pointer path and the paint path agree about where a cell is.</p>
 */
@Timeout(20)
class ParityMouseTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int ROWS = 24;

    private static final int COLS = 80;

    private static final long MILLIS = TimeUnit.MILLISECONDS.toNanos(1L);

    // ── the pointer-driving helpers ──────────────────────────────────────────

    /**
     * One report delivered against the frame the view paints at this geometry.
     *
     * <p>The router is a parameter because capture and the double-click window live in it: a drag is
     * two reports through the same instance, and a fresh instance is a fresh gesture.</p>
     */
    private static <Msg> Optional<Msg> send(
            PointerRouter router, View<Msg> view, int rows, int cols, PointerEvent event) {
        List<Msg> messages = sendAll(router, view, rows, cols, event);
        assertThat(messages)
                .as("no interceptor observes in these views, so a report produces at most one message")
                .hasSizeLessThanOrEqualTo(1);
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(0));
    }

    /** Every message one report produced: the observers' in paint order, then the target's. */
    private static <Msg> List<Msg> sendAll(
            PointerRouter router, View<Msg> view, int rows, int cols, PointerEvent event) {
        LayoutEngine.Layout layout = new LayoutEngine().layout(view, new Rect(0, 0, rows, cols));
        router.observeFrame(layout.regions());
        return router.route(view, new InputEvent.PointerInput(event), layout.regions());
    }

    private static <Msg> Optional<Msg> send(View<Msg> view, int rows, int cols, PointerEvent event) {
        return send(new PointerRouter(), view, rows, cols, event);
    }

    private static <Msg> Optional<Msg> send(View<Msg> view, PointerEvent event) {
        return send(new PointerRouter(), view, ROWS, COLS, event);
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

    private static List<String> lines(View<?> view, int rows, int cols) {
        return new ScreenRenderer().plainLines(view, new Viewport(rows, cols));
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
        assertThat(new TerminalOptions(true, true, true)).isEqualTo(TerminalOptions.DEFAULT.withMouse(true));
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

    /** A target that reports the report it was handed, so a test can see the translation. */
    private static View<PointerEvent> target(ElementId id, String text) {
        return Views.focus(id, false, (event, region) -> Optional.of(event), Views.text(text));
    }

    @Test
    void aReportGoesToTheTopmostRegionUnderItAndArrivesInThatRegionsOwnCells() {
        View<PointerEvent> view = Views.overlay(
                target(ElementId.of("behind"), "aaaaaaaa"), Views.padding(1, target(ElementId.of("above"), "bbbb")));
        PointerEvent delivered = send(view, leftPress(2, 3)).orElseThrow();
        // The padded layer starts at (1,1), so its own (1,2) is where the report landed.
        assertThat(delivered.row()).isEqualTo(1);
        assertThat(delivered.column()).isEqualTo(2);
        assertThat(delivered.button()).isEqualTo(PointerButton.LEFT);
    }

    @Test
    void aReportOnBlankSpaceReachesNoElement() {
        View<PointerEvent> view = Views.column(Views.text("ab"), Views.gap(6));
        assertThat(send(view, leftPress(4, 0))).isEmpty();
    }

    @Test
    void aRegionIsTheClippedRectangleTheElementPaintedIn() {
        // Six columns of screen, a ten-column child: the region stops where the screen does, so a
        // report past it belongs to nothing.
        View<PointerEvent> view = Views.overlay(target(ElementId.of("wide"), "0123456789"));
        assertThat(send(view, 1, 6, leftPress(0, 5))).isPresent();
        assertThat(send(view, 1, 6, leftPress(0, 6))).isEmpty();
    }

    @Test
    void aModalLayerMakesEverythingUnderItUnreachable() {
        ElementId behind = ElementId.of("behind");
        View<PointerEvent> view = Views.modal(
                Views.column(Views.focus(behind, false, Views.text("back")), Views.gap(4)),
                Views.padding(2, target(ElementId.of("dialog"), "dlg")));
        List<io.github.dhoard.braxton.view.RenderedScreen.Region> regions =
                new ScreenRenderer().regions(view, new Viewport(6, 10));
        assertThat(regions)
                .extracting(region -> region.id().value() + ":" + region.reachable())
                .containsExactlyInAnyOrder("behind:false", "dialog:true");
        assertThat(send(view, leftPress(0, 0))).isEmpty();
        assertThat(send(view, leftPress(2, 2))).isPresent();
    }

    @Test
    void theSameCellHitsAnotherElementAfterAResize() {
        ElementId top = ElementId.of("top-left");
        ElementId below = ElementId.of("below");
        View<PointerEvent> view = Views.row(Views.fixed(4, target(top, " ")), Views.grow(target(below, " ")));
        assertThat(send(new PointerRouter(), view, 1, 10, leftPress(0, 1))).isPresent();
        // A narrower terminal moves the second element's first column to cell four.
        PointerRouter router = new PointerRouter();
        assertThat(send(router, view, 1, 4, leftPress(0, 1))).isPresent();
        assertThat(send(router, view, 1, 4, leftPress(0, 3))).isPresent();
    }

    @Test
    void theInterceptorsSeeTheReportBeforeItsTargetDoes() {
        List<String> seen = new ArrayList<>();
        View<String> view = Views.keyed(
                List.of(),
                event -> {
                    seen.add(event.getClass().getSimpleName());
                    return Optional.empty();
                },
                Views.focus(ElementId.of("field"), false, (event, region) -> Optional.of("target"), Views.text("x")),
                List.of());
        assertThat(send(view, leftPress(0, 0))).contains("target");
        assertThat(seen).containsExactly("PointerInput");
    }

    @Test
    void anInterceptorsMessageIsDeliveredBeforeTheTargetsOwnButDoesNotReplaceIt() {
        View<String> view = Views.keyed(
                List.of(),
                event -> Optional.of("observed"),
                Views.focus(ElementId.of("field"), false, (event, region) -> Optional.of("target"), Views.text("x")),
                List.of());
        assertThat(sendAll(new PointerRouter(), view, ROWS, COLS, leftPress(0, 0)))
                .containsExactly("observed", "target");
    }

    @Test
    void everyObserverOnTheWaySeesTheReportBeforeTheTargetDoes() {
        List<String> seen = new ArrayList<>();
        View<String> view = Views.onKeys(
                List.of(),
                Views.keyed(
                        List.of(),
                        event -> {
                            seen.add("outer");
                            return Optional.of("outer");
                        },
                        Views.keyed(
                                List.of(),
                                event -> {
                                    seen.add("inner");
                                    return Optional.empty();
                                },
                                Views.focus(
                                        ElementId.of("field"),
                                        false,
                                        (event, region) -> Optional.of("target"),
                                        Views.text("x")),
                                List.of()),
                        List.of()));
        assertThat(sendAll(new PointerRouter(), view, ROWS, COLS, leftPress(0, 0)))
                .containsExactly("outer", "target");
        assertThat(seen).containsExactly("outer", "inner");
    }

    // ── R-02: capture ────────────────────────────────────────────────────────

    @Test
    void aDragKeepsBeingDeliveredToTheElementThatCapturedThePress() {
        View<PointerEvent> view = Views.column(target(ElementId.of("field"), "ab"), Views.gap(4));
        PointerRouter router = new PointerRouter();
        assertThat(send(router, view, 6, 10, leftPress(0, 0))).isPresent();
        // Outside its own rectangle, and still the field's gesture.
        PointerEvent dragged = send(router, view, 6, 10, leftDrag(4, 8)).orElseThrow();
        assertThat(dragged.row()).isEqualTo(4);
        assertThat(dragged.column()).isEqualTo(8);
    }

    @Test
    void aReleaseEndsTheCaptureAndTheNextReportHitsWhateverIsUnderIt() {
        ElementId field = ElementId.of("field");
        ElementId below = ElementId.of("below");
        View<PointerEvent> view = Views.column(target(field, "ab"), Views.gap(1), target(below, "cd"));
        PointerRouter router = new PointerRouter();
        assertThat(send(router, view, 6, 10, leftPress(0, 0))).isPresent();
        assertThat(router.captured()).isEqualTo(field);
        assertThat(send(router, view, 6, 10, leftRelease(4, 4))).isPresent();
        assertThat(router.captured()).isNull();
        assertThat(send(router, view, 6, 10, leftDrag(1, 0)))
                .as("nothing is at that cell any more")
                .isEmpty();
    }

    @Test
    void anElementThatDisappearsLosesItsCapture() {
        ElementId field = ElementId.of("field");
        View<PointerEvent> withField =
                Views.focus(field, false, (event, region) -> Optional.of(event), Views.text("ab"));
        PointerRouter router = new PointerRouter();
        assertThat(send(router, withField, 4, 10, leftPress(0, 0))).isPresent();
        assertThat(router.captured()).isEqualTo(field);
        // The next frame no longer declares the element: the gesture ends with it.
        View<PointerEvent> withoutField = Views.text("ab");
        assertThat(send(router, withoutField, 4, 10, leftDrag(0, 0))).isEmpty();
        assertThat(router.captured()).isNull();
    }

    @Test
    void aModalOpeningOverACapturedElementEndsTheGestureInsteadOfRetargetingIt() {
        ElementId field = ElementId.of("field");
        ElementId dialog = ElementId.of("dialog");
        View<PointerEvent> base = Views.focus(field, false, (event, region) -> Optional.of(event), Views.text("ab"));
        View<PointerEvent> modal = Views.modal(
                base,
                Views.column(
                        Views.gap(1),
                        Views.focus(dialog, false, (event, region) -> Optional.of(event), Views.text("dlg"))));
        PointerRouter router = new PointerRouter();
        assertThat(send(router, base, 4, 10, leftPress(0, 0))).isPresent();
        assertThat(router.captured()).isEqualTo(field);
        // The dialog is a route of its own: the gesture that belonged to the covered field ends, and
        // the blank row of the dialog's own column belongs to neither element.
        assertThat(send(router, modal, 4, 10, leftDrag(0, 0))).isEmpty();
        assertThat(router.captured()).isNull();
        assertThat(send(router, modal, 4, 10, leftPress(1, 0)))
                .as("the dialog itself is reachable")
                .isPresent();
    }

    @Test
    void aSecondClickInsideTheWindowIsTheSecondClickOfOneGesture() {
        View<PointerEvent> view = target(ElementId.of("field"), "ab");
        PointerRouter router = new PointerRouter();
        long start = 5_000L * MILLIS;
        assertThat(send(router, view, 4, 10, leftPress(0, 0, start))
                        .orElseThrow()
                        .clickCount())
                .isEqualTo(1);
        assertThat(send(router, view, 4, 10, leftPress(0, 0, start + 100 * MILLIS))
                        .orElseThrow()
                        .clickCount())
                .isEqualTo(2);
        long after = start + 100 * MILLIS + PointerRouter.DOUBLE_CLICK_NANOS + 1;
        assertThat(send(router, view, 4, 10, leftPress(0, 0, after))
                        .orElseThrow()
                        .clickCount())
                .as("a press after the window is a first click again")
                .isEqualTo(1);
        assertThat(send(router, view, 4, 10, leftPress(0, 3, after + MILLIS))
                        .orElseThrow()
                        .clickCount())
                .as("a press at another cell is a first click")
                .isEqualTo(1);
        assertThat(send(router, view, 4, 10, leftPress(0, 3, after + 2 * MILLIS))
                        .orElseThrow()
                        .clickCount())
                .as("a third press of one gesture is still a double click")
                .isEqualTo(2);
    }

    @Test
    void anUnstampedPressIsNeverTheSecondClickOfAGesture() {
        View<PointerEvent> view = target(ElementId.of("field"), "ab");
        PointerRouter router = new PointerRouter();
        assertThat(send(router, view, 4, 10, leftPress(0, 0)).orElseThrow().clickCount())
                .isEqualTo(1);
        assertThat(send(router, view, 4, 10, leftPress(0, 0)).orElseThrow().clickCount())
                .isEqualTo(1);
    }

    // ── R-01/R-02: wheel compatibility ───────────────────────────────────────

    @Test
    void aWheelNoElementTakesBecomesTheWheelGestureItUsedToBe() {
        List<String> offered = new ArrayList<>();
        View<String> view = Views.keyed(
                List.of(),
                Views.focus(
                        ElementId.of("field"),
                        true,
                        (event, region) -> {
                            offered.add("pointer");
                            return Optional.empty();
                        },
                        Views.text("x")),
                List.of(new Binding<>(Key.WHEEL_DOWN, "scrolled")));
        assertThat(send(view, wheel(PointerButton.WHEEL_DOWN, 0, 0))).contains("scrolled");
        assertThat(offered).as("the element saw the report once").containsExactly("pointer");
    }

    @Test
    void aWheelAnElementConsumesNeverAlsoReachesItsKeyBinding() {
        View<String> view = Views.keyed(
                List.of(),
                Views.focus(ElementId.of("field"), true, (event, region) -> Optional.of("consumed"), Views.text("x")),
                List.of(new Binding<>(Key.WHEEL_DOWN, "scrolled")));
        assertThat(send(view, wheel(PointerButton.WHEEL_DOWN, 0, 0))).contains("consumed");
    }

    @Test
    void aHorizontalWheelHasNoLegacyGestureToFallBackTo() {
        View<String> view = Views.focus(ElementId.of("field"), true, Views.text("x"));
        assertThat(send(view, wheel(PointerButton.WHEEL_LEFT, 0, 0))).isEmpty();
    }

    @Test
    void aPointerReportNeverRunsAKeyBinding() {
        View<String> view = Views.onKeys(
                List.of(new Binding<>(Key.ENTER, "entered")),
                Views.focus(ElementId.of("field"), true, Views.text("x")));
        assertThat(InputRouter.route(view, new InputEvent.PointerInput(leftPress(0, 0))))
                .isEmpty();
    }

    // ── R-03: the widgets ────────────────────────────────────────────────────

    @Test
    void clickingATabCellActivatesThatTab() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta")), "a");
        View<Tabs.Msg> view = tabs.view(model, CONTEXT);
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
        View<Tabs.Msg> view = tabs.view(model, CONTEXT);
        Optional<Tabs.Msg> message = send(view, leftPress(1, 11));
        assertThat(message).contains(new Tabs.Msg.Activate("b"));
        assertThat(tabs.update(model, message.orElseThrow()).model().activeId()).isEqualTo("a");
    }

    @Test
    void theWheelStepsTheTabBar() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta")), "a");
        View<Tabs.Msg> view = tabs.view(model, CONTEXT);
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
        return new RichList(RichList.Config.defaults());
    }

    private static RichList.Model listModel(int rows) {
        RichList.Model model = RichList.Model.of(ITEMS, rows);
        // Title and status are the first two rows, then the items: the geometry is what the rows
        // below assert, so nothing here depends on the help or pager rows.
        return model.withChrome(model.chrome().withPagination(false).withHelp(false));
    }

    @Test
    void clickingAListRowSelectsIt() {
        RichList widget = list();
        RichList.Model model = listModel(6);
        View<RichList.Msg> view = widget.view(model, new ViewContext(new Viewport(6, 40)));
        List<String> painted = lines(view, 6, 40);
        assertThat(painted.get(2)).contains("One");
        assertThat(painted.get(3)).contains("Two");
        assertThat(send(view, 6, 40, leftPress(2, 1))).contains(new RichList.Msg.Select("item-1"));
        assertThat(send(view, 6, 40, leftPress(3, 1))).contains(new RichList.Msg.Select("item-2"));
    }

    @Test
    void clickingAChromeRowOrABlankRowOfAListSelectsNothing() {
        RichList widget = list();
        View<RichList.Msg> view = widget.view(listModel(6), new ViewContext(new Viewport(6, 40)));
        assertThat(send(view, 6, 40, leftPress(0, 1))).as("the title").isEmpty();
        assertThat(send(view, 6, 40, leftPress(1, 1))).as("the status row").isEmpty();
        assertThat(send(view, 6, 40, leftPress(5, 1))).as("below the last item").isEmpty();
    }

    @Test
    void aSecondClickOnAListRowChoosesWhatTheFirstSelected() {
        RichList widget = list();
        RichList.Model model = listModel(6);
        View<RichList.Msg> view = widget.view(model, new ViewContext(new Viewport(6, 40)));
        PointerRouter router = new PointerRouter();
        long start = 9_000L * MILLIS;
        RichList.Msg first = send(router, view, 6, 40, leftPress(3, 1, start)).orElseThrow();
        assertThat(first).isEqualTo(new RichList.Msg.Select("item-2"));
        Update<RichList.Model, RichList.Msg, RichList.Output> selected = widget.update(model, first);
        RichList.Msg second =
                send(router, view, 6, 40, leftPress(3, 1, start + 120 * MILLIS)).orElseThrow();
        assertThat(second).isEqualTo(new RichList.Msg.Choose());
        Update<RichList.Model, RichList.Msg, RichList.Output> chosen = widget.update(selected.model(), second);
        assertThat(chosen.outputs()).containsExactly(new RichList.Output.Chosen(ITEMS.get(1)));
    }

    @Test
    void selectingTheAlreadySelectedListRowIsAValueEqualNoOp() {
        RichList widget = list();
        RichList.Model model = listModel(6);
        Update<RichList.Model, RichList.Msg, RichList.Output> update =
                widget.update(model, new RichList.Msg.Select("item-1"));
        assertThat(update.model()).isSameAs(model);
    }

    @Test
    void aTwoRowListItemIsSelectedFromEitherOfItsRows() {
        RichList widget = new RichList(RichList.Config.defaults().withRenderer(new RichList.Renderer() {
            @Override
            public int height(RichList.Item item) {
                return 2;
            }

            @Override
            public List<StyledText> render(RichList.Row row) {
                return List.of(StyledText.plain(row.item().title()), StyledText.plain("second row"));
            }
        }));
        View<RichList.Msg> view = widget.view(listModel(8), new ViewContext(new Viewport(8, 40)));
        List<String> painted = lines(view, 8, 40);
        assertThat(painted.get(2)).contains("One");
        assertThat(painted.get(3)).contains("second row");
        assertThat(send(view, 8, 40, leftPress(2, 1))).contains(new RichList.Msg.Select("item-1"));
        assertThat(send(view, 8, 40, leftPress(3, 1)))
                .as("the second painted row of the same item")
                .contains(new RichList.Msg.Select("item-1"));
        assertThat(send(view, 8, 40, leftPress(4, 1))).contains(new RichList.Msg.Select("item-2"));
    }

    @Test
    void theWheelStepsAListsSelection() {
        RichList widget = list();
        View<RichList.Msg> view = widget.view(listModel(6), new ViewContext(new Viewport(6, 40)));
        assertThat(send(view, 6, 40, wheel(PointerButton.WHEEL_DOWN, 2, 0))).contains(new RichList.Msg.Down());
        assertThat(send(view, 6, 40, wheel(PointerButton.WHEEL_UP, 2, 0))).contains(new RichList.Msg.Up());
    }

    // ── R-03: tables ─────────────────────────────────────────────────────────

    private static final DataTable.Content TABLE = DataTable.Content.of(
            List.of(DataTable.Column.of("id", "ID"), DataTable.Column.of("name", "Name")),
            List.of(DataTable.Row.of("r1", "1", "one"), DataTable.Row.of("r2", "2", "two")));

    private static DataTable table() {
        return new DataTable(DataTable.Config.defaults());
    }

    private static DataTable.Model tableModel(DataTable.Mode mode) {
        return new DataTable.Model(TABLE, new DataTable.Selection(mode, 0, 0), 0, 0, 6, 30, true);
    }

    @Test
    void clickingATableRowSelectsItAndTheHeaderSelectsNothing() {
        DataTable widget = table();
        View<DataTable.Msg> view = widget.view(tableModel(DataTable.Mode.ROW), new ViewContext(new Viewport(6, 30)));
        List<String> painted = lines(view, 6, 30);
        assertThat(painted.get(0)).contains("ID").contains("Name");
        assertThat(send(view, 6, 30, leftPress(0, 1))).as("the header row").isEmpty();
        assertThat(send(view, 6, 30, leftPress(1, 1))).contains(new DataTable.Msg.Select("r1"));
        assertThat(send(view, 6, 30, leftPress(2, 1))).contains(new DataTable.Msg.Select("r2"));
        assertThat(send(view, 6, 30, leftPress(7, 1))).as("past the content").isEmpty();
    }

    @Test
    void clickingACellwiseTableSelectsTheCellUnderThePointer() {
        DataTable widget = table();
        View<DataTable.Msg> view = widget.view(tableModel(DataTable.Mode.CELL), new ViewContext(new Viewport(6, 30)));
        String header = lines(view, 6, 30).get(1);
        int nameColumn = header.indexOf("one");
        assertThat(nameColumn).isPositive();
        assertThat(send(view, 6, 30, leftPress(1, nameColumn))).contains(new DataTable.Msg.SelectCell("r1", 1));
        assertThat(send(view, 6, 30, leftPress(1, 1))).contains(new DataTable.Msg.SelectCell("r1", 0));
    }

    @Test
    void theWheelStepsATablesSelection() {
        DataTable widget = table();
        View<DataTable.Msg> view = widget.view(tableModel(DataTable.Mode.ROW), new ViewContext(new Viewport(6, 30)));
        assertThat(send(view, 6, 30, wheel(PointerButton.WHEEL_DOWN, 1, 0))).contains(new DataTable.Msg.Down());
        assertThat(send(view, 6, 30, wheel(PointerButton.WHEEL_UP, 1, 0))).contains(new DataTable.Msg.Up());
    }

    // ── R-03: the file picker ────────────────────────────────────────────────

    private static final Path DIRECTORY = Path.of("/tmp/picker");

    private static List<FilePicker.Entry> entries() {
        return List.of(
                new FilePicker.Entry(DIRECTORY.resolve("docs"), FilePicker.Kind.DIRECTORY, -1, "", "", true, true),
                new FilePicker.Entry(DIRECTORY.resolve("notes.txt"), FilePicker.Kind.FILE, 12, "", "", true, false));
    }

    private static FilePicker picker() {
        return new FilePicker(FilePicker.Config.defaults());
    }

    private static FilePicker.Model pickerModel() {
        FilePicker.Model model = FilePicker.Model.of(DIRECTORY, entries()).withRows(8);
        return model.withExpanded(false);
    }

    @Test
    void clickingAFilePickerEntrySelectsIt() {
        FilePicker widget = picker();
        View<FilePicker.Msg> view = widget.view(pickerModel(), new ViewContext(new Viewport(8, 40)));
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
        View<FilePicker.Msg> view = widget.view(pickerModel(), new ViewContext(new Viewport(8, 40)));
        PointerRouter router = new PointerRouter();
        long start = 3_000L * MILLIS;
        assertThat(send(router, view, 8, 40, leftPress(1, 1, start))).contains(new FilePicker.Msg.Select("docs"));
        assertThat(send(router, view, 8, 40, leftPress(1, 1, start + 90 * MILLIS)))
                .as("a directory is entered")
                .contains(new FilePicker.Msg.Open());
        PointerRouter second = new PointerRouter();
        assertThat(send(second, view, 8, 40, leftPress(2, 1, start))).contains(new FilePicker.Msg.Select("notes.txt"));
        assertThat(send(second, view, 8, 40, leftPress(2, 1, start + 90 * MILLIS)))
                .as("a file is chosen")
                .contains(new FilePicker.Msg.Confirm());
    }

    @Test
    void theWheelStepsAFilePickersSelection() {
        FilePicker widget = picker();
        View<FilePicker.Msg> view = widget.view(pickerModel(), new ViewContext(new Viewport(8, 40)));
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
        View<TextViewport.Msg> view = viewport.view(model, CONTEXT);
        assertThat(send(view, 4, 20, wheel(PointerButton.WHEEL_DOWN, 1, 1))).contains(new TextViewport.Msg.Down(3));
        assertThat(send(view, 4, 20, wheel(PointerButton.WHEEL_UP, 1, 1))).contains(new TextViewport.Msg.Up(3));
        assertThat(send(view, 4, 20, leftPress(1, 1)))
                .as("a pane has no selection of its own")
                .isEmpty();
    }

    // ── R-03: the editor ─────────────────────────────────────────────────────

    private static Editor editor() {
        return new Editor(Editor.Config.defaults());
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
        View<Editor.Msg> view = editor.view(model, CONTEXT);
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
        View<Editor.Msg> view = editor.view(model, CONTEXT);
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
        Editor editor = new Editor(Editor.Config.defaults().withPrompt("> ", Style.NONE));
        Editor.Model model = draft("abc");
        View<Editor.Msg> view = editor.view(model, new ViewContext(new Viewport(3, 20)));
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
        View<Editor.Msg> view = editor.view(model, new ViewContext(new Viewport(4, 5)));
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
        View<Editor.Msg> view = editor.view(model, CONTEXT);
        PointerRouter router = new PointerRouter();
        Editor.Model pressed = placed(editor, model, send(router, view, 3, 20, leftPress(0, 1)));
        assertThat(pressed.anchor()).isEqualTo(1);
        assertThat(pressed.caret()).isEqualTo(1);
        Editor.Model dragged = placed(editor, pressed, send(router, view, 3, 20, leftDrag(0, 4)));
        assertThat(dragged.anchor())
                .as("the anchor stays where the press put it")
                .isEqualTo(1);
        assertThat(dragged.caret()).isEqualTo(4);
        assertThat(dragged.hasSelection()).isTrue();
        assertThat(send(router, view, 3, 20, leftRelease(0, 4)))
                .as("a release ends the gesture without editing anything")
                .isEmpty();
        assertThat(router.captured()).isNull();
    }

    @Test
    void aSecondClickInTheEditorSelectsTheWordUnderThePointer() {
        Editor editor = editor();
        Editor.Model model = draft("alpha beta gamma");
        View<Editor.Msg> view = editor.view(model, CONTEXT);
        long start = 7_000L * MILLIS;
        PointerRouter router = new PointerRouter();
        assertThat(send(router, view, 3, 30, leftPress(0, 7, start))).contains(new Editor.Msg.PlaceCaret(7));
        Optional<Editor.Msg> second = send(router, view, 3, 30, leftPress(0, 7, start + 80 * MILLIS));
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
        View<Editor.Msg> view = editor.view(model, CONTEXT);
        Editor.Model clicked = placed(editor, model, send(view, 3, 20, leftPress(0, 2)));
        assertThat(clicked.caret()).isEqualTo(2);
        assertThat(clicked.focused())
                .as("focus is the application's decision, not a side effect of a click")
                .isFalse();
    }

    // ── R-03: the single-line field ──────────────────────────────────────────

    private static TextInput field(TextInput.Config config) {
        return new TextInput(config);
    }

    @Test
    void clickingAFieldPutsTheCaretWhereThePointerWas() {
        TextInput widget = field(TextInput.Config.defaults());
        TextInput.Model model = TextInput.Model.of("abcdef");
        View<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        TextInput.Model clicked = widget.update(
                        model, send(view, 1, 20, leftPress(0, 2)).orElseThrow())
                .model();
        assertThat(clicked.draft().caret()).isEqualTo(2);
    }

    @Test
    void clickingAMaskedFieldCountsItsOwnClusters() {
        TextInput widget = field(TextInput.Config.defaults().withMasking(Editor.Config.Masking.PASSWORD));
        TextInput.Model model = TextInput.Model.of("abc").withFocus(false);
        View<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        assertThat(lines(view, 1, 20).get(0)).isEqualTo("\u2022\u2022\u2022");
        TextInput.Model clicked = widget.update(
                        model, send(view, 1, 20, leftPress(0, 1)).orElseThrow())
                .model();
        assertThat(clicked.draft().caret()).isEqualTo(1);
    }

    @Test
    void clickingAHiddenFieldPlacesNoCaret() {
        TextInput widget = field(TextInput.Config.defaults().withMasking(Editor.Config.Masking.HIDDEN));
        TextInput.Model model = TextInput.Model.of("abc").withFocus(false);
        View<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        assertThat(lines(view, 1, 20).get(0)).isEmpty();
        assertThat(send(view, 1, 20, leftPress(0, 1)))
                .as("a field that paints no glyph has nothing to point at")
                .isEmpty();
    }

    @Test
    void aClickOnAScrolledFieldLandsOnTheCellItPainted() {
        TextInput widget = field(TextInput.Config.defaults());
        TextInput.Model model = TextInput.Model.of("0123456789abcdefghij");
        View<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 10)));
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
        TextInput widget = field(TextInput.Config.defaults());
        TextInput.Model model = TextInput.Model.of("abcdef");
        View<TextInput.Msg> view = widget.view(model, new ViewContext(new Viewport(1, 20)));
        PointerRouter router = new PointerRouter();
        TextInput.Model pressed = widget.update(
                        model, send(router, view, 1, 20, leftPress(0, 1)).orElseThrow())
                .model();
        TextInput.Model dragged = widget.update(
                        pressed, send(router, view, 1, 20, leftDrag(0, 3)).orElseThrow())
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
        private final PointerRouter router = new PointerRouter();
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
            for (MouseDemo.Msg message : sendAll(router, scene.view(model, CONTEXT), ROWS, COLS, event)) {
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

    // ── preserved behaviour ─────────────────────────────────────────────────

    @Test
    void aFrameIsAFunctionOfItsInputsAndAHitTestChangesNothing() {
        RichList widget = list();
        View<RichList.Msg> view = widget.view(listModel(6), new ViewContext(new Viewport(6, 40)));
        List<String> first = lines(view, 6, 40);
        send(view, 6, 40, leftPress(2, 1));
        assertThat(lines(view, 6, 40)).isEqualTo(first);
    }

    @Test
    void aKeyStillReachesTheElementThatOwnsFocusWithPointerWiringInPlace() {
        Tabs tabs = new Tabs(null);
        Tabs.Model model = bar(List.of(Tabs.Tab.of("a", "Alpha"), Tabs.Tab.of("b", "Beta")), "a");
        assertThat(InputRouter.route(tabs.view(model, CONTEXT), InputEvent.KeyInput.of(Key.RIGHT)))
                .contains(new Tabs.Msg.Next());
    }

    @Test
    void anEmptyFrameDismissesEveryReport() {
        View<PointerEvent> view = Views.blank();
        assertThat(send(view, 0, 0, leftPress(0, 0))).isEmpty();
        assertThat(send(view, 5, 5, wheel(PointerButton.WHEEL_UP, 0, 0))).isEmpty();
    }
}
