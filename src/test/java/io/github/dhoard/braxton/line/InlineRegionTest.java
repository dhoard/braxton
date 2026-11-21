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

package io.github.dhoard.braxton.line;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.ui.Cursor;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The inline region the package shares, asserted on its own: the settings it turns on, the frames it
 * writes, what it writes when nothing changed, and what it leaves behind.
 */
class InlineRegionTest {

    @Test
    void openingHidesTheCursorAndAsksForBracketedPaste() throws Exception {
        try (Sink sink = Sink.open(new Size(24, 40))) {
            assertThat(sink.text()).isEqualTo(AnsiEncoder.ENABLE_BRACKETED_PASTE + AnsiEncoder.HIDE_CURSOR);
            assertThat(sink.region().columns()).isEqualTo(40);
        }
    }

    @Test
    void aFrameWritesOnlyWhenTheScreenOrTheCaretChanged() throws Exception {
        try (Sink sink = Sink.open(new Size(24, 20))) {
            Screen screen = row("hello", 20);

            sink.region().present(screen, Optional.empty());
            int painted = sink.text().length();
            assertThat(painted).isGreaterThan(AnsiEncoder.ENABLE_BRACKETED_PASTE.length());

            sink.region().present(screen, Optional.empty());
            assertThat(sink.text().length())
                    .as("an identical frame writes nothing")
                    .isEqualTo(painted);

            sink.region().present(screen, Optional.of(Cursor.at(0, 2)));
            assertThat(sink.text().length()).as("a caret is part of the frame").isGreaterThan(painted);
        }
    }

    @Test
    void aResizeChangesTheColumnsARowMayUse() throws Exception {
        try (Sink sink = Sink.open(new Size(24, 40))) {
            sink.region().resize(12);
            assertThat(sink.region().columns()).isEqualTo(12);
            sink.region().resize(-5);
            assertThat(sink.region().columns()).isZero();
        }
    }

    @Test
    void anAcceptedExitKeepsTheFrameAndAnAbandonedOneErasesIt() throws Exception {
        try (Sink kept = Sink.open(new Size(24, 20))) {
            kept.region().present(row("kept", 20), Optional.empty());
            kept.region().leave(true);
            assertThat(kept.text())
                    .as("the final frame stays and the cursor goes to the line below it")
                    .endsWith("\r\r\n" + AnsiEncoder.RESET + AnsiEncoder.SHOW_CURSOR
                            + AnsiEncoder.DISABLE_BRACKETED_PASTE);
        }

        try (Sink erased = Sink.open(new Size(24, 20))) {
            erased.region().present(row("erased", 20), Optional.empty());
            erased.region().leave(false);
            assertThat(erased.text())
                    .as("the region is erased at its own first row")
                    .endsWith(AnsiEncoder.ERASE_DISPLAY_BELOW
                            + AnsiEncoder.RESET
                            + AnsiEncoder.SHOW_CURSOR
                            + AnsiEncoder.DISABLE_BRACKETED_PASTE);
        }
    }

    private static Screen row(String text, int cols) {
        Screen screen = new Screen(1, cols);
        screen.write(
                new io.github.dhoard.braxton.layout.Rect(0, 0, 1, cols),
                StyledText.of(text, Themes.DARK.style(io.github.dhoard.braxton.style.ThemeToken.TEXT)),
                WidthPolicy.PINNED);
        return screen;
    }

    /**
     * A region over a stream-backed session, which is where its bytes go.
     */
    private record Sink(TerminalSession session, InlineRegion region, StringWriter written) implements AutoCloseable {

        /**
         * The bytes the region has written so far.
         */
        String text() {
            return written.toString();
        }

        @Override
        public void close() throws io.github.dhoard.braxton.terminal.TerminalException {
            session.close();
        }

        static Sink open(Size size) throws Exception {
            StringWriter written = new StringWriter();
            TerminalSession session = TerminalSession.of(new StringReader(""), written, size);
            try {
                return new Sink(session, InlineRegion.open(session, ColorProfile.NONE), written);
            } catch (Exception | Error failure) {
                session.close();
                throw failure;
            }
        }
    }
}
