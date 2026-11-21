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

import io.github.dhoard.braxton.clipboard.PasteMode;
import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.util.List;

/**
 * The one-row text arithmetic a {@link LineEditor}, its renderer and its reader share.
 *
 * <p>A line editor holds one row of text, so everything here is in grapheme clusters and terminal
 * columns: a caret index is a cluster boundary, a width is the columns a cluster paints, and the
 * only place a bare character offset is used is {@link String}'s own substring arithmetic. The two
 * entry points that prepare text ({@link #singleRow(String)} and {@link #pasted(String)}) are the
 * only ways text enters an editor, which is how a control character can never reach a painted cell:
 * a pasted escape sequence is removed by the project's one sanitizer, and every remaining control
 * character - including a tab, which has no tab stop in a cell grid - becomes one space.</p>
 */
final class LineText {

    /**
     * A cluster that separates words.
     */
    static final int BLANK = 0;

    /**
     * A cluster that is a letter, a digit or an underscore.
     */
    static final int WORD = 1;

    /**
     * A cluster that is neither a word nor a blank: punctuation and symbols.
     */
    static final int PUNCTUATION = 2;

    /**
     * The glyph a masked editor paints for every cluster of its line.
     *
     * <p>One cell rather than one cluster: a mask says how much has been typed and nothing about
     * what, so a wide cluster and a narrow one are the same one mask cell, and the column a caret
     * sits in is the number of clusters before it.</p>
     */
    static final String MASK = "*";

    private LineText() {}

    /**
     * One row of {@code value}: every line break becomes one space, and every other control
     * character becomes one space.
     *
     * <p>The conversion is the project's own {@link LineBreaks#singleRow(String)}, so CR, LF and CRLF
     * agree with every other one-row surface, and only the characters a cell cannot carry are
     * replaced. Nothing is trimmed: a default value or a prompt keeps its leading and trailing
     * blanks, because both are the caller's text and not a paste artifact.</p>
     *
     * @param value the text to prepare; null is empty
     * @return one row of printable text
     */
    static String singleRow(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String flat = LineBreaks.singleRow(value);
        StringBuilder safe = null;
        for (int index = 0; index < flat.length(); index++) {
            char character = flat.charAt(index);
            if (Character.isISOControl(character)) {
                if (safe == null) {
                    safe = new StringBuilder(flat);
                }
                safe.setCharAt(index, ' ');
            }
        }
        return safe == null ? flat : safe.toString();
    }

    /**
     * The rows a stored value holds: one entry per line break, LF, CRLF and CR alike.
     *
     * <p>The counterpart of {@link #singleRow(String)} for text that is one row per entry rather than
     * one row in total - a history file - so a stored value is split by the same policy that joins a
     * line into a cell. Nothing is prepared here beyond the split: a caller still passes each row
     * through {@link #singleRow(String)}, because a stored entry can carry a control character of its
     * own.</p>
     *
     * @param value the stored text; null is no rows
     * @return the rows, in order, with an empty row where the value has two breaks in a row
     */
    static List<String> splitRows(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return List.of(LineBreaks.normalize(value).split("\n", -1));
    }

    /**
     * A pasted payload as one row of text.
     *
     * <p>A paste is untrusted: it can carry an escape sequence, a carriage return, a tab, or a
     * zero-width character a terminal would mis-measure. {@link PasteSanitizer} removes the
     * artifacts and {@link #singleRow(String)} flattens what is left, so the result is exactly the
     * kind of text a typed key would have inserted - never a keystroke, and never a control
     * character that reaches the terminal.</p>
     *
     * @param value the complete paste payload; null is empty
     * @return one row of printable text
     */
    static String pasted(String value) {
        return singleRow(PasteSanitizer.sanitize(value, PasteMode.NORMAL));
    }

    /**
     * The boundary at or before {@code index}.
     *
     * <p>The answer is {@code index} itself when it already is a cluster boundary, which is what
     * clamping a caret needs; {@link #previous(String, int)} is the strict step back a motion
     * makes.</p>
     *
     * @param text the text to measure; null is empty
     * @param index the index to clamp
     * @return the cluster boundary at or before the index
     */
    static int boundaryBefore(String text, int index) {
        return TextMeasure.boundaryBefore(text, index);
    }

    /**
     * The boundary before {@code index}.
     *
     * @param text the text to measure; null is empty
     * @param index the index to move back from
     * @return the previous cluster boundary, clamped to the text
     */
    static int previous(String text, int index) {
        return TextMeasure.previousBoundary(text, index);
    }

    /**
     * The boundary after {@code index}.
     *
     * @param text the text to measure; null is empty
     * @param index the index to move forward from
     * @return the next cluster boundary, clamped to the text
     */
    static int next(String text, int index) {
        return TextMeasure.boundaryAfter(text, index);
    }

    /**
     * The columns {@code text} occupies under the pinned policy.
     *
     * @param text the one row to measure; null is empty
     * @return the columns
     */
    static int columns(String text) {
        return TextMeasure.columns(text, WidthPolicy.PINNED);
    }

    /**
     * The columns the text before {@code index} occupies.
     *
     * @param text the one row to measure
     * @param index a cluster boundary
     * @return the columns of the prefix
     */
    static int columnsBefore(String text, int index) {
        int limit = Math.max(0, Math.min(index, text == null ? 0 : text.length()));
        return limit == 0 ? 0 : columns(text.substring(0, limit));
    }

    /**
     * Which of the three word classes a cluster belongs to.
     *
     * <p>Letters, digits and an underscore are one class, blanks are another, and everything else is
     * a third, so a motion steps over a run of the class it is in - which is what makes
     * {@code foo.bar} three stops and {@code a  b} two. The class of a cluster is the class of its
     * first code point, because a cluster has one base.</p>
     *
     * @param cluster the cluster to classify
     * @return {@link #BLANK}, {@link #WORD} or {@link #PUNCTUATION}
     */
    static int kind(String cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return PUNCTUATION;
        }
        int codePoint = cluster.codePointAt(0);
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            return BLANK;
        }
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' ? WORD : PUNCTUATION;
    }

    /**
     * The start of the next word: the run the caret is in is skipped, then the blanks after it.
     *
     * @param text the text to move in
     * @param caret the current cluster boundary
     * @return the boundary of the next word's first cluster, or the text's end
     */
    static int wordForward(String text, int caret) {
        int length = text.length();
        int index = Math.max(0, Math.min(caret, length));
        if (index >= length) {
            return length;
        }
        int kind = kind(clusterAt(text, index));
        while (index < length && kind(clusterAt(text, index)) == kind) {
            index = next(text, index);
        }
        while (index < length && kind(clusterAt(text, index)) == BLANK) {
            index = next(text, index);
        }
        return index;
    }

    /**
     * The start of the word before the caret: blanks are skipped, then the run before them.
     *
     * @param text the text to move in
     * @param caret the current cluster boundary
     * @return the boundary of the previous word's first cluster, or zero
     */
    static int wordBack(String text, int caret) {
        int index = Math.max(0, Math.min(caret, text.length()));
        while (index > 0 && kind(clusterBefore(text, index)) == BLANK) {
            index = previous(text, index);
        }
        if (index == 0) {
            return 0;
        }
        int kind = kind(clusterBefore(text, index));
        while (index > 0 && kind(clusterBefore(text, index)) == kind) {
            index = previous(text, index);
        }
        return index;
    }

    /**
     * Just past the last cluster of the word the caret is in or the next one.
     *
     * <p>The answer is a caret position and not a character, so a change over this motion removes the
     * word's last cluster too, which is what makes Vi's {@code cw} the word it means rather than the
     * word minus its end.</p>
     *
     * @param text the text to move in
     * @param caret the current cluster boundary
     * @return the boundary just past the word's last cluster, or the text's end
     */
    static int wordEnd(String text, int caret) {
        int length = text.length();
        int index = Math.max(0, Math.min(caret, length));
        while (index < length && kind(clusterAt(text, index)) == BLANK) {
            index = next(text, index);
        }
        if (index >= length) {
            return length;
        }
        int kind = kind(clusterAt(text, index));
        while (index < length && kind(clusterAt(text, index)) == kind) {
            index = next(text, index);
        }
        return index;
    }

    /**
     * The line a masked editor paints: one mask glyph per cluster.
     *
     * @param text the real line; null is empty
     * @return one mask glyph per cluster, and one column per glyph
     */
    static String masked(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder();
        for (int index = 0; index < text.length(); index = next(text, index)) {
            masked.append(MASK);
        }
        return masked.toString();
    }

    /**
     * The caret's character offset in the line {@link #masked(String)} answers.
     *
     * <p>One glyph per cluster means one character per cluster, so the offset is the count of
     * clusters before the caret. Every caret an editor holds is on a cluster boundary, which is what
     * makes the count exact.</p>
     *
     * @param text the real line; null is empty
     * @param caret the caret's position in the real line
     * @return the caret's offset in the masked line
     */
    static int maskedCaret(String text, int caret) {
        int length = text == null ? 0 : text.length();
        int limit = Math.max(0, Math.min(caret, length));
        int count = 0;
        for (int index = 0; index < limit; index = next(text, index)) {
            count++;
        }
        return count;
    }

    /**
     * The offset where the token that ends at the caret starts.
     *
     * <p>A token is the run of non-blank clusters immediately before the caret: punctuation stays in
     * it, so {@code src/fo} and {@code --out=/tm} are each one token and a completion replaces all of
     * what the user meant to complete. A caret in the middle of blanks, or at the start of the line,
     * starts an empty token.</p>
     *
     * @param text the one row to measure; null is empty
     * @param caret the caret's position between clusters
     * @return the token's first offset
     */
    static int tokenStart(String text, int caret) {
        int length = text == null ? 0 : text.length();
        int index = Math.max(0, Math.min(caret, length));
        while (index > 0 && kind(clusterBefore(text, index)) != BLANK) {
            index = previous(text, index);
        }
        return index;
    }

    /**
     * The cluster that starts at a boundary.
     *
     * @param text the text to read
     * @param index a cluster boundary
     * @return the cluster, or an empty string past the end
     */
    static String clusterAt(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return "";
        }
        return text.substring(index, next(text, index));
    }

    /**
     * The cluster that ends at a boundary.
     *
     * @param text the text to read
     * @param index a cluster boundary
     * @return the cluster, or an empty string at the start
     */
    static String clusterBefore(String text, int index) {
        if (index <= 0 || index > text.length()) {
            return "";
        }
        return text.substring(previous(text, index), index);
    }
}
