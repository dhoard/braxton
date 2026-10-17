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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.Views;

/** The encoder is the only place escape sequences are produced. */
class AnsiEncoderTest {

    @Test
    void sgrEncodesTrueColourAndAttributes() {
        String sgr = AnsiEncoder.sgr(Style.fg(Colors.ERROR_400).withBold()
                .withBackground(Colors.BLACK));
        assertThat(sgr).contains("1;");
        assertThat(sgr).contains("38;2;240;68;56");
        assertThat(sgr).contains("48;2;0;0;0");
        assertThat(AnsiEncoder.sgr(Style.NONE)).isEmpty();
    }

    @Test
    void rowsAreAddressedAbsolutelyAndCleared() {
        Screen screen = new Screen(2, 4);
        screen.put(1, 0, "x", Style.NONE, null, 1);
        String row = AnsiEncoder.encodeRow(screen, 1);
        assertThat(row).startsWith("\u001B[2;1H\u001B[2K");
        assertThat(row).contains("x");
    }

    @Test
    void diffsOnlyChangedRows() {
        Screen first = new Screen(2, 3);
        first.put(0, 0, "a", Style.NONE, null, 1);
        Screen second = new Screen(2, 3);
        second.put(0, 0, "a", Style.NONE, null, 1);
        second.put(1, 0, "b", Style.NONE, null, 1);
        String diff = AnsiEncoder.diff(first, second);
        assertThat(diff).contains("\u001B[2;1H");
        assertThat(diff).doesNotContain("\u001B[1;1H");
    }

    @Test
    void linksAreOpenedAndClosedOncePerRun() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "a", Style.NONE, "https://example.com", 1);
        screen.put(0, 1, "b", Style.NONE, "https://example.com", 1);
        String row = AnsiEncoder.encodeRow(screen, 0);
        assertThat(row.split("\u001B]8;;https://example.com", -1)).hasSize(2);
        assertThat(row).contains("\u001B\\");
    }

    @Test
    void dataCannotInjectEscapesBecauseContentIsSanitizedBeforeEncoding() {
        var content = StyledText.of("x\u001B[31my", Style.NONE);
        var screen = new io.github.dhoard.braxton.internal.render.LayoutEngine()
                .layout(Views.text(content), new Rect(0, 0, 1, 10)).screen();
        String row = AnsiEncoder.encodeRow(screen, 0);
        assertThat(row).contains("x[31my");
        assertThat(row.replace("\u001B[1;1H\u001B[2K", "")).doesNotContain("\u001B[31m");
    }

    @Test
    void aFullFrameWritesEveryRowInOrder() {
        Screen screen = new Screen(2, 2);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        screen.put(1, 0, "b", Style.NONE, null, 1);
        String frame = AnsiEncoder.frame(screen);
        assertThat(frame.indexOf("\u001B[1;1H")).isLessThan(frame.indexOf("\u001B[2;1H"));
    }
}
