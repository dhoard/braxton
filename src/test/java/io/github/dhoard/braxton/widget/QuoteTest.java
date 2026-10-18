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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

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
}
