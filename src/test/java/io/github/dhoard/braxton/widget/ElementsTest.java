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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.Sizing;
import org.junit.jupiter.api.Test;

/**
 * The element vocabulary an application's view is written with.
 */
class ElementsTest {

    private static Screen paint(Element<String> root, int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        ElementTree.paint(ElementTree.layout(root, new Rect(0, 0, rows, cols)), screen);
        return screen;
    }

    @Test
    void emptyAndMissingGroupArgumentsRemainEmpty() {
        assertThat(Elements.<String>row().children()).isEmpty();
        assertThat(Elements.<String>column().children()).isEmpty();
        assertThat(Elements.row((Element<String>[]) null).children()).isEmpty();
        assertThat(Elements.column((Element<String>[]) null).children()).isEmpty();
        assertThat(Elements.row(Alignment.END, (Element<String>[]) null).children())
                .isEmpty();
        assertThat(Elements.column(Alignment.END, (Element<String>[]) null).children())
                .isEmpty();
        assertThat(Elements.<String>stack().children()).isEmpty();
    }

    @Test
    void compositionRejectsNullChildrenAndAMissingStackArray() {
        assertThatNullPointerException().isThrownBy(() -> Elements.row((Element<String>) null));
        assertThatNullPointerException().isThrownBy(() -> Elements.column((Element<String>) null));
        assertThatNullPointerException().isThrownBy(() -> Elements.row(Alignment.END, (Element<String>) null));
        assertThatNullPointerException().isThrownBy(() -> Elements.column(Alignment.END, (Element<String>) null));
        assertThatNullPointerException().isThrownBy(() -> Elements.stack((Element<String>) null));
        assertThatNullPointerException().isThrownBy(() -> Elements.stack((Element<String>[]) null));
    }

    @Test
    void textOccupiesTheColumnsItPaints() {
        Element<String> root = Elements.row(Elements.text("hello", Style.NONE), Elements.text("!"));

        assertThat(paint(root, 2, 12).plainLine(0)).isEqualTo("hello!");
        assertThat(ElementTree.measure(root, new io.github.dhoard.braxton.layout.Size(2, 12)))
                .isEqualTo(new io.github.dhoard.braxton.layout.Size(1, 6));
    }

    @Test
    void aColumnStacksAndAnAlignmentPlacesTheNarrowChild() {
        Element<String> root = Elements.column(Alignment.END, Elements.text("ab"), Elements.text("c"));

        Screen screen = paint(root, 2, 4);
        assertThat(screen.plainLine(0)).isEqualTo("  ab");
        assertThat(screen.plainLine(1)).isEqualTo("   c");
    }

    @Test
    void paddingAndFramingSurroundTheContent() {
        Element<String> framed = Elements.frame(Border.SINGLE, Elements.text("ok"));

        assertThat(paint(framed, 4, 6).plainLines())
                .containsExactly(
                        "\u250C\u2500\u2500\u2500\u2500\u2510",
                        "\u2502ok  \u2502",
                        "\u2502    \u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2518");
        assertThat(paint(Elements.pad(1, Elements.text("x")), 3, 3).plainLine(1))
                .isEqualTo(" x");
    }

    @Test
    void aTitleSitsOnTheFrameEdge() {
        Element<String> root =
                Elements.frame(Border.ROUNDED, StyledText.of("T", Style.NONE), Style.NONE, Elements.text("x"));

        assertThat(paint(root, 3, 6).plainLine(0)).isEqualTo("\u256D\u2500T\u2500\u2500\u256E");
    }

    @Test
    void sizingDecidesWhatEachChildTakesOfTheMainAxis() {
        Element<String> row = Elements.row(
                Elements.fixed(3, Elements.text("abcdef")), Elements.grow(Elements.text("gh")), Elements.spacer(2));

        // The fixed child is clipped to three columns and the grown one takes the five that are left.
        assertThat(paint(row, 1, 10).plainLine(0)).isEqualTo("abcgh");
    }

    @Test
    void aStackPaintsInOrderAndAModalCoversWhatIsBehindIt() {
        // Layers paint over each other where they overlap, so a shorter one leaves the tail visible.
        Element<String> layers = Elements.stack(Elements.text("abcdef"), Elements.text("XY"));
        assertThat(paint(layers, 1, 8).plainLine(0)).isEqualTo("XYcdef");

        // A modal layer paints over what is behind it and is the only thing a pointer can reach.
        Element<String> modal = Elements.column(Elements.text("under"), Elements.modal(Elements.text("XX")));
        assertThat(paint(modal, 2, 6).plainLine(0)).isEqualTo("XXder");
    }

    @Test
    void everySizingHelperKeepsTheElementItWraps() {
        Element<String> text = Elements.text("x");

        assertThat(Elements.fixed(2, text).sizing()).isEqualTo(Sizing.fixed(2));
        assertThat(Elements.grow(text).sizing()).isEqualTo(Sizing.grow());
        assertThat(Elements.grow(3, text).sizing()).isEqualTo(Sizing.grow(3));
        assertThat(Elements.measured(text).sizing()).isEqualTo(Sizing.measured());
        // A modal layer is not a sibling and keeps the sizing it has.
        Element<String> modal = Elements.modal(text);
        assertThat(Elements.grow(modal)).isSameAs(modal);
    }

    @Test
    void aFillBacksEveryCellWithItsStyleAndTakesNothingElse() {
        Style tint = Style.NONE.withBackground(new io.github.dhoard.braxton.style.Color(0x20, 0x20, 0x20));
        Screen screen = paint(Elements.stack(Elements.fill(tint), Elements.text("hi")), 2, 4);

        // The fill is opaque: every cell it covers carries its background, painted cells included.
        assertThat(screen.cell(0, 0).style().background()).isEqualTo(tint.background());
        assertThat(screen.cell(1, 3).style().background()).isEqualTo(tint.background());
        assertThat(screen.plainLine(0)).isEqualTo("hi");

        Fill<String> fill = Fill.of();
        assertThat(fill.canFocus(tint)).isFalse();
        assertThat(fill.hit(new Rect(0, 0, 2, 4), 1, 1, tint))
                .as("a background is not a pointer target")
                .isFalse();
    }

    @Test
    void aSpacerTakesSpaceAndNothingElse() {
        Screen screen = paint(Elements.row(Elements.text("a"), Elements.spacer(3), Elements.text("b")), 1, 6);

        assertThat(screen.plainLine(0)).isEqualTo("a   b");
        Spacer<String> spacer = Spacer.of();
        assertThat(spacer.canFocus(Spacer.Model.NONE)).isFalse();
        assertThat(spacer.hit(new Rect(0, 0, 1, 4), 0, 1, Spacer.Model.NONE)).isFalse();
        assertThat(paint(Elements.pad(new Insets(0, 0, 0, 0), Elements.text("z")), 1, 2)
                        .plainLine(0))
                .isEqualTo("z");
    }
}
