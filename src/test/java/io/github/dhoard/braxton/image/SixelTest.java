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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.style.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SixelTest {
    @Test
    void sixelPlanesRoundTripAcrossBandsAndColorRegisters() {
        Random random = new Random(62);
        int[] pixels = new int[11 * 13];
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
        ImageOutput image = ImageOutputTest.image(11, 13, pixels).withBackground(Color.WHITE);
        Decoded decoded = new Decoded(image.encode(ImageProtocol.SIXEL));
        assertThat(decoded.width).isEqualTo(11);
        assertThat(decoded.height).isEqualTo(13);
        for (int y = 0; y < 13; y++)
            for (int x = 0; x < 11; x++) {
                Color expected = image.color(x, y), actual = decoded.pixels[y][x];
                assertThat(actual).isNotNull();
                assertThat(Math.abs(actual.red() - expected.red())).isLessThanOrEqualTo(26);
                assertThat(Math.abs(actual.green() - expected.green())).isLessThanOrEqualTo(26);
                assertThat(Math.abs(actual.blue() - expected.blue())).isLessThanOrEqualTo(26);
            }
    }

    @Test
    void repeatsAndPartialBandsDoNotAddPixelsOrLoseTheirBackground() {
        ImageOutput image = ImageOutputTest.image(20, 7, 0x00ffffff).withBackground(new Color(0, 255, 0));
        String encoded = image.encode(ImageProtocol.SIXEL);
        assertThat(encoded)
                .startsWith("\u001bP0;1;0q\"1;1;20;7")
                .endsWith("\u001b\\")
                .contains("!20");
        Decoded decoded = new Decoded(encoded);
        for (Color[] row : decoded.pixels) assertThat(row).containsOnly(new Color(0, 255, 0));
    }

    /**
     * Independent protocol consumer: applies palette changes and six vertical bits to a raster.
     */
    private static final class Decoded {
        final String wire;
        int offset, width, height, x, y, color;
        Color[][] pixels;
        Map<Integer, Color> palette = new HashMap<>();

        Decoded(String wire) {
            this.wire = wire;
            assertThat(wire).startsWith("\u001bP0;1;0q\"").endsWith("\u001b\\");
            offset = wire.indexOf('"') + 1;
            assertThat(number()).isEqualTo(1);
            delimiter(';');
            assertThat(number()).isEqualTo(1);
            delimiter(';');
            width = number();
            delimiter(';');
            height = number();
            pixels = new Color[height][width];
            while (offset < wire.length() - 2) {
                char c = wire.charAt(offset++);
                if (c == '#') {
                    color = number();
                    if (wire.charAt(offset) == ';') {
                        offset++;
                        assertThat(number()).isEqualTo(2);
                        delimiter(';');
                        int r = number();
                        delimiter(';');
                        int g = number();
                        delimiter(';');
                        int b = number();
                        palette.put(color, new Color((r * 255 + 50) / 100, (g * 255 + 50) / 100, (b * 255 + 50) / 100));
                    }
                } else if (c == '$') x = 0;
                else if (c == '-') {
                    x = 0;
                    y += 6;
                } else if (c == '!') {
                    int count = number();
                    paint(wire.charAt(offset++), count);
                } else paint(c, 1);
            }
        }

        int number() {
            int start = offset;
            while (offset < wire.length() && Character.isDigit(wire.charAt(offset))) offset++;
            assertThat(offset).isGreaterThan(start);
            return Integer.parseInt(wire.substring(start, offset));
        }

        void delimiter(char expected) {
            assertThat(wire.charAt(offset++)).isEqualTo(expected);
        }

        void paint(char sixel, int count) {
            assertThat(sixel).isBetween('?', '~');
            assertThat(x + count).isLessThanOrEqualTo(width);
            Color ink = palette.get(color);
            assertThat(ink).isNotNull();
            int bits = sixel - 63;
            for (int i = 0; i < count; i++, x++)
                for (int bit = 0; bit < 6; bit++) {
                    if ((bits & (1 << bit)) != 0) {
                        assertThat(y + bit).isLessThan(height);
                        pixels[y + bit][x] = ink;
                    }
                }
        }
    }
}
