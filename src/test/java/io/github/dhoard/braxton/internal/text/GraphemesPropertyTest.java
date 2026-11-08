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

package io.github.dhoard.braxton.internal.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.style.TextMeasure;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Every cluster boundary the segmentation can produce, over an exhaustive small alphabet.
 *
 * <p>{@link Graphemes} has two paths: a fast path that returns one cluster per character for text it
 * can prove is simple, and the pinned JDK iterator for everything else. A character that takes the
 * wrong path is measured as one editing unit where the rest of the library treats it as two, which
 * shows up later as a cursor sitting inside a glyph or a cell painted with half a cluster. So the
 * fast path is held to the rule it claims, the slow path to the iterator it delegates to, and both
 * to the invariants everything downstream assumes.</p>
 *
 * <p>The small alphabet is enumerated exhaustively: every string up to a bounded length over it, not
 * a sample. That is the strongest claim available for a segmentation rule - the domain is finite, so
 * within it the property is not sampled but settled. It is not a claim about arbitrary Unicode text;
 * the seeded sweep over a wider alphabet covers shapes the small alphabet cannot reach.</p>
 */
class GraphemesPropertyTest {

    /** Characters that keep text on the fast path, including the two controls it accepts. */
    private static final char[] SIMPLE_ALPHABET = {'a', ' ', '~', '\n', '\t'};

    /**
     * Characters that force the JDK iterator: a combining mark, a joiner, the two variation
     * selectors, a surrogate pair, a CJK base, and the controls the fast path deliberately excludes
     * because they break CR/LF into one cluster.
     */
    private static final String[] HARD_ALPHABET = {"a", "\u0301", "\u200D", "\uFE0F", "\u4E2D", "😀", "\r", "\n"};

    /** Marks that may join the cluster before them and must never open more than one. */
    private static final String[] MARKS = {"\u0301", "\u0308", "\u200D", "\uFE0F"};

    private static final int MAX_EXHAUSTIVE_LENGTH = 5;

    @Test
    void simpleTextSplitsExactlyOneCharacterAtATime() {
        for (String text : enumerate(SIMPLE_ALPHABET, MAX_EXHAUSTIVE_LENGTH)) {
            List<String> clusters = segments(text);
            assertThat(clusters).as("[%s] segmented", show(text)).hasSize(text.length());
            for (int i = 0; i < clusters.size(); i++) {
                assertThat(clusters.get(i))
                        .as("[%s] cluster %d", show(text), i)
                        .isEqualTo(String.valueOf(text.charAt(i)));
            }
            assertAlwaysACut(text, clusters);
        }
    }

    @Test
    void complexTextMatchesTheIteratorItDelegatesTo() {
        int checked = 0;
        for (String text : enumerate(HARD_ALPHABET, MAX_EXHAUSTIVE_LENGTH)) {
            // A string made only of simple characters is allowed to take either path: the fast path
            // and the JDK iterator agree on it by construction, so it adds nothing here.
            if (isSimple(text)) {
                continue;
            }
            checked++;
            BreakIterator expected = BreakIterator.getCharacterInstance(Locale.ROOT);
            expected.setText(text);
            List<String> reference = new ArrayList<>();
            for (int start = expected.first(), end = expected.next();
                    end != BreakIterator.DONE;
                    start = end, end = expected.next()) {
                reference.add(text.substring(start, end));
            }
            List<String> actual = segments(text);
            assertThat(actual)
                    .as("[%s] segmented differently than the pinned JDK iterator", show(text))
                    .isEqualTo(reference);
            assertAlwaysACut(text, actual);
        }
        assertThat(checked).as("the exhaustive sweep must reach the slow path").isGreaterThan(10_000);
    }

    @Test
    void segmentationIsInvariantUnderTheWiderAlphabetSweep() {
        Random random = new Random(4154L);
        String[] pieces = {"a", "\u0301", "\uD83D\uDC69", "\u200D", "\uD83D\uDCBB", "\uFE0F", "\u4E2D", "\r\n", "\t"};
        for (int round = 0; round < 40_000; round++) {
            StringBuilder text = new StringBuilder();
            for (int count = random.nextInt(7); count > 0; count--) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            String value = text.toString();
            List<String> clusters = segments(value);
            assertAlwaysACut(value, clusters);
            assertThat(clusters).as("graphemeCount of [%s]", show(value)).hasSize(TextMeasure.graphemeCount(value));
            assertThat(TextMeasure.graphemes(value))
                    .as("the public segmentation of [%s]", show(value))
                    .isEqualTo(clusters);
            // A mark after any text joins the cluster before it or opens one; it can never open two,
            // so the cluster count can grow by at most one however the text ends.
            String mark = MARKS[random.nextInt(MARKS.length)];
            assertThat(TextMeasure.graphemeCount(value + mark))
                    .as("[%s] with a trailing [%s]", show(value), show(mark))
                    .isBetween(clusters.size(), clusters.size() + 1);
        }
    }

    /**
     * The invariants every consumer of segmentation relies on: the pieces are non-empty, they hold no
     * half of a surrogate pair, they re-form the input exactly, and streaming them piece by piece
     * yields the same cut as asking for them all at once.
     */
    private static void assertAlwaysACut(String text, List<String> clusters) {
        StringBuilder joined = new StringBuilder();
        for (String cluster : clusters) {
            assertThat(cluster).as("empty cluster in [%s]", show(text)).isNotEmpty();
            for (int i = 0; i < cluster.length(); i++) {
                // A cut between the halves of a surrogate pair would make one cluster unrenderable.
                assertThat(Character.isHighSurrogate(cluster.charAt(i)) && i + 1 >= cluster.length())
                        .as("[%s] cluster [%s] ends on a high surrogate", show(text), show(cluster))
                        .isFalse();
                assertThat(Character.isLowSurrogate(cluster.charAt(i)) && i == 0)
                        .as("[%s] cluster [%s] opens on a low surrogate", show(text), show(cluster))
                        .isFalse();
            }
            joined.append(cluster);
        }
        assertThat(joined.toString())
                .as("the clusters of [%s] do not re-form it", show(text))
                .isEqualTo(text);
        assertThat(iterateOneAtATime(text))
                .as("[%s] segmented one cluster at a time", show(text))
                .isEqualTo(clusters);
    }

    /** Segmentation consumed through the streaming cursor rather than as a whole list. */
    private static List<String> iterateOneAtATime(String text) {
        List<String> clusters = new ArrayList<>();
        var iterator = Graphemes.iterate(text).iterator();
        while (iterator.hasNext()) {
            clusters.add(iterator.next());
        }
        return clusters;
    }

    private static List<String> segments(String text) {
        List<String> clusters = new ArrayList<>();
        for (String cluster : Graphemes.iterate(text)) {
            clusters.add(cluster);
        }
        return clusters;
    }

    private static boolean isSimple(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 0x20 && c <= 0x7e) || c == '\n' || c == '\t')) {
                return false;
            }
        }
        return true;
    }

    /** Every string of length 0..maxLen over {@code alphabet}, in a fixed order. */
    private static List<String> enumerate(char[] alphabet, int maxLen) {
        List<String> all = new ArrayList<>();
        all.add("");
        List<String> previous = List.of("");
        for (int length = 1; length <= maxLen; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : previous) {
                for (char c : alphabet) {
                    next.add(prefix + c);
                }
            }
            all.addAll(next);
            previous = next;
        }
        return all;
    }

    /** Every string of length 0..maxLen over {@code alphabet}, where a piece may be a pair. */
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
