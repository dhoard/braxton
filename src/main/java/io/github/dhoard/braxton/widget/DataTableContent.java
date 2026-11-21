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
import io.github.dhoard.braxton.widget.DataTable.Column;
import io.github.dhoard.braxton.widget.DataTable.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of a table: its columns, its rows and the widths the columns resolved to.
 *
 * <p>The widths are resolved once, from the columns and the rows, when the content is built.
 * Nothing else in this class measures a cell, so a table of a hundred thousand rows paints a
 * window in the time a table of ten does. DataTableContent is also where the schema's invariants are
 * enforced: two columns may not share a key and two rows may not share an id.</p>
 *
 * <p>A content value is compared by its columns and rows. The header text is measured even when
 * {@link DataTableConfig#header()} paints no header, so toggling the header can never reflow the
 * columns.</p>
 */
public final class DataTableContent {

    private static final DataTableContent EMPTY = new DataTableContent(List.of(), List.of());

    private final List<Column> columns;
    private final List<Row> rows;
    private final List<Integer> widths;
    private final int rowWidth;

    private DataTableContent(List<Column> columns, List<Row> rows) {
        this.columns = columns;
        this.rows = rows;
        this.widths = measured(columns, rows);
        this.rowWidth = paintedWidth(this.widths);
    }

    /**
     * DataTableContent with no columns and no rows.
     *
     * @return the empty content
     */
    public static DataTableContent empty() {
        return EMPTY;
    }

    /**
     * A snapshot of a schema and its rows.
     *
     * @param columns the columns, in paint order; null or empty is a table with no columns
     * @param rows the rows, in display order; null or empty is a table with no rows
     * @return the content
     * @throws IllegalArgumentException when two columns share a key or two rows share an id
     */
    public static DataTableContent of(List<Column> columns, List<Row> rows) {
        List<Column> schema = columns == null ? List.of() : List.copyOf(columns);
        List<Row> values = rows == null ? List.of() : List.copyOf(rows);
        requireUniqueColumns(schema);
        requireUniqueRows(values);
        return new DataTableContent(schema, values);
    }

    /**
     * The columns, in paint order.
     *
     * @return the columns
     */
    public List<Column> columns() {
        return columns;
    }

    /**
     * The rows, in display order.
     *
     * @return the rows
     */
    public List<Row> rows() {
        return rows;
    }

    /**
     * The cells each column resolved to, in column order.
     *
     * @return the column widths
     */
    public List<Integer> widths() {
        return widths;
    }

    /**
     * How many rows the content holds.
     *
     * @return the row count
     */
    public int rowCount() {
        return rows.size();
    }

    /**
     * How many columns the schema holds.
     *
     * @return the column count
     */
    public int columnCount() {
        return columns.size();
    }

    /**
     * The cells one row paints: every column's resolved width and the gap after it.
     *
     * @return the painted width of a row, never negative
     */
    public int rowWidth() {
        return rowWidth;
    }

    /**
     * The first painted cell one column occupies in a row.
     *
     * @param column the column index
     * @return the cell offset of the column, or zero when it does not exist
     */
    public int columnStart(int column) {
        if (column <= 0) {
            return 0;
        }
        long start = 0;
        for (int index = 0; index < column && index < widths.size(); index++) {
            start += widths.get(index) + GAP;
        }
        return (int) Math.min(Integer.MAX_VALUE, start);
    }

    /**
     * The index of a row.
     *
     * @param id the row id
     * @return the row's index, or -1 when the content does not hold it
     */
    public int indexOf(String id) {
        if (id == null) {
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
     * The index of a column.
     *
     * @param key the column key
     * @return the column's index, or -1 when the schema does not hold it
     */
    public int indexOfColumn(String key) {
        if (key == null) {
            return -1;
        }
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).key().equals(key)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The same schema over different rows.
     *
     * @param value the rows, in display order; null is no rows
     * @return the content
     * @throws IllegalArgumentException when two of the new rows share an id
     */
    public DataTableContent withRows(List<Row> value) {
        return of(columns, value);
    }

    /**
     * The same rows under a different schema.
     *
     * @param value the columns, in paint order; null is no columns
     * @return the content
     * @throws IllegalArgumentException when two of the new columns share a key
     */
    public DataTableContent withColumns(List<Column> value) {
        return of(value, rows);
    }

    /**
     * The same content with one row inserted.
     *
     * @param index the position the row takes, clamped into {@code [0, rowCount()]}
     * @param value the row; required
     * @return the content
     * @throws IllegalArgumentException when the id is already taken
     */
    public DataTableContent inserted(int index, Row value) {
        Objects.requireNonNull(value, "row");
        int at = Math.max(0, Math.min(index, rows.size()));
        List<Row> grown = new ArrayList<>(rows.size() + 1);
        grown.addAll(rows.subList(0, at));
        grown.add(value);
        grown.addAll(rows.subList(at, rows.size()));
        return of(columns, grown);
    }

    /**
     * The same content with one row replaced, matched by id.
     *
     * @param value the new row; required
     * @return the content
     * @throws IllegalArgumentException when no row holds that id
     */
    public DataTableContent replaced(Row value) {
        Objects.requireNonNull(value, "row");
        int index = indexOf(value.id());
        if (index < 0) {
            throw new IllegalArgumentException("unknown row id: " + value.id());
        }
        List<Row> replaced = new ArrayList<>(rows);
        replaced.set(index, value);
        return of(columns, replaced);
    }

    /**
     * The same content without one row.
     *
     * @param id the id of the row to remove; required
     * @return the content
     * @throws IllegalArgumentException when no row holds that id
     */
    public DataTableContent removed(String id) {
        int index = indexOf(id);
        if (index < 0) {
            throw new IllegalArgumentException("unknown row id: " + id);
        }
        List<Row> fewer = new ArrayList<>(rows);
        fewer.remove(index);
        return of(columns, fewer);
    }

    /**
     * The value at one row and column of the schema.
     *
     * <p>A value a row holds beyond the schema is not part of the table, so it is not answered
     * here and never painted.</p>
     *
     * @param row the row index
     * @param column the column index
     * @return the value, or the empty value when either index names nothing
     */
    public StyledText cell(int row, int column) {
        return row >= 0 && row < rows.size() && column >= 0 && column < columns.size()
                ? rows.get(row).cell(column)
                : StyledText.empty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof DataTableContent that && columns.equals(that.columns) && rows.equals(that.rows);
    }

    @Override
    public int hashCode() {
        return 31 * columns.hashCode() + rows.hashCode();
    }

    @Override
    public String toString() {
        return "DataTableContent[" + rows.size() + " rows of " + columns.size() + " columns, " + rowWidth + " cells]";
    }

    /**
     * Rejects two columns with one key.
     */
    private static void requireUniqueColumns(List<Column> columns) {
        Map<String, Integer> keys = new HashMap<>(columns.size() * 2);
        for (int index = 0; index < columns.size(); index++) {
            Column column = Objects.requireNonNull(columns.get(index), "column");
            Integer previous = keys.putIfAbsent(column.key(), index);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate column key: " + column.key());
            }
        }
    }

    /**
     * Rejects two rows with one id.
     */
    private static void requireUniqueRows(List<Row> rows) {
        Map<String, Integer> ids = new HashMap<>(rows.size() * 2);
        for (int index = 0; index < rows.size(); index++) {
            Row row = Objects.requireNonNull(rows.get(index), "row");
            Integer previous = ids.putIfAbsent(row.id(), index);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate row id: " + row.id());
            }
        }
    }

    /**
     * The cells each column resolves to: the header and its widest cell, inside its policy.
     */
    private static List<Integer> measured(List<Column> columns, List<Row> rows) {
        int count = columns.size();
        int[] widest = new int[count];
        for (int column = 0; column < count; column++) {
            widest[column] = GridRows.widest(DataTableRendering.singleRow(
                    StyledText.plain(columns.get(column).header())));
        }
        for (Row row : rows) {
            int cells = Math.min(count, row.cells().size());
            for (int column = 0; column < cells; column++) {
                widest[column] = Math.max(
                        widest[column],
                        GridRows.widest(DataTableRendering.singleRow(row.cells().get(column))));
            }
        }
        List<Integer> resolved = new ArrayList<>(count);
        for (int column = 0; column < count; column++) {
            resolved.add(columns.get(column).width().resolve(widest[column]));
        }
        return List.copyOf(resolved);
    }

    /**
     * What one row paints: every column's width and the gap after it.
     */
    private static int paintedWidth(List<Integer> widths) {
        long total = 0;
        for (int width : widths) {
            total += width + GAP;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
