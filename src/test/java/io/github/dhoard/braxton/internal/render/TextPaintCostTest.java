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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.AllocationProbe;
import io.github.dhoard.braxton.view.Views;

/**
 * The cost of the wrap and paint path for ordinary text.
 *
 * <p>Every cluster asks whether it continues the cell before it, and every cell that is whole is
 * assembled into the text its {@link Cell} holds. Answering that question by rebuilding the growing
 * cell and segmenting the copy - and assembling a builder and a copy for a cell nothing ever
 * joined - costs several objects per column, in the path every dirty presentation pays.</p>
 *
 * <p>The bounds below pin the cost per column. The parity tests pin the semantics the cheaper path
 * has to reproduce, against the break iterator {@link TextMeasure} segments with.</p>
 */
class TextPaintCostTest {

    private static final int COLUMNS = 2048;

    private final LayoutEngine engine = new LayoutEngine();

    /** Ordinary ASCII prose: the alphabet a terminal frame is mostly made of. */
    private static String prose(int columns) {
        String unit = "the quick brown fox jumps over the lazy dog 0123456789 ";
        StringBuilder text = new StringBuilder();
        while (text.length() < columns) {
            text.append(unit);
        }
        text.setLength(columns);
        if (text.charAt(columns - 1) == ' ') {
            text.setCharAt(columns - 1, 'z');
        }
        return text.toString();
    }

    /** Every painted cell of a screen, so two layouts can be compared column by column. */
    private static String paintedCells(Screen screen) {
        StringBuilder cells = new StringBuilder();
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                cells.append(screen.isContinuation(row, col) ? ">" : screen.cell(row, col).text())
                        .append('|');
            }
            cells.append('\n');
        }
        return cells.toString();
    }

    @Test
    void aRowOfOrdinaryTextDoesNotReassembleOrReSegmentItsCellsPerColumn() {
        String line = prose(COLUMNS);
        StyledText text = StyledText.plain(line);
        long bytes = AllocationProbe.bytes(
                () -> engine.layout(Views.text(text), new Rect(0, 0, 1, COLUMNS)));
        assertThat(bytes)
                .as("a row of ordinary text costs about the grid and its cells, not several objects "
                        + "per column: a cell nothing continues is the cluster it started from, and "
                        + "a gap between printable ASCII characters needs no segmentation")
                .isLessThan(COLUMNS * 100L);

        // The cheaper path has to paint the same frame: one cell per column, the same text, and the
        // columns the plain text itself measures.
        Screen screen = engine.layout(Views.text(text), new Rect(0, 0, 1, COLUMNS)).screen();
        assertThat(screen.plainLine(0)).isEqualTo(line);
        assertThat(TextMeasure.columns(screen.plainLine(0))).isEqualTo(COLUMNS);
        int painted = 0;
        for (int col = 0; col < COLUMNS; col++) {
            if (!screen.isContinuation(0, col)) {
                painted++;
            }
        }
        assertThat(painted).isEqualTo(COLUMNS);
    }

    @Test
    void wrappingARowOfOrdinaryTextDoesNotCopyEachClusterIntoItsCell() {
        StyledText text = StyledText.plain(prose(COLUMNS));
        long bytes = AllocationProbe.bytes(() -> engine.wrapLines(text, COLUMNS));
        assertThat(bytes)
                .as("a cluster that is its own cell is appended to the line as itself, not copied "
                        + "through a dotted-circle and held-run prefix first")
                .isLessThan(COLUMNS * 20L);

        assertThat(engine.wrapLines(text, COLUMNS)).containsExactly(text);
    }

    @Test
    void aStyleBoundaryBetweenTwoPrintableAsciiCharactersKeepsThemInSeparateCells() {
        // The cheap answer for the continuation question applies when the character before the gap
        // and the cluster after it are both printable ASCII. The oracle is the break iterator
        // TextMeasure segments with: it puts a boundary in every such gap, so the two characters are
        // two clusters, two measured columns, and two painted cells each keeping the style of the
        // span it arrived in.
        Style bold = Style.NONE.withBold();
        int pairs = 0;
        for (char first = 0x20; first <= 0x7E; first++) {
            for (char second = 0x20; second <= 0x7E; second += 7) {
                String pair = "" + first + second;
                assertThat(TextMeasure.graphemeCount(pair))
                        .as("clusters of \"%s\"", pair).isEqualTo(2);
                assertThat(TextMeasure.columns(pair)).as("columns of \"%s\"", pair).isEqualTo(2);

                StyledText split = StyledText.concat(
                        StyledText.of(String.valueOf(first), Style.NONE),
                        StyledText.of(String.valueOf(second), bold));
                Screen screen = engine.layout(Views.text(split), new Rect(0, 0, 1, 6)).screen();
                Screen plain = engine.layout(
                        Views.text(StyledText.plain(split.plainText())),
                        new Rect(0, 0, 1, 6)).screen();
                assertThat(paintedCells(screen))
                        .as("a style boundary inside \"%s\"", pair)
                        .isEqualTo(paintedCells(plain));
                assertThat(screen.cell(0, 0).text()).isEqualTo(String.valueOf(first));
                assertThat(screen.cell(0, 1).text()).isEqualTo(String.valueOf(second));
                assertThat(screen.cell(0, 0).style()).isEqualTo(Style.NONE);
                assertThat(screen.cell(0, 1).style()).isEqualTo(bold);
                pairs++;
            }
        }
        assertThat(pairs).isEqualTo(95 * 14);
    }

    @Test
    void aPrintableAsciiNeighbourOnOneSideOfTheGapStillDecidesItsCellByTheBreakIterator() {
        // The fast answer applies only when both sides of the gap are printable ASCII. A mark after
        // a base joins the cell (one cell, one column), and a base after a mark does not: the mark's
        // cell keeps the dotted circle the measurement charges it, and the base opens the next.
        Style bold = Style.NONE.withBold();
        Screen joined = engine.layout(Views.text(StyledText.concat(
                        StyledText.of("e", Style.NONE), StyledText.of("\u0301", bold))),
                new Rect(0, 0, 1, 6)).screen();
        assertThat(joined.cell(0, 0).text()).isEqualTo("e\u0301");
        assertThat(joined.isContinuation(0, 1)).isFalse();
        assertThat(joined.cell(0, 1).isEmpty()).isTrue();
        assertThat(TextMeasure.columns("e\u0301")).isEqualTo(1);

        Screen separate = engine.layout(Views.text(StyledText.concat(
                        StyledText.of("e\u0301", Style.NONE), StyledText.of("x", bold))),
                new Rect(0, 0, 1, 6)).screen();
        assertThat(separate.cell(0, 0).text()).isEqualTo("e\u0301");
        assertThat(separate.cell(0, 1).text()).isEqualTo("x");
        assertThat(separate.cell(0, 1).style()).isEqualTo(bold);
        assertThat(TextMeasure.columns("e\u0301x")).isEqualTo(2);

        Screen baseless = engine.layout(Views.text(StyledText.concat(
                        StyledText.of("\u0301", Style.NONE), StyledText.of("x", bold))),
                new Rect(0, 0, 1, 6)).screen();
        assertThat(baseless.cell(0, 0).text()).isEqualTo(TextMeasure.DOTTED_CIRCLE + "\u0301");
        assertThat(baseless.cell(0, 1).text()).isEqualTo("x");
        assertThat(TextMeasure.columns("\u0301x")).isEqualTo(2);
    }
}
