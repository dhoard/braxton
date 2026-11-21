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
import io.github.dhoard.braxton.style.TextMeasure;
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
 * A bar chart: one labeled bar per category, in a window the model owns, with a reader that says
 * which category it is on.
 *
 * <p>Bars run horizontally because that is what a terminal cell is good at: a label, a bar and a
 * value fit on one row each, a long category name is clipped rather than pushing its neighbors, and
 * a chart of forty categories is a list that scrolls instead of forty columns one cell wide. The bar
 * grows from zero, so a negative value grows to the left of the zero column and a reader can see the
 * sign of a change as well as its size.</p>
 *
 * <h2>Categories</h2>
 *
 * <p>A category is an identity, a label and a value. The identity is what the selection names, so an
 * application that reorders or reloads its data keeps the reader on the category it was on rather
 * than on a row number that now means something else; a selection that names nothing falls back to
 * the first category, which is the rule {@link Menu} and {@link NotificationCenter} follow. A value
 * that is not a number is a gap: the row is painted with its label and its readout and no bar.</p>
 *
 * <p>The range is the axis every bar is measured against. It always includes zero, because a bar
 * grows from zero; an explicit range is extended to zero rather than rejected, and a range derived
 * from the data is the span of its values. A value outside an explicit range is clamped onto the end
 * it is past.</p>
 *
 * <h2>Keys, pointer and the window</h2>
 *
 * <p>{@code up} and {@code down} step the reader, {@code home} and {@code end} jump to the ends, and
 * a press or a drag selects the row under the pointer. Every gesture that lands on a category
 * reports an {@link Output.Inspected} with the category itself, which is how an application shows a
 * reading or a detail panel; a gesture at either end moves nothing and reports nothing. The model
 * carries the window: the first row painted and how many rows may be painted, kept over the
 * selection by {@link RowWindow}, so a chart taller than its pane scrolls with the reader instead of
 * losing it. {@link Msg.RowsChanged} is how a parent that knows the pane's height tells the chart.</p>
 *
 * <h2>Themes and narrow panes</h2>
 *
 * <p>A label is the theme's {@link ThemeToken#TEXT}, a bar its {@link ThemeToken#ACCENT}, a readout
 * its {@link ThemeToken#MUTED}, and the selected row is filled with {@link ThemeToken#SELECTED} with
 * {@link ThemeToken#FOCUS} merged in while the chart owns focus. A narrow pane keeps the bar and
 * gives up the rest: the readout is dropped first and the label second, because the bar is what the
 * chart is for.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A chart is one object per placed chart, held across frames the way a component is, because the
 * router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#barChart(BarChart, BarChart.Model, java.util.function.Function)
 */
public final class BarChart
        implements Component<BarChart.Model, BarChart.Msg, BarChart.Output>,
                ThemedWidget<BarChart.Model, BarChart.Msg> {

    /**
     * The rows a chart paints when it is initialized without a height of its own.
     */
    public static final int DEFAULT_ROWS = 10;

    /**
     * The least columns a bar is worth painting in, which is what a row keeps before it trims.
     */
    private static final int MIN_BAR = 3;

    private static final KeyMap<Msg> KEYS = KeyMap.of(KeyMap.group(
            "Reader",
            KeyAction.<Msg>of("previous-category", new Msg.Up(), "\u2191", "previous category", "up"),
            KeyAction.<Msg>of("next-category", new Msg.Down(), "\u2193", "next category", "down"),
            KeyAction.<Msg>of("first-category", new Msg.Home(), "home", "first category", "home"),
            KeyAction.<Msg>of("last-category", new Msg.End(), "end", "last category", "end")));

    private final Config config;

    /**
     * A chart with its default configuration.
     */
    public BarChart() {
        this(Config.defaults());
    }

    /**
     * A chart over its configuration.
     *
     * @param config the window and readout rules; required
     */
    public BarChart(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One bar.
     *
     * @param id the identity, unique across the chart; never blank
     * @param label the label painted at the left; null is empty
     * @param value the value the bar is drawn for; a value that is not a number is a gap
     */
    public record Category(String id, StyledText label, double value) {

        /**
         * Rejects a category without an id and substitutes an empty label.
         */
        public Category {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("category id is required");
            }
            label = label == null ? StyledText.empty() : label;
        }

        /**
         * A category with a plain label.
         *
         * @param id the identity; never blank
         * @param label the plain label; null is empty
         * @param value the value the bar is drawn for
         * @return the category
         */
        public static Category of(String id, String label, double value) {
            return new Category(id, StyledText.plain(label), value);
        }

        /**
         * This category with another value.
         *
         * @param newValue the value the bar is drawn for
         * @return the new category
         */
        public Category withValue(double newValue) {
            return new Category(id, label, newValue);
        }

        /**
         * This category with another label.
         *
         * @param newLabel the label; null is empty
         * @return the new category
         */
        public Category withLabel(StyledText newLabel) {
            return new Category(id, newLabel, value);
        }
    }

    /**
     * Immutable chart state.
     *
     * <p>The canonical constructor copies the categories, resolves the range - a null range is
     * derived from the values and extended to zero, because a bar grows from zero - resolves the
     * selection onto a category, and keeps the window over it.</p>
     *
     * @param categories the categories, top to bottom
     * @param range the axis the bars are measured against; null derives one from the values
     * @param selected the id of the selected category, or empty when the chart has none
     * @param focused whether the chart owns focus
     * @param offset the first painted row; clamped into the categories
     * @param rows the rows the window may paint; never negative
     */
    public record Model(
            List<Category> categories, ChartRange range, String selected, boolean focused, int offset, int rows) {

        /**
         * Copies the categories, resolves the range and the selection, and admits the window.
         */
        public Model {
            categories = copy(categories);
            range = ChartRange.resolve(range, values(categories)).withZero();
            rows = Math.max(0, rows);
            selected = resolve(categories, selected);
            offset = RowWindow.admit(indexOf(categories, selected), categories.size(), Math.max(0, offset), rows);
        }

        /**
         * A focused chart over its categories, showing the first one.
         *
         * @param categories the categories, top to bottom
         * @return the model
         */
        public static Model of(List<Category> categories) {
            return new Model(categories, null, "", true, 0, DEFAULT_ROWS);
        }

        /**
         * This chart over other categories, re-scaled to them and with the selection resolved.
         *
         * <p>A data refresh re-derives the range, because the values are what the axis spans. An
         * application that keeps a fixed axis calls {@link #withRange(ChartRange)} with the range it
         * wants after the refresh.</p>
         *
         * @param value the categories, top to bottom
         * @return the new model
         */
        public Model withCategories(List<Category> value) {
            return new Model(value, null, selected, focused, offset, rows);
        }

        /**
         * This chart measured against another axis.
         *
         * @param value the axis; null derives one from the values
         * @return the new model
         */
        public Model withRange(ChartRange value) {
            return new Model(categories, value, selected, focused, offset, rows);
        }

        /**
         * This chart with another category selected, resolved as the constructor resolves it.
         *
         * @param value the id to select; null or an unknown id falls to the first category
         * @return the new model
         */
        public Model withSelected(String value) {
            return new Model(categories, range, value, focused, offset, rows);
        }

        /**
         * The same chart, focused or not.
         *
         * @param value whether the chart owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(categories, range, selected, value, offset, rows);
        }

        /**
         * This chart with a window of a different height.
         *
         * @param value the rows the window may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(categories, range, selected, focused, offset, value);
        }

        /**
         * The selected category.
         *
         * @return the category, or null when the chart has none
         */
        public Category selectedCategory() {
            int index = indexOf(categories, selected);
            return index < 0 ? null : categories.get(index);
        }

        /**
         * Copies the categories, rejecting a null entry.
         */
        private static List<Category> copy(List<Category> categories) {
            if (categories == null || categories.isEmpty()) {
                return List.of();
            }
            List<Category> copy = new ArrayList<>(categories.size());
            for (Category category : categories) {
                copy.add(Objects.requireNonNull(category, "category"));
            }
            return List.copyOf(copy);
        }

        /**
         * The values of the categories, for the axis.
         */
        private static List<Double> values(List<Category> categories) {
            List<Double> values = new ArrayList<>(categories.size());
            for (Category category : categories) {
                values.add(category.value());
            }
            return values;
        }

        /**
         * The row of a category, or -1.
         */
        private static int indexOf(List<Category> categories, String id) {
            if (id == null || id.isBlank()) {
                return -1;
            }
            for (int index = 0; index < categories.size(); index++) {
                if (categories.get(index).id().equals(id)) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * The selection a list of categories admits.
         */
        private static String resolve(List<Category> categories, String selected) {
            int index = indexOf(categories, selected);
            return index >= 0
                    ? categories.get(index).id()
                    : categories.isEmpty() ? "" : categories.get(0).id();
        }
    }

    /**
     * The chart's messages.
     */
    public sealed interface Msg {

        /**
         * Selects the previous category.
         */
        record Up() implements Msg {}

        /**
         * Selects the next category.
         */
        record Down() implements Msg {}

        /**
         * Selects the first category.
         */
        record Home() implements Msg {}

        /**
         * Selects the last category.
         */
        record End() implements Msg {}

        /**
         * Selects a category by id, which is what a press or a drag does.
         *
         * @param id the category to select; an unknown id selects nothing
         */
        record Select(String id) implements Msg {}

        /**
         * The window is a different height.
         *
         * @param rows the rows the window may paint; never negative
         */
        record RowsChanged(int rows) implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * The reader is on a category.
         *
         * @param id the category's id
         * @param category the category itself
         */
        record Inspected(String id, Category category) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a chart is configured.
     *
     * @param rows the rows the window paints when no message names a height; never negative
     * @param showValues whether each bar's value is painted at the right edge
     */
    public record Config(int rows, boolean showValues) {

        /**
         * Rejects a negative height.
         */
        public Config {
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
        }

        /**
         * The default configuration: ten rows and a readout on every bar.
         *
         * @return the configuration
         */
        public static Config defaults() {
            return new Config(DEFAULT_ROWS, true);
        }

        /**
         * This configuration whose window paints a different height.
         *
         * @param value the rows; never negative
         * @return the new configuration
         */
        public Config withRows(int value) {
            return new Config(value, showValues);
        }

        /**
         * This configuration with or without a readout on each bar.
         *
         * @param value whether each bar's value is painted
         * @return the new configuration
         */
        public Config withShowValues(boolean value) {
            return new Config(rows, value);
        }
    }

    /**
     * The columns a painted row is divided into.
     *
     * <p>A row is a label, a bar and a readout, in that order, with one separating column between
     * them. The bar keeps at least one column, so a narrow row gives the readout up first and the
     * label second.</p>
     *
     * @param label the columns the label may use; zero when the row has no label
     * @param bar the columns the bar may use
     * @param value the columns the readout may use; zero when it is dropped
     */
    record Layout(int label, int bar, int value) {}

    /**
     * How this chart is configured.
     *
     * @return how this chart is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The chart's element: its rows, and the chart itself answering for them.
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
        return Update.next(new Model(List.of(), null, "", true, 0, config.rows()));
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
     * A press or a drag selects the row under the pointer.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!isReading(pointer) || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        int painted = paintedRows(model, bounds.rows());
        if (local.row() < 0 || local.row() >= painted) {
            return;
        }
        emit.accept(new Msg.Select(
                model.categories().get(model.offset() + local.row()).id()));
    }

    /**
     * The rows the window paints, across the columns it is offered.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(paintedRows(model, Math.max(0, max.rows())), Math.max(0, max.cols()));
    }

    /**
     * Paints the visible rows, each a label, a bar and a readout.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (model == null || bounds.isEmpty() || bounds.rows() <= 0 || bounds.cols() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Layout layout = layout(model, config.showValues(), bounds.cols());
        int painted = paintedRows(model, bounds.rows());
        for (int row = 0; row < painted; row++) {
            paintRow(
                    surface,
                    bounds,
                    model,
                    layout,
                    resolved,
                    row,
                    model.categories().get(model.offset() + row));
        }
    }

    /**
     * A chart with categories can take the keyboard; one with nothing to read cannot.
     */
    @Override
    public boolean canFocus(Model model) {
        return model != null && !model.categories().isEmpty();
    }

    /**
     * Focus is model data: the application sets it and the chart only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model != null && model.focused();
    }

    /**
     * The columns a painted row is divided into at a width.
     *
     * @param model the chart the row is for
     * @param showValues whether the row paints a readout
     * @param cols the columns the row has
     * @return the division
     */
    static Layout layout(Model model, boolean showValues, int cols) {
        if (model == null || cols <= 0 || model.categories().isEmpty()) {
            return new Layout(0, 0, 0);
        }
        int widestLabel = 0;
        int widestValue = 0;
        for (Category category : model.categories()) {
            widestLabel = Math.max(
                    widestLabel,
                    TextMeasure.columns(RowText.singleRow(category.label()).plainText(), WidthPolicy.PINNED));
            widestValue =
                    Math.max(widestValue, BlockGlyphs.value(category.value()).length());
        }
        int label = Math.min(widestLabel, cols / 3);
        int value = showValues ? Math.min(widestValue, cols / 4) : 0;
        int gaps = (label > 0 ? 1 : 0) + (value > 0 ? 1 : 0);
        int bar = cols - label - value - gaps;
        if (bar < MIN_BAR) {
            // The bar is what the chart is for, so a narrow row gives up the readout first...
            value = 0;
            gaps = label > 0 ? 1 : 0;
            bar = cols - label - gaps;
        }
        if (bar < MIN_BAR) {
            // ...and the label only when there is nothing else left.
            label = 0;
            bar = cols;
        }
        return new Layout(label, Math.max(0, bar), value);
    }

    /**
     * The column a value's bar starts at.
     *
     * @param model the chart the bar belongs to
     * @param bar the columns the bar region has
     * @return the zero column, from the left of the bar region
     */
    static int origin(Model model, int bar) {
        if (model == null || bar <= 0) {
            return 0;
        }
        return (int) Math.min(bar, Math.round(model.range().fraction(0.0) * bar));
    }

    /**
     * The eighths of the bar region a value's bar covers, and the direction it grows in.
     *
     * @param model the chart the bar belongs to
     * @param value the value the bar is drawn for
     * @param bar the columns the bar region has
     * @return the eighths the bar covers, signed: positive grows right, negative grows left
     */
    static int eighths(Model model, double value, int bar) {
        if (model == null || bar <= 0 || !Double.isFinite(value)) {
            return 0;
        }
        int end = (int) Math.round(model.range().fraction(value) * bar * 8);
        int length = end - origin(model, bar) * 8;
        if (length == 0 && model.range().fraction(value) != model.range().fraction(0.0)) {
            // A value off zero paints a mark even when it rounds into the zero column, so a chart of
            // small values does not look like a chart of no values.
            return value > 0.0 ? 1 : -1;
        }
        return Math.max(-bar * 8, Math.min(bar * 8, length));
    }

    /**
     * The rows the window paints inside a pane of a height.
     */
    private static int paintedRows(Model model, int rows) {
        if (model == null) {
            return 0;
        }
        int window = Math.max(0, Math.min(model.rows(), rows));
        return Math.max(0, Math.min(window, model.categories().size() - model.offset()));
    }

    /**
     * One row: its selection fill, its label, its bar and its readout.
     */
    private static void paintRow(
            Surface surface, Rect bounds, Model model, Layout layout, Theme theme, int row, Category category) {
        int top = bounds.row() + row;
        boolean marked = category.id().equals(model.selected());
        if (marked) {
            surface.fill(new Rect(top, bounds.col(), 1, bounds.cols()), theme.style(ThemeToken.SELECTED));
        }
        Style focus = theme.style(ThemeToken.FOCUS);
        Style label = theme.style(ThemeToken.TEXT);
        Style bar = theme.style(ThemeToken.ACCENT);
        Style value = theme.style(ThemeToken.MUTED);
        if (marked && model.focused()) {
            label = label.merge(focus);
            bar = bar.merge(focus);
            value = value.merge(focus);
        }
        int col = bounds.col();
        if (layout.label() > 0) {
            surface.write(
                    new Rect(top, col, 1, layout.label()),
                    RowText.styled(RowText.singleRow(category.label()), label),
                    WidthPolicy.PINNED);
            col += layout.label() + 1;
        }
        if (layout.bar() > 0) {
            paintBar(surface, top, col, layout.bar(), model, category.value(), bar);
        }
        if (layout.value() > 0) {
            int start = bounds.col() + bounds.cols() - layout.value();
            surface.write(
                    new Rect(top, start, 1, layout.value()),
                    RowText.styled(StyledText.plain(BlockGlyphs.value(category.value())), value),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * One bar: whole blocks from the zero column, and the eighth a positive value stops in.
     *
     * <p>A positive bar ends in the eighth its value reaches. A negative bar is quantized to whole
     * cells, because the eighth-block glyphs fill a cell from its left and a bar growing left would
     * need the ones that fill from its right, which the block range does not have: a bar ending
     * between two cells would show a gap instead of an end. The zero column is the same for every
     * row, so the two directions meet on one cell boundary.</p>
     */
    private static void paintBar(Surface surface, int row, int col, int bar, Model model, double value, Style style) {
        int eighths = eighths(model, value, bar);
        int origin = origin(model, bar);
        int whole = Math.abs(eighths) / 8;
        int part = Math.abs(eighths) % 8;
        StringBuilder blocks = new StringBuilder();
        int start;
        if (eighths < 0) {
            whole = (int) Math.round(Math.abs(eighths) / 8.0);
            start = origin - whole;
            blocks.append(BlockGlyphs.FULL.repeat(whole));
        } else {
            start = origin;
            blocks.append(BlockGlyphs.FULL.repeat(whole));
            if (part > 0) {
                blocks.append(BlockGlyphs.eighth(part));
            }
        }
        if (blocks.isEmpty() || start < 0 || start >= bar) {
            return;
        }
        surface.write(
                new Rect(row, col + start, 1, bar - start),
                StyledText.of(blocks.toString(), style),
                WidthPolicy.PINNED);
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
            case Msg.Up up -> move(state, -1);
            case Msg.Down down -> move(state, 1);
            case Msg.Home home -> jump(state, true);
            case Msg.End end -> jump(state, false);
            case Msg.Select select -> read(state, select.id());
            case Msg.RowsChanged changed -> new Result(state.withRows(changed.rows()), null);
        };
    }

    /**
     * The reader one category along, which reports nothing when there is nowhere to go.
     */
    private static Result move(Model state, int delta) {
        int current = indexOf(state.categories(), state.selected());
        int target = current + delta;
        if (current < 0 || target < 0 || target >= state.categories().size()) {
            return new Result(state, null);
        }
        return read(state, state.categories().get(target).id());
    }

    /**
     * The reader at the first or the last category.
     */
    private static Result jump(Model state, boolean first) {
        if (state.categories().isEmpty()) {
            return new Result(state, null);
        }
        return read(
                state,
                state.categories()
                        .get(first ? 0 : state.categories().size() - 1)
                        .id());
    }

    /**
     * Reads a category by id, which reports it even when it was already selected.
     */
    private static Result read(Model state, String id) {
        if (indexOf(state.categories(), id) < 0) {
            // A press that names a category the chart does not hold is not a reading, and the
            // constructor's fallback to the first category is not an answer to it.
            return new Result(state, null);
        }
        Model next = state.withSelected(id);
        Category category = next.selectedCategory();
        return category == null
                ? new Result(state, null)
                : new Result(next, new Output.Inspected(category.id(), category));
    }

    /**
     * The row of a category, or -1.
     */
    private static int indexOf(List<Category> categories, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < categories.size(); index++) {
            if (categories.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }
}
