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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** FIGlet fonts are parsed and rendered by the format's endmark and hardblank rules. */
class FigletFontTest {

    @Test
    void endmarkComesFromTheFirstGlyphRowRatherThanTheHardblank() throws Exception {
        StringBuilder font = new StringBuilder("flf2a$ 1 1 3 0 0 0 0 0\n");
        for (char code = 32; code <= 126; code++) {
            font.append("X$@\n");
        }
        FigletFont parsed = FigletFont.parse(stream(font.toString()), "synthetic");
        assertThat(parsed.hardBlank()).isEqualTo('$');
        assertThat(parsed.render("!")).containsExactly("X");
    }

    @Test
    void aTruncatedFontFailsWithADiagnosticInsteadOfPartialArt() {
        StringBuilder font = new StringBuilder("flf2a$ 2 1 3 0 0 0 0 0\n");
        for (int glyph = 0; glyph < 3; glyph++) {
            font.append("X$@\n").append("X$@\n");
        }
        assertThatThrownBy(() -> FigletFont.parse(stream(font.toString()), "short.flf"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("short.flf");
    }

    @Test
    void renderedGlyphRowsDropEndmarksAndTrailingBlanks() throws Exception {
        FigletFont font = FontLoader.load(FontLoader.Family.STANDARD);
        List<String> rows = font.render("A");
        assertThat(rows).hasSize(6);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).doesNotContain("@");
            assertThat(row).isEqualTo(row.stripTrailing());
        });
        assertThat(rows.get(0)).isEqualTo("     _");
        assertThat(rows.get(4)).isEqualTo(" /_/   \\_\\");
    }

    private static InputStream stream(String content) {
        return new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
