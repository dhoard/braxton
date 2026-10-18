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

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** Every rendered table row shares one width, bordered or not. */
class TablePropertyTest {

    @Test
    void rowsShareOneWidthForRandomCellsAndBorders() {
        Random random = new Random(23L);
        String[] cells = {"a", "\u65E5", "ccc", "", "e\u0301"};
        for (int iteration = 0; iteration < 200; iteration++) {
            Table table = Table.of().buffer(random.nextInt(4))
                    .border(random.nextBoolean() ? Border.SINGLE : Border.NONE);
            int columns = 1 + random.nextInt(3);
            int rows = 1 + random.nextInt(3);
            for (int row = 0; row < rows; row++) {
                java.util.List<StyledText> values = new java.util.ArrayList<>();
                for (int column = 0; column < columns; column++) {
                    values.add(StyledText.plain(cells[random.nextInt(cells.length)]));
                }
                table.row(values);
            }
            java.util.List<Integer> widths = table.renderLines().stream()
                    .map(line -> TextMeasure.columns(line.plainText())).distinct().toList();
            assertThat(widths).as("iteration %d", iteration).hasSizeLessThanOrEqualTo(1);
            java.util.List<Integer> renderedWidths = table.render().plainText().lines()
                    .map(TextMeasure::columns).distinct().toList();
            assertThat(renderedWidths).as("framed iteration %d", iteration)
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    void theBorderStyleAppliesToTheFrameOnly() {
        Table table = Table.of().row(io.github.dhoard.braxton.style.StyledText.plain("cell"))
                .border(io.github.dhoard.braxton.layout.Border.SINGLE)
                .borderStyle(io.github.dhoard.braxton.style.Style.NONE.withBold());
        java.util.List<io.github.dhoard.braxton.style.StyledText.Span> spans =
                table.render().spans();
        assertThat(spans.get(0).style().bold()).as("the top edge carries the border style").isTrue();
        assertThat(spans).anySatisfy(span -> {
            assertThat(span.text()).contains("cell");
            assertThat(span.style().bold()).as("the cell keeps its own style").isFalse();
        });
    }
}
