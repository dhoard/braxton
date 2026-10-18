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

package io.github.dhoard.braxton.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;

/** The terse factories produce the geometry the view records describe. */
class ViewsTest {

    private final ScreenRenderer renderer = new ScreenRenderer();

    @Test
    void gapReservesExactlyItsRowsAndNothingElse() {
        View<String> view = Views.column(Views.text("top"), Views.gap(2), Views.text("bottom"));
        assertThat(renderer.plainLines(view, new Viewport(5, 8)))
                .containsExactly("top", "", "", "bottom", "");
        assertThat(renderer.plainLines(Views.gap(0), new Viewport(2, 4)))
                .as("a zero gap is a blank placeholder")
                .containsExactly("", "");
        assertThat(renderer.plainLines(Views.column(Views.text("a"), Views.gap(-3),
                Views.text("b")), new Viewport(3, 4)))
                .as("a negative gap is clamped instead of rejected")
                .containsExactly("a", "b", "");
    }

    @Test
    void blankAndFillOccupyTheirAllocationWithoutMessages() {
        assertThat(renderer.plainLines(Views.column(Views.text("a"), Views.blank(),
                Views.text("b")), new Viewport(3, 4))).containsExactly("a", "b", "");
        assertThat(Views.blank().children()).isEmpty();
        assertThat(Views.fill(Style.NONE).children()).isEmpty();
        assertThat(renderer.plainText(Views.fill(Style.NONE), new Viewport(2, 3)))
                .as("a fill paints its whole bounds with spaces")
                .isEqualTo("\n");
    }

    @Test
    void theAlignmentOverloadsPlaceChildrenLikeTheRecord() {
        List<View<String>> items = List.of(Views.text("ab"));
        assertThat(renderer.plainLines(Views.column(Alignment.END, items), new Viewport(1, 6)))
                .containsExactly("    ab");
        assertThat(renderer.plainLines(Views.column(Alignment.CENTER, items), new Viewport(1, 6)))
                .containsExactly("  ab");
        assertThat(renderer.plainLines(Views.column(items), new Viewport(1, 6)))
                .containsExactly("ab");
        assertThat(renderer.plainLines(Views.row(Alignment.END, List.of(Views.text("ab"))),
                new Viewport(2, 4)))
                .containsExactly("", "ab");
    }

    @Test
    void modalAndOverlayBuildTheDocumentedLayers() {
        View<String> base = Views.text("base");
        View<String> layer = Views.text("layer");
        View.Overlay<String> modal = (View.Overlay<String>) Views.modal(base, layer);
        assertThat(modal.modal()).as("modal restricts input to the top layer").isTrue();
        assertThat(modal.children()).containsExactly(base, layer);
        View.Overlay<String> overlay = (View.Overlay<String>) Views.overlay(base, layer);
        assertThat(overlay.modal()).isFalse();
        assertThat(overlay.children()).containsExactly(base, layer);
        assertThat(renderer.plainLines(Views.modal(base, layer), new Viewport(1, 5)))
                .as("the top layer is painted last")
                .containsExactly("layer");
    }

    @Test
    void borderOverloadsDifferOnlyInTitleAndStyle() {
        View<String> child = Views.text("body");
        View.BorderBox<String> plain = (View.BorderBox<String>) Views.border(Border.SINGLE, child);
        assertThat(plain.border()).isEqualTo(Border.SINGLE);
        assertThat(plain.title().isEmpty()).isTrue();
        assertThat(plain.style()).isEqualTo(Style.NONE);
        View.BorderBox<String> titled = (View.BorderBox<String>) Views.border(Border.DOUBLE,
                io.github.dhoard.braxton.style.StyledText.plain("t"), child);
        assertThat(titled.border()).isEqualTo(Border.DOUBLE);
        assertThat(titled.title().plainText()).isEqualTo("t");
        assertThat(titled.style()).isEqualTo(Style.NONE);
        View.BorderBox<String> styled = (View.BorderBox<String>) Views.border(Border.SINGLE,
                io.github.dhoard.braxton.style.StyledText.plain("t"), Style.NONE.withBold(), child);
        assertThat(styled.style().bold()).isTrue();
        assertThat(renderer.plainLines(plain, new Viewport(3, 6)))
                .containsExactly("\u250C\u2500\u2500\u2500\u2500\u2510", "\u2502body\u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2518");
    }
}
