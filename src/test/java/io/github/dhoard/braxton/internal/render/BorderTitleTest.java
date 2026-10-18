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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.Views;

/** A framed view keeps its width whatever the title contains. */
class BorderTitleTest {

    @Test
    void aWideTitleNeverWidensTheFrame() {
        for (int width = 2; width <= 12; width++) {
            for (String title : new String[] {"", "ab", "\u65E5", "\u65E5\u672C", "abcdefghij"}) {
                var view = Views.border(Border.SINGLE, StyledText.of(title, Style.NONE.withBold()),
                        Views.text("x"));
                Screen screen = new LayoutEngine().layout(view, new Rect(0, 0, 3, width)).screen();
                for (String line : screen.plainLines()) {
                    assertThat(TextMeasure.columns(line))
                            .as("width %d title [%s] line [%s]", width, title, line)
                            .isLessThanOrEqualTo(Math.max(2, width));
                }
            }
        }
    }
}
