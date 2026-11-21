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
import io.github.dhoard.braxton.widget.DataTable.Mode;
import io.github.dhoard.braxton.widget.DataTable.Output;
import io.github.dhoard.braxton.widget.DataTable.Result;
import io.github.dhoard.braxton.widget.DataTable.Row;
import io.github.dhoard.braxton.widget.DataTable.Selection;
import io.github.dhoard.braxton.widget.DataTable.Sort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure table selection, navigation, and data transitions.
 */
final class DataTableEditing {

    /**
     * Applies one message. Never performs I/O, never mutates the input state.
     */
    static Result reduce(DataTableModel state, DataTableConfig config, DataTableMsg message) {
        return switch (message) {
            case DataTableMsg.Up up ->
                new Result(selected(state, config, state.selection().row() - 1), null);
            case DataTableMsg.Down down ->
                new Result(selected(state, config, state.selection().row() + 1), null);
            case DataTableMsg.PageUp pageUp ->
                new Result(movedBy(state, config, -DataTable.bodyHeight(state, config)), null);
            case DataTableMsg.PageDown pageDown ->
                new Result(movedBy(state, config, DataTable.bodyHeight(state, config)), null);
            case DataTableMsg.HalfPageUp half ->
                new Result(movedBy(state, config, -(DataTable.bodyHeight(state, config) / 2)), null);
            case DataTableMsg.HalfPageDown half ->
                new Result(movedBy(state, config, DataTable.bodyHeight(state, config) / 2), null);
            case DataTableMsg.Top top -> new Result(selected(state, config, 0), null);
            case DataTableMsg.Bottom bottom ->
                new Result(selected(state, config, state.rows().size() - 1), null);
            case DataTableMsg.Left left -> new Result(columnsMoved(state, config, -1), null);
            case DataTableMsg.Right right -> new Result(columnsMoved(state, config, 1), null);
            case DataTableMsg.PanLeft pan -> new Result(panned(state, config, -pan.columns()), null);
            case DataTableMsg.PanRight pan -> new Result(panned(state, config, pan.columns()), null);
            case DataTableMsg.ToggleMode toggle ->
                new Result(
                        modeChanged(state, config, state.selection().mode() == Mode.ROW ? Mode.CELL : Mode.ROW), null);
            case DataTableMsg.SetMode mode -> new Result(modeChanged(state, config, mode.mode()), null);
            case DataTableMsg.Select select -> new Result(selectRequested(state, config, select.rowId()), null);
            case DataTableMsg.SelectCell cell ->
                new Result(selectCellRequested(state, config, cell.rowId(), cell.column()), null);
            case DataTableMsg.Choose choose -> new Result(state, chosen(state));
            case DataTableMsg.Cancel cancel -> new Result(state, new Output.Cancelled());
            case DataTableMsg.Resized resized ->
                new Result(resized(state, config, resized.height(), resized.width()), null);
            case DataTableMsg.SetRows rows -> new Result(rowsChanged(state, config, rows.rows()), null);
            case DataTableMsg.Insert insert -> new Result(inserted(state, config, insert.index(), insert.row()), null);
            case DataTableMsg.Replace replace -> new Result(replaced(state, config, replace.row()), null);
            case DataTableMsg.Remove remove -> new Result(removed(state, config, remove.rowId()), null);
            case DataTableMsg.SetFilter filter -> new Result(revealed(state.withFilter(filter.value()), config), null);
            case DataTableMsg.ClearFilter clear -> new Result(revealed(state.withFilter(""), config), null);
            case DataTableMsg.ToggleSort sort ->
                new Result(revealed(state.withSort(cycled(state.sort(), sort.key())), config), null);
            case DataTableMsg.SortBy sort -> new Result(revealed(state.withSort(sort.sort()), config), null);
            case DataTableMsg.SortSelected sort -> {
                Column column = state.selectedColumn().orElse(null);
                yield column == null
                        ? new Result(state, null)
                        : new Result(revealed(state.withSort(cycled(state.sort(), column.key())), config), null);
            }
            case DataTableMsg.ResizeSelected resize -> {
                Column column = state.selectedColumn().orElse(null);
                yield column == null || resize.delta() == 0
                        ? new Result(state, null)
                        : new Result(
                                resizedColumn(
                                        state,
                                        config,
                                        column.key(),
                                        state.columnWidth(state.selection().column()) + resize.delta()),
                                null);
            }
            case DataTableMsg.ToggleMark mark -> new Result(markToggled(state, config), null);
            case DataTableMsg.MarkAll all -> new Result(markAll(state), null);
            case DataTableMsg.ClearMarks clear -> new Result(state.withMarked(List.of()), null);
            case DataTableMsg.ResizeColumn resize ->
                new Result(resizedColumn(state, config, resize.key(), resize.width()), null);
            case DataTableMsg.AdjustColumn adjust -> {
                int index = state.content().indexOfColumn(adjust.key());
                yield index < 0
                        ? new Result(state, null)
                        : new Result(
                                resizedColumn(state, config, adjust.key(), state.columnWidth(index) + adjust.delta()),
                                null);
            }
        };
    }

    /**
     * The order one sort gesture asks for: ascending, then descending, then none.
     */
    static Sort cycled(Sort current, String key) {
        if (key.isEmpty() || !key.equals(current.column())) {
            return key.isEmpty() ? Sort.none() : new Sort(key, false);
        }
        return current.descending() ? Sort.none() : new Sort(key, true);
    }

    /**
     * Marks or unmarks the selected row and steps down to the next one.
     */
    static DataTableModel markToggled(DataTableModel state, DataTableConfig config) {
        String id = state.selectedRow().map(Row::id).orElse("");
        if (id.isEmpty()) {
            return state;
        }
        List<String> marked = new ArrayList<>(state.marked());
        if (!marked.remove(id)) {
            marked.add(id);
        }
        DataTableModel next = state.withMarked(marked);
        int index = state.selection().row();
        return index + 1 >= next.rows().size() ? next : selected(next, config, index + 1);
    }

    /**
     * Marks every row the filter shows, keeping the marks already made.
     */
    static DataTableModel markAll(DataTableModel state) {
        List<String> marked = new ArrayList<>(state.marked());
        for (Row row : state.rows()) {
            if (!marked.contains(row.id())) {
                marked.add(row.id());
            }
        }
        return state.withMarked(marked);
    }

    /**
     * One column widened or narrowed by the reader, with the window re-clamped and revealed.
     */
    static DataTableModel resizedColumn(DataTableModel state, DataTableConfig config, String key, int width) {
        if (key.isEmpty() || state.content().indexOfColumn(key) < 0) {
            return state;
        }
        Map<String, Integer> widths = new LinkedHashMap<>(state.widths());
        widths.put(key, Math.max(1, width));
        return revealed(state.withWidths(widths), config);
    }

    /**
     * The state with one row selected, the row revealed and the geometry unchanged.
     */
    static DataTableModel selected(DataTableModel state, DataTableConfig config, int row) {
        int rows = state.rows().size();
        int target = rows <= 0 ? -1 : Math.max(0, Math.min(row, rows - 1));
        DataTableModel moved = DataTableData.copy(
                state,
                new Selection(
                        state.selection().mode(), target, state.selection().column()),
                state.top(),
                state.firstColumn(),
                state.height(),
                state.width());
        return revealed(moved, config);
    }

    /**
     * The state with the selection moved by rows, which is what a page gesture does.
     */
    static DataTableModel movedBy(DataTableModel state, DataTableConfig config, int rows) {
        int count = state.rows().size();
        if (count == 0) {
            return state;
        }
        int current = Math.max(0, state.selection().row());
        int maxTop = Math.max(0, count - DataTable.bodyHeight(state, config));
        // A page moves the window as well as the selection: a page that only moved the selection
        // would reveal one new row and hide one, which is not what a reader asking for the next
        // page wants. Both are clamped to the content, so the last page lands on the last window.
        int selection = Math.max(0, Math.min(current + rows, count - 1));
        int top = Math.max(0, Math.min(revealedTop(state, config) + rows, maxTop));
        DataTableModel moved = DataTableData.copy(
                state,
                new Selection(
                        state.selection().mode(), selection, state.selection().column()),
                top,
                state.firstColumn(),
                state.height(),
                state.width());
        return revealed(moved, config);
    }

    /**
     * The state with the selected column moved, which only a cell selection does.
     */
    static DataTableModel columnsMoved(DataTableModel state, DataTableConfig config, int columns) {
        if (!state.selection().cellwise() || state.content().columnCount() == 0) {
            return state;
        }
        int current = state.selection().column();
        int target = Math.max(
                0,
                Math.min((current < 0 ? 0 : current) + columns, state.content().columnCount() - 1));
        DataTableModel moved = DataTableData.copy(
                state,
                new Selection(Mode.CELL, state.selection().row(), target),
                state.top(),
                state.firstColumn(),
                state.height(),
                state.width());
        return revealed(moved, config);
    }

    /**
     * The state panned by cells, clamped to the content and to the selected cell.
     */
    static DataTableModel panned(DataTableModel state, DataTableConfig config, int columns) {
        if (columns == 0) {
            return state;
        }
        int max = Math.max(0, state.paintedRowWidth() - DataTable.contentWidth(state, config));
        int current = effectiveFirstColumn(state, config);
        int target = (int) Math.max(0L, Math.min((long) max, (long) current + columns));
        if (target == current && state.firstColumn() == current) {
            return state;
        }
        DataTableModel moved =
                DataTableData.copy(state, state.selection(), state.top(), target, state.height(), state.width());
        // A cell selection keeps its cell on screen, so the pan is re-clamped; a row selection has
        // the whole row selected and is free to reach any of its cells.
        return revealed(moved, config);
    }

    /**
     * The same state in another selection mode.
     */
    static DataTableModel modeChanged(DataTableModel state, DataTableConfig config, Mode mode) {
        if (state.selection().mode() == mode) {
            return state;
        }
        DataTableModel changed = DataTableData.copy(
                state,
                new Selection(mode, state.selection().row(), state.selection().column()),
                state.top(),
                state.firstColumn(),
                state.height(),
                state.width());
        return revealed(changed, config);
    }

    /**
     * The same state with one row selected by id.
     */
    static DataTableModel selectRequested(DataTableModel state, DataTableConfig config, String rowId) {
        int row = state.content().indexOf(rowId);
        if (row < 0) {
            throw new IllegalArgumentException("unknown row id: " + rowId);
        }
        return selected(state, config, row);
    }

    /**
     * The state with one cell selected: the row, and the column clicked in a cellwise table.
     */
    static DataTableModel selectCellRequested(DataTableModel state, DataTableConfig config, String rowId, int column) {
        int row = state.content().indexOf(rowId);
        if (row < 0) {
            throw new IllegalArgumentException("unknown row id: " + rowId);
        }
        DataTableModel selected = selected(state, config, row);
        if (!selected.selection().cellwise()) {
            return selected;
        }
        int columns = selected.content().columnCount();
        int target = Math.max(0, Math.min(column, columns - 1));
        return DataTableData.copy(
                selected,
                new Selection(selected.selection().mode(), selected.selection().row(), target),
                selected.top(),
                selected.firstColumn(),
                selected.height(),
                selected.width());
    }

    /**
     * The new geometry, with the window clamped to it and the selection revealed.
     */
    static DataTableModel resized(DataTableModel state, DataTableConfig config, int height, int width) {
        return revealed(state.withGeometry(height, width), config);
    }

    /**
     * Every row replaced, with the selected id carried over.
     */
    static DataTableModel rowsChanged(DataTableModel state, DataTableConfig config, List<Row> rows) {
        return afterData(state, config, state.content().withRows(rows));
    }

    /**
     * One row inserted, with the selected id carried over.
     */
    static DataTableModel inserted(DataTableModel state, DataTableConfig config, int index, Row row) {
        return afterData(state, config, state.content().inserted(index, row));
    }

    /**
     * One row replaced by id, with the selected id carried over.
     */
    static DataTableModel replaced(DataTableModel state, DataTableConfig config, Row row) {
        return afterData(state, config, state.content().replaced(row));
    }

    /**
     * One row removed, with the selection falling on the nearest survivor.
     */
    static DataTableModel removed(DataTableModel state, DataTableConfig config, String rowId) {
        return afterData(state, config, state.content().removed(rowId));
    }

    /**
     * The state over new content, with the selected row carried over by id and the window revealed.
     *
     * <p>The identity rule is here rather than in {@link DataTableContent}, which knows nothing about a
     * selection: a row that is still there keeps its selection wherever it moved to, and a row that
     * is gone hands the selection to the row that took its place.</p>
     */
    static DataTableModel afterData(DataTableModel state, DataTableConfig config, DataTableContent content) {
        return revealed(state.withContent(content), config);
    }

    /**
     * The state with the window moved the least it can to show the selection.
     */
    static DataTableModel revealed(DataTableModel state, DataTableConfig config) {
        int top = revealedTop(state, config);
        int first = effectiveFirstColumn(state, config);
        if (top == state.top() && first == state.firstColumn()) {
            return state;
        }
        return DataTableData.copy(state, state.selection(), top, first, state.height(), state.width());
    }

    /**
     * The intent one choose gesture produces, or null when there is nothing to choose.
     */
    static Output chosen(DataTableModel state) {
        Row row = state.selectedRow().orElse(null);
        Column column = state.selectedColumn().orElse(null);
        if (row == null || column == null) {
            return null;
        }
        if (!state.selection().cellwise()) {
            return new Output.Chosen(row.id(), state.selection().row(), "", -1, StyledText.empty(), state.markedRows());
        }
        int index = state.selection().column();
        return new Output.Chosen(
                row.id(), state.selection().row(), column.key(), index, row.cell(index), state.markedRows());
    }

    /**
     * The first visible body row: the stored offset clamped and revealed.
     */
    static int revealedTop(DataTableModel state, DataTableConfig config) {
        int body = DataTable.bodyHeight(state, config);
        int rows = state.rows().size();
        int max = Math.max(0, rows - body);
        int top = Math.min(Math.max(0, state.top()), max);
        int selected = state.selection().row();
        if (body <= 0 || selected < 0) {
            return top;
        }
        if (selected < top) {
            return selected;
        }
        return selected >= top + body ? Math.min(max, selected - body + 1) : top;
    }

    /**
     * The horizontal offset the window really paints at.
     *
     * <p>It is the stored offset clamped to the content and, in {@link Mode#CELL}, kept inside the
     * range that still shows the selected cell. A table that fits paints at zero.</p>
     */
    static int effectiveFirstColumn(DataTableModel state, DataTableConfig config) {
        DataTableContent content = state.content();
        int width = DataTable.contentWidth(state, config);
        int max = Math.max(0, state.paintedRowWidth() - width);
        int offset = Math.min(Math.max(0, state.firstColumn()), max);
        int column = state.selection().column();
        if (!state.selection().cellwise() || column < 0) {
            return offset;
        }
        int start = state.columnStart(column);
        int visible = state.columnWidth(column) + GAP;
        int highest = Math.min(max, start);
        // A cell wider than the window keeps its left edge on screen: no offset shows all of it, and
        // the reader is better served by the cell's start than by its end.
        int lowest = Math.min(highest, Math.max(0, start + visible - width));
        return Math.max(lowest, Math.min(offset, highest));
    }
}
