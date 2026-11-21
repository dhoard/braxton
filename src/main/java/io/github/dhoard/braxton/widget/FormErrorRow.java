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
 * The error row of a form field: a marker and the reason, in the theme's error style.
 *
 * <p>It is a {@link ThemedWidget} rather than a {@code ChoiceRow} because an error is not a choice:
 * the reason is painted in the theme's {@link ThemeToken#ERROR}, which is what makes a refused field
 * legible at a glance and survives a live theme switch. The row is one line and is clipped rather
 * than wrapped, because a form row is one line; the marker keeps its column so the reasons of two
 * invalid fields line up.</p>
 *
 * @param <Msg> the message type of the widget the row is placed in
 */
final class FormErrorRow<Msg> implements ThemedWidget<FormErrorRow.Row, Msg> {

    /**
     * The marker painted before a reason.
     */
    static final String MARKER = "!";

    /**
     * The columns between the marker and the reason.
     */
    private static final int GAP = 1;

    /**
     * One painted error row.
     *
     * @param message the reason painted after the marker; null is empty
     */
    record Row(StyledText message) {

        /**
         * Substitutes an empty value for a missing one.
         */
        Row {
            message = message == null ? StyledText.empty() : message;
        }
    }

    /**
     * One row, as wide as the marker and the reason need, inside what is offered.
     */
    @Override
    public Size measure(Size max, Row row) {
        if (row == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        return new Size(1, Math.min(max.cols(), columns(row)));
    }

    /**
     * The whole width it is offered: an error is a row of the form it sits in, so its reason is
     * clipped by the form's width rather than by the reason's own length.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Row row) {
        return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
    }

    /**
     * Paints the marker and the reason in the theme's error style.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Row row, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (row == null || bounds.isEmpty() || bounds.rows() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Style style = resolved.style(ThemeToken.ERROR);
        int markerColumns = Math.min(TextMeasure.columns(MARKER), bounds.cols());
        if (markerColumns > 0) {
            surface.writeLine(
                    bounds.row(), bounds.col(), markerColumns, StyledText.of(MARKER, style), WidthPolicy.PINNED);
        }
        int start = Math.min(bounds.cols(), markerColumns + GAP);
        if (start < bounds.cols()) {
            // The reason is written through the wrapping writer, so a cluster with no base of its own
            // is prepared into the one cell the row measured for it rather than one column more.
            surface.write(
                    new Rect(bounds.row(), bounds.col() + start, 1, bounds.cols() - start),
                    RowText.styled(RowText.singleRow(row.message()), style),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * The columns a row asks for: its marker, one separating column, and its reason.
     */
    private static int columns(Row row) {
        int message = TextMeasure.columns(RowText.singleRow(row.message()).plainText());
        if (message == 0) {
            return TextMeasure.columns(MARKER);
        }
        return TextMeasure.columns(MARKER) + GAP + message;
    }
}
