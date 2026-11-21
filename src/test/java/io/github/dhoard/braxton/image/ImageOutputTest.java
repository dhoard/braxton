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

import io.github.dhoard.braxton.asset.ImageAsset;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.testing.ElementScreen;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageOutputTest {
    static ImageOutput image(int width, int height, int... pixels) {
        BufferedImage raster = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) raster.setRGB(x, y, pixels[(y * width + x) % pixels.length]);
        return ImageOutput.of(ImageAsset.of(raster));
    }

    @Test
    void aNarrowElementClipsThePixelGridWithoutReflowingIt() {
        ImageOutput image = image(
                2, 4, 0xffff0000, 0xff0000ff, 0xff00ff00, 0xffffffff, 0xffffff00, 0xff00ffff, 0xffff00ff, 0xff000000);
        var canvas = ElementScreen.render(image.element(), 2, 1).canvas();
        assertThat(canvas.style(0, 0).foreground()).isEqualTo(new Color(255, 0, 0));
        assertThat(canvas.style(0, 0).background()).isEqualTo(new Color(0, 255, 0));
        assertThat(canvas.style(1, 0).foreground()).isEqualTo(new Color(255, 255, 0));
        assertThat(canvas.style(1, 0).background()).isEqualTo(new Color(255, 0, 255));
    }

    @Test
    void kittyPayloadIsChunkedAndRoundTripsRgbaIncludingAlpha() {
        ImageOutput image = image(40, 30, 0xffff0000, 0x804080c0, 0x00000000);
        String encoded = image.encode(ImageProtocol.KITTY);
        var packets =
                Pattern.compile("\\x1b_G([^;]+);([A-Za-z0-9+/=]+)\\x1b\\\\").matcher(encoded);
        StringBuilder payload = new StringBuilder();
        int end = 0, count = 0;
        while (packets.find()) {
            assertThat(packets.start()).isEqualTo(end);
            end = packets.end();
            if (count++ == 0) assertThat(packets.group(1)).startsWith("a=T,f=32,s=40,v=30,q=2,");
            else assertThat(packets.group(1)).startsWith("m=");
            assertThat(packets.group(2).length()).isLessThanOrEqualTo(4096);
            assertThat(packets.group(1)).endsWith(end == encoded.length() ? "m=0" : "m=1");
            payload.append(packets.group(2));
        }
        assertThat(count).isGreaterThan(1);
        assertThat(end).isEqualTo(encoded.length());
        byte[] rgba = Base64.getDecoder().decode(payload.toString());
        assertThat(rgba).hasSize(40 * 30 * 4);
        for (int y = 0; y < 30; y++)
            for (int x = 0; x < 40; x++) {
                int i = (y * 40 + x) * 4, pixel = image.argb(x, y);
                assertThat(rgba[i] & 255).isEqualTo((pixel >>> 16) & 255);
                assertThat(rgba[i + 1] & 255).isEqualTo((pixel >>> 8) & 255);
                assertThat(rgba[i + 2] & 255).isEqualTo(pixel & 255);
                assertThat(rgba[i + 3] & 255).isEqualTo(pixel >>> 24);
            }
    }

    @Test
    void itermIsAnInlinePngRatherThanAFileTransfer() throws Exception {
        ImageOutput image = image(3, 2, 0xffff0000, 0x804080c0, 0x00000000);
        String encoded = image.encode(ImageProtocol.ITERM2);
        assertThat(encoded)
                .startsWith("\u001b]1337;File=inline=1;width=3px;height=2px;preserveAspectRatio=1:")
                .endsWith("\u001b\\");
        byte[] png = Base64.getDecoder().decode(encoded.substring(encoded.indexOf(':') + 1, encoded.length() - 2));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded.getWidth()).isEqualTo(3);
        assertThat(decoded.getHeight()).isEqualTo(2);
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 3; x++) assertThat(decoded.getRGB(x, y)).isEqualTo(image.argb(x, y));
    }

    @Test
    void halfBlocksPreserveBothPixelsAndCompositeAlpha() {
        ImageOutput image = image(1, 3, 0xffff0000, 0xff00ff00, 0x800000ff).withBackground(Color.WHITE);
        var text = image.halfBlocks();
        assertThat(text.plainText()).isEqualTo("\u2580\n\u2580");
        var screen = ElementScreen.render(image.<Object>element(), 2, 1).canvas();
        assertThat(screen.cell(0, 0).style().foreground()).isEqualTo(new Color(255, 0, 0));
        assertThat(screen.cell(0, 0).style().background()).isEqualTo(new Color(0, 255, 0));
        assertThat(screen.cell(1, 0).style().foreground()).isEqualTo(new Color(127, 127, 255));
        assertThat(screen.cell(1, 0).style().background()).isEqualTo(Color.WHITE);
        assertThat(image(1, 1, 0).withBackground(Color.WHITE).color(0, 0)).isEqualTo(Color.WHITE);
    }

    @Test
    void fallbackHonorsColorProfilesAndNeverCopiesPixelBytesAsControls() {
        ImageOutput image = image(2, 2, 0xff001b07, 0xff80ff00);
        String plain = image.encode(ImageProtocol.HALF_BLOCK, ColorProfile.NONE);
        assertThat(plain).isEqualTo("\u2580\u2580\u001b[0m");
        assertThat(image.encode(ImageProtocol.HALF_BLOCK)).contains("38;2;", "48;2;");
        assertThat(image(1, 3, 0xffffffff).encode(ImageProtocol.HALF_BLOCK)).contains("\r\n");
    }

    @Test
    void fittingIsImmutableBoundedAndUsesNearestNeighbor() {
        ImageOutput image = image(4, 2, 0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff);
        ImageOutput fit = image.fit(2, 2);
        assertThat(fit.width()).isEqualTo(2);
        assertThat(fit.height()).isEqualTo(1);
        assertThat(fit.argb(0, 0)).isEqualTo(0xffff0000);
        assertThat(fit.argb(1, 0)).isEqualTo(0xff0000ff);
        assertThat(image.width()).isEqualTo(4);
        assertThat(image.fit(8, 8).width()).isEqualTo(4);
        assertThat(image(1, 20, 0xffffffff).fit(1, 1).height()).isEqualTo(1);
    }

    @Test
    void invalidDimensionsAndMissingInputsFailBeforeOutput() {
        ImageOutput image = image(1, 1, 0xffffffff);
        for (int[] bounds : new int[][] {{0, 1}, {1, -1}, {Integer.MIN_VALUE, 1}}) {
            assertThatThrownBy(() -> image.fit(bounds[0], bounds[1])).isInstanceOf(IllegalArgumentException.class);
        }
        ImageOutput wide = image(4097, 1, 0xffffffff);
        assertThatThrownBy(() -> wide.encode(ImageProtocol.KITTY)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wide.fit(80, 2).width()).isEqualTo(80);
        assertThat(wide.fit(Integer.MAX_VALUE, Integer.MAX_VALUE).width()).isEqualTo(4096);
        assertThat(image.fit(4096, 4096).width()).isEqualTo(1);
        assertThatThrownBy(() -> ImageOutput.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> image.withBackground(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> image.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> image.encode(ImageProtocol.KITTY, null)).isInstanceOf(NullPointerException.class);
        assertThat(ElementScreen.render(image.element(), 0, 0).lines()).isEmpty();
    }

    @Test
    void aStreamSessionGetsThePortableFallbackAndFlushes() throws Exception {
        StringWriter output = new StringWriter();
        try (TerminalSession session = TerminalSession.of(new StringReader(""), output, new Size(2, 3))) {
            assertThat(image(2, 2, 0xffff0000).write(session)).isEqualTo(ImageProtocol.HALF_BLOCK);
        }
        assertThat(output.toString()).contains("\u2580\u2580").doesNotContain("_G", "]1337", "P0;1;0q");
    }

    @Test
    void sessionWriteFailuresAreReported() throws Exception {
        Writer broken = new Writer() {
            public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("broken image sink");
            }

            public void flush() throws IOException {
                throw new IOException("broken image sink");
            }

            public void close() {}
        };
        try (TerminalSession session = TerminalSession.of(new StringReader(""), broken, new Size(1, 1))) {
            assertThatThrownBy(() -> image(1, 1, 0xffffffff).write(session, ImageCapabilities.NONE))
                    .isInstanceOf(TerminalException.class);
        }
    }
}
