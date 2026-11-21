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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.Editor.Model;
import io.github.dhoard.braxton.widget.Editor.Msg;
import io.github.dhoard.braxton.widget.Editor.Output;
import io.github.dhoard.braxton.widget.Editor.Result;
import io.github.dhoard.braxton.widget.EditorRendering.Frame;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Pure text editing and selection transitions.
 */
final class EditorEditing {

    /**
     * Applies one editing action. Never performs I/O and never mutates the input state.
     */
    static Result reduce(Model state, EditorConfig options, Msg action) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return switch (action) {
            case Msg.Clear clear -> Result.unchanged(Model.empty());
            case Msg.Insert insert -> Result.unchanged(insert(state, opts, insert.text()));
            case Msg.Paste paste -> Result.unchanged(insert(state, opts, sanitize(paste.text(), opts)));
            case Msg.Backspace backspace -> Result.unchanged(backspace(state));
            case Msg.Delete delete -> Result.unchanged(delete(state));
            case Msg.MoveLeft left -> Result.unchanged(moveHorizontal(state, -1));
            case Msg.MoveRight right -> Result.unchanged(moveHorizontal(state, 1));
            case Msg.MoveUp up -> Result.unchanged(moveVertical(state, opts, -1));
            case Msg.MoveDown down -> Result.unchanged(moveVertical(state, opts, 1));
            case Msg.MoveHome home -> Result.unchanged(moveLineEdge(state, true));
            case Msg.MoveEnd end -> Result.unchanged(moveLineEdge(state, false));
            case Msg.MoveWordLeft left -> Result.unchanged(moveWord(state, -1));
            case Msg.MoveWordRight right -> Result.unchanged(moveWord(state, 1));
            case Msg.MoveDocumentStart start -> Result.unchanged(new Model(state.text(), 0, 0, 0, state.focused()));
            case Msg.MoveDocumentEnd end ->
                Result.unchanged(new Model(
                        state.text(), state.text().length(), state.text().length(), 0, state.focused()));
            case Msg.DeleteWordBackward backward -> Result.unchanged(deleteWord(state, -1));
            case Msg.DeleteWordForward forward -> Result.unchanged(deleteWord(state, 1));
            case Msg.Extend extend -> Result.unchanged(extendSelection(state, opts, extend.move()));
            case Msg.SelectAll all -> Result.unchanged(selectAll(state));
            case Msg.PlaceCaret place -> Result.unchanged(placed(state, place.offset()));
            case Msg.ExtendSelection extend -> Result.unchanged(extendedTo(state, extend.offset()));
            case Msg.SelectWord word -> Result.unchanged(selectWord(state, word.offset()));
            case Msg.Newline newline -> Result.unchanged(insert(state, opts, "\n"));
            case Msg.Submit submit -> new Result(state, new Output.Submit(state.text()));
            case Msg.Copy copy -> new Result(state, new Output.Copy(opts.canCopy() ? copyText(state) : ""));
            case Msg.PasteRequest request -> new Result(state, new Output.PasteRequest());
        };
    }

    /**
     * The selection, or the whole draft when nothing is selected.
     *
     * <p>This is the raw text: it does not consider masking, so a password or hidden draft is
     * returned verbatim. Callers that must honor {@code canCopy} use {@link Editor#copyableText}
     * instead, which is what the {@code Copy} action uses.</p>
     *
     * @param state the editor state to copy from
     * @return the selection, or the whole draft
     */
    public static String copyText(Model state) {
        return state.hasSelection() ? state.selection() : state.text();
    }

    static String sanitize(String text, EditorConfig options) {
        return PasteSanitizer.sanitize(text, options.pasteMode(), options.collapsePasteBlankLines());
    }

    /**
     * Inserts text at the caret or over the selection. Line endings are normalized to the editor's
     * line break (LF), matching the paste sanitizer, the decoder, and the draft {@link Model}
     * is given, so a raw carriage return never sits in the draft as an invisible offset that does
     * not break a line.
     */
    static Model insert(Model state, EditorConfig options, String raw) {
        if (raw.isEmpty()) {
            return state;
        }
        int start = state.hasSelection() ? state.selectionStart() : state.caret();
        int end = state.hasSelection() ? state.selectionEnd() : state.caret();
        int lengthAfterRemoval = state.text().length() - (end - start);
        String insert = LineBreaks.normalize(raw);
        OptionalInt limit = options.maxLength();
        if (limit.isPresent()) {
            int room = Math.max(0, limit.getAsInt() - lengthAfterRemoval);
            insert = truncateUtf16(insert, room);
        }
        if (insert.isEmpty()) {
            return state;
        }
        String text = state.text().substring(0, start) + insert + state.text().substring(end);
        return edited(text, start + insert.length());
    }

    /**
     * The state after an edit leaves the caret at {@code caret} in {@code text}.
     *
     * <p>An edit can join the surviving text on either side of the removed range into one grapheme,
     * so the deletion point may sit inside a cluster. Keep the caret at the end of that cluster -
     * the nearest representable position - rather than letting {@link Model} round the
     * interior offset back before the base the deletion did not remove.</p>
     */
    static Model edited(String text, int caret) {
        if (!TextMeasure.isBoundary(text, caret)) {
            caret = TextMeasure.boundaryAfter(text, caret);
        }
        return new Model(text, caret, caret, 0);
    }

    /**
     * Truncates to at most {@code maxUnits} UTF-16 units without splitting a grapheme.
     */
    static String truncateUtf16(String text, int maxUnits) {
        if (maxUnits <= 0) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (String cluster : TextMeasure.graphemes(text)) {
            if (used + cluster.length() > maxUnits) {
                break;
            }
            kept.append(cluster);
            used += cluster.length();
        }
        return kept.toString();
    }

    static Model backspace(Model state) {
        if (state.hasSelection()) {
            return deleteSelection(state);
        }
        if (state.caret() == 0) {
            return state;
        }
        int previous = TextMeasure.previousBoundary(state.text(), state.caret());
        String text = state.text().substring(0, previous) + state.text().substring(state.caret());
        return edited(text, previous);
    }

    static Model delete(Model state) {
        if (state.hasSelection()) {
            return deleteSelection(state);
        }
        if (state.caret() >= state.text().length()) {
            return state;
        }
        int next = TextMeasure.boundaryAfter(state.text(), state.caret());
        String text = state.text().substring(0, state.caret()) + state.text().substring(next);
        return edited(text, state.caret());
    }

    static Model deleteSelection(Model state) {
        int start = state.selectionStart();
        int end = state.selectionEnd();
        String text = state.text().substring(0, start) + state.text().substring(end);
        return edited(text, start);
    }

    static Model moveHorizontal(Model state, int direction) {
        int caret = direction < 0
                ? TextMeasure.previousBoundary(state.text(), state.caret())
                : TextMeasure.boundaryAfter(state.text(), state.caret());
        return new Model(state.text(), caret, caret, 0);
    }

    /**
     * Moves the caret one painted row up or down, keeping the painted column it was on.
     *
     * <p>A row is what the draft is wrapped into, so a vertical move crosses a soft break inside a
     * line before it leaves the line at all: wrapped text, a tab reaching its stop and a two-column
     * glyph all move the caret to the cell the row it lands on actually draws, and the preferred
     * column is that cell, which is what makes a run of moves through lines of different lengths
     * come back to the column it started in.</p>
     *
     * <p>Without a declared width there is one row per logical line, which is the behavior every
     * earlier release had.</p>
     */
    static Model moveVertical(Model state, EditorConfig options, int direction) {
        Frame frame = EditorRendering.frame(state, options, 0);
        VisualRows.Cells cells = EditorRendering.movementCells(options);
        Map<Integer, List<VisualRows.Row>> cache = new HashMap<>();
        String text = state.text();
        int line = state.lineAt(state.caret());
        List<VisualRows.Row> rows = EditorRendering.plainRows(cache, state, frame, cells, line);
        int row = EditorRendering.rowOf(rows, state.caret());
        // A vertical move keeps the column the caret was at on the row it came from, even when a
        // shorter row in between clamps it; horizontal moves and edits reset the preference.
        int firstLine = EditorRendering.firstVisibleLine(state, options, frame, line);
        int column = state.preferredColumn() > 0
                ? state.preferredColumn()
                : EditorRendering.rowColumn(
                        text,
                        rows.get(row).start(),
                        state.caret(),
                        frame.prefix(),
                        cells,
                        EditorRendering.openAt(line, firstLine, frame));
        int targetLine = line;
        int targetRow = row;
        if (direction < 0) {
            if (row > 0) {
                targetRow = row - 1;
            } else if (line > 0) {
                targetLine = line - 1;
                targetRow = EditorRendering.plainRows(cache, state, frame, cells, targetLine)
                                .size()
                        - 1;
            } else {
                targetRow = 0;
            }
        } else if (row + 1 < rows.size()) {
            targetRow = row + 1;
        } else if (line < state.lineCount() - 1) {
            targetLine = line + 1;
            targetRow = 0;
        } else {
            targetRow = rows.size() - 1;
        }
        List<VisualRows.Row> targetRows = EditorRendering.plainRows(cache, state, frame, cells, targetLine);
        VisualRows.Row target = targetRows.get(Math.max(0, Math.min(targetRow, targetRows.size() - 1)));
        int caret = EditorRendering.indexAtColumn(
                text,
                target.start(),
                target.end(),
                column,
                frame.prefix(),
                cells,
                EditorRendering.openAt(targetLine, firstLine, frame));
        return new Model(text, caret, caret, column);
    }

    static Model moveLineEdge(Model state, boolean toStart) {
        int line = state.lineAt(state.caret());
        int caret = toStart ? state.lineStart(line) : state.lineEnd(line);
        return new Model(state.text(), caret, caret, 0);
    }

    static Model selectAll(Model state) {
        if (state.text().isEmpty()) {
            return state;
        }
        return new Model(state.text(), state.text().length(), 0, 0);
    }

    /**
     * The state with the caret at an offset and no selection.
     */
    static Model placed(Model state, int offset) {
        int caret = clampedOffset(state.text(), offset);
        return new Model(state.text(), caret, caret, 0, state.focused());
    }

    /**
     * The state with the caret at an offset and the anchor where it was.
     */
    static Model extendedTo(Model state, int offset) {
        int caret = clampedOffset(state.text(), offset);
        return new Model(state.text(), caret, state.anchor(), state.preferredColumn(), state.focused());
    }

    /**
     * The state with the word around an offset selected.
     *
     * <p>The run is the one a word gesture would step over. A click past the end of the draft names
     * the word before it, which is what a click on the cell after the last cluster means.</p>
     */
    static Model selectWord(Model state, int offset) {
        String text = state.text();
        int at = clampedOffset(text, offset);
        int probe = at == text.length() && at > 0 ? TextMeasure.previousBoundary(text, at) : at;
        int from = wordBoundary(text, probe, -1);
        int to = wordBoundary(text, from, 1);
        return new Model(text, to, from, 0, state.focused());
    }

    /**
     * An offset clamped into the draft and moved back to a cluster boundary.
     */
    static int clampedOffset(String text, int offset) {
        int limit = Math.max(0, Math.min(offset, text.length()));
        if (limit == 0 || limit == text.length() || TextMeasure.isBoundary(text, limit)) {
            return limit;
        }
        return TextMeasure.previousBoundary(text, limit);
    }

    /**
     * The class a cluster belongs to for word motion: one run of letters, digits and underscores, one
     * run of blanks, or one run of everything else.
     *
     * <p>A word is the run an editor selects when the caret is asked to move or delete by word, so a
     * decimal number, an identifier and a run of punctuation each move as one unit. A break belongs
     * to the blank class, so a word move crosses lines the way it crosses spaces.</p>
     */
    enum ClusterClass {
        WORD,
        BLANK,
        PUNCTUATION;

        static ClusterClass of(String cluster) {
            int codePoint = cluster.isEmpty() ? -1 : cluster.codePointAt(0);
            if (codePoint < 0 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                return BLANK;
            }
            return Character.isLetterOrDigit(codePoint) || codePoint == '_' ? WORD : PUNCTUATION;
        }
    }

    /**
     * The offset a word move seeks from {@code index}.
     *
     * <p>Leftward it steps back over the blanks behind the caret and then to the start of the run it
     * found. Rightward it steps forward over the blanks and then to the end of the run it found, so
     * a caret inside a word reaches that word's end rather than its start. Every step is a cluster of
     * the draft, so a word can neither be entered nor left in the middle of a grapheme.</p>
     */
    static int wordBoundary(String text, int index, int direction) {
        int limit = Math.max(0, Math.min(index, text.length()));
        if (direction < 0) {
            int position = limit;
            while (position > 0) {
                int previous = TextMeasure.previousBoundary(text, position);
                if (ClusterClass.of(text.substring(previous, position)) != ClusterClass.BLANK) {
                    break;
                }
                position = previous;
            }
            ClusterClass run = null;
            while (position > 0) {
                int previous = TextMeasure.previousBoundary(text, position);
                ClusterClass found = ClusterClass.of(text.substring(previous, position));
                if (found == ClusterClass.BLANK || (run != null && found != run)) {
                    break;
                }
                run = found;
                position = previous;
            }
            return position;
        }
        int position = limit;
        while (position < text.length()) {
            int next = TextMeasure.boundaryAfter(text, position);
            if (ClusterClass.of(text.substring(position, next)) != ClusterClass.BLANK) {
                break;
            }
            position = next;
        }
        ClusterClass run = null;
        while (position < text.length()) {
            int next = TextMeasure.boundaryAfter(text, position);
            ClusterClass found = ClusterClass.of(text.substring(position, next));
            if (found == ClusterClass.BLANK || (run != null && found != run)) {
                break;
            }
            run = found;
            position = next;
        }
        return position;
    }

    static Model moveWord(Model state, int direction) {
        int caret = wordBoundary(state.text(), state.caret(), direction);
        return new Model(state.text(), caret, caret, 0, state.focused());
    }

    static Model deleteWord(Model state, int direction) {
        if (state.hasSelection()) {
            return deleteSelection(state);
        }
        int to = wordBoundary(state.text(), state.caret(), direction);
        int from = Math.min(state.caret(), to);
        int end = Math.max(state.caret(), to);
        if (from == end) {
            return state;
        }
        String text = state.text().substring(0, from) + state.text().substring(end);
        return edited(text, from);
    }

    /**
     * Applies one navigation action while the anchor stays where it is, so the selection grows or
     * shrinks by exactly the movement the same action performs without one.
     */
    static Model extendSelection(Model state, EditorConfig opts, Msg move) {
        // The movement is computed from a collapsed caret: a vertical move from a selection would
        // otherwise start at the caret of a range whose preferred column came from its other end.
        Model collapsed =
                new Model(state.text(), state.caret(), state.caret(), state.preferredColumn(), state.focused());
        Model moved = reduce(collapsed, opts, move).state();
        return new Model(moved.text(), moved.caret(), state.anchor(), moved.preferredColumn(), moved.focused());
    }
}
