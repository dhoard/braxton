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
 * The closed row of a {@link Select} or a {@link MultiSelect}: what is chosen, and the glyph that
 * says the row opens.
 *
 * <p>It is the value row of every choice field, so the two widgets that have a closed state share
 * one painter. The value is the theme's {@code TEXT} and the glyph its {@code ACCENT} - the glyph is
 * the affordance, so it carries the accent rather than the value - and a focused field merges the
 * theme's {@code FOCUS} into both. Like the choice rows, it is a {@link ThemedWidget}: a live theme
 * switch repaints the field without the widget's model changing.</p>
 *
 * <p>The glyph is pushed to the right edge and is dropped whole when the row is too narrow to hold
 * it beside the value, the rule {@link MenuRow} follows for its hint: a clipped glyph at the right
 * edge would show a fragment of the affordance instead of the affordance. A value wider than the row
 * is clipped rather than wrapped, because a field is one line.</p>
 *
 * @param <Msg> the message type of the widget the row is placed in
 */
final class FieldRow<Msg> implements ThemedWidget<FieldRow.Row, Msg> {

    /**
     * The columns between the value and the right-aligned glyph.
     */
    private static final int GLYPH_GAP = 1;

    /**
     * The columns the value keeps before the glyph may take any of them.
     */
    private static final int MIN_VALUE_COLUMNS = 2;

    /**
     * One painted row.
     *
     * @param value the value painted from the first column; null is empty
     * @param glyph the affordance painted at the right edge; null or empty for none
     * @param focused whether the owning widget holds focus
     */
    record Row(StyledText value, String glyph, boolean focused) {

        /**
         * Substitutes an empty value for a missing one.
         */
        Row {
            value = value == null ? StyledText.empty() : value;
            glyph = glyph == null ? "" : glyph;
        }
    }

    /**
     * One row: the value's columns plus the glyph and the gap in front of it.
     */
    @Override
    public Size measure(Size max, Row row) {
        if (row == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        return new Size(1, Math.min(max.cols(), columns(row)));
    }

    /**
     * The whole width it is offered: a field is a row of the form it sits in, and its glyph belongs
     * at the right edge rather than beside a short value.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Row row) {
        return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
    }

    /**
     * Paints the value, then the glyph at the right edge when the row holds both.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Row row, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (row == null || bounds.isEmpty() || bounds.rows() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Style value = resolved.style(ThemeToken.TEXT);
        Style glyph = resolved.style(ThemeToken.ACCENT);
        if (row.focused()) {
            Style focus = resolved.style(ThemeToken.FOCUS);
            value = value.merge(focus);
            glyph = glyph.merge(focus);
        }
        boolean hasGlyph = !row.glyph().isEmpty();
        int glyphColumns = hasGlyph ? TextMeasure.columns(row.glyph()) : 0;
        // The glyph keeps one separating column from the value, and it never takes so many columns
        // that the value has less than two: a value a reader can read is worth more than an
        // affordance, and half an affordance is worth nothing at all.
        boolean showGlyph = hasGlyph && glyphColumns + GLYPH_GAP + MIN_VALUE_COLUMNS <= bounds.cols();
        int valueColumns = showGlyph ? bounds.cols() - glyphColumns - GLYPH_GAP : bounds.cols();
        // The value is written through the wrapping writer, so a cluster with no base of its own is
        // prepared into the one cell the row measured for it rather than one column more.
        surface.write(
                new Rect(bounds.row(), bounds.col(), 1, valueColumns),
                RowText.styled(row.value(), value),
                WidthPolicy.PINNED);
        if (showGlyph) {
            surface.writeLine(
                    bounds.row(),
                    bounds.col() + bounds.cols() - glyphColumns,
                    glyphColumns,
                    StyledText.of(row.glyph(), glyph),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * The columns a row asks for: the value, the gap and the glyph.
     */
    private static int columns(Row row) {
        int value = TextMeasure.columns(RowText.singleRow(row.value()).plainText());
        int glyph = row.glyph().isEmpty() ? 0 : TextMeasure.columns(row.glyph()) + GLYPH_GAP;
        return value + glyph;
    }
}
