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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The seam where one painted region meets another on the same row.
 *
 * <p>A row is one grapheme-cluster sequence no matter how many regions painted it, and the encoder
 * writes a blank for an untouched cell, so a cluster with no base character of its own at the head of
 * a region continues the character the row already holds there: a frame's edge, a sibling's last cell,
 * or that blank. A variation selector makes the joined cluster two columns where the pieces measure
 * one, and the cell that holds it then occupies a column the layout never gave it. Uncharged, the row
 * paints past its own allocation - a frame's right edge lands off the corners of the frame the same
 * call drew, and at the right edge of the screen its tail wraps into the row below - and the grid no
 * longer describes the stream the encoder wrote.</p>
 *
 * <p>{@link io.github.dhoard.braxton.widget.Box}, {@link io.github.dhoard.braxton.widget.Table} and
 * {@link io.github.dhoard.braxton.widget.Markup} already charge a cell inside the row it is painted
 * in; these tests hold the layout engine's own regions to the same rule.</p>
 */
class PaintRegionSeamTest {

    private static final Border FRAME = new Border('+', '+', '+', '+', '-', '|');

    /**
     * Clusters that claim no column of their own and so continue whatever precedes them. A byte-order
     * mark, a zero-width space and a soft hyphen stay invisible when joined; a variation selector
     * makes the cell it joins wide.
     */
    private static final List<String> JOINING = List.of(
            "\uFE0F", // wide presentation
            "\uFE0E", // text presentation
            "\u0301", // combining acute (given a dotted circle when it leads)
            "\u200B", // zero-width space
            "\uFEFF", // byte-order mark
            "\u00AD", // soft hyphen
            "\u200D"); // zero-width joiner

    /** A grid cell may not paint more columns than the grid gave it. */
    private static void assertGridMatchesTheStream(Screen screen, int cols, String label) {
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < cols; col++) {
                String text = screen.cell(row, col).text();
                if (screen.isContinuation(row, col)) {
                    assertThat(text)
                            .as("%s: the second half of a wide glyph is blank at %d,%d", label, row, col)
                            .isEmpty();
                    assertThat(screen.cell(row, col - 1).text())
                            .as("%s: the wide glyph whose continuation is at %d,%d", label, row, col)
                            .isNotEmpty();
                    assertThat(TextMeasure.columns(screen.cell(row, col - 1).text()))
                            .as("%s: a continuation needs a two-column base before it at %d,%d", label, row, col)
                            .isEqualTo(2);
                    continue;
                }
                if (text.isEmpty()) {
                    continue;
                }
                // The row's own segmentation decides how many columns a cell paints: a zero-width
                // cluster held at a cell's head is a cluster of its own in the row and continues
                // nothing inside that cell.
                int columns = TextMeasure.columns(text);
                assertThat(columns)
                        .as("%s: cell %d,%d paints %d columns", label, row, col, columns)
                        .isBetween(1, 2);
                if (columns == 2 && col + 1 < cols) {
                    assertThat(screen.isContinuation(row, col + 1))
                            .as("%s: the two-column cell at %d,%d must own the next cell too", label, row, col)
                            .isTrue();
                }
            }
        }
    }

    /** Every row the screen paints fits the columns it was laid out in. */
    private static void assertRowsFit(Screen screen, int cols, String label) {
        for (int row = 0; row < screen.rows(); row++) {
            String line = screen.plainLine(row);
            assertThat(TextMeasure.columns(line))
                    .as("%s: row %d [%s] occupies the %d columns offered", label, row, show(line), cols)
                    .isLessThanOrEqualTo(cols);
        }
    }

    /**
     * A cell may not open with a cluster that continues the cell the row holds before it.
     *
     * <p>Where such a run is found, the row paints that cell one column to the right of the grid: the
     * character already in the cell before it is the base the run continues, so the cluster occupies a
     * column the layout assigned to someone else. A cell nothing painted is not absent from the row -
     * the encoder writes a blank for it - so a run at a region's head continues that blank too.</p>
     */
    private static void assertNoJoinCrossesACellBoundary(Screen screen, int cols, String label) {
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 1; col < cols; col++) {
                if (screen.isContinuation(row, col)
                        || screen.isContinuation(row, col - 1)
                        || screen.cell(row, col).text().isEmpty()) {
                    // The second half of a wide glyph holds no cluster of its own, and a run that
                    // joined a wide glyph is already inside the cell that glyph was painted in.
                    continue;
                }
                String before = screen.cell(row, col - 1).text();
                String held = before.isEmpty() ? " " : before;
                String after = screen.cell(row, col).text();
                assertThat(TextMeasure.graphemeCount(held + after))
                        .as(
                                "%s: cell %d,%d [%s] continues the cell the row holds before it",
                                label, row, col, show(after))
                        .isEqualTo(TextMeasure.graphemeCount(held) + TextMeasure.graphemeCount(after));
            }
        }
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }

    private static Screen paint(View<?> view, int rows, int cols) {
        return new LayoutEngine().layout(view, new Rect(0, 0, rows, cols)).screen();
    }

    @Test
    void aChildThatOpensWithABaselessClusterKeepsEveryFrameRowAtTheFrameWidth() {
        for (String joining : JOINING) {
            for (int width = 4; width <= 14; width++) {
                Screen screen = paint(Views.border(FRAME, Views.text(joining + "cd")), 3, width);
                String label = "content " + show(joining + "cd") + " width " + width;
                assertRowsFit(screen, width, label);
                assertGridMatchesTheStream(screen, width, label);
                assertThat(screen.plainLines())
                        .as("%s: the frame still has three rows", label)
                        .hasSize(3);
                assertThat(screen.cell(1, width - 1).text())
                        .as("%s: the right edge stays in the last cell of its row", label)
                        .isEqualTo("|");
                assertThat(screen.plainLines().get(1))
                        .as("%s: the framed row keeps its edge characters", label)
                        .startsWith("|")
                        .endsWith("|");
            }
        }
    }

    @Test
    void aTitleThatOpensWithABaselessClusterKeepsTheTopEdgeAtTheFrameWidth() {
        for (String joining : JOINING) {
            for (int width = 6; width <= 14; width++) {
                Screen screen = paint(Views.border(FRAME, StyledText.plain(joining + "t"), Views.text("ab")), 3, width);
                String label = "title " + show(joining + "t") + " width " + width;
                assertRowsFit(screen, width, label);
                assertGridMatchesTheStream(screen, width, label);
                assertThat(screen.cell(0, width - 1).text())
                        .as("%s: the top-right corner stays in the last cell of the edge", label)
                        .isEqualTo("+");
            }
        }
    }

    @Test
    void aSiblingRegionThatOpensWithABaselessClusterKeepsTheRowAtTheViewportWidth() {
        for (String joining : JOINING) {
            // Two regions meet at the right edge of the screen: an uncharged join paints the tail of
            // the row past the last column, where it wraps into the row below.
            Screen screen = paint(Views.row(Views.text("ab"), Views.text(joining + "cd")), 1, 4);
            String label = "sibling " + show(joining + "cd");
            assertRowsFit(screen, 4, label);
            assertGridMatchesTheStream(screen, 4, label);
        }
    }

    @Test
    void aRegionThatOpensAfterAnUnpaintedCellChargesTheJoinToThatCell() {
        // The encoder writes a blank for an untouched cell, so the invisible run at the head of the
        // region continues that blank exactly as it continues a painted character.
        Screen screen = paint(Views.padding(new Insets(0, 1, 0, 1), Views.text("\uFE0Fz")), 1, 4);
        assertRowsFit(screen, 4, "padding after a blank");
        assertGridMatchesTheStream(screen, 4, "padding after a blank");
        assertThat(screen.cell(0, 0).text())
                .as("the blank the encoder writes absorbs the selector")
                .isEqualTo(" \uFE0F");
        assertThat(screen.isContinuation(0, 1))
                .as("the widened blank occupies the region's first cell")
                .isTrue();
        assertThat(screen.cell(0, 2).text())
                .as("the region's own base starts a column later")
                .isEqualTo("z");
    }

    @Test
    void aRegionOpeningWithAColumnClaimingClusterIsNotChargedToItsNeighbour() {
        // Guards against over-absorbing: a base, and a mark that measures a column of its own and is
        // drawn behind the dotted circle, keep their own cells, so nothing shifts.
        Screen plain = paint(Views.padding(new Insets(0, 1, 0, 1), Views.text("z")), 1, 4);
        assertThat(plain.cell(0, 0).text())
                .as("an untouched cell stays untouched")
                .isEmpty();
        assertThat(plain.cell(0, 1).text())
                .as("the region's base keeps its own cell")
                .isEqualTo("z");
        assertThat(plain.isContinuation(0, 1)).isFalse();

        Screen circle = paint(Views.padding(new Insets(0, 1, 0, 1), Views.text("\u0301z")), 1, 4);
        assertRowsFit(circle, 4, "a leading mark");
        assertGridMatchesTheStream(circle, 4, "a leading mark");
        assertThat(circle.cell(0, 1).text())
                .as("a leading mark keeps the dotted circle the measurement charged it")
                .isEqualTo(TextMeasure.DOTTED_CIRCLE + "\u0301");
    }

    @Test
    void aLineHoldingNothingButInvisibleClustersStillJoinsTheRow() {
        // A region whose whole line is format characters paints nothing of its own, but the row still
        // writes those characters after the cell before it, and that cell is what they continue.
        Screen screen = paint(Views.padding(new Insets(0, 1, 0, 1), Views.text("\uFE0F")), 1, 4);
        assertRowsFit(screen, 4, "a line of invisible clusters");
        assertGridMatchesTheStream(screen, 4, "a line of invisible clusters");
        assertThat(screen.cell(0, 0).text())
                .as("the invisible run joins the blank the encoder writes before the region")
                .isEqualTo(" \uFE0F");
        assertThat(screen.isContinuation(0, 1))
                .as("the widened blank owns the column the joined cluster paints")
                .isTrue();
        assertThat(screen.cell(0, 2).text())
                .as("the region paints no glyph of its own")
                .isEmpty();
        assertThat(TextMeasure.columns(screen.plainLine(0)))
                .as("the row is the two columns the joined cluster occupies")
                .isEqualTo(2);
    }

    @Test
    void aRegionThatOpensTheRowKeepsItsInvisibleRunWithItsFirstCell() {
        // Nothing precedes a region that opens the row, so there is no cell in the grid for the run to
        // continue. The run stays with the cell it was painted into: the row segments the selector as a
        // cluster of its own there, claiming no column, and the base keeps its cell.
        Screen screen = paint(Views.text("\uFE0Fx"), 1, 3);
        assertRowsFit(screen, 3, "region at the head of the row");
        assertGridMatchesTheStream(screen, 3, "region at the head of the row");
        assertThat(screen.cell(0, 0).text())
                .as("the selector is not dropped, and not charged to a cell that does not exist")
                .isEqualTo("\uFE0Fx");
        assertThat(screen.isContinuation(0, 1))
                .as("the base still occupies only its own column")
                .isFalse();
    }

    @Test
    void aRegionThatRunsOutOfColumnsStopsAtTheCellItCannotFit() {
        // Paying for the join costs the region a column at its tail: the cell that no longer fits is not
        // painted half, the way a row that runs out of room always stops.
        Screen screen = paint(Views.row(Views.text("ab"), Views.text("\uFE0Fcde")), 1, 4);
        assertRowsFit(screen, 4, "region that loses its last column");
        assertGridMatchesTheStream(screen, 4, "region that loses its last column");
        assertThat(screen.cell(0, 1).text())
                .as("the sibling's last cell takes the selector")
                .isEqualTo("b\uFE0F");
        assertThat(screen.cell(0, 3).text())
                .as("the region paints up to the column the join leaves it")
                .isEqualTo("c");
        assertThat(TextMeasure.columns(screen.plainLine(0)))
                .as("the row occupies the four columns it was given")
                .isEqualTo(4);
    }

    @Test
    void aCellThatHoldsSeveralClustersAbsorbsARunThatContinuesItsLastOne() {
        // A dotted circle drawn behind a bare mark, with a zero-width space in front of it. The
        // selector at the next region's head continues the circle, not the cell as a whole, and the
        // row measures the joined cluster as two columns. Measured against every cluster of the cell
        // the join was declined, the column was charged to nobody, and the rest of the row painted one
        // column right of the grid - out of the frame at the narrow end.
        View<?> joined = Views.row(Views.text("\u200B\u0301"), Views.text("\uFE0Fx"), Views.text("alpha"));
        for (int cols = 8; cols <= 16; cols++) {
            Screen screen = paint(joined, 1, cols);
            String label = "circle then selector at " + cols;
            assertRowsFit(screen, cols, label);
            assertGridMatchesTheStream(screen, cols, label);
            assertNoJoinCrossesACellBoundary(screen, cols, label);
        }
        Screen framed = paint(Views.border(FRAME, joined), 3, 12);
        assertRowsFit(framed, 12, "framed circle then selector");
        assertNoJoinCrossesACellBoundary(framed, 12, "framed circle then selector");
        assertThat(framed.cell(1, 1).text())
                .as("the circle absorbs the selector that presents it wide")
                .isEqualTo("\u200B" + TextMeasure.DOTTED_CIRCLE + "\u0301\uFE0F");
        assertThat(framed.isContinuation(1, 2))
                .as("the widened cell owns the column the joined cluster paints")
                .isTrue();
    }

    @Test
    void regionsThatEachOpenWithASelectorStillChargeEveryJoin() {
        // Three regions that all begin with a cluster that claims no column of its own: each join has
        // to be charged to whatever the row holds before it, including a cell that itself opens with
        // a selector.
        View<?> view = Views.row(Views.text("\uFE0Fx"), Views.text("\uFE0Fx"), Views.text("\u0301z"));
        for (int cols = 4; cols <= 10; cols++) {
            Screen screen = paint(view, 1, cols);
            String label = "three selector regions at " + cols;
            assertRowsFit(screen, cols, label);
            assertGridMatchesTheStream(screen, cols, label);
            assertNoJoinCrossesACellBoundary(screen, cols, label);
        }
    }

    @Test
    void aFrameAroundAFrameJoinsOnlyTheCellTheRowActuallyHoldsThere() {
        // Nested regions: the inner frame's left edge is the cell the inner child's run continues, and
        // neither frame may overrun the width the outer one was laid out at.
        View<?> view = Views.border(FRAME, Views.padding(1, Views.border(FRAME, Views.text("\uFE0Fx"))));
        for (int width = 8; width <= 16; width++) {
            Screen screen = paint(view, 7, width);
            String label = "nested frame width " + width;
            assertRowsFit(screen, width, label);
            assertGridMatchesTheStream(screen, width, label);
            // The inner child's content lands one column right of its cell when the inner frame's edge
            // absorbs the selector and nobody pays for it, which no row total can see.
            assertNoJoinCrossesACellBoundary(screen, width, label);
        }
        assertThat(paint(view, 7, 10).cell(3, 2).text())
                .as("the inner frame's edge is the cell the inner child's selector continues")
                .isEqualTo("|\uFE0F");
        assertThat(paint(view, 7, 10).isContinuation(3, 3))
                .as("the widened edge owns the inner child's first cell")
                .isTrue();
        assertThat(paint(view, 7, 10).cell(3, 4).text())
                .as("the inner child's base starts where the widened edge leaves it, not a column later")
                .isEqualTo("x");
    }

    @Test
    void noCellOpensWithAClusterThatContinuesTheCellBeforeIt() {
        // The property the fix exists for, over the shapes that put a region next to something the row
        // has already painted.
        List<String> joining = List.of("", "\uFE0F", "\uFE0E", "\u0301", "\u200B", "\uFEFF", "\u00AD", "\u200D");
        List<String> bases = List.of("a", "\u4E2D", "\uD83D\uDE00", "e\u0301", " ");
        for (String lead : joining) {
            for (String base : bases) {
                String content = lead + base;
                List<View<?>> views = List.of(
                        Views.border(FRAME, Views.text(content)),
                        Views.border(FRAME, StyledText.plain(content), Views.text("inner")),
                        Views.row(Views.text("ab"), Views.text(content)),
                        Views.padding(new Insets(0, 1, 0, 1), Views.text(content)),
                        Views.border(FRAME, Views.padding(1, Views.border(FRAME, Views.text(content)))));
                for (View<?> view : views) {
                    for (int rows = 1; rows <= 6; rows++) {
                        for (int cols = 1; cols <= 12; cols++) {
                            String label = "content " + show(content) + " " + rows + "x" + cols;
                            Screen screen = paint(view, rows, cols);
                            assertRowsFit(screen, cols, label);
                            assertGridMatchesTheStream(screen, cols, label);
                            assertNoJoinCrossesACellBoundary(screen, cols, label);
                        }
                    }
                }
            }
        }
    }
}
