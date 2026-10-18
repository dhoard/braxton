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

package io.github.dhoard.braxton.style;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Styles are immutable values; the palette is valid by construction. */
class StyleTest {

    @Test
    void withersChangeOnlyTheirOwnComponent() {
        Style style = Style.NONE.withBold().withItalic().withUnderline().withStrikethrough()
                .withInvert().withHidden().withForeground(Colors.ERROR_400)
                .withBackground(Colors.BLACK);
        assertThat(style.bold()).isTrue();
        assertThat(style.italic()).isTrue();
        assertThat(style.underline()).isTrue();
        assertThat(style.strikethrough()).isTrue();
        assertThat(style.invert()).isTrue();
        assertThat(style.hidden()).isTrue();
        assertThat(style.foreground()).isEqualTo(Colors.ERROR_400);
        assertThat(style.background()).isEqualTo(Colors.BLACK);
        assertThat(style.isPlain()).isFalse();
        assertThat(Style.NONE.isPlain()).isTrue();
        assertThat(Style.fg(Colors.SUCCESS_400).background()).isNull();
        assertThat(Style.bg(Colors.BLACK).foreground()).isNull();
    }

    @Test
    void mergeInheritsUnspecifiedColoursAndOrsAttributes() {
        Style base = Style.fg(Colors.ERROR_400).withBold();
        Style overlay = Style.bg(Colors.BLACK).withItalic();
        Style merged = base.merge(overlay);
        assertThat(merged.foreground()).as("the overlay sets no foreground").isEqualTo(Colors.ERROR_400);
        assertThat(merged.background()).isEqualTo(Colors.BLACK);
        assertThat(merged.bold()).isTrue();
        assertThat(merged.italic()).isTrue();
        assertThat(base.merge(null)).isSameAs(base);
        Style replacing = Style.fg(Colors.SUCCESS_400);
        assertThat(base.merge(replacing).foreground()).isEqualTo(Colors.SUCCESS_400);
    }
}
