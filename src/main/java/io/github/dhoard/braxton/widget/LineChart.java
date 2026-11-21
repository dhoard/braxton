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
import io.github.dhoard.braxton.input.PointerButton;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A line chart: one or more series drawn as a continuous line, with a reader that says which sample
 * it is on.
 *
 * <p>The plot is drawn in braille, so a cell carries a two-by-four grid of dots: a chart three rows
 * deep has twelve rows of vertical resolution rather than three, which is what makes a line look like
 * a line in a terminal instead of a staircase. Samples are connected across their sub-cells, so a
 * rising series is a rising line at every size, and a series with more samples than there are
 * sub-columns stays dense rather than dropping points.</p>
 *
 * <h2>Series</h2>
 *
 * <p>A series is a name, a {@link ThemeToken} its line is painted in and its samples left to right.
 * The name is painted in a legend row above the plot when at least one series has one and the chart
 * is more than one row tall, so with a single row the whole rectangle is plot; the token is the
 * semantic role the application chose, so a live theme switch repaints the chart and a chart cannot
 * disagree with the rest of the screen about what "warning" looks like.</p>
 *
 * <p>A sample that is not a number is a gap: it is skipped and the line breaks there, instead of
 * dropping to the bottom of the axis. Two series that share a cell share its dots - braille has one
 * character per cell and merging is the only honest answer - and the cell takes the style of the
 * first series in the list that painted a dot in it.</p>
 *
 * <h2>The reader</h2>
 *
 * <p>The model names the sample the reader is on; a guide column is painted in the theme's
 * {@link ThemeToken#BORDER} at that sample's column and the points the reader is on carry
 * {@link ThemeToken#FOCUS} while the chart owns focus. {@code left} and {@code right} step the
 * reader, {@code home} and {@code end} jump to the ends, and a press or drag puts it on the column
 * under the pointer. Every gesture that lands on a sample reports an {@link Output.Inspected} with
 * the index and every series' value there, which is how an application shows a readout beside the
 * chart.</p>
 *
 * <h2>Sizes</h2>
 *
 * <p>A chart fills the rectangle it is given: {@link #measure} takes every cell it is offered, so an
 * application places it with {@code Elements.grow} or {@code Elements.fixed} in a column, the way it
 * places {@link Fill}. A rectangle too small to hold two sub-columns or two sub-rows paints nothing
 * rather than a misleading mark, and a narrower pane compresses the samples instead of clipping
 * them.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A chart is one object per placed chart, held across frames the way a component is, because the
 * router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#lineChart(LineChart, LineChart.Model, java.util.function.Function)
 */
public final class LineChart
        implements Component<LineChart.Model, LineChart.Msg, LineChart.Output>,
                ThemedWidget<LineChart.Model, LineChart.Msg> {

    /**
     * The braille dot a sub-cell's position sets, indexed by sub-row and then by sub-column.
     */
    private static final int[][] DOTS = {
        {0x01, 0x08},
        {0x02, 0x10},
        {0x04, 0x20},
        {0x40, 0x80}
    };

    /**
     * The first code point of the braille block, which a cell with no dots is offset from.
     */
    private static final char BRAILLE = '\u2800';

    /**
     * The glyph a legend marks a series with.
     */
    private static final String LEGEND_MARKER = "\u25CF";

    /**
     * The columns between one legend entry and the next.
     */
    private static final String LEGEND_GAP = "  ";

    /**
     * The cell the reader's guide column is painted with.
     */
    private static final String GUIDE = "\u2502";

    /**
     * The sub-rows one cell is divided into.
     */
    private static final int SUB_ROWS = 4;

    /**
     * The sub-columns one cell is divided into.
     */
    private static final int SUB_COLUMNS = 2;

    private static final KeyMap<Msg> KEYS = KeyMap.of(KeyMap.group(
            "Reader",
            KeyAction.<Msg>of("previous-sample", new Msg.Left(), "\u2190", "previous sample", "left"),
            KeyAction.<Msg>of("next-sample", new Msg.Right(), "\u2192", "next sample", "right"),
            KeyAction.<Msg>of("first-sample", new Msg.Home(), "home", "first sample", "home"),
            KeyAction.<Msg>of("last-sample", new Msg.End(), "end", "last sample", "end")));

    /**
     * A chart over no series, which paints an empty rectangle until the application sends a model.
     */
    public LineChart() {}

    /**
     * One plotted series.
     *
     * @param label the name the legend paints; empty for a series left out of the legend
     * @param token the theme role the line is painted in; null is {@link ThemeToken#ACCENT}
     * @param values the samples, left to right
     */
    public record Series(String label, ThemeToken token, List<Double> values) {

        /**
         * Copies the samples and substitutes the defaults.
         */
        public Series {
            label = label == null ? "" : label;
            token = token == null ? ThemeToken.ACCENT : token;
            values = copy(values);
        }

        /**
         * A series without a name, painted in the theme's accent.
         *
         * @param values the samples, left to right
         * @return the series
         */
        public static Series of(List<Double> values) {
            return new Series("", ThemeToken.ACCENT, values);
        }

        /**
         * A named series, painted in the theme's accent.
         *
         * @param label the name the legend paints
         * @param values the samples, left to right
         * @return the series
         */
        public static Series of(String label, List<Double> values) {
            return new Series(label, ThemeToken.ACCENT, values);
        }

        /**
         * A named series in its own theme role.
         *
         * @param label the name the legend paints
         * @param token the theme role the line is painted in
         * @param values the samples, left to right
         * @return the series
         */
        public static Series of(String label, ThemeToken token, List<Double> values) {
            return new Series(label, token, values);
        }

        /**
         * This series under another name.
         *
         * @param value the name; null is empty
         * @return the new series
         */
        public Series withLabel(String value) {
            return new Series(value, token, values);
        }

        /**
         * This series in another theme role.
         *
         * @param value the role; null is {@link ThemeToken#ACCENT}
         * @return the new series
         */
        public Series withToken(ThemeToken value) {
            return new Series(label, value, values);
        }

        /**
         * How many samples the series has.
         *
         * @return the sample count
         */
        public int size() {
            return values.size();
        }

        /**
         * Copies the samples, reading a null entry as a gap.
         */
        private static List<Double> copy(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<Double> copy = new ArrayList<>(values.size());
            for (Double value : values) {
                copy.add(value == null ? Double.NaN : value);
            }
            return List.copyOf(copy);
        }
    }

    /**
     * Immutable chart state.
     *
     * <p>The canonical constructor copies the series, resolves the range - a null range is derived
     * from every finite sample of every series, so a chart scaled to its data needs no arithmetic
     * from the application - and clamps the reader into the samples, where {@code -1} means no
     * sample is selected.</p>
     *
     * @param series the series, painted in order
     * @param range the value range the plot is scaled to; null derives one from the samples
     * @param selected the sample the reader is on; {@code -1} for none
     * @param focused whether the chart owns focus
     */
    public record Model(List<Series> series, ChartRange range, int selected, boolean focused) {

        /**
         * Copies the series, resolves the range and clamps the reader.
         */
        public Model {
            series = copy(series);
            range = ChartRange.resolve(range, samples(series));
            selected = Math.max(-1, Math.min(selected, size(series) - 1));
        }

        /**
         * A focused chart over its series, with the reader on the newest sample.
         *
         * @param series the series, painted in order
         * @return the model
         */
        public static Model of(List<Series> series) {
            List<Series> copy = copy(series);
            return new Model(copy, null, size(copy) - 1, true);
        }

        /**
         * This chart over other series, re-scaled to them and with the reader clamped.
         *
         * <p>A data refresh re-derives the range, because the samples are what the plot is scaled
         * to. An application that keeps a fixed scale calls {@link #withRange(ChartRange)} with the
         * range it wants after the refresh.</p>
         *
         * @param value the series, painted in order
         * @return the new model
         */
        public Model withSeries(List<Series> value) {
            return new Model(value, null, selected, focused);
        }

        /**
         * This chart scaled to another range.
         *
         * @param value the range; null derives one from the samples
         * @return the new model
         */
        public Model withRange(ChartRange value) {
            return new Model(series, value, selected, focused);
        }

        /**
         * This chart with the reader on a sample, clamped as the constructor clamps it.
         *
         * @param value the sample to read; {@code -1} for none
         * @return the new model
         */
        public Model withSelected(int value) {
            return new Model(series, range, value, focused);
        }

        /**
         * The same chart, focused or not.
         *
         * @param value whether the chart owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(series, range, selected, value);
        }

        /**
         * The samples the chart plots: the longest series decides.
         *
         * @return the sample count
         */
        public int size() {
            return size(series);
        }

        /**
         * Every series' value at a sample.
         *
         * <p>The list is in the model's series order, so a caller can name each value; a series with
         * no sample at that index - it is shorter, or the sample is a gap - contributes
         * {@code NaN}.</p>
         *
         * @param index the sample's index
         * @return one value per series
         */
        public List<Double> values(int index) {
            List<Double> values = new ArrayList<>(series.size());
            for (Series one : series) {
                values.add(
                        index >= 0 && index < one.values().size() ? one.values().get(index) : Double.NaN);
            }
            return List.copyOf(values);
        }

        /**
         * Copies the series, rejecting a null entry.
         */
        private static List<Series> copy(List<Series> series) {
            if (series == null || series.isEmpty()) {
                return List.of();
            }
            List<Series> copy = new ArrayList<>(series.size());
            for (Series one : series) {
                copy.add(Objects.requireNonNull(one, "series"));
            }
            return List.copyOf(copy);
        }

        /**
         * Every sample of every series, for the range.
         */
        private static List<Double> samples(List<Series> series) {
            List<Double> samples = new ArrayList<>();
            for (Series one : series) {
                samples.addAll(one.values());
            }
            return samples;
        }

        /**
         * The sample count of the longest series.
         */
        private static int size(List<Series> series) {
            int size = 0;
            for (Series one : series) {
                size = Math.max(size, one.values().size());
            }
            return size;
        }
    }

    /**
     * The chart's messages.
     */
    public sealed interface Msg {

        /**
         * Reads the previous sample.
         */
        record Left() implements Msg {}

        /**
         * Reads the next sample.
         */
        record Right() implements Msg {}

        /**
         * Reads the first sample.
         */
        record Home() implements Msg {}

        /**
         * Reads the last sample.
         */
        record End() implements Msg {}

        /**
         * Reads a sample by index, which is what a press or a drag does.
         *
         * @param index the sample to read, clamped into the samples
         */
        record Select(int index) implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * The reader is on a sample.
         *
         * @param index the sample's index
         * @param values every series' value at that sample, in the model's series order
         */
        record Inspected(int index, List<Double> values) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * The chart's element: its rectangle, and the chart itself answering for it.
     *
     * @param model the state to paint; required
     * @return the element, emitting this chart's messages
     */
    public Element<Msg> element(Model model) {
        return new Element.Leaf<>(this, Objects.requireNonNull(model, "model"), Sizing.measured());
    }

    /**
     * The default actions: step the reader, or jump to either end.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KEYS;
    }

    /**
     * The actions this chart handles.
     *
     * @return the actions this chart handles
     */
    public KeyMap<Msg> keys() {
        return KEYS;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(Model.of(List.of()));
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
     * The reader's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        keys().actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * A press or a drag puts the reader on the column under the pointer.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!isReading(pointer) || bounds.isEmpty() || model.size() <= 0) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (local.column() < 0 || local.column() >= bounds.cols()) {
            return;
        }
        emit.accept(new Msg.Select(sampleAt(local.column(), bounds.cols(), model.size())));
    }

    /**
     * Every cell it is offered: a chart fills its pane and compresses its samples into it.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(Math.max(0, max.rows()), Math.max(0, max.cols()));
    }

    /**
     * Paints the legend, the reader's guide and then the series.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (model == null || bounds.isEmpty() || bounds.rows() <= 0 || bounds.cols() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        int legendRows = hasLegend(model) && bounds.rows() > 1 ? 1 : 0;
        if (legendRows > 0) {
            paintLegend(surface, bounds, model, resolved);
        }
        Rect plot = new Rect(bounds.row() + legendRows, bounds.col(), bounds.rows() - legendRows, bounds.cols());
        if (plot.rows() <= 0) {
            return;
        }
        int selectedColumn = -1;
        if (model.selected() >= 0) {
            selectedColumn = column(model.selected(), model.size(), plot.cols());
            paintGuide(surface, plot, selectedColumn, resolved);
        }
        paintSeries(surface, plot, model, resolved, selectedColumn);
    }

    /**
     * A chart with samples can take the keyboard; one with nothing to read cannot.
     */
    @Override
    public boolean canFocus(Model model) {
        return model != null && model.size() > 0;
    }

    /**
     * Focus is model data: the application sets it and the chart only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model != null && model.focused();
    }

    /**
     * The column a sample lands in, over a plot of a given width.
     *
     * @param index the sample's index
     * @param count the samples there are; positive
     * @param columns the plot's columns; positive
     * @return the column, from zero to {@code columns - 1}
     */
    static int column(int index, int count, int columns) {
        return subColumn(index, count, columns * SUB_COLUMNS) / SUB_COLUMNS;
    }

    /**
     * The sample a column reads.
     *
     * @param column the column the pointer is on
     * @param columns the plot's columns; positive
     * @param count the samples there are; positive
     * @return the sample's index
     */
    static int sampleAt(int column, int columns, int count) {
        if (count <= 0) {
            return 0;
        }
        if (columns <= 1) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(column, columns - 1));
        long index = Math.round((double) clamped * (count - 1) / (columns - 1));
        return (int) Math.min(count - 1, index);
    }

    /**
     * The sub-column a sample sits in, evenly spaced across the plot's sub-columns.
     */
    private static int subColumn(int index, int count, int subColumns) {
        if (count <= 1 || subColumns <= 1) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(index, count - 1));
        return (int) Math.min(subColumns - 1, Math.round((double) clamped * (subColumns - 1) / (count - 1)));
    }

    /**
     * The sub-row a value sits in; the axis grows upwards, so a high value is a low sub-row.
     */
    private static int subRow(double fraction, int subRows) {
        if (subRows <= 1) {
            return 0;
        }
        int row = (int) Math.round((1.0 - Math.max(0.0, Math.min(1.0, fraction))) * (subRows - 1));
        return Math.max(0, Math.min(subRows - 1, row));
    }

    /**
     * Whether any series asked for a legend entry.
     */
    private static boolean hasLegend(Model model) {
        for (Series series : model.series()) {
            if (!series.label().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The legend row: a marker and the series name for every named series, in order.
     */
    private static void paintLegend(Surface surface, Rect bounds, Model model, Theme theme) {
        StyledText legend = StyledText.empty();
        for (Series series : model.series()) {
            if (series.label().isEmpty()) {
                continue;
            }
            if (!legend.isEmpty()) {
                legend = legend.append(LEGEND_GAP);
            }
            legend = legend.append(StyledText.of(LEGEND_MARKER, theme.style(series.token())))
                    .append(" ")
                    .append(RowText.styled(
                            RowText.singleRow(StyledText.plain(series.label())), theme.style(ThemeToken.MUTED)));
        }
        if (!legend.isEmpty()) {
            surface.write(new Rect(bounds.row(), bounds.col(), 1, bounds.cols()), legend, WidthPolicy.PINNED);
        }
    }

    /**
     * The reader's guide: a vertical rule at the selected sample's column.
     */
    private static void paintGuide(Surface surface, Rect plot, int column, Theme theme) {
        Style style = theme.style(ThemeToken.BORDER);
        for (int row = 0; row < plot.rows(); row++) {
            surface.put(plot.row() + row, plot.col() + column, GUIDE, style, null, 1);
        }
    }

    /**
     * The series, connected across the plot's sub-cells.
     */
    private static void paintSeries(Surface surface, Rect plot, Model model, Theme theme, int selectedColumn) {
        int subColumns = plot.cols() * SUB_COLUMNS;
        int subRows = plot.rows() * SUB_ROWS;
        int[][] bits = new int[plot.rows()][plot.cols()];
        int[][] owner = new int[plot.rows()][plot.cols()];
        for (int row = 0; row < plot.rows(); row++) {
            for (int column = 0; column < plot.cols(); column++) {
                owner[row][column] = -1;
            }
        }
        List<Series> series = model.series();
        for (int index = 0; index < series.size(); index++) {
            plotInto(bits, owner, index, series.get(index), model.range(), subColumns, subRows);
        }
        int selectedSubRow = selectedSubRow(model, subRows);
        int selectedRow = selectedSubRow < 0 ? -1 : selectedSubRow / SUB_ROWS;
        for (int row = 0; row < plot.rows(); row++) {
            for (int column = 0; column < plot.cols(); column++) {
                if (bits[row][column] == 0) {
                    continue;
                }
                Series painter = series.get(owner[row][column]);
                Style style = theme.style(painter.token());
                if (model.focused() && row == selectedRow && column == selectedColumn) {
                    style = style.merge(theme.style(ThemeToken.FOCUS));
                }
                surface.put(
                        plot.row() + row,
                        plot.col() + column,
                        String.valueOf((char) (BRAILLE + bits[row][column])),
                        style,
                        null,
                        1);
            }
        }
    }

    /**
     * The sub-row the selected sample sits in, or {@code -1} when there is no reader.
     */
    private static int selectedSubRow(Model model, int subRows) {
        if (model.selected() < 0) {
            return -1;
        }
        for (Series series : model.series()) {
            if (model.selected() < series.values().size()) {
                double value = series.values().get(model.selected());
                if (Double.isFinite(value)) {
                    return subRow(model.range().fraction(value), subRows);
                }
            }
        }
        return -1;
    }

    /**
     * Sets the dots one series paints, connecting each sample to the next.
     */
    private static void plotInto(
            int[][] bits, int[][] owner, int index, Series series, ChartRange range, int subColumns, int subRows) {
        List<Double> values = series.values();
        int previousX = -1;
        int previousY = -1;
        boolean connected = false;
        for (int sample = 0; sample < values.size(); sample++) {
            double value = values.get(sample);
            if (!Double.isFinite(value)) {
                // A gap breaks the line: the next sample starts a new run rather than being joined
                // across a reading nobody has.
                connected = false;
                continue;
            }
            int x = subColumn(sample, values.size(), subColumns);
            int y = subRow(range.fraction(value), subRows);
            if (connected) {
                connect(bits, owner, index, previousX, previousY, x, y);
            } else {
                dot(bits, owner, index, x, y);
            }
            previousX = x;
            previousY = y;
            connected = true;
        }
    }

    /**
     * Joins two sub-cells with a run of dots, including both ends.
     */
    private static void connect(int[][] bits, int[][] owner, int index, int x0, int y0, int x1, int y1) {
        if (x0 == x1) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                dot(bits, owner, index, x0, y);
            }
            return;
        }
        int step = x1 > x0 ? 1 : -1;
        for (int x = x0; x != x1 + step; x += step) {
            double along = (double) (x - x0) / (x1 - x0);
            dot(bits, owner, index, x, (int) Math.round(y0 + (y1 - y0) * along));
        }
    }

    /**
     * Sets one dot, keeping the first series that painted the cell as its owner.
     */
    private static void dot(int[][] bits, int[][] owner, int index, int subColumn, int subRow) {
        int column = subColumn / SUB_COLUMNS;
        int row = subRow / SUB_ROWS;
        if (row < 0 || row >= bits.length || column < 0 || column >= bits[row].length) {
            return;
        }
        bits[row][column] |= DOTS[subRow % SUB_ROWS][subColumn % SUB_COLUMNS];
        if (owner[row][column] < 0) {
            owner[row][column] = index;
        }
    }

    /**
     * Whether a pointer report is a press or a drag, which are the gestures a reader answers.
     */
    private static boolean isReading(PointerEvent pointer) {
        if (pointer == null) {
            return false;
        }
        if (pointer.isPress()) {
            return pointer.button() == PointerButton.LEFT;
        }
        return pointer.isMotion() && pointer.button() == PointerButton.LEFT;
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Left left -> move(state, -1);
            case Msg.Right right -> move(state, 1);
            case Msg.Home home -> jump(state, true);
            case Msg.End end -> jump(state, false);
            case Msg.Select select -> read(state, select.index());
        };
    }

    /**
     * The reader one sample along, which reports nothing when there is nowhere to go.
     */
    private static Result move(Model state, int delta) {
        int target = state.selected() + delta;
        if (state.selected() < 0 || target < 0 || target >= state.size()) {
            return new Result(state, null);
        }
        return read(state, target);
    }

    /**
     * The reader at the first or the last sample.
     */
    private static Result jump(Model state, boolean first) {
        if (state.size() <= 0) {
            return new Result(state, null);
        }
        return read(state, first ? 0 : state.size() - 1);
    }

    /**
     * Reads a sample, clamped into the samples, and reports it even when it was already selected.
     */
    private static Result read(Model state, int index) {
        if (state.size() <= 0) {
            return new Result(state, null);
        }
        Model next = state.withSelected(index);
        return new Result(next, new Output.Inspected(next.selected(), next.values(next.selected())));
    }
}
