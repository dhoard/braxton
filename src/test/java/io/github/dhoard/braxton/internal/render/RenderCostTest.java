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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

class RenderCostTest {
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
    void wrappingAWideRowDoesNotCopyItsGrowingPrefixPerGrapheme() {
        LayoutEngine engine = new LayoutEngine();
        StyledText text = StyledText.plain("x".repeat(2048));
        long bytes = allocated(() -> sink = engine.wrapLines(text, 2048));
        assertThat(bytes).as("one wrapped row should not allocate quadratic prefix copies")
                .isLessThan(1_000_000);
        assertThat(engine.wrapLines(text, 2048)).containsExactly(text);
    }

    @Test
    void concatenationDoesNotCopyEveryIntermediatePrefix() {
        StyledText[] parts = new StyledText[1024];
        java.util.Arrays.fill(parts, StyledText.plain("x"));
        long bytes = allocated(() -> sink = StyledText.concat(parts));
        assertThat(bytes).as("concat should assemble a styled run once").isLessThan(200_000);
        assertThat(StyledText.concat(parts)).isEqualTo(StyledText.plain("x".repeat(parts.length)));
    }

    @Test
    void wrappingPreservesStylesLinksTabsAndWideClusters() {
        Style red = Style.fg(Colors.ERROR_400);
        Style blue = Style.fg(Colors.BLUE_400);
        String url = "https://example.com";
        StyledText text = StyledText.of("ab🙂", red, url).append(StyledText.of("c\td\nz", blue, url));
        assertThat(new LayoutEngine().wrapLines(text, 4)).containsExactly(
                StyledText.of("ab🙂", red, url), StyledText.of("c   ", blue, url),
                StyledText.of("d", blue, url), StyledText.of("z", blue, url));
    }

    @Test
    void concatKeepsTheExistingAppendSemanticsIncludingEmptyStyledSpans() {
        StyledText[] parts = {StyledText.empty(), StyledText.of("\u001b", Style.NONE.withBold()),
                StyledText.plain("a"), StyledText.plain("b"),
                StyledText.of("c", Style.NONE, "https://example.com"), StyledText.empty()};
        StyledText expected = StyledText.empty();
        for (StyledText part : parts) {
            expected = expected.append(part);
        }
        assertThat(StyledText.concat(parts)).isEqualTo(expected);
        assertThat(StyledText.concat()).isEqualTo(StyledText.empty());
    }
}
