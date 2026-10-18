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

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/** The generated wide-width table: sorted, disjoint ranges that the binary search can rely on. */
class Unicode16WidthTest {

    private static int[] ranges(String of) throws Exception {
        Field field = Unicode16Width.class.getDeclaredField(of);
        field.setAccessible(true);
        return (int[]) field.get(null);
    }

    @Test
    void theGeneratedRangesAreSortedAndDisjoint() throws Exception {
        int[] start = ranges("START");
        int[] end = ranges("END");
        assertThat(start).as("one end per start").hasSameSizeAs(end);
        assertThat(start).isSorted();
        for (int i = 0; i < start.length; i++) {
            assertThat(start[i]).as("range %d must not be inverted", i)
                    .isLessThanOrEqualTo(end[i]);
            if (i > 0) {
                assertThat(start[i]).as("range %d must start after the previous end", i)
                        .isGreaterThan(end[i - 1]);
            }
        }
    }

    @Test
    void everyRangeEdgeAnswersAsTheLinearScanWould() throws Exception {
        int[] start = ranges("START");
        int[] end = ranges("END");
        for (int i = 0; i < start.length; i++) {
            assertThat(Unicode16Width.isWide(start[i])).as("start of range %d", i).isTrue();
            assertThat(Unicode16Width.isWide(end[i])).as("end of range %d", i).isTrue();
            assertThat(Unicode16Width.isWide(start[i] - 1)).as("before range %d", i).isFalse();
            assertThat(Unicode16Width.isWide(end[i] + 1)).as("after range %d", i).isFalse();
        }
        // A few anchors that must never drift: ASCII and combining marks are narrow, CJK, fullwidth
        // forms and emoji presentation are wide.
        assertThat(Unicode16Width.isWide('a')).isFalse();
        assertThat(Unicode16Width.isWide(0x0301)).isFalse();
        assertThat(Unicode16Width.isWide(0x200D)).isFalse();
        assertThat(Unicode16Width.isWide(0x4E00)).isTrue();
        assertThat(Unicode16Width.isWide(0xFF01)).isTrue();
        assertThat(Unicode16Width.isWide(0x1F600)).isTrue();
        assertThat(Unicode16Width.isWide(0x1F1E6)).isTrue();
        assertThat(Unicode16Width.isWide(0x10FFFF)).isFalse();
    }
}
