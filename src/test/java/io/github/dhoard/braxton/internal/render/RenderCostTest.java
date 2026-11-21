/*
 * Copyright (c) 2026-present Douglas Hoard
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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextWrap;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
    void wrappingAUniformRunAllocatesByRowsRatherThanCells() {
        StyledText text = StyledText.plain(("\u2502" + "x".repeat(78) + "\u2502\n").repeat(30));
        long bytes = allocated(() -> sink = TextWrap.wrap(text, WidthPolicy.PINNED, 80, 24));
        assertThat(bytes)
                .as("a 24-row uniform run should not rebuild each of its 1,920 cells")
                .isLessThan(20_000);
        assertThat(TextWrap.wrap(text, WidthPolicy.PINNED, 80, 24)).hasSize(24);
    }

    @Test
    void paintingOrdinaryTextDoesNotCopyEverySingleCharacter() {
        Screen screen = new Screen(1, 2048);
        StyledText line = StyledText.plain("a".repeat(2048));
        long bytes = allocated(() -> screen.writeLine(0, 0, 2048, line, WidthPolicy.PINNED));
        assertThat(bytes)
                .as("painting existing ASCII clusters should reuse their text")
                .isLessThan(10_000);
        assertThat(screen.plainLine(0)).isEqualTo(line.plainText());
    }

    @Test
    void aOneCellChangeAllocatesAtMostAQuarterOfTheRecordedBaseline() {
        Screen before = scene(24, 80);
        Screen after = scene(24, 80);
        after.put(12, 40, "Z", Style.fg(Colors.ERROR_400), null, 1);
        long bytes = allocated(() -> sink = AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR));
        assertThat(bytes)
                .as("R-13: 25%% of the WP-0.1 baseline of 97,208 B/op for a one-cell 24x80 diff")
                .isLessThanOrEqualTo(24_302L);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("the same change's R-13 byte budget")
                .hasSizeLessThanOrEqualTo(48);
    }

    /**
     * A scene painted cell by cell, with the style changes a real frame carries.
     */
    private static Screen scene(int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Style style = switch ((row + col) % 3) {
                    case 0 -> Style.fg(Colors.BLUE_400);
                    case 1 -> Style.NONE.withBold();
                    default -> Style.NONE;
                };
                screen.put(row, col, "x", style, null, 1);
            }
        }
        return screen;
    }

    @Test
    void aUniformFillDoesNotAllocateAnIdenticalImmutableCellForEveryPosition() {
        Style background = Style.bg(Colors.BLACK);
        Rect bounds = new Rect(0, 0, 40, 120);
        long bytes = allocated(() -> {
            Screen screen = new Screen(40, 120);
            screen.fill(bounds, background);
            sink = screen;
        });
        assertThat(bytes).as("grid storage plus one immutable fill value").isLessThan(60_000L);
    }

    @Test
    void cellsFromTheSameFillRemainIndependentWhenRepainted() {
        Style background = Style.bg(Colors.BLACK);
        Screen actual = new Screen(2, 4);
        actual.fill(new Rect(0, 0, 2, 4), background);
        Screen expected = new Screen(2, 4);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                expected.put(row, col, " ", background, null, 1);
            }
        }
        assertThat(actual).isEqualTo(expected);
        actual.put(0, 1, "漢", Style.NONE, null, 2);
        expected.put(0, 1, "漢", Style.NONE, null, 2);
        actual.clear(1, 2);
        expected.clear(1, 2);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.cell(1, 1).style()).isEqualTo(background);
        actual.fill(new Rect(0, 0, 1, 1), Style.bg(Colors.WHITE));
        assertThat(actual.cell(1, 1).style()).isEqualTo(background);
    }

    @Test
    void wrappingAWideRowDoesNotCopyItsGrowingPrefixPerGrapheme() {
        StyledText text = StyledText.plain("x".repeat(2048));
        long bytes = allocated(() -> sink = TextWrap.wrap(text, WidthPolicy.PINNED, 2048));
        assertThat(bytes)
                .as("one wrapped row should not allocate quadratic prefix copies")
                .isLessThan(1_000_000);
        assertThat(TextWrap.wrap(text, WidthPolicy.PINNED, 2048)).containsExactly(text);
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
        assertThat(TextWrap.wrap(text, WidthPolicy.PINNED, 4))
                .containsExactly(
                        StyledText.of("ab🙂", red, url), StyledText.of("c   ", blue, url),
                        StyledText.of("d", blue, url), StyledText.of("z", blue, url));
    }

    @Test
    void concatKeepsTheExistingAppendSemanticsIncludingEmptyStyledSpans() {
        StyledText[] parts = {
            StyledText.empty(),
            StyledText.of("\u001b", Style.NONE.withBold()),
            StyledText.plain("a"),
            StyledText.plain("b"),
            StyledText.of("c", Style.NONE, "https://example.com"),
            StyledText.empty()
        };
        StyledText expected = StyledText.empty();
        for (StyledText part : parts) {
            expected = expected.append(part);
        }
        assertThat(StyledText.concat(parts)).isEqualTo(expected);
        assertThat(StyledText.concat()).isEqualTo(StyledText.empty());
    }
}
