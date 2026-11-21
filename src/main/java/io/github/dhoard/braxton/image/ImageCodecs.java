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

package io.github.dhoard.braxton.image;

import io.github.dhoard.braxton.style.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Owned encoders derived from the published wire specifications, not terminal implementations.
 */
final class ImageCodecs {
    private static final String ESC = "\u001b";
    private static final String ST = ESC + "\\";

    private ImageCodecs() {}

    static String kitty(ImageOutput image) {
        byte[] pixels = new byte[image.width() * image.height() * 4];
        int at = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int pixel = image.argb(x, y);
                pixels[at++] = (byte) (pixel >>> 16);
                pixels[at++] = (byte) (pixel >>> 8);
                pixels[at++] = (byte) pixel;
                pixels[at++] = (byte) (pixel >>> 24);
            }
        }
        String payload = Base64.getEncoder().encodeToString(pixels);
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < payload.length(); offset += 4096) {
            int end = Math.min(payload.length(), offset + 4096);
            out.append(ESC).append("_G");
            if (offset == 0)
                out.append("a=T,f=32,s=")
                        .append(image.width())
                        .append(",v=")
                        .append(image.height())
                        .append(",q=2,");
            out.append("m=")
                    .append(end == payload.length() ? 0 : 1)
                    .append(';')
                    .append(payload, offset, end)
                    .append(ST);
        }
        return out.toString();
    }

    static String iterm2(ImageOutput image) {
        BufferedImage raster = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) raster.setRGB(x, y, image.argb(x, y));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(raster, "png", output)) throw new IOException("PNG encoder is unavailable");
        } catch (IOException failure) {
            throw new UncheckedIOException("could not encode inline image", failure);
        }
        return ESC + "]1337;File=inline=1;width=" + image.width() + "px;height=" + image.height()
                + "px;preserveAspectRatio=1:" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + ST;
    }

    static String sixel(ImageOutput image) {
        StringBuilder out = new StringBuilder(ESC + "P0;1;0q\"1;1;" + image.width() + ";" + image.height());
        boolean[] defined = new boolean[216];
        for (int top = 0; top < image.height(); top += 6) {
            byte[][] masks = new byte[216][];
            for (int y = top; y < Math.min(top + 6, image.height()); y++) {
                for (int x = 0; x < image.width(); x++) {
                    int color = palette(image.color(x, y));
                    if (masks[color] == null) masks[color] = new byte[image.width()];
                    masks[color][x] |= (byte) (1 << (y - top));
                }
            }
            boolean first = true;
            for (int color = 0; color < masks.length; color++) {
                if (masks[color] == null) continue;
                if (!first) out.append('$');
                first = false;
                out.append('#').append(color);
                if (!defined[color]) {
                    out.append(";2;")
                            .append((color / 36) * 20)
                            .append(';')
                            .append(((color / 6) % 6) * 20)
                            .append(';')
                            .append((color % 6) * 20);
                    defined[color] = true;
                }
                byte[] row = masks[color];
                int end = row.length;
                while (end > 0 && row[end - 1] == 0) end--;
                for (int x = 0; x < end; ) {
                    int next = x + 1;
                    while (next < end && row[next] == row[x]) next++;
                    char sixel = (char) (63 + row[x]);
                    if (next - x > 3) out.append('!').append(next - x).append(sixel);
                    else out.repeat(sixel, next - x);
                    x = next;
                }
                if (out.length() > ImageOutput.MAX_OUTPUT - ST.length()) {
                    throw new IllegalArgumentException("Sixel output exceeds 64 Mi characters; downsample the image");
                }
            }
            if (top + 6 < image.height()) out.append('-');
        }
        return out.append(ST).toString();
    }

    private static int palette(Color color) {
        return ((color.red() + 25) / 51) * 36 + ((color.green() + 25) / 51) * 6 + (color.blue() + 25) / 51;
    }
}
