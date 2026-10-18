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

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Style;

/** Randomized painting keeps the wide-glyph bookkeeping consistent. */
class ScreenPropertyTest {

    @Test
    void everyContinuationCellFollowsItsWideGlyph() {
        // Glyph and the width a caller must pass for it: a wide cluster is two columns.
        String[][] glyphs = {{"a", "1"}, {"\u65E5", "2"}, {"\uD83D\uDE00", "2"}, {" ", "1"},
                {"\u0301", "1"}};
        Random random = new Random(11L);
        for (int iteration = 0; iteration < 500; iteration++) {
            int cols = 1 + random.nextInt(8);
            Screen screen = new Screen(2, cols);
            for (int step = 0; step < 20; step++) {
                int row = random.nextInt(2);
                int col = random.nextInt(cols);
                if (random.nextInt(4) == 0) {
                    screen.clear(row, col);
                } else {
                    screen.fill(new io.github.dhoard.braxton.layout.Rect(row, col,
                            random.nextInt(2), 1 + random.nextInt(2)), Style.NONE.withBold());
                }
                String[] glyph = glyphs[random.nextInt(glyphs.length)];
                screen.put(row, col, glyph[0], Style.NONE, null, Integer.parseInt(glyph[1]));
                for (int c = 0; c < cols; c++) {
                    if (screen.isContinuation(row, c)) {
                        assertThat(c).as("a continuation needs a cell before it").isPositive();
                        assertThat(screen.isContinuation(row, c - 1)).as("no two continuations in a row")
                                .isFalse();
                        assertThat(screen.cell(row, c - 1).text()).as("the glyph precedes its continuation")
                                .isNotEmpty();
                    }
                }
                assertThat(io.github.dhoard.braxton.style.TextMeasure.columns(screen.plainLine(row)))
                        .as("a painted row never exceeds the grid").isLessThanOrEqualTo(cols);
            }
        }
    }
}
