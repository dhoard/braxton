/*
 * Copyright (c) 2026-present Doug Hoard
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

package io.github.dhoard.braxton.widget;

import java.util.Optional;

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.TextMeasure;

/**
 * Immutable editing state: content, caret, selection anchor, and preferred visual column.
 *
 * <p>Positions are UTF-16 offsets that always sit on a grapheme boundary. Selection is represented by
 * an anchor plus the caret; the anchor follows the caret when a move clears the selection.</p>
 *
 * <p>The draft is prepared the way {@link Editor}'s input paths prepare what they insert:
 * {@link LineBreaks#normalize} converts LF, CRLF, and a lone CR to the editor's LF, so a draft an
 * application owns - seeded from a file, a clipboard, a fixture, or a restored session - can never
 * hold a raw carriage return. A CR would be charged one column by the caret's column arithmetic and
 * painted as no cell at all, because a text span drops it as a control character, which puts the
 * caret in a column its row does not draw.</p>
 *
 * <p>The {@code caret} and {@code anchor} a caller passes are offsets in the text it supplied; they
 * are carried across that conversion to the same position in the prepared draft.</p>
 *
 * <p>{@code preferredColumn} is the column a run of vertical moves returns to, measured as the
 * caret's painted column: the columns the editor draws before it, including a configured prompt, so
 * a tab in the draft advances from the same base the text view paints, a masked draft counts one
 * cell per cluster, the one glyph its rows draw, and a cluster with no base character of its own is
 * charged the cell the row paints it in. Zero means measure the caret afresh, which is the case
 * after a horizontal move, an edit, or a move to a line edge.</p>
 */
public record EditorState(String text, int caret, int anchor, int preferredColumn) {

    public EditorState {
        String supplied = text == null ? "" : text;
        // Preparing the line endings where the draft is entered, not only where the reducer inserts,
        // is what keeps the draft one text: the rows paint the draft's own lines, and every offset
        // the state reports counts the characters those rows draw.
        String draft = LineBreaks.normalize(supplied);
        if (draft != supplied) {
            // normalize returns the value it was given when there is nothing to convert, so a new
            // string is the signal that breaks before an offset collapsed to one unit each.
            caret = preparedOffset(supplied, caret);
            anchor = preparedOffset(supplied, anchor);
        }
        text = draft;
        caret = clampToBoundary(text, caret);
        anchor = clampToBoundary(text, anchor);
        preferredColumn = Math.max(0, preferredColumn);
    }

    /**
     * The offset in a {@link LineBreaks#normalize prepared} draft that corresponds to {@code offset}
     * counted in the text the caller supplied: one CR, or one CR/LF pair, becomes the one LF that
     * breaks the line. An offset between the CR and the LF of one pair - a position no grapheme
     * boundary names, because the pair is a single cluster - maps to the start of the LF replacing
     * it, the position the boundary clamping below would have chosen in the supplied text.
     */
    private static int preparedOffset(String supplied, int offset) {
        int limit = Math.max(0, Math.min(offset, supplied.length()));
        int units = 0;
        int index = 0;
        while (index < limit) {
            if (supplied.charAt(index) == '\r') {
                // The pair test matches LineBreaks: a CR is followed by its LF in the supplied text,
                // wherever the offset happens to sit.
                boolean pair = index + 1 < supplied.length()
                        && supplied.charAt(index + 1) == '\n';
                if (pair) {
                    if (index + 1 >= limit) {
                        break;
                    }
                    index++;
                }
            }
            index++;
            units++;
        }
        return units;
    }

    private static int clampToBoundary(String text, int index) {
        int limit = Math.max(0, Math.min(index, text.length()));
        return TextMeasure.boundaryBefore(text, limit);
    }

    public static EditorState empty() {
        return new EditorState("", 0, 0, 0);
    }

    public static EditorState of(String text) {
        String value = text == null ? "" : text;
        // The offsets are the caller's own coordinates in the text it supplied; the constructor
        // carries them onto the end of the prepared draft.
        return new EditorState(value, value.length(), value.length(), 0);
    }

    public boolean hasSelection() {
        return anchor != caret;
    }

    public int selectionStart() {
        return Math.min(anchor, caret);
    }

    public int selectionEnd() {
        return Math.max(anchor, caret);
    }

    public String selection() {
        return hasSelection() ? text.substring(selectionStart(), selectionEnd()) : "";
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** Zero-based logical line containing {@code index}. */
    public int lineAt(int index) {
        int limit = Math.max(0, Math.min(index, text.length()));
        int line = 0;
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    public int lineCount() {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public int lineStart(int line) {
        if (line <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++seen == line) {
                return i + 1;
            }
        }
        return text.length();
    }

    public int lineEnd(int line) {
        int start = lineStart(line);
        int next = text.indexOf('\n', start);
        return next < 0 ? text.length() : next;
    }

    public EditorState withText(String value) {
        // The canonical constructor and of() treat null as an empty draft; a wither must not be
        // the one public entry point that throws instead. Replacing the draft prepares its line
        // endings the same way, because a restored draft arrives with its own platform's breaks.
        String text = value == null ? "" : value;
        return new EditorState(text, text.length(), text.length(), 0);
    }

    public EditorState withCaret(int index) {
        return new EditorState(text, index, index, preferredColumn);
    }

    public EditorState withSelection(int from, int to) {
        return new EditorState(text, to, from, preferredColumn);
    }

    public EditorState withPreferredColumn(int column) {
        return new EditorState(text, caret, anchor, column);
    }

    /** The text to display, honoring masking, or empty when hidden. */
    public String displayText(EditorOptions options) {
        return switch (options.masking()) {
            case NONE -> text;
            case PASSWORD -> masked();
            case HIDDEN -> "";
        };
    }

    /**
     * One mask glyph per cluster, line breaks kept, exactly as {@link Editor#render} draws them.
     *
     * <p>The only break a draft can carry is the LF the canonical constructor prepared, and it
     * segments as a cluster of its own: the break masks as the break, never as a glyph, so masking
     * cannot collapse the two lines it separates into one.</p>
     */
    private String masked() {
        StringBuilder masked = new StringBuilder(text.length());
        for (String cluster : TextMeasure.graphemes(text)) {
            if ("\n".equals(cluster)) {
                masked.append('\n');
            } else {
                masked.append("\u2022");
            }
        }
        return masked.toString();
    }

    public Optional<String> caretLine() {
        int line = lineAt(caret);
        return Optional.of(text.substring(lineStart(line), lineEnd(line)));
    }
}
