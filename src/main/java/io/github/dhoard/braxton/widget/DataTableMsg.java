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

import io.github.dhoard.braxton.widget.DataTable.Mode;
import io.github.dhoard.braxton.widget.DataTable.Output;
import io.github.dhoard.braxton.widget.DataTable.Row;
import io.github.dhoard.braxton.widget.DataTable.Sort;
import java.util.List;
import java.util.Objects;

/**
 * One navigation, presentation or content message.
 */
public sealed interface DataTableMsg {

    /**
     * Selects the row above, revealing it.
     */
    record Up() implements DataTableMsg {}

    /**
     * Selects the row below, revealing it.
     */
    record Down() implements DataTableMsg {}

    /**
     * Selects the column before the selected one, in {@link Mode#CELL}; the gesture does nothing
     * in {@link Mode#ROW}, where the selection covers the whole row.
     */
    record Left() implements DataTableMsg {}

    /**
     * Selects the column after the selected one, in {@link Mode#CELL}; the gesture does nothing
     * in {@link Mode#ROW}, where the selection covers the whole row.
     */
    record Right() implements DataTableMsg {}

    /**
     * Selects the row one window below, revealing it.
     */
    record PageDown() implements DataTableMsg {}

    /**
     * Selects the row one window above, revealing it.
     */
    record PageUp() implements DataTableMsg {}

    /**
     * Selects the row half a window below, revealing it.
     */
    record HalfPageDown() implements DataTableMsg {}

    /**
     * Selects the row half a window above, revealing it.
     */
    record HalfPageUp() implements DataTableMsg {}

    /**
     * Selects the first row, revealing it.
     */
    record Top() implements DataTableMsg {}

    /**
     * Selects the last row, revealing it.
     */
    record Bottom() implements DataTableMsg {}

    /**
     * Pans toward the first cell.
     *
     * <p>The count is never negative and zero changes nothing. A {@link Mode#CELL} selection
     * stays visible, so the pan stops where the selected cell's right edge would leave the
     * window.</p>
     *
     * @param columns how many cells to show from further left
     */
    record PanLeft(int columns) implements DataTableMsg {
        /**
         * Creates a {@code PanLeft} value.
         *
         * @param columns how many cells to show from further left
         */
        public PanLeft {
            columns = Math.max(0, columns);
        }
    }

    /**
     * Pans toward the last cell.
     *
     * <p>The count is never negative and zero changes nothing. A {@link Mode#CELL} selection
     * stays visible, so the pan stops where the selected cell's left edge would leave the
     * window.</p>
     *
     * @param columns how many more cells to show from further right
     */
    record PanRight(int columns) implements DataTableMsg {
        /**
         * Creates a {@code PanRight} value.
         *
         * @param columns how many more cells to show from further right
         */
        public PanRight {
            columns = Math.max(0, columns);
        }
    }

    /**
     * Switches the selection between a whole row and one cell of it.
     */
    record ToggleMode() implements DataTableMsg {}

    /**
     * Selects one of the two selection modes.
     *
     * @param mode the mode; required
     */
    record SetMode(Mode mode) implements DataTableMsg {
        /**
         * Creates a {@code SetMode} value.
         *
         * @param mode the mode; required
         */
        public SetMode {
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * Selects one row by id, revealing it.
     *
     * @param rowId the row id; required
     */
    record Select(String rowId) implements DataTableMsg {
        /**
         * Creates a {@code Select} value.
         *
         * @param rowId the row id; required
         */
        public Select {
            Objects.requireNonNull(rowId, "rowId");
        }
    }

    /**
     * Selects one cell: its row, and the column the pointer was over.
     *
     * <p>This is what a click on a cellwise table means. A row-mode table has no column to
     * select, so a click there asks for {@link Select} alone.</p>
     *
     * @param rowId the id of the row to select; required
     * @param column the column index to select; clamped into the schema
     */
    record SelectCell(String rowId, int column) implements DataTableMsg {

        /**
         * Rejects no id; an unknown one is rejected by the reduction.
         */
        public SelectCell {
            rowId = rowId == null ? "" : rowId;
        }
    }

    /**
     * Reports the selection to the application as an {@link Output.Chosen}, if there is one.
     */
    record Choose() implements DataTableMsg {}

    /**
     * Reports a canceled table as an {@link Output.Cancelled}.
     */
    record Cancel() implements DataTableMsg {}

    /**
     * Hides the rows the filter does not match, keeping the selected row selected.
     *
     * <p>The filter is the table's own matcher: every whitespace-separated term must appear in
     * one of a row's cells, ignoring case. The text is model data rather than a field the table
     * owns, so an application composes a {@link TextInput} and sends this message - which is the
     * same contract every other message of this component has.</p>
     *
     * @param value the filter text; null or blank shows every row
     */
    record SetFilter(String value) implements DataTableMsg {}

    /**
     * Clears the filter, showing every row again.
     */
    record ClearFilter() implements DataTableMsg {}

    /**
     * Orders the rows by one column, or drops the order when the column is already sorted
     * ascending: a press of the same gesture cycles ascending, descending and unsorted.
     *
     * @param key the column key to sort by; a key the schema does not hold leaves the order
     */
    record ToggleSort(String key) implements DataTableMsg {
        /**
         * Creates a {@code ToggleSort} value.
         *
         * @param key the column key to sort by; a key the schema does not hold leaves the order
         */
        public ToggleSort {
            key = key == null ? "" : key;
        }
    }

    /**
     * Orders the rows by one column.
     *
     * @param sort the order; null is {@link Sort#none()}
     */
    record SortBy(Sort sort) implements DataTableMsg {}

    /**
     * Orders the rows by the selected column, cycling ascending, descending and unsorted.
     *
     * <p>This is the key-bound form of {@link ToggleSort}: a key map carries a message, not a
     * column, so the selected column is resolved when the message is reduced.</p>
     */
    record SortSelected() implements DataTableMsg {}

    /**
     * Widens or narrows the selected column.
     *
     * @param delta the cells to add, or to take away when negative
     */
    record ResizeSelected(int delta) implements DataTableMsg {}

    /**
     * Marks the selected row, or unmarks it when it is already marked, and steps down.
     */
    record ToggleMark() implements DataTableMsg {}

    /**
     * Marks every visible row.
     */
    record MarkAll() implements DataTableMsg {}

    /**
     * Drops every mark.
     */
    record ClearMarks() implements DataTableMsg {}

    /**
     * Sets one column's painted width.
     *
     * <p>The width is the reader's, not the schema's: it is kept for as long as the table shows
     * this content and dropped when the content no longer holds the column. A width below one is
     * ignored, so a column can never be made unpaintable.</p>
     *
     * @param key the column key; a key the schema does not hold is a no-op
     * @param width the cells the column should occupy
     */
    record ResizeColumn(String key, int width) implements DataTableMsg {
        /**
         * Creates a {@code ResizeColumn} value.
         *
         * @param key the column key; a key the schema does not hold is a no-op
         * @param width the cells the column should occupy
         */
        public ResizeColumn {
            key = key == null ? "" : key;
        }
    }

    /**
     * Narrows or widens one column by a number of cells.
     *
     * @param key the column key; a key the schema does not hold is a no-op
     * @param delta the cells to add, or to take away when negative
     */
    record AdjustColumn(String key, int delta) implements DataTableMsg {
        /**
         * Creates a {@code AdjustColumn} value.
         *
         * @param key the column key; a key the schema does not hold is a no-op
         * @param delta the cells to add, or to take away when negative
         */
        public AdjustColumn {
            key = key == null ? "" : key;
        }
    }

    /**
     * Reports the geometry the parent allocated.
     *
     * <p>Negative values become zero, which paints and scrolls nothing. The window keeps its
     * offsets as far as the new geometry allows and the selection stays visible.</p>
     *
     * @param height the rows the parent allocated, header included
     * @param width the cells the parent allocated
     */
    record Resized(int height, int width) implements DataTableMsg {
        /**
         * Creates a {@code Resized} value.
         *
         * @param height the rows the parent allocated, header included
         * @param width the cells the parent allocated
         */
        public Resized {
            height = Math.max(0, height);
            width = Math.max(0, width);
        }
    }

    /**
     * Replaces every row, keeping the selected row's id selected.
     *
     * @param rows the new rows, in display order; null is no rows
     */
    record SetRows(List<Row> rows) implements DataTableMsg {
        /**
         * Creates a {@code SetRows} value.
         *
         * @param rows the new rows, in display order; null is no rows
         */
        public SetRows {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * Inserts one row.
     *
     * @param index the position the row takes, clamped into the table
     * @param row the row; required
     */
    record Insert(int index, Row row) implements DataTableMsg {
        /**
         * Creates a {@code Insert} value.
         *
         * @param index the position the row takes, clamped into the table
         * @param row the row; required
         */
        public Insert {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * Replaces one row, matched by id.
     *
     * @param row the new row; required
     */
    record Replace(Row row) implements DataTableMsg {
        /**
         * Creates a {@code Replace} value.
         *
         * @param row the new row; required
         */
        public Replace {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * Removes one row, matched by id.
     *
     * @param rowId the id of the row to remove; required
     */
    record Remove(String rowId) implements DataTableMsg {
        /**
         * Creates a {@code Remove} value.
         *
         * @param rowId the id of the row to remove; required
         */
        public Remove {
            Objects.requireNonNull(rowId, "rowId");
        }
    }
}
