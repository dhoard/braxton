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

package io.github.dhoard.braxton.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A painted screen that has to stay the picture it was built from.
 *
 * <p>{@link RenderedScreen} is what a test, a snapshot or an accessibility client sees: rows of cells
 * plus the regions that claim them. Its text view is built by skipping the continuation half of a wide
 * glyph, painting an empty cell as a blank, and trimming trailing blanks - small rules with a long
 * reach, because everything that compares two screens reads them through that view.</p>
 *
 * <p>The oracle re-derives the plain text of every row from the cells it was given, independently of
 * the record's own walk. Alongside it: a cell's occupancy has to agree with its continuation flag, a
 * snapshot whose stated size disagrees with its cells has to be refused rather than half-believed, the
 * cells handed in have to be copied so a caller editing its own lists cannot repaint a screen after the
 * fact, and reading outside the grid has to fail as an index failure rather than answer with a
 * neighbour.</p>
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class RenderedScreenFuzzTest {

    private static final String[] TEXTS = {
        "",
        " ",
        "  ",
        "a",
        "abc",
        "\u4E2D",
        "\uD83D\uDE00",
        "\u0301",
        "\u200B",
        "\u00E9\u0301",
        " x ",
        "x  ",
        "\t",
        "\u00A0",
    };

    @Test
    void theTextViewOfEveryRowIsWhatItsCellsSay() {
        Random random = new Random(2_236_067L);
        for (int round = 0; round < 4_000; round++) {
            int rows = random.nextInt(6);
            int cols = random.nextInt(6);
            List<List<RenderedScreen.Cell>> cells = cells(random, rows, cols);
            RenderedScreen screen = new RenderedScreen(rows, cols, cells, List.of());
            String where = "screen %dx%d".formatted(rows, cols);

            assertThat(screen.rows()).as("%s: rows()", where).isEqualTo(rows);
            assertThat(screen.cols()).as("%s: cols()", where).isEqualTo(cols);
            assertThat(screen.isEmpty())
                    .as("%s: isEmpty() on a %dx%d screen", where, rows, cols)
                    .isEqualTo(rows == 0 || cols == 0);
            assertThat(screen.plainLines())
                    .as("%s: one plain line per row", where)
                    .hasSize(rows);
            for (int row = 0; row < rows; row++) {
                assertThat(screen.plainLine(row))
                        .as(
                                "%s: row %d reads as %s, its cells say %s",
                                where, row, show(screen.plainLine(row)), show(expected(cells.get(row))))
                        .isEqualTo(expected(cells.get(row)));
                for (int col = 0; col < cols; col++) {
                    assertThat(screen.cell(row, col))
                            .as("%s: cell (%d,%d) is not the cell that was painted there", where, row, col)
                            .isEqualTo(cells.get(row).get(col));
                    assertThat(screen.isContinuation(row, col))
                            .as("%s: (%d,%d) continuation flag", where, row, col)
                            .isEqualTo(cells.get(row).get(col).continuation());
                }
            }
            assertThat(screen.plainText())
                    .as("%s: plainText() is the rows joined", where)
                    .isEqualTo(String.join("\n", screen.plainLines()));
        }
    }

    /** A cell either occupies columns or continues one; the two answers cannot disagree. */
    @Test
    void aCellCannotBothOccupyAColumnAndContinueOne() {
        Random random = new Random(3_322_777L);
        for (int round = 0; round < 3_000; round++) {
            String text = TEXTS[random.nextInt(TEXTS.length)];
            int columns = switch (random.nextInt(6)) {
                case 0 -> -1;
                case 1 -> -7;
                case 2 -> 3;
                case 3 -> 99;
                default -> random.nextInt(3);
            };
            boolean continuation = random.nextBoolean();
            boolean allowed = columns >= 0 && columns <= 2 && continuation == (columns == 0);
            String where = "text %s columns %d continuation %s".formatted(show(text), columns, continuation);
            if (allowed) {
                RenderedScreen.Cell cell = new RenderedScreen.Cell(text, Style.NONE, null, columns, continuation);
                assertThat(cell.text()).as("%s: text", where).isEqualTo(text);
                assertThat(cell.columns()).as("%s: columns", where).isEqualTo(columns);
                assertThat(cell.continuation()).as("%s: continuation", where).isEqualTo(continuation);
                assertThat(cell.isEmpty())
                        .as("%s: isEmpty() on a cell with text %s and style %s", where, show(text), Style.NONE)
                        .isEqualTo(text.isEmpty() && Style.NONE.isPlain());
            } else {
                assertThatThrownBy(() -> new RenderedScreen.Cell(text, Style.NONE, null, columns, continuation))
                        .as("%s", where)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new RenderedScreen.Cell(null, Style.NONE, null, 1, false))
                .as("a cell with no text")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RenderedScreen.Cell("a", null, null, 1, false))
                .as("a cell with no style")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RenderedScreen.Region(null, new Rect(0, 0, 1, 1)))
                .as("a region with no owner")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RenderedScreen.Region(ElementId.of("a"), null))
                .as("a region with no bounds")
                .isInstanceOf(NullPointerException.class);
    }

    /** A snapshot that does not match its own cells is refused, not partially believed. */
    @Test
    void aSnapshotWhoseSizeDisagreesWithItsCellsIsRefused() {
        Random random = new Random(5_482_171L);
        for (int round = 0; round < 3_000; round++) {
            int rows = random.nextInt(5);
            int cols = random.nextInt(5);
            List<List<RenderedScreen.Cell>> built = cells(random, rows, cols);
            List<List<RenderedScreen.Cell>> cells = built;
            int claimedRows = rows;
            int claimedCols = cols;
            switch (random.nextInt(4)) {
                case 0 -> claimedRows = random.nextInt(9);
                case 1 -> claimedCols = random.nextInt(9);
                case 2 -> {
                    if (!built.isEmpty()) {
                        cells = new ArrayList<>(built);
                        cells.remove(random.nextInt(cells.size()));
                    }
                }
                case 3 -> {
                    if (!built.isEmpty() && cols > 0) {
                        cells = new ArrayList<>(built);
                        int at = random.nextInt(cells.size());
                        cells.set(
                                at,
                                new ArrayList<>(
                                        cells.get(at).subList(0, cells.get(at).size() - 1)));
                    }
                }
            }
            final List<List<RenderedScreen.Cell>> painted = cells;
            final int rowsClaimed = claimedRows;
            final int colsClaimed = claimedCols;
            // The claim matches when the stated size is the size of what was painted, row for row.
            boolean exact = rowsClaimed >= 0
                    && colsClaimed >= 0
                    && painted.size() == rowsClaimed
                    && painted.stream().allMatch(row -> row.size() == colsClaimed);
            String where = "claim %dx%d over %d rows of %s"
                    .formatted(
                            rowsClaimed,
                            colsClaimed,
                            painted.size(),
                            painted.stream().map(List::size).toList());
            if (exact) {
                RenderedScreen screen = new RenderedScreen(rowsClaimed, colsClaimed, painted, List.of());
                assertThat(screen.plainLines()).as("%s", where).hasSize(rowsClaimed);
            } else {
                assertThatThrownBy(() -> new RenderedScreen(rowsClaimed, colsClaimed, painted, List.of()))
                        .as("%s", where)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new RenderedScreen(-1, 3, List.of(), List.of()))
                .as("a screen with negative rows")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenderedScreen(3, -1, List.of(), List.of()))
                .as("a screen with negative columns")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Reading outside the grid is an index failure, and the caller's lists are not the screen's. */
    @Test
    void theGridIsCopiedOnTheWayInAndReadingOutsideItFailsAsAnIndex() {
        Random random = new Random(7_211_102L);
        for (int round = 0; round < 1_500; round++) {
            int rows = 1 + random.nextInt(4);
            int cols = 1 + random.nextInt(4);
            List<List<RenderedScreen.Cell>> cells = cells(random, rows, cols, true);
            String before = new RenderedScreen(rows, cols, deepCopy(cells), List.of()).plainText();
            RenderedScreen screen = new RenderedScreen(rows, cols, cells, List.of());

            // The caller still holds the lists it built the screen from; editing them must not repaint
            // a screen that has already been taken.
            for (int row = 0; row < rows; row++) {
                cells.get(row).set(random.nextInt(cols), new RenderedScreen.Cell("?", Style.NONE, null, 1, false));
            }
            cells.add(List.of(new RenderedScreen.Cell("!", Style.NONE, null, 1, false)));
            assertThat(screen.plainText())
                    .as("rows %d cols %d: the screen follows the caller's lists", rows, cols)
                    .isEqualTo(before);

            for (int[] outside : new int[][] {
                {-1, 0},
                {0, -1},
                {rows, 0},
                {0, cols},
                {rows + 3, cols + 5},
                {Integer.MIN_VALUE, Integer.MAX_VALUE}
            }) {
                int row = outside[0];
                int col = outside[1];
                assertThatThrownBy(() -> screen.cell(row, col))
                        .as("cell (%d,%d) on a %dx%d screen", row, col, rows, cols)
                        .isInstanceOf(IndexOutOfBoundsException.class);
                assertThatThrownBy(() -> screen.isContinuation(row, col))
                        .as("isContinuation(%d,%d) on a %dx%d screen", row, col, rows, cols)
                        .isInstanceOf(IndexOutOfBoundsException.class);
                if (row < 0 || row >= rows) {
                    assertThatThrownBy(() -> screen.plainLine(row))
                            .as("plainLine(%d) on a %dx%d screen", row, rows, cols)
                            .isInstanceOf(IndexOutOfBoundsException.class);
                }
            }
        }
    }

    /** What the row should read as: skip the continuation halves, blank the empties, trim trailing blanks. */
    private static String expected(List<RenderedScreen.Cell> row) {
        StringBuilder out = new StringBuilder();
        for (RenderedScreen.Cell cell : row) {
            if (cell.continuation()) {
                continue;
            }
            out.append(cell.text().isEmpty() ? " " : cell.text());
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }

    private static List<List<RenderedScreen.Cell>> cells(Random random, int rows, int cols) {
        return cells(random, rows, cols, false);
    }

    private static List<List<RenderedScreen.Cell>> cells(Random random, int rows, int cols, boolean arrays) {
        List<List<RenderedScreen.Cell>> grid = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            List<RenderedScreen.Cell> line = new ArrayList<>();
            for (int col = 0; col < cols; col++) {
                String text = TEXTS[random.nextInt(TEXTS.length)];
                int columns = random.nextInt(3);
                String link = random.nextInt(6) == 0 ? "https://example.test" : null;
                line.add(new RenderedScreen.Cell(
                        text, random.nextBoolean() ? Style.NONE.withBold() : Style.NONE, link, columns, columns == 0));
            }
            grid.add(arrays ? line : List.copyOf(line));
        }
        return arrays ? grid : List.copyOf(grid);
    }

    private static List<List<RenderedScreen.Cell>> deepCopy(List<List<RenderedScreen.Cell>> cells) {
        List<List<RenderedScreen.Cell>> copy = new ArrayList<>();
        for (List<RenderedScreen.Cell> row : cells) {
            copy.add(new ArrayList<>(row));
        }
        return copy;
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < Math.min(value.length(), 24); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append(value.length() > 24 ? "...\"" : "\"").toString();
    }
}
