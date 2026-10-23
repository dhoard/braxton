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

package examples.harness.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Transcript text wrapping: widths, words, and indentation. */
class HarnessRenderTest {

    @Test
    void sanitizingUsesOneLineBreakPolicyAndExpandsTabs() {
        // The harness used to normalize before PasteSanitizer; delegating keeps one policy for the
        // three break forms and still preserves blank lines and expands tabs.
        for (String raw : List.of("a\nb", "a\r\nb", "a\rb")) {
            assertThat(HarnessRender.sanitize(raw))
                    .as("sanitize %s", raw.replace("\n", "\\n").replace("\r", "\\r"))
                    .isEqualTo("a\nb");
        }
        assertThat(HarnessRender.sanitize("a\tb")).isEqualTo("a    b");
        assertThat(HarnessRender.sanitize("a\r\u001B[31m\nb")).isEqualTo("a\n\nb");
        assertThat(HarnessRender.sanitize("a\rb")).doesNotContain("\r");
    }

    @Test
    void wrappingKeepsLeadingIndentationWithTheFirstWord() {
        List<String> lines = HarnessRender.wrap("  indented", 6);
        assertThat(lines).containsExactly("  inde", "nted");
        assertThat(lines).noneMatch(String::isEmpty);
        assertThat(lines).allSatisfy(line ->
                assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(6));
    }

    @Test
    void displayFormattersStepAtTheirBoundaries() {
        assertThat(HarnessRender.formatTokens(0)).isEqualTo("0");
        assertThat(HarnessRender.formatTokens(999)).isEqualTo("999");
        assertThat(HarnessRender.formatTokens(1_000)).isEqualTo("1.0k");
        assertThat(HarnessRender.formatTokens(9_999)).isEqualTo("10.0k");
        assertThat(HarnessRender.formatTokens(10_000)).isEqualTo("10k");
        assertThat(HarnessRender.formatTokens(999_499)).isEqualTo("999k");
        assertThat(HarnessRender.formatTokens(999_500)).isEqualTo("1.0M");
        assertThat(HarnessRender.formatTokens(9_999_999)).isEqualTo("10.0M");
        assertThat(HarnessRender.formatTokens(10_000_000)).isEqualTo("10M");

        assertThat(HarnessRender.formatElapsed(0)).isEqualTo("0s");
        assertThat(HarnessRender.formatElapsed(65)).isEqualTo("1m 5s");
        assertThat(HarnessRender.formatElapsed(3_605)).isEqualTo("1h 0m 5s");
        assertThat(HarnessRender.formatElapsed(-5)).as("a negative elapsed reads as zero").isEqualTo("0s");

        assertThat(HarnessRender.formatRate(null)).isEqualTo("- t/s");
        assertThat(HarnessRender.formatRate(Double.NaN)).isEqualTo("- t/s");
        assertThat(HarnessRender.formatRate(-1.0)).isEqualTo("- t/s");
        assertThat(HarnessRender.formatRate(63.6)).isEqualTo("64 t/s");
    }

    @Test
    void wrappingAnOverlongIndentKeepsTheLineBlankInsteadOfInventingAnEllipsis() {
        // A whitespace-only line must stay blank. Clipping the indent keeps its width; the old
        // truncate() call turned it into " …", an ellipsis that was never in the text.
        assertThat(HarnessRender.wrap("    ", 2)).containsExactly("");
        assertThat(HarnessRender.wrap("a\n    \nb", 2)).containsExactly("a", "", "b");
        for (int width = 1; width <= 8; width++) {
            int limit = width;
            List<String> lines = HarnessRender.wrap(" ".repeat(12), width);
            assertThat(lines).as("width %d", width).noneMatch(line -> line.contains("\u2026"));
            assertThat(lines).allSatisfy(line ->
                    assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(limit));
        }
    }

    @Test
    void wrappingAnIndentedLineNeverEmitsABlankLine() {
        assertThat(HarnessRender.wrap("     ab", 6)).containsExactly("ab");
        assertThat(HarnessRender.wrap("  ab", 3)).containsExactly("ab");
        for (int width = 1; width <= 8; width++) {
            int limit = width;
            List<String> lines = HarnessRender.wrap("       x", width);
            assertThat(lines).as("width %d", width).noneMatch(String::isEmpty);
            assertThat(lines).allSatisfy(line ->
                    assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(limit));
        }
    }

    @Test
    void aSelectorAfterASpaceKeepsTheCellsItsRowMeasures() {
        // A variation selector with no base of its own - one whose base glyph was lost when the text
        // was copied - continues the space in front of it and makes that cell two columns wide.
        // The run after the space was measured on its own, which charges the selector nothing, so
        // the row came back a cell wider than the width it wrapped to and pad() cut the "CD" behind
        // it with an ellipsis that was never in the text.
        assertThat(HarnessRender.wrap("a \uFE0FCD", 4)).containsExactly("a \uFE0F", "CD");
        assertThat(HarnessRender.wrap("a \uFE0FCD", 5)).containsExactly("a \uFE0FCD");
        for (int width = 1; width <= 8; width++) {
            // The widest cell of this text is the space the selector widens: two columns.
            int bound = Math.max(width, 2);
            List<String> lines = HarnessRender.wrap("a \uFE0FCD", width);
            assertThat(lines).as("width %d", width).allSatisfy(line ->
                    assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(bound));
            assertThat(String.join("", lines).replace(" ", "")).as("width %d", width)
                    .isEqualTo("a\uFE0FCD");
            assertThat(lines).as("width %d", width).noneMatch(line -> line.contains("\u2026"));
        }
    }

    @Test
    void aSelectorAfterIndentationKeepsTheCellsItsRowMeasures() {
        // The same under-count after an indent or an expanded tab: the indent is not content, but the
        // cell it ends with is still the one the selector widens.
        assertThat(HarnessRender.wrap("  \uFE0F", 2)).allSatisfy(line ->
                assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(2));
        for (int width = 1; width <= 8; width++) {
            int bound = Math.max(width, 2);
            List<String> lines = HarnessRender.wrap("    \uFE0Fx", width);
            assertThat(lines).as("width %d", width).allSatisfy(line ->
                    assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(bound));
            assertThat(String.join("", lines).replace(" ", "")).as("width %d", width)
                    .isEqualTo("\uFE0Fx");
            assertThat(lines).as("width %d", width).noneMatch(line -> line.contains("\u2026"));
        }
    }
}
