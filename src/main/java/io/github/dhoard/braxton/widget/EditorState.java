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

import io.github.dhoard.braxton.style.TextMeasure;

/**
 * Immutable editing state: content, caret, selection anchor, and preferred visual column.
 *
 * <p>Positions are UTF-16 offsets that always sit on a grapheme boundary. Selection is represented by
 * an anchor plus the caret; the anchor follows the caret when a move clears the selection.</p>
 */
public record EditorState(String text, int caret, int anchor, int preferredColumn) {

    public EditorState {
        text = text == null ? "" : text;
        caret = clampToBoundary(text, caret);
        anchor = clampToBoundary(text, anchor);
        preferredColumn = Math.max(0, preferredColumn);
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
        // the one public entry point that throws instead.
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

    /** One mask glyph per cluster, line breaks kept, exactly as {@link Editor#render} draws them. */
    private String masked() {
        StringBuilder masked = new StringBuilder(text.length());
        for (String cluster : TextMeasure.graphemes(text)) {
            masked.append("\n".equals(cluster) ? "\n" : "\u2022");
        }
        return masked.toString();
    }

    public Optional<String> caretLine() {
        int line = lineAt(caret);
        return Optional.of(text.substring(lineStart(line), lineEnd(line)));
    }
}
