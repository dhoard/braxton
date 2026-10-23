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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;

/** A table selector: navigation, confirmation, and the drawn grid. */
class TableSelectorTest {

    private static final List<String> HEADER = List.of("id", "name");
    private static final List<List<String>> ROWS = List.of(List.of("1", "alpha"), List.of("2", "be"));

    private static TableSelector.State state() {
        return TableSelector.State.of(HEADER, ROWS);
    }

    @Test
    void navigationClampsAndConfirmNamesTheSelectedCell() {
        TableSelector.State state = state();
        assertThat(TableSelector.update(state, new TableSelector.Action.Up()).state().row()).isZero();
        assertThat(TableSelector.update(state, new TableSelector.Action.Left()).state().column())
                .isZero();
        TableSelector.State moved = TableSelector.update(state, new TableSelector.Action.Down())
                .state();
        moved = TableSelector.update(moved, new TableSelector.Action.Down()).state();
        moved = TableSelector.update(moved, new TableSelector.Action.Right()).state();
        moved = TableSelector.update(moved, new TableSelector.Action.Right()).state();
        assertThat(moved.row()).as("the last row is the floor").isEqualTo(1);
        assertThat(moved.column()).as("the last column is the floor").isEqualTo(1);
        assertThat(moved.selectedValue()).isEqualTo("be");
        assertThat(TableSelector.update(moved, new TableSelector.Action.Confirm()).intent())
                .isEqualTo(new TableSelector.Intent.Chosen(1, 1, "be"));
        assertThat(TableSelector.update(moved, new TableSelector.Action.Cancel()).intent())
                .isEqualTo(new TableSelector.Intent.Cancelled());
    }

    @Test
    void anEmptyTableHasNothingToConfirmAndNoSelectedValue() {
        TableSelector.State empty = TableSelector.State.of(List.of(), List.of());
        assertThat(empty.row()).isEqualTo(-1);
        assertThat(empty.column()).isEqualTo(-1);
        assertThat(empty.selectedValue()).isEmpty();
        assertThat(TableSelector.update(empty, new TableSelector.Action.Confirm()).intent())
                .isEqualTo(new TableSelector.Intent.None());
        // A header without rows still has a column, but no row to name.
        TableSelector.State headerOnly = TableSelector.State.of(HEADER, List.of());
        assertThat(headerOnly.column()).isZero();
        assertThat(TableSelector.update(headerOnly, new TableSelector.Action.Confirm()).intent())
                .isEqualTo(new TableSelector.Intent.None());
    }

    @Test
    void aRowShorterThanTheHeaderHasNoValueForTheMissingCell() {
        TableSelector.State shortRow =
                TableSelector.State.of(HEADER, List.of(List.of("only")));
        assertThat(shortRow.selectedValue()).isEqualTo("only");
        TableSelector.State moved = TableSelector.update(shortRow, new TableSelector.Action.Right()).state();
        assertThat(moved.column()).isEqualTo(1);
        assertThat(moved.selectedValue()).as("a missing cell reads as empty").isEmpty();
    }

    @Test
    void theViewPadsColumnsToOneGridAndMapsItsKeys() {
        List<String> lines = new ScreenRenderer().plainLines(
                TableSelector.view(ElementId.of("table"), state(), true, Style.NONE.withInvert(),
                        Style.NONE, Style.NONE.withBold(), action -> action.getClass().getSimpleName()),
                new Viewport(3, 40));
        assertThat(lines).containsExactly("id  name", "1   alpha", "2   be");
        java.util.Optional<String> down = actionFor(Key.DOWN);
        assertThat(down).contains("Down");
        assertThat(actionFor(Key.ENTER)).contains("Confirm");
        assertThat(actionFor(Key.ESCAPE)).contains("Cancel");
        assertThat(actionFor(Key.of("x"))).isEmpty();
    }

    @Test
    void aCellBreakCannotWidenTheSharedGrid() {
        // TableSelector prepares every cell with the same one-line rule the sibling widgets apply
        // (Selector, Markup, Quote, ImageView, ProgressBar, EditorOptions): CRLF, CR and LF each
        // become one space. Flattening per character instead turned CRLF into two spaces, so a
        // Windows line ending widened the shared column and pushed every later column right.
        List<String> lineBreak = painted(List.of("id", "n"), List.of(List.of("1", "a\r\nb")));
        List<String> bareLf = painted(List.of("id", "n"), List.of(List.of("1", "a\nb")));
        List<String> bareCr = painted(List.of("id", "n"), List.of(List.of("1", "a\rb")));
        assertThat(lineBreak).as("CRLF is one break, so it paints as one space").isEqualTo(bareLf);
        assertThat(bareCr).as("CR is one break, so it paints as one space").isEqualTo(bareLf);
        assertThat(bareLf).containsExactly("id  n", "1   a b");

        // The widened cell also shifted the next column: with the one-space rule every row of the
        // grid puts the third column at the same painted column.
        List<String> threeColumns =
                painted(List.of("id", "n", "x"), List.of(List.of("1", "a\r\nb", "z")));
        List<String> control =
                painted(List.of("id", "n", "x"), List.of(List.of("1", "a\nb", "z")));
        assertThat(threeColumns).isEqualTo(control);
        assertThat(control).containsExactly("id  n    x", "1   a b  z");
    }

    private static List<String> painted(List<String> header, List<List<String>> rows) {
        return new ScreenRenderer()
                .plainLines(TableSelector.view(ElementId.of("table"),
                        TableSelector.State.of(header, rows), true, Style.NONE.withInvert(),
                        Style.NONE, Style.NONE.withBold(), action -> action), new Viewport(2, 40));
    }

    /**
     * Cells whose first cluster has no base of its own, so it joins the pad of the cell before it
     * when the row is painted as one cluster sequence. {@code "x\u0301"} is the control: the accent
     * has its own base, so it already occupied a cell of its own.
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

    /** The grid painted through a viewport wider than it, cell (1, 1) selected. */
    private static RenderedScreen grid(List<String> header, List<List<String>> rows) {
        return new ScreenRenderer().screen(TableSelector.view(ElementId.of("table"),
                new TableSelector.State(header, rows, 1, 1), true, Style.NONE.withInvert(),
                Style.NONE, Style.NONE.withBold(), action -> action.getClass().getSimpleName()),
                new Viewport(rows.size() + 1, 40));
    }

    /** The cells a row was painted with; a wide cluster's second half is a continuation. */
    private static List<String> paintedCells(RenderedScreen screen, int row) {
        List<String> cells = new ArrayList<>();
        for (int col = 0; col < screen.cols(); col++) {
            cells.add(screen.isContinuation(row, col)
                    ? "<continuation>" : show(screen.cell(row, col).text()));
        }
        return cells;
    }

    /**
     * The columns a row really occupies. Re-measuring the row's own plain text hides a column the
     * paint path gave back to the cell before it, so the cells are the oracle.
     */
    private static int paintedColumns(RenderedScreen screen, int row) {
        int columns = 0;
        for (int col = 0; col < screen.cols(); col++) {
            if (screen.isContinuation(row, col)) {
                continue;
            }
            columns += TextMeasure.columns(screen.cell(row, col).text());
        }
        return columns;
    }

    /** The last column a row paints in, so the grid's right edge has to land in one column. */
    private static int lastPaintedColumn(RenderedScreen screen, int row) {
        for (int col = screen.cols() - 1; col >= 0; col--) {
            if (!screen.isContinuation(row, col) && !screen.cell(row, col).isEmpty()) {
                return col;
            }
        }
        return -1;
    }

    /** The column {@code needle} was painted in on {@code row}, or -1 when it is not painted. */
    private static int paintedColumn(RenderedScreen screen, int row, char needle) {
        for (int column = 0; column < screen.cols(); column++) {
            if (!screen.isContinuation(row, column)
                    && screen.cell(row, column).text().indexOf(needle) >= 0) {
                return column;
            }
        }
        return -1;
    }

    /** The columns the inversion of the selected cell covers. */
    private static int invertedColumns(RenderedScreen screen, int row) {
        int columns = 0;
        for (int col = 0; col < screen.cols(); col++) {
            if (!screen.isContinuation(row, col) && screen.cell(row, col).style().invert()) {
                columns += TextMeasure.columns(screen.cell(row, col).text());
            }
        }
        return columns;
    }

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

    /**
     * A row is painted as one grapheme-cluster sequence, so a cell that opens with a cluster that
     * has no base of its own joins the pad of the cell before it: the mark claims no column of its
     * own there and a variation selector widens that pad by a column. Charging every cell the
     * columns it measures on its own made the mark row seven painted columns and the selector row
     * nine, and the inversion drawn for the selected cell missed a column of that cell.
     */
    @Test
    void aCellOpeningWithABaselessClusterKeepsTheGridRectangular() {
        // Column widths are [2, 2], so every row occupies (2 + 2) + (2 + 2) = 8 painted columns.
        RenderedScreen screen = grid(List.of("h1", "h2"),
                List.of(List.of("a", "b"), List.of("p", "\u0301x"), List.of("q", "\uFE0Fz")));
        assertThat(screen.rows()).isEqualTo(4);
        for (int row = 0; row < screen.rows(); row++) {
            List<String> cells = paintedCells(screen, row);
            assertThat(paintedColumns(screen, row))
                    .as("painted columns of row %d, painted as %s", row, cells).isEqualTo(8);
            assertThat(lastPaintedColumn(screen, row))
                    .as("right edge of row %d, painted as %s", row, cells).isEqualTo(7);
        }
        // The mark shares the pad cell of the column before it, so the selected cell's own text and
        // its pad still cover the four columns the ASCII row's cell covers.
        assertThat(invertedColumns(screen, 2))
                .as("inverted columns of the mark cell, painted as %s", paintedCells(screen, 2))
                .isEqualTo(4);
    }

    /** Every baseless cell form keeps the grid rectangular, in either column. */
    @Test
    void everyRowKeepsOneGridWhenACellOpensWithABaselessCluster() {
        for (String cell : BASELESS) {
            for (int column = 0; column < 2; column++) {
                List<List<String>> rows = column == 0
                        ? List.of(List.of(cell, "b"), List.of("bb", "c"))
                        : List.of(List.of("a", cell), List.of("bb", "c"));
                RenderedScreen screen = grid(List.of("h1", "h2"), rows);
                assertThat(screen.rows()).as("cell %s column %d", show(cell), column).isEqualTo(3);
                for (int row = 1; row < screen.rows(); row++) {
                    List<String> cells = paintedCells(screen, row);
                    assertThat(paintedColumns(screen, row))
                            .as("painted columns of row %d for cell %s in column %d, painted as %s",
                                    row, show(cell), column, cells)
                            .isEqualTo(paintedColumns(screen, 0));
                    assertThat(lastPaintedColumn(screen, row))
                            .as("right edge of row %d for cell %s in column %d, painted as %s",
                                    row, show(cell), column, cells)
                            .isEqualTo(lastPaintedColumn(screen, 0));
                }
            }
        }
    }

    /**
     * The column after a baseless cell has to stay where the grid put it, and the selected cell has
     * to be inverted across the columns it occupies. {@link #aCellBreakCannotWidenTheSharedGrid} is
     * the same invariant for a cell break: the mark left the row a column short, so the third
     * column was painted one column left of the other rows and the inversion missed a column.
     */
    @Test
    void aCellOpeningWithABaselessClusterKeepsLaterColumnsAndItsWholeInversion() {
        // Column widths are [2, 5, 1], so every row occupies 4 + 7 + 3 = 14 columns and the third
        // column's text is painted in column 11 on every row.
        for (String cell : List.of("\u0301beta", "beta")) {
            RenderedScreen screen = grid(List.of("id", "name", "x"),
                    List.of(List.of("1", "alpha", "z"), List.of("22", cell, "z")));
            for (int row = 0; row < screen.rows(); row++) {
                assertThat(paintedColumns(screen, row))
                        .as("painted columns of row %d for cell %s, painted as %s",
                                row, show(cell), paintedCells(screen, row))
                        .isEqualTo(14);
            }
            for (int row = 1; row < screen.rows(); row++) {
                assertThat(paintedColumn(screen, row, 'z'))
                        .as("third column of row %d for cell %s, painted as %s",
                                row, show(cell), paintedCells(screen, row))
                        .isEqualTo(11);
            }
            assertThat(invertedColumns(screen, 2))
                    .as("inverted columns of the selected cell %s, painted as %s",
                            show(cell), paintedCells(screen, 2))
                    .isEqualTo(7);
        }
    }

    @Test
    void onlyTheSelectedCellIsInvertedAndOnlyWhileFocused() {
        var screen = new ScreenRenderer().screen(TableSelector.view(ElementId.of("table"), state(),
                true, Style.NONE.withInvert(), Style.NONE, Style.NONE.withBold(),
                action -> action.getClass().getSimpleName()), new Viewport(3, 40));
        assertThat(screen.cell(1, 0).style().invert()).as("selected cell").isTrue();
        assertThat(screen.cell(2, 0).style().invert()).as("other row").isFalse();
        assertThat(screen.cell(1, 4).style().invert()).as("other column").isFalse();
        assertThat(screen.cell(0, 0).style().bold()).as("header").isTrue();

        var unfocused = new ScreenRenderer().screen(TableSelector.view(ElementId.of("table"),
                state(), false, Style.NONE.withInvert(), Style.NONE, Style.NONE.withBold(),
                action -> action.getClass().getSimpleName()), new Viewport(3, 40));
        assertThat(unfocused.cell(1, 0).style().invert()).as("nothing is selected while unfocused")
                .isFalse();
        assertThat(unfocused.plainLine(1)).as("the rows are drawn the same").isEqualTo("1   alpha");
    }

    private static java.util.Optional<String> actionFor(Key key) {
        var view = TableSelector.view(ElementId.of("table"), state(), true, Style.NONE,
                Style.NONE, Style.NONE, action -> action.getClass().getSimpleName());
        return view instanceof io.github.dhoard.braxton.view.View.Focused<String> focused
                ? focused.input().handle(InputEvent.KeyInput.of(key, ""))
                : java.util.Optional.empty();
    }
}
