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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;

import examples.parity.LinePromptsDemo;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Phase 29 acceptance: the four line prompts, asked in order by the demo's own script.
 *
 * <p>The script is the packaged demo's transcript, driven here over a stream-backed session with the
 * bytes a terminal sends, so the parity record's scenario and this regression assert the same run.
 * </p>
 */
class ParityLinePromptsTest {

    @Test
    void theScriptAsksEveryPromptAndPublishesWhatWasAnswered() throws Exception {
        Run run = script("y\r" + "\u001b[B\r" + " \u001b[B \r");

        assertThat(run.status()).isEqualTo("prompts confirm=yes theme=light targets=2/4 progress=6/6");
        assertThat(plain(run.written())).contains("confirm=yes", "theme=light", "targets=linux-x86_64,linux-aarch64");
    }

    @Test
    void theTranscriptKeepsEverythingAboveThePrompts() throws Exception {
        Run run = script("y\r" + "\r" + "\r");

        assertThat(plain(run.written())).startsWith(LinePromptsDemo.HEADING);
        // An inline program never clears the screen it was started on.
        assertThat(run.written()).doesNotContain("\u001b[2J");
    }

    @Test
    void aCanceledPromptAnswersThatItWasCanceledAndTheScriptCarriesOn() throws Exception {
        Run run = script("\u0003\u0003\u0003");

        assertThat(run.status()).isEqualTo("prompts confirm=canceled theme=canceled targets=canceled progress=6/6");
    }

    @Test
    void theProgressPromptIsLeftOnScreenWithItsFinalCount() throws Exception {
        Run run = script("\r\r\r");

        assertThat(plain(run.written()))
                .contains("Installing packages")
                .contains(LinePromptsDemo.PACKAGES + "/" + LinePromptsDemo.PACKAGES)
                .contains("100%");
    }

    @Test
    void everyPromptRowSaysWhichGesturesAnswerIt() throws Exception {
        String written = script("\r\r\r").written();

        assertThat(plain(written))
                .contains("Overwrite the report? [y/N] ")
                .contains("Pick a theme  (enter chooses, esc cancels)")
                .contains("Build which targets  (space marks, enter chooses, esc cancels)");
    }

    private record Run(String status, String written) {}

    /**
     * The text a terminal would show: every CSI sequence removed, so styling cannot hide a cell.
     */
    private static String plain(String written) {
        return written.replaceAll("\u001b\\[[0-9;? ]*[A-Za-z]", "");
    }

    private static Run script(String input) throws Exception {
        StringWriter written = new StringWriter();
        try (TerminalSession session = TerminalSession.of(new StringReader(input), written, new Size(24, 80))) {
            return new Run(LinePromptsDemo.script(session, ColorProfile.NONE), written.toString());
        }
    }
}
