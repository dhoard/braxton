/*
 * Copyright (c) 2026-present Doug Hoard
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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.AllocationProbe;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;

/**
 * What assembling one grid row costs, in cells.
 *
 * <p>Every cell of a row is charged against the row it is painted in, so the row's text has to be
 * read where a cell can join the cell before it. Keeping the whole painted prefix and re-measuring
 * it per cell made that quadratic in the column count: a 200-row table of ten-character cells cost
 * 1.2 MB of garbage at 8 columns but 186 MB at 256, and the same shape through {@code Markup} and
 * {@code TableSelector}. The per-cell cost must therefore not grow with the column count, whatever
 * the widget - which is what these bounds hold, with room for the fixed cost of building one
 * styled value per cell.</p>
 */
class GridRowCostTest {

    private static final String CELL = "abcdefghij";

    private static Table table(int rows, int columns) {
        Table table = Table.of();
        for (int row = 0; row < rows; row++) {
            StyledText[] cells = new StyledText[columns];
            for (int column = 0; column < columns; column++) {
                cells[column] = StyledText.plain(CELL);
            }
            table.row(cells);
        }
        return table;
    }

    private static String markup(int rows, int columns) {
        StringBuilder xml = new StringBuilder("<table>");
        for (int row = 0; row < rows; row++) {
            xml.append("<tr>");
            for (int column = 0; column < columns; column++) {
                xml.append("<td>").append(CELL).append("</td>");
            }
            xml.append("</tr>");
        }
        return xml.append("</table>").toString();
    }

    private static View<TableSelector.Msg> selector(int rows, int columns) {
        List<String> header = new ArrayList<>(columns);
        for (int column = 0; column < columns; column++) {
            header.add("h" + column);
        }
        List<List<String>> data = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            List<String> cells = new ArrayList<>(columns);
            for (int column = 0; column < columns; column++) {
                cells.add(CELL);
            }
            data.add(cells);
        }
        TableSelector.Model model = new TableSelector.Model(header, data, 0, 0, true);
        return new TableSelector(
                new TableSelector.Config(model, Style.NONE.withInvert(), Style.NONE, Style.NONE))
                .view(model, new ViewContext(new Viewport(rows, 400)));
    }

    /** Allocated bytes of one unit of work, per cell it renders. */
    private static double bytesPerCell(long bytes, int rows, int columns) {
        return (double) bytes / ((double) rows * columns);
    }

    @Test
    void aTableRowCostsTheSamePerCellAtAnyColumnCount() {
        int rows = 20;
        long narrow = AllocationProbe.bytes(() -> table(rows, 8).renderLines());
        long wide = AllocationProbe.bytes(() -> table(rows, 256).renderLines());
        assertThat(bytesPerCell(wide, rows, 256))
                .as("per-cell bytes for 256 columns against %s for 8 (total %s / %s)",
                        bytesPerCell(narrow, rows, 8), wide, narrow)
                .isLessThan(2 * bytesPerCell(narrow, rows, 8));
        // One cell's paint, its pad and its styled value: what a row of any width must cost.
        assertThat(bytesPerCell(wide, rows, 256))
                .as("per-cell bytes for 256 columns (%s in total)", wide)
                .isLessThan(2048.0);
    }

    @Test
    void aMarkupTableRowCostsTheSamePerCellAtAnyColumnCount() {
        int rows = 10;
        String narrow = markup(rows, 8);
        String wide = markup(rows, 256);
        long narrowBytes = AllocationProbe.bytes(() -> Markup.parse(narrow));
        long wideBytes = AllocationProbe.bytes(() -> Markup.parse(wide));
        assertThat(bytesPerCell(wideBytes, rows, 256))
                .as("per-cell bytes for 256 columns against %s for 8 (total %s / %s)",
                        bytesPerCell(narrowBytes, rows, 8), wideBytes, narrowBytes)
                .isLessThan(2 * bytesPerCell(narrowBytes, rows, 8));
        assertThat(bytesPerCell(wideBytes, rows, 256))
                .as("per-cell bytes for 256 columns (%s in total)", wideBytes)
                .isLessThan(2048.0);
    }

    @Test
    void aTableSelectorRowCostsTheSamePerCellAtAnyColumnCount() {
        int rows = 20;
        ScreenRenderer renderer = new ScreenRenderer();
        long narrow = AllocationProbe.bytes(
                () -> renderer.plainLines(selector(rows, 8), new Viewport(rows, 400)));
        long wide = AllocationProbe.bytes(
                () -> renderer.plainLines(selector(rows, 256), new Viewport(rows, 400)));
        assertThat(bytesPerCell(wide, rows, 256))
                .as("per-cell bytes for 256 columns against %s for 8 (total %s / %s)",
                        bytesPerCell(narrow, rows, 8), wide, narrow)
                .isLessThan(2 * bytesPerCell(narrow, rows, 8));
        assertThat(bytesPerCell(wide, rows, 256))
                .as("per-cell bytes for 256 columns (%s in total)", wide)
                .isLessThan(2048.0);
    }
}
