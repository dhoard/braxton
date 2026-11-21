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
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A one-row trend: the shape of a series in the cells it is given, with a reader that says which
 * sample the caret is on.
 *
 * <p>A sparkline is the smallest honest chart: no axis, no labels, one row of eighth-height blocks
 * whose heights are the values. It answers the question a status pane actually asks - is this number
 * going up, going down, or jumping about - without spending the rows a {@link LineChart} needs.</p>
 *
 * <h2>More samples than cells</h2>
 *
 * <p>A sparkline compresses rather than clips. Each cell covers the run of samples that land in it,
 * and paints the <em>highest</em> of them, because a trend is read for its peaks: a one-cell spike
 * that a mean would flatten is exactly what a reader is looking for. With fewer samples than cells
 * every sample keeps its own cell - a sample covers the cells its share of the width reaches - so a
 * short series paints across the whole width and a long one keeps its shape in a narrow pane.</p>
 *
 * <h2>The reader</h2>
 *
 * <p>The model names the sample the reader is on ({@link Model#selected()}); {@code left} and
 * {@code right} step it, {@code home} and {@code end} jump to the ends, and a press or drag puts it
 * on the cell under the pointer. Every gesture that lands on a sample reports an {@link
 * Output.Inspected} with the sample's index and value, which is how an application shows the reading
 * beside the trend; a gesture at either end moves nothing and reports nothing, and a press on the
 * sample already selected reports it again, because the reader asked to see it.</p>
 *
 * <p>A point that is not a number is a gap, not a value: it is skipped when the cell's peak is
 * chosen, and a cell whose samples are all gaps stays blank. That is how a series with missing
 * readings is drawn - the line stops, rather than dropping to the bottom of the axis.</p>
 *
 * <h2>Themes and sizes</h2>
 *
 * <p>The samples are painted in the theme's {@link ThemeToken#ACCENT} and the reader's cell adds
 * {@link ThemeToken#SELECTED} and, while the sparkline owns focus, {@link ThemeToken#FOCUS}. A
 * sparkline is one row: it asks for one row of every row it is offered, paints that row across the
 * columns it is given, and clips a narrower pane like every other one-row widget. A model with no
 * samples paints nothing but still reserves its row, so a pane does not jump when data arrives.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A sparkline is one object per placed sparkline, held across frames the way a component is,
 * because the router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#sparkline(Sparkline, Sparkline.Model, java.util.function.Function)
 */
public final class Sparkline
        implements Component<Sparkline.Model, Sparkline.Msg, Sparkline.Output>,
                ThemedWidget<Sparkline.Model, Sparkline.Msg> {

    private static final KeyMap<Msg> KEYS = KeyMap.of(KeyMap.group(
            "Reader",
            KeyAction.<Msg>of("previous-sample", new Msg.Left(), "\u2190", "previous sample", "left"),
            KeyAction.<Msg>of("next-sample", new Msg.Right(), "\u2192", "next sample", "right"),
            KeyAction.<Msg>of("first-sample", new Msg.Home(), "home", "first sample", "home"),
            KeyAction.<Msg>of("last-sample", new Msg.End(), "end", "last sample", "end")));

    /**
     * A sparkline over no data, which paints an empty row until the application sends a model.
     */
    public Sparkline() {}

    /**
     * Immutable sparkline state.
     *
     * <p>The canonical constructor copies the samples, resolves the range - a null range is derived
     * from the samples, so a model scaled to its data needs no arithmetic from the application - and
     * clamps the reader into the samples, where {@code -1} means no sample is selected.</p>
     *
     * @param values the samples, left to right
     * @param range the value range the row is scaled to; null derives one from the samples
     * @param selected the sample the reader is on; {@code -1} for none
     * @param focused whether the sparkline owns focus
     */
    public record Model(List<Double> values, ChartRange range, int selected, boolean focused) {

        /**
         * Copies the samples, resolves the range and clamps the reader.
         */
        public Model {
            values = copy(values);
            range = ChartRange.resolve(range, values);
            selected = values.isEmpty() ? -1 : Math.max(-1, Math.min(selected, values.size() - 1));
        }

        /**
         * A focused sparkline over its samples, with the reader on the newest one.
         *
         * @param values the samples, left to right
         * @return the model
         */
        public static Model of(List<Double> values) {
            List<Double> copy = copy(values);
            return new Model(copy, null, copy.size() - 1, true);
        }

        /**
         * This sparkline over other samples, re-scaled to them and with the reader clamped.
         *
         * <p>A data refresh re-derives the range, because the samples are what the row is scaled to.
         * An application that keeps a fixed scale calls {@link #withRange(ChartRange)} with the range
         * it wants after the refresh.</p>
         *
         * @param value the samples, left to right
         * @return the new model
         */
        public Model withValues(List<Double> value) {
            return new Model(value, null, selected, focused);
        }

        /**
         * This sparkline scaled to another range.
         *
         * @param value the range; null derives one from the samples
         * @return the new model
         */
        public Model withRange(ChartRange value) {
            return new Model(values, value, selected, focused);
        }

        /**
         * This sparkline with the reader on a sample, clamped as the constructor clamps it.
         *
         * @param value the sample to read; {@code -1} for none
         * @return the new model
         */
        public Model withSelected(int value) {
            return new Model(values, range, value, focused);
        }

        /**
         * The same sparkline, focused or not.
         *
         * @param value whether the sparkline owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(values, range, selected, value);
        }

        /**
         * The sample the reader is on.
         *
         * @return the value, or {@code NaN} when no sample is selected
         */
        public double value() {
            return selected < 0 ? Double.NaN : values.get(selected);
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
     * The sparkline's messages.
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
         * @param value the sample's value
         */
        record Inspected(int index, double value) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * The sparkline's element: its one row, and the sparkline itself answering for it.
     *
     * @param model the state to paint; required
     * @return the element, emitting this sparkline's messages
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
     * The actions this sparkline handles.
     *
     * @return the actions this sparkline handles
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
     * A press or a drag puts the reader on the cell under the pointer.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!isReading(pointer) || bounds.isEmpty() || model.values().isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (local.column() < 0 || local.column() >= bounds.cols()) {
            return;
        }
        emit.accept(new Msg.Select(
                sampleAt(local.column(), bounds.cols(), model.values().size())));
    }

    /**
     * One row of the columns it is offered; a wider pane is filled, a taller one is not.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(Math.min(1, Math.max(0, max.rows())), Math.max(0, max.cols()));
    }

    /**
     * Paints the trend and the reader's cell in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (model == null
                || bounds.isEmpty()
                || bounds.rows() <= 0
                || model.values().isEmpty()) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Style base = resolved.style(ThemeToken.ACCENT);
        Style marked = base.merge(resolved.style(ThemeToken.SELECTED));
        if (model.focused()) {
            marked = marked.merge(resolved.style(ThemeToken.FOCUS));
        }
        int count = model.values().size();
        int columns = bounds.cols();
        int selectedColumn = model.selected() < 0 ? -1 : column(model.selected(), count, columns);
        for (int column = 0; column < columns; column++) {
            double peak = peak(model.values(), column, columns);
            if (!Double.isFinite(peak)) {
                continue;
            }
            surface.put(
                    bounds.row(),
                    bounds.col() + column,
                    BlockGlyphs.vertical(model.range().fraction(peak)),
                    column == selectedColumn ? marked : base,
                    null,
                    1);
        }
    }

    /**
     * A sparkline with samples can take the keyboard; one with nothing to read cannot.
     */
    @Override
    public boolean canFocus(Model model) {
        return model != null && !model.values().isEmpty();
    }

    /**
     * Focus is model data: the application sets it and the sparkline only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model != null && model.focused();
    }

    /**
     * The column a sample lands in.
     *
     * @param index the sample's index
     * @param count the samples there are; positive
     * @param columns the columns the row has; positive
     * @return the column, from zero to {@code columns - 1}
     */
    static int column(int index, int count, int columns) {
        if (count <= 0 || columns <= 0) {
            return 0;
        }
        long column = (long) Math.max(0, Math.min(index, count - 1)) * columns / count;
        return (int) Math.min(columns - 1, column);
    }

    /**
     * The sample a column reads.
     *
     * @param column the column the pointer is on
     * @param columns the columns the row has; positive
     * @param count the samples there are; positive
     * @return the sample's index
     */
    static int sampleAt(int column, int columns, int count) {
        if (count <= 0 || columns <= 0) {
            return 0;
        }
        long index = (long) Math.max(0, Math.min(column, columns - 1)) * count / columns;
        return (int) Math.min(count - 1, index);
    }

    /**
     * The highest sample the column covers, or {@code NaN} when every sample it covers is a gap.
     */
    private static double peak(List<Double> values, int column, int columns) {
        int count = values.size();
        int first = (int) ((long) column * count / columns);
        int last = (int) Math.max(first + 1, (long) (column + 1) * count / columns);
        double peak = Double.NEGATIVE_INFINITY;
        for (int index = Math.min(first, count - 1); index < Math.min(last, count); index++) {
            double value = values.get(index);
            if (Double.isFinite(value)) {
                peak = Math.max(peak, value);
            }
        }
        return peak;
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
        if (state.selected() < 0 || target < 0 || target >= state.values().size()) {
            return new Result(state, null);
        }
        return read(state, target);
    }

    /**
     * The reader at the first or the last sample.
     */
    private static Result jump(Model state, boolean first) {
        if (state.values().isEmpty()) {
            return new Result(state, null);
        }
        return read(state, first ? 0 : state.values().size() - 1);
    }

    /**
     * Reads a sample, clamped into the samples, and reports it even when it was already selected.
     */
    private static Result read(Model state, int index) {
        if (state.values().isEmpty()) {
            return new Result(state, null);
        }
        Model next = state.withSelected(index);
        return new Result(next, new Output.Inspected(next.selected(), next.value()));
    }
}
