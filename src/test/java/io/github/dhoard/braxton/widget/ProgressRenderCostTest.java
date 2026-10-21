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

class ProgressRenderCostTest {
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
    void aLongBarDoesNotCopyItsGrowingRunsForEveryCell() {
        ProgressBar bar = ProgressBar.of(4096);
        assertThat(allocated(() -> sink = bar.render(0.5, 1.0))).isLessThan(2_000_000L);
        assertThat(bar.render(0.5, 1.0).plainText())
                .isEqualTo("\u2588".repeat(2048) + "\u2591".repeat(2048));
    }

    @Test
    void batchedAssemblyKeepsLabelsBuffersIconsAndSpanMetadata() {
        StyledText label = StyledText.of("pre\n", Style.NONE.withBold(), "https://example.com");
        for (int width : new int[] {1, 3, 8}) {
            for (int buffer : new int[] {0, 2}) {
                for (double value : new double[] {0, 0.5, 1}) {
                    ProgressBar bar = ProgressBar.of(width).icons("漢", " ").buffer(buffer)
                            .colors(Color.WHITE, Color.BLACK).prepend(label)
                            .append(StyledText.of("post", Style.NONE.withItalic())).showPercentage(true);
                    StyledText expected = StyledText.of("pre ", label.style(), "https://example.com");
                    int filled = (int) Math.round(value * width);
                    for (int i = 0; i < width; i++) {
                        if (i > 0 && buffer > 0) {
                            expected = expected.append(" ".repeat(buffer));
                        }
                        expected = expected.append(StyledText.of(i < filled ? "漢" : " ",
                                Style.fg(i < filled ? Color.WHITE : Color.BLACK)));
                    }
                    expected = expected.append(" " + Math.round(value * 100) + "%")
                            .append(StyledText.of("post", Style.NONE.withItalic()));
                    assertThat(bar.render(value, 1)).isEqualTo(expected);
                }
            }
        }
    }
}
