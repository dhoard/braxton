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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** Wide-glyph bookkeeping in the painted cell grid. */
class ScreenTest {

    @Test
    void paintingOverTheFirstHalfOfAWideGlyphClearsTheContinuationCell() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "\u65E5", Style.NONE, null, 2);
        screen.put(0, 0, "x", Style.NONE, null, 1);
        screen.put(0, 1, "y", Style.NONE, null, 1);
        assertThat(screen.cell(0, 0).text()).isEqualTo("x");
        assertThat(screen.cell(0, 1).text()).isEqualTo("y");
        assertThat(screen.plainLine(0)).isEqualTo("xy");
    }

    @Test
    void aNarrowOverlayReplacesAWideUnderlayItFullyCovers() {
        View<?> view = Views.overlay(Views.text("\u65E5\u672C"), Views.text("abcd"));
        Screen screen = new LayoutEngine().layout(view, new Rect(0, 0, 1, 4)).screen();
        assertThat(screen.plainLine(0)).isEqualTo("abcd");
    }
}
