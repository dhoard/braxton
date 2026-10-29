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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** Deterministic layout: sizing, clipping, overlays, and wide-cell bookkeeping. */
class LayoutEngineTest {

    private final LayoutEngine engine = new LayoutEngine();

    private Screen paint(View<?> view, int rows, int cols) {
        return engine.layout(view, new Rect(0, 0, rows, cols)).screen();
    }

    /** Rows whose cells carry a background, i.e. rows a fill actually painted. */
    private static int filledRows(View<?> view, int rows, int cols) {
        Screen screen = new LayoutEngine().layout(view, new Rect(0, 0, rows, cols)).screen();
        int filled = 0;
        for (int row = 0; row < rows; row++) {
            if (screen.cell(row, 0).style().background() != null) {
                filled++;
            }
        }
        return filled;
    }

    /** Every painted cell of a screen, so a test can compare two layouts column by column. */
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
    void aRootFixedOrGrowDefersToTheViewportBecauseNoParentAllocatesItsAxis() {
        // Fixed and Grow reserve space on a parent's main axis. A document whose root is one of
        // them has no such parent, so the child paints the whole viewport instead of being
        // clipped to the nominal size.
        Style background = Style.bg(Colors.ERROR_400);
        assertThat(filledRows(Views.fixed(2, Views.fill(background)), 4, 6)).isEqualTo(4);
        assertThat(filledRows(Views.grow(Views.fill(background)), 4, 6)).isEqualTo(4);
        // A column of fills is only as tall as its children's intrinsic rows (one each), so a
        // full-viewport background must be a root fill or an overlay layer.
        assertThat(filledRows(Views.column(Views.fill(background), Views.fill(background)), 4, 6))
                .isEqualTo(2);
    }

    @Test
    void fixedAndGrowChildrenSplitTheAvailableHeight() {
        View<?> view = Views.column(
                Views.fixed(2, Views.text("top")),
                Views.grow(Views.text("middle")),
                Views.fixed(1, Views.text("bottom")));
        Screen screen = paint(view, 9, 10);
        assertThat(screen.plainLine(0)).isEqualTo("top");
        assertThat(screen.plainLine(2)).isEqualTo("middle");
        assertThat(screen.plainLine(8)).isEqualTo("bottom");
    }

    @Test
    void aGrowChildInARowKeepsItsContentHeight() {
        View<?> view = Views.row(Views.text("a"), Views.grow(Views.text("middle")));
        Screen screen = paint(view, 1, 20);
        assertThat(screen.plainLine(0)).isEqualTo("amiddle");
    }

    @Test
    void flexWeightsTooLargeForAnIntStillDivideTheRemainingSpace() {
        // A weight is any non-negative int and the documented rule is that remaining cells are
        // divided by weight, so neither the weight sum nor the per-child product may overflow.
        // Two Integer.MAX_VALUE weights summed negative (no child got a cell) and one large weight
        // multiplied by the remaining columns wrapped (children overlapped or vanished).
        View<?> lopsidedRow = Views.row(Views.grow(Integer.MAX_VALUE, Views.text("a")),
                Views.grow(1, Views.text("b")));
        assertThat(paint(lopsidedRow, 1, 10).plainLine(0)).isEqualTo("a");

        View<?> evenRow = Views.row(Views.grow(Integer.MAX_VALUE, Views.text("a")),
                Views.grow(Integer.MAX_VALUE, Views.text("b")));
        assertThat(paint(evenRow, 1, 10).plainLine(0)).isEqualTo("a    b");

        View<?> wrappedRow = Views.row(Views.grow(1_000_000_000, Views.text("a")),
                Views.grow(1, Views.text("b")));
        assertThat(paint(wrappedRow, 1, 10).plainLine(0)).isEqualTo("a");

        View<?> evenColumn = Views.column(Views.grow(Integer.MAX_VALUE, Views.text("a")),
                Views.grow(Integer.MAX_VALUE, Views.text("b")));
        Screen even = paint(evenColumn, 10, 3);
        assertThat(even.plainLine(0)).isEqualTo("a");
        assertThat(even.plainLine(5)).isEqualTo("b");

        View<?> wrappedColumn = Views.column(Views.grow(1_000_000_000, Views.text("a")),
                Views.grow(1, Views.text("b")));
        Screen lopsided = paint(wrappedColumn, 10, 3);
        assertThat(lopsided.plainLine(0)).isEqualTo("a");
        assertThat(lopsided.plainLines()).doesNotContain("b");
    }

    @Test
    void anEndAlignedColumnKeepsAGrowChildsContentWidth() {
        View<?> view = Views.column(Alignment.END, List.of(Views.grow(Views.text("mid"))));
        Screen screen = paint(view, 1, 10);
        assertThat(screen.plainLine(0)).isEqualTo("       mid");
    }

    @Test
    void anAlignedColumnKeepsAGrowContentNestedInsideARowChild() {
        // A row child is measured at the column's full width, where rowWidths gives its Grow child
        // a share of that width. The cross-axis width that places the child has to count the same
        // content: measuring the Grow as zero narrowed the rectangle to "Score: " and the flex
        // text inside it was never painted.
        View<?> status = Views.row(Views.text("Score: "), Views.grow(Views.text("42")));
        assertThat(paint(Views.column(Alignment.START, List.of(status)), 1, 12).plainLine(0))
                .isEqualTo("Score: 42");
        assertThat(paint(Views.column(Alignment.CENTER, List.of(status)), 1, 12).plainLine(0))
                .as("centred status line keeps its flex value")
                .isEqualTo(" Score: 42");
        assertThat(paint(Views.column(Alignment.END, List.of(status)), 1, 12).plainLine(0))
                .as("end-aligned status line keeps its flex value")
                .isEqualTo("   Score: 42");
    }

    @Test
    void anAlignedColumnPaintsEveryRowItsMeasuredHeightReserves() {
        // The measurement and the paint rectangle have to agree on the width: at 12 columns the
        // row reserves five rows for the wrapped value, then painted at seven it needed one, so
        // four reserved rows were left blank and the words after "Score: " disappeared.
        View<?> status = Views.row(Views.text("Score: "),
                Views.grow(Views.text("aaaa bbbb cccc dddd eeee")));
        assertThat(engine.intrinsicHeight(status, 12)).isEqualTo(5);
        Screen screen = paint(Views.column(Alignment.CENTER, List.of(status)), 5, 12);
        String painted = String.join("", screen.plainLines());
        for (String word : List.of("Score:", "aaaa", "bbbb", "cccc", "dddd", "eeee")) {
            assertThat(painted).as("%s in the centred status line", word).contains(word);
        }
    }

    @Test
    void anAlignedColumnKeepsAGrowOnlyRowsContent() {
        // A row of nothing but flex measures zero intrinsic width, which left an aligned column
        // with an empty rectangle: the rows were reserved and paint() drew nothing at all.
        View<?> growOnly = Views.row(Views.grow(Views.text("mid")));
        assertThat(paint(Views.column(Alignment.CENTER, List.of(growOnly)), 1, 10).plainLine(0))
                .isEqualTo("   mid");
        assertThat(paint(Views.column(Alignment.END, List.of(growOnly)), 1, 10).plainLine(0))
                .isEqualTo("       mid");
    }

    @Test
    void aCentredFrameAroundAGrowChildKeepsItsContentWidth() {
        // The frame paints the rectangle it is given, so a flex-blind cross-axis width collapsed
        // it to its two corners with the value it frames lost between them.
        View<?> framed = Views.border(Border.SINGLE,
                Views.row(Views.grow(Views.text("content"))));
        Screen screen = paint(Views.column(Alignment.CENTER, List.of(framed)), 3, 14);
        assertThat(screen.plainLine(0)).isEqualTo("  \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510");
        assertThat(screen.plainLine(1)).isEqualTo("  \u2502content\u2502");
        assertThat(screen.plainLine(2)).isEqualTo("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void aCentredOverlayKeepsAFlexLayerContentWidth() {
        // An overlay layer is measured on the cross axis like a column child, so a flex row layer
        // contributes the text it holds and not the zero its wrapper measures.
        View<?> overlay = Views.overlay(
                List.of(Views.text("base"), Views.row(Views.grow(Views.text("layer")))), false);
        assertThat(engine.intrinsicWidth(overlay, 12)).isEqualTo(5);
        assertThat(paint(Views.column(Alignment.CENTER, List.of(overlay)), 1, 12).plainLine(0))
                .isEqualTo("   layer");
    }

    @Test
    void aColumnChildOfARowKeepsAGrowChildsContentWidth() {
        // The same cross-axis rule decides the columns a row hands a nested column: measured as
        // zero, the column was given no rectangle and its flex content was never painted.
        View<?> nested = Views.column(Alignment.CENTER,
                List.of(Views.row(Views.grow(Views.text("inner")))));
        assertThat(engine.intrinsicWidth(nested, 12)).isEqualTo(5);
        assertThat(paint(nested, 1, 12).plainLine(0)).isEqualTo("   inner");
        assertThat(paint(Views.row(nested, Views.text("|end")), 1, 12).plainLine(0))
                .isEqualTo("inner|end");
    }

    @Test
    void aColumnMeasuresTheHeightOfARowHoldingAGrowChild() {
        View<?> view = Views.column(Views.row(Views.grow(Views.text("mid"))),
                Views.text("last"));
        Screen screen = paint(view, 2, 10);
        assertThat(screen.plainLine(0)).isEqualTo("mid");
        assertThat(screen.plainLine(1)).isEqualTo("last");
    }

    @Test
    void textIsClippedToTheAvailableWidth() {
        Screen screen = paint(Views.text("abcdefgh"), 1, 4);
        assertThat(screen.plainLine(0)).isEqualTo("abcd");
        assertThat(screen.cell(0, 5).text()).isEmpty();
    }

    @Test
    void aStyleBoundaryInsideAClusterDoesNotAddAColumn() {
        // StyledText keeps a span boundary, but the value it measures is the concatenated plain
        // text, where the mark and the letter before it are one cluster. Painting each span on its
        // own gave the bare mark a dotted circle, so the painted row was a column wider than the
        // width every measurement - and therefore every frame - computed from the same value.
        StyledText split = StyledText.concat(StyledText.of("e", Style.NONE),
                StyledText.of("\u0301", Style.NONE.withBold()));
        assertThat(split.plainText()).isEqualTo("e\u0301");
        assertThat(split.columns()).isEqualTo(1);
        assertThat(engine.intrinsicWidth(Views.text(split), 4)).isEqualTo(1);
        Screen screen = paint(Views.text(split), 1, 2);
        assertThat(screen.cell(0, 0).text()).isEqualTo("e\u0301");
        assertThat(screen.cell(0, 1).text()).isEmpty();
        assertThat(screen.plainLine(0)).isEqualTo("e\u0301");
    }

    @Test
    void aClusterContinuingTheLastCellDoesNotWrapAtTheLineEdge() {
        // The mark continues the "b" cell, so it costs no column and stays on the row the
        // measurement counted: moving it to a second line reserved a row that was never measured.
        StyledText split = StyledText.concat(StyledText.of("ab", Style.NONE),
                StyledText.of("\u0301", Style.NONE.withBold()));
        assertThat(engine.wrapLines(split, 2)).hasSize(1);
        assertThat(engine.intrinsicHeight(Views.text(split), 2)).isEqualTo(1);
        Screen screen = paint(Views.text(split), 1, 2);
        assertThat(screen.plainLine(0)).isEqualTo("ab\u0301");
    }

    @Test
    void aVariationSelectorInItsOwnSpanStillTakesTheColumnsItMeasures() {
        // A selector selects the wide form of the character before it, so the joined cluster is two
        // columns even though the selector's own span starts without a base.
        StyledText wide = StyledText.of("e", Style.NONE)
                .append(StyledText.of("\uFE0F", Style.NONE.withBold()));
        assertThat(wide.columns()).isEqualTo(2);
        Screen screen = paint(Views.text(wide), 1, 2);
        assertThat(screen.cell(0, 0).text()).isEqualTo("e\uFE0F");
        assertThat(screen.isContinuation(0, 1)).isTrue();
        assertThat(screen.plainLine(0)).isEqualTo("e\uFE0F");
    }

    @Test
    void aSelectorSplitAtTheLineEdgeMovesTheWholeClusterToTheNextRow() {
        // The selector widens the base it follows to two columns, so the pair cannot share the last
        // column of the row. It has to start the next row as one cluster: dropping the selector when
        // the widened cell no longer fitted painted different content for the same plain text.
        StyledText split = StyledText.concat(StyledText.of("ab", Style.NONE),
                StyledText.of("\uFE0F", Style.NONE.withBold()));
        StyledText plain = StyledText.plain(split.plainText());
        assertThat(split.columns()).isEqualTo(3);
        assertThat(engine.wrapLines(split, 2)).hasSize(2);
        assertThat(engine.wrapLines(plain, 2)).hasSize(2);
        assertThat(engine.intrinsicHeight(Views.text(split), 2)).isEqualTo(2);
        Screen screen = paint(Views.text(split), 2, 2);
        assertThat(screen.plainLine(0)).isEqualTo("a");
        assertThat(screen.cell(1, 0).text()).isEqualTo("b\uFE0F");
        assertThat(screen.isContinuation(1, 1)).isTrue();
    }

    @Test
    void aSelectorSplitAtTheLineEdgeThatNarrowsItsCellKeepsTheColumnsTheRowMeasures() {
        // U+FE0E asks for the text form of the wide base before it, so the joined cluster is one
        // column where the base alone is two. wrapLines reads the selector across the span
        // boundary and charges the row for the joined cluster, but paintLine tested the fit with
        // the width of the base inside its own span and gave up on the rest of the row: the row
        // measured to hold the glyph was painted without it, and a frame one column wider showed
        // it. The cell's columns are only final once every cluster that continues it has joined.
        StyledText split = StyledText.concat(StyledText.of("abc", Style.NONE),
                StyledText.of("\u4E2D", Style.NONE.withBold()),
                StyledText.of("\uFE0E", Style.NONE));
        StyledText plain = StyledText.plain(split.plainText());
        assertThat(split.columns()).isEqualTo(4);
        assertThat(engine.wrapLines(split, 4)).hasSize(1);
        assertThat(engine.intrinsicWidth(Views.text(split), 4)).isEqualTo(4);
        assertThat(engine.intrinsicHeight(Views.text(split), 4)).isEqualTo(1);
        Screen screen = paint(Views.text(split), 1, 4);
        assertThat(screen.cell(0, 3).text()).isEqualTo("\u4E2D\uFE0E");
        assertThat(screen.plainLine(0)).isEqualTo("abc\u4E2D\uFE0E");
        assertThat(paintedCells(screen))
                .isEqualTo(paintedCells(paint(Views.text(plain), 1, 4)));
    }

    @Test
    void aNarrowingSelectorSplitFromItsBaseKeepsTheRestOfTheRow() {
        // The dropped cell is not always the row's last: the narrowed cell sits in the middle of
        // this value and the row after it holds the tail. Every width from one to the value's own
        // columns has to paint the cells wrapLines charged for it, so the same plain text paints
        // the same cells whatever spans it arrived in.
        StyledText split = StyledText.concat(StyledText.of("abc", Style.NONE),
                StyledText.of("\u4E2D", Style.NONE.withBold()),
                StyledText.of("\uFE0E", Style.NONE),
                StyledText.of("xy", Style.NONE.withBold()));
        StyledText plain = StyledText.plain(split.plainText());
        assertThat(split.columns()).isEqualTo(6);
        for (int cols = 1; cols <= 8; cols++) {
            int rows = Math.max(1, engine.wrapLines(plain, cols).size());
            assertThat(paintedCells(paint(Views.text(split), rows, cols)))
                    .as("value \"%s\" at %d columns", split.plainText(), cols)
                    .isEqualTo(paintedCells(paint(Views.text(plain), rows, cols)));
        }
        Screen screen = paint(Views.text(split), 2, 4);
        assertThat(screen.plainLine(0)).isEqualTo("abc\u4E2D\uFE0E");
        assertThat(screen.plainLine(1)).isEqualTo("xy");
    }

    @Test
    void anEmojiAndItsTextPresentationSelectorSplitAcrossSpansStayOneCell() {
        // The canonical use of U+FE0E: an emoji that terminals draw two columns wide is asked for
        // its text form, which is one. The selector arrives in the run after the emoji, so the
        // cell that fits the last column of this row is only known once the selector joins it.
        StyledText split = StyledText.concat(StyledText.of("ab", Style.NONE),
                StyledText.of("\uD83D\uDE42", Style.NONE.withBold()),
                StyledText.of("\uFE0E", Style.NONE));
        StyledText plain = StyledText.plain(split.plainText());
        assertThat(split.columns()).isEqualTo(3);
        assertThat(engine.intrinsicHeight(Views.text(split), 3)).isEqualTo(1);
        Screen screen = paint(Views.text(split), 1, 3);
        assertThat(screen.cell(0, 2).text()).isEqualTo("\uD83D\uDE42\uFE0E");
        assertThat(screen.plainLine(0)).isEqualTo("ab\uD83D\uDE42\uFE0E");
        assertThat(paintedCells(screen))
                .isEqualTo(paintedCells(paint(Views.text(plain), 1, 3)));
    }

    @Test
    void aControlBeforeABaselessClusterKeepsTheColumnTheMarkMeasures() {
        // The measurement breaks the cluster after a control character such as a zero-width space,
        // so the mark that follows is a cluster of its own and its column is counted. Joining it
        // into the cell before the control folded that column into the base and moved every cell
        // after it - the right edge of a row included - one column to the left.
        StyledText split = StyledText.concat(StyledText.of("\u65E5", Style.NONE),
                StyledText.of("\u200B\u0301", Style.NONE.withBold()));
        StyledText plain = StyledText.plain(split.plainText());
        assertThat(TextMeasure.columns(split.plainText())).isEqualTo(3);
        assertThat(engine.intrinsicWidth(Views.text(split), 8)).isEqualTo(3);
        Screen screen = paint(Views.text(split), 1, 4);
        assertThat(screen.cell(0, 0).text()).isEqualTo("\u65E5\u200B");
        assertThat(screen.isContinuation(0, 1)).isTrue();
        assertThat(screen.cell(0, 2).text()).isEqualTo(TextMeasure.DOTTED_CIRCLE + "\u0301");
        assertThat(screen.cell(0, 3).text()).isEmpty();
        assertThat(paintedCells(screen)).isEqualTo(paintedCells(paint(Views.text(plain), 1, 4)));
    }

    @Test
    void aTabStopAndABaselessClusterKeepTheColumnsTheyMeasure() {
        // The tab expands to the next four-column stop and ends the cluster there, so the mark
        // after it is a cluster of its own: five columns, whether it arrives in the tab's span or in
        // a span of its own.
        StyledText split = StyledText.concat(StyledText.of("\t", Style.NONE),
                StyledText.of("\u0301", Style.NONE.withBold()));
        assertThat(engine.intrinsicWidth(Views.text(split), 8)).isEqualTo(5);
        assertThat(engine.intrinsicHeight(Views.text(split), 8)).isEqualTo(1);
        Screen screen = paint(Views.text(split), 1, 8);
        assertThat(screen.cell(0, 3).text()).isEqualTo(" ");
        assertThat(screen.cell(0, 4).text()).isEqualTo(TextMeasure.DOTTED_CIRCLE + "\u0301");
        assertThat(screen.cell(0, 5).text()).isEmpty();
        assertThat(paintedCells(screen)).isEqualTo(paintedCells(
                paint(Views.text(StyledText.plain(split.plainText())), 1, 8)));
    }

    @Test
    void aSelectorAfterATabStopKeepsTheColumnsTheRowMeasures() {
        // A tab reaches the row as the spaces of its stop, so that row's own plain text reads the
        // selector after the tab as part of the last of those spaces: a cell two columns wide. The
        // stop has to reserve that column the way every other widening cell does. Charged as one
        // column, the row came back a column wider than the width it wrapped to, and paint dropped
        // the tail of the row - the "x" here.
        String text = "a\t\uFE0Fx";
        for (int cols = 1; cols <= 8; cols++) {
            for (StyledText line : engine.wrapLines(StyledText.plain(text), cols)) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as("row at %d columns", cols)
                        .isLessThanOrEqualTo(cols);
            }
        }
        // Six columns: the four of the stop, the column its last space gains, and the "x".
        assertThat(engine.intrinsicWidth(Views.text(text), 8)).isEqualTo(6);
        int rows = engine.intrinsicHeight(Views.text(text), 5);
        assertThat(rows).isEqualTo(2);
        Screen screen = paint(Views.text(text), rows, 5);
        assertThat(screen.cell(0, 3).text()).isEqualTo(" \uFE0F");
        assertThat(screen.isContinuation(0, 4)).isTrue();
        assertThat(screen.cell(1, 0).text()).isEqualTo("x");
        // A value that spells the same stop with spaces is the reference: the tab has to keep the
        // same cells, including the character that follows the widened one.
        assertThat(paintedCells(screen)).isEqualTo(paintedCells(
                paint(Views.text(StyledText.plain("a   \uFE0Fx")), rows, 5)));
    }

    @Test
    void aSelectorSplitFromItsJoinerAfterATabStopKeepsTheColumnsTheRowMeasures() {
        // A style boundary can split the run of clusters that continues a tab stop's last space:
        // the joiner claims no column of itself and the selector that widens the cell follows it in
        // the next span. The stop reserved only the cluster behind the tab, so the row it committed
        // measured a column wider than the width it wrapped to, the value measured a row fewer than
        // it painted, and paint dropped the row's tail - the "D" here - although a wider frame
        // showed it. The whole run that continues the cell is reserved with the stop now.
        StyledText split = StyledText.concat(StyledText.of("ab\t", Style.NONE),
                StyledText.of("\u200D", Style.NONE.withBold()),
                StyledText.of("\uFE0FCD", Style.NONE));
        StyledText plain = StyledText.plain(split.plainText());
        for (int cols = 1; cols <= 9; cols++) {
            for (StyledText line : engine.wrapLines(split, cols)) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as("row at %d columns", cols)
                        .isLessThanOrEqualTo(cols);
            }
            assertThat(engine.intrinsicHeight(Views.text(split), cols))
                    .as("rows of \"%s\" at %d columns", split.plainText(), cols)
                    .isEqualTo(engine.intrinsicHeight(Views.text(plain), cols));
        }
        // Six columns: "ab", the two of the stop, the column its last space gains, and "C" - "D"
        // starts the next row, exactly as it does for the same text in one span.
        int rows = engine.intrinsicHeight(Views.text(split), 6);
        assertThat(rows).isEqualTo(2);
        Screen screen = paint(Views.text(split), rows, 6);
        assertThat(paintedCells(screen))
                .isEqualTo(paintedCells(paint(Views.text(plain), rows, 6)));
        assertThat(screen.cell(1, 0).text()).isEqualTo("D");
        // A frame is measured from the rows its content wraps to, so the row it paints keeps every
        // character the frame is tall enough for.
        View<?> framed = Views.border(Border.SINGLE, Views.text(split));
        int framedRows = engine.intrinsicHeight(framed, 8);
        assertThat(framedRows).isEqualTo(4);
        assertThat(paint(framed, framedRows, 8).plainLines()).containsExactly(
                "\u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
                "\u2502ab  \u200D\uFE0FC\u2502",
                "\u2502D     \u2502",
                "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void aSelectorInsideABaselessClusterKeepsTheColumnsItsDottedCircleMeasures() {
        // The dotted circle stands in for the base a cluster does not have, and the row holds it, so
        // the selector in that cluster widens the circle the way it widens any other base: two
        // columns, not the one the bare cluster measures. Charged as one, the row measured wider
        // than the width it wrapped to and paint dropped the "a" that followed it.
        String text = "\uFE0F\u0301a";
        for (int cols = 1; cols <= 4; cols++) {
            for (StyledText line : engine.wrapLines(StyledText.plain(text), cols)) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as("row at %d columns", cols)
                        .isLessThanOrEqualTo(cols);
            }
        }
        assertThat(engine.intrinsicWidth(Views.text(text), 4)).isEqualTo(3);
        assertThat(engine.intrinsicHeight(Views.text(text), 2)).isEqualTo(2);
        Screen screen = paint(Views.text(text), 2, 2);
        assertThat(screen.cell(0, 0).text()).isEqualTo(TextMeasure.DOTTED_CIRCLE + "\uFE0F\u0301");
        assertThat(screen.isContinuation(0, 1)).isTrue();
        assertThat(screen.plainLine(1)).isEqualTo("a");
    }

    @Test
    void aSelectorSplitFromABaselessClusterKeepsTheColumnsItsDottedCircleMeasures() {
        // The plain text keeps the selector in front of the mark: together they are one cluster, and
        // the selector is the character that makes the dotted circle's cell two columns wide. A span
        // boundary between them committed the selector outside the cell and measured the cell without
        // it, so the same plain text painted one column where every measurement of it says two, the
        // dotted circle moved behind the selector, and every cell after it landed one column left.
        StyledText split = StyledText.of("\uFE0F", Style.NONE.withBold())
                .append(StyledText.of("\u0301a", Style.NONE));
        StyledText plain = StyledText.plain(split.plainText());
        assertThat(TextMeasure.columns(split.plainText())).isEqualTo(3);
        assertThat(split.columns()).isEqualTo(3);
        assertThat(engine.intrinsicWidth(Views.text(split), 4)).isEqualTo(3);
        for (int cols = 1; cols <= 8; cols++) {
            assertThat(engine.intrinsicWidth(Views.text(split), cols))
                    .as("intrinsic width of \"%s\" at %d columns", split.plainText(), cols)
                    .isEqualTo(engine.intrinsicWidth(Views.text(plain), cols));
            assertThat(engine.intrinsicHeight(Views.text(split), cols))
                    .as("intrinsic height of \"%s\" at %d columns", split.plainText(), cols)
                    .isEqualTo(engine.intrinsicHeight(Views.text(plain), cols));
        }
        // The widened cell cannot share the last column of a two-column row, so the "a" it cannot
        // hold starts the next row - the row count the same plain text measures.
        assertThat(engine.intrinsicHeight(Views.text(split), 2)).isEqualTo(2);
        Screen screen = paint(Views.text(split), 2, 2);
        assertThat(screen.cell(0, 0).text()).isEqualTo(TextMeasure.DOTTED_CIRCLE + "\uFE0F\u0301");
        assertThat(screen.isContinuation(0, 1)).isTrue();
        assertThat(screen.plainLine(1)).isEqualTo("a");
        for (int cols = 1; cols <= 8; cols++) {
            int rows = Math.max(1, engine.wrapLines(plain, cols).size());
            assertThat(paintedCells(paint(Views.text(split), rows, cols)))
                    .as("value \"%s\" at %d columns", split.plainText(), cols)
                    .isEqualTo(paintedCells(paint(Views.text(plain), rows, cols)));
        }
        // A joiner is invisible and adds no column, but the plain text keeps it inside the cluster
        // just the same, so the dotted circle stays in front of the cluster rather than sliding
        // between its two halves.
        StyledText joined = StyledText.of("\u200D", Style.NONE.withBold())
                .append(StyledText.of("\u0301", Style.NONE));
        assertThat(paint(Views.text(joined), 1, 3).cell(0, 0).text())
                .isEqualTo(TextMeasure.DOTTED_CIRCLE + "\u200D\u0301");
        // The cell is one cluster, so it carries the link of the span the cluster - and therefore
        // the dotted circle written in front of it - starts in.
        StyledText linked = StyledText.of("\uFE0F", Style.NONE, "https://example.com")
                .append(StyledText.of("\u0301", Style.NONE));
        assertThat(paint(Views.text(linked), 1, 3).cell(0, 0).link())
                .isEqualTo("https://example.com");
    }

    @Test
    void aSelectorAfterATabStopKeepsTheColumnsItsFrameMeasures() {
        // A frame is measured from the columns its content's rows occupy. A tab stop that never
        // charged the selector after it measured the value one column narrower than it painted, so
        // the frame drew its right edge over the content and the last character was dropped.
        View<?> framed = Views.border(Border.SINGLE, Views.text("name\t\uFE0Fvalue"));
        assertThat(engine.intrinsicWidth(framed, 16)).isEqualTo(16);
        assertThat(engine.intrinsicHeight(framed, 16)).isEqualTo(3);
        assertThat(paint(framed, 3, 16).plainLine(1))
                .isEqualTo("\u2502name    \uFE0Fvalue\u2502");
    }

    @Test
    void aSpaceAfterATabStopOnASoftWrappedRowKeepsTheColumnItMeasures() {
        // A soft break may only drop the one space it broke at. The tab that opens the new row is
        // committed content - the row holds the spaces of its stop, columns 0-3 - so the space
        // after that stop sits in the middle of the row and is painted at column 4, with the text
        // behind it at column 5. The wrap went on treating the row as "nothing committed since the
        // break", swallowed that space, and shifted the rest of the row one column left: the space
        // the input spells after the tab never reached the screen.
        assertThat(engine.wrapLines(StyledText.plain("aaaaaa \t d"), 6))
                .extracting(line -> line.plainText())
                .containsExactly("aaaaaa", "     d");
        // The same row broken before the tab instead of before the space is the reference: it
        // paints the same cells before and after the fix, so the dropped space is the difference.
        assertThat(paintedCells(paint(Views.text("aaaaaa \t d"), 2, 6)))
                .isEqualTo(paintedCells(paint(Views.text("aaaaaa\t d"), 2, 6)));
        // A run of spaces after the stop keeps the columns it measures: the row that cannot hold
        // them all breaks again instead of losing them.
        assertThat(engine.wrapLines(StyledText.plain("aaaaaa \t   d"), 6))
                .extracting(line -> line.plainText())
                .containsExactly("aaaaaa", "      ", "d");
        // No row may measure wider than the width it wrapped to. Leaving the stop's last space
        // free to be widened by a cluster that arrives after the break charged that column to no
        // row at all, and paint then dropped the tail of the row that held it.
        for (int cols = 1; cols <= 8; cols++) {
            for (StyledText line : engine.wrapLines(StyledText.plain("\t \t\uFE0Fx"), cols)) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as("row at %d columns", cols)
                        .isLessThanOrEqualTo(cols);
            }
        }
        // The realistic shape: a tab used as an alignment column, whose preceding space is the
        // place the line breaks. The frame keeps the path it wraps to intact.
        View<?> framed = Views.border(Border.SINGLE, Views.text("settings: \t /etc/app.conf"));
        int rows = engine.intrinsicHeight(framed, 11);
        assertThat(rows).isEqualTo(5);
        assertThat(paint(framed, rows, 11).plainLines()).containsExactly(
                "\u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
                "\u2502settings:\u2502",
                "\u2502     /etc\u2502",
                "\u2502/app.conf\u2502",
                "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void aLeadingFormatCharacterInItsOwnSpanStaysWithTheClusterItBelongsTo() {
        // The plain text keeps a zero-width space in front of the mark it precedes, so the row it
        // is painted on has to keep it too: a span boundary that separated them dropped it, and the
        // same value painted different content depending on how it was styled.
        StyledText split = StyledText.concat(StyledText.of("ab\n", Style.NONE),
                StyledText.of("\u200B\u0301", Style.NONE.withBold()));
        Screen screen = paint(Views.text(split), 2, 3);
        assertThat(screen.plainLine(1))
                .isEqualTo("\u200B" + TextMeasure.DOTTED_CIRCLE + "\u0301");
        assertThat(paintedCells(screen)).isEqualTo(paintedCells(
                paint(Views.text(StyledText.plain(split.plainText())), 2, 3)));
    }

    @Test
    void aStyleBoundaryNeverChangesTheLayout() {
        // A span boundary is decoration. The same plain text lays out in the same cells, rows,
        // intrinsic width and intrinsic height whether it arrives in one span or in several, so a
        // frame measured from one of them cannot be painted from the other.
        Style bold = Style.NONE.withBold();
        List<StyledText> values = List.of(
                StyledText.concat(StyledText.of("e", Style.NONE), StyledText.of("\u0301", bold)),
                StyledText.of("ab", Style.NONE).append(StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("ab", Style.NONE), StyledText.of("\uFE0F", bold)),
                StyledText.concat(StyledText.of("a ", Style.NONE), StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("a", Style.NONE),
                        StyledText.of("\u200B\u0301", bold)),
                StyledText.concat(StyledText.of("\u65E5", Style.NONE),
                        StyledText.of("\u200B\u0301", bold)),
                StyledText.concat(StyledText.of("\uD83D\uDE00", Style.NONE),
                        StyledText.of("\u00AD\u0301", bold)),
                StyledText.concat(StyledText.of("abc", Style.NONE),
                        StyledText.of("\u4E2D", bold), StyledText.of("\uFE0E", Style.NONE)),
                StyledText.concat(StyledText.of("ab", Style.NONE),
                        StyledText.of("\uD83D\uDE42", bold),
                        StyledText.of("\uFE0E", Style.NONE)),
                StyledText.concat(StyledText.of("\t", Style.NONE), StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("a\t", Style.NONE),
                        StyledText.of("\uFE0Fx", bold)),
                StyledText.concat(StyledText.of("ab\t", Style.NONE),
                        StyledText.of("\u200D", bold), StyledText.of("\uFE0FCD", Style.NONE)),
                StyledText.concat(StyledText.of("\u0301", Style.NONE), StyledText.of("x", bold)),
                StyledText.concat(StyledText.of("ab\n", Style.NONE),
                        StyledText.of("\u0301x", bold)),
                StyledText.concat(StyledText.of("\u200B", Style.NONE),
                        StyledText.of("\u0301", bold)),
                // A boundary between a format character and the base-less cluster the plain text
                // keeps it in front of: the cluster is measured with the character, not without it.
                StyledText.concat(StyledText.of("\uFE0F", bold),
                        StyledText.of("\u0301", Style.NONE)),
                StyledText.concat(StyledText.of("\u200D", Style.NONE),
                        StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("ab\n", Style.NONE),
                        StyledText.of("\uFE0F", bold), StyledText.of("\u0301x", Style.NONE)),
                StyledText.concat(StyledText.of("a\u200B", Style.NONE),
                        StyledText.of("\uFE0F", bold), StyledText.of("\u0301y", Style.NONE)));
        for (StyledText value : values) {
            StyledText plain = StyledText.plain(value.plainText());
            for (int cols = 1; cols <= 8; cols++) {
                int rows = Math.max(1, engine.wrapLines(plain, cols).size());
                assertThat(paintedCells(paint(Views.text(value), rows, cols)))
                        .as("value \"%s\" at %d columns", value.plainText(), cols)
                        .isEqualTo(paintedCells(paint(Views.text(plain), rows, cols)));
                assertThat(engine.intrinsicWidth(Views.text(value), cols))
                        .as("intrinsic width of \"%s\" at %d columns", value.plainText(), cols)
                        .isEqualTo(engine.intrinsicWidth(Views.text(plain), cols));
                assertThat(engine.intrinsicHeight(Views.text(value), cols))
                        .as("intrinsic height of \"%s\" at %d columns", value.plainText(), cols)
                        .isEqualTo(engine.intrinsicHeight(Views.text(plain), cols));
            }
        }
    }

    @Test
    void aBaselessClusterAtTheStartOfARowStillGetsItsDottedCircle() {
        // With no cell before it there is nothing for a bare mark to join, so the dotted circle
        // stands in for its missing base exactly once, as the measurement of a mark counts it.
        StyledText leading = StyledText.concat(StyledText.of("ab\n", Style.NONE),
                StyledText.of("\u0301x", Style.NONE.withBold()));
        assertThat(engine.intrinsicHeight(Views.text(leading), 3)).isEqualTo(2);
        Screen screen = paint(Views.text(leading), 2, 3);
        assertThat(screen.plainLine(0)).isEqualTo("ab");
        assertThat(screen.plainLine(1)).isEqualTo(TextMeasure.DOTTED_CIRCLE + "\u0301x");
    }

    @Test
    void wideGlyphMarksAContinuationCellAndIsClippedAtTheEdge() {
        Screen screen = paint(Views.text("\u65E5a"), 1, 4);
        assertThat(screen.cell(0, 0).text()).isEqualTo("\u65E5");
        assertThat(screen.isContinuation(0, 1)).isTrue();
        assertThat(screen.cell(0, 2).text()).isEqualTo("a");

        Screen narrow = paint(Views.text("\u65E5"), 1, 1);
        assertThat(narrow.cell(0, 0).text()).isEmpty();
    }

    @Test
    void paintingOverTheSecondHalfOfAWideGlyphClearsBothCells() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "\u65E5", Style.NONE, null, 2);
        screen.put(0, 1, "x", Style.NONE, null, 1);
        assertThat(screen.cell(0, 0).text()).isEmpty();
        assertThat(screen.cell(0, 1).text()).isEqualTo("x");
    }

    @Test
    void overlaysPaintBackToFront() {
        View<?> view = Views.overlay(
                Views.text("under"),
                Views.text("OVER"));
        Screen screen = paint(view, 1, 6);
        assertThat(screen.plainLine(0)).isEqualTo("OVERr");
    }

    @Test
    void backgroundFillWritesEveryCellAndIsClearedByNarrowerContentLater() {
        Screen screen = paint(Views.fill(Style.bg(Colors.BLUE_400)), 2, 3);
        assertThat(screen.cell(0, 0).style().background()).isEqualTo(Colors.BLUE_400);
        assertThat(screen.cell(1, 2).style().background()).isEqualTo(Colors.BLUE_400);
    }

    @Test
    void aFillInARowCoversTheRowsFullHeight() {
        Style black = Style.bg(Colors.BLACK);
        Screen screen = paint(Views.row(Views.text("ab"), Views.fill(black)), 3, 4);
        for (int row = 0; row < 3; row++) {
            for (int col = 2; col < 4; col++) {
                assertThat(screen.cell(row, col).style().background())
                        .as("row %d cell %d background", row, col)
                        .isEqualTo(Colors.BLACK);
            }
        }
    }

    @Test
    void aFillCoversTheContentOfALowerLayer() {
        Screen screen = paint(Views.overlay(Views.text("secret"), Views.fill(Style.NONE)), 1, 8);
        assertThat(screen.plainLine(0)).isEmpty();
    }

    @Test
    void textPaintedOverABackgroundFillKeepsTheBackground() {
        Style black = Style.bg(Colors.BLACK);
        Screen screen = paint(Views.overlay(Views.fill(black), Views.text("ab")), 1, 4);
        for (int col = 0; col < 4; col++) {
            assertThat(screen.cell(0, col).style().background())
                    .as("cell %d background", col)
                    .isEqualTo(Colors.BLACK);
        }
    }

    @Test
    void paddingAndBordersInsetTheChild() {
        View<?> view = Views.border(io.github.dhoard.braxton.layout.Border.SINGLE,
                Views.padding(1, Views.text("x")));
        Screen screen = paint(view, 5, 7);
        assertThat(screen.plainLine(0)).startsWith("\u250C");
        assertThat(screen.plainLine(2)).contains("x");
    }

    @Test
    void aColumnReservesTheHeightARowNeedsAtItsChildWidths() {
        View<?> row = Views.row(Views.fixed(4, Views.text("aa bb")), Views.text("xxxx"));
        assertThat(engine.intrinsicHeight(row, 10)).isEqualTo(2);
        View<?> view = Views.column(row, Views.text("next"));
        Screen screen = paint(view, 3, 10);
        assertThat(screen.plainLine(0)).isEqualTo("aa bxxxx");
        assertThat(screen.plainLine(1)).isEqualTo("b");
        assertThat(screen.plainLine(2)).isEqualTo("next");
    }

    @Test
    void aColumnReservesTheHeightAPaddedChildNeedsAtItsInnerWidth() {
        View<?> padded = Views.padding(Insets.symmetric(0, 1), Views.text("aaaa bbbb"));
        assertThat(engine.intrinsicHeight(padded, 10)).isEqualTo(2);
        View<?> view = Views.column(padded, Views.text("next"));
        Screen screen = paint(view, 3, 10);
        assertThat(screen.plainLine(0)).isEqualTo(" aaaa bbb");
        assertThat(screen.plainLine(1)).isEqualTo(" b");
        assertThat(screen.plainLine(2)).isEqualTo("next");
    }

    @Test
    void aContentHeightPastTheIntRangeSaturatesInsteadOfGoingNegative() {
        // Content taller than an int can describe still has a height, and the only representable
        // answer is the saturating ceiling; a negative measurement would place content above the
        // viewport and contradict the documented "never by negative arithmetic" degradation.
        View<?> overflowingInsets = Views.padding(new Insets(Integer.MAX_VALUE, 0, 0, 0),
                Views.text("a"));
        List<View<?>> views = List.of(
                // Two reservations that fit apart but not together.
                Views.column(
                        Views.fixed(2_000_000_000, Views.text("a")),
                        Views.fixed(2_000_000_000, Views.text("b"))),
                // Insets and border edges added on top of a reservation.
                Views.padding(new Insets(500_000_000, 0, 500_000_000, 0),
                        Views.fixed(2_000_000_000, Views.text("a"))),
                Views.border(Border.SINGLE, Views.fixed(Integer.MAX_VALUE, Views.text("a"))),
                // Insets that cannot even fit in the rows the previous sibling left.
                Views.column(
                        Views.fixed(2_000_000_000, Views.text("a")),
                        overflowingInsets),
                Views.border(Border.SINGLE, Views.column(
                        Views.fixed(2_000_000_000, Views.text("a")),
                        overflowingInsets)));
        for (View<?> view : views) {
            assertThat(engine.intrinsicHeight(view, 10)).isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Test
    void tabStopsCountTowardTheMeasuredWidthOfText() {
        View<?> view = Views.row(Views.text("a\tb"), Views.text("X"));
        Screen screen = paint(view, 1, 20);
        assertThat(screen.plainLine(0)).isEqualTo("a   bX");
    }

    @Test
    void aTabStopThatCannotFitOnAnEmptyLineStaysInsideTheWidth() {
        assertThat(engine.wrapLines(StyledText.plain("\ta"), 3))
                .extracting(line -> line.plainText())
                .containsExactly("   ", "a");
        for (int cols = 1; cols <= 8; cols++) {
            List<StyledText> lines = engine.wrapLines(StyledText.plain("\ta\tb\t"), cols);
            assertThat(lines).isNotEmpty();
            for (StyledText line : lines) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as("line width at %d columns", cols)
                        .isLessThanOrEqualTo(cols);
            }
        }
    }

    @Test
    void aNarrowOverlayOnABackgroundWideGlyphKeepsTheClearedColumn() {
        View<?> view = Views.overlay(List.of(
                Views.fill(Style.bg(Colors.BLACK)),
                Views.text("\u65E5"),
                Views.padding(new Insets(0, 0, 0, 1), Views.text("x"))), false);
        Screen screen = paint(view, 1, 4);
        assertThat(screen.plainLine(0)).isEqualTo(" x");
    }

    @Test
    void anOverlayLayerThatGrowsKeepsItsContentSize() {
        View<?> view = Views.column(
                Views.overlay(List.of(Views.grow(Views.text("mid"))), false),
                Views.text("last"));
        Screen screen = paint(view, 2, 10);
        assertThat(screen.plainLine(0)).isEqualTo("mid");
        assertThat(screen.plainLine(1)).isEqualTo("last");
    }

    @Test
    void nestedFlexOverlaysStayDeterministicAndInsideTheViewport() {
        View<?> view = Views.column(Views.grow(Views.row(Views.padding(
                Insets.symmetric(0, 1),
                Views.text("a\tb\n\u4f60\u597d")),
                Views.fill(Style.bg(Colors.BLUE_400)))), Views.fixed(1, Views.border(
                        Border.ROUNDED, StyledText.plain("t"), Views.overlay(List.of(
                                Views.text("base"), Views.text("\uD83D\uDC4D")), false))));
        for (int rows : new int[] {0, 1, 5, 12}) {
            for (int cols : new int[] {0, 1, 7, 20}) {
                Rect bounds = new Rect(0, 0, rows, cols);
                Screen first = new LayoutEngine().layout(view, bounds).screen();
                Screen second = new LayoutEngine().layout(view, bounds).screen();
                assertThat(first).isEqualTo(second);
                assertThat(first.rows()).isEqualTo(rows);
                assertThat(first.cols()).isEqualTo(cols);
                for (int row = 0; row < rows; row++) {
                    assertThat(TextMeasure.columns(first.plainLine(row))).isLessThanOrEqualTo(cols);
                }
            }
        }
    }

    @Test
    void anEndAlignedColumnItemKeepsItsContentWidth() {
        View<?> view = Views.column(Alignment.END,
                List.of(Views.fixed(3, Views.text("tall")), Views.text("s")));
        Screen screen = new LayoutEngine().layout(view, new Rect(0, 0, 4, 6)).screen();
        assertThat(screen.plainLine(0)).isEqualTo("  tall");
        assertThat(screen.plainLine(1)).isEmpty();
        assertThat(screen.plainLine(3)).isEqualTo("     s");
    }

    @Test
    void aRoutingWrapperDoesNotChangeAnEndAlignedFixedChildsContentWidth() {
        // Focused and Keyed route messages and reserve no cells of their own, so a Fixed child they
        // wrap keeps the content width it has without them: on the cross axis the routing wrapper
        // is invisible, exactly as the Fixed it wraps already is.
        View<Object> direct = Views.fixed(1, Views.text("tall"));
        View<Object> focused = Views.focus(ElementId.of("field"), true, direct);
        View<Object> keyed = Views.keyed(List.of(), direct, List.of());
        View<Object> nested = Views.focus(ElementId.of("outer"), false, keyed);
        for (View<Object> child : List.of(direct, focused, keyed, nested)) {
            View<Object> view = Views.column(Alignment.END, List.of(child, Views.text("s")));
            assertThat(engine.intrinsicWidth(view, 6)).as("width of %s", child).isEqualTo(4);
            Screen screen = paint(view, 4, 6);
            assertThat(screen.plainLine(0)).as("row 0 of %s", child).isEqualTo("  tall");
            assertThat(screen.plainLine(1)).as("row 1 of %s", child).isEqualTo("     s");
            assertThat(screen.plainLine(2)).as("row 2 of %s", child).isEmpty();
        }
        // The focus region is the rectangle the child painted, not the cell Fixed reserves.
        LayoutEngine.Layout layout = engine.layout(
                Views.column(Alignment.END, List.of(focused, Views.text("s"))), new Rect(0, 0, 4, 6));
        assertThat(layout.regions()).hasSize(1);
        assertThat(layout.regions().get(0).bounds()).isEqualTo(new Rect(0, 2, 1, 4));
    }

    @Test
    void aRoutingWrapperDoesNotChangeARowHoldingAFixedChildsContentHeight() {
        // The same wrapper must also stay out of the other axis: the row reserves the height its
        // children paint, so a routing wrapper over a Fixed child cannot add a blank row beneath it.
        View<Object> direct = Views.row(Views.fixed(3, Views.text("tall")), Views.text("X"));
        View<Object> focused = Views.row(
                Views.focus(ElementId.of("field"), true, Views.fixed(3, Views.text("tall"))),
                Views.text("X"));
        View<Object> keyed = Views.row(
                Views.keyed(List.of(), Views.fixed(3, Views.text("tall")), List.of()),
                Views.text("X"));
        for (View<Object> row : List.of(direct, focused, keyed)) {
            assertThat(engine.intrinsicHeight(row, 12)).as("height of %s", row).isEqualTo(2);
            View<Object> view = Views.column(row, Views.text("z"));
            assertThat(engine.intrinsicHeight(view, 12)).as("height of %s in a column", row)
                    .isEqualTo(3);
            Screen screen = paint(view, 4, 12);
            assertThat(screen.plainLine(0)).as("row 0 of %s", row).isEqualTo("talX");
            assertThat(screen.plainLine(1)).as("row 1 of %s", row).isEqualTo("l");
            assertThat(screen.plainLine(2)).as("row 2 of %s", row).isEqualTo("z");
        }
    }

    @Test
    void rightAlignedTextEndingInAZeroWidthCharacterSitsFlush() {
        View<?> view = Views.column(Alignment.END,
                List.of(Views.text("ab\u200B"), Views.text("cd")));
        Screen screen = new LayoutEngine().layout(view, new Rect(0, 0, 2, 4)).screen();
        assertThat(screen.plainLine(0).replace("\u200B", "")).isEqualTo("  ab");
        assertThat(screen.plainLine(1)).isEqualTo("  cd");
    }

    @Test
    void centringUsesTheMeasuredWidthAndNeverGoesNegative() {
        View<?> view = Views.column(Alignment.CENTER, java.util.List.of(Views.text("ab")));
        Screen screen = paint(view, 1, 6);
        assertThat(screen.plainLine(0)).isEqualTo("  ab");
        assertThat(screen.cell(0, 2).text()).isEqualTo("a");
    }

    @Test
    void aCentredChildIsPaintedAtItsMeasuredWidth() {
        // A child that fills its rectangle (a frame, say) must be placed at the measured centroid
        // with its own width; giving it the rest of the row makes "centred" run to the right edge.
        View<?> centred = Views.column(Alignment.CENTER,
                List.of(Views.border(Border.SINGLE, Views.text("x"))));
        Screen screen = paint(centred, 3, 10);
        assertThat(screen.plainLine(0)).isEqualTo("   \u250C\u2500\u2510");
        assertThat(screen.plainLine(1)).isEqualTo("   \u2502x\u2502");
        assertThat(screen.plainLine(2)).isEqualTo("   \u2514\u2500\u2518");
        View<?> ended = Views.column(Alignment.END,
                List.of(Views.border(Border.SINGLE, Views.text("x"))));
        Screen endScreen = paint(ended, 3, 10);
        assertThat(endScreen.plainLine(0)).isEqualTo("       \u250C\u2500\u2510");
        assertThat(endScreen.plainLine(1)).isEqualTo("       \u2502x\u2502");
    }

    @Test
    void aBorderedViewNeverReportsMoreColumnsThanTheAvailableWidth() {
        View<?> bordered = Views.border(io.github.dhoard.braxton.layout.Border.SINGLE,
                Views.fill(Style.NONE));
        assertThat(engine.intrinsicWidth(bordered, 1)).isLessThanOrEqualTo(1);
    }

    @Test
    void intrinsicWidthIsCappedAtTheAvailableColumns() {
        View<?> padded = Views.padding(2, Views.fill(Style.NONE));
        assertThat(engine.intrinsicWidth(padded, 10)).isEqualTo(10);
        View<?> bordered = Views.border(io.github.dhoard.braxton.layout.Border.SINGLE,
                Views.padding(1, Views.fill(Style.NONE)));
        assertThat(engine.intrinsicWidth(bordered, 10)).isEqualTo(10);
    }

    @Test
    void zeroSizeViewportsProduceAnEmptyScreenAndNoErrors() {
        Screen screen = paint(Views.text("abc"), 0, 0);
        assertThat(screen.rows()).isZero();
        assertThat(screen.cols()).isZero();
    }

    @Test
    void tinyViewportClipsInsteadOfCrashing() {
        Screen screen = paint(Views.column(Views.text("a"), Views.text("b"), Views.text("c")), 1, 1);
        assertThat(screen.plainLine(0)).isEqualTo("a");
    }

    @Test
    void repeatedLayoutOfTheSameViewIsIdentical() {
        View<?> view = Views.column(Views.text("a"), Views.text("b"));
        assertThat(paint(view, 2, 4)).isEqualTo(paint(view, 2, 4));
    }

    @Test
    void focusedRegionsRecordTheirPaintedBounds() {
        View<?> view = Views.focus(io.github.dhoard.braxton.view.ElementId.of("field"), true, Views.text("x"));
        LayoutEngine.Layout layout = engine.layout(view, new Rect(0, 0, 3, 5));
        assertThat(layout.regions()).hasSize(1);
        assertThat(layout.regions().get(0).id().value()).isEqualTo("field");
        assertThat(layout.regions().get(0).bounds()).isEqualTo(new Rect(0, 0, 3, 5));
    }

    /**
     * The height {@link LayoutEngine#intrinsicHeight} reports is enough to paint the whole tree: a
     * taller box adds no content, only empty rows. Frames are excluded because a border paints its
     * whole allocated rectangle, and end/centre rows are excluded because they place their content
     * at the aligned offset of whatever height they are given.
     */
    @Test
    void aMeasuredHeightIsEnoughToPaintEveryRowOfTheTree() {
        java.util.Random random = new java.util.Random(101L);
        for (int iteration = 0; iteration < 250; iteration++) {
            View<Object> view = randomTree(random, 3);
            for (int cols = 1; cols <= 12; cols++) {
                int height = engine.intrinsicHeight(view, cols);
                Screen taller = paint(view, height + 6, cols);
                for (int row = height; row < height + 6; row++) {
                    assertThat(taller.plainLine(row))
                            .as("row %d below the measured height %d at %d columns", row, height, cols)
                            .isBlank();
                }
            }
        }
    }

    private static View<Object> randomTree(java.util.Random random, int depth) {
        if (depth <= 0) {
            return Views.text(randomText(random));
        }
        return switch (random.nextInt(8)) {
            case 0 -> Views.text(randomText(random));
            case 1 -> Views.blank();
            case 2 -> Views.column(Alignment.START, randomItems(random, depth));
            case 3 -> Views.row(Alignment.START, randomItems(random, depth));
            case 4 -> Views.padding(Insets.uniform(random.nextInt(2)),
                    Views.column(randomItems(random, depth)));
            case 5 -> Views.padding(Insets.uniform(1), Views.row(Alignment.START,
                    randomItems(random, depth)));
            case 6 -> Views.overlay(randomItems(random, depth), false);
            default -> Views.column(Alignment.CENTER, randomItems(random, depth));
        };
    }

    private static List<View<Object>> randomItems(java.util.Random random, int depth) {
        List<View<Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < random.nextInt(3); i++) {
            items.add(randomTree(random, depth - 1));
        }
        return items;
    }

    private static StyledText randomText(java.util.Random random) {
        String[] atoms = {"a", "bb", " ", "\t", "\n", "\u65E5", "\uD83D\uDE00", "e\u0301",
                "-", "cc dd"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1 + random.nextInt(4); i++) {
            text.append(atoms[random.nextInt(atoms.length)]);
        }
        return StyledText.plain(text.toString());
    }

    @Test
    void textWrapsAtWordBoundariesAndExplicitNewlines() {
        Screen screen = paint(Views.text(StyledText.plain("one two three\nfour")), 4, 7);
        assertThat(screen.plainLine(0)).isEqualTo("one two");
        assertThat(screen.plainLine(1)).isEqualTo("three");
        assertThat(screen.plainLine(2)).isEqualTo("four");
    }
}
