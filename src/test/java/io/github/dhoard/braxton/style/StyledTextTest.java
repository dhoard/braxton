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

import java.nio.charset.StandardCharsets;
import java.util.List;

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
    void anUnpairedSurrogateIsDroppedInsteadOfReachingTheTerminalAsAReplacement() {
        // A Java string can hold half of a surrogate pair (a substring, a CESU-8 or mis-decoded
        // stream). UTF-8 cannot encode that half, so the terminal paints '?' where the content
        // says something else and an OSC 52 payload carries the replacement byte instead of the
        // text. Every span therefore holds only scalar values: an unpaired half is dropped like a
        // control character, and both halves of a real pair survive.
        assertThat(StyledText.of("a\uD800b", Style.NONE).plainText()).isEqualTo("ab");
        assertThat(StyledText.of("a\uDC00b", Style.NONE).plainText()).isEqualTo("ab");
        assertThat(StyledText.of("a\uD83D\uDE00b", Style.NONE).plainText())
                .isEqualTo("a\uD83D\uDE00b");
        // Two halves in the wrong order are two unpaired surrogates, not a pair.
        assertThat(StyledText.of("\uDE00\uD83D", Style.NONE).plainText()).isEmpty();
        // Dropping the surrogate lets a dangling combining mark join the character before it.
        assertThat(StyledText.of("a\uD800\u0301b", Style.NONE).plainText()).isEqualTo("a\u0301b");
        // The surviving content is what the session writes: UTF-8 round-trips it unchanged.
        for (String raw : List.of("a\uD800\u0301b", "\uD83D\uDE00", "e\u0301")) {
            String content = StyledText.of(raw, Style.NONE).plainText();
            assertThat(new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .as("wire form of %s", raw)
                    .isEqualTo(content);
        }
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
    void hyperlinkTargetsRejectEveryC1Control() {
        for (char control = 0x80; control <= 0x9f; control++) {
            String target = "https://example.com/a" + control + "b";
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> StyledText.of("link", Style.NONE, target))
                    .as("C1 control U+%04X in link", (int) control)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(StyledText.of("link", Style.NONE, "https://example.com/漢字").spans().get(0).link())
                .isEqualTo("https://example.com/漢字");
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
    void columnsIsTheWidestLineRatherThanTheWholeValue() {
        assertThat(StyledText.plain("ab\ncd").columns()).isEqualTo(2);
        assertThat(StyledText.plain("a\nbbb").columns()).isEqualTo(3);
        assertThat(StyledText.plain("abc").columns()).isEqualTo(3);
        assertThat(StyledText.empty().columns()).isZero();
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
