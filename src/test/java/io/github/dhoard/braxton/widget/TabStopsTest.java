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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/**
 * A tab expands to the stop of the clusters of the value's plain text. Where a style boundary splits
 * one cluster in two - a base and the combining mark or variation selector that continues it - the
 * pieces are charged as the cluster they join, never one by one: charging the pieces separately put
 * the stop a column early or late depending only on where the value happened to be styled.
 */
class TabStopsTest {

    @Test
    void aSpanBoundaryInsideAClusterDoesNotMoveTheStop() {
        Style bold = Style.NONE.withBold();
        // U+0301 adds no column to its base, so "a\u0301" is one column and the tab needs three
        // spaces; U+FE0F widens its base to two, so "a\uFE0F" is two columns and the tab needs two.
        assertExpanded("a\u0301\tb", "a\u0301   b",
                StyledText.concat(StyledText.of("a", Style.NONE),
                        StyledText.of("\u0301\tb", bold)));
        assertExpanded("a\uFE0F\tb", "a\uFE0F  b",
                StyledText.concat(StyledText.of("a", Style.NONE),
                        StyledText.of("\uFE0F\tb", bold)));
        // The control: a boundary that splits no cluster already expanded from the line start.
        assertExpanded("ab\tx", "ab  x",
                StyledText.concat(StyledText.of("ab", Style.NONE),
                        StyledText.of("\tx", bold)));
    }

    @Test
    void theExpandedValueKeepsEachSpansStyleAndLinkAcrossTheSplitCluster() {
        Style bold = Style.NONE.withBold();
        StyledText split = StyledText.concat(StyledText.of("a", Style.NONE),
                StyledText.of("\u0301\tb", bold, "https://example.test"));
        assertThat(TabStops.expand(split).spans()).containsExactly(
                new StyledText.Span("a", Style.NONE, null),
                new StyledText.Span("\u0301   b", bold, "https://example.test"));
    }

    @Test
    void aStopStartsOverAfterANewlineWhicheverSpanTheLineBreakIsIn() {
        Style bold = Style.NONE.withBold();
        StyledText split = StyledText.concat(StyledText.of("a\tb\n", Style.NONE),
                StyledText.of("\t\u0301x", bold));
        assertThat(TabStops.expand(split).plainText())
                .isEqualTo(TabStops.expand(StyledText.plain("a\tb\n\t\u0301x")).plainText())
                .isEqualTo("a   b\n    \u0301x");
    }

    private static void assertExpanded(String value, String expected, StyledText split) {
        assertThat(split.plainText()).as("the styling is the only difference").isEqualTo(value);
        StyledText expanded = TabStops.expand(split);
        assertThat(expanded.plainText()).as("expansion of \"%s\"", value)
                .isEqualTo(expected)
                .isEqualTo(TabStops.expand(StyledText.plain(value)).plainText());
        assertThat(TextMeasure.columns(expanded.plainText()))
                .as("columns of \"%s\"", value)
                .isEqualTo(TextMeasure.columns(expected));
    }
}
