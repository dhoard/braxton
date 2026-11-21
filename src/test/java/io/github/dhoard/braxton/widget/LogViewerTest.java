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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The log viewer: its lines, its windows, its reader, its themes and its sizes.
 */
class LogViewerTest {

    private final LogViewer log = new LogViewer();

    @Test
    void linesArePaintedInOrderWithTheirLevelMarkersAndTheNewestAtTheBottom() {
        LogViewer.Model model = LogViewer.Model.of(List.of(
                        LogViewer.Entry.of("a", Severity.INFO, "started"),
                        LogViewer.Entry.of("b", Severity.WARNING, "slow"),
                        LogViewer.Entry.of("c", Severity.ERROR, "failed")))
                .withRows(3);

        assertThat(ElementScreen.render(log.element(model), 3, 20).lines())
                .as("oldest first, each line a marker in its level's role and the message after it")
                .containsExactly("\u2022 started", "! slow", "\u2716 failed");
        // The view paints the same lines the element does, because they are the same leaf.
        assertThat(ElementScreen.render(log.view(model, new ViewContext(new Viewport(3, 20))), 3, 20)
                        .lines())
                .isEqualTo(ElementScreen.render(log.element(model), 3, 20).lines());

        assertThat(ElementScreen.render(log.element(model), 2, 20).lines())
                .as("a shorter pane clips the top of the window")
                .containsExactly("\u2022 started", "! slow");
        assertThat(ElementScreen.render(log.element(LogViewer.Model.empty()), 3, 20)
                        .lines())
                .as("an empty log paints nothing")
                .containsOnly("");
    }

    @Test
    void theWindowFollowsTheTailOnlyWhileTheReaderIsAtIt() {
        LogViewer viewer = new LogViewer(new LogViewer.Config(100, 3));
        LogViewer.Model model = viewer.init(new InitContext(null, null)).model();
        for (int index = 0; index < 5; index++) {
            model = apply(viewer, model, new LogViewer.Msg.Append(LogViewer.Entry.of("e" + index, "line " + index)));
        }
        assertThat(model.selected()).as("a new log starts on its newest line").isEqualTo("e4");
        assertThat(model.firstPainted()).as("the window shows the tail").isEqualTo(2);

        model = apply(viewer, apply(viewer, model, new LogViewer.Msg.Up()), new LogViewer.Msg.Up());
        assertThat(model.selected()).isEqualTo("e2");
        assertThat(model.firstPainted()).as("the window follows the reader up").isEqualTo(2);

        LogViewer.Model arrived = apply(viewer, model, new LogViewer.Msg.Append(LogViewer.Entry.of("e5", "line 5")));
        assertThat(arrived.selected())
                .as("a line arriving does not drag a reader who has scrolled back")
                .isEqualTo("e2");
        assertThat(arrived.visibleEntries()).extracting(LogViewer.Entry::id).containsExactly("e2", "e3", "e4");

        LogViewer.Model resumed =
                apply(viewer, apply(viewer, arrived, new LogViewer.Msg.End()), new LogViewer.Msg.Down());
        assertThat(resumed.selected()).isEqualTo("e5");
        resumed = apply(viewer, resumed, new LogViewer.Msg.Append(LogViewer.Entry.of("e6", "line 6")));
        assertThat(resumed.selected()).as("end resumes following the tail").isEqualTo("e6");
        assertThat(resumed.firstPainted()).isEqualTo(4);
        assertThat(apply(viewer, resumed, new LogViewer.Msg.Down()))
                .as("the newest line is the end of the list")
                .isSameAs(resumed);

        assertThat(apply(viewer, resumed, new LogViewer.Msg.Home()).firstPainted())
                .as("home shows the oldest line")
                .isZero();
    }

    @Test
    void thePageKeysMoveByAWindowAndClearEmptiesTheLog() {
        LogViewer viewer = new LogViewer(new LogViewer.Config(100, 3));
        LogViewer.Model model = model(0, 10).withRows(3);
        assertThat(model.selected()).isEqualTo("e9");

        model = apply(model, new LogViewer.Msg.PageUp());
        assertThat(model.selected()).as("a page is the window's height").isEqualTo("e6");
        assertThat(model.firstPainted()).isEqualTo(6);
        model = apply(model, new LogViewer.Msg.PageUp());
        assertThat(model.selected()).isEqualTo("e3");
        model = apply(model, new LogViewer.Msg.PageDown());
        assertThat(model.selected()).isEqualTo("e6");
        assertThat(apply(model, new LogViewer.Msg.PageDown()).selected())
                .as("a page that runs past the end lands on the newest line")
                .isEqualTo("e9");
        assertThat(apply(model.withRows(0), new LogViewer.Msg.PageUp()).selected())
                .as("a window with no rows still moves one line rather than none")
                .isEqualTo("e5");
        assertThat(apply(model, new LogViewer.Msg.Up()).selected())
                .as("the reader is still on the line the page left it on")
                .isEqualTo("e5");
        LogViewer.Model top = model.withSelected("e0");
        assertThat(apply(top, new LogViewer.Msg.Up()))
                .as("a line at the top has nowhere to go")
                .isSameAs(top);

        LogViewer.Model cleared = apply(model, new LogViewer.Msg.Clear());
        assertThat(cleared.entries()).isEmpty();
        assertThat(cleared.selected()).isEmpty();
        assertThat(cleared.firstPainted()).isZero();
        assertThat(apply(cleared, new LogViewer.Msg.Clear()))
                .as("clearing an empty log changes nothing")
                .isSameAs(cleared);
        assertThat(viewer.config().limit()).isEqualTo(100);
    }

    @Test
    void theBoundDropsTheOldestLinesAndKeepsTheSelectionOnSomethingItHolds() {
        LogViewer viewer = new LogViewer(LogViewer.Config.of(3).withRows(3));
        LogViewer.Model model = model("a", "first", "b", "second", "c", "third").withRows(3);

        model = apply(viewer, model, new LogViewer.Msg.Append(LogViewer.Entry.of("d", "fourth")));
        assertThat(model.entries())
                .extracting(LogViewer.Entry::id)
                .as("the bound keeps the newest lines")
                .containsExactly("b", "c", "d");
        assertThat(model.selected()).isEqualTo("d");

        model = apply(viewer, model.withSelected("b"), new LogViewer.Msg.Append(LogViewer.Entry.of("e", "fifth")));
        assertThat(model.entries()).extracting(LogViewer.Entry::id).containsExactly("c", "d", "e");
        assertThat(model.selected())
                .as("a reader whose line fell out of the buffer lands on the oldest line kept")
                .isEqualTo("c");

        LogViewer.Model batched = apply(
                viewer,
                model,
                new LogViewer.Msg.AppendAll(
                        List.of(LogViewer.Entry.of("f", "sixth"), LogViewer.Entry.of("g", "seventh"))));
        assertThat(batched.entries()).extracting(LogViewer.Entry::id).containsExactly("e", "f", "g");
        assertThat(batched.selected())
                .as("the reader's line fell out of the buffer in the batch, so it lands on the oldest kept")
                .isEqualTo("e");
        assertThat(apply(viewer, model, new LogViewer.Msg.AppendAll(null)))
                .as("a missing batch adds nothing")
                .isSameAs(model);
        assertThat(apply(viewer, model, new LogViewer.Msg.AppendAll(List.of()))).isSameAs(model);
        assertThat(viewer.config().rows()).isEqualTo(3);
    }

    @Test
    void theKeysWheelAndPressesReachTheFocusedLog() {
        LogViewer.Model model = model(0, 4).withRows(3);
        Element<LogViewer.Msg> root = log.element(model);

        assertThat(route(root, "up")).containsExactly(new LogViewer.Msg.Up());
        assertThat(route(root, "down")).containsExactly(new LogViewer.Msg.Down());
        assertThat(route(root, "pageup")).containsExactly(new LogViewer.Msg.PageUp());
        assertThat(route(root, "pagedown")).containsExactly(new LogViewer.Msg.PageDown());
        assertThat(route(root, "home")).containsExactly(new LogViewer.Msg.Home());
        assertThat(route(root, "end")).containsExactly(new LogViewer.Msg.End());
        assertThat(route(root, "enter")).containsExactly(new LogViewer.Msg.Activate());
        assertThat(route(root, "x"))
                .as("a key the log does not own falls through to the application")
                .isEmpty();
        assertThat(log.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly(
                        "previous-line", "next-line", "previous-page", "next-page", "oldest", "newest", "open");

        // The window shows e1, e2, e3 in rows 0 to 2.
        assertThat(press(root, 3, 20, 0, 4))
                .as("a press on a painted line selects it")
                .containsExactly(new LogViewer.Msg.Select("e1"));
        assertThat(press(root, 3, 20, 2, 4))
                .as("a press on the selected line chooses it")
                .containsExactly(new LogViewer.Msg.Activate());
        assertThat(press(root, 3, 20, 3, 0))
                .as("a press below the painted window is not the log's")
                .isEmpty();

        assertThat(wheel(root, 3, 20, PointerButton.WHEEL_UP)).containsExactly(new LogViewer.Msg.Up());
        assertThat(wheel(root, 3, 20, PointerButton.WHEEL_DOWN)).containsExactly(new LogViewer.Msg.Down());
        assertThat(wheel(root, 3, 20, PointerButton.WHEEL_LEFT)).isEmpty();

        // A log with no lines is not a focus stop and answers nothing.
        Element<LogViewer.Msg> blank = log.element(LogViewer.Model.empty());
        assertThat(ElementScreen.render(blank, 3, 20).focusables()).isEmpty();
        assertThat(press(blank, 3, 20, 0, 0)).isEmpty();
    }

    @Test
    void aLineIsPaintedInItsLevelsStyleAndTheSelectedLineIsHighlighted() {
        LogViewer.Model model = LogViewer.Model.of(List.of(
                        LogViewer.Entry.of("a", Severity.INFO, "started"),
                        LogViewer.Entry.of("b", Severity.WARNING, "slow"),
                        LogViewer.Entry.of("c", Severity.ERROR, "failed")))
                .withRows(3);
        ElementScreen dark = ElementScreen.render(log.element(model), 3, 20, Themes.DARK);
        ElementScreen light = ElementScreen.render(log.element(model), 3, 20, Themes.LIGHT);
        ElementScreen idle = ElementScreen.render(log.element(model.withFocus(false)), 3, 20, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("an informational marker is the accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(0).get(2))
                .as("a message is the text style")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(dark.styles(1).get(0))
                .as("a warning marker is the warning role")
                .isEqualTo(Themes.DARK.style(ThemeToken.WARNING));
        assertThat(dark.styles(2).get(0))
                .as("the selected line's error marker keeps its role over the selection and takes the focus")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.ERROR)
                        .merge(Themes.DARK.style(ThemeToken.SELECTED))
                        .merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(2).get(2))
                .as("the selected line's message is the text style over the selection with focus")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.TEXT)
                        .merge(Themes.DARK.style(ThemeToken.SELECTED))
                        .merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(2).get(19))
                .as("the selected line is filled across the whole width")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(idle.styles(2).get(0))
                .as("an unfocused log keeps the selection and drops the focus")
                .isEqualTo(Themes.DARK.style(ThemeToken.ERROR).merge(Themes.DARK.style(ThemeToken.SELECTED)));
        assertThat(light.styles(2).get(19))
                .as("a live theme switch repaints the selection")
                .isEqualTo(Themes.LIGHT.style(ThemeToken.SELECTED))
                .isNotEqualTo(dark.styles(2).get(19));
    }

    @Test
    void aNarrowPaneClipsOneLongLineRatherThanPushingTheOthersOut() {
        LogViewer.Model model = model("a", "started", "b", "x".repeat(40)).withRows(2);

        assertThat(ElementScreen.render(log.element(model), 2, 10).lines())
                .as("a line wider than the pane is clipped at the pane's edge")
                .containsExactly("\u2022 started", "\u2022 xxxxxxxx");
        assertThat(ElementScreen.render(log.element(model), 0, 10).lines()).isEmpty();
        assertThat(ElementScreen.render(log.element(model), 1, 0).lines()).hasSize(1);
    }

    @Test
    void modelsAreNormalizedAndValidated() {
        LogViewer.Model empty = LogViewer.Model.of(null);
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.selected()).isEmpty();
        assertThat(empty.selectedEntry()).isEmpty();
        assertThat(empty.find(null)).isEmpty();
        assertThat(empty.withRows(-4).rows()).isZero();
        assertThat(empty.withEntries(null).entries()).isEmpty();
        assertThat(LogViewer.Model.of(List.of(entry("a"))).withSelected("gone").selected())
                .as("an unknown selection resolves to the newest line")
                .isEqualTo("a");
        assertThat(new LogViewer.Model(List.of(entry("a")), "a", true, 9, 2).firstPainted())
                .as("an offset past the end is admitted to the last window the lines allow")
                .isZero();
        assertThatThrownBy(() -> LogViewer.Model.of(List.of(entry("a"), entry("a"))))
                .as("a duplicated id is an application error, not a refresh")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate entry id: a");

        assertThatThrownBy(() -> new LogViewer.Config(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogViewer.Config(3, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LogViewer.Config.of(3).withLimit(4).limit()).isEqualTo(4);
        assertThat(LogViewer.Config.defaults().limit()).isEqualTo(LogViewer.DEFAULT_LIMIT);
        assertThat(LogViewer.Config.defaults().rows()).isEqualTo(LogViewer.DEFAULT_ROWS);
        assertThat(log.config()).isEqualTo(LogViewer.Config.defaults());

        assertThatThrownBy(() -> LogViewer.Entry.of(" ", "message")).isInstanceOf(IllegalArgumentException.class);
        assertThat(LogViewer.Entry.of("a", null, null).severity()).isEqualTo(Severity.INFO);
        assertThat(LogViewer.Entry.of("a", null, null).text().plainText()).isEmpty();
        assertThat(entry("a").withSeverity(Severity.SUCCESS).severity()).isEqualTo(Severity.SUCCESS);
        assertThat(entry("a").withSeverity(null).severity()).isEqualTo(Severity.INFO);
        assertThat(entry("a").withText(null).text().plainText()).isEmpty();
        assertThatThrownBy(() -> new LogViewer.Msg.Append(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> apply(
                        LogViewer.Model.empty(),
                        new LogViewer.Msg.AppendAll(java.util.Arrays.asList(entry("a"), null))))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new LogViewer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> log.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> log.update(null, new LogViewer.Msg.Up())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> log.update(empty, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> log.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> log.element(null)).isInstanceOf(NullPointerException.class);
        assertThat(log.init(new InitContext(null, null)).model().rows())
                .as("an initialized log takes the window height from its configuration")
                .isEqualTo(LogViewer.DEFAULT_ROWS);
    }

    private LogViewer.Model apply(LogViewer.Model model, LogViewer.Msg message) {
        return apply(log, model, message);
    }

    private static LogViewer.Model apply(LogViewer viewer, LogViewer.Model model, LogViewer.Msg message) {
        return viewer.update(model, message).model();
    }

    private List<LogViewer.Msg> route(Element<LogViewer.Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                log.keys().bindings());
    }

    private static List<LogViewer.Msg> press(Element<LogViewer.Msg> root, int rows, int cols, int row, int col) {
        return report(root, rows, cols, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col));
    }

    private static List<LogViewer.Msg> wheel(Element<LogViewer.Msg> root, int rows, int cols, PointerButton direction) {
        return report(root, rows, cols, PointerEvent.wheel(direction, Modifiers.NONE, 0, 0));
    }

    private static List<LogViewer.Msg> report(Element<LogViewer.Msg> root, int rows, int cols, PointerEvent event) {
        List<LogViewer.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<LogViewer.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, event, recorded::add);
        return recorded;
    }

    private static LogViewer.Model model(String... fields) {
        List<LogViewer.Entry> entries = new ArrayList<>(fields.length / 2);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            entries.add(LogViewer.Entry.of(fields[index], fields[index + 1]));
        }
        return LogViewer.Model.of(entries);
    }

    private static LogViewer.Model model(int first, int count) {
        List<LogViewer.Entry> entries = new ArrayList<>(count);
        for (int index = first; index < first + count; index++) {
            entries.add(LogViewer.Entry.of("e" + index, "line " + index));
        }
        return LogViewer.Model.of(entries);
    }

    private static LogViewer.Entry entry(String id) {
        return LogViewer.Entry.of(id, "line " + id);
    }
}
