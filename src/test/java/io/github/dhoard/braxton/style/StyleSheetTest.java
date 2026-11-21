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

package io.github.dhoard.braxton.style;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Selector matching, D-06 precedence and line/column errors.
 */
class StyleSheetTest {

    private static final StyleSheet.Element TITLE =
            new StyleSheet.Element("label", Set.of("accent"), "main", Set.of("focused"));
    private static final StyleSheet.Element PANEL = new StyleSheet.Element("panel", Set.of(), "root", Set.of());

    @Test
    void selectorsMatchTypeClassIdStateAndDescendants() {
        StyleSheet sheet = StyleSheet.parse("""
                label { color: #ffffff; }
                .accent { bold: true; }
                #main { italic: true; }
                label:focused { underline: true; }
                panel label { background: #000000; }
                """);
        Style resolved = sheet.resolve(TITLE, List.of(PANEL), Style.NONE, Style.NONE, Style.NONE);
        assertThat(resolved.foreground()).isEqualTo(Color.WHITE);
        assertThat(resolved.bold()).isTrue();
        assertThat(resolved.italic()).isTrue();
        assertThat(resolved.underline()).isTrue();
        assertThat(resolved.background()).isEqualTo(Color.BLACK);
        assertThat(sheet.resolve(PANEL, List.of(), Style.NONE, Style.NONE, Style.NONE)
                        .foreground())
                .isNull();
    }

    @Test
    void precedenceIsDefaultsThemeSheetExplicit() {
        StyleSheet sheet = StyleSheet.parse("label { color: #112233; bold: true; }");
        Style defaults = Style.fg(Color.WHITE);
        Style theme = Style.fg(Color.BLACK).withItalic();
        Style explicit = Style.fg(new Color(9, 9, 9));
        Style result = sheet.resolve(TITLE, List.of(), defaults, theme, explicit);
        assertThat(result.foreground()).isEqualTo(explicit.foreground());
        assertThat(result.bold()).isTrue();
        assertThat(result.italic()).isTrue();
    }

    @Test
    void malformedInputReportsLineAndColumn() {
        assertThatThrownBy(() -> StyleSheet.parse("label { color: #112233; }\nbutton { bogus: 1; }"))
                .isInstanceOf(StylesheetException.class)
                .satisfies(failure -> {
                    StylesheetException error = (StylesheetException) failure;
                    assertThat(error.line()).isEqualTo(2);
                    assertThat(error.column()).isGreaterThan(1);
                });
    }
}
