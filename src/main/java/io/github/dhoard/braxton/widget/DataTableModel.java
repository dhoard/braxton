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

import static io.github.dhoard.braxton.widget.DataTable.DEFAULT_HEIGHT;
import static io.github.dhoard.braxton.widget.DataTable.DEFAULT_WIDTH;
import static io.github.dhoard.braxton.widget.DataTable.GAP;

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.widget.DataTable.Column;
import io.github.dhoard.braxton.widget.DataTable.Mode;
import io.github.dhoard.braxton.widget.DataTable.Row;
import io.github.dhoard.braxton.widget.DataTable.Selection;
import io.github.dhoard.braxton.widget.DataTable.Sort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Table state: the content, the window, the filter and sort that decide which rows it shows, and
 * the marks the user made.
 *
 * <p>The state is derived where it must be: {@link #rows()} is the visible rows in display order,
 * computed from the content, {@link #filter()} and {@link #sort()} when the state is built, so a
 * frame, a gesture and a pointer report all read one order and none of them re-filters a table.
 * The selection and the window name positions in that order, so hiding a row with a filter moves
 * the selection to the row that takes its place rather than leaving it on a row nothing paints.
 * The selected row's identity is what the messages that change the data carry over.</p>
 *
 * <p>{@link #widths()} holds the columns the reader resized, keyed by column key: a column an
 * application does not override keeps the width the content resolved when it was built, so
 * resizing one column never re-measures the table.</p>
 *
 * @param content the columns and rows, and the widths they resolved to
 * @param rows the visible rows in display order, derived from the content, the filter and the sort
 * @param selection the selected position and the mode
 * @param top the position in {@link #rows()} of the first body row the window shows
 * @param firstColumn the first painted cell the window shows
 * @param height the rows the parent allocated, header included
 * @param width the cells the parent allocated
 * @param focused whether the table owns focus
 * @param filter the text that hides rows, empty for none
 * @param sort the column order the rows are shown in, or {@link Sort#none()}
 * @param marked the row ids the user marked, in the order they were marked
 * @param widths the column widths the reader set, keyed by column key
 */
public record DataTableModel(
        DataTableContent content,
        List<Row> rows,
        Selection selection,
        int top,
        int firstColumn,
        int height,
        int width,
        boolean focused,
        String filter,
        Sort sort,
        List<String> marked,
        Map<String, Integer> widths) {

    /**
     * Keeps the state representable and derives the visible rows.
     */
    public DataTableModel {
        content = content == null ? DataTableContent.empty() : content;
        filter = filter == null ? "" : filter;
        sort = sort == null ? Sort.none() : sort;
        // A width the schema does not have, or one no column could be painted at, is dropped
        // where the state is made rather than repaired while painting.
        Map<String, Integer> keptWidths = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : (widths == null ? Map.<String, Integer>of() : widths).entrySet()) {
            if (entry.getKey() != null
                    && entry.getValue() != null
                    && entry.getValue() > 0
                    && content.indexOfColumn(entry.getKey()) >= 0) {
                keptWidths.put(entry.getKey(), entry.getValue());
            }
        }
        widths = Collections.unmodifiableMap(keptWidths);
        Set<String> ids = new HashSet<>();
        for (Row row : content.rows()) {
            ids.add(row.id());
        }
        List<String> keptMarks = new ArrayList<>();
        for (String id : marked == null ? List.<String>of() : marked) {
            if (id != null && ids.contains(id) && !keptMarks.contains(id)) {
                keptMarks.add(id);
            }
        }
        marked = List.copyOf(keptMarks);
        rows = DataTableData.displayRows(content, filter, sort);
        Selection value = selection == null ? Selection.none() : selection;
        int columns = content.columnCount();
        selection = new Selection(
                value.mode(),
                rows.isEmpty() ? -1 : Math.max(0, Math.min(value.row(), rows.size() - 1)),
                columns <= 0 ? -1 : Math.max(0, Math.min(value.column(), columns - 1)));
        top = Math.max(0, Math.min(top, Math.max(0, rows.size() - 1)));
        height = Math.max(0, height);
        width = Math.max(0, width);
        firstColumn =
                Math.max(0, Math.min(firstColumn, Math.max(0, DataTableData.resolveRowWidth(content, widths) - width)));
    }

    /**
     * The same state without a filter, a sort, marks or column overrides.
     *
     * @param content the columns and rows, and the widths they resolved to
     * @param selection the selected row and cell
     * @param top the index of the first body row the window shows
     * @param firstColumn the first painted cell the window shows
     * @param height the rows the parent allocated, header included
     * @param width the cells the parent allocated
     * @param focused whether the table owns focus
     */
    public DataTableModel(
            DataTableContent content,
            Selection selection,
            int top,
            int firstColumn,
            int height,
            int width,
            boolean focused) {
        this(
                content,
                List.of(),
                selection,
                top,
                firstColumn,
                height,
                width,
                focused,
                "",
                Sort.none(),
                List.of(),
                Map.of());
    }

    /**
     * A table showing the first row of some content.
     *
     * @param content the columns and rows; null is empty content
     * @return the state
     */
    public static DataTableModel of(DataTableContent content) {
        DataTableContent values = content == null ? DataTableContent.empty() : content;
        boolean present = values.rowCount() > 0 && values.columnCount() > 0;
        return new DataTableModel(
                values,
                new Selection(Mode.ROW, present ? 0 : -1, present ? 0 : -1),
                0,
                0,
                DEFAULT_HEIGHT,
                DEFAULT_WIDTH,
                true);
    }

    /**
     * The same state over different content, with the selected row carried over by id.
     *
     * <p>A selection the new content does not hold falls on the row that takes its place, or on
     * the last row when it was the last one; a table that was empty selects the new first row.</p>
     *
     * @param value the new content; null is empty content
     * @return the new state
     */
    public DataTableModel withContent(DataTableContent value) {
        DataTableContent next = value == null ? DataTableContent.empty() : value;
        String selectedId = selectedRow().map(Row::id).orElse("");
        return rebuilt(next, filter, sort, marked, widths, selection.row(), selectedId);
    }

    /**
     * The same content with a different selection.
     *
     * <p>The window is not moved here: a projection reveals the selection, so a state built this
     * way still paints it.</p>
     *
     * @param value the selection; null is no selection
     * @return the new state
     */
    public DataTableModel withSelection(Selection value) {
        return new DataTableModel(
                content, List.of(), value, top, firstColumn, height, width, focused, filter, sort, marked, widths);
    }

    /**
     * The same state with a different focus.
     *
     * <p>A reduction never changes focus; the application owns it, exactly as it does for {@link
     * Editor} and {@link TextViewport}.</p>
     *
     * @param value whether the table owns focus
     * @return this instance when the focus did not change
     */
    public DataTableModel withFocus(boolean value) {
        return value == focused
                ? this
                : new DataTableModel(
                        content,
                        List.of(),
                        selection,
                        top,
                        firstColumn,
                        height,
                        width,
                        value,
                        filter,
                        sort,
                        marked,
                        widths);
    }

    /**
     * The same state at a different geometry.
     *
     * @param rows the rows the parent allocated; negative is zero
     * @param cells the cells the parent allocated; negative is zero
     * @return the new state
     */
    public DataTableModel withGeometry(int rows, int cells) {
        return new DataTableModel(
                content, List.of(), selection, top, firstColumn, rows, cells, focused, filter, sort, marked, widths);
    }

    /**
     * The same state with a different filter, keeping the selected row's id selected.
     *
     * @param value the text that hides rows; null or blank shows every row
     * @return this instance when the filter did not change
     */
    public DataTableModel withFilter(String value) {
        String next = value == null ? "" : value;
        return next.equals(filter)
                ? this
                : rebuilt(
                        content,
                        next,
                        sort,
                        marked,
                        widths,
                        selection.row(),
                        selectedRow().map(Row::id).orElse(""));
    }

    /**
     * The same state with a different sort, keeping the selected row's id selected.
     *
     * @param value the order; null is {@link Sort#none()}
     * @return this instance when the sort did not change
     */
    public DataTableModel withSort(Sort value) {
        Sort next = value == null ? Sort.none() : value;
        return next.equals(sort)
                ? this
                : rebuilt(
                        content,
                        filter,
                        next,
                        marked,
                        widths,
                        selection.row(),
                        selectedRow().map(Row::id).orElse(""));
    }

    /**
     * The same state with different marks.
     *
     * @param value the ids to mark, in the order they were marked; null clears every mark
     * @return this instance when the marks did not change
     */
    public DataTableModel withMarked(List<String> value) {
        List<String> next = value == null ? List.of() : List.copyOf(value);
        return next.equals(marked)
                ? this
                : rebuilt(
                        content,
                        filter,
                        sort,
                        next,
                        widths,
                        selection.row(),
                        selectedRow().map(Row::id).orElse(""));
    }

    /**
     * The same state with different column widths.
     *
     * @param value the widths to set, keyed by column key; null drops every override
     * @return this instance when the widths did not change
     */
    public DataTableModel withWidths(Map<String, Integer> value) {
        Map<String, Integer> next = value == null ? Map.of() : Map.copyOf(value);
        return next.equals(widths)
                ? this
                : rebuilt(
                        content,
                        filter,
                        sort,
                        marked,
                        next,
                        selection.row(),
                        selectedRow().map(Row::id).orElse(""));
    }

    /**
     * Whether one row is marked.
     *
     * @param id the row id to test
     * @return true when the row is marked
     */
    public boolean isMarked(String id) {
        return id != null && marked.contains(id);
    }

    /**
     * The marked rows in display order.
     *
     * @return the marked rows
     */
    public List<Row> markedRows() {
        List<Row> chosen = new ArrayList<>(marked.size());
        for (Row row : rows) {
            if (marked.contains(row.id())) {
                chosen.add(row);
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * The width one column is painted at: the reader's width when it was resized, otherwise the
     * width the content resolved.
     *
     * @param column the column index
     * @return the painted width, or zero when the column does not exist
     */
    public int columnWidth(int column) {
        if (column < 0 || column >= content.columnCount()) {
            return 0;
        }
        Integer override = widths.get(content.columns().get(column).key());
        return override != null ? override : content.widths().get(column);
    }

    /**
     * The first painted cell one column occupies, at the widths this state paints.
     *
     * @param column the column index
     * @return the cell offset, or zero when the column does not exist
     */
    public int columnStart(int column) {
        int start = 0;
        for (int index = 0; index < column && index < content.columnCount(); index++) {
            start += columnWidth(index) + GAP;
        }
        return start;
    }

    /**
     * The cells one row paints at the widths this state holds.
     *
     * @return the painted row width, never negative
     */
    public int paintedRowWidth() {
        return DataTableData.resolveRowWidth(content, widths);
    }

    /**
     * The selected row.
     *
     * @return the row, or empty when nothing is selected
     */
    public Optional<Row> selectedRow() {
        int row = selection.row();
        return row >= 0 && row < rows.size() ? Optional.of(rows.get(row)) : Optional.empty();
    }

    /**
     * The selected column.
     *
     * @return the column, or empty when the schema is empty
     */
    public Optional<Column> selectedColumn() {
        int column = selection.column();
        return column >= 0 && column < content.columnCount()
                ? Optional.of(content.columns().get(column))
                : Optional.empty();
    }

    /**
     * The selected value.
     *
     * @return the value, or empty when nothing is selected
     */
    public Optional<StyledText> selectedValue() {
        int row = selection.row();
        int column = selection.column();
        if (row < 0 || row >= rows.size() || column < 0 || column >= content.columnCount()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(row).cell(column));
    }

    /**
     * The same state rebuilt around new content, a filter, a sort, marks and widths.
     *
     * <p>The selected row is carried over by id: a filter or a sort that only reorders keeps the
     * reader on the same row, and one that hides it moves the selection to the row that takes its
     * place.</p>
     */
    private DataTableModel rebuilt(
            DataTableContent nextContent,
            String nextFilter,
            Sort nextSort,
            List<String> nextMarked,
            Map<String, Integer> nextWidths,
            int fallbackRow,
            String selectedId) {
        DataTableModel probe = new DataTableModel(
                nextContent,
                List.of(),
                selection,
                top,
                firstColumn,
                height,
                width,
                focused,
                nextFilter,
                nextSort,
                nextMarked,
                nextWidths);
        int row = probe.rows().isEmpty() ? -1 : DataTableData.visibleIndex(probe.rows(), selectedId);
        if (row < 0) {
            row = probe.rows().isEmpty()
                    ? -1
                    : Math.max(0, Math.min(fallbackRow, probe.rows().size() - 1));
        }
        if (row == probe.selection().row()) {
            return probe;
        }
        return new DataTableModel(
                probe.content(),
                List.of(),
                new Selection(probe.selection().mode(), row, probe.selection().column()),
                probe.top(),
                probe.firstColumn(),
                height,
                width,
                focused,
                nextFilter,
                nextSort,
                nextMarked,
                nextWidths);
    }
}
