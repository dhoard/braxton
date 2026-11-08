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

import io.github.dhoard.braxton.testing.AllocationProbe;
import org.junit.jupiter.api.Test;

/**
 * What storing the bounded prefix of a large copy costs.
 *
 * <p>The buffer keeps at most {@code maxChars} units, so the work of a truncating copy has to
 * follow that bound and not the size of the copy: a paste can be megabytes, and the pinned break
 * iterator copies and indexes every unit it is given. Both bounds below are measured on the
 * truncating path only; a copy that fits the bound is stored without segmenting anything.</p>
 */
class ClipboardTruncationCostTest {

    /** Translated text: two-unit clusters, so every unit reaches the break iterator. */
    private static String wide(int chars) {
        StringBuilder text = new StringBuilder(chars + 2);
        while (text.length() < chars) {
            text.append("\u65E5\u672C");
        }
        text.setLength(chars);
        return text.toString();
    }

    @Test
    void storingThirtyTwoUnitsOfAMegabyteCopyDoesNotSegmentTheCopy() {
        String copy = wide(1_000_000);
        ClipboardBuffer buffer = new ClipboardBuffer(32);
        long bytes = AllocationProbe.bytes(() -> buffer.set(copy));
        // The window the buffer segments is the bound plus one cluster of lookahead, so the cost is
        // tens of thousands of bytes; segmenting the copy itself costs tens of millions.
        assertThat(bytes)
                .as("allocated bytes for a 32-unit bound on a 1 Mi copy")
                .isLessThan(64 * 1024L);
        assertThat(buffer.get()).isEqualTo(wide(32));
    }

    @Test
    void theCostOfATruncatingCopyFollowsTheBoundNotTheCopy() {
        String smallCopy = wide(2_000);
        String largeCopy = wide(1_000_000);
        long small = AllocationProbe.bytes(() -> new ClipboardBuffer(1_024).set(smallCopy));
        long large = AllocationProbe.bytes(() -> new ClipboardBuffer(1_024).set(largeCopy));
        // The same bound, so the same window: a copy five hundred times larger must not cost more.
        assertThat(large)
                .as("allocated bytes for a 1 Ki bound on a 2 Ki and a 1 Mi copy")
                .isLessThan(small + 16 * 1024L);
        assertThat(new ClipboardBuffer(1_024).set(largeCopy)).isEqualTo(wide(1_024));
    }
}
