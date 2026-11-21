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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import org.junit.jupiter.api.Test;

/**
 * A box is measured where it renders and paints the cells it measured.
 */
class BoxElementTest {

    @Test
    void aResponsiveBoxTakesTheWidthItIsOffered() {
        Element<String> box = Box.of("hi").element();

        assertThat(ElementTree.measure(box, new Size(6, 10))).isEqualTo(new Size(3, 10));
        assertThat(ElementScreen.render(box, 6, 10).lines())
                .containsExactly(
                        "\u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
                        "\u2502hi      \u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518",
                        "",
                        "",
                        "");
    }

    @Test
    void aFixedWidthBoxKeepsItsOwnColumns() {
        Element<String> box = Box.of("hi").width(6).element();

        assertThat(ElementTree.measure(box, new Size(6, 20))).isEqualTo(new Size(3, 6));
        assertThat(ElementScreen.render(box, 3, 20).lines())
                .containsExactly(
                        "\u250C\u2500\u2500\u2500\u2500\u2510",
                        "\u2502hi  \u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void aTitleIsPartOfTheTopEdgeAndTheStyleAppliesToIt() {
        Element<String> box = Box.of("x")
                .border(Border.ROUNDED)
                .title("T")
                .style(Style.NONE.withBold())
                .element();

        ElementScreen screen = ElementScreen.render(box, 3, 8);

        assertThat(screen.lines().get(0)).isEqualTo("\u256D\u2500 T \u2500\u2500\u256E");
        assertThat(screen.canvas().style(0, 3)).isEqualTo(Style.NONE.withBold());
    }

    @Test
    void aBoxNarrowerThanItsContentIsClippedRatherThanWrapped() {
        Element<String> box = Box.of(StyledText.of("abcdefgh", Style.NONE)).element();

        assertThat(ElementScreen.render(box, 3, 5).lines())
                .containsExactly("\u250C\u2500\u2500\u2500\u2510", "\u2502abc\u2502", "\u2514\u2500\u2500\u2500\u2518");
    }

    @Test
    void anEmptyRectanglePaintsNothing() {
        Element<String> box = Box.of("hi").element();

        // A screen with no columns still has rows; each reads back empty.
        assertThat(ElementScreen.render(box, 3, 0).lines()).containsExactly("", "", "");
        assertThat(ElementTree.measure(box, new Size(0, 0))).isEqualTo(new Size(0, 0));
    }

    @Test
    void aBoxComposesBesideOtherContent() {
        Element<String> root = Elements.row(Box.of("a").width(5).element(), Elements.text("tail"));

        ElementScreen screen = ElementScreen.render(root, 3, 12);

        // Both children start on the row's first line, so the text sits beside the frame's top edge.
        assertThat(screen.lines().get(0)).isEqualTo("\u250C\u2500\u2500\u2500\u2510tail");
        assertThat(screen.lines().get(1)).isEqualTo("\u2502a  \u2502");
        assertThat(screen.hit(0, 6)).isNotNull();
        assertThat(screen.placement().children()).hasSize(2);
    }

    @Test
    void theRendererIsStillPure() {
        Box box = Box.of("hi").width(6);

        assertThat(box.render(6).plainText()).isEqualTo(box.render(6).plainText());
        assertThat(box.isResponsive()).isFalse();
        assertThat(new Rect(0, 0, 1, 1).isEmpty()).isFalse();
    }
}
