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

package io.github.dhoard.braxton.internal.clipboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The bounded in-process buffer truncates only at a grapheme-cluster boundary. */
class ClipboardBufferTest {

    private static final String EMOJI = "\uD83D\uDE00";
    private static final String WIDE = "\u65E5\u672C";
    private static final String FAMILY =
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";

    @Test
    void aBoundThatFitsKeepsTheTextExactly() {
        ClipboardBuffer buffer = new ClipboardBuffer(5);
        assertThat(buffer.set("abcde")).isEqualTo("abcde");
        assertThat(buffer.set("abcd")).isEqualTo("abcd");
        assertThat(buffer.length()).isEqualTo(4);
        assertThat(buffer.isEmpty()).isFalse();
    }

    @Test
    void truncationDropsAHalfSurrogatePairRatherThanKeepingOneHalf() {
        // "a" plus an emoji is three UTF-16 units, so a bound of two lands between the surrogates.
        ClipboardBuffer buffer = new ClipboardBuffer(2);
        assertThat(buffer.set("a" + EMOJI)).isEqualTo("a");
        assertThat(buffer.get()).isEqualTo("a");
        assertThat(buffer.length()).isEqualTo(1);

        assertThat(new ClipboardBuffer(3).set("a" + EMOJI)).isEqualTo("a" + EMOJI);
        assertThat(new ClipboardBuffer(1).set(EMOJI)).isEmpty();
        assertThat(new ClipboardBuffer(2).set(WIDE + "x")).isEqualTo(WIDE);
    }

    @Test
    void theBoundFloorsAtOneAndAcceptsNull() {
        ClipboardBuffer buffer = new ClipboardBuffer(0);
        assertThat(buffer.maxChars()).isEqualTo(1);
        assertThat(buffer.maxChars()).isEqualTo(new ClipboardBuffer(-5).maxChars());
        assertThat(buffer.set(null)).isEmpty();
        assertThat(buffer.set("ab")).isEqualTo("a");
        assertThat(buffer.set(null)).isEmpty();
        buffer.set("held");
        buffer.clear();
        assertThat(buffer.get()).isEmpty();
        assertThat(buffer.isEmpty()).isTrue();
        assertThat(new ClipboardBuffer(4).set(null).isEmpty()).isTrue();
    }

    @Test
    void truncationKeepsOnlyWholeGraphemeClusters() {
        // The stored value can be pasted back, so a cut inside a cluster would end it in a joiner,
        // selector, or mark whose meaning depends on the text after the insertion point. OSC 52
        // already truncates the same copy on a cluster boundary.
        assertThat(new ClipboardBuffer(4).set("a" + FAMILY)).isEqualTo("a");
        assertThat(new ClipboardBuffer(2).set("ab\u0301c")).isEqualTo("a");
        assertThat(new ClipboardBuffer(2).set("e\u0301x")).isEqualTo("e\u0301");
        assertThat(new ClipboardBuffer(3).set(FAMILY + "x")).isEmpty();
        // A bound that reaches the end of a cluster keeps it; the next cluster is the cut.
        assertThat(new ClipboardBuffer(3).set("a" + EMOJI + "x")).isEqualTo("a" + EMOJI);
    }

    @Test
    void truncationNeverStoresATrailingLoneHighSurrogate() {
        // A high surrogate with nothing after it is not a character, so a truncated value must not
        // end with one, however many the source carries. A whole pair is a character and is kept.
        assertThat(new ClipboardBuffer(3).set("\uD800\uD800\uD800\uD800")).isEmpty();
        assertThat(new ClipboardBuffer(2).set("a\uD800\uD800")).isEqualTo("a");
        assertThat(new ClipboardBuffer(2).set("a\uD800b")).isEqualTo("a");
        assertThat(new ClipboardBuffer(3).set("\uDE00\uD800\uD800\uD800")).isEqualTo("\uDE00");
        assertThat(new ClipboardBuffer(3).set("a" + EMOJI)).isEqualTo("a" + EMOJI);
    }

    @Test
    void storedTextIsAlwaysAPrefixThatEndsAfterAWholeGraphemeCluster() {
        String[] pieces = {"a", "bc", " ", EMOJI, FAMILY, WIDE, "\u0301", "e\u0301", "\u200D",
                "\uFE0F", "\n", "\uD800", "\uDC00"};
        Random random = new Random(29L);
        for (int iteration = 0; iteration < 5_000; iteration++) {
            StringBuilder source = new StringBuilder();
            int count = random.nextInt(6);
            for (int i = 0; i < count; i++) {
                source.append(pieces[random.nextInt(pieces.length)]);
            }
            String text = source.toString();
            int bound = 1 + random.nextInt(8);
            boolean truncated = text.length() > bound;
            String stored = new ClipboardBuffer(bound).set(text);
            assertThat(stored).as("iteration %d bound %d", iteration, bound)
                    .hasSizeLessThanOrEqualTo(bound)
                    .isEqualTo(truncated ? truncateToClusters(text, bound) : text);
            assertThat(stored).as("iteration %d is a prefix", iteration)
                    .isEqualTo(text.substring(0, stored.length()));
            if (truncated) {
                assertThat(stored.isEmpty()
                        || !Character.isHighSurrogate(stored.charAt(stored.length() - 1)))
                        .as("iteration %d keeps no trailing lone high surrogate", iteration)
                        .isTrue();
            }
        }
    }

    @Test
    void aClusterLongerThanTheLookaheadPastTheBoundIsStillCutOnAClusterBoundary() {
        // A cluster has no length limit, so the window the buffer segments can end inside the one
        // cluster that straddles the bound. The whole source is segmented then rather than cutting
        // inside that cluster: every bound below lands at the start of it or after it.
        String cluster = "e" + "\u0301".repeat(200);
        String source = "a".repeat(10) + cluster + "z".repeat(300);
        for (int bound : new int[] {10, 11, 100, 210, 211, 300, 511}) {
            assertThat(new ClipboardBuffer(bound).set(source)).as("bound %d", bound)
                    .isEqualTo(truncateToClusters(source, bound));
        }
        assertThat(new ClipboardBuffer(100).set(source)).isEqualTo("a".repeat(10));
        assertThat(new ClipboardBuffer(211).set(source)).isEqualTo("a".repeat(10) + cluster);
        assertThat(new ClipboardBuffer(511).set(source)).isEqualTo(source);
    }

    @Test
    void aSourceLongerThanTheSegmentedWindowKeepsTheWholeClusterPrefix() {
        // The window is the bound plus a fixed lookahead, so a copy that long exercises the path
        // that segments only part of the source; the reference segments all of it.
        String[] pieces = {"a", "bc", " ", EMOJI, FAMILY, WIDE, "\u0301", "e\u0301", "\u200D",
                "\uFE0F", "\n", "\uD800", "\uDC00", "\uD83C\uDDFA\uD83C\uDDF8",
                "\uD83D\uDC4D\uD83C\uDFFD", "\uAC00", "\u1100\u1161\u11A8", "\u0E01\u0E33"};
        Random random = new Random(41L);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            StringBuilder source = new StringBuilder();
            int target = 200 + random.nextInt(2_800);
            while (source.length() < target) {
                source.append(pieces[random.nextInt(pieces.length)]);
            }
            String text = source.toString();
            int bound = 1 + random.nextInt(random.nextBoolean() ? 64 : 600);
            boolean truncated = text.length() > bound;
            String stored = new ClipboardBuffer(bound).set(text);
            assertThat(stored).as("iteration %d bound %d", iteration, bound)
                    .hasSizeLessThanOrEqualTo(bound)
                    .isEqualTo(truncated ? truncateToClusters(text, bound) : text);
            assertThat(stored).as("iteration %d is a prefix", iteration)
                    .isEqualTo(text.substring(0, stored.length()));
        }
    }

    @Test
    void malformedInputIsStoredVerbatimWhenNoTruncationHappens() {
        // This buffer bounds and stores; it does not sanitize. A caller hands it well-formed text.
        ClipboardBuffer buffer = new ClipboardBuffer(4);
        assertThat(buffer.set("a\uD83D")).isEqualTo("a\uD83D");
        // Truncation still backs off the dangling high surrogate rather than keeping half of it.
        assertThat(new ClipboardBuffer(2).set("a\uD83Db")).isEqualTo("a");
    }

    /**
     * The documented rule: the longest whole-cluster prefix that fits the bound and keeps no
     * trailing lone high surrogate. Expressed as a boundary search so it does not mirror the
     * implementation's steps.
     */
    private static String truncateToClusters(String text, int bound) {
        java.text.BreakIterator iterator =
                java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(text);
        int end = 0;
        while (true) {
            int next = iterator.next();
            if (next == java.text.BreakIterator.DONE || next > bound) {
                break;
            }
            // A prefix that ends with a high surrogate holds one half of a pair, not a character.
            if (!Character.isHighSurrogate(text.charAt(next - 1))) {
                end = next;
            }
        }
        return text.substring(0, end);
    }
}
