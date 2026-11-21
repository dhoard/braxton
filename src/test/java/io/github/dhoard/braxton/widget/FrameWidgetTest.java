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
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The frame draws a border and a title around content it does not own.
 */
class FrameWidgetTest {

    private static final Style BOLD = Style.NONE.withBold();

    private static Screen paint(Frame<?> widget, Frame.Model model, int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        widget.paint(screen, new Rect(0, 0, rows, cols), model);
        return screen;
    }

    @Test
    void theBorderFollowsTheRectangleItIsGiven() {
        Screen screen = paint(Frame.of(), Frame.Model.of(Border.SINGLE), 4, 8);

        assertThat(screen.plainLines())
                .containsExactly(
                        "\u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
                        "\u2502      \u2502",
                        "\u2502      \u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void theTitleSitsOnTheTopEdgeOneColumnInFromTheCorner() {
        Frame.Model model = Frame.Model.of(Border.SINGLE).withTitle(StyledText.of("Files", BOLD));

        Screen screen = paint(Frame.of(), model, 3, 10);

        assertThat(screen.plainLine(0)).isEqualTo("\u250C\u2500Files\u2500\u2500\u2510");
        assertThat(screen.cell(0, 2).style()).isEqualTo(BOLD);
        assertThat(screen.cell(0, 1).style()).isEqualTo(Style.NONE);
    }

    @Test
    void aTitleNarrowerThanTheEdgeIsClippedAndAWideOneWrapsAway() {
        Frame<Object> narrow = Frame.of();
        Frame.Model model = Frame.Model.of(Border.SINGLE).withTitle(StyledText.of("abcdef", Style.NONE));

        // The title starts two columns in and has cols-4 columns, so four columns hold none at all.
        assertThat(paint(narrow, model, 3, 4).plainLine(0)).isEqualTo("\u250C\u2500\u2500\u2510");
        assertThat(paint(narrow, model, 3, 5).plainLine(0)).isEqualTo("\u250C\u2500a\u2500\u2510");
        assertThat(paint(narrow, model, 3, 9).plainLine(0)).isEqualTo("\u250C\u2500abcde\u2500\u2510");
    }

    @Test
    void anInvisibleBorderAndARectangleTooSmallForOnePaintNothing() {
        Screen screen = new Screen(2, 4);

        Frame.of().paint(screen, new Rect(0, 0, 2, 4), Frame.Model.of(Border.NONE));
        Frame.of().paint(screen, new Rect(0, 0, 1, 4), Frame.Model.of(Border.SINGLE));
        Frame.of().paint(screen, new Rect(0, 0, 2, 1), Frame.Model.of(Border.SINGLE));
        Frame.of().paint(screen, new Rect(0, 0, 2, 4), null);

        assertThat(screen.plainText()).isEqualTo("\n");
    }

    @Test
    void aBuiltTopRowReplacesTheTitleAndStaysInsideTheFrame() {
        Frame<String> frame = Frame.of();
        Frame.Model model = Frame.Model.of(Border.SINGLE)
                .withTitle(StyledText.of("ignored", BOLD))
                .withTopEdge(cols -> StyledText.of("|" + " ".repeat(Math.max(0, cols - 2)) + "|", BOLD));

        Screen screen = paint(frame, model, 3, 8);

        // The builder's row is the top edge; the title a model also carries does not paint over it.
        assertThat(screen.plainLine(0)).isEqualTo("|      |");
        assertThat(screen.plainLine(1)).isEqualTo("\u2502      \u2502");
        assertThat(screen.plainLine(2)).isEqualTo("\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
        // A row wider than the frame is clipped to the columns the frame was given.
        Screen narrowed = paint(frame, model.withTopEdge(cols -> StyledText.of("0123456789", BOLD)), 2, 4);
        assertThat(narrowed.plainLine(0)).isEqualTo("0123");
    }

    @Test
    void aTopEdgeElementFramesContentTheWayAFrameDoes() {
        Element<String> box = Elements.topEdge(
                Border.SINGLE,
                Style.NONE,
                cols -> StyledText.of("[" + "=".repeat(Math.max(0, cols - 2)) + "]", Style.NONE),
                Elements.text("body"));

        Screen screen = new Screen(4, 8);
        ElementTree.paint(ElementTree.layout(box, new Rect(0, 0, 4, 8)), screen);

        assertThat(screen.plainLines())
                .containsExactly(
                        "[======]",
                        "\u2502body  \u2502",
                        "\u2502      \u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void aDecorationTakesNoSpaceAndNoPointer() {
        assertThat(Frame.of().measure(new Size(10, 10), Frame.Model.of(Border.SINGLE)))
                .isEqualTo(new Size(0, 0));
        assertThat(Frame.of().canFocus(Frame.Model.of(Border.SINGLE))).isFalse();
        assertThat(Frame.of().hit(new Rect(0, 0, 4, 8), 0, 0, Frame.Model.of(Border.SINGLE)))
                .isFalse();
    }

    @Test
    void chromeAndContentComposeIntoABorderedRegion() {
        // The tree carries the application's message type, and leaves that emit nothing still fit it.
        Element<String> chrome = new Element.Leaf<>(
                Frame.<String>of(), Frame.Model.of(Border.ROUNDED).withTitle(StyledText.of("T", Style.NONE)));
        Element<String> content = new Element.Leaf<>(Text.<String>of(), StyledText.of("hi", Style.NONE));
        Element<String> root = new Element.Stack<>(List.of(chrome, new Element.Pad<>(new Insets(1, 1, 1, 1), content)));
        var placed = ElementTree.layout(root, new Rect(0, 0, 4, 8));
        Screen screen = new Screen(4, 8);

        ElementTree.paint(placed, screen);

        assertThat(screen.plainLine(0)).isEqualTo("\u256D\u2500T\u2500\u2500\u2500\u2500\u256E");
        assertThat(screen.plainLine(1)).isEqualTo("\u2502hi    \u2502");
        // A click inside the region reaches the content, not the frame that draws around it; the
        // edge itself belongs to nothing, because a decoration never takes a pointer.
        assertThat(ElementTree.hit(placed, 1, 1)).isSameAs(content);
        assertThat(ElementTree.hit(placed, 0, 0)).isNull();
    }
}
