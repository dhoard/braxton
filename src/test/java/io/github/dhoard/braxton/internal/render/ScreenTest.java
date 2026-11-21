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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import org.junit.jupiter.api.Test;

/**
 * Wide-glyph bookkeeping in the painted cell grid.
 */
class ScreenTest {

    /**
     * One frame of {@code tree}, painted the way the runtime paints it.
     */
    private static Screen paint(Element<?> tree, int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        io.github.dhoard.braxton.ui.ElementTree.paint(
                io.github.dhoard.braxton.ui.ElementTree.layout(tree, new Rect(0, 0, rows, cols)), screen);
        return screen;
    }

    @Test
    void excessiveDimensionsAreRejectedBeforeAllocatingOrOverflowing() {
        for (int[] dimensions : new int[][] {
            {65536, 65536}, {65535, 65535}, {Integer.MAX_VALUE, 2},
            {Integer.MAX_VALUE, 0}, {0, Integer.MAX_VALUE}, {2049, 2048}
        }) {
            assertThatThrownBy(() -> new Screen(dimensions[0], dimensions[1]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("screen dimensions");
        }
        assertThat(new Screen(0, 4_194_304).isEmpty()).isTrue();
        assertThat(new Screen(-1, 80).isEmpty()).isTrue();
        assertThat(new Screen(24, 0).plainLines()).hasSize(24);
    }

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
    void clearingOneHalfOfAWideGlyphClearsBothHalves() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "\u65E5", Style.NONE, null, 2);
        screen.clear(0, 1);
        assertThat(screen.cell(0, 0).text()).isEmpty();
        assertThat(screen.isContinuation(0, 0)).isFalse();
        assertThat(screen.isContinuation(0, 1)).isFalse();
        assertThat(screen.plainLine(0)).isEmpty();
    }

    @Test
    void aFillCoveringTheSecondHalfOfAWideGlyphDoesNotLeaveHalfAGlyph() {
        Element<?> view = Elements.stack(
                Elements.text("\u65E5"),
                Elements.row(
                        Elements.fixed(1, Elements.spacer()),
                        Elements.fixed(1, Elements.fill(Style.bg(Colors.BLUE_400)))));
        Screen screen = paint(view, 1, 4);
        assertThat(screen.cell(0, 0).text()).isEmpty();
        assertThat(screen.cell(0, 1).style().background()).isEqualTo(Colors.BLUE_400);
    }

    @Test
    void overwritingTheSecondHalfOfABackgroundWideGlyphKeepsItsColumn() {
        Screen screen = new Screen(1, 4);
        screen.fill(new Rect(0, 0, 1, 4), Style.bg(Colors.BLACK));
        screen.put(0, 0, "\u65E5", Style.NONE, null, 2);
        screen.put(0, 1, "x", Style.NONE, null, 1);
        // The cleared half is a blank cell, so it still occupies its column in the row and in
        // the bytes the terminal receives.
        assertThat(screen.plainLine(0)).isEqualTo(" x");
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).endsWith(" x  \u001B[0m");
    }

    @Test
    void plainLineKeepsInteriorBlanksAsColumns() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        screen.put(0, 3, "b", Style.NONE, null, 1);
        assertThat(screen.plainLine(0)).isEqualTo("a  b");
    }

    @Test
    void aWideOverlayGlyphDoesNotLeaveAStaleContinuationBehind() {
        Element<?> view = Elements.stack(
                Elements.text("\u65E5\u5F0F"),
                Elements.row(
                        Elements.fixed(4, Elements.pad(Insets.symmetric(0, 1), Elements.text("\u672C"))),
                        Elements.fixed(1, Elements.text("x"))));
        Screen screen = paint(view, 1, 5);
        assertThat(screen.cell(0, 1).text()).isEqualTo("\u672C");
        assertThat(screen.isContinuation(0, 2)).isTrue();
        assertThat(screen.isContinuation(0, 3)).isFalse();
        assertThat(screen.cell(0, 4).text()).isEqualTo("x");
    }

    @Test
    void aNarrowOverlayReplacesAWideUnderlayItFullyCovers() {
        Element<?> view = Elements.stack(Elements.text("\u65E5\u672C"), Elements.text("abcd"));
        Screen screen = paint(view, 1, 4);
        assertThat(screen.plainLine(0)).isEqualTo("abcd");
    }
}
