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

package io.github.dhoard.braxton.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** Pixel art keeps one grid cell per pixel whatever symbol the caller asks for. */
class ImageViewTest {

    private static BufferedImage diagonalPixels() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0x00000000);
        image.setRGB(0, 1, 0x00000000);
        image.setRGB(1, 1, 0xFF00FF00);
        return image;
    }

    @Test
    void aPartiallyTransparentPixelIsPaintedWithItsColour() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80FF0000);
        StyledText rendered = ImageView.of(ImageAsset.of(image)).render();
        assertThat(rendered.plainText()).isEqualTo("\u2588");
        assertThat(rendered.spans().get(0).style().foreground())
                .isEqualTo(new io.github.dhoard.braxton.style.Color(0xFF, 0x00, 0x00));
    }

    @Test
    void aWideSymbolLeavesTransparentPixelsJustAsWideToKeepTheGridAligned() {
        StyledText rendered = ImageView.of(ImageAsset.of(diagonalPixels()))
                .symbol("\u65E5").render();
        assertThat(rendered.plainText()).isEqualTo("\u65E5  \n  \u65E5");
        assertThat(rendered.plainText().split("\n", -1))
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isEqualTo(4));
    }

    @Test
    void theSymbolDefaultsToABlockAndScalesHorizontally() {
        assertThat(ImageView.of(ImageAsset.of(diagonalPixels())).symbol(null).render().plainText())
                .isEqualTo("\u2588 \n \u2588");
        assertThat(ImageView.of(ImageAsset.of(diagonalPixels())).symbol("").render().plainText())
                .isEqualTo("\u2588 \n \u2588");
        StyledText scaled = ImageView.of(ImageAsset.of(diagonalPixels())).symbol("@").scale(3)
                .render();
        assertThat(scaled.plainText()).isEqualTo("@@@   \n   @@@");
        assertThat(scaled.plainText().split("\n", -1)).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isEqualTo(6));
        // Scale is horizontal repetition; zero and negative values mean one.
        assertThat(ImageView.of(ImageAsset.of(diagonalPixels())).scale(0).render().plainText())
                .isEqualTo("\u2588 \n \u2588");
        assertThat(ImageView.of(ImageAsset.of(diagonalPixels())).scale(-4).render().plainText())
                .isEqualTo("\u2588 \n \u2588");
    }

    @Test
    void replaceMapsOneExactColourAndIgnoresAnythingUnparseable() {
        ImageView view = ImageView.of(ImageAsset.of(diagonalPixels())).symbol("@");
        view.replace("#ff0000", "#0000ff").replace("not a colour", "#ffffff")
                .replace("#00ff00", "nope").replace("00ff00", "#123456");
        StyledText rendered = view.render();
        assertThat(rendered.spans().get(0).style().foreground())
                .isEqualTo(new io.github.dhoard.braxton.style.Color(0, 0, 0xFF));
        assertThat(rendered.spans().get(2).style().foreground())
                .as("an unparseable target leaves the pixel's own colour").isEqualTo(
                        new io.github.dhoard.braxton.style.Color(0, 0xFF, 0));
        assertThat(rendered.spans()).as("only #rrggbb parses, so the source without one is ignored")
                .noneMatch(span -> span.style().foreground() != null
                        && span.style().foreground().equals(
                                new io.github.dhoard.braxton.style.Color(0x12, 0x34, 0x56)));
        // A replacement survives later edits and does not leak between views.
        assertThat(view.render()).isEqualTo(rendered);
        assertThat(ImageView.of(ImageAsset.of(diagonalPixels())).symbol("@").render().spans()
                .get(0).style().foreground())
                .isEqualTo(new io.github.dhoard.braxton.style.Color(0xFF, 0, 0));
    }

    @Test
    void aSymbolWithNoVisibleColumnFallsBackToTheBlockSoTheGridStaysAligned() {
        // The class promises one grid cell per pixel whatever symbol the caller asks for. A symbol
        // that paints nothing at all (a zero-width space, a byte-order mark) would leave the row
        // short, so it is treated like no symbol and falls back to the block.
        for (String symbol : java.util.List.of("\u200B", "\uFEFF", "a", "\u65E5")) {
            StyledText rendered = ImageView.of(ImageAsset.of(diagonalPixels()))
                    .symbol(symbol).render();
            int cell = Math.max(1, TextMeasure.columns(symbol));
            assertThat(rendered.plainText().split("\n", -1)).as("symbol %s", symbol)
                    .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isEqualTo(2 * cell));
        }
        assertThat(ImageView.of(ImageAsset.of(diagonalPixels())).symbol("\u200B").render().plainText())
                .isEqualTo("\u2588 \n \u2588");
    }

    @Test
    void aCombiningMarkAttachesToThePixelBeforeItByTheUnicodeClusterRule() {
        // TextMeasure segments Extend onto the preceding base, so a bare combining mark merges
        // with the blank cell to its left instead of occupying a cell of its own. That is the
        // measurement policy the project pins, not a grid the renderer can correct.
        String rendered = ImageView.of(ImageAsset.of(diagonalPixels())).symbol("\u0301")
                .render().plainText();
        assertThat(TextMeasure.columns(rendered.lines().toList().get(1))).isEqualTo(1);
        assertThat(TextMeasure.columns("\u0301")).isEqualTo(1);
    }

    @Test
    void ofRequiresAnImage() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ImageView.of(null));
    }
}
