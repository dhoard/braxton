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

package io.github.dhoard.braxton.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** FIGlet row assembly must grow with the output rather than repeated prefix copies. */
class FigletRenderCostTest {
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
    void aLongIdentityFontRowDoesNotRebuildEveryPrefix() throws Exception {
        FigletFont font = FontLoader.load(FontLoader.Family.TERM);
        String draft = "a".repeat(4096);
        assertThat(allocated(() -> sink = font.render(draft))).isLessThan(1_000_000L);
        assertThat(font.render(draft)).containsExactly(draft);
        assertThat(font.render("a\n\uD83D\uDE00b\n")).containsExactly("a", "b", "");
    }

    @Test
    void fourTimesTheArtStaysBelowEightTimesTheAllocation() throws Exception {
        FigletFont font = FontLoader.load(FontLoader.Family.STANDARD);
        String small = "a".repeat(256);
        String large = "a".repeat(1024);
        long smallBytes = allocated(() -> sink = font.render(small));
        long largeBytes = allocated(() -> sink = font.render(large));
        assertThat(largeBytes).as("four times the glyphs should scale near linearly")
                .isLessThan(smallBytes * 8);
        assertThat(font.render(large)).hasSize(font.height());
    }
}
