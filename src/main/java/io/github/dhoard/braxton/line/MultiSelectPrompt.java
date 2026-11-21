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
 * Several choices from a list, made with the keyboard.
 *
 * <p>The prompt is {@link SelectPrompt}'s shape over a set of marks rather than one choice: the
 * question row and a window of options are painted into the inline region, each option carrying an
 * accented box when it is marked, and the highlighted row carrying the theme's selection. The
 * question row says which gestures answer it.</p>
 *
 * <p>{@code up}/{@code down}, {@code home}/{@code end} and {@code pageup}/{@code pagedown} move the
 * highlight, {@code space} marks or unmarks the highlighted option and stays where it is, so marking
 * two options that are not neighbors costs the moves the user intends rather than a skip it did not
 * ask for, {@code enter} accepts the marks made so far, and {@code escape} or {@code ctrl+c}
 * abandons the prompt. Any other key does nothing at all.</p>
 *
 * <p>{@link #ask} answers every marked label in the order the list holds them, which is the
 * application's order and not the order they were marked in, and answers
 * {@link Optional#empty()} only when the prompt was abandoned or has nothing to offer - an empty
 * list of marks is a real answer, "none of these", and a caller that requires at least one mark asks
 * again rather than reading the empty list as a cancellation. A choice leaves the final frame on
 * screen, the rule the other prompts follow.</p>
 *
 * <p>The prompt is a value, and its options are prepared once like {@link SelectPrompt}'s, so
 * {@link #options()} answers what the prompt really shows.</p>
 */
public final class MultiSelectPrompt {

    /**
     * The option rows a prompt shows at once when the caller does not choose a window.
     */
    public static final int DEFAULT_ROWS = 10;

    private final String question;
    private final List<String> options;
    private final int rows;

    private MultiSelectPrompt(String question, List<String> options, int rows) {
        this.question = question == null ? "" : question;
        this.options = PromptList.prepare(options);
        this.rows = rows <= 0 ? DEFAULT_ROWS : rows;
    }

    /**
     * A prompt offering a list, with the first option highlighted and nothing marked.
     *
     * @param question the question painted above the options; null is none
     * @param options the labels to offer; null offers nothing
     * @return the prompt
     */
    public static MultiSelectPrompt of(String question, List<String> options) {
        return new MultiSelectPrompt(question, options, DEFAULT_ROWS);
    }

    /**
     * The same prompt asking another question.
     *
     * @param value the question; null is no question
     * @return the prompt
     */
    public MultiSelectPrompt withQuestion(String value) {
        return new MultiSelectPrompt(value, options, rows);
    }

    /**
     * The same prompt offering another list.
     *
     * @param value the labels to offer; null offers nothing
     * @return the prompt
     */
    public MultiSelectPrompt withOptions(List<String> value) {
        return new MultiSelectPrompt(question, value, rows);
    }

    /**
     * The same prompt showing another number of option rows at once.
     *
     * @param value the rows to show; a value below one shows {@link #DEFAULT_ROWS}
     * @return the prompt
     */
    public MultiSelectPrompt withRows(int value) {
        return new MultiSelectPrompt(question, options, value);
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
     * Asks the question and answers the options the user marked.
     *
     * @param reader the reader that supplies the session, the theme and the measurement; required
     * @return the marked labels in the list's order, or empty when the prompt was abandoned or
     *     offered nothing
     * @throws TerminalException when the session cannot be read from or written to
     */
    public Optional<List<String>> ask(LineReader reader) throws TerminalException {
        Objects.requireNonNull(reader, "reader");
        return PromptList.ask(PromptList.of(question, options, rows, true), reader)
                .map(PromptList::markedOptions);
    }
}
