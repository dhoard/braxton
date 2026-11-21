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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One choice from a list, made with the keyboard.
 *
 * <p>The prompt paints its question and the options the window shows into the inline region, the way
 * {@link LineReader} paints a line: where the terminal's cursor already is, never the alternate
 * screen, and nothing above the region is touched. The highlighted option carries an arrow and the
 * theme's selection, and the question row says which gestures answer it.</p>
 *
 * <p>{@code up}/{@code down} move the highlight, {@code home}/{@code end} take it to either end,
 * {@code pageup}/{@code pagedown} move it a window at a time, {@code enter} or {@code space} chooses
 * the highlighted option, and {@code escape} or {@code ctrl+c} abandons the prompt. A movement that
 * cannot move changes nothing: the highlight clamps rather than wrapping, so holding {@code down}
 * stays on the last option. Any other key does nothing at all, which leaves the user's next
 * keystroke to the gesture it was meant for rather than turning it into a choice.</p>
 *
 * <p>{@link #ask} answers the chosen option, or {@link Optional#empty()} when the prompt was
 * abandoned or has nothing to offer. It answers a label rather than an index because a label is what
 * the user saw and what an application compares against, the rule {@code widget.Select}'s output
 * follows: a list with two identical labels is the caller's ambiguity to resolve. A choice leaves the
 * final frame on screen, so a program's transcript shows what was asked and what was offered.</p>
 *
 * <p>The prompt is a value: {@link #withQuestion}, {@link #withOptions} and {@link #withRows} return
 * new values and never change the one they were called on. The options are prepared once - each
 * flattened to one row, and a blank label dropped - so {@link #options()} answers what the prompt
 * really shows.</p>
 */
public final class SelectPrompt {

    /**
     * The option rows a prompt shows at once when the caller does not choose a window.
     */
    public static final int DEFAULT_ROWS = 10;

    private final String question;
    private final List<String> options;
    private final int rows;

    private SelectPrompt(String question, List<String> options, int rows) {
        this.question = question == null ? "" : question;
        this.options = PromptList.prepare(options);
        this.rows = rows <= 0 ? DEFAULT_ROWS : rows;
    }

    /**
     * A prompt offering a list, with the first option highlighted and nothing chosen yet.
     *
     * @param question the question painted above the options; null is none
     * @param options the labels to offer; null offers nothing
     * @return the prompt
     */
    public static SelectPrompt of(String question, List<String> options) {
        return new SelectPrompt(question, options, DEFAULT_ROWS);
    }

    /**
     * The same prompt asking another question.
     *
     * @param value the question; null is no question
     * @return the prompt
     */
    public SelectPrompt withQuestion(String value) {
        return new SelectPrompt(value, options, rows);
    }

    /**
     * The same prompt offering another list.
     *
     * @param value the labels to offer; null offers nothing
     * @return the prompt
     */
    public SelectPrompt withOptions(List<String> value) {
        return new SelectPrompt(question, value, rows);
    }

    /**
     * The same prompt showing another number of option rows at once.
     *
     * <p>The window follows the highlight, so a list longer than the window scrolls rather than
     * being cut: the option the user is on is always one of the rows painted.</p>
     *
     * @param value the rows to show; a value below one shows {@link #DEFAULT_ROWS}
     * @return the prompt
     */
    public SelectPrompt withRows(int value) {
        return new SelectPrompt(question, options, value);
    }

    /**
     * The question painted above the options.
     *
     * @return the question painted above the options
     */
    public String question() {
        return question;
    }

    /**
     * The labels the prompt offers, as it shows them.
     *
     * @return the labels the prompt offers, as it shows them
     */
    public List<String> options() {
        return options;
    }

    /**
     * The option rows the prompt shows at once.
     *
     * @return the option rows the prompt shows at once
     */
    public int rows() {
        return rows;
    }

    /**
     * Asks the question and answers the option the user chose.
     *
     * @param reader the reader that supplies the session, the theme and the measurement; required
     * @return the chosen label, or empty when the prompt was abandoned or offered nothing
     * @throws TerminalException when the session cannot be read from or written to
     */
    public Optional<String> ask(LineReader reader) throws TerminalException {
        Objects.requireNonNull(reader, "reader");
        return PromptList.ask(PromptList.of(question, options, rows, false), reader)
                .map(PromptList::selectedOption);
    }
}
