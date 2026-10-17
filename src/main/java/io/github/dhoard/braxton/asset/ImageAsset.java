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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * An immutable ARGB image snapshot.
 *
 * <p>Decoding happens once at the asset boundary; rendering a view cannot block on I/O or reread a
 * file whose contents changed.</p>
 */
public final class ImageAsset {

    private final int width;
    private final int height;
    private final int[] pixels;

    private ImageAsset(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /** Copies a buffered image into an immutable asset. */
    public static ImageAsset of(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image is required");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new ImageAsset(width, height, pixels);
    }

    public static ImageAsset load(Path path) throws IOException {
        if (path == null) {
            throw new IOException("image path is required");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("image not found: " + path);
        }
        try (InputStream stream = Files.newInputStream(path)) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                throw new IOException("unsupported image format: " + path);
            }
            return of(image);
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** ARGB value at {@code (x, y)}. */
    public int argb(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    /** Immutable pixel copy, used by tests. */
    public int[] pixels() {
        return pixels.clone();
    }
}
