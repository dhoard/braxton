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
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The one-choice prompt, driven over a stream-backed session: the keys are the bytes a terminal
 * sends, and the assertions read the same transcripts a terminal would show.
 */
class LineSelectPromptTest {

    private static final Size SIZE = new Size(24, 80);

    private static final List<String> THEMES = List.of("dark", "light", "high-contrast");

    private static final String LEAVE =
            AnsiEncoder.RESET + AnsiEncoder.SHOW_CURSOR + AnsiEncoder.DISABLE_BRACKETED_PASTE;

    @Test
    void theFirstOptionIsHighlightedAndEnterChoosesIt() throws Exception {
        Run run = ask("\r", SelectPrompt.of("Pick a theme", THEMES));

        assertThat(run.answer()).contains("dark");
        assertThat(plain(run.written())).contains("Pick a theme  (enter chooses, esc cancels)");
        assertThat(plain(run.written())).contains("\u276F dark");
        assertThat(plain(run.written())).contains("light").contains("high-contrast");
    }

    @Test
    void downMovesTheHighlightAndSpaceChoosesIt() throws Exception {
        assertThat(ask("\u001b[B \r", SelectPrompt.of("Pick a theme", THEMES)).answer())
                .contains("light");
    }

    @Test
    void theHighlightClampsAtBothEnds() throws Exception {
        assertThat(ask("\u001b[A\r", SelectPrompt.of("Pick a theme", THEMES)).answer())
                .as("up at the first option stays there")
                .contains("dark");
        assertThat(ask("\u001b[F\u001b[B\r", SelectPrompt.of("Pick a theme", THEMES))
                        .answer())
                .as("down at the last option stays there")
                .contains("high-contrast");
    }

    @Test
    void homeEndAndThePageKeysMoveTheHighlight() throws Exception {
        SelectPrompt window =
                SelectPrompt.of("Pick", List.of("one", "two", "three", "four")).withRows(2);

        assertThat(ask("\u001b[6~\r", window).answer()).as("a page down").contains("three");
        assertThat(ask("\u001b[F\u001b[5~\r", window).answer())
                .as("a page up from the end")
                .contains("two");
        assertThat(ask("\u001b[F\u001b[H\r", window).answer())
                .as("end then home")
                .contains("one");
    }

    @Test
    void aGestureThePromptDoesNotAnswerChangesNothing() throws Exception {
        assertThat(ask("x\u001b[A\r", SelectPrompt.of("Pick a theme", THEMES)).answer())
                .as("an unbound letter does not choose and up still clamps")
                .contains("dark");
        assertThat(ask("\u001b[200~light\u001b[201~\r", SelectPrompt.of("Pick a theme", THEMES))
                        .answer())
                .as("a paste is not a gesture")
                .contains("dark");
    }

    @Test
    void cancelAbandonsThePromptAndErasesItsRows() throws Exception {
        Run run = ask("\u0003", SelectPrompt.of("Pick a theme", THEMES));

        assertThat(run.answer()).isEmpty();
        // The region is erased at its own first row and the terminal is given its cursor and its
        // paste setting back, so an abandoned prompt leaves nothing behind.
        assertThat(run.written()).endsWith(AnsiEncoder.ERASE_DISPLAY_BELOW + LEAVE);
    }

    @Test
    void aPromptWithNothingToOfferAsksNothingAndWritesNothing() throws Exception {
        Run run = ask("\r", SelectPrompt.of("Pick a theme", List.of()));

        assertThat(run.answer()).isEmpty();
        assertThat(run.written()).isEmpty();
    }

    @Test
    void aResizeRepaintsTheRowsAtTheNewWidth() throws Exception {
        AtomicReference<Size> size = new AtomicReference<>(new Size(24, 20));
        StringWriter sink = new StringWriter();
        Writer resizing = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                sink.write(buffer, offset, length);
                if (size.get().cols() == 20 && sink.toString().contains("high-contrast")) {
                    size.set(new Size(24, 40));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        Optional<String> answer;
        try (TerminalSession session = TerminalSession.of(new StringReader("\u001b[F\r"), resizing, size::get)) {
            answer = SelectPrompt.of("Pick a theme", THEMES)
                    .ask(LineReader.of(session).withColorProfile(ColorProfile.NONE));
        }

        assertThat(answer).contains("high-contrast");
        assertThat(plain(sink.toString())).as("painted before the resize").contains("high-cont");
    }

    @Test
    void theValuePreparesItsOptionsAndTheWithersLeaveItAlone() {
        SelectPrompt prompt = SelectPrompt.of("Pick", List.of("one", "two\nlines", "bad\u0007char", " "));

        assertThat(prompt.options()).containsExactly("one", "two lines", "bad char");
        assertThat(prompt.question()).isEqualTo("Pick");
        assertThat(prompt.rows()).isEqualTo(SelectPrompt.DEFAULT_ROWS);

        SelectPrompt changed =
                prompt.withQuestion("Choose").withOptions(List.of("only")).withRows(3);
        assertThat(prompt.options()).containsExactly("one", "two lines", "bad char");
        assertThat(changed.question()).isEqualTo("Choose");
        assertThat(changed.options()).containsExactly("only");
        assertThat(changed.rows()).isEqualTo(3);

        // A window below one row is no window at all, and a null list offers nothing.
        assertThat(prompt.withRows(0).rows()).isEqualTo(SelectPrompt.DEFAULT_ROWS);
        assertThat(prompt.withOptions(null).options()).isEmpty();
        assertThat(SelectPrompt.of(null, THEMES).question()).isEmpty();
    }

    private record Run(Optional<String> answer, String written) {}

    /**
     * The text a terminal would show: every CSI sequence removed, so styling cannot hide a cell.
     */
    private static String plain(String written) {
        return written.replaceAll("\u001b\\[[0-9;? ]*[A-Za-z]", "");
    }

    private static Run ask(String input, SelectPrompt prompt) throws TerminalException {
        StringWriter written = new StringWriter();
        Optional<String> answer;
        try (TerminalSession session = TerminalSession.of(new StringReader(input), written, SIZE)) {
            answer = prompt.ask(LineReader.of(session).withColorProfile(ColorProfile.NONE));
        }
        return new Run(answer, written.toString());
    }
}
