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

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The yes-or-no prompt, driven over a stream-backed session: no TTY, no native library, and the same
 * bytes a terminal would receive.
 */
class LineConfirmPromptTest {

    private static final Size SIZE = new Size(24, 60);

    @Test
    void yesIsYesAndTheQuestionCarriesTheDefault() throws Exception {
        Run run = ask("y\r", ConfirmPrompt.of("Overwrite the report?"));

        assertThat(run.answer()).contains(true);
        assertThat(plain(run.written())).contains("Overwrite the report? [y/N] ");
    }

    @Test
    void theAnswerIsOneOfFourWordsInAnyCase() throws Exception {
        assertThat(ask("YES\r", ConfirmPrompt.of("go?")).answer()).contains(true);
        assertThat(ask("Yes\r", ConfirmPrompt.of("go?")).answer()).contains(true);
        assertThat(ask("No\r", ConfirmPrompt.of("go?")).answer()).contains(false);
        assertThat(ask(" NO \r", ConfirmPrompt.of("go?")).answer()).contains(false);
    }

    @Test
    void aBareEnterTakesTheDefault() throws Exception {
        Run yes = ask("\r", ConfirmPrompt.of("keep going?", true));
        Run no = ask("\r", ConfirmPrompt.of("delete it?", false));

        assertThat(yes.answer()).contains(true);
        assertThat(no.answer()).contains(false);
        assertThat(plain(yes.written())).contains("keep going? [Y/n] ");
        assertThat(plain(no.written())).contains("delete it? [y/N] ");
    }

    @Test
    void anAnswerItCannotReadAsksAgain() throws Exception {
        Run run = ask("maybe\ryes\r", ConfirmPrompt.of("Overwrite the report?"));

        assertThat(run.answer()).contains(true);
        // Two lines were accepted - the rejected one and the answer - so the question was asked
        // twice and the first answer was left on screen above the second question.
        assertThat(count(run.written(), "\r\r\n")).isEqualTo(2);
        assertThat(plain(run.written())).contains("maybe");
    }

    @Test
    void cancelAbandonsTheQuestionInsteadOfAnsweringNo() throws Exception {
        Run run = ask("y\u0003", ConfirmPrompt.of("Overwrite the report?"));

        assertThat(run.answer()).isEmpty();
        // The abandoned prompt erases the row it painted, so nothing half-typed is left behind.
        assertThat(run.written()).endsWith("\u001b[0m\u001b[?25h" + "\u001b[?2004l");
    }

    @Test
    void theEndOfTheInputAbandonsTheQuestion() throws Exception {
        assertThat(ask("", ConfirmPrompt.of("Overwrite the report?")).answer()).isEmpty();
    }

    @Test
    void aQuestionIsFlattenedToOneRow() throws Exception {
        Run run = ask("n\r", ConfirmPrompt.of("Overwrite\nnow?"));

        assertThat(run.answer()).contains(false);
        assertThat(plain(run.written())).contains("Overwrite now? [y/N] ");
        assertThat(plain(run.written())).doesNotContain("Overwrite\nnow");
    }

    @Test
    void aPastedAnswerIsAnsweredLikeATypedOne() throws Exception {
        assertThat(ask("\u001b[200~yes\u001b[201~\r", ConfirmPrompt.of("go?")).answer())
                .contains(true);
    }

    @Test
    void theDefaultIsNoUnlessTheCallerSaysOtherwise() {
        assertThat(ConfirmPrompt.of("go?").defaultAnswer()).isFalse();
        assertThat(ConfirmPrompt.of("go?", true).defaultAnswer()).isTrue();
        assertThat(ConfirmPrompt.of("go?").label()).isEqualTo("go? [y/N] ");
        assertThat(ConfirmPrompt.of("go?", true).label()).isEqualTo("go? [Y/n] ");
        assertThat(ConfirmPrompt.of(null).label()).isEqualTo("[y/N] ");
    }

    @Test
    void theWithersAnswerNewValuesAndLeaveTheReceiverAlone() {
        ConfirmPrompt original = ConfirmPrompt.of("first?");
        ConfirmPrompt changed = original.withQuestion("second?").withDefault(true);

        assertThat(original.question()).isEqualTo("first?");
        assertThat(original.defaultAnswer()).isFalse();
        assertThat(changed.question()).isEqualTo("second?");
        assertThat(changed.defaultAnswer()).isTrue();
        // A null question is no question rather than a failure, the rule every wither follows.
        assertThat(ConfirmPrompt.of("go?").withQuestion(null).question()).isEmpty();
    }

    private record Run(Optional<Boolean> answer, String written) {}

    /**
     * The text a terminal would show: every CSI sequence removed, so styling cannot hide a cell.
     */
    private static String plain(String written) {
        return written.replaceAll("\u001b\\[[0-9;? ]*[A-Za-z]", "");
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    private static Run ask(String input, ConfirmPrompt prompt) throws TerminalException {
        StringWriter written = new StringWriter();
        Optional<Boolean> answer;
        try (TerminalSession session = TerminalSession.of(new StringReader(input), written, SIZE)) {
            answer = prompt.ask(LineReader.of(session).withColorProfile(ColorProfile.NONE));
        }
        return new Run(answer, written.toString());
    }
}
