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
    void wrappingKeepsLeadingIndentationWithTheFirstWord() {
        List<String> lines = HarnessRender.wrap("  indented", 6);
        assertThat(lines).containsExactly("  inde", "nted");
        assertThat(lines).noneMatch(String::isEmpty);
        assertThat(lines).allSatisfy(line ->
                assertThat(HarnessRender.visibleLength(line)).isLessThanOrEqualTo(6));
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
}
