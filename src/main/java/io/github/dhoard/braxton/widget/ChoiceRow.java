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
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.Objects;

/**
 * One row of a choice widget: a marker and the label it marks.
 *
 * <p>{@link Checkbox}, {@link RadioGroup} and the popup of {@link Select} and {@link MultiSelect}
 * all paint the same row - a marker in a fixed column, one separating column, then the label - so the
 * row is one leaf rather than four. It is a {@link ThemedWidget} so the frame's theme is what the row
 * resolves its styles from: a live theme switch repaints every row without the widget's model
 * changing, which is the rule {@link TreeRow} and {@link MenuRow} already follow.</p>
 *
 * <p>The marker is the option's state ({@code marked}) in the theme's accent and its absence in the
 * muted style, so a checked box and an unchecked box differ in color as well as in glyph. A
 * highlighted row is filled with the theme's selection across its whole width - a highlight is a row,
 * not the cells its label happens to cover - and a highlighted row in a focused widget merges the
 * theme's focus style into the marker and the label it paints over that fill.</p>
 *
 * @param <Msg> the message type of the widget the row is placed in
 */
final class ChoiceRow<Msg> implements ThemedWidget<ChoiceRow.Row, Msg> {

    /**
     * One painted row.
     *
     * @param marker the marker painted in the first columns, such as a box or a radio dot; null or
     *     empty for none
     * @param label the label painted after the marker; null is empty
     * @param marked whether the option is in its chosen state, which paints the marker as accented
     * @param highlighted whether this row is the highlight
     * @param focused whether the owning widget holds focus
     */
    record Row(StyledText marker, StyledText label, boolean marked, boolean highlighted, boolean focused) {

        /**
         * Substitutes an empty value for a missing marker or label.
         */
        Row {
            marker = marker == null ? StyledText.empty() : marker;
            label = label == null ? StyledText.empty() : label;
        }
    }

    /**
     * One row, as wide as the marker and the label need, inside what is offered.
     */
    @Override
    public Size measure(Size max, Row row) {
        if (row == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        return new Size(1, Math.min(max.cols(), columns(row)));
    }

    /**
     * The whole width it is offered: a row is one line of the widget, so a highlight covers the row
     * rather than the cells its label happens to use.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Row row) {
        return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
    }

    /**
     * Paints the highlight, then the marker and the label in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Row row, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (row == null || bounds.isEmpty() || bounds.rows() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        if (row.highlighted()) {
            surface.fill(bounds, resolved.style(ThemeToken.SELECTED));
        }
        Style marker = resolved.style(row.marked() ? ThemeToken.ACCENT : ThemeToken.MUTED);
        Style label = resolved.style(ThemeToken.TEXT);
        if (row.highlighted() && row.focused()) {
            // Focus colors the row's content rather than its selection's background: the theme's
            // focus style is a foreground style, and filling a row with it would color the cells the
            // label does not cover.
            Style focus = resolved.style(ThemeToken.FOCUS);
            marker = marker.merge(focus);
            label = label.merge(focus);
        }
        StyledText markerText = RowText.singleRow(row.marker());
        int markerColumns = TextMeasure.columns(markerText.plainText());
        // The value is written through the wrapping writer, so a cluster with no base of its own is
        // prepared into the one cell the row measured for it rather than one column more.
        surface.write(
                new Rect(bounds.row(), bounds.col(), 1, Math.min(markerColumns, bounds.cols())),
                RowText.styled(markerText, marker),
                WidthPolicy.PINNED);
        // A row without a marker is only its label, so no separating column is left in front of it.
        int start = markerColumns == 0 ? 0 : markerColumns + 1;
        if (start < bounds.cols()) {
            surface.write(
                    new Rect(bounds.row(), bounds.col() + start, 1, bounds.cols() - start),
                    RowText.styled(row.label(), label),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * The columns a row asks for: its marker, one separating column, and its label.
     */
    private static int columns(Row row) {
        int marker = TextMeasure.columns(RowText.singleRow(row.marker()).plainText());
        int label = TextMeasure.columns(RowText.singleRow(row.label()).plainText());
        if (label == 0) {
            return marker;
        }
        return marker == 0 ? label : marker + 1 + label;
    }
}
