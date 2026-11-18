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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal bar with an active/inactive fill, an optional percentage label, and separators.
 *
 * <p>Values are clamped: a bar never renders more cells than its width, and an unusable width or
 * maximum leaves the bar empty instead of throwing. The bar is one row, so line breaks and tabs in
 * its icons, prepended label, or appended label become spaces.</p>
 *
 * <p>A bar is normally a whole number of filled cells. Two opt-in refinements paint a finer
 * picture without changing that default: {@link #fractional(boolean)} stops the fill inside a cell
 * with an eighth-block glyph instead of rounding it to a whole one, and {@link #blend(Color, Color)}
 * colours the filled cells along a ramp from one colour to another. Both are off unless asked for,
 * so a bar that does not opt in paints exactly what it always painted.</p>
 *
 * <p>A bar counts cells, not columns. {@link #fractional(boolean)} pads a partial cell to the
 * active icon's own width, so the painted width of the row does not change as the fill crosses a
 * cell boundary; an active icon that is wider than the inactive one still changes the row's total
 * width at the boundary, exactly as it does without a fractional fill, because that difference is
 * the caller's own icon choice.</p>
 *
 * <p>{@link AnimatedProgress} is the component that eases a bar towards a target over time; this
 * class remains the pure renderer both of them share.</p>
 */
public final class ProgressBar {

    /** The glyph a fractional fill stops on, indexed by eighths of a cell from one to eight. */
    private static final List<String> FRACTIONAL_ICONS =
            List.of("\u258F", "\u258E", "\u258D", "\u258C", "\u258B", "\u258A", "\u2589", "\u2588");

    private final int width;
    private final int buffer;
    private final String activeIcon;
    private final String inactiveIcon;
    private final Style activeStyle;
    private final Style inactiveStyle;
    private final boolean showPercentage;
    private final StyledText prepend;
    private final StyledText append;
    private final boolean fractional;
    private final Color blendStart;
    private final Color blendEnd;

    private ProgressBar(
            int width,
            int buffer,
            String activeIcon,
            String inactiveIcon,
            Style activeStyle,
            Style inactiveStyle,
            boolean showPercentage,
            StyledText prepend,
            StyledText append,
            boolean fractional,
            Color blendStart,
            Color blendEnd) {
        this.width = width;
        this.buffer = buffer;
        this.activeIcon = activeIcon;
        this.inactiveIcon = inactiveIcon;
        this.activeStyle = activeStyle;
        this.inactiveStyle = inactiveStyle;
        this.showPercentage = showPercentage;
        this.prepend = prepend;
        this.append = append;
        this.fractional = fractional;
        this.blendStart = blendStart;
        this.blendEnd = blendEnd;
    }

    /**
     * A bar of {@code width} cells.
     *
     * @param width the bar width in cells
     * @return the builder
     */
    public static ProgressBar of(int width) {
        return new ProgressBar(
                width,
                0,
                "\u2588",
                "\u2591",
                Style.NONE,
                Style.NONE.withItalic(),
                false,
                StyledText.empty(),
                StyledText.empty(),
                false,
                null,
                null);
    }

    /**
     * The same bar with a different width.
     *
     * @param value the bar width in cells
     * @return this builder
     */
    public ProgressBar width(int value) {
        return new ProgressBar(
                value,
                buffer,
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                showPercentage,
                prepend,
                append,
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with a different cell buffer.
     *
     * @param value the cells reserved around the bar
     * @return this builder
     */
    public ProgressBar buffer(int value) {
        return new ProgressBar(
                width,
                Math.max(0, value),
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                showPercentage,
                prepend,
                append,
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with different active and inactive glyphs.
     *
     * @param active the glyph for a filled cell
     * @param inactive the glyph for an empty cell
     * @return this builder
     */
    public ProgressBar icons(String active, String inactive) {
        return new ProgressBar(
                width,
                buffer,
                LineBreaks.singleRowAndTabs(active),
                LineBreaks.singleRowAndTabs(inactive),
                activeStyle,
                inactiveStyle,
                showPercentage,
                prepend,
                append,
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with different colours.
     *
     * @param active the colour of a filled cell
     * @param inactive the colour of an empty cell
     * @return this builder
     */
    public ProgressBar colors(Color active, Color inactive) {
        return new ProgressBar(
                width,
                buffer,
                activeIcon,
                inactiveIcon,
                active == null ? activeStyle : Style.fg(active),
                inactive == null ? inactiveStyle : Style.fg(inactive),
                showPercentage,
                prepend,
                append,
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with the percentage label shown or hidden.
     *
     * @param value whether to show the percentage
     * @return this builder
     */
    public ProgressBar showPercentage(boolean value) {
        return new ProgressBar(
                width,
                buffer,
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                value,
                prepend,
                append,
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with a label before it.
     *
     * @param value the label to prepend
     * @return this builder
     */
    public ProgressBar prepend(StyledText value) {
        return new ProgressBar(
                width,
                buffer,
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                showPercentage,
                SingleRow.of(value),
                append,
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with a label after it.
     *
     * @param value the label to append
     * @return this builder
     */
    public ProgressBar append(StyledText value) {
        return new ProgressBar(
                width,
                buffer,
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                showPercentage,
                prepend,
                SingleRow.of(value),
                fractional,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with a fractional fill.
     *
     * <p>When the bar is on, the cell the fill stops inside is painted with one of the eighth-block
     * glyphs {@code ▏▎▍▌▋▊▉} rather than rounded up or down to a whole cell, and that cell is padded
     * to the active icon's width so the row keeps its painted width as the fill crosses a cell
     * boundary. Progress is graded to the nearest eighth; below half an eighth the cell stays
     * empty. A custom active icon is replaced at that one cell: the ramp is the eighth-block set the
     * widget owns.</p>
     *
     * @param value whether a partial cell is graded instead of rounded
     * @return this builder
     */
    public ProgressBar fractional(boolean value) {
        return new ProgressBar(
                width,
                buffer,
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                showPercentage,
                prepend,
                append,
                value,
                blendStart,
                blendEnd);
    }

    /**
     * The same bar with the filled cells blended from one colour to another.
     *
     * <p>The ramp is anchored to the bar rather than to the fill: cell {@code i} of {@code n} is
     * painted at {@code i / (n - 1)} of the way from {@code start} to {@code end}, whatever the
     * fill is. A cell therefore keeps its colour as the fill grows, so an animated bar does not
     * shimmer while it moves, and the blend spans exactly as much of the ramp as the fill covers.
     * A bar of one cell is painted with {@code start}.</p>
     *
     * <p>Either colour may be null, and a bar with a missing end has no blend at all - the filled
     * cells keep the colour {@link #colors(Color, Color)} gave them.</p>
     *
     * @param start the colour of the first cell, or null for no blend
     * @param end the colour of the last cell, or null for no blend
     * @return this builder
     */
    public ProgressBar blend(Color start, Color end) {
        return new ProgressBar(
                width,
                buffer,
                activeIcon,
                inactiveIcon,
                activeStyle,
                inactiveStyle,
                showPercentage,
                prepend,
                append,
                fractional,
                start,
                end);
    }

    /**
     * Renders a fraction of {@code value / max}; invalid bounds render an empty bar.
     *
     * @param value the completed amount
     * @param max the amount that fills the bar
     * @return the painted bar
     */
    public StyledText render(double value, double max) {
        // NaN cannot be clamped onto the 0..1 scale, so it is unusable in the same way as a
        // non-finite maximum; an out-of-range finite (or infinite) value still clamps.
        if (width <= 0 || max <= 0 || !Double.isFinite(max) || Double.isNaN(value)) {
            return StyledText.empty();
        }
        return renderFraction(Math.max(0, Math.min(1, value / max)));
    }

    /**
     * Paints a normalized fraction of the bar.
     *
     * <p>This is the renderer {@link AnimatedProgress} shares with {@link #render}: the fill is a
     * fraction of the scale rather than of a maximum. A non-number paints an empty bar, and a
     * finite value outside the scale clamps to an end of it.</p>
     *
     * @param fraction the completed fraction of the scale, normally in {@code [0,1]}
     * @return the painted bar
     */
    StyledText renderFraction(double fraction) {
        int cells = Math.max(0, width);
        if (cells == 0) {
            return StyledText.empty();
        }
        double clamped = Double.isNaN(fraction) ? 0.0 : Math.max(0, Math.min(1, fraction));
        int whole;
        int eighths = 0;
        if (fractional) {
            double exact = clamped * cells;
            whole = (int) Math.floor(exact);
            eighths = (int) Math.round((exact - whole) * FRACTIONAL_ICONS.size());
            if (eighths == FRACTIONAL_ICONS.size()) {
                whole++;
                eighths = 0;
            }
            whole = Math.min(cells, whole);
        } else {
            whole = (int) Math.round(clamped * cells);
        }
        List<Color> ramp = blendStart == null || blendEnd == null ? null : List.of(blendStart, blendEnd);
        List<StyledText> parts = new ArrayList<>();
        parts.add(prepend);
        for (int i = 0; i < cells; i++) {
            if (i > 0 && buffer > 0) {
                parts.add(StyledText.plain(" ".repeat(buffer)));
            }
            if (i < whole) {
                parts.add(StyledText.of(activeIcon, filledStyle(ramp, i, cells)));
            } else if (i == whole && eighths > 0) {
                parts.add(StyledText.of(partialIcon(eighths), filledStyle(ramp, i, cells)));
            } else {
                parts.add(StyledText.of(inactiveIcon, inactiveStyle));
            }
        }
        if (showPercentage) {
            parts.add(StyledText.of(
                    String.format(java.util.Locale.ROOT, " %d%%", Math.round(clamped * 100)), Style.NONE));
        }
        parts.add(append);
        return StyledText.concat(parts.toArray(StyledText[]::new));
    }

    /** The style of one filled cell: the blend's colour at that cell, or the active style. */
    private Style filledStyle(List<Color> ramp, int index, int cells) {
        if (ramp == null) {
            return activeStyle;
        }
        double position = cells <= 1 ? 0.0 : (double) index / (cells - 1);
        return activeStyle.withForeground(Gradient.interpolate(ramp, position));
    }

    /**
     * One partial glyph, padded to the active icon's width so a fractional fill does not change the
     * row's painted width where the fill stops.
     */
    private String partialIcon(int eighths) {
        String glyph = FRACTIONAL_ICONS.get(eighths - 1);
        int cellColumns = Math.max(1, TextMeasure.columns(activeIcon));
        int padding = cellColumns - TextMeasure.columns(glyph);
        return padding <= 0 ? glyph : glyph + " ".repeat(padding);
    }

    /**
     * A single-line view of the bar.
     *
     * @param value the completed amount
     * @param max the amount that fills the bar
     * @return a text view holding the painted bar
     */
    public View<Object> view(double value, double max) {
        return Views.text(render(value, max));
    }

    /**
     * Visible cell count for a rendered bar (useful for tests).
     *
     * @param value the completed amount
     * @param max the amount that fills the bar
     * @return the columns the painted bar occupies
     */
    public int columns(double value, double max) {
        return TextMeasure.columns(render(value, max).plainText());
    }

    /**
     * Up to {@code count} icon cells from the left, used by tests to assert fill order.
     *
     * @param count the number of cells to describe
     * @param value the completed amount
     * @param max the amount that fills the bar
     * @return one glyph per cell, in order
     */
    public List<String> icons(int count, double value, double max) {
        List<String> icons = new ArrayList<>();
        if (count <= 0) {
            return icons;
        }
        StyledText bar = render(value, max);
        for (String grapheme : TextMeasure.graphemes(bar.plainText())) {
            if (icons.size() >= count) {
                break;
            }
            icons.add(grapheme);
        }
        return icons;
    }
}
