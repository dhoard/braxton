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

package io.github.dhoard.braxton.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** One complete-string policy converts LF, CRLF, and lone CR without doubling a CRLF pair. */
class LineBreaksTest {

    @Test
    void normalizeConvertsEveryBreakFormToOneLfAndPreservesTabs() {
        for (String raw : List.of("a\nb", "a\r\nb", "a\rb")) {
            assertThat(LineBreaks.normalize(raw))
                    .as("normalize %s", raw.replace("\n", "\\n").replace("\r", "\\r"))
                    .isEqualTo("a\nb");
        }
        assertThat(LineBreaks.normalize("a\tb")).isEqualTo("a\tb");
        assertThat(LineBreaks.normalize("\r\n")).isEqualTo("\n");
        assertThat(LineBreaks.normalize("\r")).isEqualTo("\n");
        assertThat(LineBreaks.normalize("\n")).isEqualTo("\n");
    }

    @Test
    void singleRowConvertsEveryBreakFormToOneSpaceAndPreservesTabs() {
        for (String raw : List.of("a\nb", "a\r\nb", "a\rb")) {
            assertThat(LineBreaks.singleRow(raw))
                    .as("singleRow %s", raw.replace("\n", "\\n").replace("\r", "\\r"))
                    .isEqualTo("a b");
        }
        assertThat(LineBreaks.singleRow("a\tb")).isEqualTo("a\tb");
        assertThat(LineBreaks.singleRow("\r\n")).isEqualTo(" ");
    }

    @Test
    void singleRowAndTabsAlsoTurnsATabIntoOneSpace() {
        for (String raw : List.of("a\nb", "a\r\nb", "a\rb")) {
            assertThat(LineBreaks.singleRowAndTabs(raw + "\tc")).isEqualTo("a b c");
        }
        assertThat(LineBreaks.singleRowAndTabs("a\tb")).isEqualTo("a b");
    }

    @Test
    void consecutiveAndTrailingBreaksStayDistinct() {
        // Two logical breaks stay two separators; collapsing them would silently join paragraphs.
        assertThat(LineBreaks.normalize("a\r\rb")).isEqualTo("a\n\nb");
        assertThat(LineBreaks.normalize("a\r\n\nb")).isEqualTo("a\n\nb");
        assertThat(LineBreaks.normalize("a\r\nb\r\n")).isEqualTo("a\nb\n");
        assertThat(LineBreaks.singleRow("a\r\rb")).isEqualTo("a  b");
        assertThat(LineBreaks.singleRow("a\r\n\nb")).isEqualTo("a  b");
        assertThat(LineBreaks.singleRow("a\r\n")).isEqualTo("a ");
    }

    @Test
    void nullAndEmptyBecomeEmpty() {
        for (String value : new String[] {null, ""}) {
            assertThat(LineBreaks.normalize(value)).isEmpty();
            assertThat(LineBreaks.singleRow(value)).isEmpty();
            assertThat(LineBreaks.singleRowAndTabs(value)).isEmpty();
        }
    }

    @Test
    void conversionIsNotSanitization() {
        // Other controls and unpaired surrogates survive: StyledText remains the sanitization
        // boundary, and this helper must not grow a second, divergent policy.
        String raw = "a\u0000b\u001B[31m\uD800c\u007Fd";
        assertThat(LineBreaks.normalize(raw)).isEqualTo(raw);
        assertThat(LineBreaks.singleRow(raw)).isEqualTo(raw);
        assertThat(LineBreaks.singleRowAndTabs(raw)).isEqualTo(raw);
        // A tab is the only additional character the flat-tab variant converts.
        assertThat(LineBreaks.singleRowAndTabs("a\u0000b\u001B[31m\tc")).isEqualTo("a\u0000b\u001B[31m c");
    }

    @Test
    void everyOperationIsIdempotentAndLeavesNoCarriageReturn() {
        String[] atoms = {"a", "\n", "\r\n", "\r", "\t", " ", "\u65E5", "\uD83D\uDE00", "e\u0301"};
        Random random = new Random(29L);
        for (int iteration = 0; iteration < 500; iteration++) {
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < random.nextInt(12); i++) {
                raw.append(atoms[random.nextInt(atoms.length)]);
            }
            String text = raw.toString();
            String normalized = LineBreaks.normalize(text);
            String singleRow = LineBreaks.singleRow(text);
            String flattest = LineBreaks.singleRowAndTabs(text);
            assertThat(normalized).as("no CR in normalize of [%s]", text).doesNotContain("\r");
            assertThat(singleRow).as("no break in singleRow of [%s]", text)
                    .doesNotContain("\r").doesNotContain("\n");
            assertThat(flattest).as("no break or tab in singleRowAndTabs of [%s]", text)
                    .doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
            assertThat(LineBreaks.normalize(normalized)).isEqualTo(normalized);
            assertThat(LineBreaks.singleRow(singleRow)).isEqualTo(singleRow);
            assertThat(LineBreaks.singleRowAndTabs(flattest)).isEqualTo(flattest);
        }
    }

    @Test
    void independentCallsAtACrLfSeamAreNotEquivalentToPreparingTheWholeValue() {
        // The documented obligation: assemble the complete raw value first. Stateless calls cannot
        // know that the trailing CR and the next chunk's LF were one break, so callers must not
        // present per-chunk normalization as an equivalent alternative.
        assertThat(LineBreaks.normalize("a\r") + LineBreaks.normalize("\nb")).isEqualTo("a\n\nb");
        assertThat(LineBreaks.normalize("a\r\nb")).isEqualTo("a\nb");
        assertThat(LineBreaks.singleRow("a\r") + LineBreaks.singleRow("\nb")).isEqualTo("a  b");
        assertThat(LineBreaks.singleRow("a\r\nb")).isEqualTo("a b");
    }

    @Test
    void aLargeInputIsConvertedInOnePassWithoutChangingItsShape() {
        // Linearity is argued from the single forward scan in convert(); this smoke test keeps the
        // conversion honest for a value far larger than a terminal row.
        int lines = 20_000;
        String raw = ("a\r\nb\rc\n").repeat(lines);
        String normalized = LineBreaks.normalize(raw);
        assertThat(normalized).isEqualTo(("a\nb\nc\n").repeat(lines));
        assertThat(normalized.length()).isEqualTo(raw.length() - lines);
        String flat = LineBreaks.singleRow(raw);
        assertThat(flat).isEqualTo(("a b c ").repeat(lines));
        assertThat(flat).doesNotContain("\r").doesNotContain("\n");
    }
}
