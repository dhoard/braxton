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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The calendar: its month grid, its reader, its themes and its sizes.
 */
class CalendarTest {

    private static final LocalDate NOVEMBER_18 = LocalDate.of(2026, 11, 18);

    private final Calendar calendar = new Calendar();

    @Test
    void aMonthPaintsItsHeaderWeekdayNamesAndDays() {
        Calendar.Model model = Calendar.Model.of(NOVEMBER_18);

        assertThat(ElementScreen.render(calendar.element(model), 8, 20).lines())
                .as("a two-row header and the six weeks of November 2026, Monday first")
                .containsExactly(
                        "November 2026",
                        "Mo Tu We Th Fr Sa Su",
                        " ".repeat(18) + " 1",
                        " 2  3  4  5  6  7  8",
                        " 9 10 11 12 13 14 15",
                        "16 17 18 19 20 21 22",
                        "23 24 25 26 27 28 29",
                        "30");
        // The view paints the same grid the element does, because they are the same leaf.
        assertThat(ElementScreen.render(calendar.view(model, new ViewContext(new Viewport(8, 20))), 8, 20)
                        .lines())
                .isEqualTo(ElementScreen.render(calendar.element(model), 8, 20).lines());

        Calendar sundayFirst = new Calendar(Calendar.Config.defaults().withFirstDayOfWeek(DayOfWeek.SUNDAY));
        assertThat(ElementScreen.render(sundayFirst.element(model), 7, 20).lines())
                .as("a week that starts on Sunday starts the grid on the first and needs five weeks")
                .containsExactly(
                        "November 2026",
                        "Su Mo Tu We Th Fr Sa",
                        " 1  2  3  4  5  6  7",
                        " 8  9 10 11 12 13 14",
                        "15 16 17 18 19 20 21",
                        "22 23 24 25 26 27 28",
                        "29 30");

        assertThat(Calendar.weeks(YearMonth.of(2026, 2), DayOfWeek.MONDAY))
                .as("February 2026 starts on a Sunday, so its month-first day is in the sixth column")
                .isEqualTo(5);
        assertThat(Calendar.weeks(YearMonth.of(2026, 8), DayOfWeek.MONDAY))
                .as("a thirty-one day month that starts on a Saturday needs six rows")
                .isEqualTo(6);
        assertThat(Calendar.gridStart(YearMonth.of(2026, 2), DayOfWeek.MONDAY)).isEqualTo(LocalDate.of(2026, 1, 26));
        assertThat(Calendar.gridStart(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void theSelectionMovesADayAWeekOrAMonthAndCrossesBoundaries() {
        assertThat(apply(Calendar.Model.of(NOVEMBER_18), new Calendar.Msg.Right())
                        .selected())
                .isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(apply(Calendar.Model.of(NOVEMBER_18), new Calendar.Msg.Left())
                        .selected())
                .isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(apply(Calendar.Model.of(NOVEMBER_18), new Calendar.Msg.Up()).selected())
                .isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(apply(Calendar.Model.of(NOVEMBER_18), new Calendar.Msg.Down())
                        .selected())
                .isEqualTo(LocalDate.of(2026, 11, 25));

        assertThat(apply(Calendar.Model.of(LocalDate.of(2026, 11, 30)), new Calendar.Msg.Right())
                        .selected())
                .as("a day move past the end of the month shows the month it lands in")
                .isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(apply(Calendar.Model.of(LocalDate.of(2026, 12, 1)), new Calendar.Msg.Left())
                        .selected())
                .isEqualTo(LocalDate.of(2026, 11, 30));

        Calendar.Model january = apply(Calendar.Model.of(LocalDate.of(2026, 1, 31)), new Calendar.Msg.NextMonth());
        assertThat(january.selected())
                .as("the thirty-first of a month is clamped to the target month's length")
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(january.month()).contains(YearMonth.of(2026, 2));
        assertThat(apply(january, new Calendar.Msg.PreviousMonth()).selected())
                .as("and the day is not restored on the way back, because the model holds one date")
                .isEqualTo(LocalDate.of(2026, 1, 28));
        assertThat(apply(Calendar.Model.of(LocalDate.of(2024, 3, 31)), new Calendar.Msg.PreviousMonth())
                        .selected())
                .as("a leap February keeps the day it has")
                .isEqualTo(LocalDate.of(2024, 2, 29));

        assertThat(apply(Calendar.Model.of(NOVEMBER_18), new Calendar.Msg.FirstOfMonth())
                        .selected())
                .isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(apply(Calendar.Model.of(NOVEMBER_18), new Calendar.Msg.LastOfMonth())
                        .selected())
                .isEqualTo(LocalDate.of(2026, 11, 30));

        Calendar.Model empty = Calendar.Model.empty();
        for (Calendar.Msg message : List.of(
                new Calendar.Msg.Left(),
                new Calendar.Msg.Right(),
                new Calendar.Msg.Up(),
                new Calendar.Msg.Down(),
                new Calendar.Msg.PreviousMonth(),
                new Calendar.Msg.NextMonth(),
                new Calendar.Msg.FirstOfMonth(),
                new Calendar.Msg.LastOfMonth())) {
            assertThat(calendar.update(empty, message).model())
                    .as("a calendar with no date answers %s without inventing one", message)
                    .isSameAs(empty);
        }
    }

    @Test
    void onlyChoosingTheDateReportsAnOutput() {
        Calendar.Model model = Calendar.Model.of(NOVEMBER_18);

        assertThat(calendar.update(model, new Calendar.Msg.Activate()).outputs())
                .containsExactly(new Calendar.Output.Chosen(NOVEMBER_18));
        assertThat(calendar.update(model, new Calendar.Msg.Right()).outputs())
                .as("a move changes the selection without reporting a date the user did not choose")
                .isEmpty();
        assertThat(calendar.update(Calendar.Model.empty(), new Calendar.Msg.Activate())
                        .outputs())
                .isEmpty();
        assertThat(apply(model, new Calendar.Msg.Select(NOVEMBER_18)))
                .as("selecting the date already selected changes nothing")
                .isSameAs(model);
        assertThat(apply(model, new Calendar.Msg.Select(null))).isSameAs(model);
        assertThat(apply(model, new Calendar.Msg.Select(LocalDate.of(2027, 3, 9))))
                .as("selecting a date from another month shows that date's month")
                .isEqualTo(Calendar.Model.of(LocalDate.of(2027, 3, 9)));
    }

    @Test
    void theKeysAndPressesReachTheFocusedCalendar() {
        Calendar.Model model = Calendar.Model.of(NOVEMBER_18);
        Element<Calendar.Msg> root = calendar.element(model);

        assertThat(route(root, "left")).containsExactly(new Calendar.Msg.Left());
        assertThat(route(root, "right")).containsExactly(new Calendar.Msg.Right());
        assertThat(route(root, "up")).containsExactly(new Calendar.Msg.Up());
        assertThat(route(root, "down")).containsExactly(new Calendar.Msg.Down());
        assertThat(route(root, "pageup")).containsExactly(new Calendar.Msg.PreviousMonth());
        assertThat(route(root, "pagedown")).containsExactly(new Calendar.Msg.NextMonth());
        assertThat(route(root, "home")).containsExactly(new Calendar.Msg.FirstOfMonth());
        assertThat(route(root, "end")).containsExactly(new Calendar.Msg.LastOfMonth());
        assertThat(route(root, "enter")).containsExactly(new Calendar.Msg.Activate());
        assertThat(route(root, "space")).containsExactly(new Calendar.Msg.Activate());
        assertThat(route(root, "x"))
                .as("a key the calendar does not own falls through to the application")
                .isEmpty();

        assertThat(calendar.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly(
                        "previous-day",
                        "next-day",
                        "previous-week",
                        "next-week",
                        "previous-month",
                        "next-month",
                        "first-day",
                        "last-day",
                        "choose");

        // November 2026 starts on a Sunday, so its first Monday-first week paints a single cell.
        assertThat(press(root, 8, 20, 3, 0))
                .as("a press on the second day of the month selects it")
                .containsExactly(new Calendar.Msg.Select(LocalDate.of(2026, 11, 2)));
        assertThat(press(root, 8, 20, 5, 6))
                .as("a press on the selected day chooses it")
                .containsExactly(new Calendar.Msg.Activate());
        assertThat(press(root, 8, 20, 3, 18))
                .as("the last day of a week is in the last cell of the row")
                .containsExactly(new Calendar.Msg.Select(LocalDate.of(2026, 11, 8)));
        assertThat(press(root, 8, 20, 0, 0))
                .as("the month header is not a target")
                .isEmpty();
        assertThat(press(root, 8, 20, 1, 0))
                .as("the weekday row is not a target")
                .isEmpty();
        assertThat(press(root, 8, 20, 2, 0))
                .as("a blank cell before the first of the month is not a target")
                .isEmpty();
        assertThat(press(root, 8, 20, 7, 3))
                .as("a cell that belongs to the next month is not a target")
                .isEmpty();

        // A calendar with no date is not a focus stop and answers nothing.
        Element<Calendar.Msg> blank = calendar.element(Calendar.Model.empty());
        assertThat(ElementScreen.render(blank, 8, 20).lines())
                .as("an empty calendar paints no cells")
                .containsOnly("");
        assertThat(ElementScreen.render(blank, 8, 20).focusables()).isEmpty();
        assertThat(press(blank, 8, 20, 3, 0)).isEmpty();
    }

    @Test
    void theMonthTheWeekdayRowAndTheSelectedDayArePaintedInTheThemesStyles() {
        Calendar.Model model = Calendar.Model.of(NOVEMBER_18);
        ElementScreen dark = ElementScreen.render(calendar.element(model), 8, 20, Themes.DARK);
        ElementScreen light = ElementScreen.render(calendar.element(model), 8, 20, Themes.LIGHT);
        ElementScreen idle = ElementScreen.render(calendar.element(model.withFocus(false)), 8, 20, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("the month header is the accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(1).get(0))
                .as("the weekday names are muted")
                .isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(3).get(0))
                .as("a weekday is the text style")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(dark.styles(3).get(15)).as("a Saturday is muted").isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(3).get(18)).as("a Sunday is muted").isEqualTo(Themes.DARK.style(ThemeToken.MUTED));

        // November 18 is the third column of the fourth week: row 5, column 6.
        assertThat(dark.styles(5).get(6))
                .as("the selected day's number is the text style over the selection with focus")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.TEXT)
                        .merge(Themes.DARK.style(ThemeToken.SELECTED))
                        .merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(5).get(8))
                .as("the cell's separating column is filled by the selection and by nothing else")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(idle.styles(5).get(6))
                .as("an unfocused calendar keeps the selection and drops the focus")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.SELECTED)));
        assertThat(light.styles(5).get(8))
                .as("a live theme switch repaints the selection")
                .isEqualTo(Themes.LIGHT.style(ThemeToken.SELECTED))
                .isNotEqualTo(dark.styles(5).get(8));
    }

    @Test
    void aNarrowCalendarDropsTheSeparatorsAndClipsWhatDoesNotFit() {
        Calendar.Model model = Calendar.Model.of(NOVEMBER_18);

        assertThat(ElementScreen.render(calendar.element(model), 8, 14).lines())
                .as("fourteen columns are seven two-column cells with no separating column")
                .containsExactly(
                        "November 2026",
                        "MoTuWeThFrSaSu",
                        " ".repeat(12) + " 1",
                        " 2 3 4 5 6 7 8",
                        " 9101112131415",
                        "16171819202122",
                        "23242526272829",
                        "30");
        assertThat(ElementScreen.render(calendar.element(model), 8, 10).lines())
                .as("a pane narrower than a week clips the days that do not fit")
                .containsExactly(
                        "November 2", "MoTuWeThFr", "", " 2 3 4 5 6", " 910111213", "1617181920", "2324252627", "30");
        assertThat(ElementScreen.render(calendar.element(model), 3, 20).lines())
                .as("a pane with fewer rows paints the header and the week that fits")
                .containsExactly("November 2026", "Mo Tu We Th Fr Sa Su", " ".repeat(18) + " 1");
        assertThat(ElementScreen.render(calendar.element(model), 0, 20).lines()).isEmpty();
        assertThat(ElementScreen.render(calendar.element(model), 1, 0).lines()).hasSize(1);
        // A press in the compact grid names the day under the pointer just the same.
        assertThat(press(calendar.element(model), 8, 14, 3, 0))
                .containsExactly(new Calendar.Msg.Select(LocalDate.of(2026, 11, 2)));
    }

    @Test
    void modelsAreNormalizedAndValidated() {
        Calendar.Model empty = Calendar.Model.of(null);
        assertThat(empty.hasDate()).isFalse();
        assertThat(empty.date()).isEmpty();
        assertThat(empty.month()).isEmpty();
        assertThat(Calendar.Model.empty().withFocus(false).focused()).isFalse();
        assertThat(Calendar.Model.of(NOVEMBER_18).withSelected(null).hasDate()).isFalse();
        assertThat(Calendar.Model.of(NOVEMBER_18).month()).contains(YearMonth.of(2026, 11));
        assertThat(Calendar.Config.defaults().withFirstDayOfWeek(null).firstDayOfWeek())
                .as("a missing first day of the week is Monday")
                .isEqualTo(DayOfWeek.MONDAY);
        assertThat(new Calendar.Config(null).firstDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(calendar.config()).isEqualTo(Calendar.Config.defaults());
        assertThat(Calendar.cellColumns(20)).isEqualTo(3);
        assertThat(Calendar.cellColumns(19)).isEqualTo(2);

        assertThatThrownBy(() -> new Calendar(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.update(null, new Calendar.Msg.Right()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.update(empty, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.element(null)).isInstanceOf(NullPointerException.class);
        assertThat(calendar.init(new InitContext(null, null)).model().hasDate())
                .as("an initialized calendar has no date until the application sends one")
                .isFalse();
    }

    private Calendar.Model apply(Calendar.Model model, Calendar.Msg message) {
        return calendar.update(model, message).model();
    }

    private List<Calendar.Msg> route(Element<Calendar.Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                calendar.keys().bindings());
    }

    private static List<Calendar.Msg> press(Element<Calendar.Msg> root, int rows, int cols, int row, int col) {
        List<Calendar.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<Calendar.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(
                placement,
                InteractionRouter.Interaction.NONE,
                PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col),
                recorded::add);
        return recorded;
    }
}
