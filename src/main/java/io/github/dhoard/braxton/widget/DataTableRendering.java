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

import static io.github.dhoard.braxton.widget.DataTable.GAP;

import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.DataTable.Alignment;
import io.github.dhoard.braxton.widget.DataTable.Cell;
import io.github.dhoard.braxton.widget.DataTable.Column;
import io.github.dhoard.braxton.widget.DataTable.Row;
import io.github.dhoard.braxton.widget.DataTable.Selection;
import java.util.ArrayList;
import java.util.List;

/**
 * Table cell rendering and clipping.
 */
final class DataTableRendering {

    /**
     * The header's values, one per column, carrying the indicator of the column the rows are sorted by.
     */
    static List<StyledText> headerValues(DataTableModel state) {
        DataTableContent content = state.content();
        List<StyledText> values = new ArrayList<>(content.columnCount());
        for (int index = 0; index < content.columnCount(); index++) {
            Column column = content.columns().get(index);
            boolean sorted = state.sort().active() && state.sort().column().equals(column.key());
            String indicator = sorted ? (state.sort().descending() ? " \u25BC" : " \u25B2") : "";
            values.add(StyledText.plain(column.header() + indicator));
        }
        return values;
    }

    /**
     * One line as the table paints it: every column padded to its width, the horizontal window cut
     * and the result padded to the cells the window shows.
     */
    static StyledText paint(
            DataTableModel state,
            DataTableConfig config,
            int row,
            List<StyledText> header,
            boolean headings,
            int firstColumn) {
        DataTableContent content = state.content();
        int columns = content.columnCount();
        Style selection = state.focused() ? config.activeStyle() : config.inactiveStyle();
        // The row's own style is asked for once, not once per cell: a theme that styles a whole row
        // should not be told about it four times.
        Style rowStyle = headings ? Style.NONE : rowStyle(state, config, row);
        GridRows.Row charge = GridRows.Row.empty();
        StyledText line = StyledText.empty();
        Style last = Style.NONE;
        for (int column = 0; column < columns; column++) {
            Column schema = content.columns().get(column);
            int width = state.columnWidth(column);
            StyledText value =
                    headings ? header.get(column) : state.rows().get(row).cell(column);
            Style style = headings ? config.theme().header(schema) : style(state, config, row, column, rowStyle);
            if (!headings && isSelected(state, row, column)) {
                style = (style == null ? Style.NONE : style).merge(selection);
            }
            StyledText fitted = clipped(value, width);
            int used = TextMeasure.columns(fitted.plainText());
            int leading = leading(schema.alignment(), width, used);
            // The cell is charged to the row it is painted in, so a cluster with no base of its own
            // joins the pad beside it instead of starting a cell of its own - the rule the static
            // table, the selector and markup share - and a row keeps its columns lined up with the
            // header above it.
            int left = charge.pad("", leading);
            int trailing = charge.pad(fitted.plainText(), Math.max(0, width - leading)) + charge.pad("", GAP);
            line = line.append(StyledText.of(" ".repeat(left), style))
                    .append(fitted.styled(0, fitted.length(), style))
                    .append(StyledText.of(" ".repeat(trailing), style));
            last = style;
        }
        return window(state, line, last, firstColumn);
    }

    /**
     * The style of one body row: the theme's own, the marked style, and nothing merged twice.
     */
    static Style rowStyle(DataTableModel state, DataTableConfig config, int row) {
        Row source = state.rows().get(row);
        Style style = config.theme().row(source, row);
        if (state.isMarked(source.id())) {
            style = (style == null ? Style.NONE : style).merge(config.theme().marked(source, row));
        }
        return style;
    }

    /**
     * The share of a cell's padding that precedes its value.
     */
    static int leading(Alignment alignment, int width, int used) {
        int pad = Math.max(0, width - used);
        return switch (alignment) {
            case LEFT -> 0;
            case CENTER -> pad / 2;
            case RIGHT -> pad;
        };
    }

    /**
     * Whether one cell is part of the selection.
     */
    static boolean isSelected(DataTableModel state, int row, int column) {
        Selection selection = state.selection();
        if (selection.row() != row) {
            return false;
        }
        return !selection.cellwise() || selection.column() == column;
    }

    /**
     * The style the theme gives one body cell, merged over the row's own style.
     */
    static Style style(DataTableModel state, DataTableConfig config, int row, int column, Style rowStyle) {
        Column schema = state.content().columns().get(column);
        Row source = state.rows().get(row);
        Cell cell = new Cell(row, column, schema, source, isSelected(state, row, column), state.focused());
        return (rowStyle == null ? Style.NONE : rowStyle).merge(config.theme().cell(cell));
    }

    /**
     * A value prepared as the table paints it and truncated to a column.
     */
    static StyledText clipped(StyledText value, int width) {
        StyledText prepared = prepared(value);
        String plain = prepared.plainText();
        String truncated = TextMeasure.truncate(plain, width);
        return truncated.length() == plain.length() ? prepared : prepared.slice(0, truncated.length());
    }

    /**
     * One line cut to the horizontal window and padded to the cells the window shows.
     */
    static StyledText window(DataTableModel state, StyledText line, Style style, int firstColumn) {
        int width = Math.max(0, state.width());
        if (width == 0) {
            return StyledText.empty();
        }
        VisualRows.Window pane = VisualRows.pane(line.plainText(), 0, width, firstColumn);
        StyledText visible = line.slice(pane.start(), pane.end());
        int span = Math.max(0, Math.min(state.paintedRowWidth(), firstColumn + width) - firstColumn);
        int pad = span - TextMeasure.columns(visible.plainText());
        return pad <= 0 ? visible : visible.append(StyledText.of(" ".repeat(pad), style));
    }

    /**
     * A cell as the table paints it: one row, tab-expanded, spans kept.
     */
    static StyledText prepared(StyledText value) {
        return TabStops.expand(singleRow(value));
    }

    /**
     * A value with every break form turned into one space, spans and links kept.
     *
     * <p>A cell is one painted row, the rule {@link TableSelector} and {@link Markup} apply, so the
     * conversion is the one policy {@link LineBreaks} owns. A break is one character and is never
     * split across spans, so converting each span on its own is the same conversion the whole value
     * would get; a bare CR cannot reach a cell at all, because {@link StyledText} drops an
     * unrepresentable control character where the span is made. An application that reads raw text
     * prepares it with {@link LineBreaks#singleRow} before it becomes a cell.</p>
     */
    static StyledText singleRow(StyledText value) {
        if (value.plainText().indexOf('\n') < 0) {
            return value;
        }
        StyledText out = StyledText.empty();
        for (StyledText.Span span : value.spans()) {
            out = out.append(StyledText.of(LineBreaks.singleRow(span.text()), span.style(), span.link()));
        }
        return out;
    }
}
