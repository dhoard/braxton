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

/** Styled text is a value: immutable, style-aware, and safe to render or copy. */
class StyledTextTest {

    @Test
    void plainTextIsTheConcatenatedContent() {
        StyledText text = StyledText.of("a", Style.fg(Colors.ERROR_400))
                .append(StyledText.of("b", Style.NONE));
        assertThat(text.plainText()).isEqualTo("ab");
        assertThat(text.spans()).hasSize(2);
    }

    @Test
    void adjacentSpansWithTheSameStyleMerge() {
        Style red = Style.fg(Colors.ERROR_400);
        StyledText text = StyledText.of("a", red).append(StyledText.of("b", red));
        assertThat(text.spans()).hasSize(1);
        assertThat(text.spans().get(0).text()).isEqualTo("ab");
    }

    @Test
    void controlCharactersInContentAreRemovedSoDataCannotEmitTerminalCommands() {
        StyledText text = StyledText.of("a\u001B[31mb\u0007c", Style.NONE);
        assertThat(text.plainText()).isEqualTo("a[31mbc");
        assertThat(text.plainText()).doesNotContain("\u001B");
    }

    @Test
    void newlinesAndTabsSurviveSanitization() {
        assertThat(StyledText.of("a\nb\tc", Style.NONE).plainText()).isEqualTo("a\nb\tc");
    }

    @Test
    void linksAreTypedAndValidated() {
        StyledText link = StyledText.of("site", Style.NONE, "https://example.com");
        assertThat(link.spans().get(0).link()).isEqualTo("https://example.com");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> StyledText.of("bad", Style.NONE, "https://example.com/\u001B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restylingAndLinkingDoNotChangeContent() {
        StyledText text = StyledText.plain("abc").withStyle(Style.NONE.withBold())
                .withLink("https://example.com");
        assertThat(text.plainText()).isEqualTo("abc");
        assertThat(text.style().bold()).isTrue();
        assertThat(text.spans().get(0).link()).isEqualTo("https://example.com");
    }

    @Test
    void linesAndTrailingNewlineRemovalPreserveStyles() {
        StyledText text = StyledText.of("a\nb\n", Style.NONE.withItalic());
        assertThat(text.lines()).hasSize(3);
        assertThat(text.lines().get(0).plainText()).isEqualTo("a");
        assertThat(text.stripTrailingNewlines().plainText()).isEqualTo("a\nb");
        assertThat(text.stripTrailingNewlines().style().italic()).isTrue();
    }

    @Test
    void diagnosticsNeverContainEscapeSequences() {
        StyledText text = StyledText.of("hello", Style.fg(Colors.BLUE_400).withBold());
        assertThat(text.toString()).isEqualTo("StyledText[hello]").doesNotContain("\u001B");
    }

    @Test
    void equalityIsStructural() {
        assertThat(StyledText.plain("a")).isEqualTo(StyledText.plain("a"));
        assertThat(StyledText.plain("a")).isNotEqualTo(StyledText.of("a", Style.NONE.withBold()));
    }
}
