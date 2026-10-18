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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Every bundled FIGlet font loads and renders through the artwork path the widget uses. */
class FontLoaderTest {

    @Test
    void everyBundledFontLoadsAndRendersTheSameGlyphGrid() throws IOException {
        for (FontLoader.Family family : FontLoader.Family.values()) {
            FigletFont font = FontLoader.load(family);
            assertThat(font.name()).as("%s resource", family).endsWith(family.resource());
            assertThat(font.height()).as("%s height", family).isPositive();
            List<String> rows = font.render("Ag");
            assertThat(rows).as("%s rows", family).hasSize(font.height());
            assertThat(rows).as("%s is not blank", family).anySatisfy(row ->
                    assertThat(row).isNotBlank());
            for (String row : rows) {
                assertThat(row).as("%s row %s", family, row).doesNotContain("\u0000")
                        .doesNotContain(String.valueOf(font.hardBlank()));
            }
        }
    }

    @Test
    void twoRenderCallsAgreeAndUnknownCharactersAreSkipped() throws IOException {
        FigletFont font = FontLoader.load(FontLoader.Family.SMALL);
        assertThat(font.render("Hi")).isEqualTo(font.render("Hi"));
        assertThat(font.render("\u65E5")).as("characters outside 32..126 are skipped")
                .containsOnly("");
        assertThat(font.render("")).hasSize(font.height());
    }

    @Test
    void aMissingResourceFailsWithADiagnostic() {
        // Family resources are fixed, so a missing one is exercised through the arbitrary-stream
        // overload with a null stream, which the parser reports as a missing font.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                FigletFont.parse(null, "/fonts/none.flf")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("font not found");
    }
}
