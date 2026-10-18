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

    @Test
    void anAbsurdHeaderFailsWithADiagnosticInsteadOfOverflowingTheCursor() {
        // The parser reports malformed fonts with an IOException that names the source. A header
        // whose height or comment count overflows the cursor arithmetic must not escape as an
        // unchecked index failure instead.
        StringBuilder glyphs = new StringBuilder();
        for (char code = 32; code <= 126; code++) {
            glyphs.append("X$@\n");
        }
        // Each header sets both candidate comment-count fields to the same absurd value, and keeps the
        // fifth field the parser reads today, so the case does not depend on which field the
        // parser treats as the comment count. What matters is that an absurd count is reported.
        for (String header : List.of("flf2a$ 2147483647 1 2147483647 2147483647",
                "flf2a$ 2 1 3 2147483647 2147483647",
                "flf2a$ 1 1 3 2147483648 2147483648", "flf2a$ -2147483648 1 3 3")) {
            assertThatThrownBy(() -> FigletFont.parse(stream(header + "\n" + glyphs),
                    "abnormal.flf"))
                    .as("header %s", header)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("abnormal.flf");
        }
    }

    @Test
    void everyBundledFontParsesAndRendersTheRightGlyphs() throws Exception {
        // Each row below is the shipped font's own first row for "A". A comment count read from
        // the wrong field shifts every glyph by a code point, which changes these rows and, at the
        // end of the table, fails the parse as truncated.
        java.util.Map<FontLoader.Family, String> firstRow = java.util.Map.of(
                FontLoader.Family.DIGITAL, " +-+",
                FontLoader.Family.SHADOW, "     \\",
                FontLoader.Family.SLANT, "    ___",
                FontLoader.Family.SMALL, "    _",
                FontLoader.Family.SMSHADOW, "    \\",
                FontLoader.Family.STANDARD, "     _",
                FontLoader.Family.TERM, "A");
        for (FontLoader.Family family : FontLoader.Family.values()) {
            FigletFont font = FontLoader.load(family);
            List<String> rows = font.render("A");
            assertThat(rows).as("%s row count", family).hasSize(font.height());
            assertThat(rows).as("%s rows", family).allSatisfy(row -> {
                assertThat(row).as("%s row", family).doesNotContain("@").doesNotContain("$");
                assertThat(row).as("%s row", family).isEqualTo(row.stripTrailing());
            });
            String expected = firstRow.get(family);
            if (expected != null) {
                assertThat(rows.get(0)).as("%s first row", family).isEqualTo(expected);
            }
            assertThat(font.render("A 1")).as("%s mixed text", family)
                    .hasSize(font.height()).allSatisfy(row -> assertThat(row).doesNotContain("@"));
        }
    }

    @Test
    void theCommentCountIsTheFieldAfterTheHardblankHeaderBlock() throws Exception {
        // flf2a headers carry the comment count in the fifth value; each bundled font has exactly
        // that many comment lines before its first glyph row. Skipping the wrong value shifts every
        // glyph by a code point, so the art changes.
        StringBuilder comments = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            comments.append("comment ").append(i).append('\n');
        }
        StringBuilder font = new StringBuilder("flf2a$ 1 1 3 0 3 0 0 0\n").append(comments);
        for (char code = 32; code <= 126; code++) {
            font.append(code).append("$@\n");
        }
        FigletFont parsed = FigletFont.parse(stream(font.toString()), "comments.flf");
        assertThat(parsed.render("A")).containsExactly("A");
        assertThat(parsed.render("AB")).containsExactly("A B");
    }

    @Test
    void aRepeatedEndMarkIsStrippedWithTheGlyphArt() throws Exception {
        // A glyph whose art ends in the endmark carries it twice; both are removed, which is what
        // the canonical fonts require ("A\____/@" is drawn as "A\____/").
        StringBuilder font = new StringBuilder("flf2a 1 1 3 0 0 0 0 0\n");
        for (char code = 32; code <= 126; code++) {
            font.append("X@@\n");
        }
        FigletFont parsed = FigletFont.parse(stream(font.toString()), "doubled.flf");
        assertThat(parsed.hardBlank()).isEqualTo(' ');
        assertThat(parsed.render("!")).containsExactly("X");
    }

    private static InputStream stream(String content) {
        return new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
