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

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.ui.Element;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What every widget owes the box it was measured at, over random trees of every view and widget.
 *
 * <p>Each widget is written on its own, and each one decides for itself how wide it is, where its
 * border goes, and what happens to content that no longer fits. They share one contract, and the
 * container above them cannot tell them apart: measure, then paint, and what is painted has to fit the
 * box the same call just asked for. A widget that breaks that contract does not fail inside itself - it
 * corrupts whatever is painted next to it, which is why a single driver over every widget type finds
 * what a per-widget test does not.</p>
 *
 * <p>The leaves include the tricky content every widget has to survive at once: wide clusters, clusters
 * that claim no column of their own, marks that have to be drawn behind a dotted circle, tabs, and
 * newlines. Widgets that keep internal state are also driven through random message sequences, because
 * a widget whose scroll offset or selected cell can leave its own bounds paints a row that does not
 * exist.</p>
 */
class WidgetContractFuzzTest {

    private static final String[] TEXTS = {
        "alpha",
        "a b c",
        "\u4E2D\u6587 mixed",
        "\uD83D\uDE00 done",
        // A variation selector inside a cluster, not orphaned at the start: an orphan has no
        // standalone width, so a frame that clips it would be measuring nothing.
        "\uD83D\uDE00\uFE0F ok",
        "e\u0301",
        "\u200B\u0301",
        "\uD83D\uDE42\uFE0E tail",
        "",
        "x".repeat(11),
        "tab\there",
        "line1\nline2",
        "  padded  ",
        "\u00ADsoft",
        "a\tb\tc",
        "\u25CC",
        "\uFF21\uFF22",
        "\uD83D\uDC69\u200D\uD83D\uDCBB"
    };

    private static final String[] MARKUP = {
        "<root>plain</root>",
        "<root><b>bold</b> and <i>italic</i></root>",
        "<root><fg color=\"#ff0000\">red</fg></root>",
        "<root>wide \u4E2D and emoji \uD83D\uDE00</root>",
        "<root>tab\there</root>",
        "<root></root>"
    };

    private static final Border[] BORDERS = {Border.SINGLE, Border.NONE, new Border('+', '+', '+', '+', '-', '|')};

    private static final Viewport VIEWPORT = new Viewport(12, 40);

    /**
     * The size {@code tree} asks for when it is offered {@code rows} by {@code cols} cells.
     */
    private static Size measure(Element<?> tree, int rows, int cols) {
        return io.github.dhoard.braxton.ui.ElementTree.measure(tree, new Size(rows, cols));
    }

    /**
     * One frame of {@code tree}, painted the way the runtime paints it.
     */
    private static Screen paint(Element<?> tree, int rows, int cols) {
        Screen screen = new Screen(Math.max(0, rows), Math.max(0, cols));
        io.github.dhoard.braxton.ui.ElementTree.paint(
                io.github.dhoard.braxton.ui.ElementTree.layout(tree, new Rect(0, 0, screen.rows(), screen.cols())),
                screen);
        return screen;
    }

    @Test
    void everyWidgetPaintsWithinTheBoxItWasMeasuredAt() {
        Random random = new Random(555_111L);
        for (int round = 0; round < 1200; round++) {
            Element<Object> view = build(3, random);
            int maxCols = 1 + random.nextInt(26);
            int width = measure(view, Integer.MAX_VALUE, maxCols).cols();
            assertThat(width)
                    .as("measured width of %s against %d columns", view, maxCols)
                    .isBetween(0, maxCols);
            for (int cols : new int[] {Math.max(1, width), Math.max(1, width - 2), 1, maxCols}) {
                int rows = measure(view, Integer.MAX_VALUE, cols).rows();
                if (rows > 1000) throw new AssertionError("UNBOUNDED: " + view + " cols=" + cols);
                assertThat(rows)
                        .as("measured height of %s at %d columns", view, cols)
                        .isNotNegative();
                assertThat(measure(view, Integer.MAX_VALUE, cols).rows())
                        .as("measuring %s twice at %d columns gave different heights", view, cols)
                        .isEqualTo(rows);
                Screen painted = paint(view, rows, cols);
                assertThat(painted.rows())
                        .as("%s at %dx%d painted a different number of rows", view, rows, cols)
                        .isEqualTo(rows);
                assertThat(painted.cols())
                        .as("%s at %dx%d painted a different number of columns", view, rows, cols)
                        .isEqualTo(cols);
                for (int row = 0; row < rows; row++) {
                    assertThat(TextMeasure.columns(painted.plainLine(row)))
                            .as(
                                    "%s at %dx%d: row %d [%s] fills more than the %d columns it was given",
                                    view, rows, cols, row, show(painted.plainLine(row)), cols)
                            .isLessThanOrEqualTo(cols);
                }
                assertThat(paint(view, rows, cols))
                        .as("%s at %dx%d did not paint the same grid twice", view, rows, cols)
                        .isEqualTo(painted);
            }
        }
    }

    /**
     * A scrolled or selected widget cannot let its own cursor leave the rows and columns it has.
     */
    @Test
    void aWidgetWithInternalStateNeverMovesOutsideItsOwnBounds() {
        Random random = new Random(808_080L);
        for (int round = 0; round < 400; round++) {
            ScrollView scroll = new ScrollView(new ScrollView.Config(1 + random.nextInt(5), random.nextBoolean()));
            ScrollView.Content content = content(random);
            ScrollView.Model scrollState = new ScrollView.Model(
                    random.nextInt(9), random.nextBoolean(), random.nextBoolean(), 1 + random.nextInt(5), content);
            for (int steps = 1 + random.nextInt(14); steps > 0; steps--) {
                scrollState = scroll.update(scrollState, scrollMessage(random, content))
                        .model();
                // The content can change under the pane, so the bound comes from the state itself:
                // the last row that can be the top of the window is the one that leaves a full window
                // of visible rows behind it.
                assertThat(scrollState.topLine())
                        .as(
                                "ScrollView scrolled to line %d of %d with a %d-row window",
                                scrollState.topLine(), scrollState.lineCount(), scrollState.visibleRows())
                        .isBetween(0, Math.max(0, scrollState.lineCount() - scrollState.visibleRows()));
                assertThat(scroll.view(scrollState, new ViewContext(VIEWPORT)))
                        .as("ScrollView view after %s", scrollState)
                        .isNotNull();
            }

            List<String> options = options(random);
            Selector selector = new Selector(new Selector.Config(options, Style.NONE, Style.NONE));
            Selector.Model choice =
                    new Selector.Model(options, random.nextInt(options.size() + 2) - 1, random.nextBoolean());
            for (int steps = 1 + random.nextInt(10); steps > 0; steps--) {
                choice = selector.update(choice, selectorMessage(random)).model();
                assertThat(choice.selected())
                        .as(
                                "Selector moved to option %d of %d",
                                choice.selected(), choice.options().size())
                        .isBetween(0, choice.options().size() - 1);
                assertThat(selector.view(choice, new ViewContext(VIEWPORT)))
                        .as("Selector view after %s", choice)
                        .isNotNull();
            }

            List<String> header = header(random);
            List<List<String>> table = grid(random, header.size());
            TableSelector gridWidget = new TableSelector(new TableSelector.Config(
                    TableSelector.Model.of(header, table), Style.NONE, Style.NONE, Style.NONE));
            TableSelector.Model cell =
                    new TableSelector.Model(header, table, random.nextInt(6), random.nextInt(6), random.nextBoolean());
            for (int steps = 1 + random.nextInt(10); steps > 0; steps--) {
                cell = gridWidget.update(cell, cellMessage(random)).model();
                assertThat(cell.row())
                        .as(
                                "TableSelector moved to row %d of %d",
                                cell.row(), cell.rows().size())
                        .isBetween(0, cell.rows().size() - 1);
                assertThat(cell.column())
                        .as(
                                "TableSelector moved to column %d of %d",
                                cell.column(), cell.header().size())
                        .isBetween(0, cell.header().size() - 1);
                assertThat(gridWidget.view(cell, new ViewContext(VIEWPORT)))
                        .as("TableSelector view after %s", cell)
                        .isNotNull();
            }

            DataTableConfig config = DataTableConfig.defaults()
                    .withContent(dataContent(random))
                    .withHeight(1 + random.nextInt(8))
                    .withWidth(1 + random.nextInt(40));
            DataTable widget = new DataTable(config);
            DataTableModel data = widget.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                data = widget.update(data, dataMessage(random)).model();
                assertThat(data.selection().row())
                        .as(
                                "DataTable moved to row %d of %d",
                                data.selection().row(), data.content().rowCount())
                        .isBetween(-1, Math.max(0, data.content().rowCount() - 1));
                assertThat(data.selection().column())
                        .as(
                                "DataTable moved to column %d of %d",
                                data.selection().column(), data.content().columnCount())
                        .isBetween(-1, Math.max(0, data.content().columnCount() - 1));
                int body = DataTable.bodyHeight(data, config);
                assertThat(DataTable.top(data, config))
                        .as(
                                "DataTable window of %d rows in %d of %d",
                                body, data.content().rowCount(), data.height())
                        .isBetween(0, Math.max(0, data.content().rowCount() - body));
                assertThat(DataTable.firstColumn(data, config))
                        .as(
                                "DataTable panned to %d of %d cells in %d",
                                DataTable.firstColumn(data, config), DataTable.rowWidth(data), data.width())
                        .isBetween(0, Math.max(0, DataTable.rowWidth(data) - data.width()));
                Element<DataTableMsg> view = widget.view(data, new ViewContext(VIEWPORT));
                assertThat(view).as("DataTable view after %s", data).isNotNull();
                Screen painted = paint(view, VIEWPORT.rows(), VIEWPORT.cols());
                for (int row = 0; row < painted.rows(); row++) {
                    assertThat(TextMeasure.columns(painted.plainLine(row)))
                            .as(
                                    "DataTable at %s: row %d [%s] fills more than %d columns",
                                    data, row, painted.plainLine(row), VIEWPORT.cols())
                            .isLessThanOrEqualTo(VIEWPORT.cols());
                }
            }

            FilePickerConfig pickerConfig = FilePickerConfig.defaults()
                    .withDirectory(Path.of("/fixture"))
                    .withRows(1 + random.nextInt(8))
                    .withExtensions(random.nextBoolean() ? Set.of() : Set.of("txt"));
            FilePicker picker = new FilePicker(pickerConfig);
            FilePickerModel picked = FilePickerModel.of(Path.of("/fixture"), pickerEntries(random))
                    .withRows(1 + random.nextInt(8));
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                picked = picker.update(picked, pickerMessage(random)).model();
                List<FilePicker.Entry> shown = FilePicker.shown(picked);
                int entryRows = FilePicker.contentRows(picked, pickerConfig);
                assertThat(FilePicker.selectedIndex(picked))
                        .as("FilePicker selected %d of %d", FilePicker.selectedIndex(picked), shown.size())
                        .isBetween(-1, Math.max(0, shown.size() - 1));
                assertThat(FilePicker.top(picked, pickerConfig))
                        .as(
                                "FilePicker window of %d rows in %d of %d",
                                entryRows, FilePicker.top(picked, pickerConfig), shown.size())
                        .isBetween(0, Math.max(0, shown.size() - entryRows));
                List<StyledText> painted = FilePicker.lines(picked, pickerConfig, VIEWPORT.cols());
                assertThat(painted).as("FilePicker rows at %d", picked.rows()).hasSize(picked.rows());
                for (int row = 0; row < painted.size(); row++) {
                    assertThat(TextMeasure.columns(painted.get(row).plainText()))
                            .as(
                                    "FilePicker at %s: row %d [%s] fills more than %d columns",
                                    picked, row, painted.get(row).plainText(), VIEWPORT.cols())
                            .isLessThanOrEqualTo(VIEWPORT.cols());
                }
                assertThat(picker.view(picked, new ViewContext(VIEWPORT)))
                        .as("FilePicker view after %s", picked)
                        .isNotNull();
            }

            // The choice widgets own a window, a highlight and a set of marks, and any of them can
            // leave the options it has. The loop below is appended after the blocks above so the
            // fixtures those blocks draw stay the ones they always drew.

            Select select = new Select(choiceOptions(random));
            Select.Model selection =
                    select.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                selection = select.update(selection, selectMessage(random)).model();
                List<String> ids = selection.options().stream().map(Option::id).toList();
                assertThat(ids).as("Select kept no options").isNotEmpty();
                assertThat(ids)
                        .as("Select chose %s of %s", selection.selected(), ids)
                        .contains(selection.selected());
                assertThat(ids)
                        .as("Select highlighted %s of %s", selection.highlighted(), ids)
                        .contains(selection.highlighted());
                assertThat(selection.offset())
                        .as(
                                "Select window of %d rows in %d of %d options",
                                selection.rows(), selection.offset(), ids.size())
                        .isBetween(0, Math.max(0, ids.size() - selection.rows()));
                paintedWithin(select.view(selection, new ViewContext(VIEWPORT)), "Select after %s", selection);
            }

            MultiSelect multiSelect = new MultiSelect(choiceOptions(random));
            MultiSelect.Model marks =
                    multiSelect.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                marks = multiSelect.update(marks, multiSelectMessage(random)).model();
                List<String> ids = marks.options().stream().map(Option::id).toList();
                assertThat(marks.chosen())
                        .as("MultiSelect marked %s of %s", marks.chosen(), ids)
                        .isSubsetOf(ids);
                // A closed field with nothing marked paints no highlight, so a blank id is legal
                // there; every other highlight has to name an option.
                assertThat(marks.highlighted().isEmpty() || ids.contains(marks.highlighted()))
                        .as("MultiSelect highlighted %s of %s", marks.highlighted(), ids)
                        .isTrue();
                assertThat(marks.offset())
                        .as(
                                "MultiSelect window of %d rows in %d of %d options",
                                marks.rows(), marks.offset(), ids.size())
                        .isBetween(0, Math.max(0, ids.size() - marks.rows()));
                paintedWithin(multiSelect.view(marks, new ViewContext(VIEWPORT)), "MultiSelect after %s", marks);
            }

            RadioGroup radioGroup = new RadioGroup(choiceOptions(random));
            RadioGroup.Model radio =
                    radioGroup.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                radio = radioGroup.update(radio, radioMessage(random)).model();
                List<String> ids = radio.options().stream().map(Option::id).toList();
                assertThat(ids)
                        .as("RadioGroup chose %s of %s", radio.selected(), ids)
                        .contains(radio.selected());
                paintedWithin(radioGroup.view(radio, new ViewContext(VIEWPORT)), "RadioGroup after %s", radio);
            }

            // A form owns focus, errors and a submission over field definitions the application
            // supplies. The loop drives every message and checks the model's invariants and the
            // composition's paint, including the rows for fields with no editor placed.
            Form form = formFixture(random);
            Form.Model record = form.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                record = form.update(record, formMessage(random)).model();
                List<String> ids = record.fields().stream().map(Form.Field::id).toList();
                assertThat(ids).as("Form kept no fields").isNotEmpty();
                assertThat(ids)
                        .as("Form focused %s of %s", record.focused(), ids)
                        .contains(record.focused());
                for (Form.Error error : record.errors()) {
                    assertThat(ids)
                            .as("Form reported %s for a field it does not show", error)
                            .contains(error.field());
                }
                assertThat(record.valid())
                        .as(
                                "Form valid state disagreed with its %d errors",
                                record.errors().size())
                        .isEqualTo(record.errors().isEmpty());
                paintedWithin(form.view(record, new ViewContext(VIEWPORT)), "Form after %s", record);
                paintedWithin(
                        form.element(record, formEditors(record, random), message -> message),
                        "Form with editors after %s",
                        record);
            }

            // A toast keeps a run, a deadline and the time it has left. The loop drives every message
            // and checks that nothing it can paint leaves the row it asked for, and that the one
            // scoped interval is declared exactly while the toast shows.
            Toast toast = new Toast(Toast.Config.of(Duration.ofMillis(1 + random.nextInt(500))));
            Toast.Model toastState = toast.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                toastState = toast.update(toastState, toastMessage(random, toastState))
                        .model();
                assertThat(toastState.remainingNanos() > 0L)
                        .as("Toast showing state disagreed with its %d ns left", toastState.remainingNanos())
                        .isEqualTo(toastState.showing());
                assertThat(toast.subscriptions(toastState) instanceof Subscription.None<?>)
                        .as("Toast declared an interval while %s", toastState)
                        .isEqualTo(!toastState.showing());
                paintedWithin(Elements.toast(toast, toastState), "Toast after %s", toastState);
            }

            // A notification center keeps a bounded list, a selection and the window over it. The loop
            // drives every message and checks that the selection is always a row the window shows.
            NotificationCenter center = new NotificationCenter(
                    NotificationCenter.Config.of(1 + random.nextInt(6)).withRows(random.nextInt(6)));
            NotificationCenter.Model events =
                    center.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(14); steps > 0; steps--) {
                events = center.update(events, notificationMessage(random, events))
                        .model();
                List<String> ids = events.notifications().stream()
                        .map(NotificationCenter.Notification::id)
                        .toList();
                assertThat(ids)
                        .as(
                                "NotificationCenter kept %d notifications past its bound of %d",
                                ids.size(), center.config().limit())
                        .hasSizeLessThanOrEqualTo(center.config().limit());
                if (ids.isEmpty()) {
                    assertThat(events.selected())
                            .as("NotificationCenter selected a row it does not hold")
                            .isEmpty();
                } else {
                    assertThat(ids)
                            .as("NotificationCenter selected %s of %s", events.selected(), ids)
                            .contains(events.selected());
                    assertThat(events.offset())
                            .as(
                                    "NotificationCenter window of %d rows in %d of %d",
                                    events.rows(), events.offset(), ids.size())
                            .isBetween(0, Math.max(0, ids.size() - events.rows()));
                    if (events.rows() > 0) {
                        assertThat(ids.indexOf(events.selected()))
                                .as(
                                        "NotificationCenter window of %d rows from %d does not show the selected %s of %s",
                                        events.rows(), events.offset(), events.selected(), ids)
                                .isBetween(events.offset(), events.offset() + events.rows() - 1);
                    }
                }
                assertThat(events.unread())
                        .as("NotificationCenter unread count disagreed with its rows")
                        .isEqualTo((int) events.notifications().stream()
                                .filter(notification -> !notification.read())
                                .count());
                paintedWithin(
                        Elements.notificationCenter(center, events, message -> message),
                        "NotificationCenter after %s",
                        events);
            }

            // A dialog keeps the button its keys are on. The loop drives every message and checks that
            // the focus is always on a button it has, and that the box and its buttons stay inside the
            // layer at any width.
            Dialog dialog = new Dialog(Dialog.Config.of(TEXTS[random.nextInt(TEXTS.length)], dialogActions(random))
                    .withWidth(1 + random.nextInt(40)));
            Dialog.Model modal = dialog.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(10); steps > 0; steps--) {
                modal = dialog.update(modal, dialogMessage(random)).model();
                List<String> ids = modal.actions().stream().map(Option::id).toList();
                if (ids.isEmpty()) {
                    assertThat(modal.focusedAction())
                            .as("Dialog without buttons focused %s", modal.focusedAction())
                            .isEmpty();
                } else {
                    assertThat(ids)
                            .as("Dialog focused %s of %s", modal.focusedAction(), ids)
                            .contains(modal.focusedAction());
                }
                paintedWithin(dialog.view(modal, new ViewContext(VIEWPORT)), "Dialog after %s", modal);
                paintedWithin(
                        dialog.element(modal, Elements.text(TEXTS[random.nextInt(TEXTS.length)]), message -> message),
                        "Dialog with content after %s",
                        modal);
            }

            // A sparkline keeps a reader over a series that a refresh can shorten. The loop drives
            // every message and checks that the reader is always a sample the row really has.
            Sparkline sparkline = new Sparkline();
            Sparkline.Model trend = Sparkline.Model.of(samples(random));
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                trend = sparkline.update(trend, sparklineMessage(random, trend)).model();
                assertThat(trend.selected())
                        .as(
                                "Sparkline read sample %d of %d",
                                trend.selected(), trend.values().size())
                        .isBetween(-1, Math.max(-1, trend.values().size() - 1));
                assertThat(Double.compare(
                                trend.value(),
                                trend.selected() < 0
                                        ? Double.NaN
                                        : trend.values().get(trend.selected())))
                        .as("Sparkline reported a value that is not the sample it is on")
                        .isZero();
                paintedWithin(Elements.sparkline(sparkline, trend, message -> message), "Sparkline after %s", trend);
            }

            // A line chart keeps a reader over the longest series. The loop drives every message and
            // checks that the reader is a sample, and that a reading reports one value per series.
            LineChart chart = new LineChart();
            LineChart.Model plotted = LineChart.Model.of(chartSeries(random));
            for (int steps = 1 + random.nextInt(12); steps > 0; steps--) {
                plotted =
                        chart.update(plotted, lineChartMessage(random, plotted)).model();
                assertThat(plotted.selected())
                        .as("LineChart read sample %d of %d", plotted.selected(), plotted.size())
                        .isBetween(-1, Math.max(-1, plotted.size() - 1));
                assertThat(plotted.values(Math.max(0, plotted.selected())))
                        .as("LineChart reported a reading that does not match its series")
                        .hasSize(plotted.series().size());
                paintedWithin(Elements.lineChart(chart, plotted, message -> message), "LineChart after %s", plotted);
            }

            // A bar chart keeps a selection and the window over it. The loop drives every message and
            // checks that the selection is always a category the chart holds and the window shows it.
            BarChart bars = new BarChart(BarChart.Config.defaults().withRows(random.nextInt(6)));
            BarChart.Model measured = BarChart.Model.of(chartCategories(random))
                    .withRows(bars.config().rows());
            for (int steps = 1 + random.nextInt(14); steps > 0; steps--) {
                measured =
                        bars.update(measured, barChartMessage(random, measured)).model();
                List<String> ids = measured.categories().stream()
                        .map(BarChart.Category::id)
                        .toList();
                if (ids.isEmpty()) {
                    assertThat(measured.selected())
                            .as("BarChart selected a category it does not hold")
                            .isEmpty();
                } else {
                    assertThat(ids)
                            .as("BarChart selected %s of %s", measured.selected(), ids)
                            .contains(measured.selected());
                    assertThat(measured.offset())
                            .as(
                                    "BarChart window of %d rows in %d of %d",
                                    measured.rows(), measured.offset(), ids.size())
                            .isBetween(0, Math.max(0, ids.size() - measured.rows()));
                    if (measured.rows() > 0) {
                        assertThat(ids.indexOf(measured.selected()))
                                .as(
                                        "BarChart window of %d rows from %d does not show the selected %s of %s",
                                        measured.rows(), measured.offset(), measured.selected(), ids)
                                .isBetween(
                                        measured.offset(),
                                        Math.min(ids.size() - 1, measured.offset() + measured.rows() - 1));
                    }
                }
                paintedWithin(Elements.barChart(bars, measured, message -> message), "BarChart after %s", measured);
            }

            // A calendar owns one date and paints that date's month. The loop drives every message
            // and checks that the selection always names a day, and that the grid it paints fits the
            // box it was measured at.
            Calendar calendar = new Calendar(Calendar.Config.defaults()
                    .withFirstDayOfWeek(random.nextBoolean() ? DayOfWeek.MONDAY : DayOfWeek.SUNDAY));
            Calendar.Model due = Calendar.Model.of(LocalDate.of(2026, 1, 1).plusDays(random.nextInt(800)))
                    .withFocus(random.nextBoolean());
            for (int steps = 1 + random.nextInt(14); steps > 0; steps--) {
                due = calendar.update(due, calendarMessage(random)).model();
                assertThat(due.selected())
                        .as("Calendar forgot the date it was showing")
                        .isNotNull();
                assertThat(due.month())
                        .as("Calendar painted %s without a month", due)
                        .contains(YearMonth.from(due.selected()));
                paintedWithin(Elements.calendar(calendar, due, message -> message), "Calendar after %s", due);
            }

            // A log viewer holds an append-only, bounded list and a selection on it. The loop drives
            // every message and checks the selection, the bound and the window.
            LogViewer viewer = new LogViewer(new LogViewer.Config(1 + random.nextInt(8), random.nextInt(6)));
            LogViewer.Model trace = viewer.init(new InitContext(VIEWPORT, null)).model();
            for (int steps = 1 + random.nextInt(14); steps > 0; steps--) {
                trace = viewer.update(trace, logViewerMessage(random, trace, steps))
                        .model();
                List<String> ids =
                        trace.entries().stream().map(LogViewer.Entry::id).toList();
                assertThat(trace.entries().size())
                        .as(
                                "LogViewer held %d lines for a bound of %d",
                                trace.entries().size(), viewer.config().limit())
                        .isLessThanOrEqualTo(viewer.config().limit());
                if (ids.isEmpty()) {
                    assertThat(trace.selected())
                            .as("LogViewer selected a line it does not hold")
                            .isEmpty();
                } else {
                    assertThat(ids)
                            .as("LogViewer selected %s of %s", trace.selected(), ids)
                            .contains(trace.selected());
                    assertThat(trace.offset())
                            .as("LogViewer window of %d rows in %d of %d", trace.rows(), trace.offset(), ids.size())
                            .isBetween(0, Math.max(0, ids.size() - trace.rows()));
                    if (trace.rows() > 0) {
                        assertThat(ids.indexOf(trace.selected()))
                                .as(
                                        "LogViewer window of %d rows from %d does not show the selected %s of %s",
                                        trace.rows(), trace.offset(), trace.selected(), ids)
                                .isBetween(trace.offset(), Math.min(ids.size() - 1, trace.offset() + trace.rows() - 1));
                    }
                }
                paintedWithin(Elements.logViewer(viewer, trace, message -> message), "LogViewer after %s", trace);
            }
        }
    }

    /**
     * One calendar message, including a selection of a date from another month and of no date.
     */
    private static Calendar.Msg calendarMessage(Random random) {
        return switch (random.nextInt(12)) {
            case 0, 1 -> new Calendar.Msg.Left();
            case 2, 3 -> new Calendar.Msg.Right();
            case 4 -> new Calendar.Msg.Up();
            case 5 -> new Calendar.Msg.Down();
            case 6 -> new Calendar.Msg.PreviousMonth();
            case 7 -> new Calendar.Msg.NextMonth();
            case 8 -> new Calendar.Msg.FirstOfMonth();
            case 9 -> new Calendar.Msg.LastOfMonth();
            case 10 -> new Calendar.Msg.Select(LocalDate.of(2024, 1, 1).plusDays(random.nextInt(3_000)));
            case 11 -> random.nextBoolean() ? new Calendar.Msg.Activate() : new Calendar.Msg.Select(null);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * One log-viewer message, most of them naming a line the log really holds.
     *
     * <p>The sequence makes every appended line's id unique: a log rejects a duplicate id, because
     * appending one line twice is two lines rather than a refresh, so the driver has to append lines
     * that really are new ones.</p>
     */
    private static LogViewer.Msg logViewerMessage(Random random, LogViewer.Model model, int sequence) {
        List<String> ids = model.entries().stream().map(LogViewer.Entry::id).toList();
        String known = ids.isEmpty() ? "gone" : ids.get(random.nextInt(ids.size()));
        return switch (random.nextInt(13)) {
            case 0, 1, 2 ->
                new LogViewer.Msg.Append(new LogViewer.Entry(
                        "e" + sequence,
                        Severity.values()[random.nextInt(Severity.values().length)],
                        StyledText.plain(TEXTS[random.nextInt(TEXTS.length)])));
            case 3 ->
                new LogViewer.Msg.AppendAll(List.of(new LogViewer.Entry(
                        "e" + sequence,
                        Severity.values()[random.nextInt(Severity.values().length)],
                        StyledText.plain(TEXTS[random.nextInt(TEXTS.length)]))));
            case 4 -> new LogViewer.Msg.Select(known);
            case 5 -> new LogViewer.Msg.Select("gone");
            case 6 -> new LogViewer.Msg.Activate();
            case 7 -> new LogViewer.Msg.Up();
            case 8 -> new LogViewer.Msg.Down();
            case 9 -> new LogViewer.Msg.PageUp();
            case 10 -> new LogViewer.Msg.PageDown();
            case 11 -> new LogViewer.Msg.RowsChanged(random.nextInt(6));
            default -> random.nextBoolean() ? new LogViewer.Msg.Home() : new LogViewer.Msg.End();
        };
    }

    /**
     * One sparkline message, including a selection past either end of the samples.
     */
    private static Sparkline.Msg sparklineMessage(Random random, Sparkline.Model model) {
        return switch (random.nextInt(8)) {
            case 0, 1 -> new Sparkline.Msg.Left();
            case 2, 3 -> new Sparkline.Msg.Right();
            case 4 -> new Sparkline.Msg.Home();
            case 5 -> new Sparkline.Msg.End();
            case 6 -> new Sparkline.Msg.Select(random.nextInt(model.values().size() + 3) - 1);
            default -> new Sparkline.Msg.Select(-5);
        };
    }

    /**
     * One line-chart message, including a selection past either end of the samples.
     */
    private static LineChart.Msg lineChartMessage(Random random, LineChart.Model model) {
        return switch (random.nextInt(9)) {
            case 0, 1 -> new LineChart.Msg.Left();
            case 2, 3 -> new LineChart.Msg.Right();
            case 4 -> new LineChart.Msg.Home();
            case 5 -> new LineChart.Msg.End();
            case 6, 7 -> new LineChart.Msg.Select(random.nextInt(model.size() + 3) - 1);
            default -> new LineChart.Msg.Select(-5);
        };
    }

    /**
     * One bar-chart message, most of them naming a category the chart really holds.
     */
    private static BarChart.Msg barChartMessage(Random random, BarChart.Model model) {
        List<String> ids =
                model.categories().stream().map(BarChart.Category::id).toList();
        String known = ids.isEmpty() ? "gone" : ids.get(random.nextInt(ids.size()));
        return switch (random.nextInt(9)) {
            case 0, 1 -> new BarChart.Msg.Down();
            case 2 -> new BarChart.Msg.Up();
            case 3 -> new BarChart.Msg.Home();
            case 4 -> new BarChart.Msg.End();
            case 5, 6 -> new BarChart.Msg.Select(known);
            case 7 -> new BarChart.Msg.Select("gone");
            default -> new BarChart.Msg.RowsChanged(random.nextInt(6));
        };
    }

    /**
     * A series of samples with occasional gaps, from none to nine values.
     */
    private static List<Double> samples(Random random) {
        List<Double> samples = new ArrayList<>();
        for (int count = random.nextInt(10); count > 0; count--) {
            samples.add(random.nextInt(5) == 0 ? Double.NaN : random.nextInt(400) / 10.0 - 20.0);
        }
        return samples;
    }

    /**
     * One to three series with names, roles and samples.
     */
    private static List<LineChart.Series> chartSeries(Random random) {
        List<LineChart.Series> series = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            series.add(LineChart.Series.of(
                    random.nextBoolean() ? TEXTS[random.nextInt(TEXTS.length)] : "",
                    ThemeToken.values()[random.nextInt(ThemeToken.values().length)],
                    samples(random)));
        }
        return series;
    }

    /**
     * A bar chart's categories, from none to nine of them.
     */
    private static List<BarChart.Category> chartCategories(Random random) {
        List<BarChart.Category> categories = new ArrayList<>();
        for (int count = random.nextInt(10); count > 0; count--) {
            categories.add(BarChart.Category.of(
                    "c" + categories.size(),
                    TEXTS[random.nextInt(TEXTS.length)],
                    random.nextInt(5) == 0 ? Double.NaN : random.nextInt(400) / 10.0 - 200.0));
        }
        return categories;
    }

    /**
     * One toast message: showing another message, dismissing, or one runtime reading.
     */
    private static Toast.Msg toastMessage(Random random, Toast.Model model) {
        return switch (random.nextInt(6)) {
            case 0, 1, 2 ->
                Toast.Msg.Show.of(
                        Severity.values()[random.nextInt(Severity.values().length)],
                        TEXTS[random.nextInt(TEXTS.length)]);
            case 3 -> new Toast.Msg.Dismiss();
            // A reading of the current run, and one of a run that is over.
            case 4 -> new Toast.Msg.Reading(model.epoch(), random.nextInt(2_000));
            default -> new Toast.Msg.Reading(model.epoch() + 1L, 1_000 + random.nextInt(1_000));
        };
    }

    /**
     * One notification-center message, most of them naming a row the center really holds.
     */
    private static NotificationCenter.Msg notificationMessage(Random random, NotificationCenter.Model model) {
        List<String> ids = model.notifications().stream()
                .map(NotificationCenter.Notification::id)
                .toList();
        String known = ids.isEmpty() ? "gone" : ids.get(random.nextInt(ids.size()));
        return switch (random.nextInt(13)) {
            case 0, 1, 2 ->
                new NotificationCenter.Msg.Push(NotificationCenter.Notification.of(
                        "n" + random.nextInt(8),
                        Severity.values()[random.nextInt(Severity.values().length)],
                        TEXTS[random.nextInt(TEXTS.length)]));
            case 3, 4 -> new NotificationCenter.Msg.Select(known);
            case 5 -> new NotificationCenter.Msg.Select("gone");
            case 6 -> new NotificationCenter.Msg.Activate();
            case 7 -> new NotificationCenter.Msg.Up();
            case 8 -> new NotificationCenter.Msg.Down();
            case 9 -> new NotificationCenter.Msg.Home();
            case 10 -> new NotificationCenter.Msg.End();
            case 11 -> new NotificationCenter.Msg.MarkRead(known);
            default ->
                random.nextBoolean()
                        ? new NotificationCenter.Msg.MarkAllRead()
                        : new NotificationCenter.Msg.Dismiss(known);
        };
    }

    /**
     * The buttons a dialog is configured with, from none to three.
     */
    private static List<Option> dialogActions(Random random) {
        List<Option> actions = new ArrayList<>();
        for (int count = random.nextInt(4); count > 0; count--) {
            actions.add(Option.of("b" + actions.size(), TEXTS[random.nextInt(TEXTS.length)]));
        }
        return actions;
    }

    /**
     * One dialog message, including a press on a button the dialog does not have.
     */
    private static Dialog.Msg dialogMessage(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> new Dialog.Msg.Next();
            case 1 -> new Dialog.Msg.Previous();
            case 2 -> new Dialog.Msg.Activate();
            case 3 -> new Dialog.Msg.Choose("b" + random.nextInt(4));
            case 4 -> new Dialog.Msg.Choose("gone");
            default -> new Dialog.Msg.Cancel();
        };
    }

    /**
     * A form over one to four fields whose validator also reports a field the form does not show.
     */
    private static Form formFixture(Random random) {
        List<Form.Field> fields = new ArrayList<>();
        for (int count = 1 + random.nextInt(4); count > 0; count--) {
            fields.add(Form.Field.of("f" + fields.size(), TEXTS[random.nextInt(TEXTS.length)]));
        }
        return new Form(Form.Config.of(fields).withValidator(values -> {
            List<Form.Error> errors = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                String text = values.text("f" + index);
                if (!text.isBlank() && text.length() % 2 == 0) {
                    errors.add(new Form.Error("f" + index, "even " + index));
                }
            }
            errors.add(new Form.Error("gone", "not a field"));
            return errors;
        }));
    }

    private static Form.Msg formMessage(Random random) {
        return switch (random.nextInt(8)) {
            case 0, 1 -> new Form.Msg.Next();
            case 2 -> new Form.Msg.Previous();
            case 3 -> new Form.Msg.Focus("f" + random.nextInt(5));
            case 4 -> new Form.Msg.Focus("gone");
            case 5 -> new Form.Msg.Validate(formValues(random));
            default -> new Form.Msg.Submit(formValues(random));
        };
    }

    private static Form.Values formValues(Random random) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < 4; index++) {
            if (random.nextBoolean()) {
                values.put("f" + index, TEXTS[random.nextInt(TEXTS.length)]);
            }
        }
        return Form.Values.of(values);
    }

    private static Map<String, Element<Form.Msg>> formEditors(Form.Model model, Random random) {
        Map<String, Element<Form.Msg>> editors = new LinkedHashMap<>();
        for (Form.Field field : model.fields()) {
            editors.put(field.id(), Elements.text(TEXTS[random.nextInt(TEXTS.length)]));
        }
        return editors;
    }

    /**
     * One frame of {@code view}, asserting every row it paints fits the viewport.
     */
    private static void paintedWithin(Element<?> view, String description, Object model) {
        assertThat(view).as(description, model).isNotNull();
        Screen painted = paint(view, VIEWPORT.rows(), VIEWPORT.cols());
        for (int row = 0; row < painted.rows(); row++) {
            assertThat(TextMeasure.columns(painted.plainLine(row)))
                    .as(
                            "%s at %s: row %d [%s] fills more than %d columns",
                            description, model, row, painted.plainLine(row), VIEWPORT.cols())
                    .isLessThanOrEqualTo(VIEWPORT.cols());
        }
    }

    /**
     * Options with distinct identities and the tricky content every widget has to survive.
     */
    private static List<Option> choiceOptions(Random random) {
        List<Option> options = new ArrayList<>();
        for (int count = 1 + random.nextInt(4); count > 0; count--) {
            options.add(Option.of("o" + options.size(), TEXTS[random.nextInt(TEXTS.length)]));
        }
        return options;
    }

    private static Select.Msg selectMessage(Random random) {
        return switch (random.nextInt(9)) {
            case 0 -> new Select.Msg.Up();
            case 1 -> new Select.Msg.Down();
            case 2 -> new Select.Msg.Home();
            case 3 -> new Select.Msg.End();
            case 4 -> new Select.Msg.Activate();
            case 5 -> new Select.Msg.ToggleOpen();
            case 6 -> new Select.Msg.Cancel();
            case 7 -> new Select.Msg.Choose("o" + random.nextInt(5));
            default -> new Select.Msg.Choose("gone");
        };
    }

    private static MultiSelect.Msg multiSelectMessage(Random random) {
        return switch (random.nextInt(9)) {
            case 0 -> new MultiSelect.Msg.Up();
            case 1 -> new MultiSelect.Msg.Down();
            case 2 -> new MultiSelect.Msg.Home();
            case 3 -> new MultiSelect.Msg.End();
            case 4 -> new MultiSelect.Msg.Toggle();
            case 5 -> new MultiSelect.Msg.ToggleOpen();
            case 6 -> new MultiSelect.Msg.Cancel();
            case 7 -> new MultiSelect.Msg.Choose("o" + random.nextInt(5));
            default -> new MultiSelect.Msg.Choose("gone");
        };
    }

    private static RadioGroup.Msg radioMessage(Random random) {
        return switch (random.nextInt(7)) {
            case 0 -> new RadioGroup.Msg.Up();
            case 1 -> new RadioGroup.Msg.Down();
            case 2 -> new RadioGroup.Msg.Home();
            case 3 -> new RadioGroup.Msg.End();
            case 4 -> new RadioGroup.Msg.Activate();
            case 5 -> new RadioGroup.Msg.Choose("o" + random.nextInt(5));
            default -> new RadioGroup.Msg.Choose("gone");
        };
    }

    /**
     * The row-level helpers the widgets share. A tab that survives expansion changes the columns of the
     * row that holds it, and a line break inside a single-row cell splits a row the grid counted as one.
     */
    @Test
    void theSharedRowHelpersLeaveNeitherTabsNorLineBreaksBehind() {
        Random random = new Random(313_373L);
        for (int round = 0; round < 6000; round++) {
            StyledText text = styled(random);
            StyledText expanded = TabStops.expand(text);
            assertThat(containsCharacter(expanded, '\t'))
                    .as("TabStops.expand left a tab in [%s]", expanded.plainText())
                    .isFalse();
            assertThat(TextMeasure.columns(expanded.plainText()))
                    .as("TabStops.expand shortened [%s] to [%s]", text.plainText(), expanded.plainText())
                    .isGreaterThanOrEqualTo(TextMeasure.columns(text.plainText()));
            assertThat(TabStops.expand(expanded))
                    .as("TabStops.expand is not stable on [%s]", expanded.plainText())
                    .isEqualTo(expanded);

            StyledText single = SingleRow.of(text);
            assertThat(containsCharacter(single, '\n'))
                    .as("SingleRow.of returned a line break for [%s]", text.plainText())
                    .isFalse();

            List<StyledText> shared = GridRows.shareOneWidth(List.of(styled(random), styled(random), styled(random)));
            int width = TextMeasure.columns(shared.getFirst().plainText());
            for (StyledText line : shared) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as(
                                "GridRows.shareOneWidth returned [%s] beside [%s]",
                                line.plainText(), shared.getFirst().plainText())
                        .isEqualTo(width);
            }
        }
    }

    /**
     * Markup text is arbitrary application content: it has to reach the grid without moving it.
     */
    @Test
    void markupContentMeasuresAndPaintsLikeAnyOtherText() {
        Random random = new Random(909_091L);
        for (int round = 0; round < 400; round++) {
            String source = MARKUP[random.nextInt(MARKUP.length)];
            Element<Object> view = Elements.text(Markup.parse(source));
            int cols = 1 + random.nextInt(24);
            int rows = Math.max(1, measure(view, Integer.MAX_VALUE, cols).rows());
            assertThat(measure(view, Integer.MAX_VALUE, cols).cols())
                    .as("markup [%s] at %d columns measured wider than it was offered", source, cols)
                    .isLessThanOrEqualTo(cols);
            Screen painted = paint(view, rows, cols);
            for (int row = 0; row < rows; row++) {
                assertThat(TextMeasure.columns(painted.plainLine(row)))
                        .as("markup [%s] at %dx%d: row %d overflows", source, rows, cols, row)
                        .isLessThanOrEqualTo(cols);
            }
        }
    }

    private static ScrollView.Msg scrollMessage(Random random, ScrollView.Content content) {
        return switch (random.nextInt(7)) {
            case 0 -> new ScrollView.Msg.Up(1 + random.nextInt(5));
            case 1 -> new ScrollView.Msg.Down(1 + random.nextInt(5));
            case 2 -> new ScrollView.Msg.PageUp();
            case 3 -> new ScrollView.Msg.PageDown();
            case 4 -> new ScrollView.Msg.Home();
            case 5 -> new ScrollView.Msg.End();
            default ->
                random.nextBoolean()
                        ? new ScrollView.Msg.ContentChanged(content(random))
                        : new ScrollView.Msg.ViewportChanged(1 + random.nextInt(8));
        };
    }

    private static Selector.Msg selectorMessage(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> new Selector.Msg.Up();
            case 1 -> new Selector.Msg.Down();
            case 2 -> new Selector.Msg.Confirm();
            default -> new Selector.Msg.Cancel();
        };
    }

    private static TableSelector.Msg cellMessage(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> new TableSelector.Msg.Up();
            case 1 -> new TableSelector.Msg.Down();
            case 2 -> new TableSelector.Msg.Left();
            case 3 -> new TableSelector.Msg.Right();
            case 4 -> new TableSelector.Msg.Confirm();
            default -> new TableSelector.Msg.Cancel();
        };
    }

    /**
     * The entries the picker is driven over: every awkward leaf the widgets share, as a name.
     */
    private static List<FilePicker.Entry> pickerEntries(Random random) {
        List<FilePicker.Entry> entries = new ArrayList<>();
        for (int count = random.nextInt(9); count > 0; count--) {
            String name = TEXTS[random.nextInt(TEXTS.length)] + (count % 2 == 0 ? "" : ".txt");
            FilePicker.Kind kind = switch (random.nextInt(4)) {
                case 0 -> FilePicker.Kind.DIRECTORY;
                case 1 -> FilePicker.Kind.FILE;
                case 2 -> FilePicker.Kind.OTHER;
                default -> FilePicker.Kind.MISSING;
            };
            entries.add(new FilePicker.Entry(
                    Path.of("/fixture").resolve(name),
                    kind,
                    random.nextInt(4096),
                    random.nextBoolean() ? "rw-r--r--" : "",
                    random.nextInt(4) == 0 ? "../somewhere" : "",
                    kind == FilePicker.Kind.FILE,
                    kind == FilePicker.Kind.DIRECTORY));
        }
        return List.copyOf(entries);
    }

    private static FilePicker.Msg pickerMessage(Random random) {
        return switch (random.nextInt(13)) {
            case 0 -> new FilePicker.Msg.Up();
            case 1 -> new FilePicker.Msg.Down();
            case 2 -> new FilePicker.Msg.PageUp();
            case 3 -> new FilePicker.Msg.PageDown();
            case 4 -> new FilePicker.Msg.First();
            case 5 -> new FilePicker.Msg.Last();
            case 6 -> new FilePicker.Msg.Open();
            case 7 -> new FilePicker.Msg.Parent();
            case 8 -> new FilePicker.Msg.Confirm();
            case 9 -> new FilePicker.Msg.Cancel();
            case 10 -> new FilePicker.Msg.ToggleHidden();
            case 11 -> new FilePicker.Msg.ToggleHelp();
            default -> new FilePicker.Msg.RowsChanged(random.nextInt(9));
        };
    }

    private static ScrollView.Content content(Random random) {
        List<StyledText> lines = new ArrayList<>();
        for (int count = random.nextInt(9); count > 0; count--) {
            lines.add(styled(random));
        }
        return ScrollView.Content.of(lines);
    }

    private static DataTableContent dataContent(Random random) {
        List<DataTable.Column> columns = new ArrayList<>();
        for (int column = 0; column < 1 + random.nextInt(4); column++) {
            int most = 4 + random.nextInt(8);
            DataTable.Width width = switch (random.nextInt(3)) {
                case 0 -> DataTable.Width.auto();
                case 1 -> DataTable.Width.fixed(random.nextInt(most + 1));
                default -> DataTable.Width.of(random.nextInt(most + 1), random.nextInt(most + 1), most);
            };
            columns.add(DataTable.Column.of("c" + column, TEXTS[random.nextInt(TEXTS.length)])
                    .withWidth(width)
                    .withAlignment(DataTable.Alignment.values()[random.nextInt(3)]));
        }
        List<DataTable.Row> rows = new ArrayList<>();
        for (int count = random.nextInt(8); count > 0; count--) {
            List<StyledText> cells = new ArrayList<>();
            for (int cell = random.nextInt(columns.size() + 2); cell > 0; cell--) {
                cells.add(styled(random));
            }
            rows.add(DataTable.Row.of("r" + rows.size(), cells));
        }
        return DataTableContent.of(columns, rows);
    }

    private static DataTableMsg dataMessage(Random random) {
        return switch (random.nextInt(14)) {
            case 0 -> new DataTableMsg.Up();
            case 1 -> new DataTableMsg.Down();
            case 2 -> new DataTableMsg.Left();
            case 3 -> new DataTableMsg.Right();
            case 4 -> new DataTableMsg.PageUp();
            case 5 -> new DataTableMsg.PageDown();
            case 6 -> new DataTableMsg.HalfPageUp();
            case 7 -> new DataTableMsg.HalfPageDown();
            case 8 -> new DataTableMsg.Top();
            case 9 -> new DataTableMsg.Bottom();
            case 10 -> new DataTableMsg.PanLeft(1 + random.nextInt(4));
            case 11 -> new DataTableMsg.PanRight(1 + random.nextInt(4));
            case 12 -> new DataTableMsg.ToggleMode();
            default -> new DataTableMsg.Resized(1 + random.nextInt(8), 1 + random.nextInt(40));
        };
    }

    private static List<String> options(Random random) {
        List<String> options = new ArrayList<>();
        for (int count = 1 + random.nextInt(4); count > 0; count--) {
            options.add(TEXTS[random.nextInt(TEXTS.length)]);
        }
        return options;
    }

    private static List<String> header(Random random) {
        List<String> header = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            header.add(TEXTS[random.nextInt(TEXTS.length)]);
        }
        return header;
    }

    private static List<List<String>> grid(Random random, int columns) {
        List<List<String>> rows = new ArrayList<>();
        for (int count = 1 + random.nextInt(4); count > 0; count--) {
            List<String> row = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                row.add(TEXTS[random.nextInt(TEXTS.length)]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static StyledText styled(Random random) {
        return StyledText.of(TEXTS[random.nextInt(TEXTS.length)], Style.NONE.withBold());
    }

    private static boolean containsCharacter(StyledText text, char value) {
        return text.plainText().indexOf(value) >= 0;
    }

    private static Element<Object> build(int depth, Random random) {
        if (depth <= 0 || random.nextInt(3) == 0) {
            return leaf(random);
        }
        return switch (random.nextInt(12)) {
            case 0 -> Elements.frame(BORDERS[random.nextInt(BORDERS.length)], child(depth - 1, random));
            case 1 ->
                Elements.frame(
                        BORDERS[random.nextInt(BORDERS.length)], styled(random), Style.NONE, child(depth - 1, random));
            case 2 -> Elements.pad(Insets.uniform(random.nextInt(3)), child(depth - 1, random));
            case 3 -> new Element.Group<>(items(depth - 1, random), Element.Direction.ROW, Alignment.START);
            case 4 -> new Element.Group<>(items(depth - 1, random), Element.Direction.COLUMN, Alignment.START);
            case 5 -> Elements.stack(child(depth - 1, random), child(depth - 1, random));
            case 6 -> Elements.fixed(random.nextInt(8), child(depth - 1, random));
            case 7 -> Elements.grow(1 + random.nextInt(4), child(depth - 1, random));
            case 8 -> Elements.spacer(random.nextInt(4));
            // A fill takes every cell it is offered, so a random parent that measures without a
            // bound would ask for an unbounded size. Its contract is asserted directly by
            // ElementsTest and against the legacy engine by LayoutParityTest.
            case 9 -> Elements.spacer(random.nextInt(3));
            default -> child(depth - 1, random);
        };
    }

    /**
     * Every widget that turns content into a view, chosen at random.
     */
    private static Element<Object> leaf(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> Elements.text(styled(random));
            case 1 ->
                Elements.text(Badge.of()
                        .append(styled(random))
                        .append(styled(random), new Color(10, 20, 30))
                        .render());
            case 2 -> Elements.text(Quote.of().append(styled(random)).bar(">").render());
            case 3 ->
                Elements.text(Table.of()
                        .buffer(random.nextInt(3))
                        .border(BORDERS[random.nextInt(BORDERS.length)])
                        .row(styled(random), styled(random))
                        .row(styled(random), styled(random))
                        .render());
            case 4 ->
                Elements.text(
                        ProgressBar.of(1 + random.nextInt(20)).render(random.nextInt(120), 1 + random.nextInt(100)));
            case 5 -> Elements.text(Markup.parse(MARKUP[random.nextInt(MARKUP.length)]));
            case 6 ->
                Elements.text(Gradient.apply(
                        styled(random),
                        List.of(new Color(255, 0, 0), new Color(0, 0, 255)),
                        Gradient.Direction.HORIZONTAL));
            default -> Elements.text(Box.of(styled(random)).render(2 + random.nextInt(20)));
        };
    }

    private static Element<Object> child(int depth, Random random) {
        return build(depth, random);
    }

    private static List<Element<Object>> items(int depth, Random random) {
        List<Element<Object>> items = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            items.add(build(depth, random));
        }
        return items;
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }
}
