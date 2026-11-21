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

import io.github.dhoard.braxton.terminal.TerminalException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A yes-or-no question, read as one line.
 *
 * <p>A confirmation is a line rather than a single keystroke, for the reason every careful
 * command-line program reads one: a user who types {@code yes} at a one-key prompt answers three
 * times and leaves two keystrokes for whatever reads next, and a user who pastes an answer is
 * answered the same way as one who types it. The answer is the line {@code y}, {@code yes},
 * {@code n} or {@code no} in any case; a bare {@code enter} takes the default the prompt was built
 * with, and an answer it cannot read asks the question again.</p>
 *
 * <p>The read itself is {@link LineReader}'s, so a confirmation is painted exactly where the
 * terminal's cursor is, keeps everything above it, and repaints on a resize like any other read.
 * Its cancellation is the editor's: {@code ctrl+c} abandons the answer, as it does for a line, and
 * an answer that ends with the input ends the prompt. Both answer {@link Optional#empty()} rather
 * than a guess, because a caller that overwrites a file must not be able to mistake an abandoned
 * prompt for a {@code no}.</p>
 *
 * <p>The prompt is a value: {@link #withQuestion}, {@link #withDefault} and {@link #ask} return new
 * values or leave the receiver alone, so one confirmation can be asked any number of times.</p>
 */
public final class ConfirmPrompt {

    private final String question;
    private final boolean defaultAnswer;

    private ConfirmPrompt(String question, boolean defaultAnswer) {
        this.question = question == null ? "" : question;
        this.defaultAnswer = defaultAnswer;
    }

    /**
     * A confirmation whose default answer is no, which is the safe default for a destructive
     * question.
     *
     * @param question the question to ask; null is no question
     * @return the prompt
     */
    public static ConfirmPrompt of(String question) {
        return new ConfirmPrompt(question, false);
    }

    /**
     * A confirmation with an explicit default answer.
     *
     * @param question the question to ask; null is no question
     * @param defaultAnswer the answer a bare {@code enter} gives
     * @return the prompt
     */
    public static ConfirmPrompt of(String question, boolean defaultAnswer) {
        return new ConfirmPrompt(question, defaultAnswer);
    }

    /**
     * The same prompt asking another question.
     *
     * @param value the question; null is no question
     * @return the prompt
     */
    public ConfirmPrompt withQuestion(String value) {
        return new ConfirmPrompt(value, defaultAnswer);
    }

    /**
     * The same prompt with another default answer.
     *
     * @param value the answer a bare {@code enter} gives
     * @return the prompt
     */
    public ConfirmPrompt withDefault(boolean value) {
        return new ConfirmPrompt(question, value);
    }

    /**
     * The question to ask.
     *
     * @return the question to ask
     */
    public String question() {
        return question;
    }

    /**
     * The answer a bare {@code enter} gives.
     *
     * @return {@code true} if the answer a bare {@code enter} gives; otherwise {@code false}
     */
    public boolean defaultAnswer() {
        return defaultAnswer;
    }

    /**
     * Asks the question and answers what the user said.
     *
     * @param reader the reader to ask with; required
     * @return the answer, or empty when the question was abandoned or the input ended
     * @throws TerminalException when the session cannot be read from or written to
     */
    public Optional<Boolean> ask(LineReader reader) throws TerminalException {
        Objects.requireNonNull(reader, "reader");
        String label = label();
        while (true) {
            LineEditor.Output output = reader.read(label);
            if (!(output instanceof LineEditor.Output.Accepted accepted)) {
                return Optional.empty();
            }
            Optional<Boolean> answer = answer(accepted.text());
            if (answer.isPresent()) {
                return answer;
            }
            // An answer the prompt cannot read asks again, with the line the user typed left above
            // the question, so the row that follows says what was rejected and what is wanted.
        }
    }

    /**
     * What the prompt paints: the question, then the default answer as the capitalised choice.
     *
     * <p>The question is flattened to one row, because a label that wrapped would move the line it
     * is being asked about and a label that carried an escape sequence would reach the terminal.</p>
     */
    String label() {
        String choice = defaultAnswer ? "[Y/n] " : "[y/N] ";
        String asked = LineText.singleRow(question);
        return asked.isEmpty() ? choice : asked + " " + choice;
    }

    /**
     * The answer a line gives: yes, no, the default for an empty line, or nothing readable.
     */
    private Optional<Boolean> answer(String text) {
        String normalized = LineText.singleRow(text).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "" -> Optional.of(defaultAnswer);
            case "y", "yes" -> Optional.of(true);
            case "n", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }
}
