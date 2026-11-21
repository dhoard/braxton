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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.text.TextBuffer;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.widget.EditorRendering.Painted;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A reusable text editor: a pure reducer plus a declarative view.
 *
 * <p>Submitting, copying, pasting, and focus changes are <em>intents</em>, not side effects. The
 * owning application decides whether a submit clears the draft, deactivates focus, runs validation,
 * or turns a paste request into a clipboard command.</p>
 *
 * <p>The gestures the editor handles are a {@link KeyMap}. {@link #defaultKeys(EditorConfig)} defines them
 * from the editing policy - the copy and paste gestures come from {@link EditorConfig#copyKey()} and
 * {@link EditorConfig#pasteKey()} - and the {@link #Editor(EditorConfig, KeyMap)} constructor replaces them.
 * A configured action is matched before the text a gesture carries, so an action remapped onto a
 * printable key fires instead of inserting, and the same map is what {@link Help} renders.</p>
 *
 * <h2>Pointer input</h2>
 *
 * <p>A click places the caret on the cluster under the pointer ({@link Msg.PlaceCaret}), a drag
 * extends the selection from where the press landed ({@link Msg.ExtendSelection}) and a second click
 * selects the word there ({@link Msg.SelectWord}). The cell is mapped through the same rows, wrap,
 * gutter and mask the frame was painted from, so a click lands where the glyph it covered was drawn
 * and never inside a grapheme; a drag that leaves the editor keeps selecting because the press
 * captured it. A click never changes {@code focused}: the message is what the application acts on.</p>
 */
public final class Editor
        implements Component<Editor.Model, Editor.Msg, Editor.Output>, Interactive<Editor.Model, Editor.Msg> {

    /**
     * Reads a state's text through the shared immutable text engine.
     */
    static TextBuffer buffer(Model state) {
        if (state == null) return TextBuffer.empty();
        return TextBuffer.of(
                java.util.Arrays.stream(state.text().split("\n", -1)).toList());
    }

    /**
     * Immutable editor state: the draft, the caret and selection offsets, the preferred column and focus.
     *
     * @param text the draft text; null is empty and line endings are normalized to LF
     * @param caret the caret offset, clamped to a grapheme boundary
     * @param anchor the selection anchor offset, clamped to a grapheme boundary
     * @param preferredColumn the visual column a vertical move returns to, or 0
     * @param focused whether the editor currently owns focus
     */
    public record Model(String text, int caret, int anchor, int preferredColumn, boolean focused) {

        /**
         * State that starts focused.
         *
         * @param text the draft text
         * @param caret the caret offset
         * @param anchor the selection anchor offset
         * @param preferredColumn the preferred visual column
         */
        public Model(String text, int caret, int anchor, int preferredColumn) {
            this(text, caret, anchor, preferredColumn, true);
        }

        /**
         * The same state with a new focus.
         *
         * @param value whether the editor owns focus
         * @return this instance when the focus did not change
         */
        public Model withFocus(boolean value) {
            // Returning the same model when focus is unchanged keeps an editor message that changed
            // nothing a value-equal no-op, so the runtime's unchanged-reference view rule holds.
            return value == focused ? this : new Model(text, caret, anchor, preferredColumn, value);
        }

        /**
         * Normalizes the draft's line endings and clamps both offsets to grapheme boundaries.
         */
        public Model {
            String supplied = text == null ? "" : text;
            // Preparing the line endings where the draft is entered, not only where the reducer
            // inserts, is what keeps the draft one text: the rows paint the draft's own lines, and
            // every offset the state reports counts the characters those rows draw.
            String draft = LineBreaks.normalize(supplied);
            if (draft != supplied) {
                // normalize returns the value it was given when there is nothing to convert, so a
                // new string is the signal that breaks before an offset collapsed to one unit
                // each.
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
                    // The pair test matches LineBreaks: a CR is followed by its LF in the supplied
                    // text, wherever the offset happens to sit.
                    boolean pair = index + 1 < supplied.length() && supplied.charAt(index + 1) == '\n';
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

        /**
         * An empty draft with the caret at the start.
         *
         * @return the empty state
         */
        public static Model empty() {
            return new Model("", 0, 0, 0);
        }

        /**
         * A draft with the caret and the anchor at its end.
         *
         * @param text the draft text; null is empty
         * @return the state holding that draft
         */
        public static Model of(String text) {
            String value = text == null ? "" : text;
            // The offsets are the caller's own coordinates in the text it supplied; the constructor
            // carries them onto the end of the prepared draft.
            return new Model(value, value.length(), value.length(), 0);
        }

        /**
         * Whether the anchor and the caret differ.
         *
         * @return whether the anchor and the caret differ
         */
        public boolean hasSelection() {
            return anchor != caret;
        }

        /**
         * The lower selection offset.
         *
         * @return the lower selection offset
         */
        public int selectionStart() {
            return Math.min(anchor, caret);
        }

        /**
         * The higher selection offset.
         *
         * @return the higher selection offset
         */
        public int selectionEnd() {
            return Math.max(anchor, caret);
        }

        /**
         * The selected text, or an empty string.
         *
         * @return the selected text, or an empty string
         */
        public String selection() {
            return hasSelection() ? text.substring(selectionStart(), selectionEnd()) : "";
        }

        /**
         * Whether the draft is empty.
         *
         * @return whether the draft is empty
         */
        public boolean isEmpty() {
            return text.isEmpty();
        }

        /**
         * Zero-based logical line containing {@code index}.
         *
         * @param index the draft offset
         * @return the zero-based line containing it
         */
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

        /**
         * The number of logical lines.
         *
         * @return the number of logical lines
         */
        public int lineCount() {
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            return lines;
        }

        /**
         * The offset where a logical line starts.
         *
         * @param line the zero-based line
         * @return the first offset of that line
         */
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

        /**
         * The offset where a logical line ends.
         *
         * @param line the zero-based line
         * @return the offset of its line break, or the end of the draft
         */
        public int lineEnd(int line) {
            int start = lineStart(line);
            int next = text.indexOf('\n', start);
            return next < 0 ? text.length() : next;
        }

        /**
         * The same state with a different draft and the caret at its end.
         *
         * @param value the new draft; null is empty
         * @return the new state
         */
        public Model withText(String value) {
            // The canonical constructor and of() treat null as an empty draft; a wither must not be
            // the one public entry point that throws instead. Replacing the draft prepares its line
            // endings the same way, because a restored draft arrives with its own platform's
            // breaks.
            String text = value == null ? "" : value;
            return new Model(text, text.length(), text.length(), 0, focused);
        }

        /**
         * The same draft with the caret moved, clearing the selection.
         *
         * @param index the new caret offset
         * @return the new state
         */
        public Model withCaret(int index) {
            return new Model(text, index, index, preferredColumn, focused);
        }

        /**
         * The same draft with a selection and the caret at its end.
         *
         * @param from the selection anchor offset
         * @param to the caret offset
         * @return the new state
         */
        public Model withSelection(int from, int to) {
            return new Model(text, to, from, preferredColumn, focused);
        }

        /**
         * The same draft with a different preferred column.
         *
         * @param column the visual column a vertical move returns to
         * @return the new state
         */
        public Model withPreferredColumn(int column) {
            return new Model(text, caret, anchor, column, focused);
        }

        /**
         * The text to display, honoring masking, or empty when hidden.
         *
         * @param options the editing and rendering policy
         * @return the text the rows paint
         */
        public String displayText(EditorConfig options) {
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

        /**
         * The logical line the caret sits on.
         *
         * @return that line, without its break
         */
        public Optional<String> caretLine() {
            int line = lineAt(caret);
            return Optional.of(text.substring(lineStart(line), lineEnd(line)));
        }
    }

    /**
     * Editing, navigation and intent messages.
     */
    public sealed interface Msg {
        /**
         * Replaces the draft with an empty one.
         */
        record Clear() implements Msg {}

        /**
         * Inserts printable text at the caret, replacing the selection.
         *
         * @param text the text to insert; null is empty
         */
        record Insert(String text) implements Msg {

            /**
             * An insertion never carries null.
             */
            public Insert {
                text = text == null ? "" : text;
            }
        }

        /**
         * Inserts one complete paste payload, sanitized according to the editor options.
         *
         * @param text the paste payload; null is empty
         */
        record Paste(String text) implements Msg {

            /**
             * A paste payload never carries null.
             */
            public Paste {
                text = text == null ? "" : text;
            }
        }

        /**
         * Deletes the selection, or the cluster before the caret.
         */
        record Backspace() implements Msg {}

        /**
         * Deletes the selection, or the cluster after the caret.
         */
        record Delete() implements Msg {}

        /**
         * Moves the caret one cluster left.
         */
        record MoveLeft() implements Msg {}

        /**
         * Moves the caret one cluster right.
         */
        record MoveRight() implements Msg {}

        /**
         * Moves the caret one visual row up.
         */
        record MoveUp() implements Msg {}

        /**
         * Moves the caret one visual row down.
         */
        record MoveDown() implements Msg {}

        /**
         * Moves the caret to the start of its line.
         */
        record MoveHome() implements Msg {}

        /**
         * Moves the caret to the end of its line.
         */
        record MoveEnd() implements Msg {}

        /**
         * Selects the whole draft.
         */
        record SelectAll() implements Msg {}

        /**
         * Puts the caret at an offset and collapses the selection there.
         *
         * <p>This is what a click means: the caret goes where the pointer was, and any previous
         * selection is dropped. The offset is clamped into the draft and moved to a cluster
         * boundary, so a click can never split a grapheme.</p>
         *
         * @param offset the document offset the caret moves to
         */
        record PlaceCaret(int offset) implements Msg {}

        /**
         * Moves the caret to an offset, keeping the selection anchor where it is.
         *
         * <p>This is what a drag means. It is deliberately not {@link Extend}, which extends by a
         * navigation action: a drag names the offset it reached, so the same message covers motion
         * in either direction and across a wrapped row.</p>
         *
         * @param offset the document offset the caret moves to
         */
        record ExtendSelection(int offset) implements Msg {}

        /**
         * Selects the word around an offset.
         *
         * <p>This is what a second click means. A word is the run the editor's own word motion
         * steps over - letters, digits and underscores, blanks, or punctuation - so a double click
         * selects exactly what a word gesture would.</p>
         *
         * @param offset the document offset the clicked cell falls on
         */
        record SelectWord(int offset) implements Msg {}

        /**
         * Inserts a line break at the caret.
         */
        record Newline() implements Msg {}

        /**
         * Moves the caret to the start of the word before it.
         */
        record MoveWordLeft() implements Msg {}

        /**
         * Moves the caret past the end of the word after it.
         */
        record MoveWordRight() implements Msg {}

        /**
         * Moves the caret to the start of the draft.
         */
        record MoveDocumentStart() implements Msg {}

        /**
         * Moves the caret to the end of the draft.
         */
        record MoveDocumentEnd() implements Msg {}

        /**
         * Deletes the selection, or the run of blanks and the word before the caret.
         */
        record DeleteWordBackward() implements Msg {}

        /**
         * Deletes the selection, or the word after the caret and the blanks behind it.
         */
        record DeleteWordForward() implements Msg {}

        /**
         * Extends the selection by one navigation action, keeping the anchor where it is.
         *
         * <p>Only a navigation action may be extended: an insertion or a deletion would have to
         * decide what happens to the text the selection already covers, and that is the
         * application's choice, not a gesture's.</p>
         *
         * @param move the navigation action to apply from the caret
         */
        record Extend(Msg move) implements Msg {

            /**
             * Rejects a missing action and one that is not a navigation.
             */
            public Extend {
                if (!(move instanceof MoveLeft
                        || move instanceof MoveRight
                        || move instanceof MoveUp
                        || move instanceof MoveDown
                        || move instanceof MoveHome
                        || move instanceof MoveEnd
                        || move instanceof MoveWordLeft
                        || move instanceof MoveWordRight
                        || move instanceof MoveDocumentStart
                        || move instanceof MoveDocumentEnd)) {
                    throw new IllegalArgumentException("only a navigation action extends a selection");
                }
            }
        }

        /**
         * Requests submission; the application decides what submission means.
         */
        record Submit() implements Msg {}

        /**
         * Copies the selection, or the whole draft when nothing is selected.
         *
         * <p>The editor paints nothing and writes nothing: the answer is an {@link Output.Copy} the
         * parent turns into a copy command. A masked draft answers with empty text, so a password never
         * leaves the field even when the parent copies everything it is given.</p>
         */
        record Copy() implements Msg {}

        /**
         * Requests clipboard text; the application answers with {@link Paste}.
         *
         * <p>The request names no source. The parent answers from whichever it trusts - the session's
         * in-process buffer through {@code Command.readClipboard}, or what the terminal holds through
         * {@code Command.readTerminalClipboard} - and asking the terminal is the parent's decision, so a
         * paste request alone writes no bytes.</p>
         */
        record PasteRequest() implements Msg {}
    }

    /**
     * Semantic outputs the parent consumes.
     */
    public sealed interface Output {

        /**
         * The user asked to submit this text.
         *
         * @param text the submitted draft
         */
        record Submit(String text) implements Output {}

        /**
         * The user asked to copy this text (empty when copying is not permitted).
         *
         * <p>Where it goes is the parent's decision: {@code Command.copy} writes the system clipboard,
         * {@code Command.copy(ClipboardSelection, ...)} names the primary selection instead. The copy
         * result the command reports is the only evidence about the transport - OSC 52 has no
         * acknowledgment - so this value carries the text and nothing about what happened to it.</p>
         *
         * @param text the copied text, empty when copying is not permitted
         */
        record Copy(String text) implements Output {}

        /**
         * The user asked to paste; the parent answers with {@link Msg.Paste}.
         *
         * <p>The request does not read anything itself. The parent reads the in-process buffer or asks
         * the terminal and then delivers {@link Msg.Paste}, which is what applies the paste policy to
         * the text.</p>
         */
        record PasteRequest() implements Output {}
    }

    /**
     * How an editor draws its caret.
     */
    public enum Caret {
        /**
         * The block glyph painted into the draft's own text, as every earlier release did.
         */
        PAINTED,
        /**
         * The terminal's cursor, moved and shaped by the runtime from the editor's caret.
         */
        NATIVE,
        /**
         * A reverse-video cell painted by the layout, for a terminal the caret is not asked of.
         */
        VIRTUAL
    }

    /**
     * Immutable caret presentation policy.
     *
     * <p>The caret's position is never part of the policy: it comes from the model, which holds the
     * draft, the caret offset and focus. This decides only how that caret is drawn.</p>
     *
     * <p>{@link Caret#PAINTED} is the default and is what an editor that does not configure a
     * cursor keeps: a block glyph replaces the character under the caret in the painted text, so
     * nothing outside the editor's own content moves. {@link Caret#NATIVE} and {@link
     * Caret#VIRTUAL} declare a cursor instead, which leaves the character under the caret readable
     * and lets shape and blink be chosen.</p>
     *
     * <p>A caret is painted only while the editor is focused, so an unfocused field shows none.</p>
     *
     * @param caret how the caret is drawn; never null
     * @param shape the shape a declared cursor takes; never null
     * @param mode whether a declared cursor blinks; never null
     */
    public record CursorPolicy(Caret caret, Cursor.Shape shape, Cursor.Mode mode) {

        /**
         * Rejects no value; a missing caret is the painted block and a missing shape is a block.
         */
        public CursorPolicy {
            caret = caret == null ? Caret.PAINTED : caret;
            shape = shape == null ? Cursor.Shape.BLOCK : shape;
            mode = mode == null ? Cursor.Mode.BLINK : mode;
        }

        /**
         * The legacy painted block, which is what an unconfigured editor draws.
         *
         * @return the policy
         */
        public static CursorPolicy painted() {
            return new CursorPolicy(Caret.PAINTED, Cursor.Shape.BLOCK, Cursor.Mode.BLINK);
        }

        /**
         * The terminal's own cursor.
         *
         * @param shape the caret's shape
         * @param mode whether the terminal blinks it
         * @return the policy
         */
        public static CursorPolicy nativeCursor(Cursor.Shape shape, Cursor.Mode mode) {
            return new CursorPolicy(Caret.NATIVE, shape, mode);
        }

        /**
         * A cell the layout marks, for a terminal that is not asked for a cursor.
         *
         * <p>Nothing blinks a painted cell, so a {@link Cursor.Mode#BLINK} mode here is presented
         * steadily unless the application alternates it with model time.</p>
         *
         * @param shape the caret's shape
         * @param mode whether the caret should blink
         * @return the policy
         */
        public static CursorPolicy virtualCursor(Cursor.Shape shape, Cursor.Mode mode) {
            return new CursorPolicy(Caret.VIRTUAL, shape, mode);
        }

        /**
         * The strategy a declared cursor uses; only meaningful when the caret is not painted.
         *
         * <p>This is the one place the mapping from {@link Caret} to {@link Cursor.Strategy} is
         * written down, so every widget that declares a caret from this policy asks the layout for
         * the same thing.</p>
         *
         * @return the cursor strategy the caret asks for
         */
        public Cursor.Strategy strategy() {
            return caret == Caret.VIRTUAL ? Cursor.Strategy.VIRTUAL : Cursor.Strategy.NATIVE;
        }
    }

    /**
     * Immutable frame policy: what the editor paints around its draft, and how tall it grows.
     *
     * <p>Nothing here is part of the draft. A placeholder is painted where an empty draft would be,
     * a line number is the logical line's own number, and a cursor line is a style over the row the
     * caret is on - none of them is inserted, submitted or copied, and none of them occupies a
     * document offset.</p>
     *
     * <p>{@link #dynamicHeight()} makes the painted height follow the wrapped content instead of
     * {@link EditorConfig#maxVisibleLines()}: the height is the content's rows clamped between
     * {@link #minLines()} and {@link #maxLines()}, and content taller than
     * {@link #maxContentLines()} rows scrolls instead of growing. Counting stops at
     * {@code maxContentLines}, so a large draft is never measured whole.</p>
     *
     * @param placeholder the text painted in place of an empty draft; never null
     * @param placeholderStyle the style applied to the placeholder; never null
     * @param lineNumbers whether each logical line's number is painted in a gutter
     * @param lineNumberStyle the style applied to the gutter; never null
     * @param cursorLineStyle the style applied to the row the caret is on while the editor is
     *     focused, or {@link Style#NONE} for none; never null
     * @param dynamicHeight whether the painted height follows the content
     * @param minLines the smallest painted height in rows when the height is dynamic; at least one
     * @param maxLines the largest painted height in rows when the height is dynamic
     * @param maxContentLines the largest number of content rows a dynamic height counts, so a
     *     longer draft scrolls; at least one
     */
    public record Presentation(
            String placeholder,
            Style placeholderStyle,
            boolean lineNumbers,
            Style lineNumberStyle,
            Style cursorLineStyle,
            boolean dynamicHeight,
            int minLines,
            int maxLines,
            int maxContentLines) {

        /**
         * Rejects a height range that cannot be painted.
         */
        public Presentation {
            placeholder = placeholder == null ? "" : LineBreaks.singleRow(placeholder);
            placeholderStyle = placeholderStyle == null ? Style.NONE : placeholderStyle;
            lineNumberStyle = lineNumberStyle == null ? Style.NONE : lineNumberStyle;
            cursorLineStyle = cursorLineStyle == null ? Style.NONE : cursorLineStyle;
            if (minLines < 1) {
                throw new IllegalArgumentException("minLines must be positive");
            }
            if (maxLines < minLines) {
                throw new IllegalArgumentException("maxLines must not be below minLines: " + maxLines);
            }
            if (maxContentLines < 1) {
                throw new IllegalArgumentException("maxContentLines must be positive");
            }
        }

        /**
         * No frame at all: no placeholder, no gutter, no cursor line, a height from
         * {@link EditorConfig#maxVisibleLines()}.
         *
         * @return the plain frame
         */
        public static Presentation plain() {
            return new Presentation(
                    "", Style.NONE, false, Style.NONE, Style.NONE, false, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        /**
         * The same frame with a placeholder.
         *
         * @param value the text painted in place of an empty draft; null paints none
         * @param style the style applied to it; null keeps the draft's own
         * @return the new policy
         */
        public Presentation withPlaceholder(String value, Style style) {
            return new Presentation(
                    value,
                    style,
                    lineNumbers,
                    lineNumberStyle,
                    cursorLineStyle,
                    dynamicHeight,
                    minLines,
                    maxLines,
                    maxContentLines);
        }

        /**
         * The same frame with or without a line-number gutter.
         *
         * @param value whether each logical line's number is painted
         * @param style the style applied to the gutter; null paints it unstyled
         * @return the new policy
         */
        public Presentation withLineNumbers(boolean value, Style style) {
            return new Presentation(
                    placeholder,
                    placeholderStyle,
                    value,
                    style,
                    cursorLineStyle,
                    dynamicHeight,
                    minLines,
                    maxLines,
                    maxContentLines);
        }

        /**
         * The same frame with a different style over the row the caret is on.
         *
         * @param value the style; null paints no cursor line
         * @return the new policy
         */
        public Presentation withCursorLine(Style value) {
            return new Presentation(
                    placeholder,
                    placeholderStyle,
                    lineNumbers,
                    lineNumberStyle,
                    value,
                    dynamicHeight,
                    minLines,
                    maxLines,
                    maxContentLines);
        }

        /**
         * The same frame with a height that follows the content.
         *
         * @param value whether the height is dynamic
         * @param min the smallest height in rows; at least one
         * @param max the largest height in rows
         * @return the new policy
         */
        public Presentation withDynamicHeight(boolean value, int min, int max) {
            return new Presentation(
                    placeholder,
                    placeholderStyle,
                    lineNumbers,
                    lineNumberStyle,
                    cursorLineStyle,
                    value,
                    min,
                    max,
                    maxContentLines);
        }

        /**
         * The same frame with a different content-row bound for a dynamic height.
         *
         * @param value the largest number of content rows counted; at least one
         * @return the new policy
         */
        public Presentation withMaxContentLines(int value) {
            return new Presentation(
                    placeholder,
                    placeholderStyle,
                    lineNumbers,
                    lineNumberStyle,
                    cursorLineStyle,
                    dynamicHeight,
                    minLines,
                    maxLines,
                    value);
        }
    }

    record Result(Model state, Output intent) implements WidgetResult<Model, Output> {

        public Result {
            if (state == null) {
                throw new IllegalArgumentException("state is required");
            }
        }

        public static Result unchanged(Model state) {
            return new Result(state, null);
        }
    }

    /**
     * The block glyph painted at the caret.
     */
    public static final String CURSOR = "\u2588";

    static final String MASK = "\u2022";

    private final EditorConfig config;
    private final KeyMap<Msg> keys;

    /**
     * Creates the component with the default keys for its policy.
     *
     * @param config the immutable editing and rendering policy; required
     */
    public Editor(EditorConfig config) {
        this(config, defaultKeys(config));
    }

    /**
     * Creates the component with its own keys.
     *
     * @param config the immutable editing and rendering policy; required
     * @param keys the actions this editor handles, matched while it is focused; required
     */
    public Editor(EditorConfig config, KeyMap<Msg> keys) {
        this.config = Objects.requireNonNull(config, "config");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * The component of a textarea: Enter inserts a line break and submitting has its own gesture.
     *
     * <p>This is the explicit preset for a field that accepts several lines. An editor built with
     * {@link #Editor(EditorConfig)} keeps the submit-on-Enter behavior every earlier release had, so an
     * existing caller is not turned into a textarea by the phase that added one.</p>
     *
     * @param config the immutable editing and rendering policy; required
     * @return an editor whose Enter breaks a line
     */
    public static Editor textarea(EditorConfig config) {
        return new Editor(config, textareaKeys(config));
    }

    /**
     * The default actions for one editing policy, where Enter submits.
     *
     * <p>The copy and paste actions carry {@link EditorConfig#copyKey()} and {@link EditorConfig#pasteKey()} as
     * their only aliases, so the policy and the map cannot disagree about what those gestures are.
     * Enter submits here; {@link #textareaKeys(EditorConfig)} is the same map with Enter inserting a line
     * break instead.</p>
     *
     * @param config the editing policy; null is {@link EditorConfig#defaults()}
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys(EditorConfig config) {
        return EditorKeys.keys(config, false);
    }

    /**
     * The actions of a textarea: Enter inserts a line break and submitting has its own gesture.
     *
     * <p>Every other action is the same, so a field that accepts line breaks still submits - it just
     * does not do so on the key that also breaks a line. Submission stays reachable as
     * {@code ctrl+enter} and {@code alt+enter}.</p>
     *
     * @param config the editing policy; null is {@link EditorConfig#defaults()}
     * @return the textarea actions
     */
    public static KeyMap<Msg> textareaKeys(EditorConfig config) {
        return EditorKeys.keys(config, true);
    }

    /**
     * The actions this editor handles.
     *
     * @return the key map
     */
    public KeyMap<Msg> keys() {
        return keys;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        return Update.next(Model.empty());
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model,
                message,
                action -> EditorEditing.reduce(model, config, action),
                state -> state.withFocus(model.focused()));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        return scene(model);
    }

    /**
     * The painted block, with the editor itself as the leaf that answers for it.
     */
    private Element<Msg> scene(Model model) {
        EditorConfig opts = config == null ? EditorConfig.defaults() : config;
        return Elements.stack(
                new Element.Leaf<Void, Msg>(new EditorRendering.Block(model, opts), null, Sizing.measured()),
                new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The editor's keys: the configured actions first, then the text a printable key carries.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        EditorConfig opts = config == null ? EditorConfig.defaults() : config;
        EditorActions.actionFor(key, opts, keys).ifPresent(emit);
    }

    /**
     * A paste is text to insert, and a configuration may bind the gesture that carries it.
     */
    @Override
    public void onInput(InputEvent event, Model model, Consumer<Msg> emit) {
        EditorConfig opts = config == null ? EditorConfig.defaults() : config;
        EditorActions.actionFor(event, opts, keys).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The caret the editor places, in screen cells.
     *
     * <p>The row and column come from the same projection the block was painted from, so a declared
     * caret lands on the cell the painted block would have covered - across a soft wrap, after a
     * tab, on a two-column glyph, and in a masked or hidden field. An editor whose cursor policy
     * paints the block reports none: the block is part of the cells, and a terminal cursor on top
     * of it would be a second caret.</p>
     */
    @Override
    public io.github.dhoard.braxton.ui.Cursor cursor(Rect bounds, Model model) {
        EditorConfig opts = config == null ? EditorConfig.defaults() : config;
        if (!model.focused() || opts.cursor().caret() == Caret.PAINTED) {
            return null;
        }
        Painted frame = EditorRendering.painted(model, opts, true, bounds.cols(), bounds.rows());
        Cursor placed = EditorRendering.caret(model, opts, frame);
        // The projection is the painted block's own, so its row and column are relative to the
        // rectangle the block occupies; the caret a widget reports is a screen cell.
        return placed.movedTo(bounds.row() + placed.row(), bounds.col() + placed.column());
    }

    /**
     * A press places the caret, a second press selects the word and a drag extends the selection.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        EditorConfig opts = config == null ? EditorConfig.defaults() : config;
        EditorRendering.pointerAction(
                        model,
                        opts,
                        model.focused(),
                        new Size(bounds.rows(), bounds.cols()),
                        pointer.localTo(bounds.row(), bounds.col()))
                .ifPresent(emit);
    }

    /**
     * Renders at the configured width, or one row per logical line without a declared width.
     *
     * <p>The sanitized prompt appears on the first visible row; later rows are indented by its
     * cell width. Rendering honors masking and caret policy without changing the model.</p>
     *
     * @param state the editor state to paint
     * @param options the editing and rendering policy
     * @param focused whether the caret is painted
     * @return the painted rows
     */
    public static StyledText render(Model state, EditorConfig options, boolean focused) {
        return EditorRendering.render(state, options, focused);
    }

    /**
     * Pure text rendering for an explicit width.
     *
     * @param state the editor state to paint
     * @param options the editing and rendering policy
     * @param focused whether the caret is painted
     * @param cols the columns the view was given, or zero to use the policy's declared width alone
     * @return the painted rows
     */
    public static StyledText render(Model state, EditorConfig options, boolean focused, int cols) {
        return EditorRendering.render(state, options, focused, cols);
    }

    // ── input mapping ────────────────────────────────────────────────────────

    /**
     * Maps one normalized input event to an editor action, or empty when unhandled.
     *
     * <p>A configured action wins over the text its gesture carries, so a copy or paste binding, or
     * any action a caller remapped onto a printable key, stays reachable.</p>
     *
     * @param event the normalized input event
     * @param options the editing and rendering policy
     * @return the editing message, or empty when the event is not an editor gesture
     */
    public static Optional<Msg> actionFor(InputEvent event, EditorConfig options) {
        return EditorActions.actionFor(event, options);
    }

    /**
     * All logical lines of a draft, used by projections that need the line count.
     *
     * @param state the editor state
     * @return its logical lines
     */
    public static List<String> lines(Model state) {
        return EditorActions.lines(state);
    }

    /**
     * Visible line count for layout projections.
     *
     * @param state the editor state
     * @param options the editing and rendering policy
     * @return the number of rows the editor paints
     */
    public static int visibleLineCount(Model state, EditorConfig options) {
        return EditorActions.visibleLineCount(state, options);
    }

    /**
     * Convenience: number of visible lines under the defaults.
     *
     * @param state the editor state
     * @return the number of rows the editor paints
     */
    public static int visibleLineCount(Model state) {
        return EditorActions.visibleLineCount(state);
    }

    /**
     * The number of rows the editor paints when it is given {@code cols} columns.
     *
     * <p>This is the wrapped row count after the gutter and the prompt have taken their columns, and
     * after the height policy has clamped it: what a projection that has to reserve space for the
     * editor needs, without painting it.</p>
     *
     * @param state the editor state
     * @param options the editing and rendering policy
     * @param cols the columns the view is given, or zero to use the policy's declared width alone
     * @return the number of rows the editor paints
     */
    public static int visibleLineCount(Model state, EditorConfig options, int cols) {
        return EditorActions.visibleLineCount(state, options, cols);
    }

    /**
     * Convenience: the selection or the whole draft, honoring masking through {@code canCopy}.
     *
     * @param state the editor state to copy from
     * @param options the editing and rendering policy
     * @return the text copying would produce, or empty when copying is refused
     */
    public static Optional<String> copyableText(Model state, EditorConfig options) {
        return EditorActions.copyableText(state, options);
    }
}
