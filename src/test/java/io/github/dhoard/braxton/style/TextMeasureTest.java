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

import org.junit.jupiter.api.Test;

/** Grapheme segmentation and terminal-column measurement are separate contracts. */
class TextMeasureTest {

    private static final String FAMILY = new String(new int[] {
            0x1F469, 0x200D, 0x1F469, 0x200D, 0x1F467}, 0, 5);

    @Test
    void combiningMarksAndZwjSequencesAreSingleGraphemes() {
        assertThat(TextMeasure.graphemeCount("e\u0301")).isEqualTo(1);
        assertThat(TextMeasure.graphemeCount(FAMILY)).isEqualTo(1);
        assertThat(TextMeasure.graphemeCount("a" + FAMILY + "b")).isEqualTo(3);
    }

    @Test
    void asciiIsOneColumn() {
        assertThat(TextMeasure.columns("abc")).isEqualTo(3);
        assertThat(TextMeasure.columns("")).isZero();
    }

    @Test
    void styledValuesMeasureTheirWidestLineLikeStyledTextColumns() {
        StyledText value = StyledText.plain("ab\ncdef");
        assertThat(TextMeasure.columns(value)).isEqualTo(4);
        assertThat(TextMeasure.columns(value)).isEqualTo(value.columns());
    }

    @Test
    void eastAsianWideAndDefaultEmojiAreTwoColumns() {
        assertThat(TextMeasure.columns("\u65E5")).isEqualTo(2);
        assertThat(TextMeasure.columns("\uD83D\uDE00")).isEqualTo(2);
        assertThat(TextMeasure.columns(FAMILY)).isEqualTo(2);
    }

    @Test
    void variationSelectorsDecideEmojiPresentation() {
        assertThat(TextMeasure.columns("\u2764")).isEqualTo(1);
        assertThat(TextMeasure.columns("\u2764\uFE0F")).isEqualTo(2);
        assertThat(TextMeasure.columns("\u2764\uFE0E")).isEqualTo(1);
        assertThat(TextMeasure.columns("\u00A9")).isEqualTo(1);
    }

    @Test
    void combiningClusterMeasuresAsItsBase() {
        assertThat(TextMeasure.columns("e\u0301")).isEqualTo(1);
        assertThat(TextMeasure.columns("\u0301")).isEqualTo(1);
    }

    @Test
    void truncationNeverSplitsAClusterAndDropsAWideGlyphThatDoesNotFit() {
        assertThat(TextMeasure.truncate("abcd", 2)).isEqualTo("ab");
        assertThat(TextMeasure.truncate("a\u65E5", 2)).isEqualTo("a");
        assertThat(TextMeasure.truncate("\u65E5x", 1)).isEmpty();
        assertThat(TextMeasure.truncate("e\u0301x", 1)).isEqualTo("e\u0301");
    }

    @Test
    void boundariesFollowGraphemeClusters() {
        String text = "a" + FAMILY + "b";
        int afterEmoji = TextMeasure.boundaryAfter(text, 1);
        assertThat(afterEmoji).isEqualTo(1 + FAMILY.length());
        assertThat(TextMeasure.previousBoundary(text, afterEmoji)).isEqualTo(1);
        assertThat(TextMeasure.isBoundary(text, afterEmoji)).isTrue();
        assertThat(TextMeasure.isBoundary(text, 2)).isFalse();
    }

    @Test
    void fitPadsAndTruncatesToExactColumns() {
        assertThat(TextMeasure.fit("ab", 4)).isEqualTo("ab  ");
        assertThat(TextMeasure.fit("abcdef", 3)).isEqualTo("abc");
    }
}
