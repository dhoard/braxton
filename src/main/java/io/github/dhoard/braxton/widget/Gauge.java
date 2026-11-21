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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
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
import java.util.Objects;

/**
 * A gauge: one value on its range, as a filled bar with a readout.
 *
 * <p>A gauge answers "how far along is this" for a single reading - a download's progress, a pool's
 * utilization, a score - where a chart answers it for a series. The model is the reading and the
 * range it is measured against; the bar fills from the left by that fraction, its last cell carries
 * the eighth the value stops in, and the remaining cells are painted as a track so the reader can
 * see how much of the range is left.</p>
 *
 * <p>The label is the widget's configuration and may be styled, the readout is the percentage in
 * the theme's text style, and both are given up as the row narrows: the readout goes first, the
 * label second, because the bar is what the gauge is for. The readout reserves four columns - the
 * width of {@code 100%} - whatever the value is, so a bar does not jump one column sideways every
 * time the percentage gains a digit.</p>
 *
 * <h2>Why it does not answer gestures</h2>
 *
 * <p>A gauge is an indicator, not a control: it shows a value the application owns, so it owns no
 * keys, takes no turn in the focus walk, and a pointer report never reaches it - a click on a gauge
 * belongs to whatever it is drawn over or to nobody at all. That is the same boundary {@link Toast}
 * draws, and it is what keeps a pane of indicators from filling the focus walk with stops that do
 * nothing. An application that edits the value places an editor of its own beside the gauge and
 * paints the gauge from the same model.</p>
 *
 * <h2>Themes</h2>
 *
 * <p>The filled cells are the theme's {@link ThemeToken#ACCENT}, the track its
 * {@link ThemeToken#MUTED}, and the label and readout its {@link ThemeToken#TEXT}, so a live theme
 * switch repaints the gauge without the model changing. {@link #FILLED} and {@link #EMPTY} are the
 * glyphs the two regions are painted with; color belongs to the theme, and a gauge that hard-coded
 * its own would ignore a switch.</p>
 *
 * @param <Msg> the message type of the tree this gauge is placed in
 * @see Elements#gauge(Gauge, Gauge.Model)
 */
public final class Gauge<Msg> implements ThemedWidget<Gauge.Model, Msg> {

    /**
     * The glyph a filled cell is painted with.
     */
    public static final String FILLED = "\u2588";

    /**
     * The glyph a cell of the unfilled track is painted with.
     */
    public static final String EMPTY = "\u2591";

    /**
     * The columns a percentage readout reserves, which is the width of {@code 100%}.
     */
    public static final int READOUT_COLUMNS = 4;

    /**
     * The least columns a bar is worth painting in.
     */
    private static final int MIN_BAR = 3;

    private final Config config;

    /**
     * A gauge with its default configuration.
     */
    public Gauge() {
        this(Config.defaults());
    }

    /**
     * A gauge over its configuration.
     *
     * @param config the label and the readout; required
     */
    public Gauge(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * How a gauge is configured.
     *
     * @param label the label painted before the bar; null is empty
     * @param showValue whether the percentage is painted after the bar
     */
    public record Config(StyledText label, boolean showValue) {

        /**
         * Substitutes an empty label.
         */
        public Config {
            label = label == null ? StyledText.empty() : label;
        }

        /**
         * The default configuration: no label, with the percentage shown.
         *
         * @return the configuration
         */
        public static Config defaults() {
            return new Config(StyledText.empty(), true);
        }

        /**
         * A configuration with a plain label and the percentage shown.
         *
         * @param label the plain label; null is empty
         * @return the configuration
         */
        public static Config of(String label) {
            return new Config(StyledText.plain(label), true);
        }

        /**
         * This configuration with another label.
         *
         * @param value the label; null is empty
         * @return the new configuration
         */
        public Config withLabel(StyledText value) {
            return new Config(value, showValue);
        }

        /**
         * This configuration with or without the percentage.
         *
         * @param value whether the percentage is painted
         * @return the new configuration
         */
        public Config withShowValue(boolean value) {
            return new Config(label, value);
        }
    }

    /**
     * One reading on its range.
     *
     * <p>A missing range is the unit interval, which is what a ratio is: {@code 0.42} fills
     * {@code 42%} of the bar. A value outside the range is clamped onto the end it is past, and a
     * value that is not a number fills nothing.</p>
     *
     * @param value the reading
     * @param range the range it is measured against; null is zero to one
     */
    public record Model(double value, ChartRange range) {

        /**
         * Substitutes the unit interval for a missing range.
         */
        public Model {
            range = range == null ? ChartRange.of(0.0, 1.0) : range;
        }

        /**
         * A ratio: a reading on the unit interval.
         *
         * @param value the reading
         * @return the model
         */
        public static Model of(double value) {
            return new Model(value, ChartRange.of(0.0, 1.0));
        }

        /**
         * A reading on a range of its own.
         *
         * @param value the reading
         * @param min the range's lowest value
         * @param max the range's highest value
         * @return the model
         */
        public static Model of(double value, double min, double max) {
            return new Model(value, ChartRange.of(min, max));
        }

        /**
         * How much of the range the reading fills.
         *
         * @return the fraction, from zero to one
         */
        public double fraction() {
            return range.fraction(value);
        }

        /**
         * The percentage the readout paints.
         *
         * @return the percentage, from zero to a hundred
         */
        public int percent() {
            return (int) Math.round(fraction() * 100.0);
        }
    }

    /**
     * The columns a painted row is divided into.
     *
     * @param label the columns the label may use; zero when the row has no label
     * @param bar the columns the bar may use
     * @param readout the columns the percentage may use; zero when it is dropped
     */
    record Layout(int label, int bar, int readout) {}

    /**
     * How this gauge is configured.
     *
     * @return how this gauge is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The gauge's element: one row of the columns it is offered.
     *
     * @param model the reading to paint; required
     * @return the element
     */
    public Element<Msg> element(Model model) {
        return new Element.Leaf<>(this, Objects.requireNonNull(model, "model"), Sizing.measured());
    }

    /**
     * The columns a gauge's row is divided into at a width.
     *
     * @param label the label painted before the bar; null is empty
     * @param showValue whether the percentage is painted
     * @param cols the columns the row has
     * @return the division
     */
    static Layout layout(StyledText label, boolean showValue, int cols) {
        int width = Math.max(0, cols);
        StyledText text = RowText.singleRow(label);
        int labelCols = Math.min(TextMeasure.columns(text.plainText(), WidthPolicy.PINNED), width / 3);
        int readout = showValue ? Math.min(READOUT_COLUMNS, width) : 0;
        int bar = width - labelCols - (labelCols > 0 ? 1 : 0) - readout - (readout > 0 ? 1 : 0);
        if (bar < MIN_BAR) {
            // The bar is the gauge, so the readout goes before the label does.
            readout = 0;
            bar = width - labelCols - (labelCols > 0 ? 1 : 0);
        }
        if (bar < MIN_BAR) {
            labelCols = 0;
            bar = width;
        }
        return new Layout(labelCols, Math.max(0, bar), readout);
    }

    /**
     * One row: the columns the label and bar need, and no more.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(Math.min(1, Math.max(0, max.rows())), Math.max(0, max.cols()));
    }

    /**
     * Paints the label, the bar and the readout in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (bounds.isEmpty() || bounds.rows() <= 0 || bounds.cols() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Layout layout = layout(config.label(), config.showValue(), bounds.cols());
        int col = bounds.col();
        if (layout.label() > 0) {
            surface.write(
                    new Rect(bounds.row(), col, 1, layout.label()),
                    RowText.styled(RowText.singleRow(config.label()), resolved.style(ThemeToken.TEXT)),
                    WidthPolicy.PINNED);
            col += layout.label() + 1;
        }
        paintBar(surface, bounds.row(), col, layout.bar(), model, resolved);
        col += layout.bar();
        if (layout.readout() > 0) {
            String text = model.percent() + "%";
            // The readout is right-aligned inside the columns it reserves, so a value that gains a
            // digit reads as the same field rather than as a new one.
            String padded =
                    text.length() < layout.readout() ? " ".repeat(layout.readout() - text.length()) + text : text;
            int start = bounds.col() + bounds.cols() - layout.readout();
            surface.write(
                    new Rect(bounds.row(), start, 1, layout.readout()),
                    StyledText.of(padded, resolved.style(ThemeToken.TEXT)),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * An indicator is not a target: a click belongs to what it is drawn over, never to the gauge.
     */
    @Override
    public boolean hit(Rect bounds, int row, int col, Model model) {
        return false;
    }

    /**
     * The filled cells and the track, with the eighth the value stops in.
     */
    private static void paintBar(Surface surface, int row, int col, int bar, Model model, Theme theme) {
        if (bar <= 0) {
            return;
        }
        Style filled = theme.style(ThemeToken.ACCENT);
        Style empty = theme.style(ThemeToken.MUTED);
        int eighths = (int) Math.round(model.fraction() * bar * 8);
        int whole = Math.min(bar, eighths / 8);
        int part = whole >= bar ? 0 : eighths % 8;
        StringBuilder fill = new StringBuilder(bar);
        fill.append(FILLED.repeat(whole));
        if (part > 0) {
            fill.append(BlockGlyphs.eighth(part));
        }
        if (!fill.isEmpty()) {
            surface.write(new Rect(row, col, 1, bar), StyledText.of(fill.toString(), filled), WidthPolicy.PINNED);
        }
        int used = whole + (part > 0 ? 1 : 0);
        if (used < bar) {
            surface.write(
                    new Rect(row, col + used, 1, bar - used),
                    StyledText.of(EMPTY.repeat(bar - used), empty),
                    WidthPolicy.PINNED);
        }
    }
}
