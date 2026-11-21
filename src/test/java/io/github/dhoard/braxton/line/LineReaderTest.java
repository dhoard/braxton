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
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The inline host, driven over a stream-backed session: no TTY, no native library, and the same
 * bytes a terminal would receive.
 */
class LineReaderTest {

    private static final String LEAVE =
            AnsiEncoder.RESET + AnsiEncoder.SHOW_CURSOR + AnsiEncoder.DISABLE_BRACKETED_PASTE;

    @Test
    void acceptsATypedLineAndLeavesItOnScreen() throws Exception {
        Run run = read("hello\r", LineEditor.emacs(), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Accepted("hello"));
        assertThat(run.written())
                .startsWith(AnsiEncoder.ENABLE_BRACKETED_PASTE)
                // The final frame stays on screen with the cursor on the line below it.
                .endsWith("\r\r\n" + AnsiEncoder.RESET_CURSOR_STYLE + LEAVE);
        assertThat(plain(run.written())).contains("> hello");
    }

    @Test
    void cancelErasesTheRowItPainted() throws Exception {
        Run run = read("ab\u0003", LineEditor.emacs(), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Canceled());
        assertThat(plain(run.written())).contains("> ab");
        // The region is erased at its own first row and the caller's next output starts there.
        assertThat(run.written())
                .endsWith(AnsiEncoder.ERASE_DISPLAY_BELOW
                        + AnsiEncoder.HIDE_CURSOR
                        + AnsiEncoder.RESET_CURSOR_STYLE
                        + LEAVE);
        assertThat(run.written()).doesNotContain("\r\n");
    }

    @Test
    void endOfInputOnAnEmptyLineEndsTheRead() throws Exception {
        Run run = read("\u0004", LineEditor.emacs(), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Eof());
        assertThat(run.written()).endsWith(LEAVE);
    }

    @Test
    void theEndOfTheStreamEndsTheRead() throws Exception {
        Run run = read("", LineEditor.emacs(), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Eof());
    }

    @Test
    void aGestureThatChangesNothingRepaintsNothing() throws Exception {
        // ESC NUL is ctrl+alt+space: a gesture no binding claims, so the row is left alone.
        Run run = read("a\u001b\u0000\r", LineEditor.emacs(), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Accepted("a"));
        assertThat(plain(run.written())).contains("> a");
    }

    @Test
    void aPasteIsInsertedAsOneRowAndNeverSubmitted() throws Exception {
        Run run = read("\u001b[200~one\ntwo\u001b[201~\r", LineEditor.emacs(), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Accepted("one two"));
    }

    @Test
    void aLineWiderThanTheTerminalScrollsAndAResizeRepaints() throws Exception {
        AtomicReference<Size> size = new AtomicReference<>(new Size(24, 8));
        StringWriter sink = new StringWriter();
        Writer resizing = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                sink.write(buffer, offset, length);
                if (size.get().cols() == 8 && sink.toString().contains("bcdef")) {
                    size.set(new Size(24, 20));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        LineEditor.Output output;
        try (TerminalSession session = TerminalSession.of(new StringReader("abcdefgh\r"), resizing, size::get)) {
            output = LineReader.of(session).withColorProfile(ColorProfile.NONE).read("> ");
        }

        assertThat(output).isEqualTo(new LineEditor.Output.Accepted("abcdefgh"));
        assertThat(plain(sink.toString())).as("scrolled at eight columns").contains("> bcdef");
        assertThat(plain(sink.toString())).as("whole at twenty columns").contains("> abcdefgh");
    }

    @Test
    void viNormalModeEditsTheLineAndTheEditorModeWins() throws Exception {
        LineEditor vi = LineEditor.of(Mode.VI, LineEditor.State.NORMAL)
                .withText("one two")
                .withCaret(0);

        Run run = read("dw\r", vi, new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Accepted("two"));
        assertThat(plain(run.written())).contains("> one two");
    }

    @Test
    void aReaderCanBeReconfiguredBeforeItReads() throws Exception {
        StringWriter written = new StringWriter();
        try (TerminalSession session = TerminalSession.of(new StringReader("a\r"), written, new Size(24, 40))) {
            LineReader configured = LineReader.of(session)
                    .withTheme(Themes.LIGHT)
                    .withPolicy(WidthPolicy.ONE_COLUMN)
                    .withColorProfile(ColorProfile.NONE)
                    .withMode(Mode.VI);
            assertThat(configured.read(null)).isEqualTo(new LineEditor.Output.Accepted("a"));
            // Nulls fall back to the documented defaults rather than failing a session.
            assertThat(LineReader.of(session)
                            .withTheme(null)
                            .withPolicy(null)
                            .withMode(null)
                            .withColorProfile(null))
                    .isNotNull();
        }
        assertThat(plain(written.toString())).contains("a");
    }

    @Test
    void aReverseSearchOverTheHistoryFindsALineAndPaintsTheSearchPrompt() throws Exception {
        History history = History.of(List.of("make test", "run tests"));
        StringWriter written = new StringWriter();
        LineEditor.Output output;
        // ctrl+r, the query, Enter to end the search, and Enter again to accept the found line.
        try (TerminalSession session =
                TerminalSession.of(new StringReader("\u0012tests\r\r"), written, new Size(24, 60))) {
            output = LineReader.of(session)
                    .withHistory(history)
                    .withColorProfile(ColorProfile.NONE)
                    .read("> ");
        }

        assertThat(output).isEqualTo(new LineEditor.Output.Accepted("run tests"));
        assertThat(plain(written.toString())).contains("(reverse-i-search)`tests': run tests");
        assertThat(plain(written.toString()))
                .as("the accepted line is left on screen")
                .contains("> run tests");
    }

    @Test
    void theSearchPromptFailsWhereNothingMatches() throws Exception {
        StringWriter written = new StringWriter();
        LineEditor.Output output;
        try (TerminalSession session =
                TerminalSession.of(new StringReader("\u0012zz\u0007\r"), written, new Size(24, 60))) {
            output = LineReader.of(session)
                    .withHistory(History.of(List.of("one", "two")))
                    .withColorProfile(ColorProfile.NONE)
                    .read("> ");
        }

        // The failed search is abandoned with ctrl+g, which restores the line the read started from.
        assertThat(output).isEqualTo(new LineEditor.Output.Accepted(""));
        assertThat(plain(written.toString())).contains("(failed reverse-i-search)`zz': two");
    }

    @Test
    void aCompletionMenuIsPaintedBelowTheLineAndTheAcceptedLineIsLeftAlone() throws Exception {
        LineEditor starting = LineEditor.emacs().withCompleter((text, caret) -> List.of("git", "gist"));

        // Type "gi", complete it, choose the highlighted candidate, then accept the line.
        Run run = read("gi\t\n\r", starting, new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Accepted("git"));
        assertThat(plain(run.written())).as("the menu is a row below the line").contains("[git]  gist");
        assertThat(plain(run.written()))
                .as("the menu scrolls away and the accepted line stays")
                .contains("> git");
        assertThat(run.written())
                .as("the accepted line is left on screen with the cursor on the line below it")
                .endsWith("\r\r\n" + AnsiEncoder.RESET_CURSOR_STYLE + LEAVE);
    }

    @Test
    void aCanceledReadErasesTheMenuItWasShowing() throws Exception {
        LineEditor starting = LineEditor.emacs().withCompleter((text, caret) -> List.of("git", "gist"));

        // Open the menu, then abandon the line with ctrl+c.
        Run run = read("gi\t\u0003", starting, new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Canceled());
        assertThat(plain(run.written())).contains("[git]  gist");
        assertThat(run.written())
                .as("the whole region, the menu row included, is erased")
                .endsWith(AnsiEncoder.ERASE_DISPLAY_BELOW
                        + AnsiEncoder.HIDE_CURSOR
                        + AnsiEncoder.RESET_CURSOR_STYLE
                        + LEAVE);
    }

    @Test
    void aMaskedPromptPaintsItsGlyphsAndStillReportsTheRealText() throws Exception {
        Run run = read("hunter2\r", LineEditor.emacs().withMasked(true), new Size(24, 40));

        assertThat(run.output()).isEqualTo(new LineEditor.Output.Accepted("hunter2"));
        assertThat(plain(run.written()))
                .as("nothing the user typed reaches the screen")
                .doesNotContain("hunter2");
        assertThat(plain(run.written())).contains("> *******");
    }

    private record Run(LineEditor.Output output, String written) {}

    /**
     * The text a terminal would show: every CSI sequence removed, so styling cannot hide a cell.
     */
    private static String plain(String written) {
        return written.replaceAll("\u001b\\[[0-9;? ]*[A-Za-z]", "");
    }

    private static Run read(String input, LineEditor editor, Size size) throws TerminalException {
        return read(input, editor, size, "> ");
    }

    private static Run read(String input, LineEditor editor, Size size, String prompt) throws TerminalException {
        StringWriter written = new StringWriter();
        LineEditor.Output output;
        try (TerminalSession session = TerminalSession.of(new StringReader(input), written, size)) {
            // A stream session negotiates no color of its own, so the default AUTO profile is the
            // plain one here: the only bytes the assertions can see.
            output = LineReader.of(session).withMode(Mode.EMACS).read(prompt, editor);
        }
        return new Run(output, written.toString());
    }
}
