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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

/** A FIGlet logo applies one style, with an optional shadow colour over the whole art. */
class LogoTest {

    @Test
    void aLogoRendersTheFontWithTheConfiguredStyle() throws IOException {
        FigletFont font = FontLoader.load(FontLoader.Family.SMALL);
        Logo logo = Logo.of("Hi", font).style(Style.NONE.withBold()).color(Colors.ERROR_400);
        StyledText rendered = logo.render();
        assertThat(rendered.plainText().split("\n", -1)).hasSize(font.height());
        assertThat(rendered.plainText()).contains("|");
        assertThat(rendered.spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.style().foreground())
                        .isEqualTo(Colors.ERROR_400));
        assertThat(rendered.spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.style().bold()).isTrue());
        assertThat(logo.view()).isNotNull();
    }

    @Test
    void aShadowColourReplacesTheForegroundAndNullsKeepTheCurrentStyle() throws IOException {
        FigletFont font = FontLoader.load(FontLoader.Family.SMALL);
        Logo logo = Logo.of("A", font).style(Style.NONE).color(Colors.ERROR_400)
                .shadowColor(Colors.SUCCESS_400);
        assertThat(logo.render().spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.style().foreground())
                        .isEqualTo(Colors.SUCCESS_400));
        Logo plain = Logo.of("A", font).style(null).color(null).shadowColor(null);
        assertThat(plain.render().spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE));
        assertThat(Logo.of(null, font).render().plainText())
                .isEqualTo(String.join("\n", font.render("")));
        assertThatThrownBy(() -> Logo.of("A", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("font");
    }
}
