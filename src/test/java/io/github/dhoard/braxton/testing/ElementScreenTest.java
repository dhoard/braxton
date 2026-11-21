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

package io.github.dhoard.braxton.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ThemedWidget;
import io.github.dhoard.braxton.widget.Elements;
import org.junit.jupiter.api.Test;

/**
 * A test reads a painted element tree the same way a frame would show it.
 */
class ElementScreenTest {

    /**
     * A widget that paints one theme token, so the themed path has something to show.
     */
    private record Bearer() implements ThemedWidget<Object, String> {
        @Override
        public Size measure(Size max, Object model) {
            return new Size(1, 1);
        }

        @Override
        public void paint(Surface surface, Rect bounds, Object model, Theme theme) {
            surface.put(
                    bounds.row(),
                    bounds.col(),
                    "T",
                    theme.style(io.github.dhoard.braxton.style.ThemeToken.TEXT),
                    null,
                    1);
        }
    }

    @Test
    void theScreenHoldsWhatTheFrameWouldShow() {
        ElementScreen screen = ElementScreen.render(Elements.text("hello"), 2, 8);

        assertThat(screen.lines()).containsExactly("hello", "");
        assertThat(screen.text()).isEqualTo("hello\n");
        assertThat(screen.canvas().text(0, 1)).isEqualTo("e");
        // The root leaf is placed in the rectangle it measures, inside the screen.
        assertThat(screen.placement().bounds()).isEqualTo(new Rect(0, 0, 1, 5));
    }

    @Test
    void aPointerReachesTheLeafThatAcceptsTheCell() {
        Element<String> left = Elements.text("ab");
        Element<String> right = Elements.text("cd");
        ElementScreen screen = ElementScreen.render(Elements.row(left, right), 1, 6);

        assertThat(screen.hit(0, 0)).isSameAs(left);
        assertThat(screen.hit(0, 2)).isSameAs(right);
        // The cells past the last child belong to nothing.
        assertThat(screen.hit(0, 5)).isNull();
    }

    @Test
    void theCaretIsTheFocusedWidgets() {
        Element<String> focused = new Element.Leaf<>(
                new ThemedWidget<>() {
                    @Override
                    public Size measure(Size max, String model) {
                        return new Size(1, 4);
                    }

                    @Override
                    public boolean focused(String model) {
                        return true;
                    }

                    @Override
                    public Cursor cursor(Rect bounds, String model) {
                        return Cursor.at(bounds.row(), bounds.col() + 2);
                    }

                    @Override
                    public void paint(Surface surface, Rect bounds, String model) {}

                    @Override
                    public void paint(Surface surface, Rect bounds, String model, Theme theme) {}
                },
                "text");

        ElementScreen screen = ElementScreen.render(focused, 3, 6);

        assertThat(screen.cursor()).isNotNull();
        assertThat(screen.cursor().row()).isZero();
        assertThat(screen.cursor().column()).isEqualTo(2);
    }

    @Test
    void aThemedScreenPaintsThroughTheTheme() {
        Element<String> root = new Element.Leaf<>(new Bearer(), null);

        ElementScreen light = ElementScreen.render(root, 1, 1, Themes.LIGHT);
        ElementScreen dark = ElementScreen.render(root, 1, 1, Themes.DARK);

        assertThat(light.canvas().text(0, 0)).isEqualTo("T");
        assertThat(light.canvas().style(0, 0)).isNotEqualTo(Style.NONE);
        assertThat(light.canvas().style(0, 0)).isNotEqualTo(dark.canvas().style(0, 0));
    }

    @Test
    void anEmptyScreenIsStillReadable() {
        ElementScreen screen = ElementScreen.render(Elements.text("x"), 0, 0);

        assertThat(screen.lines()).isEmpty();
        assertThat(screen.hit(0, 0)).isNull();
        assertThat(screen.cursor()).isNull();
    }
}
