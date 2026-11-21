/*
 * Copyright (c) 2026-present Douglas Hoard
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
import org.junit.jupiter.api.Test;

/**
 * The wrapping API the renderer and the widgets share.
 */
class TextWrapTest {

    private static final Style BOLD = Style.NONE.withBold();

    private static List<String> plain(List<StyledText> lines) {
        return lines.stream().map(StyledText::plainText).toList();
    }

    @Test
    void uniformRunsWrapLikeStyledRunsAcrossEveryBreakAndLimit() {
        java.util.Random random = new java.util.Random(611);
        String alphabet = "ab  \n\u2500\u2502";
        for (int sample = 0; sample < 300; sample++) {
            StringBuilder raw = new StringBuilder();
            StyledText split = StyledText.empty();
            for (int i = 0; i < 40; i++) {
                String c = String.valueOf(alphabet.charAt(random.nextInt(alphabet.length())));
                raw.append(c);
                split = split.append(StyledText.of(c, i % 2 == 0 ? Style.NONE : Style.NONE.withBold()));
            }
            for (int width = 1; width <= 9; width++) {
                int limit = 1 + random.nextInt(12);
                assertThat(TextWrap.wrap(StyledText.plain(raw.toString()), WidthPolicy.PINNED, width, limit).stream()
                                .map(StyledText::plainText)
                                .toList())
                        .isEqualTo(TextWrap.wrap(split, WidthPolicy.PINNED, width, limit).stream()
                                .map(StyledText::plainText)
                                .toList());
            }
        }
    }

    @Test
    void wrapsToTheWidthWithoutLosingAStyledRun() {
        StyledText value = StyledText.of("hello ", Style.NONE).append(StyledText.of("world", BOLD));

        List<StyledText> lines = TextWrap.wrap(value, WidthPolicy.PINNED, 6);

        // The space that ends the first line fits inside the six columns, so it stays on that line.
        assertThat(plain(lines)).containsExactly("hello ", "world");
        assertThat(lines.get(1).spans()).extracting(StyledText.Span::style).containsExactly(BOLD);
    }

    @Test
    void anExplicitNewlineOpensALineOfItsOwn() {
        List<StyledText> lines = TextWrap.wrap(StyledText.of("a\nb", Style.NONE), WidthPolicy.PINNED, 8);

        assertThat(plain(lines)).containsExactly("a", "b");
        assertThat(TextWrap.wrap(StyledText.of("a\n\nb", Style.NONE), WidthPolicy.PINNED, 8))
                .extracting(StyledText::plainText)
                .containsExactly("a", "", "b");
    }

    @Test
    void theLineLimitStopsTheWrapWhereItIsReached() {
        List<StyledText> lines = TextWrap.wrap(StyledText.of("aaa bbb ccc", Style.NONE), WidthPolicy.PINNED, 3, 2);

        assertThat(plain(lines)).containsExactly("aaa", "bbb");
        assertThat(TextWrap.wrap(StyledText.of("aaa", Style.NONE), WidthPolicy.PINNED, 3, 0))
                .isEmpty();
    }

    @Test
    void nothingToWrapProducesNoLines() {
        assertThat(TextWrap.wrap(null, WidthPolicy.PINNED, 4)).isEmpty();
        assertThat(TextWrap.wrap(StyledText.of("abc", Style.NONE), WidthPolicy.PINNED, 0))
                .isEmpty();
        assertThat(TextWrap.wrap(StyledText.of("abc", Style.NONE), WidthPolicy.PINNED, -1))
                .isEmpty();
        assertThat(TextWrap.wrap(StyledText.of("", Style.NONE), WidthPolicy.PINNED, 4))
                .extracting(StyledText::plainText)
                .containsExactly("");
    }

    @Test
    void aWideClusterStraddlingTheEdgeMovesWholeToTheNextLine() {
        // The emoji is two columns wide, so one column of room is not enough to paint it.
        List<StyledText> lines = TextWrap.wrap(StyledText.of("a\uD83D\uDE00", Style.NONE), WidthPolicy.PINNED, 2);

        assertThat(plain(lines)).containsExactly("a", "\uD83D\uDE00");
        assertThat(TextMeasure.columns(plain(lines).get(1), WidthPolicy.PINNED)).isEqualTo(2);
        // A cluster wider than the whole line is clipped rather than half painted.
        assertThat(TextWrap.wrap(StyledText.of("\uD83D\uDE00", Style.NONE), WidthPolicy.PINNED, 1))
                .extracting(StyledText::plainText)
                .containsExactly("");
    }

    @Test
    void aStyleBoundaryNeverSplitTheClusterItDivides() {
        // A base and the selector that widens it, arriving in two spans, are one cell: two columns wide,
        // on one line, exactly as the same text in one span.
        StyledText split = StyledText.of("\u2764", Style.NONE).append(StyledText.of("\uFE0F", BOLD));
        StyledText whole = StyledText.of("\u2764\uFE0F", Style.NONE);

        List<StyledText> splitLines = TextWrap.wrap(split, WidthPolicy.PINNED, 2);
        List<StyledText> wholeLines = TextWrap.wrap(whole, WidthPolicy.PINNED, 2);

        assertThat(splitLines).extracting(StyledText::plainText).isEqualTo(plain(wholeLines));
        assertThat(plain(splitLines)).containsExactly("\u2764\uFE0F");
        assertThat(TextMeasure.columns(plain(splitLines).get(0), WidthPolicy.PINNED))
                .isEqualTo(2);
    }

    @Test
    void theWrapUsesTheWidthPolicyItIsGivenAndFallsBackToPinned() {
        String ambiguous = "\u00B1";

        assertThat(TextWrap.wrap(StyledText.of("a" + ambiguous, Style.NONE), null, 2))
                .extracting(StyledText::plainText)
                .containsExactly("a" + ambiguous);
        assertThat(TextMeasure.columns(ambiguous, WidthPolicy.PINNED)).isEqualTo(1);
    }

    @Test
    void theClusterFactsAnswerWhatTheyAreAsked() {
        assertThat(TextWrap.continuesCluster("a", "b")).isFalse();
        assertThat(TextWrap.continuesCluster("a", "\u0301")).isTrue();
        assertThat(TextWrap.hasNoBase("\u0301")).isTrue();
        assertThat(TextWrap.hasNoBase("\u0301\u200D")).isTrue();
        assertThat(TextWrap.hasNoBase("a\u0301")).isFalse();
        assertThat(TextWrap.hasNoBase("\u200D")).isFalse();
    }
}
