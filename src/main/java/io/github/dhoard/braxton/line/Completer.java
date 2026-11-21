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

import java.util.List;

/**
 * A source of completion candidates for the line being edited.
 *
 * <p>The editor asks for candidates when the user presses the completion gesture, and never at any
 * other time: a completer is called from one gesture and not from a paint, so it can do the work a
 * completion needs - list a directory, query an index, match a command table - without a frame
 * paying for it.</p>
 *
 * <p>The answer is the candidates for the <em>token</em> that ends at the caret, where a token is
 * the run of non-blank clusters immediately before the caret, so {@code src/fo} is completed as one
 * token and the accepted candidate replaces all of it. A completer therefore answers whole tokens,
 * not suffixes. Each candidate is prepared to one row before it is offered, so a candidate can
 * never carry a line break or a control character into a painted cell; an empty, blank or missing
 * list means there is nothing to offer, and the editor then leaves the line alone.</p>
 *
 * <p>One candidate completes immediately; two or more open the menu, which is
 * {@link Completion} and which {@link LineRenderer#paintMenu} paints. The editor never inserts a
 * candidate on its own once it has a choice to show.</p>
 *
 * @see LineEditor#withCompleter(Completer)
 */
@FunctionalInterface
public interface Completer {

    /**
     * The candidates for the text and caret, in the order the menu shows them.
     *
     * @param text the one row being edited; never null and never two rows
     * @param caret the caret's position between clusters, from zero to the text's length
     * @return the candidates, or null or an empty list when there is nothing to offer
     */
    List<String> candidates(String text, int caret);
}
