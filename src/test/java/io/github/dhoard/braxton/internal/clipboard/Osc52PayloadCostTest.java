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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.testing.AllocationProbe;

/**
 * What limiting a large copy to the OSC 52 payload budget costs.
 *
 * <p>The encoder writes at most {@code maxPayloadBytes} bytes, so the work of encoding a copy has to
 * follow that budget and not the size of the copy: a paste can be megabytes, and the pinned break
 * iterator copies and indexes every unit it is given. Both bounds below are measured on the
 * truncating path only; a copy that fits the budget is returned without segmenting anything.</p>
 */
class Osc52PayloadCostTest {

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
    void limitingAMegabyteCopyToTheDefaultPayloadBudgetDoesNotSegmentTheCopy() {
        String copy = wide(1_000_000);
        int budget = 65_536;
        long bytes = AllocationProbe.bytes(() -> Osc52.encode(copy, budget));
        // The encoder segments the budget plus one cluster of lookahead, so the cost is a few
        // megabytes; segmenting the copy itself costs more than a hundred.
        assertThat(bytes).as("allocated bytes for the default payload budget on a 1 Mi copy")
                .isLessThan(8 * 1024 * 1024L);
        Osc52.Encoded encoded = Osc52.encode(copy, budget);
        assertThat(encoded.text()).isEqualTo(wide(21_845));
        assertThat(encoded.byteLength()).isEqualTo(65_535);
        assertThat(encoded.truncated()).isTrue();
    }

    @Test
    void theCostOfAPayloadFollowsTheByteBudgetNotTheCopy() {
        String smallCopy = wide(100_000);
        String largeCopy = wide(1_000_000);
        int budget = 1_024;
        long small = AllocationProbe.bytes(() -> Osc52.encode(smallCopy, budget));
        long large = AllocationProbe.bytes(() -> Osc52.encode(largeCopy, budget));
        // The same budget, so the same window: a copy ten times larger must not cost more.
        assertThat(large).as("allocated bytes for a 1 Ki budget on a 100 Ki and a 1 Mi copy")
                .isLessThan(small + 1024 * 1024L);
        assertThat(Osc52.encode(largeCopy, budget).text()).isEqualTo(wide(341));
    }
}
