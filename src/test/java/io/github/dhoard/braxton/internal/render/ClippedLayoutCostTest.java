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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.testing.AllocationProbe;

class ClippedLayoutCostTest {

    @Test
    void clippedTextDoesNotMaterializeOffscreenWrappedRows() {
        LayoutEngine engine = new LayoutEngine();
        for (View<Object> view : List.of(Views.<Object>text("x\n".repeat(20000)),
                Views.<Object>column(Views.text("x\n".repeat(20000))))) {
            assertThat(AllocationProbe.bytes(() -> engine.layout(view, new Rect(0, 0, 24, 80))))
                    .isLessThan(1_000_000);
        }
    }

    @Test
    void zeroWidthSuffixIsAccumulatedBeforeRepaintingItsCell() {
        LayoutEngine engine = new LayoutEngine();
        View<Object> view = Views.text("a" + "\u200B".repeat(4096));
        assertThat(AllocationProbe.bytes(() -> engine.layout(view, new Rect(0, 0, 1, 80))))
                .isLessThan(2_000_000);
        assertThat(engine.layout(view, new Rect(0, 0, 1, 80)).screen().cell(0, 0).text())
                .isEqualTo("a" + "\u200B".repeat(4096));
    }

    @Test
    void clippedPaintingMatchesFullWrappingAcrossWidthsAndPolicies() {
        StyledText text = StyledText.of("ab🙂\tcd\n\n ", Style.NONE.withBold(), "https://example.com")
                .append(StyledText.of("界é  xyz\t\nlast\u200B", Style.NONE));
        for (WidthPolicy policy : List.of(WidthPolicy.PINNED, WidthPolicy.ONE_COLUMN)) {
            LayoutEngine engine = new LayoutEngine(policy);
            for (int cols = 1; cols <= 12; cols++) {
                for (int rows = 1; rows <= 8; rows++) {
                    Screen expected = new Screen(rows, cols);
                    List<StyledText> lines = engine.wrapLines(text, cols);
                    for (int i = 0; i < Math.min(rows, lines.size()); i++) {
                        Screen one = engine.layout(Views.text(lines.get(i)), new Rect(0, 0, 1, cols)).screen();
                        for (int c = 0; c < cols; c++) {
                            if (!one.isContinuation(0, c)) {
                                Cell cell = one.cell(0, c);
                                int width = c + 1 < cols && one.isContinuation(0, c + 1) ? 2 : 1;
                                expected.put(i, c, cell.text(), cell.style(), cell.link(), width);
                            }
                        }
                    }
                    assertThat(engine.layout(Views.text(text), new Rect(0, 0, rows, cols)).screen())
                            .isEqualTo(expected);
                    assertThat(engine.intrinsicHeight(Views.text(text), cols)).isEqualTo(lines.size());
                }
            }
        }
    }
}
