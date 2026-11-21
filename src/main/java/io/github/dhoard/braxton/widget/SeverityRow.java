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
 * One notification row: a severity's marker and the message it marks.
 *
 * <p>{@link Toast} and {@link NotificationCenter} paint the same row - a marker in a fixed column,
 * one separating column, then the message - so the row is one leaf rather than two. It is a
 * {@link ThemedWidget} so the frame's theme is what the row resolves its styles from: a live theme
 * switch repaints every notification without the widget's model changing, the rule {@link TreeRow},
 * {@link MenuRow} and {@link ChoiceRow} already follow.</p>
 *
 * <p>It is a painter of its own rather than another {@link ChoiceRow} use because a severity's
 * marker is painted in the role its {@link Severity#token()} names, and a choice row knows only
 * "marked" and "unmarked". The same reason {@link FormErrorRow} is not a choice row.</p>
 *
 * <p>A {@link Row#dim() dim} row is one the reader has already seen: its marker and its message are
 * painted muted. A highlighted row is filled with the theme's selection across its whole width - a
 * highlight is a row, not the cells its message happens to cover - and a highlighted row in a
 * focused widget merges the theme's focus style into what it paints over that fill.</p>
 *
 * @param <Msg> the message type of the widget the row is placed in
 */
final class SeverityRow<Msg> implements ThemedWidget<SeverityRow.Row, Msg> {

    /**
     * One painted row.
     *
     * @param severity the severity whose marker is painted; null is {@link Severity#INFO}
     * @param message the message painted after the marker; null is empty
     * @param dim whether the row is painted muted because the reader has already seen it
     * @param highlighted whether this row is the highlight
     * @param focused whether the owning widget holds focus
     */
    record Row(Severity severity, StyledText message, boolean dim, boolean highlighted, boolean focused) {

        /**
         * Substitutes the defaults for a missing severity or message.
         */
        Row {
            severity = severity == null ? Severity.INFO : severity;
            message = message == null ? StyledText.empty() : message;
        }
    }

    /**
     * One row, as wide as the marker and the message need, inside what is offered.
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
     * rather than the cells its message happens to use.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Row row) {
        return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
    }

    /**
     * Paints the highlight, then the marker in the severity's role and the message.
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
        Style marker =
                resolved.style(row.dim() ? ThemeToken.MUTED : row.severity().token());
        Style message = resolved.style(row.dim() ? ThemeToken.MUTED : ThemeToken.TEXT);
        if (row.highlighted() && row.focused()) {
            // Focus colors the row's content rather than its selection's background: the theme's
            // focus style is a foreground style, and filling a row with it would color the cells the
            // message does not cover.
            Style focus = resolved.style(ThemeToken.FOCUS);
            marker = marker.merge(focus);
            message = message.merge(focus);
        }
        StyledText markerText =
                RowText.singleRow(StyledText.plain(row.severity().marker()));
        int markerColumns = TextMeasure.columns(markerText.plainText());
        // The marker is written through the wrapping writer, so a cluster with no base of its own
        // takes the one cell this row measured for it rather than one column more.
        surface.write(
                new Rect(bounds.row(), bounds.col(), 1, Math.min(markerColumns, bounds.cols())),
                RowText.styled(markerText, marker),
                WidthPolicy.PINNED);
        int start = markerColumns + 1;
        if (start < bounds.cols()) {
            surface.write(
                    new Rect(bounds.row(), bounds.col() + start, 1, bounds.cols() - start),
                    RowText.styled(row.message(), message),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * The columns a row asks for: its marker, one separating column, and its message.
     */
    private static int columns(Row row) {
        int marker = TextMeasure.columns(row.severity().marker());
        int message = TextMeasure.columns(RowText.singleRow(row.message()).plainText());
        return message == 0 ? marker : marker + 1 + message;
    }
}
