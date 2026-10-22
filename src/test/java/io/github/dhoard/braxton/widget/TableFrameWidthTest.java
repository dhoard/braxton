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

import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ScreenRenderer;

/**
 * A frame reserves one cell per edge character, so every row a {@link Table} renders occupies the
 * width of the frame drawn around it, and the rows of an unframed table share one width. That holds
 * while each cell is charged inside the row it is painted in: a row is one grapheme-cluster
 * sequence, and a cluster with no base character of its own - a combining mark, a variation
 * selector, a joiner, a byte-order mark - at the head of a cell joins the cell beside it (that
 * cell's pad space, or the frame's edge character) instead of occupying a cell. Budgeting each cell
 * from its own measurement then leaves the row short of the frame or one column past it, and the
 * right edge lands off the corners of the frame the same call drew.
 */
class TableFrameWidthTest {

    private static final Border FRAME = new Border('+', '+', '+', '+', '-', '|');

    /**
     * Cells whose first cluster has no base of its own, so the cell beside it absorbs that cluster
     * when the row is joined. {@code "x\u0301"} is the control: the accent has its own base inside
     * the cell, so it already occupied a cell of its own.
     */
    private static final List<String> BASELESS = List.of(
            "\u0301",              // combining acute accent
            "\u0301x",             // accent with nothing before it
            "x\u0301",             // accent that has its own base
            "\u200D",              // zero-width joiner
            "\u200D\u0301a\u200D",
            "\uFE0F",              // variation selector 16 (wide presentation)
            "\uFE0Fx",
            "\uFE0E",              // variation selector 15 (text presentation)
            "\uFE0Ex",
            "\u200B",              // zero-width space
            "\uFEFFx",             // byte-order mark
            "\u00ADx");            // soft hyphen

    private static String show(String value) {
        if (value == null) {
            return "<none>";
        }
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F
                    ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }

    /** A two-column framed table whose first cell of the first row is {@code cell}. */
    private static Table table(Border border, int buffer, String cell) {
        Table table = Table.of().buffer(buffer).border(border);
        table.row(StyledText.plain(cell), StyledText.plain("bb"));
        table.row(StyledText.plain("\u65E5"), StyledText.plain("c"));
        return table;
    }

    private static List<String> rows(String rendered) {
        return List.of(rendered.split("\n", -1));
    }

    /** Every row a framed table renders, including its own top and bottom edges, keeps one width. */
    private static void assertFrameKeepsWidth(Border border, int buffer, String cell) {
        List<String> framed = rows(table(border, buffer, cell).render().plainText());
        assertThat(framed).as("cell %s buffer %d", show(cell), buffer).hasSize(4);
        int width = TextMeasure.columns(framed.get(0));
        assertThat(width).as("cell %s buffer %d top edge [%s]",
                show(cell), buffer, show(framed.get(0))).isPositive();
        for (String row : framed) {
            assertThat(TextMeasure.columns(row))
                    .as("cell %s buffer %d row [%s]", show(cell), buffer, show(row))
                    .isEqualTo(width);
        }
    }

    @Test
    void aCellOpeningWithABaselessClusterKeepsEveryFrameRowAtTheFrameWidth() {
        for (String cell : BASELESS) {
            for (int buffer = 0; buffer <= 3; buffer++) {
                assertFrameKeepsWidth(FRAME, buffer, cell);
            }
        }
    }

    /** Framing a table keeps the row it framed and adds one cell at each edge. */
    @Test
    void framingATableWithABaselessCellKeepsItsRowsAndAddsOneCellPerEdge() {
        for (String cell : BASELESS) {
            for (int buffer = 0; buffer <= 3; buffer++) {
                Table table = table(FRAME, buffer, cell);
                List<String> lines = table.renderLines().stream()
                        .map(StyledText::plainText).toList();
                assertThat(lines).as("cell %s buffer %d", show(cell), buffer).hasSize(2);
                assertThat(lines.stream().map(TextMeasure::columns).distinct())
                        .as("cell %s buffer %d rows %s",
                                show(cell), buffer, lines.stream().map(TableFrameWidthTest::show)
                                .toList())
                        .hasSize(1);
                List<String> framed = rows(table.render().plainText());
                String edge = Border.glyph(FRAME.vertical());
                String row = framed.get(1);
                assertThat(row).as("cell %s buffer %d framed row [%s]",
                        show(cell), buffer, show(row)).startsWith(edge).endsWith(edge);
                String inside = row.substring(1, row.length() - 1);
                assertThat(inside).as("cell %s buffer %d framed row [%s]",
                        show(cell), buffer, show(row)).startsWith(lines.get(0));
                assertThat(inside.substring(lines.get(0).length()))
                        .as("the frame pads the row out instead of adding content to it")
                        .isEqualTo(" ".repeat(inside.length() - lines.get(0).length()));
                // Two edge cells, and a row that opens with a selector widens the left one by the
                // column the frame then has to pay for.
                assertThat(TextMeasure.columns(row))
                        .as("cell %s buffer %d framed row [%s] against unframed [%s]",
                                show(cell), buffer, show(row), show(lines.get(0)))
                        .isGreaterThanOrEqualTo(TextMeasure.columns(lines.get(0)) + 2);
            }
        }
    }

    /** Without a frame the rows still share one width. */
    @Test
    void everyRowOfAnUnframedTableSharesOneWidthWithABaselessCell() {
        for (String cell : BASELESS) {
            for (int buffer = 0; buffer <= 3; buffer++) {
                List<String> lines = table(Border.NONE, buffer, cell).renderLines().stream()
                        .map(StyledText::plainText).toList();
                assertThat(lines.stream().map(TextMeasure::columns).distinct())
                        .as("cell %s buffer %d rows %s",
                                show(cell), buffer, lines.stream().map(TableFrameWidthTest::show)
                                .toList())
                        .hasSize(1);
            }
        }
    }

    /**
     * Cells a row was painted with; a wide cluster's second half is marked as a continuation.
     */
    private static List<String> paintedCells(Screen screen, int row) {
        List<String> cells = new ArrayList<>();
        for (int col = 0; col < screen.cols(); col++) {
            cells.add(screen.isContinuation(row, col)
                    ? "<continuation>" : screen.cell(row, col).text());
        }
        return cells;
    }

    /**
     * Checks the frame where it is painted rather than the text {@code render} produced. A row whose
     * cells lost or gained a column still reads back at the frame width when its plain text is
     * measured again, because re-measuring joins the cells the paint path collapsed; only the
     * column the closing edge landed in shows that the right edge is inside the frame.
     */
    private static void assertFrameIsPaintedColumnByColumn(Table table, List<String> rendered) {
        int width = TextMeasure.columns(rendered.get(0));
        Screen screen = new ScreenRenderer().screen(table.view(),
                new Viewport(rendered.size(), width));
        assertThat(screen.rows()).as("rows painted for %s", rendered).isEqualTo(rendered.size());
        Border border = FRAME;
        for (int row = 0; row < rendered.size(); row++) {
            assertThat(paintedCells(screen, row)).as("row %d of %s", row, rendered)
                    .hasSize(width).doesNotContain("");
            assertThat(screen.isContinuation(row, width - 1))
                    .as("row %d of %s ends in a wide cluster's first half", row, rendered)
                    .isFalse();
            char edge = row == 0 ? border.topRight()
                    : row == rendered.size() - 1 ? border.bottomRight() : border.vertical();
            assertThat(screen.cell(row, width - 1).text())
                    .as("right edge of row %d of %s painted as %s", row, rendered,
                            paintedCells(screen, row))
                    .isEqualTo(Border.glyph(edge));
        }
        assertThat(new LayoutEngine().intrinsicHeight(table.view(), width))
                .as("rows measured for %s", rendered)
                .isEqualTo(rendered.size());
    }

    @Test
    void aFrameAroundACellOpeningWithABaselessClusterIsPaintedColumnByColumn() {
        for (String cell : BASELESS) {
            for (int buffer = 0; buffer <= 3; buffer++) {
                Table table = table(FRAME, buffer, cell);
                assertFrameIsPaintedColumnByColumn(table, rows(table.render().plainText()));
            }
        }
    }

    /** The reported content: the frame row lost the column its own top and bottom edges paid. */
    @Test
    void aFrameAroundACellThatOpensWithACombiningMarkCloses() {
        Table table = Table.of().border(Border.SINGLE);
        table.row(StyledText.plain("\u0301x"));
        assertThat(rows(table.render().plainText()))
                .containsExactly("\u250C\u2500\u2500\u2500\u2510",
                        "\u2502\u0301x  \u2502",
                        "\u2514\u2500\u2500\u2500\u2518");
        assertThat(rows(table.render().plainText())).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).as("line [%s]", show(line)).isEqualTo(5));
    }

    /** The second reported shape: a short row in the middle of a multi-row frame. */
    @Test
    void aFrameAroundRowsThatOpenWithBaselessClustersCloses() {
        Table table = Table.of().border(Border.SINGLE);
        table.row(StyledText.plain("\u0301"), StyledText.plain("hello"));
        table.row(StyledText.plain("\u200D"));
        List<String> framed = rows(table.render().plainText());
        assertThat(framed).hasSize(4);
        assertThat(framed).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).as("line [%s]", show(line)).isEqualTo(12));
        assertThat(framed).isEqualTo(List.of(
                "\u250C" + "\u2500".repeat(10) + "\u2510",
                "\u2502\u0301  hello   \u2502",
                "\u2502\u200D          \u2502",
                "\u2514" + "\u2500".repeat(10) + "\u2518"));
        assertFrameIsPaintedColumnByColumn(Table.of().buffer(1).border(FRAME)
                .row(StyledText.plain("\u0301"), StyledText.plain("bb"))
                .row(StyledText.plain("\u65E5"), StyledText.plain("c")),
                List.of("+------+", "|\u0301  bb  |", "|\u65E5 c  |", "+------+"));
    }

    /**
     * A cell that opens with a variation selector widens the cell before it rather than claiming a
     * column of its own, so the row is painted a column more than its column boundaries reserved.
     * That column cannot be taken back, so every row pays it and the grid keeps one width instead
     * of ending ragged.
     */
    @Test
    void aCellThatWidensTheCellBeforeItIsPaidForByEveryRow() {
        Table table = Table.of().buffer(0);
        table.row(StyledText.plain("ab"), StyledText.plain("\uFE0Fx"));
        table.row(StyledText.plain("\uFE0Fx"), StyledText.plain("a"));
        List<String> lines = table.renderLines().stream().map(StyledText::plainText).toList();
        assertThat(lines).isEqualTo(List.of("ab\uFE0Fx", "\uFE0Fx a "));
        assertThat(lines).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).as("line [%s]", show(line)).isEqualTo(4));
        List<String> framed = rows(table.border(FRAME).render().plainText());
        assertThat(framed).isEqualTo(List.of("+-----+", "|ab\uFE0Fx |", "|\uFE0Fx a |", "+-----+"));
        assertThat(framed).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).as("line [%s]", show(line)).isEqualTo(7));
    }

    /** A cell with several lines keeps one width per row of the block it occupies. */
    @Test
    void aMultiLineCellWithABaselessClusterKeepsEveryRowAtOneWidth() {
        for (String cell : List.of("\u0301\nbb", "a\n\uFE0Fx", "\u0301x\n\u65E5")) {
            Table table = Table.of().buffer(1).border(FRAME);
            table.row(StyledText.plain(cell), StyledText.plain("x"));
            table.row(StyledText.plain("ccc"), StyledText.plain("\u0301"));
            List<String> framed = rows(table.render().plainText());
            assertThat(framed).as("cell %s", show(cell)).hasSize(5);
            assertThat(framed.stream().map(TextMeasure::columns).distinct())
                    .as("cell %s rows %s", show(cell), framed.stream().map(TableFrameWidthTest::show)
                            .toList())
                    .hasSize(1);
        }
    }
}
