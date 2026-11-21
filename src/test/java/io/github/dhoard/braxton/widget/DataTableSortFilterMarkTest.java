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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.testing.ElementScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Sorting, filtering, marks, column widths and the scrollbar of one table.
 */
class DataTableSortFilterMarkTest {

    private static List<DataTable.Column> columns() {
        return List.of(
                DataTable.Column.of("id", "id", 4),
                DataTable.Column.of("name", "name"),
                DataTable.Column.of("count", "count", 5).withAlignment(DataTable.Alignment.RIGHT));
    }

    private static DataTableContent content(String[]... rows) {
        List<DataTable.Row> values = new ArrayList<>(rows.length);
        for (String[] row : rows) {
            values.add(DataTable.Row.of(row[0], row));
        }
        return DataTableContent.of(columns(), values);
    }

    private static DataTableContent three() {
        return content(new String[] {"A-1", "charlie", "3"}, new String[] {"B-2", "alpha", "10"}, new String[] {
            "C-3", "bravo", "2"
        });
    }

    private static DataTableConfig config(DataTableContent content, int height, int width) {
        return DataTableConfig.defaults()
                .withContent(content)
                .withHeight(height)
                .withWidth(width);
    }

    private static DataTable table(DataTableConfig config) {
        return new DataTable(config);
    }

    private static DataTableModel model(DataTableConfig config) {
        return table(config).init(new InitContext(new Viewport(0, 0), null)).model();
    }

    private static DataTableModel apply(DataTable table, DataTableModel state, DataTableMsg message) {
        return table.update(state, message).model();
    }

    private static List<String> names(DataTableModel state) {
        return state.rows().stream().map(row -> row.cell(1).plainText()).toList();
    }

    @Test
    void sortingCyclesAscendingDescendingAndUnsorted() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = model(config);

        DataTableModel ascending = apply(table, state, new DataTableMsg.ToggleSort("name"));
        assertThat(names(ascending)).containsExactly("alpha", "bravo", "charlie");
        assertThat(ascending.sort()).isEqualTo(new DataTable.Sort("name", false));
        assertThat(ascending.selectedRow().orElseThrow().id()).isEqualTo("A-1");
        assertThat(DataTable.lines(ascending, config).get(0).text().plainText()).contains("name \u25B2");

        DataTableModel descending = apply(table, ascending, new DataTableMsg.ToggleSort("name"));
        assertThat(names(descending)).containsExactly("charlie", "bravo", "alpha");
        assertThat(descending.sort()).isEqualTo(new DataTable.Sort("name", true));
        assertThat(DataTable.lines(descending, config).get(0).text().plainText())
                .contains("name \u25BC");

        DataTableModel unsorted = apply(table, descending, new DataTableMsg.ToggleSort("name"));
        assertThat(names(unsorted)).containsExactly("charlie", "alpha", "bravo");
        assertThat(unsorted.sort().active()).isFalse();
    }

    @Test
    void theSelectedColumnSortsAndEveryRowKeepsItsIdentity() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = model(config);

        DataTableModel sorted = apply(table, state, new DataTableMsg.SortSelected());
        assertThat(sorted.sort()).isEqualTo(new DataTable.Sort("id", false));
        assertThat(sorted.selectedRow().orElseThrow().id()).isEqualTo("A-1");
        assertThat(names(sorted)).containsExactly("charlie", "alpha", "bravo");
    }

    @Test
    void filteringHidesRowsAndKeepsTheSelectedRowSelected() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = apply(table, model(config), new DataTableMsg.Select("B-2"));

        DataTableModel filtered = apply(table, state, new DataTableMsg.SetFilter("brav"));
        assertThat(names(filtered)).containsExactly("bravo");
        assertThat(filtered.selectedRow().orElseThrow().id()).isEqualTo("C-3");
        assertThat(filtered.filter()).isEqualTo("brav");

        DataTableModel restored = apply(table, filtered, new DataTableMsg.ClearFilter());
        assertThat(names(restored)).hasSize(3);
        assertThat(restored.selectedRow().orElseThrow().id()).isEqualTo("C-3");
    }

    @Test
    void everyFilterTermMustMatchSomeCell() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = model(config);

        assertThat(names(apply(table, state, new DataTableMsg.SetFilter("charlie 10"))))
                .isEmpty();
        assertThat(names(apply(table, state, new DataTableMsg.SetFilter("bravo 2"))))
                .containsExactly("bravo");
        assertThat(names(apply(table, state, new DataTableMsg.SetFilter("CHARLIE"))))
                .containsExactly("charlie");
    }

    @Test
    void spaceMarksARowAndStepsDownAndAChoiceReportsEveryMark() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = model(config);

        DataTableModel first = apply(table, state, new DataTableMsg.ToggleMark());
        assertThat(first.marked()).containsExactly("A-1");
        assertThat(first.selection().row()).isEqualTo(1);

        DataTableModel second = apply(table, first, new DataTableMsg.ToggleMark());
        assertThat(second.marked()).containsExactly("A-1", "B-2");

        DataTable.Output.Chosen chosen = (DataTable.Output.Chosen)
                table.update(second, new DataTableMsg.Choose()).outputs().get(0);
        assertThat(chosen.marked()).extracting(DataTable.Row::id).containsExactly("A-1", "B-2");

        DataTableModel all = apply(table, second, new DataTableMsg.MarkAll());
        assertThat(all.marked()).containsExactly("A-1", "B-2", "C-3");
        assertThat(apply(table, all, new DataTableMsg.ClearMarks()).marked()).isEmpty();
    }

    @Test
    void aMarkedRowIsPaintedThroughTheTheme() {
        Style marked = Style.NONE.withBold();
        DataTableConfig config = config(three(), 8, 40).withTheme(new DataTable.Theme() {
            @Override
            public Style marked(DataTable.Row row, int index) {
                return marked;
            }
        });
        DataTable table = new DataTable(config);
        DataTableModel state = apply(table, model(config), new DataTableMsg.ToggleMark());

        DataTable.Line line = DataTable.lines(state, config).get(1);
        assertThat(line.text().spans())
                .anySatisfy(span -> assertThat(span.style().bold()).isTrue());
        assertThat(DataTable.lines(state, config).get(2).text().spans())
                .allSatisfy(span -> assertThat(span.style().bold()).isFalse());
    }

    @Test
    void aColumnWidthIsTheReadersAndIsClampedAtOneCell() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = model(config);

        assertThat(state.columnWidth(1)).isEqualTo(7);
        assertThat(state.paintedRowWidth()).isEqualTo(4 + 1 + 7 + 1 + 5 + 1);

        DataTableModel wider = apply(table, state, new DataTableMsg.ResizeColumn("name", 12));
        assertThat(wider.columnWidth(1)).isEqualTo(12);
        assertThat(wider.columnStart(2)).isEqualTo(4 + 1 + 12 + 1);
        assertThat(DataTable.rowWidth(wider)).isEqualTo(4 + 1 + 12 + 1 + 5 + 1);

        DataTableModel adjusted = apply(table, wider, new DataTableMsg.AdjustColumn("name", -20));
        assertThat(adjusted.columnWidth(1)).isEqualTo(1);

        DataTableModel unknown = apply(table, wider, new DataTableMsg.ResizeColumn("nope", 9));
        assertThat(unknown.widths()).containsOnlyKeys("name");
    }

    @Test
    void theScrollbarCoversTheRowsTheWindowShows() {
        String[][] rows = new String[20][];
        for (int index = 0; index < rows.length; index++) {
            rows[index] = new String[] {"A-" + index, "row-" + index, Integer.toString(index)};
        }
        DataTableConfig config = config(content(rows), 6, 20);
        DataTableConfig withBar = config.withScrollbar(DataTable.Bar.of(Style.NONE, Style.NONE));
        DataTable table = new DataTable(withBar);
        DataTableModel state =
                table.init(new InitContext(new Viewport(0, 0), null)).model();

        assertThat(DataTable.thumb(state, config)).isEqualTo(new DataTable.Thumb(0, 0));
        assertThat(DataTable.thumb(state, withBar)).isEqualTo(new DataTable.Thumb(0, 1));
        assertThat(DataTable.contentWidth(state, withBar)).isEqualTo(19);

        List<String> lines = ElementScreen.render(table.view(state, new ViewContext(new Viewport(6, 20))), 6, 20)
                .lines();
        assertThat(lines.get(0)).startsWith("id ");
        assertThat(lines.get(0)).doesNotContain(DataTable.Bar.THUMB);
        assertThat(lines.get(1)).endsWith(DataTable.Bar.THUMB);
        assertThat(lines.get(3)).endsWith(DataTable.Bar.TRACK);
    }

    @Test
    void aHeaderPressSortsByThatColumn() {
        DataTableConfig config = config(three(), 8, 40);
        DataTable table = table(config);
        DataTableModel state = model(config);

        assertThat(press(table, state, 0, 6)).contains(new DataTableMsg.ToggleSort("name"));
    }

    /**
     * The message a press produces, offered to the table the way the runtime offers it.
     */
    private static Optional<DataTableMsg> press(DataTable table, DataTableModel state, int row, int col) {
        List<DataTableMsg> produced = new ArrayList<>(1);
        table.onPointer(
                PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col),
                new Rect(0, 0, 8, 40),
                state,
                produced::add);
        return produced.stream().findFirst();
    }
}
