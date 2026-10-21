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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

class ShimmerRenderCostTest {
    private static volatile Object sink;

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

    @Test
    void aLongShimmerDoesNotRebuildItsUnlitPrefixEveryFrame() {
        String text = "a".repeat(4096);
        assertThat(allocated(() -> sink = Animation.shimmer(text, 0L, Color.BLACK, Color.WHITE)))
                .isLessThan(1_500_000L);
        assertThat(Animation.shimmer(text, 0L, Color.BLACK, Color.WHITE).plainText()).isEqualTo(text);
    }

    @Test
    void batchingKeepsTheOriginalClusterStylesAndSanitizedEmptyRuns() {
        String[] inputs = {"ab", "e\u0301漢\uD83D\uDE00", "a\u001bb\n\tc", "", "\u001b", "abc\uD800x"};
        for (String text : inputs) {
            for (long now : new long[] {0L, 210_000_000L, 700_000_000L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                for (Color base : new Color[] {null, Color.BLACK}) {
                    for (Color highlight : new Color[] {null, Color.WHITE, base}) {
                        var clusters = TextMeasure.graphemes(text);
                        int position = Math.floorMod(now / 70_000_000L, clusters.size() + 12);
                        StyledText reference = StyledText.empty();
                        for (int i = 0; i < clusters.size(); i++) {
                            Color color = i >= position - 3 && i <= position ? highlight : base;
                            if (color == null) {
                                color = base;
                            }
                            reference = reference.append(StyledText.of(clusters.get(i),
                                    color == null ? Style.NONE : Style.fg(color)));
                        }
                        assertThat(Animation.shimmer(text, now, base, highlight)).isEqualTo(reference);
                    }
                }
            }
        }
    }
}
