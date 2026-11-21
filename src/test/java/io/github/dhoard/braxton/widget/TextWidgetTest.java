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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import org.junit.jupiter.api.Test;

/**
 * The text widget measures, wraps and paints styled values as whole cells.
 */
class TextWidgetTest {

    private static final Style BOLD = Style.NONE.withBold();

    private static StyledText plain(String text) {
        return StyledText.of(text, Style.NONE);
    }

    private static Screen paint(Text<?> widget, StyledText model, int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        Rect bounds = new Rect(0, 0, rows, cols);
        widget.paint(screen, bounds, model);
        return screen;
    }

    @Test
    void aShortValueMeasuresItsOwnColumns() {
        Text<Object> text = Text.of();

        assertThat(text.measure(new Size(5, 20), plain("hello"))).isEqualTo(new Size(1, 5));
        assertThat(text.measure(new Size(5, 3), plain("hello"))).isEqualTo(new Size(2, 3));
        assertThat(text.measure(new Size(1, 20), plain("hello world"))).isEqualTo(new Size(1, 11));
    }

    @Test
    void nothingToMeasureIsNoSizeAtAll() {
        Text<Object> text = Text.of();

        assertThat(text.measure(new Size(4, 4), null)).isEqualTo(new Size(0, 0));
        assertThat(text.measure(new Size(0, 4), plain("a"))).isEqualTo(new Size(0, 0));
        assertThat(text.measure(new Size(4, 0), plain("a"))).isEqualTo(new Size(0, 0));
        assertThat(text.measure(new Size(4, 4), plain(""))).isEqualTo(new Size(1, 0));
    }

    @Test
    void theValueWrapsToTheBoundsAndPaintsEveryLine() {
        Screen screen = paint(Text.of(), plain("alpha beta"), 4, 5);

        assertThat(screen.plainLine(0)).isEqualTo("alpha");
        assertThat(screen.plainLine(1)).isEqualTo("beta");
        assertThat(screen.toCanvas().text(0, 0)).isEqualTo("a");
        assertThat(screen.plainLine(2)).isEmpty();
    }

    @Test
    void onlyTheRowsTheBoundsHoldArePainted() {
        Screen screen = paint(Text.of(), plain("alpha beta gamma"), 1, 5);

        assertThat(screen.plainLines()).containsExactly("alpha");
    }

    @Test
    void everyRunKeepsItsOwnStyleAndLink() {
        StyledText model = plain("ab").append(StyledText.of("cd", BOLD, "https://example.test"));

        Screen screen = paint(Text.of(), model, 1, 10);

        assertThat(screen.plainLine(0)).isEqualTo("abcd");
        assertThat(screen.cell(0, 1).style()).isEqualTo(Style.NONE);
        assertThat(screen.cell(0, 2).style()).isEqualTo(BOLD);
        assertThat(screen.cell(0, 3).link()).isEqualTo("https://example.test");
    }

    @Test
    void aWideClusterIsPaintedWholeWithItsContinuation() {
        Screen screen = paint(Text.of(), plain("a\uD83D\uDE00b"), 2, 4);

        assertThat(screen.plainLine(0)).isEqualTo("a\uD83D\uDE00b");
        assertThat(screen.isContinuation(0, 2)).isTrue();
        assertThat(screen.cell(0, 1).text()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void aStyleBoundaryDoesNotSplitTheClusterItDivides() {
        // A heart and the selector that widens it, arriving in two spans, paint as one two-column cell.
        StyledText split = plain("\u2764").append(StyledText.of("\uFE0F", BOLD));

        Screen screen = paint(Text.of(), split, 1, 4);

        assertThat(screen.cell(0, 0).text()).isEqualTo("\u2764\uFE0F");
        assertThat(screen.isContinuation(0, 1)).isTrue();
        assertThat(screen.cell(0, 2).text()).isEmpty();
    }

    @Test
    void aClusterThatContinuesTheCellBeforeItStaysInThatCell() {
        Screen screen = paint(Text.of(), plain("e\u0301x"), 1, 4);

        assertThat(screen.cell(0, 0).text()).isEqualTo("e\u0301");
        assertThat(screen.cell(0, 1).text()).isEqualTo("x");
    }

    @Test
    void theWidthPolicyCanBeReplacedWithoutTouchingTheWidget() {
        assertThat(Text.of().withPolicy(WidthPolicy.PINNED).measure(new Size(2, 4), plain("abcd")))
                .isEqualTo(new Size(1, 4));
        assertThat(Text.of(WidthPolicy.PINNED).measure(new Size(2, 4), plain("abcd")))
                .isEqualTo(new Size(1, 4));
    }

    @Test
    void anEmptyBoundsPaintsNothing() {
        Screen screen = paint(Text.of(), plain("abc"), 2, 3);

        Text.of().paint(screen, new Rect(0, 0, 0, 3), plain("abc"));
        Text.of().paint(screen, new Rect(0, 0, 2, 0), plain("abc"));
        Text.of().paint(screen, new Rect(0, 0, 2, 3), null);

        assertThat(screen.plainLines()).containsExactly("abc", "");
    }
}
