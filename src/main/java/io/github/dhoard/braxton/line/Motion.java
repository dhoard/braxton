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

/**
 * A caret movement a {@link LineEditor} can make, named rather than spelled.
 *
 * <p>A motion is the vocabulary both modes share, which is why a Vi operator can be armed and then
 * resolved by any of them: {@link LineEditor.Msg.Move} moves the caret and
 * {@link LineEditor.Msg.Remove} removes what a motion covers, so the same motion means the same
 * range in both messages. Every motion is measured in grapheme clusters and answers a caret that is
 * on a cluster boundary, which is what keeps an edit from splitting a combining mark, a ZWJ sequence
 * or a surrogate pair.</p>
 *
 * <p>This editor has one caret model for both modes: a position between clusters, from {@code 0} to
 * the text's length. Vi's habit of leaving the cursor <em>on</em> a character is expressed by the
 * one-cluster difference between {@link LineEditor.Place#HERE} and
 * {@link LineEditor.Place#AFTER}, so {@code l} may move past the last character here where Vi's
 * would stop on it.</p>
 */
public enum Motion {

    /**
     * One cluster toward the start.
     */
    BACK,

    /**
     * One cluster toward the end.
     */
    FORWARD,

    /**
     * The start of the word before the caret, skipping blanks.
     */
    WORD_BACK,

    /**
     * The start of the next word, skipping blanks and the run the caret is in.
     */
    WORD_FORWARD,

    /**
     * Just past the last cluster of the word the caret is in or the next one.
     */
    WORD_END,

    /**
     * The first column.
     */
    LINE_START,

    /**
     * Just past the last cluster.
     */
    LINE_END
}
