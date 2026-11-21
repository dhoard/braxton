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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The multiple-choice prompt, driven over a stream-backed session: {@code space} marks, {@code enter}
 * confirms, and every mark comes back in the list's own order.
 */
class LineMultiSelectPromptTest {

    private static final Size SIZE = new Size(24, 80);

    private static final List<String> TARGETS = List.of("linux-x86_64", "linux-aarch64", "macos-arm64", "macos-x86_64");

    private static final String LEAVE =
            AnsiEncoder.RESET + AnsiEncoder.SHOW_CURSOR + AnsiEncoder.DISABLE_BRACKETED_PASTE;

    @Test
    void spaceMarksTheHighlightedOptionAndEnterConfirms() throws Exception {
        Run run = ask(" \u001b[B \r", MultiSelectPrompt.of("Build which targets", TARGETS));

        assertThat(run.answer()).contains(List.of("linux-x86_64", "linux-aarch64"));
        assertThat(plain(run.written())).contains("Build which targets  (space marks, enter chooses, esc cancels)");
        assertThat(plain(run.written())).contains("[\u2713] linux-x86_64");
        assertThat(plain(run.written())).contains("[ ] macos-arm64");
    }

    @Test
    void spaceAgainUnmarksTheSameOption() throws Exception {
        Run run = ask("  \r", MultiSelectPrompt.of("Build which targets", TARGETS));

        assertThat(run.answer()).contains(List.of());
    }

    @Test
    void confirmingWithNothingMarkedIsNoneRatherThanACancellation() throws Exception {
        Run run = ask("\r", MultiSelectPrompt.of("Build which targets", TARGETS));

        assertThat(run.answer()).as("an empty selection is a real answer").contains(List.of());
    }

    @Test
    void theMarksComeBackInTheListsOrderNotTheOrderTheyWereMade() throws Exception {
        // Mark the third, then the first: the answer is the list's order.
        Run run = ask("\u001b[B\u001b[B \u001b[H \r", MultiSelectPrompt.of("Build", TARGETS));

        assertThat(run.answer()).contains(List.of("linux-x86_64", "macos-arm64"));
    }

    @Test
    void cancelAbandonsEveryMarkMadeSoFar() throws Exception {
        Run run = ask(" \u0003", MultiSelectPrompt.of("Build which targets", TARGETS));

        assertThat(run.answer()).isEmpty();
        assertThat(run.written()).endsWith(AnsiEncoder.ERASE_DISPLAY_BELOW + LEAVE);
    }

    @Test
    void aPromptWithNothingToOfferAsksNothingAndWritesNothing() throws Exception {
        Run run = ask(" \r", MultiSelectPrompt.of("Build which targets", List.of()));

        assertThat(run.answer()).isEmpty();
        assertThat(run.written()).isEmpty();
    }

    @Test
    void twoIdenticalLabelsAreTwoMarks() throws Exception {
        Run run = ask(" \u001b[B \r", MultiSelectPrompt.of("Build", List.of("same", "same")));

        assertThat(run.answer())
                .as("the prompt answers what was marked, not a de-duplicated set")
                .contains(List.of("same", "same"));
    }

    @Test
    void aGestureThePromptDoesNotAnswerChangesNothing() throws Exception {
        assertThat(ask("x\u001b[A\r", MultiSelectPrompt.of("Build", TARGETS)).answer())
                .as("an unbound letter does not mark")
                .contains(List.of());
        assertThat(ask("\u001b[200~ \u001b[201~\r", MultiSelectPrompt.of("Build", TARGETS))
                        .answer())
                .as("a pasted space is not a mark")
                .contains(List.of());
    }

    @Test
    void theValuePreparesItsOptionsAndTheWithersLeaveItAlone() {
        MultiSelectPrompt prompt = MultiSelectPrompt.of("Build", List.of("one", "two\nlines", " "));

        assertThat(prompt.options()).containsExactly("one", "two lines");
        assertThat(prompt.rows()).isEqualTo(MultiSelectPrompt.DEFAULT_ROWS);
        assertThat(prompt.withRows(2).rows()).isEqualTo(2);
        assertThat(prompt.withRows(0).rows()).isEqualTo(MultiSelectPrompt.DEFAULT_ROWS);
        assertThat(prompt.withOptions(null).options()).isEmpty();
        assertThat(prompt.withQuestion("Install").question()).isEqualTo("Install");
        assertThat(prompt.question()).isEqualTo("Build");
        assertThat(MultiSelectPrompt.of(null, List.of("one")).question()).isEmpty();
    }

    private record Run(Optional<List<String>> answer, String written) {}

    /**
     * The text a terminal would show: every CSI sequence removed, so styling cannot hide a cell.
     */
    private static String plain(String written) {
        return written.replaceAll("\u001b\\[[0-9;? ]*[A-Za-z]", "");
    }

    private static Run ask(String input, MultiSelectPrompt prompt) throws TerminalException {
        StringWriter written = new StringWriter();
        Optional<List<String>> answer;
        try (TerminalSession session = TerminalSession.of(new StringReader(input), written, SIZE)) {
            answer = prompt.ask(LineReader.of(session).withColorProfile(ColorProfile.NONE));
        }
        return new Run(answer, written.toString());
    }
}
