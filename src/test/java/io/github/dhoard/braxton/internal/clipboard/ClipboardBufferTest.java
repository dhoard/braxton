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

/** The bounded in-process buffer truncates only at a code-point boundary. */
class ClipboardBufferTest {

    private static final String EMOJI = "\uD83D\uDE00";
    private static final String WIDE = "\u65E5\u672C";

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
    void storedTextIsAlwaysAPrefixThatEndsOnAWholeCodePoint() {
        String[] pieces = {"a", "bc", " ", EMOJI, WIDE, "\u0301", "\n"};
        Random random = new Random(29L);
        for (int iteration = 0; iteration < 5_000; iteration++) {
            StringBuilder source = new StringBuilder();
            int count = random.nextInt(6);
            for (int i = 0; i < count; i++) {
                source.append(pieces[random.nextInt(pieces.length)]);
            }
            String text = source.toString();
            int bound = 1 + random.nextInt(8);
            String stored = new ClipboardBuffer(bound).set(text);
            assertThat(stored).as("iteration %d bound %d", iteration, bound)
                    .hasSizeLessThanOrEqualTo(bound)
                    .isEqualTo(text.length() <= bound ? text : truncateAt(text, bound));
            assertThat(stored).as("iteration %d is a prefix", iteration)
                    .isEqualTo(text.substring(0, stored.length()));
            assertThat(stored.isEmpty()
                    || !Character.isHighSurrogate(stored.charAt(stored.length() - 1)))
                    .as("iteration %d keeps no half surrogate", iteration).isTrue();
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

    /** The rule the buffer documents: cut at the bound, then back off a dangling high surrogate. */
    private static String truncateAt(String text, int bound) {
        return Character.isHighSurrogate(text.charAt(bound - 1))
                ? text.substring(0, bound - 1) : text.substring(0, bound);
    }
}