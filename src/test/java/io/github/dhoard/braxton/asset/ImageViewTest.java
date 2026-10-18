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
}
