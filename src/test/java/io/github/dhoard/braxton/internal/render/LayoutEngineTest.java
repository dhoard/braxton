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
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** Deterministic layout: sizing, clipping, overlays, and wide-cell bookkeeping. */
class LayoutEngineTest {

    private final LayoutEngine engine = new LayoutEngine();

    private Screen paint(View<?> view, int rows, int cols) {
        return engine.layout(view, new Rect(0, 0, rows, cols)).screen();
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
    void anEndAlignedColumnKeepsAGrowChildsContentWidth() {
        View<?> view = Views.column(Alignment.END, List.of(Views.grow(Views.text("mid"))));
        Screen screen = paint(view, 1, 10);
        assertThat(screen.plainLine(0)).isEqualTo("       mid");
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

    @Test
    void textWrapsAtWordBoundariesAndExplicitNewlines() {
        Screen screen = paint(Views.text(StyledText.plain("one two three\nfour")), 4, 7);
        assertThat(screen.plainLine(0)).isEqualTo("one two");
        assertThat(screen.plainLine(1)).isEqualTo("three");
        assertThat(screen.plainLine(2)).isEqualTo("four");
    }
}
