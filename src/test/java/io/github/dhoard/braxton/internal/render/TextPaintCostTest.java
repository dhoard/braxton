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

import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
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

    /**
     * A whole row of one non-ASCII cluster, wide enough to fill {@code columns} columns exactly.
     * Box drawing is the frame a bordered widget paints; CJK is what a row of translated text
     * holds.
     */
    private static String rowOf(char glyph, int columns) {
        String cluster = String.valueOf(glyph);
        int width = TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
        StringBuilder text = new StringBuilder();
        for (int used = 0; used < columns; used += width) {
            text.append(cluster);
        }
        return text.toString();
    }

    /**
     * What one segmentation pass over {@code line} costs: the cursor, its break iterator, and the
     * String of every cluster it hands out. Wrapping and painting each segment a value, so this is
     * the part of a row's cost that does not depend on how a "does this cluster continue the cell"
     * question is answered - the allowance the bounds below add to the printable-ASCII row of the
     * same width.
     */
    private static long segmentationCost(String line) {
        return AllocationProbe.bytes(() -> {
            int clusters = 0;
            for (String ignored : Graphemes.iterate(line)) {
                clusters++;
            }
            return clusters;
        });
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
    void aRowOfNonAsciiClustersIsNotReSegmentedPerColumn() {
        // The bounds above cover the alphabet the fast answer already decides: a printable-ASCII
        // row never reaches the break iterator. A frame is not that alphabet - a bordered widget
        // paints its edges as one run of box-drawing glyphs, and a translated row is CJK - and a
        // row of those used to copy and re-segment the growing cell at every column. The reference
        // is the printable-ASCII row of the same width; the allowance is the segmentation every
        // pass over the row pays whatever the answer to the continuation question is.
        long ascii = AllocationProbe.bytes(
                () -> engine.layout(Views.text(StyledText.plain(prose(COLUMNS))),
                        new Rect(0, 0, 1, COLUMNS)));
        for (char glyph : new char[] {'\u2500', '\u4e2d', '\u00e9'}) {
            String line = rowOf(glyph, COLUMNS);
            StyledText text = StyledText.plain(line);
            int clusters = TextMeasure.graphemeCount(line);
            long segmentation = segmentationCost(line);

            long bytes = AllocationProbe.bytes(
                    () -> engine.layout(Views.text(text), new Rect(0, 0, 1, COLUMNS)));
            assertThat(bytes)
                    .as("a row of \"%s\" costs the printable-ASCII row plus the wrap and paint "
                            + "segmentations of its clusters, not a copy of the cell and a "
                            + "segmentation per column", glyph)
                    .isLessThan(ascii + 4 * segmentation);

            Screen screen = engine.layout(Views.text(text), new Rect(0, 0, 1, COLUMNS)).screen();
            assertThat(screen.plainLine(0)).isEqualTo(line);
            assertThat(TextMeasure.columns(screen.plainLine(0))).isEqualTo(COLUMNS);
            int painted = 0;
            for (int col = 0; col < COLUMNS; col++) {
                if (!screen.isContinuation(0, col)) {
                    painted++;
                }
            }
            assertThat(painted).as("cells painted for \"%s\"", glyph).isEqualTo(clusters);
        }
    }

    @Test
    void wrappingARowOfNonAsciiClustersDoesNotReSegmentItsCellPerColumn() {
        long ascii = AllocationProbe.bytes(
                () -> engine.wrapLines(StyledText.plain(prose(COLUMNS)), COLUMNS));
        for (char glyph : new char[] {'\u2500', '\u4e2d', '\u00e9'}) {
            String line = rowOf(glyph, COLUMNS);
            StyledText text = StyledText.plain(line);
            long segmentation = segmentationCost(line);

            long bytes = AllocationProbe.bytes(() -> engine.wrapLines(text, COLUMNS));
            assertThat(bytes)
                    .as("wrapping a row of \"%s\" charges the row's own segmentation, not a "
                            + "segmentation of the cell it is building at every column", glyph)
                    .isLessThan(ascii + 2 * segmentation);

            assertThat(engine.wrapLines(text, COLUMNS))
                    .as("a row of \"%s\" that fits the row is still one line", glyph)
                    .containsExactly(text);
        }
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
    void aRunInsideOneSpanKeepsItsCellsAndAClusterSplitIntoTheNextSpanStillJoins() {
        // The cheap answer is a fact about a span's own clusters, not a licence to stop asking. A
        // run of box-drawing glyphs in one span is a run of clusters the value's segmentation
        // already separated; a base and the mark that arrives in the span after it are one cluster
        // of the plain text, and only the break iterator can say so. Both answers have to hold in
        // the same row, and they have to agree with the same text as one plain span.
        Style bold = Style.NONE.withBold();
        StyledText split = StyledText.concat(
                StyledText.of("\u2500\u2500\u2500", Style.NONE),
                StyledText.of("e", bold),
                StyledText.of("\u0301", Style.NONE),
                StyledText.of("\u2500", bold));
        StyledText plain = StyledText.plain(split.plainText());
        Screen screen = engine.layout(Views.text(split), new Rect(0, 0, 1, 8)).screen();
        Screen reference = engine.layout(Views.text(plain), new Rect(0, 0, 1, 8)).screen();

        assertThat(paintedCells(screen)).isEqualTo(paintedCells(reference));
        assertThat(screen.cell(0, 0).text()).isEqualTo("\u2500");
        assertThat(screen.cell(0, 1).text()).isEqualTo("\u2500");
        assertThat(screen.cell(0, 2).text()).isEqualTo("\u2500");
        assertThat(screen.cell(0, 3).text()).isEqualTo("e\u0301");
        assertThat(screen.cell(0, 3).style()).isEqualTo(bold);
        assertThat(screen.cell(0, 4).text()).isEqualTo("\u2500");
        assertThat(TextMeasure.columns(screen.plainLine(0))).isEqualTo(5);
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
