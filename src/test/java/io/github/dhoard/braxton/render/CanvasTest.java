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

package io.github.dhoard.braxton.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The public frame view exposes text, styles and wide-cell continuations without internal types.
 */
class CanvasTest {

    @Test
    void plainLinesBecomeAReadableCanvas() {
        Canvas canvas = Canvas.fromLines(List.of("ab", "c"));

        assertThat(canvas.rows()).isEqualTo(2);
        assertThat(canvas.cols()).isEqualTo(2);
        assertThat(canvas.text(0, 1)).isEqualTo("b");
        assertThat(canvas.text(1, 1)).isEmpty();
        assertThat(canvas.isContinuation(0, 1)).isFalse();
        assertThat(canvas.plainText()).isEqualTo("ab\nc");
        assertThat(canvas.cell(9, 9)).isEqualTo(new Canvas.Cell("", Style.NONE, null));
    }

    @Test
    void aRendererCanvasKeepsStylesAndWideOccupancy() {
        Style red = Style.fg(new Color(220, 20, 20));
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "你", red, "https://example.test", 2);

        Canvas canvas = screen.toCanvas();

        assertThat(canvas.text(0, 0)).isEqualTo("你");
        assertThat(canvas.style(0, 0)).isEqualTo(red);
        assertThat(canvas.link(0, 0)).isEqualTo("https://example.test");
        assertThat(canvas.isContinuation(0, 1)).isTrue();
        assertThat(canvas.plainLine(0)).isEqualTo("你");
        assertThat(canvas).isEqualTo(screen.toCanvas());
    }
}
