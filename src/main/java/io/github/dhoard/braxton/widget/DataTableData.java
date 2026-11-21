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

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.widget.DataTable.Row;
import io.github.dhoard.braxton.widget.DataTable.Selection;
import io.github.dhoard.braxton.widget.DataTable.Sort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable table row preparation, filtering, and column sizing.
 */
final class DataTableData {

    /**
     * The rows a filter and a sort show, in display order.
     *
     * <p>The filter is a set of whitespace-separated terms, and a row is shown when every term
     * appears in one of its cells, ignoring case. The sort orders by one column's plain text,
     * compared without regard to case; equal values keep the content's own order, because the sort
     * is stable, so ordering is total and a re-sort never shuffles rows that compare equal. The
     * result is computed once when a state is built, never once per frame.</p>
     */
    static List<Row> displayRows(DataTableContent content, String filter, Sort sort) {
        List<Row> visible = new ArrayList<>(content.rows());
        List<String> terms = terms(filter);
        if (!terms.isEmpty()) {
            visible.removeIf(row -> !matches(row, terms));
        }
        int column = sort.active() ? content.indexOfColumn(sort.column()) : -1;
        if (column >= 0) {
            Comparator<Row> order =
                    Comparator.comparing(row -> row.cell(column).plainText().toLowerCase(Locale.ROOT));
            visible.sort(sort.descending() ? order.reversed() : order);
        }
        return List.copyOf(visible);
    }

    /**
     * The terms one filter is made of: lower case, whitespace separated.
     */
    static List<String> terms(String filter) {
        if (filter == null || filter.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String term : filter.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    /**
     * Whether every term appears in at least one of a row's cells.
     */
    static boolean matches(Row row, List<String> terms) {
        for (String term : terms) {
            boolean found = false;
            for (StyledText cell : row.cells()) {
                if (cell.plainText().toLowerCase(Locale.ROOT).contains(term)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * The display index of one row id, or -1 when the order does not hold it.
     */
    static int visibleIndex(List<Row> rows, String id) {
        if (id == null || id.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The cells one row paints at a set of column widths.
     */
    static int resolveRowWidth(DataTableContent content, Map<String, Integer> widths) {
        long total = 0;
        for (int index = 0; index < content.columnCount(); index++) {
            Integer override = widths == null
                    ? null
                    : widths.get(content.columns().get(index).key());
            total += (override != null ? override : content.widths().get(index)) + GAP;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * A copy of one state with a new selection, window and geometry; filter, sort and marks carry over.
     */
    static DataTableModel copy(
            DataTableModel state, Selection selection, int top, int firstColumn, int height, int width) {
        return new DataTableModel(
                state.content(),
                List.of(),
                selection,
                top,
                firstColumn,
                height,
                width,
                state.focused(),
                state.filter(),
                state.sort(),
                state.marked(),
                state.widths());
    }
}
