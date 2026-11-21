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
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A month grid over a selected date: seven columns of day numbers, a month header and a weekday row.
 *
 * <p>A calendar is the part of a screen that answers "which day": the user moves a selection a day,
 * a week or a month at a time, and the application is told the date when the user chooses it. It
 * owns one fact - the selected date - and the month it shows is that date's month, so there is no
 * second field that can disagree with the selection and no way to move the grid somewhere the
 * selection is not.</p>
 *
 * <h2>Navigation</h2>
 *
 * <p>{@code left} and {@code right} move a day, {@code up} and {@code down} a week, and a move that
 * crosses a month boundary shows the month it lands in: the selection is what the grid is centered
 * on. {@code pageup} and {@code pagedown} move a month, keeping the day of the month where the
 * target month has it - the thirty-first of January becomes the twenty-eighth of February rather
 * than an error - and {@code home} and {@code end} go to the first and the last day of the shown
 * month. {@code enter} and {@code space} choose the selected date, which reports an {@link
 * Output.Chosen}; a move only changes the selection, because a picker that reported every step
 * would report eleven dates while the user walked to the twelfth.</p>
 *
 * <h2>Pointer</h2>
 *
 * <p>A press on a day cell selects it, and a second press on the selected day chooses it - the rule
 * {@link Tree} and {@link NotificationCenter} follow. The month row and the weekday row are not
 * targets, and neither is a blank cell of the first or last week: only a day of the shown month is
 * painted, so only a painted day can be pressed.</p>
 *
 * <h2>Geometry, themes and narrow panes</h2>
 *
 * <p>A calendar is a two-row header over as many week rows as the month needs, so February can
 * need four and a Sunday-first month can need six. The month name and year are painted in {@link
 * ThemeToken#ACCENT}, the weekday names in {@link ThemeToken#MUTED}, a day in {@link
 * ThemeToken#TEXT} with the weekend in {@link ThemeToken#MUTED}, and the selected day's cell is
 * filled with {@link ThemeToken#SELECTED} with {@link ThemeToken#FOCUS} merged into its number
 * while the calendar owns focus.</p>
 *
 * <p>At twenty columns the grid uses a three-column cell - the day number and its separating column
 * - and below that it drops the separator and uses two columns, which is the narrowest width at
 * which seven days still fit. A pane narrower than fourteen columns clips the end of each week like
 * every other widget clips content, and a pane with fewer rows than the month needs paints the rows
 * it has. A calendar with no date measures nothing, so a pane does not reserve a month that is not
 * shown.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A calendar is one object per placed calendar, held across frames the way a component is,
 * because the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see LogViewer
 * @see Elements#calendar(Calendar, Calendar.Model, java.util.function.Function)
 */
public final class Calendar
        implements Component<Calendar.Model, Calendar.Msg, Calendar.Output>,
                ThemedWidget<Calendar.Model, Calendar.Msg> {

    /**
     * The rows the two headers take above the week rows.
     */
    private static final int HEADER_ROWS = 2;

    /**
     * The columns a day cell takes when the pane is wide enough to separate the days.
     */
    private static final int CELL_COLUMNS = 3;

    /**
     * The columns a day cell takes when it is not: seven days, no separating column.
     */
    private static final int COMPACT_CELL_COLUMNS = 2;

    /**
     * The columns the grid asks for: seven three-column cells less the trailing separator.
     */
    private static final int PREFERRED_COLUMNS = 7 * CELL_COLUMNS - 1;

    private static final KeyMap<Msg> KEYS = KeyMap.of(
            KeyMap.group(
                    "Selection",
                    KeyAction.<Msg>of("previous-day", new Msg.Left(), "\u2190", "previous day", "left"),
                    KeyAction.<Msg>of("next-day", new Msg.Right(), "\u2192", "next day", "right"),
                    KeyAction.<Msg>of("previous-week", new Msg.Up(), "\u2191", "previous week", "up"),
                    KeyAction.<Msg>of("next-week", new Msg.Down(), "\u2193", "next week", "down")),
            KeyMap.group(
                    "Month",
                    KeyAction.<Msg>of("previous-month", new Msg.PreviousMonth(), "pageup", "previous month", "pageup"),
                    KeyAction.<Msg>of("next-month", new Msg.NextMonth(), "pagedown", "next month", "pagedown"),
                    KeyAction.<Msg>of("first-day", new Msg.FirstOfMonth(), "home", "first day", "home"),
                    KeyAction.<Msg>of("last-day", new Msg.LastOfMonth(), "end", "last day", "end")),
            KeyMap.group(
                    "Actions", KeyAction.<Msg>of("choose", new Msg.Activate(), "enter", "choose", "enter", "space")));

    private final Config config;

    /**
     * A calendar whose weeks start on Monday.
     */
    public Calendar() {
        this(Config.defaults());
    }

    /**
     * A calendar over its configuration.
     *
     * @param config the first day of the week; required
     */
    public Calendar(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Immutable calendar state.
     *
     * <p>The selected date is the only fact the calendar owns: the month it paints is that date's
     * month. A model with no date paints nothing, which is what an application has before it knows
     * which day the user is picking.</p>
     *
     * @param selected the selected date, or null when the calendar has none
     * @param focused whether the calendar owns focus
     */
    public record Model(LocalDate selected, boolean focused) {

        /**
         * An empty calendar, focused.
         *
         * @return the model
         */
        public static Model empty() {
            return new Model(null, true);
        }

        /**
         * A focused calendar on a date.
         *
         * @param date the selected date; null is an empty calendar
         * @return the model
         */
        public static Model of(LocalDate date) {
            return new Model(date, true);
        }

        /**
         * This calendar on another date, which also shows that date's month.
         *
         * @param value the selected date; null empties the calendar
         * @return the new model
         */
        public Model withSelected(LocalDate value) {
            return new Model(value, focused);
        }

        /**
         * The same calendar, focused or not.
         *
         * @param value whether the calendar owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(selected, value);
        }

        /**
         * Whether the calendar has a date to paint.
         *
         * @return true when it has one
         */
        public boolean hasDate() {
            return selected != null;
        }

        /**
         * The month the calendar paints.
         *
         * @return the month, or empty when the calendar has no date
         */
        public Optional<YearMonth> month() {
            return Optional.ofNullable(selected).map(YearMonth::from);
        }

        /**
         * The selected date.
         *
         * @return the date, or empty when the calendar has none
         */
        public Optional<LocalDate> date() {
            return Optional.ofNullable(selected);
        }
    }

    /**
     * The calendar's messages.
     */
    public sealed interface Msg {

        /**
         * Selects the previous day.
         */
        record Left() implements Msg {}

        /**
         * Selects the next day.
         */
        record Right() implements Msg {}

        /**
         * Selects the same weekday in the previous week.
         */
        record Up() implements Msg {}

        /**
         * Selects the same weekday in the next week.
         */
        record Down() implements Msg {}

        /**
         * Selects the same day of the previous month, clamped to that month's length.
         */
        record PreviousMonth() implements Msg {}

        /**
         * Selects the same day of the next month, clamped to that month's length.
         */
        record NextMonth() implements Msg {}

        /**
         * Selects the first day of the shown month.
         */
        record FirstOfMonth() implements Msg {}

        /**
         * Selects the last day of the shown month.
         */
        record LastOfMonth() implements Msg {}

        /**
         * Selects a date, which is what a press on a day cell does.
         *
         * @param date the date to select; null selects nothing
         */
        record Select(LocalDate date) implements Msg {}

        /**
         * Chooses the selected date.
         */
        record Activate() implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * The user chose a date.
         *
         * @param date the chosen date
         */
        record Chosen(LocalDate date) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a calendar is configured.
     *
     * @param firstDayOfWeek the day the week starts on; null is {@link DayOfWeek#MONDAY}
     */
    public record Config(DayOfWeek firstDayOfWeek) {

        /**
         * Substitutes the default first day of the week.
         */
        public Config {
            firstDayOfWeek = firstDayOfWeek == null ? DayOfWeek.MONDAY : firstDayOfWeek;
        }

        /**
         * The default configuration: weeks start on Monday.
         *
         * @return the configuration
         */
        public static Config defaults() {
            return new Config(DayOfWeek.MONDAY);
        }

        /**
         * This configuration with another first day of the week.
         *
         * @param value the first day of the week; null is Monday
         * @return the new configuration
         */
        public Config withFirstDayOfWeek(DayOfWeek value) {
            return new Config(value);
        }
    }

    /**
     * How this calendar is configured.
     *
     * @return how this calendar is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The calendar's element: its month grid, and the calendar itself answering for it.
     *
     * @param model the state to paint; required
     * @return the element, emitting this calendar's messages
     */
    public Element<Msg> element(Model model) {
        return new Element.Leaf<>(this, Objects.requireNonNull(model, "model"), Sizing.measured());
    }

    /**
     * The default actions: move the selection, move a month, or choose the selected day.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KEYS;
    }

    /**
     * The actions this calendar handles.
     *
     * @return the actions this calendar handles
     */
    public KeyMap<Msg> keys() {
        return KEYS;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(Model.empty());
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return element(model);
    }

    /**
     * The calendar's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key its press did, so it never fires the press a second time.
            return;
        }
        keys().actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * A press on a painted day selects it, and a press on the selected day chooses it.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (pointer == null || !pointer.isLeftPress() || bounds.isEmpty() || model.selected() == null) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (local.row() < HEADER_ROWS || local.row() >= bounds.rows() || local.column() < 0) {
            return;
        }
        int day = local.column() / cellColumns(bounds.cols());
        if (day >= 7) {
            return;
        }
        LocalDate date = gridStart(YearMonth.from(model.selected()), config.firstDayOfWeek())
                .plusDays((long) (local.row() - HEADER_ROWS) * 7 + day);
        if (!YearMonth.from(date).equals(YearMonth.from(model.selected()))) {
            // Only a day of the shown month is painted, so only one of those is a target.
            return;
        }
        emit.accept(date.equals(model.selected()) ? new Msg.Activate() : new Msg.Select(date));
    }

    /**
     * The rows the month needs and the columns the grid asks for, inside what is offered.
     */
    @Override
    public Size measure(Size max, Model model) {
        if (model == null || model.selected() == null) {
            return new Size(0, 0);
        }
        int rows = Math.min(Math.max(0, max.rows()), height(model));
        int cols = Math.min(Math.max(0, max.cols()), PREFERRED_COLUMNS);
        return new Size(rows, cols);
    }

    /**
     * Paints the month header, the weekday row and the weeks of the shown month.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (model == null || model.selected() == null || bounds.isEmpty()) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        YearMonth month = YearMonth.from(model.selected());
        int right = bounds.col() + bounds.cols();
        int cell = cellColumns(bounds.cols());
        paintHeader(surface, bounds, month, resolved);
        if (bounds.rows() > 1) {
            paintWeekdays(surface, bounds, cell, right, resolved);
        }
        LocalDate start = gridStart(month, config.firstDayOfWeek());
        int weeks = Math.min(weeks(month, config.firstDayOfWeek()), bounds.rows() - HEADER_ROWS);
        for (int week = 0; week < weeks; week++) {
            paintWeek(surface, bounds, model, month, start, week, cell, right, resolved);
        }
    }

    /**
     * A calendar with a date can take the keyboard; one with nothing to show cannot.
     */
    @Override
    public boolean canFocus(Model model) {
        return model != null && model.selected() != null;
    }

    /**
     * Focus is model data: the application sets it and the calendar only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model != null && model.focused();
    }

    /**
     * The columns a day cell takes at a width.
     *
     * <p>The grid separates its days while it can and drops the separator below the width that
     * needs it, so seven days still fit a narrow pane.</p>
     *
     * @param cols the columns the calendar has
     * @return three, or two for a narrow pane
     */
    static int cellColumns(int cols) {
        return cols >= PREFERRED_COLUMNS ? CELL_COLUMNS : COMPACT_CELL_COLUMNS;
    }

    /**
     * The first cell of the grid: the first day of the shown month, or the last first-day-of-week
     * before it.
     *
     * @param month the shown month; required
     * @param firstDayOfWeek the day the week starts on; null is Monday
     * @return the date the first cell paints
     */
    static LocalDate gridStart(YearMonth month, DayOfWeek firstDayOfWeek) {
        DayOfWeek first = firstDayOfWeek == null ? DayOfWeek.MONDAY : firstDayOfWeek;
        int offset = Math.floorMod(month.atDay(1).getDayOfWeek().getValue() - first.getValue(), 7);
        return month.atDay(1).minusDays(offset);
    }

    /**
     * The week rows a month needs.
     *
     * @param month the shown month; required
     * @param firstDayOfWeek the day the week starts on; null is Monday
     * @return four, five or six
     */
    static int weeks(YearMonth month, DayOfWeek firstDayOfWeek) {
        DayOfWeek first = firstDayOfWeek == null ? DayOfWeek.MONDAY : firstDayOfWeek;
        int offset = Math.floorMod(month.atDay(1).getDayOfWeek().getValue() - first.getValue(), 7);
        return (offset + month.lengthOfMonth() + 6) / 7;
    }

    /**
     * The rows a calendar paints: its two headers and the weeks of the month.
     */
    private int height(Model model) {
        return HEADER_ROWS + weeks(YearMonth.from(model.selected()), config.firstDayOfWeek());
    }

    /**
     * The month name and the year, in the accent style and clipped to the pane.
     */
    private static void paintHeader(Surface surface, Rect bounds, YearMonth month, Theme theme) {
        String title = month.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + " " + month.getYear();
        surface.write(
                new Rect(bounds.row(), bounds.col(), 1, bounds.cols()),
                StyledText.of(title, theme.style(ThemeToken.ACCENT)),
                WidthPolicy.PINNED);
    }

    /**
     * The weekday row: two-letter names in the theme's muted style, aligned over their columns.
     */
    private void paintWeekdays(Surface surface, Rect bounds, int cell, int right, Theme theme) {
        Style style = theme.style(ThemeToken.MUTED);
        for (int day = 0; day < 7; day++) {
            int col = bounds.col() + day * cell;
            int cols = Math.min(2, right - col);
            if (cols <= 0) {
                break;
            }
            String name = config.firstDayOfWeek().plus(day).getDisplayName(TextStyle.SHORT, Locale.US);
            surface.write(
                    new Rect(bounds.row() + 1, col, 1, cols),
                    StyledText.of(String.format(Locale.US, "%2.2s", name), style),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * One week: the days of the shown month, the selected one filled.
     */
    private void paintWeek(
            Surface surface,
            Rect bounds,
            Model model,
            YearMonth month,
            LocalDate start,
            int week,
            int cell,
            int right,
            Theme theme) {
        int row = bounds.row() + HEADER_ROWS + week;
        Style dayStyle = theme.style(ThemeToken.TEXT);
        Style weekendStyle = theme.style(ThemeToken.MUTED);
        for (int day = 0; day < 7; day++) {
            int col = bounds.col() + day * cell;
            if (col >= right) {
                break;
            }
            LocalDate date = start.plusDays((long) week * 7 + day);
            if (!YearMonth.from(date).equals(month)) {
                continue;
            }
            boolean marked = date.equals(model.selected());
            Style style = marked
                    ? dayStyle
                    : date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
                            ? weekendStyle
                            : dayStyle;
            if (marked) {
                surface.fill(new Rect(row, col, 1, Math.min(cell, right - col)), theme.style(ThemeToken.SELECTED));
                if (model.focused()) {
                    // Focus colors the day's number rather than its cell: the theme's focus style is a
                    // foreground style, and filling a cell with it would color the separator too.
                    style = style.merge(theme.style(ThemeToken.FOCUS));
                }
            }
            surface.write(
                    new Rect(row, col, 1, Math.min(2, right - col)),
                    StyledText.of(String.format(Locale.US, "%2d", date.getDayOfMonth()), style),
                    WidthPolicy.PINNED);
        }
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Left left -> move(state, -1);
            case Msg.Right right -> move(state, 1);
            case Msg.Up up -> move(state, -7);
            case Msg.Down down -> move(state, 7);
            case Msg.PreviousMonth previous -> moveMonth(state, -1);
            case Msg.NextMonth next -> moveMonth(state, 1);
            case Msg.FirstOfMonth first -> select(state, firstOfMonth(state));
            case Msg.LastOfMonth last -> select(state, lastOfMonth(state));
            case Msg.Select select -> select(state, select.date());
            case Msg.Activate activate -> activate(state);
        };
    }

    /**
     * The selection a number of days along, crossing months as it goes.
     */
    private static Result move(Model state, int days) {
        if (state.selected() == null) {
            return new Result(state, null);
        }
        return select(state, state.selected().plusDays(days));
    }

    /**
     * The selection the same day of another month, clamped to that month's length.
     */
    private static Result moveMonth(Model state, int months) {
        if (state.selected() == null) {
            return new Result(state, null);
        }
        YearMonth month = YearMonth.from(state.selected()).plusMonths(months);
        int day = Math.min(state.selected().getDayOfMonth(), month.lengthOfMonth());
        return select(state, month.atDay(day));
    }

    /**
     * The first day of the shown month, or null when there is no month.
     */
    private static LocalDate firstOfMonth(Model state) {
        return state.selected() == null
                ? null
                : YearMonth.from(state.selected()).atDay(1);
    }

    /**
     * The last day of the shown month, or null when there is no month.
     */
    private static LocalDate lastOfMonth(Model state) {
        if (state.selected() == null) {
            return null;
        }
        YearMonth month = YearMonth.from(state.selected());
        return month.atDay(month.lengthOfMonth());
    }

    /**
     * The selected date chosen, which is the only gesture that reports an output.
     */
    private static Result activate(Model state) {
        return state.selected() == null
                ? new Result(state, null)
                : new Result(state, new Output.Chosen(state.selected()));
    }

    /**
     * A date selected; a missing date and the date already selected change nothing.
     */
    private static Result select(Model state, LocalDate date) {
        if (date == null || date.equals(state.selected())) {
            return new Result(state, null);
        }
        return new Result(state.withSelected(date), null);
    }
}
