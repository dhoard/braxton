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

import java.lang.management.ManagementFactory;
import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Each caret and word operation asks {@link TextMeasure} where the clusters start and end. Every one
 * of those lookups used to build a fresh {@link BreakIterator} and re-scan the draft from offset 0,
 * which costs the editor a full draft scan and a large allocation per keystroke. The allocation
 * bounds below pin the lookup cost; the parity test pins the semantics the fast paths must reproduce.
 */
class TextMeasureBoundaryCostTest {

    private static final int LOOKUPS = 256;

    private static final List<String> CORPUS = List.of(
            "", "a", "ab", "the quick brown fox", "\n", "\t", "\r\n",
            "漢字", "🙂", "\uFE0E", "🙂\uFE0F", "é", "é",
            "\u0301abc", "a\u200Db", "\uD83D\uDE00", "\uD83D\uDE00x", "x\uD83D\uDE00",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD800", "a\uD800b",
            "a漢🙂b\u0301\tz 0123456789");

    private static long allocated(Runnable action) {
        Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
        for (int i = 0; i < 5; i++) {
            action.run();
        }
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        action.run();
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
    }

    private static String draft(String unit, int units) {
        return unit.repeat(units);
    }

    @Test
    void boundaryLookupsOnALongAsciiDraftAllocateNoBreakIterator() {
        String text = draft("the quick brown fox jumps over the lazy dog 0123456789 ", 82);
        long bytes = allocated(() -> lookups(text, everyOffset(text)));
        assertThat(bytes)
                .as("a printable-ASCII draft has a cluster boundary at every offset, so a lookup "
                        + "must not build a break iterator")
                .isLessThan(LOOKUPS * 1_024L);
    }

    @Test
    void aLookupInsideAPrintableAsciiRunSkipsTheBreakIteratorInsideAUnicodeDraft() {
        String text = draft("a漢🙂\u0301\t hello ", 450);
        int[] offsets = java.util.stream.IntStream.range(2, text.length() - 1)
                .filter(i -> isPrintableAscii(text.charAt(i - 2))
                        && isPrintableAscii(text.charAt(i - 1))
                        && isPrintableAscii(text.charAt(i))
                        && isPrintableAscii(text.charAt(i + 1)))
                .limit(LOOKUPS)
                .toArray();
        assertThat(offsets).as("the draft must contain printable-ASCII runs to measure")
                .hasSize(LOOKUPS);
        long bytes = allocated(() -> lookups(text, offsets));
        assertThat(bytes)
                .as("a lookup with printable-ASCII characters on both sides of the gap, and of the "
                        + "neighbouring gaps, is a cluster boundary whatever the rest of the draft "
                        + "holds")
                .isLessThan(LOOKUPS * 1_024L);
    }

    private static boolean isPrintableAscii(char value) {
        return value >= 0x20 && value <= 0x7E;
    }

    private static int[] everyOffset(String text) {
        int[] offsets = new int[LOOKUPS];
        for (int i = 0; i < LOOKUPS; i++) {
            offsets[i] = (i * 7) % text.length();
        }
        return offsets;
    }

    private static void lookups(String text, int[] offsets) {
        for (int index : offsets) {
            TextMeasure.isBoundary(text, index);
            TextMeasure.previousBoundary(text, index);
            TextMeasure.boundaryBefore(text, index);
            TextMeasure.boundaryAfter(text, index);
        }
    }

    @Test
    void everyBoundaryLookupMatchesTheUnicodeBreakIteratorAtEveryOffset() {
        for (String text : CORPUS) {
            for (int index = -1; index <= text.length() + 1; index++) {
                assertThat(TextMeasure.isBoundary(text, index))
                        .as("isBoundary(%s, %d)", text, index)
                        .isEqualTo(iteratorIsBoundary(text, index));
                assertThat(TextMeasure.previousBoundary(text, index))
                        .as("previousBoundary(%s, %d)", text, index)
                        .isEqualTo(iteratorPreviousBoundary(text, index));
                assertThat(TextMeasure.boundaryBefore(text, index))
                        .as("boundaryBefore(%s, %d)", text, index)
                        .isEqualTo(iteratorBoundaryBefore(text, index));
                assertThat(TextMeasure.boundaryAfter(text, index))
                        .as("boundaryAfter(%s, %d)", text, index)
                        .isEqualTo(iteratorBoundaryAfter(text, index));
            }
        }
    }

    private static boolean iteratorIsBoundary(String text, int index) {
        if (index < 0 || index > text.length()) {
            return false;
        }
        return iterator(text).isBoundary(index);
    }

    private static int iteratorPreviousBoundary(String text, int index) {
        if (text.isEmpty() || index <= 0) {
            return 0;
        }
        int previous = iterator(text).preceding(Math.min(index, text.length()));
        return previous == BreakIterator.DONE ? 0 : previous;
    }

    private static int iteratorBoundaryBefore(String text, int index) {
        if (text.isEmpty() || index <= 0) {
            return 0;
        }
        int limit = Math.min(index, text.length());
        BreakIterator iterator = iterator(text);
        if (iterator.isBoundary(limit)) {
            return limit;
        }
        int previous = iterator.preceding(limit);
        return previous == BreakIterator.DONE ? 0 : previous;
    }

    private static int iteratorBoundaryAfter(String text, int index) {
        if (text.isEmpty() || index < 0) {
            return 0;
        }
        int next = iterator(text).following(Math.min(index, text.length() - 1));
        return next == BreakIterator.DONE ? text.length() : next;
    }

    private static BreakIterator iterator(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        return iterator;
    }
}
