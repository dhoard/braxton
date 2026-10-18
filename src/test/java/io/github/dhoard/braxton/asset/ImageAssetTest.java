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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Decoding happens once, pixels are copied, and failures are diagnostics. */
class ImageAssetTest {

    @Test
    void anImageIsCopiedIntoAnImmutableSnapshot() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 1, 0x8000FF00);
        ImageAsset asset = ImageAsset.of(image);
        image.setRGB(0, 0, 0xFF0000FF);
        assertThat(asset.width()).isEqualTo(2);
        assertThat(asset.height()).isEqualTo(2);
        assertThat(asset.argb(0, 0)).as("mutating the source must not change the asset")
                .isEqualTo(0xFFFF0000);
        assertThat(asset.argb(1, 1)).isEqualTo(0x8000FF00);
        assertThat(asset.argb(-1, 0)).isZero();
        assertThat(asset.argb(2, 0)).isZero();
        int[] pixels = asset.pixels();
        pixels[0] = 0;
        assertThat(asset.argb(0, 0)).as("pixels() is a copy").isEqualTo(0xFFFF0000);
        assertThatThrownBy(() -> ImageAsset.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadingRejectsMissingAndUnsupportedFiles(@TempDir Path directory) throws IOException {
        assertThatThrownBy(() -> ImageAsset.load(null)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ImageAsset.load(directory.resolve("absent.png")))
                .isInstanceOf(IOException.class).hasMessageContaining("not found");
        Path text = directory.resolve("notes.txt");
        Files.writeString(text, "not an image");
        assertThatThrownBy(() -> ImageAsset.load(text)).isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported");
        Path png = directory.resolve("pixel.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        ImageAsset loaded = ImageAsset.load(png);
        assertThat(loaded.width()).isEqualTo(1);
        assertThat(loaded.height()).isEqualTo(1);
    }
}
