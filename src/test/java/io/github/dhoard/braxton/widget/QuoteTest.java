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
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ScreenRenderer;

/** A quote keeps one width: a bar, a space, the expanded line, and padding to the widest line. */
class QuoteTest {

    private static final String BAR = "\u2502";

    @Test
    void everyRowIsTheBarSpaceContentAndPaddingToTheWidestLine() {
        Quote quote = Quote.of().append(StyledText.plain("ab")).append("c");
        assertThat(List.of(quote.render().plainText().split("\n", -1)))
                .containsExactly(BAR + " ab", BAR + " c ");
        assertThat(quote.render().lines()).allSatisfy(
                line -> assertThat(TextMeasure.columns(line.plainText())).isEqualTo(4));
    }

    /** Tabs expand to the same four-column stops the text view paints, so the block stays square. */
    @Test
    void aTabIsExpandedBeforeTheWidthIsMeasured() {
        Quote quote = Quote.of().append(StyledText.plain("a\tb")).append("c");
        List<String> rows = List.of(quote.render().plainText().split("\n", -1));
        assertThat(rows.get(0)).isEqualTo(BAR + " a   b");
        assertThat(rows.get(1)).isEqualTo(BAR + " c    ");
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain("\t"));
    }

    /**
     * The bar and its space are prefix cells like any other, so styling the content cannot move a
     * stop: a cluster a span boundary splits expands exactly as its joined plain text does.
     */
    @Test
    void aTabAfterAClusterASpanBoundarySplitKeepsThePlainRows() {
        Style bold = Style.NONE.withBold();
        assertSplitRowsAreThePlainRows("a\u0301\tb", BAR + " a\u0301   b",
                StyledText.concat(StyledText.of("a", Style.NONE),
                        StyledText.of("\u0301\tb", bold)));
        assertSplitRowsAreThePlainRows("a\uFE0F\tb", BAR + " a\uFE0F  b",
                StyledText.concat(StyledText.of("a", Style.NONE),
                        StyledText.of("\uFE0F\tb", bold)));
    }

    private static void assertSplitRowsAreThePlainRows(String value, String expected,
            StyledText split) {
        assertThat(split.plainText()).as("the same plain text").isEqualTo(value);
        assertThat(List.of(Quote.of().append(split).render().plainText().split("\n", -1)))
                .as("rows of \"%s\"", value)
                .isEqualTo(List.of(Quote.of().append(StyledText.plain(value))
                        .render().plainText().split("\n", -1)))
                .containsExactly(expected);
        assertThat(TextMeasure.columns(expected)).as("columns of \"%s\"", value).isEqualTo(7);
    }

    /**
     * The bar is a one-line prefix on every quoted line, so a break in it cannot split that line
     * into extra rows or merge its own characters into the content.
     */
    @Test
    void aBarCannotSplitAQuotedLineAcrossRows() {
        for (String bar : List.of("a\nb", "a\rb", "a\r\nb")) {
            Quote quote = Quote.of().bar(bar).append("x");
            assertThat(List.of(quote.render().plainText().split("\n", -1)))
                    .as("rendered rows for bar %s", bar.replace("\n", "\\n").replace("\r", "\\r"))
                    .containsExactly("a b x");
            // The painted grid and the measured height are the oracle: one appended line is one
            // row, and the bar's characters stay in the bar.
            assertThat(new ScreenRenderer().plainLines(quote.view(), new Viewport(1, 40)))
                    .as("painted rows for bar %s", bar.replace("\n", "\\n").replace("\r", "\\r"))
                    .containsExactly("a b x");
            assertThat(new LayoutEngine().intrinsicHeight(quote.view(), 40))
                    .as("measured rows for bar %s", bar.replace("\n", "\\n").replace("\r", "\\r"))
                    .isEqualTo(1);
        }
        // The flattened bar is still one prefix on each of several quoted lines, and the block
        // keeps its single width.
        assertThat(List.of(Quote.of().bar(">\n>").append("ab").append("c")
                .render().plainText().split("\n", -1)))
                .containsExactly("> > ab", "> > c ");
    }

    @Test
    void theBackgroundCoversTheBarSpaceContentAndPadding() {
        Color background = Color.parse("#102030").orElseThrow();
        Quote quote = Quote.of()
                .append(StyledText.of("x", Style.NONE.withBold()))
                .append("long line")
                .background(background);
        StyledText rendered = quote.render();
        List<StyledText.Span> spans = rendered.spans();
        // The bar carries the border colour, so the background starts at the space after it.
        assertThat(spans.get(0).text()).isEqualTo(BAR);
        for (StyledText.Span span : spans.subList(1, spans.size())) {
            if (span.text().contains("\n")) {
                continue;
            }
            assertThat(span.style().background()).as("background on [%s]", span.text())
                    .isEqualTo(background);
        }
        assertThat(spans.get(1).text()).isEqualTo(" ");
    }

    @Test
    void theBorderColourAppliesToTheBarOnly() {
        Color border = Color.parse("#00ff00").orElseThrow();
        Quote quote = Quote.of().append("x").border(border);
        List<StyledText.Span> spans = quote.render().spans();
        assertThat(spans.get(0).text()).isEqualTo(BAR);
        assertThat(spans.get(0).style().foreground()).isEqualTo(border);
        for (StyledText.Span span : spans.subList(1, spans.size())) {
            assertThat(span.style().foreground()).as("border colour on [%s]", span.text())
                    .isNull();
        }
    }

    @Test
    void aBlankOrNullBarFallsBackToASpaceAndNullContentIsIgnored() {
        // A blank bar becomes one space, so the row is bar, space, content.
        assertThat(Quote.of().bar("").append("x").render().plainText()).isEqualTo("  x");
        assertThat(Quote.of().bar(null).append(StyledText.plain("x")).render().plainText())
                .isEqualTo("  x");
        assertThat(Quote.of().bar(">>").append("x").render().plainText()).isEqualTo(">> x");
        assertThat(Quote.of().append((StyledText) null).render().plainText()).isEmpty();
        assertThat(List.of(Quote.of().append("\n").render().plainText().split("\n", -1)))
                .hasSize(2);
    }

    /** Every rendered row has the same painted width, whatever the content holds. */
    @Test
    void everyRenderedRowHasTheSameWidth() {
        String[] atoms = {"a", "bc", " ", "\t", "\u65E5", "\uD83D\uDE00", "e\u0301", "\u2502"};
        Random random = new Random(31L);
        for (int iteration = 0; iteration < 300; iteration++) {
            Quote quote = Quote.of();
            List<String> expected = new ArrayList<>();
            int lines = 1 + random.nextInt(4);
            for (int line = 0; line < lines; line++) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < random.nextInt(4); i++) {
                    text.append(atoms[random.nextInt(atoms.length)]);
                }
                quote.append(StyledText.plain(text.toString()));
                expected.add(TabStops.expand(StyledText.plain(text.toString())).plainText());
            }
            List<StyledText> rendered = quote.render().lines();
            assertThat(rendered).hasSize(lines);
            int width = TextMeasure.columns(rendered.get(0).plainText());
            int barWidth = TextMeasure.columns(BAR);
            for (int line = 0; line < lines; line++) {
                String row = rendered.get(line).plainText();
                assertThat(TextMeasure.columns(row)).as("row %d of %s", line, expected)
                        .isEqualTo(width);
                assertThat(row).startsWith(BAR + " " + expected.get(line));
                assertThat(TextMeasure.columns(row.substring(barWidth + 1)))
                        .isEqualTo(width - barWidth - 1);
            }
        }
    }

    /**
     * A line that opens with a cluster that has no base of its own - a combining mark, a variation
     * selector, a joiner - is painted inside the bar and the space after it: the mark joins that
     * space instead of claiming a column, and a selector there widens it by one. Padding a line
     * from its own measurement leaves that row short of the block or one column past it.
     */
    @Test
    void aLineOpeningWithABaselessClusterKeepsTheBlockWidth() {
        for (String content : List.of("\u0301", "\u0301x", "\uFE0F", "\uFE0Fx", "\uFE0Ex",
                "\u200D", "\u200B", "\uFEFFx", "\u00ADx")) {
            Quote quote = Quote.of()
                    .append(StyledText.plain("ab"))
                    .append(StyledText.plain(content))
                    .background(Color.parse("#102030").orElseThrow());
            List<StyledText> rows = quote.render().lines();
            assertThat(rows).as("rows for content %s", show(content)).hasSize(2);
            // The line with its own base is the control: the block's width is its four columns.
            assertThat(rows.get(0).plainText()).as("control row for %s", show(content))
                    .isEqualTo(BAR + " ab");
            for (int row = 0; row < rows.size(); row++) {
                String text = rows.get(row).plainText();
                assertThat(TextMeasure.columns(text))
                        .as("columns of row %d for content %s", row, show(content))
                        .isEqualTo(4);
            }
            // The quoted content survives the padding unchanged, in the row it is painted in.
            assertThat(rows.get(1).plainText()).as("row for content %s", show(content))
                    .startsWith(BAR + " " + content);
        }
    }

    /**
     * The pad the row really needs: a mark that joins the space after the bar still takes the
     * column the block owes that row, and a selector that widens the space leaves no pad to take.
     */
    @Test
    void thePadOfARowIsChargedInsideTheBarAndItsSpace() {
        Color background = Color.parse("#102030").orElseThrow();
        Quote mark = Quote.of().append(StyledText.plain("ab"))
                .append(StyledText.plain("\u0301x")).background(background);
        assertThat(List.of(mark.render().plainText().split("\n", -1)))
                .containsExactly(BAR + " ab", BAR + " \u0301x ");
        Quote selector = Quote.of().append(StyledText.plain("ab"))
                .append(StyledText.plain("\uFE0Fx")).background(background);
        assertThat(List.of(selector.render().plainText().split("\n", -1)))
                .containsExactly(BAR + " ab", BAR + " \uFE0Fx");
    }

    /**
     * The block where it is painted rather than in the text {@code render} produced. Re-measuring a
     * row's own plain text hides a column the paint path gave back to the space after the bar, so
     * the cells are the oracle: the background band of every row has to end in the same column.
     */
    @Test
    void everyPaintedRowCoversTheSameColumnsWhenALineOpensWithABaselessCluster() {
        Color background = Color.parse("#102030").orElseThrow();
        // The mark in a span of its own is how styled and pasted content arrives, and the quote
        // keeps each span's own style, so the mark reaches the paint path split from the bar.
        List<StyledText> contents = List.of(
                StyledText.plain("\u0301x"),
                StyledText.plain("\uFE0Fx"),
                StyledText.of("\u0301", Style.NONE.withBold()).append(StyledText.plain("x")));
        for (StyledText content : contents) {
            Quote quote = Quote.of().append(StyledText.plain("ab"))
                    .append(content).background(background);
            List<StyledText> rows = quote.render().lines();
            int width = TextMeasure.columns(rows.get(0).plainText());
            assertThat(width).as("block width for %s", show(content.plainText())).isEqualTo(4);
            assertThat(new LayoutEngine().intrinsicWidth(quote.view(), width + 8))
                    .as("width measured for %s", show(content.plainText())).isEqualTo(width);
            RenderedScreen screen = new ScreenRenderer().screen(quote.view(),
                    new Viewport(rows.size(), width + 2));
            assertThat(screen.rows()).as("painted rows for %s", show(content.plainText()))
                    .isEqualTo(rows.size());
            for (int row = 0; row < rows.size(); row++) {
                assertThat(paintedColumns(screen, row))
                        .as("painted columns of row %d for %s, painted as %s",
                                row, show(content.plainText()), paintedCells(screen, row))
                        .isEqualTo(width);
                // The band the background paints ends on the block's last column and no further.
                assertThat(lastPaintedColumn(screen, row))
                        .as("last painted column of row %d for %s, painted as %s",
                                row, show(content.plainText()), paintedCells(screen, row))
                        .isEqualTo(width - 1);
                for (int col = 1; col < width; col++) {
                    if (screen.isContinuation(row, col)) {
                        continue;
                    }
                    assertThat(screen.cell(row, col).style().background())
                            .as("background at row %d column %d for %s",
                                    row, col, show(content.plainText()))
                            .isEqualTo(background);
                }
            }
        }
    }

    /**
     * The same invariant over content that opens lines with baseless clusters, as NFD-normalised
     * text and text copied out of a document do.
     */
    @Test
    void everyRenderedRowKeepsOneWidthWhenLinesOpenWithBaselessClusters() {
        String[] atoms = {"a", "bc", " ", "\u65E5", "\uD83D\uDE00", "e\u0301",
                "\u0301", "\uFE0F", "\uFE0E", "\u200D", "\u200B", "\u2502"};
        Random random = new Random(17L);
        for (int iteration = 0; iteration < 200; iteration++) {
            Quote quote = Quote.of().background(Color.parse("#102030").orElseThrow());
            List<String> expected = new ArrayList<>();
            int lines = 1 + random.nextInt(3);
            for (int line = 0; line < lines; line++) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < 1 + random.nextInt(3); i++) {
                    text.append(atoms[random.nextInt(atoms.length)]);
                }
                quote.append(StyledText.plain(text.toString()));
                expected.add(show(text.toString()));
            }
            List<StyledText> rendered = quote.render().lines();
            assertThat(rendered).hasSize(lines);
            int width = TextMeasure.columns(rendered.get(0).plainText());
            for (int line = 0; line < lines; line++) {
                assertThat(TextMeasure.columns(rendered.get(line).plainText()))
                        .as("row %d of %s", line, expected).isEqualTo(width);
            }
            RenderedScreen screen = new ScreenRenderer().screen(quote.view(),
                    new Viewport(lines, width + 2));
            assertThat(screen.rows()).as("painted rows of %s", expected).isEqualTo(lines);
            for (int row = 0; row < lines; row++) {
                assertThat(paintedColumns(screen, row))
                        .as("painted columns of row %d of %s", row, expected).isEqualTo(width);
            }
        }
    }

    /** Columns a painted row actually occupies: a wide cell counts two, its continuation none. */
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

    /** The last column a row paints in, so the block's edge has to land in one column. */
    private static int lastPaintedColumn(RenderedScreen screen, int row) {
        for (int col = screen.cols() - 1; col >= 0; col--) {
            if (screen.isContinuation(row, col) || !screen.cell(row, col).isEmpty()) {
                return col;
            }
        }
        return -1;
    }

    /** The cells a row was painted with, for a readable failure message. */
    private static List<String> paintedCells(RenderedScreen screen, int row) {
        List<String> cells = new ArrayList<>();
        for (int col = 0; col < screen.cols(); col++) {
            cells.add(screen.isContinuation(row, col)
                    ? "<continuation>" : show(screen.cell(row, col).text()));
        }
        return cells;
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

    @Test
    void theBlockPaddingUsesThePinnedPolicyWhateverTheRendererPolicyIs() {
        // Documented limitation: widget padding is measured with the pinned policy, so a renderer
        // built with another policy clips and wraps differently but does not change the padding the
        // widget already baked in. Pin that so making widgets policy-aware is a deliberate change.
        Quote quote = Quote.of().append("\u65E5").append("ab");
        java.util.List<String> pinned = new io.github.dhoard.braxton.view.ScreenRenderer()
                .plainLines(quote.view(), new io.github.dhoard.braxton.layout.Viewport(2, 10));
        java.util.List<String> oneColumn = new io.github.dhoard.braxton.view.ScreenRenderer(
                io.github.dhoard.braxton.style.WidthPolicy.ONE_COLUMN)
                .plainLines(quote.view(), new io.github.dhoard.braxton.layout.Viewport(2, 10));
        assertThat(oneColumn).as("the widget's own widths do not follow the renderer policy")
                .isEqualTo(pinned);
        assertThat(pinned.stream().map(TextMeasure::columns).distinct())
                .as("under the pinned policy both rows are the same width").hasSize(1);
        assertThat(oneColumn.stream()
                .map(line -> TextMeasure.columns(line,
                        io.github.dhoard.braxton.style.WidthPolicy.ONE_COLUMN))
                .distinct())
                .as("under a one-column policy the mixed row is short by the wide glyph")
                .hasSize(2);
    }
}
