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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ScreenRenderer;

/**
 * A frame reserves one cell per edge character, so every row a {@link Box} renders occupies exactly
 * the frame width. That holds while each row's content and label are measured as they will be
 * painted: one grapheme-cluster sequence that starts after the left frame cell. A cluster with no
 * base character of its own - a combining mark, a variation selector, a joiner, a byte-order mark -
 * joins the cell beside it instead of occupying its own, so padding computed from the content
 * measured on its own leaves the row short of the frame or one column past it.
 */
class BoxFrameWidthTest {

    private static final Border FRAME = new Border('+', '+', '+', '+', '-', '|');

    /**
     * Clusters that occupy no cell of their own when they are painted next to a frame character.
     * Each one is a legal, encodable character that {@code Span} keeps. The entry {@code "x"} plus
     * U+0301 is the control: that accent has its own base inside the content, so it already
     * occupied its own cell.
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

    /**
     * A base, then a zero-width control, then a cluster with no base of its own. The control ends the
     * plain-text cluster, so the mark after it is a cluster of its own that the measurement counts:
     * these are the values a join that only asks "was a cell painted" gets wrong.
     */
    private static final List<String> CONTROL_THEN_BASELESS = List.of(
            "\u200B\u0301",                              // control, then a mark
            "\u65E5\u200B\u0301",                       // base, control, then a mark
            "\uD83D\uDE00\u00AD\u0301",                // wide base, soft hyphen, then a mark
            "\uD83D\uDE00\u00AD\u00AD\u0308",         // wide base, two soft hyphens, then a mark
            "\uD83D\uDE00\u00AD\uFEFF\u20DD\u00AD",  // soft hyphen, BOM, enclosing circle
            "ab\uFEFF\u0301",                            // text, BOM, then a mark
            "ab\u00AD\u20DD");                           // text, soft hyphen, then a circle

    /** Every cluster shape the frame tests below paint, controls included. */
    private static final List<String> BASELESS_SHAPES = Stream.concat(
            BASELESS.stream(), CONTROL_THEN_BASELESS.stream()).toList();

    /**
     * Contents built from concatenated runs - how coloured text is assembled - whose run boundary
     * falls inside one cluster. A variation selector that opens a span measures no column of its
     * own, being a cluster with no base, but joined with the base before it it forces that cell to
     * emoji presentation: the joined cluster is two columns where the parts measure one. A budget
     * charged to each span in isolation under-counts the row by exactly the columns the join gains,
     * and the row leaves the frame one or two columns past the width it was rendered at.
     */
    private static final List<StyledText> SPLIT_PRESENTATION_SELECTOR = List.of(
            // The reported content: "\u2764\uFE0F a \u2764\uFE0F b", nine columns, four runs.
            StyledText.of("\u2764", Style.NONE.withBold())
                    .append("\uFE0F a ")
                    .append(StyledText.of("\u2764", Style.NONE.withBold()))
                    .append("\uFE0F b"),
            // A single join, which the frame's left cell has to absorb as well.
            StyledText.of("\u2764", Style.NONE.withBold()).append("\uFE0F a b"),
            // A selector that opens the content joins the left frame cell and widens it.
            StyledText.of("\uFE0F ", Style.NONE.withBold()).append("\uFE0F "),
            StyledText.of("\uFE0F", Style.NONE.withBold()).append("a b c"),
            // Joins in the safe direction, kept so the fix does not over-truncate: a joiner claims
            // no column and a combining mark measures its own, split or not.
            StyledText.of("\uD83D\uDC69", Style.NONE.withBold()).append("\u200D\uD83D\uDCBB"),
            StyledText.of("e", Style.NONE.withBold()).append("\u0301 a b"));

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

    private static List<String> rows(int width, String title, String content) {
        Box box = Box.of(content).border(FRAME).width(width);
        if (title != null) {
            box.title(title);
        }
        return List.of(box.render(width).plainText().split("\n", -1));
    }

    /** Every row of a framed box occupies the frame width, with or without a title. */
    private static void assertFrameKeepsWidth(String title, String content) {
        for (int width = 4; width <= 14; width++) {
            List<String> rows = rows(width, title, content);
            assertThat(rows).as("title %s content %s width %d", show(title), show(content), width)
                    .hasSize(3);
            for (String row : rows) {
                assertThat(TextMeasure.columns(row))
                        .as("title %s content %s width %d row [%s]",
                                show(title), show(content), width, show(row))
                        .isEqualTo(width);
            }
        }
    }

    @Test
    void aTitleWithABaselessClusterKeepsEveryFrameRowAtTheFrameWidth() {
        for (String title : BASELESS) {
            assertFrameKeepsWidth(title, "ab");
        }
    }

    @Test
    void aContentLineWithABaselessClusterKeepsEveryFrameRowAtTheFrameWidth() {
        for (String content : BASELESS) {
            assertFrameKeepsWidth(null, content);
        }
    }

    /**
     * The same row through the layout engine: the frame's right edge is one column, so the closing
     * corner of the top row sits above the closing bar of the row below it. A row the frame
     * reserved more columns for than it painted leaves that column empty and lands every cell after
     * the label left of the row above.
     */
    @Test
    void aTitledFrameDrawsItsRightEdgeInOneColumn() {
        for (String title : List.of("\u0301", "\uFE0Fx")) {
            List<String> painted = new ScreenRenderer().plainLines(
                    Box.of("ab").border(FRAME).title(title).width(8).view(8), new Viewport(3, 8));
            assertThat(painted).as("title %s", show(title))
                    .allSatisfy(row -> assertThat(TextMeasure.columns(row))
                            .as("painted row [%s]", show(row)).isEqualTo(8));
            assertThat(painted.get(0)).as("title %s", show(title)).endsWith("+");
            assertThat(painted.get(1)).as("title %s", show(title)).endsWith("|");
            assertThat(painted.get(2)).as("title %s", show(title)).isEqualTo("+------+");
        }
    }

    /** A title that cannot fit its reserved label space still leaves a complete top edge. */
    @Test
    void aTitleThatNeedsMoreColumnsThanItClaimsStillFitsItsLabelSpace() {
        for (String title : List.of("\uFE0F", "\uFE0Fx", "\uFE0F\uFE0Fx")) {
            for (int width = 4; width <= 8; width++) {
                List<String> rows = rows(width, title, "ab");
                assertThat(TextMeasure.columns(rows.get(0)))
                        .as("title %s width %d row [%s]", show(title), width, show(rows.get(0)))
                        .isEqualTo(width);
                assertThat(rows.get(0)).as("title %s width %d", show(title), width)
                        .startsWith("+").endsWith("+");
            }
        }
    }

    /** A frame with several content lines pads each one on its own terms. */
    @Test
    void everyLineOfATallFrameKeepsTheFrameWidth() {
        for (String content : List.of("\u0301\nab\n\uFE0Fx\n\u200D\nplain",
                "ab\n\u0301x\n\uFE0E\ncd")) {
            for (int width = 5; width <= 12; width++) {
                List<String> rows = rows(width, "\u0301", content);
                assertThat(rows).as("content %s width %d", show(content), width)
                        .hasSize(2 + content.split("\n", -1).length);
                for (String row : rows) {
                    assertThat(TextMeasure.columns(row))
                            .as("content %s width %d row [%s]", show(content), width, show(row))
                            .isEqualTo(width);
                }
            }
        }
    }

    /** A responsive frame (no fixed width) keeps the same rule for its own measured width. */
    @Test
    void aResponsiveFrameKeepsItsWidthWithABaselessCluster() {
        for (String content : BASELESS) {
            for (int width = 4; width <= 14; width++) {
                List<String> rows = List.of(
                        Box.of(content).border(FRAME).title("\u0301").render(width).plainText()
                                .split("\n", -1));
                for (String row : rows) {
                    assertThat(TextMeasure.columns(row))
                            .as("content %s width %d row [%s]", show(content), width, show(row))
                            .isEqualTo(Math.max(2, width));
                }
            }
        }
    }

    /** Cells a row was painted with; a wide cluster's second half is marked as a continuation. */
    private static List<String> paintedCells(RenderedScreen screen, int row) {
        List<String> cells = new ArrayList<>();
        for (int col = 0; col < screen.cols(); col++) {
            cells.add(screen.isContinuation(row, col)
                    ? "<continuation>" : screen.cell(row, col).text());
        }
        return cells;
    }

    /**
     * Checks the frame where it is painted rather than the text {@code render} produced. A row whose
     * cells lost a column still reads back at the frame width when its plain text is measured again,
     * because re-measuring joins the cells the paint path collapsed; only the column a cell landed
     * in shows that the right edge is inside the frame.
     */
    private static void assertFrameIsPaintedColumnByColumn(Box box, int width) {
        List<String> rendered = List.of(box.render(width).plainText().split("\n", -1));
        RenderedScreen screen = new ScreenRenderer().screen(box.view(width),
                new Viewport(rendered.size(), width));
        assertThat(screen.rows()).as("rows painted for %s", rendered).isEqualTo(rendered.size());
        for (int row = 0; row < rendered.size(); row++) {
            assertThat(paintedCells(screen, row)).as("row %d of %s", row, rendered)
                    .hasSize(width).doesNotContain("");
            assertThat(screen.isContinuation(row, width - 1))
                    .as("row %d of %s ends in a wide cluster's first half", row, rendered)
                    .isFalse();
            char edge = row == 0 ? FRAME.topRight()
                    : row == rendered.size() - 1 ? FRAME.bottomRight() : FRAME.vertical();
            assertThat(screen.cell(row, width - 1).text())
                    .as("right edge of row %d of %s painted as %s", row, rendered,
                            paintedCells(screen, row))
                    .isEqualTo(Border.glyph(edge));
        }
        assertThat(new LayoutEngine().intrinsicHeight(box.view(width), width))
                .as("rows measured for %s", rendered)
                .isEqualTo(rendered.size());
    }

    /**
     * A title whose own style differs from the frame's keeps its spans, so a mark reaches the paint
     * path in a span of its own. The row is still one cluster sequence - the mark joins the frame
     * cell beside it - so the painted frame must not gain the dotted circle's column and push its
     * right edge out of the rows {@code render} and the measurement reserved.
     */
    @Test
    void aStyledTitleWithABaselessClusterKeepsTheFrameWidthThroughPaint() {
        for (String title : BASELESS_SHAPES) {
            for (int width = 6; width <= 10; width++) {
                assertFrameIsPaintedColumnByColumn(Box.of("ab").border(FRAME)
                        .title(StyledText.of(title, Style.NONE.withBold())).width(width), width);
            }
        }
    }

    /**
     * The same boundary inside the content: a mark in a span of its own continues the cell before
     * it, so a frame row keeps the width the content was measured at instead of wrapping the right
     * edge onto a row of its own.
     */
    @Test
    void aStyledContentWithABaselessClusterKeepsTheFrameWidthThroughPaint() {
        Style bold = Style.NONE.withBold();
        List<StyledText> contents = List.of(
                StyledText.of("ab", Style.NONE).append(StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("e", Style.NONE), StyledText.of("\u0301x", bold)),
                StyledText.of("\u0301", bold),
                StyledText.of("\u0301x", bold),
                StyledText.of("ab\ncd", Style.NONE).append(StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("\t", Style.NONE),
                        StyledText.of("\u0301", bold)),
                StyledText.concat(StyledText.of("\u65E5", Style.NONE),
                        StyledText.of("\u200B\u0301", bold)));
        for (StyledText content : contents) {
            for (int width = 6; width <= 10; width++) {
                assertFrameIsPaintedColumnByColumn(
                        Box.of(content).border(FRAME).width(width), width);
            }
        }
    }

    /**
     * The columns a row may use are the columns of the joined content, so a run boundary inside one
     * cluster must not let the content claim columns the frame never reserved. Both the fixed-width
     * and the responsive frame are checked: the box width is the same promise either way.
     */
    @Test
    void contentWhosePresentationSelectorIsSplitFromItsBaseKeepsEveryFrameRowAtTheFrameWidth() {
        for (StyledText content : SPLIT_PRESENTATION_SELECTOR) {
            for (int width = 4; width <= 14; width++) {
                List<String> framed = List.of(Box.of(content).border(FRAME).width(width)
                        .render(width).plainText().split("\n", -1));
                assertThat(framed).as("content %s width %d", show(content.plainText()), width)
                        .hasSize(3);
                for (String row : framed) {
                    assertThat(TextMeasure.columns(row))
                            .as("content %s width %d row [%s]",
                                    show(content.plainText()), width, show(row))
                            .isEqualTo(width);
                }
                for (String row : List.of(Box.of(content).border(FRAME).render(width).plainText()
                        .split("\n", -1))) {
                    assertThat(TextMeasure.columns(row))
                            .as("responsive content %s width %d row [%s]",
                                    show(content.plainText()), width, show(row))
                            .isEqualTo(Math.max(2, width));
                }
            }
        }
    }

    /**
     * The same rows through the layout engine. A row the frame measured one column too narrow loses
     * its right edge cell and spends another wrapped row on the remainder, which replaces the box's
     * whole bottom edge with a stray frame character.
     */
    @Test
    void contentWhosePresentationSelectorIsSplitFromItsBaseKeepsTheFrameWidthThroughPaint() {
        for (StyledText content : SPLIT_PRESENTATION_SELECTOR) {
            for (int width = 6; width <= 14; width++) {
                assertFrameIsPaintedColumnByColumn(
                        Box.of(content).border(FRAME).width(width), width);
            }
        }
    }

    /** A frame with room for every joined cluster drops none of the content it was given. */
    @Test
    void aFrameWideEnoughForASplitPresentationSelectorKeepsAllItsContent() {
        // Nine columns of content, so an eleven-column frame keeps a column for each edge and pads
        // nothing: fitting the row is not a licence to clip the content.
        assertThat(List.of(Box.of(SPLIT_PRESENTATION_SELECTOR.getFirst()).border(FRAME).width(11)
                .render(11).plainText().split("\n", -1)))
                .containsExactly("+---------+",
                        "|\u2764\uFE0F a \u2764\uFE0F b|", "+---------+");
    }

    /**
     * Without a frame the fixed width still bounds the content it would have framed, measured from
     * the same joined clusters. No frame cell sits beside the content here, but its own run boundary
     * still splits the cluster the measurement and the paint path read as one.
     */
    @Test
    void anUnframedFixedWidthBoundsContentWhosePresentationSelectorIsSplitFromItsBase() {
        for (StyledText content : SPLIT_PRESENTATION_SELECTOR) {
            for (int width = 3; width <= 14; width++) {
                List<String> rows = List.of(Box.of(content).border(Border.NONE).width(width)
                        .render(width).plainText().split("\n", -1));
                assertThat(rows).as("content %s width %d", show(content.plainText()), width)
                        .hasSize(content.lines().size());
                for (String row : rows) {
                    assertThat(TextMeasure.columns(row))
                            .as("content %s width %d row [%s]",
                                    show(content.plainText()), width, show(row))
                            .isLessThanOrEqualTo(width);
                }
            }
        }
        // The reported second symptom: six columns of content in a five-column box keeps the five
        // columns that fit instead of every column it was given.
        assertThat(List.of(Box.of(StyledText.of("\u2764", Style.NONE.withBold())
                .append("\uFE0F a b")).border(Border.NONE).width(5).render(5).plainText()
                .split("\n", -1))).containsExactly("\u2764\uFE0F a ");
    }

    /**
     * A zero-width control ends the plain-text cluster before the mark, so the mark is a cluster of
     * its own and the measurement counts the dotted-circle column the paint path has to leave for
     * it. Joining the mark into the cell before the control would drop that column, and every cell
     * after it - the right edge included - would land left of where the frame was measured.
     */
    @Test
    void aControlBetweenABaseAndABaselessClusterKeepsTheFramesColumns() {
        for (String content : CONTROL_THEN_BASELESS) {
            for (int width = 4; width <= 10; width++) {
                assertFrameIsPaintedColumnByColumn(
                        Box.of(content).border(FRAME).width(width), width);
            }
        }
    }

    /** Ordinary titles and content keep the frames the existing catalog tests pin. */
    @Test
    void anOrdinaryFrameIsUnchanged() {
        assertThat(rows(8, "t", "ab")).containsExactly("+- t --+", "|ab    |", "+------+");
        assertThat(rows(8, null, "ab")).containsExactly("+------+", "|ab    |", "+------+");
        assertThat(rows(7, "\u65E5\u672C", "x"))
                .containsExactly("+- \u65E5 +", "|x    |", "+-----+");
    }
}
