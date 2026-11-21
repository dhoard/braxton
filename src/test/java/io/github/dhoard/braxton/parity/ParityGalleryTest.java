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

import examples.parity.GalleryState;
import examples.parity.WidgetGalleryDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.testing.Routing;
import io.github.dhoard.braxton.widget.BarChart;
import io.github.dhoard.braxton.widget.Calendar;
import io.github.dhoard.braxton.widget.Checkbox;
import io.github.dhoard.braxton.widget.CommandPalette;
import io.github.dhoard.braxton.widget.Dialog;
import io.github.dhoard.braxton.widget.Form;
import io.github.dhoard.braxton.widget.Gauge;
import io.github.dhoard.braxton.widget.LineChart;
import io.github.dhoard.braxton.widget.LogViewer;
import io.github.dhoard.braxton.widget.Menu;
import io.github.dhoard.braxton.widget.MultiSelect;
import io.github.dhoard.braxton.widget.NotificationCenter;
import io.github.dhoard.braxton.widget.RadioGroup;
import io.github.dhoard.braxton.widget.Scrollbar;
import io.github.dhoard.braxton.widget.Select;
import io.github.dhoard.braxton.widget.Sparkline;
import io.github.dhoard.braxton.widget.SplitPane;
import io.github.dhoard.braxton.widget.Toast;
import io.github.dhoard.braxton.widget.Tree;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 28 acceptance: the widget gallery.
 *
 * <p>The milestone's own suites prove one widget at a time; this one proves the composed scene. It is
 * asserted where a reader reads it - the status rows and the painted page - rather than through the
 * scene's internals, so a specimen that stops painting, a focus flag that stops moving or a gesture
 * that lands on the wrong widget is a failing assertion here.</p>
 *
 * <p>Every key is routed the way the runtime routes it, through {@link Routing}, so what is under
 * test is the same three tiers the session uses: none of these cases calls a widget directly.</p>
 */
class ParityGalleryTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static ComponentDriver<GalleryState, WidgetGalleryDemo.Msg, Never> gallery() {
        return ComponentDriver.initialize(new WidgetGalleryDemo.Scene(), new InitContext(new Viewport(24, 80), null));
    }

    private static List<String> lines(ComponentDriver<GalleryState, WidgetGalleryDemo.Msg, ?> driver) {
        return ElementScreen.render(
                        driver.view(CONTEXT),
                        CONTEXT.viewport().rows(),
                        CONTEXT.viewport().cols())
                .lines();
    }

    /**
     * Presses one gesture and lets every message the router produced arrive.
     */
    private static void press(ComponentDriver<GalleryState, WidgetGalleryDemo.Msg, Never> driver, String spec) {
        send(driver, InputEvent.KeyInput.of(Key.parse(spec)));
    }

    /**
     * Types one printable key, which carries the text an emacs-style decoder would report.
     */
    private static void type(ComponentDriver<GalleryState, WidgetGalleryDemo.Msg, Never> driver, String text) {
        send(driver, InputEvent.KeyInput.of(Key.parse(text), text));
    }

    private static void send(ComponentDriver<GalleryState, WidgetGalleryDemo.Msg, Never> driver, InputEvent event) {
        for (WidgetGalleryDemo.Msg message : Routing.route(driver.component(), driver.model(), CONTEXT, event)) {
            driver.send(message);
        }
    }

    private static void show(ComponentDriver<GalleryState, WidgetGalleryDemo.Msg, Never> driver, int page) {
        driver.send(new WidgetGalleryDemo.Msg.PageChosen(page));
    }

    // ── coverage ───────────────────────────────────────────────────────────

    /**
     * The gallery's coverage claim, as a statement about the milestone rather than about this file:
     * every widget the plan lists for M4 is placed by some page, and every name the pages hold is one
     * of them.
     */
    @Test
    void theGalleryPlacesEveryMilestoneFourWidget() {
        assertThat(WidgetGalleryDemo.widgets().values())
                .as("one entry per milestone-4 widget")
                .containsExactlyInAnyOrder(
                        Select.class,
                        MultiSelect.class,
                        Checkbox.class,
                        RadioGroup.class,
                        Form.class,
                        Tree.class,
                        Menu.class,
                        CommandPalette.class,
                        Scrollbar.class,
                        SplitPane.class,
                        Dialog.class,
                        Toast.class,
                        NotificationCenter.class,
                        LineChart.class,
                        BarChart.class,
                        Sparkline.class,
                        Gauge.class,
                        Calendar.class,
                        LogViewer.class);

        Set<String> painted = new HashSet<>();
        for (WidgetGalleryDemo.Page page : WidgetGalleryDemo.Page.values()) {
            painted.addAll(page.names());
        }
        // The two specimens that own no keys - or that the scene drives - are named outside a focus
        // walk, so they are added here rather than by a page.
        painted.addAll(Set.of("toast", "dialog", "gauge"));
        assertThat(painted)
                .as("every placed widget is named by a page, and nothing else is")
                .isEqualTo(WidgetGalleryDemo.widgets().keySet());
    }

    // ── the pages ──────────────────────────────────────────────────────────

    /**
     * Each page paints its own specimens, and its status rows say which ones they are.
     */
    @Test
    void everyPagePaintsItsSpecimensAndNamesThem() {
        assertThat(pageLines(0).getFirst()).isEqualTo(WidgetGalleryDemo.TITLE);
        assertThat(pageLines(0).subList(1, 4))
                .containsExactly(
                        "gallery page=1/4 choice widgets=5 names=select,tags,remember,density,form",
                        "focus=select last=page-choice reports=1",
                        "select=standard tags=design remember=off density=compact form=name");
        assertThat(pageLines(0))
                .as("the choice controls")
                .anySatisfy(line -> assertThat(line).contains("Standard"))
                .anySatisfy(line -> assertThat(line).contains("remember this page"))
                .anySatisfy(line -> assertThat(line).contains("Compact"))
                .anySatisfy(line -> assertThat(line).contains("Name"))
                .anySatisfy(line -> assertThat(line).contains("Email"));

        assertThat(pageLines(1).subList(1, 4))
                .containsExactly(
                        "gallery page=2/4 navigate widgets=5 names=tree,bar,split,menu,palette",
                        "focus=tree last=page-navigate reports=1",
                        "tree=ui bar=14/14 menu=open palette=- split=26");
        assertThat(pageLines(1))
                .as("the navigation widgets")
                .anySatisfy(line -> assertThat(line).contains("UI kernel"))
                .anySatisfy(line -> assertThat(line).contains("Open"))
                .anySatisfy(line -> assertThat(line).contains("Switch theme"));

        assertThat(pageLines(2).subList(1, 4))
                .containsExactly(
                        "gallery page=3/4 message widgets=2 names=notices,log",
                        "focus=notices last=page-message reports=1",
                        "notices=1/3 unread=3 log=l20 at=14 lines=20 toast=hidden dialog=closed");
        assertThat(pageLines(2))
                .as("the message surfaces")
                .anySatisfy(line -> assertThat(line).contains("every widget painted"))
                .anySatisfy(line -> assertThat(line).contains("gallery line 20"));

        assertThat(pageLines(3).subList(1, 4))
                .containsExactly(
                        "gallery page=4/4 data widgets=4 names=line,bars,spark,calendar",
                        "focus=line last=page-data reports=1",
                        "line=8/8 bars=plan spark=8/8 gauge=0.62 date=2026-03-14");
        assertThat(pageLines(3))
                .as("the data views")
                .anySatisfy(line -> assertThat(line).contains("requests"))
                .anySatisfy(line -> assertThat(line).contains("budget"))
                .anySatisfy(line -> assertThat(line).contains("ship"))
                .anySatisfy(line -> assertThat(line).contains("March 2026"));
    }

    // ── the choice controls ────────────────────────────────────────────────

    /**
     * One page, five specimens, and one keyboard: a gesture reaches the specimen whose model says it
     * is focused, and {@code tab} is what moves that fact from one model to the next.
     */
    @Test
    void theChoicePageHandsTheKeyboardToItsFocusedSpecimen() {
        var driver = gallery();

        press(driver, "down");
        assertThat(driver.model().revision().selected())
                .as("a closed select's arrow chooses")
                .isEqualTo("rich");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=select-chosen");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=tags");
        press(driver, "space");
        press(driver, "down");
        press(driver, "space");
        assertThat(driver.model().tags().chosen())
                .as("marks accumulate and the whole set is reported")
                .containsExactly("design", "runtime");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=remember");
        press(driver, "space");
        assertThat(driver.model().remember().checked()).isTrue();
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=remember-changed");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=density");
        press(driver, "down");
        assertThat(driver.model().density().selected()).isEqualTo("comfortable");

        // The gallery's own map is the last tier, so it answers only what a specimen declined.
        press(driver, "shift+tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=remember");
        press(driver, "q");
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, InputEvent.KeyInput.of(Key.parse("q")))
                        .stream()
                        .findFirst())
                .contains(new WidgetGalleryDemo.Msg.Quit());
    }

    /**
     * The form keeps the two editors, the gallery keeps their focus, and a submission is one message:
     * the rejected case paints the error row the form owns, and the accepted case reports the values.
     */
    @Test
    void theFormValidatesAPastedAddressAndThenSubmits() {
        var driver = gallery();
        for (int step = 0; step < 4; step++) {
            press(driver, "tab");
        }
        assertThat(WidgetGalleryDemo.turn(driver.model()))
                .as("tab walks to the form, whose editors belong to the scene")
                .contains("focus=form");
        assertThat(driver.model().name().focused()).isTrue();

        press(driver, "ctrl+s");
        assertThat(driver.model().form().errors())
                .as("the seeded address has no @")
                .hasSize(1);
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=form-rejected");
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("an address needs an @"));

        // The form's own focus names the field, and the scene is what gives that field the keyboard.
        driver.send(new WidgetGalleryDemo.Msg.FormMessage(new Form.Msg.Focus("email")));
        assertThat(driver.model().email().focused()).isTrue();
        assertThat(driver.model().name().focused()).isFalse();
        send(driver, new InputEvent.PasteInput("@example"));
        assertThat(driver.model().email().text())
                .as("a paste reaches the mapped editor as text")
                .isEqualTo("ada.example@example");

        press(driver, "ctrl+s");
        assertThat(driver.model().form().errors()).isEmpty();
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=form-submitted");
    }

    // ── the navigation widgets ─────────────────────────────────────────────

    /**
     * The tree, the bar that scrolls the log, the split between the panes, the menu and the palette:
     * each answers the gesture its own model is focused for, and the scene's published row follows.
     */
    @Test
    void theNavigationPageMovesItsPanesAndChoosesFromItsLists() {
        var driver = gallery();
        show(driver, 1);

        press(driver, "down");
        assertThat(driver.model().tree().selected()).isEqualTo("ui.element");
        press(driver, "down");
        press(driver, "down");
        assertThat(driver.model().tree().selected()).isEqualTo("widgets");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=bar");
        press(driver, "down");
        assertThat(WidgetGalleryDemo.turn(driver.model()))
                .as("the standalone bar scrolls the log's own window")
                .contains("last=bar-14");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=split");
        press(driver, "right");
        assertThat(driver.model().splitFirst()).as("the divider moved one cell").isEqualTo(27);

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=menu");
        press(driver, "down");
        assertThat(driver.model().menu().highlighted()).isEqualTo("rename");
        press(driver, "enter");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=menu-chosen");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=palette");
        type(driver, "s");
        type(driver, "o");
        assertThat(driver.model().palette().query()).isEqualTo("so");
        assertThat(WidgetGalleryDemo.published(driver.model())).contains("palette=so");
        press(driver, "enter");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=palette-chosen");
    }

    // ── the message surfaces ───────────────────────────────────────────────

    /**
     * The log follows its tail until the reader leaves it, the notifications mark what a selection
     * gesture lands on read, and a toast shows, subscribes exactly once and logs the gesture.
     */
    @Test
    void theMessagePageFollowsTheLogAndShowsTheToast() {
        var driver = gallery();
        show(driver, 2);
        assertThat(Subscription.leaves(driver.subscriptions()))
                .as("one size source and no timer while the toast is hidden")
                .hasSize(1);

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=log");
        press(driver, "home");
        assertThat(driver.model().log().selected()).isEqualTo("l1");
        assertThat(WidgetGalleryDemo.published(driver.model()))
                .as("the window follows the reader to the first line")
                .contains("log=l1 at=0");
        press(driver, "enter");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=log-chosen");

        press(driver, "shift+tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=notices");
        press(driver, "down");
        assertThat(WidgetGalleryDemo.published(driver.model()))
                .as("the row a selection gesture lands on is read")
                .contains("notices=2/3 unread=2");

        press(driver, "ctrl+g");
        assertThat(driver.model().toast().showing()).isTrue();
        assertThat(WidgetGalleryDemo.published(driver.model())).contains("toast=shown");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=toast-shown");
        assertThat(Subscription.leaves(driver.subscriptions()))
                .as("a shown toast is the one specimen with a timer")
                .hasSize(2);

        // A toast owns no keys and no focus, so a gesture still belongs to the log behind it and the
        // toast stays exactly where it is.
        press(driver, "tab");
        press(driver, "end");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=log");
        assertThat(driver.model().toast().showing()).isTrue();
        assertThat(driver.model().log().selected()).isEqualTo("l20");
    }

    // ── the data views ─────────────────────────────────────────────────────

    /**
     * Every reader reports the sample it lands on, and the calendar reports only the date it chose.
     */
    @Test
    void theDataPageMovesEveryReaderAndChoosesADate() {
        var driver = gallery();
        show(driver, 3);

        press(driver, "left");
        assertThat(driver.model().line().selected()).isEqualTo(6);
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=line-inspected");
        assertThat(WidgetGalleryDemo.published(driver.model())).contains("line=7/8");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=bars");
        press(driver, "end");
        assertThat(driver.model().bars().selected()).isEqualTo("ship");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=bars-inspected");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=spark");
        press(driver, "left");
        assertThat(driver.model().spark().selected()).isEqualTo(6);
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=spark-inspected");

        press(driver, "tab");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=calendar");
        press(driver, "right");
        assertThat(WidgetGalleryDemo.published(driver.model()))
                .as("the shown month and the selection move together")
                .contains("date=2026-03-15");
        assertThat(WidgetGalleryDemo.turn(driver.model()))
                .as("a picker's steps are where the user is, so nothing is reported")
                .contains("last=calendar");
        press(driver, "enter");
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=calendar-chosen");
    }

    // ── the dialog ─────────────────────────────────────────────────────────

    /**
     * The dialog is a modal layer over the page: it takes the keyboard when it opens, answers the
     * cancel itself, and gives the keyboard back to the page when it goes.
     */
    @Test
    void theDialogIsAModalLayerThatGivesTheKeyboardBack() {
        var driver = gallery();
        press(driver, "ctrl+d");
        assertThat(driver.model().dialogOpen()).isTrue();
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("focus=dialog");
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("apply the gallery change?"));

        // The layer covers the page: the select behind it does not take the arrow.
        press(driver, "down");
        assertThat(driver.model().revision().selected())
                .as("the page under a modal layer is unreachable")
                .isEqualTo("standard");
        assertThat(driver.model().dialog().focusedAction()).isEqualTo("apply");

        press(driver, "right");
        assertThat(driver.model().dialog().focusedAction()).isEqualTo("cancel");
        press(driver, "esc");
        assertThat(driver.model().dialogOpen()).isFalse();
        assertThat(WidgetGalleryDemo.turn(driver.model())).contains("last=dialog-canceled");
        assertThat(WidgetGalleryDemo.turn(driver.model()))
                .as("the keyboard returns to the page")
                .contains("focus=select");
    }

    // ── the boundaries ─────────────────────────────────────────────────────

    /**
     * A terminal too small for the page clips it rather than throwing or painting outside it.
     */
    @Test
    void aNarrowAndShortTerminalStillPaints() {
        for (int rows : new int[] {10, 4, 1}) {
            for (int cols : new int[] {30, 12, 1}) {
                for (int page = 0; page < 4; page++) {
                    assertPaintsWithin(rows, cols, page);
                }
            }
        }
    }

    /**
     * Every page fits the terminal it is given, one cell at a time.
     */
    private static void assertPaintsWithin(int rows, int cols, int page) {
        var driver = gallery();
        show(driver, page);
        List<String> lines =
                ElementScreen.render(driver.view(CONTEXT), rows, cols).lines();
        assertThat(lines).as("%dx%d page %d", rows, cols, page).hasSize(rows);
        assertThat(lines)
                .allSatisfy(line -> assertThat(line.length())
                        .as("%dx%d page %d line [%s]", rows, cols, page, line)
                        .isLessThanOrEqualTo(cols));
    }

    /**
     * Quitting is the scene's own gesture, and it is a quit request like any other.
     */
    @Test
    void quittingIsTheScenesOwnGesture() {
        var driver = gallery();
        Update<GalleryState, WidgetGalleryDemo.Msg, Never> quitted = driver.send(new WidgetGalleryDemo.Msg.Quit());
        assertThat(quitted.command()).isEqualTo(Command.quit());
        assertThat(quitted.outputs()).isEmpty();
    }

    /**
     * A fresh gallery on one page, as the rows it paints.
     */
    private static List<String> pageLines(int page) {
        var driver = gallery();
        show(driver, page);
        return lines(driver);
    }
}
