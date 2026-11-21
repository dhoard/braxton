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

package examples.parity;

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.line.ConfirmPrompt;
import io.github.dhoard.braxton.line.LineReader;
import io.github.dhoard.braxton.line.MultiSelectPrompt;
import io.github.dhoard.braxton.line.ProgressPrompt;
import io.github.dhoard.braxton.line.SelectPrompt;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.util.List;
import java.util.Optional;

/**
 * Phase 29 parity demo: the four line prompts, asked in order by a line-oriented program.
 *
 * <p>Nothing here owns the screen. The program opens the terminal, enters raw mode and then asks a
 * confirmation, a single choice, a set of marks and a bounded progress count, printing one durable
 * answer line after each so an owned PTY verifier can assert where the dialog reached without
 * guessing from styled cells. Everything above the prompts survives the whole session, which is what
 * makes a wizard usable after a command has already printed its output.</p>
 *
 * <p>The script is one method over a session, so the same transcript is what the packaged demo runs
 * on a real terminal and what the headless regression drives with scripted keys on a stream-backed
 * session. The final status line is the one thing both assert:</p>
 *
 * <pre>
 * prompts confirm=yes theme=light targets=2/4 progress=6/6
 * </pre>
 */
public final class LinePromptsDemo {

    /**
     * The heading the transcript starts with.
     */
    public static final String HEADING = "Braxton line prompts";

    /**
     * The single choices the select prompt offers.
     */
    public static final List<String> THEMES = List.of("dark", "light", "high-contrast");

    /**
     * The marks the multiple-choice prompt offers.
     */
    public static final List<String> TARGETS = List.of("linux-x86_64", "linux-aarch64", "macos-arm64", "macos-x86_64");

    /**
     * The steps the progress prompt counts.
     */
    public static final int PACKAGES = 6;

    private LinePromptsDemo() {}

    /**
     * Runs the packaged demo on the controlling terminal.
     *
     * @return how the run ended
     */
    public static ExitResult run() {
        try (TerminalSession session = TerminalSession.open()) {
            session.enterRawMode();
            script(session, ColorProfile.AUTO);
            return ExitResult.quit(0);
        } catch (TerminalException failure) {
            return ExitResult.failure(failure, List.of());
        }
    }

    /**
     * Asks the four prompts over a session the caller supplies and answers the status line.
     *
     * <p>The session is not opened, closed or put in raw mode here: the caller owns it, which is
     * what lets a test drive the same transcript with a stream-backed session and no terminal at
     * all. Every durable line is written as {@code CR LF}, because a raw terminal translates
     * neither.</p>
     *
     * @param session the session to ask over; required
     * @param profile the profile the prompts encode with
     * @return the status line the transcript ends with
     * @throws TerminalException when the session cannot be read from or written to
     */
    public static String script(TerminalSession session, ColorProfile profile) throws TerminalException {
        LineReader reader = LineReader.of(session).withColorProfile(profile);
        print(session, HEADING);

        Optional<Boolean> confirmed =
                ConfirmPrompt.of("Overwrite the report?", false).ask(reader);
        print(
                session,
                "confirm=" + confirmed.map(answer -> answer ? "yes" : "no").orElse("canceled"));

        Optional<String> theme = SelectPrompt.of("Pick a theme", THEMES).ask(reader);
        print(session, "theme=" + theme.orElse("canceled"));

        Optional<List<String>> targets =
                MultiSelectPrompt.of("Build which targets", TARGETS).ask(reader);
        print(session, "targets=" + targets.map(LinePromptsDemo::joined).orElse("canceled"));

        try (ProgressPrompt progress = ProgressPrompt.open(reader, "Installing packages", PACKAGES)) {
            for (int step = 0; step < PACKAGES; step++) {
                progress.step();
            }
        }

        String status = status(confirmed, theme, targets);
        print(session, status);
        return status;
    }

    /**
     * What the transcript ends with: one line naming every answer.
     */
    private static String status(Optional<Boolean> confirmed, Optional<String> theme, Optional<List<String>> targets) {
        return "prompts"
                + " confirm=" + confirmed.map(answer -> answer ? "yes" : "no").orElse("canceled")
                + " theme=" + theme.orElse("canceled")
                + " targets="
                + targets.map(marks -> marks.size() + "/" + TARGETS.size()).orElse("canceled")
                + " progress=" + PACKAGES + "/" + PACKAGES;
    }

    /**
     * The marks as one answer ready to print.
     */
    private static String joined(List<String> marks) {
        return marks.isEmpty() ? "none" : String.join(",", marks);
    }

    /**
     * One durable line: a raw terminal does not translate LF and writeText turns CR into a space.
     */
    private static void print(TerminalSession session, String line) throws TerminalException {
        session.writeText(line);
        session.write("\r\n");
        session.flush();
    }

    /**
     * Runs the phase scenario.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }
}
