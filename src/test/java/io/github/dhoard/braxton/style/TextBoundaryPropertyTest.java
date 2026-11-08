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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Every offset the boundary helpers can be asked about, over an exhaustively enumerated alphabet.
 *
 * <p>The editor moves its caret with {@link TextMeasure#boundaryAfter} and
 * {@link TextMeasure#previousBoundary}, while the layout segments the same text with
 * {@link Graphemes#iterate} and the truncation path walks that same segmentation. Three call sites, one
 * fact about the text. When they disagree, the disagreement is invisible in any single component: the
 * caret sits inside a glyph the renderer draws as one unit, a truncation cuts a cluster in half and
 * leaves the second half on screen, or - worst of all - a boundary answers the position it was given,
 * and the loop that moves the caret never moves.</p>
 *
 * <p>So the boundaries are checked against each other and against themselves: the forward walk must
 * make progress and stop exactly at the end, the walk backwards must stop at the start, both must stop
 * at the same positions the segmentation produces, and asking the same question twice must answer the
 * same way. The small alphabet is enumerated rather than sampled - every string up to a bounded length
 * over it - because a boundary bug is a single-character bug, and enumeration settles the domain
 * instead of visiting part of it.</p>
 */
class TextBoundaryPropertyTest {

    /** One-column text, a two-column base, a combining mark, a surrogate pair, a break, and joiners. */
    private static final String[] ALPHABET = {"a", "\u4E2D", "\u0301", "\uD83D\uDE00", "\n", "\t", "\u200D", "\uFE0F"};

    private static final int MAX_EXHAUSTIVE_LENGTH = 4;

    @Test
    void boundariesWalkTheSameCutTheSegmentationMakes() {
        for (String text : enumerate(ALPHABET, MAX_EXHAUSTIVE_LENGTH)) {
            check(text);
        }
    }

    @Test
    void boundariesHoldForLongerTextBuiltFromTheSameShapes() {
        Random random = new Random(616_161L);
        for (int round = 0; round < 30_000; round++) {
            StringBuilder text = new StringBuilder();
            for (int count = 1 + random.nextInt(14); count > 0; count--) {
                text.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            check(text.toString());
        }
    }

    private static void check(String text) {
        String where = "text %s".formatted(show(text));
        List<Integer> segments = segmentOffsets(text);

        // Forward: every step has to move, or a caret move is an infinite loop.
        List<Integer> forward = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int next = TextMeasure.boundaryAfter(text, index);
            assertThat(next)
                    .as("%s: boundaryAfter(%d) did not move past it (%d)", where, index, next)
                    .isGreaterThan(index);
            assertThat(next)
                    .as("%s: boundaryAfter(%d) ran past the end of the text", where, index)
                    .isLessThanOrEqualTo(text.length());
            assertThat(TextMeasure.isBoundary(text, next))
                    .as("%s: %d is not a boundary", where, next)
                    .isTrue();
            forward.add(next);
            index = next;
        }
        assertThat(index)
                .as("%s: the forward walk ended at %d, not at %d", where, index, text.length())
                .isEqualTo(text.length());
        assertThat(forward)
                .as("%s: forward boundaries differ from the segmentation", where)
                .isEqualTo(segments);

        // Backward: the same cut reached from the other end.
        List<Integer> backward = new ArrayList<>();
        index = text.length();
        while (index > 0) {
            int previous = TextMeasure.previousBoundary(text, index);
            assertThat(previous)
                    .as("%s: previousBoundary(%d) did not move below it (%d)", where, index, previous)
                    .isLessThan(index);
            assertThat(previous)
                    .as("%s: previousBoundary(%d) went negative", where, index)
                    .isNotNegative();
            assertThat(TextMeasure.isBoundary(text, previous))
                    .as("%s: %d is not a boundary", where, previous)
                    .isTrue();
            backward.add(previous);
            index = previous;
        }
        assertThat(index)
                .as("%s: the backward walk did not reach the start", where)
                .isZero();
        List<Integer> reversed = new ArrayList<>(backward);
        java.util.Collections.reverse(reversed);
        reversed.add(text.length());
        assertThat(reversed)
                .as("%s: backward boundaries differ from the segmentation", where)
                .isEqualTo(boundarySet(segments, text.length()));
        List<Integer> forwardSet = new ArrayList<>();
        forwardSet.add(0);
        forwardSet.addAll(forward);
        assertThat(forwardSet)
                .as("%s: forward boundaries differ from the segmentation", where)
                .isEqualTo(boundarySet(segments, text.length()));

        for (int at = 0; at <= text.length(); at++) {
            int before = TextMeasure.boundaryBefore(text, at);
            int after = TextMeasure.boundaryAfter(text, at);
            assertThat(before)
                    .as("%s: boundaryBefore(%d) is ahead of the index", where, at)
                    .isLessThanOrEqualTo(at);
            assertThat(before)
                    .as("%s: boundaryBefore(%d) is negative", where, at)
                    .isNotNegative();
            assertThat(TextMeasure.isBoundary(text, before))
                    .as("%s: %d is not a boundary", where, before)
                    .isTrue();
            assertThat(TextMeasure.boundaryBefore(text, before))
                    .as("%s: boundaryBefore(%d) is not stable", where, at)
                    .isEqualTo(before);
            if (at < text.length()) {
                assertThat(after)
                        .as("%s: boundaryAfter(%d) is not past the index", where, at)
                        .isGreaterThan(at);
            }
            if (at == text.length()) {
                assertThat(after)
                        .as("%s: boundaryAfter at the end is the end", where)
                        .isEqualTo(text.length());
            }
        }
    }

    /** Truncation and padding: the budget is respected, nothing is cut mid-cluster, and the result stays a prefix. */
    @Test
    void truncationKeepsWholeClustersAndNeverBreaksItsOwnPrefix() {
        List<String> texts = enumerate(ALPHABET, MAX_EXHAUSTIVE_LENGTH);
        for (String text : texts) {
            String where = "text %s".formatted(show(text));
            int full = TextMeasure.columns(text);
            for (int budget = -2; budget <= full + 2; budget++) {
                String cut = TextMeasure.truncate(text, budget);
                assertThat(TextMeasure.columns(cut))
                        .as("%s truncated to %d columns measures %d", where, budget, TextMeasure.columns(cut))
                        .isLessThanOrEqualTo(Math.max(0, budget));
                assertThat(text)
                        .as("%s truncated to %d columns is not a prefix of it", where, budget)
                        .startsWith(cut);
                assertThat(TextMeasure.isBoundary(text, cut.length()))
                        .as("%s truncated to %d columns stops at %d, inside a cluster", where, budget, cut.length())
                        .isTrue();
                if (budget <= 0) {
                    assertThat(cut)
                            .as("%s truncated to %d columns", where, budget)
                            .isEmpty();
                }
                if (budget >= full && budget > 0) {
                    // A zero budget asks for nothing at all, which truncate answers as empty even for
                    // a cluster that occupies no column.
                    assertThat(cut)
                            .as("%s truncated to %d columns lost content it had room for", where, budget)
                            .isEqualTo(text);
                }
                if (budget >= 1) {
                    String padded = TextMeasure.fit(text, budget);
                    assertThat(TextMeasure.columns(padded))
                            .as("%s fitted to %d columns occupies %d", where, budget, TextMeasure.columns(padded))
                            .isEqualTo(budget);
                    assertThat(padded)
                            .as("%s fitted to %d columns does not begin with its own truncation", where, budget)
                            .startsWith(cut);
                }
                if (budget + 1 <= full + 2) {
                    String longer = TextMeasure.truncate(text, budget + 1);
                    assertThat(longer)
                            .as("%s: a wider budget does not extend the shorter truncation", where)
                            .startsWith(cut);
                }
            }
        }
    }

    /** Cumulative end offsets of the clusters, plus the start: every boundary of the text. */
    private static List<Integer> boundarySet(List<Integer> segmentEnds, int length) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        boundaries.addAll(segmentEnds);
        if (boundaries.getLast() != length) {
            boundaries.add(length);
        }
        return boundaries;
    }

    /** Cumulative end offsets of the clusters, which is what a boundary has to agree with. */
    private static List<Integer> segmentOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        int at = 0;
        for (String cluster : TextMeasure.graphemes(text)) {
            at += cluster.length();
            offsets.add(at);
        }
        return offsets;
    }

    private static List<String> enumerate(String[] alphabet, int maxLen) {
        List<String> all = new ArrayList<>();
        all.add("");
        List<String> previous = List.of("");
        for (int length = 1; length <= maxLen; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : previous) {
                for (String piece : alphabet) {
                    next.add(prefix + piece);
                }
            }
            all.addAll(next);
            previous = next;
        }
        return all;
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }
}
