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

import java.util.List;

import org.junit.jupiter.api.Test;

/** Rendered markup is inert text and its tables keep one row width. */
class MarkupPropertyTest {

    @Test
    void renderedMarkupCarriesNoControlCharacters() {
        String xml = "<root><p>a\u009Bb</p><span style=\"bold\">cd</span>"
                + "<ul><li>e</li></ul><table><tr><td>f</td></tr></table></root>";
        String plain = Markup.parse(xml).plainText();
        // Raw escape characters are not valid XML and are rejected with a diagnostic instead.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Markup.parse("<root>\u001B[31m</root>"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid markup");
        for (char c : plain.toCharArray()) {
            assertThat(c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7F && !(c >= 0x80 && c <= 0x9F)))
                    .as("control 0x%x", (int) c).isTrue();
        }
    }

    @Test
    void tableRowsShareOneWidth() {
        String xml = "<root><table gap=\"1\"><tr><td>\u65E5</td><td>b</td></tr>"
                + "<tr><td>ccc</td><td>\u672C\u672C</td></tr></table></root>";
        List<String> lines = Markup.parse(xml).plainText().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(io.github.dhoard.braxton.style.TextMeasure::columns).distinct())
                .hasSize(1);
    }

    /**
     * The same grid with a cell that opens with a cluster that has no base of its own. The row is
     * painted as one cluster sequence, so that cluster joins the gap of the cell before it instead
     * of occupying a column: a cell padded from its own measurement made one row a column wider than
     * the next.
     */
    @Test
    void tableRowsShareOneWidthWhenACellOpensWithABaselessCluster() {
        for (String cell : List.of("\u0301", "\u0301x", "\uFE0Fx", "\u200D", "\u200B")) {
            for (int gap = 0; gap <= 2; gap++) {
                String xml = "<root><table gap=\"" + gap + "\"><tr><td>ccc</td>"
                        + "<td>" + cell + "</td></tr>"
                        + "<tr><td>ccc</td><td>\u672C\u672C</td></tr></table></root>";
                List<String> lines = Markup.parse(xml).plainText().lines().toList();
                assertThat(lines).as("cell 0x%x gap %d", (int) cell.charAt(0), gap).hasSize(2);
                assertThat(lines.stream()
                        .map(io.github.dhoard.braxton.style.TextMeasure::columns).distinct())
                        .as("rows of cell 0x%x gap %d", (int) cell.charAt(0), gap)
                        .hasSize(1);
            }
        }
    }

    @Test
    void aParentLinkAppliesToChildrenAndABackgroundIsInherited() {
        var linked = Markup.parse("<root><a href=\"https://x\"><span>one</span><span>two</span>"
                + "</a></root>");
        assertThat(linked.spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.link()).isEqualTo("https://x"));
        var background = Markup.parse("<root><span bg-color=\"#102030\"><span>inner</span>"
                + "</span></root>");
        assertThat(background.spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.style().background())
                        .isEqualTo(io.github.dhoard.braxton.style.Color.parse("#102030").orElseThrow()));
    }
}
