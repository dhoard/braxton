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

import io.github.dhoard.braxton.clipboard.PasteMode;
import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.view.Cursor;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * A reusable text editor: a pure reducer plus a declarative view.
 *
 * <p>Submitting, copying, pasting, and focus changes are <em>intents</em>, not side effects. The
 * owning application decides whether a submit clears the draft, deactivates focus, runs validation,
 * or turns a paste request into a clipboard command.</p>
 *
 * <p>The gestures the editor handles are a {@link KeyMap}. {@link #defaultKeys(Config)} defines them
 * from the editing policy - the copy and paste gestures come from {@link Config#copyKey()} and
 * {@link Config#pasteKey()} - and the {@link #Editor(Config, KeyMap)} constructor replaces them.
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
public final class Editor implements Component<Editor.Model, Editor.Msg, Editor.Output> {
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
        public String displayText(Config options) {
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

    /** Editing, navigation and intent messages. */
    public sealed interface Msg {
        /** Replaces the draft with an empty one. */
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

        /** Deletes the selection, or the cluster before the caret. */
        record Backspace() implements Msg {}

        /** Deletes the selection, or the cluster after the caret. */
        record Delete() implements Msg {}

        /** Moves the caret one cluster left. */
        record MoveLeft() implements Msg {}

        /** Moves the caret one cluster right. */
        record MoveRight() implements Msg {}

        /** Moves the caret one visual row up. */
        record MoveUp() implements Msg {}

        /** Moves the caret one visual row down. */
        record MoveDown() implements Msg {}

        /** Moves the caret to the start of its line. */
        record MoveHome() implements Msg {}

        /** Moves the caret to the end of its line. */
        record MoveEnd() implements Msg {}

        /** Selects the whole draft. */
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

        /** Inserts a line break at the caret. */
        record Newline() implements Msg {}

        /** Moves the caret to the start of the word before it. */
        record MoveWordLeft() implements Msg {}

        /** Moves the caret past the end of the word after it. */
        record MoveWordRight() implements Msg {}

        /** Moves the caret to the start of the draft. */
        record MoveDocumentStart() implements Msg {}

        /** Moves the caret to the end of the draft. */
        record MoveDocumentEnd() implements Msg {}

        /** Deletes the selection, or the run of blanks and the word before the caret. */
        record DeleteWordBackward() implements Msg {}

        /** Deletes the selection, or the word after the caret and the blanks behind it. */
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

        /** Requests submission; the application decides what submission means. */
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

    /** Semantic outputs the parent consumes. */
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
         * acknowledgement - so this value carries the text and nothing about what happened to it.</p>
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

    /** How an editor draws its caret. */
    public enum Caret {
        /** The block glyph painted into the draft's own text, as every earlier release did. */
        PAINTED,
        /** The terminal's cursor, moved and shaped by the runtime from the editor's caret. */
        NATIVE,
        /** A reverse-video cell painted by the layout, for a terminal the caret is not asked of. */
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
     * {@link Config#maxVisibleLines()}: the height is the content's rows clamped between
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
         * {@link Config#maxVisibleLines()}.
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

    /**
     * Immutable editing and rendering policy.
     *
     * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
     * @param pasteMode the sanitizing policy applied to a paste payload
     * @param collapsePasteBlankLines whether a paste collapses runs of blank lines
     * @param masking how the draft is displayed and whether it may be copied
     * @param copyKey the key gesture that copies
     * @param pasteKey the key gesture that requests a paste
     * @param prompt the one-line prompt painted before the first visible row
     * @param promptStyle the style applied to the prompt
     * @param textStyle the style applied to the draft
     * @param selectionStyle the style applied to the selection
     * @param maxVisibleLines the number of draft rows the editor paints at once
     * @param copyEnabled whether a visible draft may be copied
     * @param cursor how the caret is drawn while the editor is focused
     * @param presentation how the draft is framed: placeholder, gutter, cursor line and height
     * @param wrapWidth the columns the editor lays its rows out for, or empty to paint each logical
     *     line as one row and let the layout wrap it
     */
    public record Config(
            OptionalInt maxLength,
            PasteMode pasteMode,
            boolean collapsePasteBlankLines,
            Masking masking,
            Key copyKey,
            Key pasteKey,
            String prompt,
            Style promptStyle,
            Style textStyle,
            Style selectionStyle,
            int maxVisibleLines,
            boolean copyEnabled,
            CursorPolicy cursor,
            Presentation presentation,
            OptionalInt wrapWidth) {

        /**
         * How the content is displayed.
         *
         * <p>A masked draft ({@link Masking#PASSWORD} or {@link Masking#HIDDEN}) never copies: its
         * cleartext does not leave the editor even when {@code copyEnabled} is true. Setting
         * {@code copyEnabled} to false additionally refuses to copy a visible draft, which is how a
         * field opts out of copying entirely.</p>
         */
        public enum Masking {
            /** The draft is displayed as typed. */
            NONE,
            /** Every cluster is displayed as one mask glyph. */
            PASSWORD,
            /** The draft is not displayed at all. */
            HIDDEN
        }

        public static final Key DEFAULT_COPY_KEY = Key.of("c", io.github.dhoard.braxton.input.Modifiers.ALT);
        public static final Key DEFAULT_PASTE_KEY = Key.of("v", io.github.dhoard.braxton.input.Modifiers.CTRL);

        /**
         * Rejects an inconsistent policy and prepares the one-line prompt.
         */
        public Config {
            maxLength = maxLength == null ? OptionalInt.empty() : maxLength;
            maxLength.ifPresent(limit -> {
                if (limit < 0) {
                    throw new IllegalArgumentException("maxLength must not be negative");
                }
            });
            pasteMode = pasteMode == null ? PasteMode.NORMAL : pasteMode;
            masking = masking == null ? Masking.NONE : masking;
            copyKey = copyKey == null ? DEFAULT_COPY_KEY : copyKey;
            pasteKey = pasteKey == null ? DEFAULT_PASTE_KEY : pasteKey;
            prompt = singleLine(prompt);
            promptStyle = promptStyle == null ? Style.NONE : promptStyle;
            textStyle = textStyle == null ? Style.NONE : textStyle;
            selectionStyle = selectionStyle == null ? Style.NONE.withInvert() : selectionStyle;
            if (maxVisibleLines < 1) {
                throw new IllegalArgumentException("maxVisibleLines must be positive");
            }
            cursor = cursor == null ? CursorPolicy.painted() : cursor;
            presentation = presentation == null ? Presentation.plain() : presentation;
            wrapWidth = wrapWidth == null ? OptionalInt.empty() : wrapWidth;
            wrapWidth.ifPresent(width -> {
                if (width < 1) {
                    throw new IllegalArgumentException("wrapWidth must be positive");
                }
            });
        }

        /**
         * A policy that keeps the painted block and paints no frame, for callers written before
         * cursors and the textarea presentation existed.
         *
         * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
         * @param pasteMode the sanitizing policy applied to a paste payload
         * @param collapsePasteBlankLines whether a paste collapses runs of blank lines
         * @param masking how the draft is displayed and whether it may be copied
         * @param copyKey the key gesture that copies
         * @param pasteKey the key gesture that requests a paste
         * @param prompt the one-line prompt painted before the first visible row
         * @param promptStyle the style applied to the prompt
         * @param textStyle the style applied to the draft
         * @param selectionStyle the style applied to the selection
         * @param maxVisibleLines the number of draft rows the editor paints at once
         * @param copyEnabled whether a visible draft may be copied
         * @param cursor how the caret is drawn while the editor is focused
         */
        public Config(
                OptionalInt maxLength,
                PasteMode pasteMode,
                boolean collapsePasteBlankLines,
                Masking masking,
                Key copyKey,
                Key pasteKey,
                String prompt,
                Style promptStyle,
                Style textStyle,
                Style selectionStyle,
                int maxVisibleLines,
                boolean copyEnabled,
                CursorPolicy cursor) {
            this(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    Presentation.plain(),
                    OptionalInt.empty());
        }

        /**
         * A policy whose caret keeps the painted block, for callers written before cursors existed.
         *
         * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
         * @param pasteMode the sanitizing policy applied to a paste payload
         * @param collapsePasteBlankLines whether a paste collapses runs of blank lines
         * @param masking how the draft is displayed and whether it may be copied
         * @param copyKey the key gesture that copies
         * @param pasteKey the key gesture that requests a paste
         * @param prompt the one-line prompt painted before the first visible row
         * @param promptStyle the style applied to the prompt
         * @param textStyle the style applied to the draft
         * @param selectionStyle the style applied to the selection
         * @param maxVisibleLines the number of draft rows the editor paints at once
         * @param copyEnabled whether a visible draft may be copied
         */
        public Config(
                OptionalInt maxLength,
                PasteMode pasteMode,
                boolean collapsePasteBlankLines,
                Masking masking,
                Key copyKey,
                Key pasteKey,
                String prompt,
                Style promptStyle,
                Style textStyle,
                Style selectionStyle,
                int maxVisibleLines,
                boolean copyEnabled) {
            this(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    CursorPolicy.painted(),
                    Presentation.plain(),
                    OptionalInt.empty());
        }

        /** A prompt prefixes one line, so CR/LF, CR and LF each become one space. */
        private static String singleLine(String value) {
            return LineBreaks.singleRow(value);
        }

        /**
         * The default policy: unlimited, unmasked, copyable, six rows.
         *
         * @return the default configuration
         */
        public static Config defaults() {
            return new Config(
                    OptionalInt.empty(),
                    PasteMode.NORMAL,
                    false,
                    Masking.NONE,
                    DEFAULT_COPY_KEY,
                    DEFAULT_PASTE_KEY,
                    "",
                    Style.NONE,
                    Style.NONE,
                    Style.NONE.withInvert(),
                    6,
                    true,
                    CursorPolicy.painted(),
                    Presentation.plain(),
                    OptionalInt.empty());
        }

        /**
         * The same policy with a length limit.
         *
         * @param limit the maximum draft length in UTF-16 units; never negative
         * @return the new configuration
         */
        public Config withMaxLength(int limit) {
            return new Config(
                    OptionalInt.of(limit),
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with no length limit.
         *
         * @return the new configuration
         */
        public Config withUnlimitedLength() {
            return new Config(
                    OptionalInt.empty(),
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with a different paste policy.
         *
         * @param mode the sanitizing policy applied to a paste payload
         * @param collapseBlankLines whether a paste collapses runs of blank lines
         * @return the new configuration
         */
        public Config withPaste(PasteMode mode, boolean collapseBlankLines) {
            return new Config(
                    maxLength,
                    mode,
                    collapseBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with a different masking policy.
         *
         * @param value how the draft is displayed and copied
         * @return the new configuration
         */
        public Config withMasking(Masking value) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    value,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with different copy and paste gestures.
         *
         * @param copy the key gesture that copies
         * @param paste the key gesture that requests a paste
         * @return the new configuration
         */
        public Config withKeys(Key copy, Key paste) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copy,
                    paste,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with a different prompt.
         *
         * @param value the prompt text; line breaks become spaces
         * @param style the style applied to the prompt
         * @return the new configuration
         */
        public Config withPrompt(String value, Style style) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    value,
                    style,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with different styles.
         *
         * @param text the style applied to the draft
         * @param promptText the style applied to the prompt
         * @param selection the style applied to the selection
         * @return the new configuration
         */
        public Config withStyles(Style text, Style promptText, Style selection) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptText,
                    text,
                    selection,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with a different visible height.
         *
         * @param lines the number of draft rows to paint; must be positive
         * @return the new configuration
         */
        public Config withVisibleLines(int lines) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    lines,
                    copyEnabled,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with copying allowed or refused.
         *
         * @param value whether a visible draft may be copied
         * @return the new configuration
         */
        public Config withCopyEnabled(boolean value) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    value,
                    cursor,
                    presentation,
                    wrapWidth);
        }

        /**
         * The same policy with a different caret presentation.
         *
         * @param value how the caret is drawn; null keeps the painted block
         * @return the new configuration
         */
        public Config withCursor(CursorPolicy value) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    value == null ? CursorPolicy.painted() : value,
                    presentation,
                    wrapWidth);
        }

        /**
         * Whether copying is allowed under the masking policy.
         *
         * @return whether copying is permitted
         */
        public boolean canCopy() {
            return copyEnabled && masking != Masking.PASSWORD && masking != Masking.HIDDEN;
        }

        /**
         * The same policy with a different frame: placeholder, gutter, cursor line and height.
         *
         * @param value the frame policy; null paints no frame
         * @return the new configuration
         */
        public Config withPresentation(Presentation value) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    value == null ? Presentation.plain() : value,
                    wrapWidth);
        }

        /**
         * The same policy laid out for a fixed number of columns.
         *
         * <p>This is the width vertical movement, the caret and a dynamic height are computed for.
         * The space a view is actually given still wins when it is narrower, so an application that
         * derives this from its viewport - the way it derives {@link #maxVisibleLines()} - keeps the
         * two in agreement across a resize.</p>
         *
         * @param columns the columns the rows are wrapped at; must be positive
         * @return the new configuration
         */
        public Config withWrapWidth(int columns) {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    OptionalInt.of(columns));
        }

        /**
         * The same policy with no declared width, so each logical line is painted as one row.
         *
         * @return the new configuration
         */
        public Config withUnboundedWidth() {
            return new Config(
                    maxLength,
                    pasteMode,
                    collapsePasteBlankLines,
                    masking,
                    copyKey,
                    pasteKey,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    maxVisibleLines,
                    copyEnabled,
                    cursor,
                    presentation,
                    OptionalInt.empty());
        }
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {

        public Result {
            if (state == null) {
                throw new IllegalArgumentException("state is required");
            }
        }

        public static Result unchanged(Model state) {
            return new Result(state, null);
        }
    }

    /** The block glyph painted at the caret. */
    public static final String CURSOR = "\u2588";

    private static final String MASK = "\u2022";

    private final Config config;
    private final KeyMap<Msg> keys;

    /**
     * Creates the component with the default keys for its policy.
     *
     * @param config the immutable editing and rendering policy; required
     */
    public Editor(Config config) {
        this(config, defaultKeys(config));
    }

    /**
     * Creates the component with its own keys.
     *
     * @param config the immutable editing and rendering policy; required
     * @param keys the actions this editor handles, matched while it is focused; required
     */
    public Editor(Config config, KeyMap<Msg> keys) {
        this.config = Objects.requireNonNull(config, "config");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * The component of a textarea: Enter inserts a line break and submitting has its own gesture.
     *
     * <p>This is the explicit preset for a field that accepts several lines. An editor built with
     * {@link #Editor(Config)} keeps the submit-on-Enter behaviour every earlier release had, so an
     * existing caller is not turned into a textarea by the phase that added one.</p>
     *
     * @param config the immutable editing and rendering policy; required
     * @return an editor whose Enter breaks a line
     */
    public static Editor textarea(Config config) {
        return new Editor(config, textareaKeys(config));
    }

    /**
     * The default actions for one editing policy, where Enter submits.
     *
     * <p>The copy and paste actions carry {@link Config#copyKey()} and {@link Config#pasteKey()} as
     * their only aliases, so the policy and the map cannot disagree about what those gestures are.
     * Enter submits here; {@link #textareaKeys(Config)} is the same map with Enter inserting a line
     * break instead.</p>
     * @param config the editing policy; null is {@link Config#defaults()}
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys(Config config) {
        return keys(config, false);
    }

    /**
     * The actions of a textarea: Enter inserts a line break and submitting has its own gesture.
     *
     * <p>Every other action is the same, so a field that accepts line breaks still submits - it just
     * does not do so on the key that also breaks a line. Submission stays reachable as
     * {@code ctrl+enter} and {@code alt+enter}.</p>
     *
     * @param config the editing policy; null is {@link Config#defaults()}
     * @return the textarea actions
     */
    public static KeyMap<Msg> textareaKeys(Config config) {
        return keys(config, true);
    }

    private static List<Key> parse(String... specs) {
        List<Key> keys = new ArrayList<>(specs.length);
        for (String spec : specs) {
            keys.add(Key.parse(spec));
        }
        return List.copyOf(keys);
    }

    private static KeyMap<Msg> keys(Config config, boolean enterInsertsNewline) {
        Config opts = config == null ? Config.defaults() : config;
        // Enter is one gesture with two meanings, so exactly one action claims it: the other action
        // keeps a gesture of its own, which is what "exposes submit separately" means.
        List<Key> newline = enterInsertsNewline ? parse("enter", "ctrl+j") : parse("ctrl+j");
        List<Key> submit = enterInsertsNewline ? parse("ctrl+enter", "alt+enter") : parse("enter", "alt+enter");
        String newlineLabel = enterInsertsNewline ? "enter" : "ctrl+j";
        String submitLabel = enterInsertsNewline ? "ctrl+enter" : "enter";
        return KeyMap.of(
                KeyMap.group(
                        "Editing",
                        KeyAction.<Msg>of("newline", new Msg.Newline(), newlineLabel, "new line", newline)
                                .withCompact(false),
                        // The name-only switch this replaced also accepted the Alt-modified forms a
                        // terminal sends for Meta+key, which the decoder normalizes to alt+backspace
                        // and alt+enter.
                        KeyAction.<Msg>of(
                                        "backspace",
                                        new Msg.Backspace(),
                                        "backspace",
                                        "delete back",
                                        "backspace",
                                        "alt+backspace")
                                .withCompact(false),
                        KeyAction.<Msg>of("delete", new Msg.Delete(), "delete", "delete forward", "delete")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "delete-word-back",
                                        new Msg.DeleteWordBackward(),
                                        "",
                                        "delete word back",
                                        "ctrl+backspace",
                                        "alt+w")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "delete-word-forward",
                                        new Msg.DeleteWordForward(),
                                        "",
                                        "delete word",
                                        "ctrl+delete",
                                        "alt+d")
                                .withCompact(false),
                        KeyAction.<Msg>of("select-all", new Msg.SelectAll(), "ctrl+a", "select all", "ctrl+a")
                                .withCompact(false)),
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("left", new Msg.MoveLeft(), "\u2190", "left", "left")
                                .withCompact(false),
                        KeyAction.<Msg>of("right", new Msg.MoveRight(), "\u2192", "right", "right")
                                .withCompact(false),
                        KeyAction.<Msg>of("up", new Msg.MoveUp(), "\u2191", "up", "up")
                                .withCompact(false),
                        KeyAction.<Msg>of("down", new Msg.MoveDown(), "\u2193", "down", "down")
                                .withCompact(false),
                        KeyAction.<Msg>of("word-left", new Msg.MoveWordLeft(), "", "word left", "ctrl+left", "alt+b")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "word-right", new Msg.MoveWordRight(), "", "word right", "ctrl+right", "alt+f")
                                .withCompact(false),
                        KeyAction.<Msg>of("home", new Msg.MoveHome(), "home", "line start", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("end", new Msg.MoveEnd(), "end", "line end", "end")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "document-start",
                                        new Msg.MoveDocumentStart(),
                                        "",
                                        "document start",
                                        "ctrl+home")
                                .withCompact(false),
                        KeyAction.<Msg>of("document-end", new Msg.MoveDocumentEnd(), "", "document end", "ctrl+end")
                                .withCompact(false)),
                KeyMap.group(
                        "Select",
                        KeyAction.<Msg>of(
                                        "select-left",
                                        new Msg.Extend(new Msg.MoveLeft()),
                                        "",
                                        "select left",
                                        "shift+left")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-right",
                                        new Msg.Extend(new Msg.MoveRight()),
                                        "",
                                        "select right",
                                        "shift+right")
                                .withCompact(false),
                        KeyAction.<Msg>of("select-up", new Msg.Extend(new Msg.MoveUp()), "", "select up", "shift+up")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-down",
                                        new Msg.Extend(new Msg.MoveDown()),
                                        "",
                                        "select down",
                                        "shift+down")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-home",
                                        new Msg.Extend(new Msg.MoveHome()),
                                        "",
                                        "select to start",
                                        "shift+home")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-end",
                                        new Msg.Extend(new Msg.MoveEnd()),
                                        "",
                                        "select to end",
                                        "shift+end")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-word-left",
                                        new Msg.Extend(new Msg.MoveWordLeft()),
                                        "",
                                        "select word left",
                                        "ctrl+shift+left")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-word-right",
                                        new Msg.Extend(new Msg.MoveWordRight()),
                                        "",
                                        "select word right",
                                        "ctrl+shift+right")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-document-start",
                                        new Msg.Extend(new Msg.MoveDocumentStart()),
                                        "",
                                        "select to document start",
                                        "ctrl+shift+home")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-document-end",
                                        new Msg.Extend(new Msg.MoveDocumentEnd()),
                                        "",
                                        "select to document end",
                                        "ctrl+shift+end")
                                .withCompact(false)),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("submit", new Msg.Submit(), submitLabel, "submit", submit),
                        KeyAction.<Msg>of("copy", new Msg.Copy(), "", "copy", List.of(opts.copyKey())),
                        KeyAction.<Msg>of("paste", new Msg.PasteRequest(), "", "paste", List.of(opts.pasteKey()))));
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
                model, message, action -> reduce(model, config, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        return paint(ElementId.of("input"), model, config, keys, model.focused(), Function.identity());
    }

    /** Applies one editing action. Never performs I/O and never mutates the input state. */
    private static Result reduce(Model state, Config options, Msg action) {
        Config opts = options == null ? Config.defaults() : options;
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
     * returned verbatim. Callers that must honour {@code canCopy} use {@link #copyableText}
     * instead, which is what the {@code Copy} action uses.</p>
     * @param state the editor state to copy from
     * @return the selection, or the whole draft
     */
    public static String copyText(Model state) {
        return state.hasSelection() ? state.selection() : state.text();
    }

    private static String sanitize(String text, Config options) {
        return PasteSanitizer.sanitize(text, options.pasteMode(), options.collapsePasteBlankLines());
    }

    /**
     * Inserts text at the caret or over the selection. Line endings are normalized to the editor's
     * line break (LF), matching the paste sanitizer, the decoder, and the draft {@link Model}
     * is given, so a raw carriage return never sits in the draft as an invisible offset that does
     * not break a line.
     */
    private static Model insert(Model state, Config options, String raw) {
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
    private static Model edited(String text, int caret) {
        if (!TextMeasure.isBoundary(text, caret)) {
            caret = TextMeasure.boundaryAfter(text, caret);
        }
        return new Model(text, caret, caret, 0);
    }

    /** Truncates to at most {@code maxUnits} UTF-16 units without splitting a grapheme. */
    private static String truncateUtf16(String text, int maxUnits) {
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

    private static Model backspace(Model state) {
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

    private static Model delete(Model state) {
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

    private static Model deleteSelection(Model state) {
        int start = state.selectionStart();
        int end = state.selectionEnd();
        String text = state.text().substring(0, start) + state.text().substring(end);
        return edited(text, start);
    }

    private static Model moveHorizontal(Model state, int direction) {
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
     * <p>Without a declared width there is one row per logical line, which is the behaviour every
     * earlier release had.</p>
     */
    private static Model moveVertical(Model state, Config options, int direction) {
        Frame frame = frame(state, options, 0);
        VisualRows.Cells cells = movementCells(options);
        Map<Integer, List<VisualRows.Row>> cache = new HashMap<>();
        String text = state.text();
        int line = state.lineAt(state.caret());
        List<VisualRows.Row> rows = plainRows(cache, state, frame, cells, line);
        int row = rowOf(rows, state.caret());
        // A vertical move keeps the column the caret was at on the row it came from, even when a
        // shorter row in between clamps it; horizontal moves and edits reset the preference.
        int firstLine = firstVisibleLine(state, options, frame, line);
        int column = state.preferredColumn() > 0
                ? state.preferredColumn()
                : rowColumn(
                        text,
                        rows.get(row).start(),
                        state.caret(),
                        frame.prefix(),
                        cells,
                        openAt(line, firstLine, frame));
        int targetLine = line;
        int targetRow = row;
        if (direction < 0) {
            if (row > 0) {
                targetRow = row - 1;
            } else if (line > 0) {
                targetLine = line - 1;
                targetRow = plainRows(cache, state, frame, cells, targetLine).size() - 1;
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
        List<VisualRows.Row> targetRows = plainRows(cache, state, frame, cells, targetLine);
        VisualRows.Row target = targetRows.get(Math.max(0, Math.min(targetRow, targetRows.size() - 1)));
        int caret = indexAtColumn(
                text,
                target.start(),
                target.end(),
                column,
                frame.prefix(),
                cells,
                openAt(targetLine, firstLine, frame));
        return new Model(text, caret, caret, column);
    }

    private static Model moveLineEdge(Model state, boolean toStart) {
        int line = state.lineAt(state.caret());
        int caret = toStart ? state.lineStart(line) : state.lineEnd(line);
        return new Model(state.text(), caret, caret, 0);
    }

    private static Model selectAll(Model state) {
        if (state.text().isEmpty()) {
            return state;
        }
        return new Model(state.text(), state.text().length(), 0, 0);
    }

    /** The state with the caret at an offset and no selection. */
    private static Model placed(Model state, int offset) {
        int caret = clampedOffset(state.text(), offset);
        return new Model(state.text(), caret, caret, 0, state.focused());
    }

    /** The state with the caret at an offset and the anchor where it was. */
    private static Model extendedTo(Model state, int offset) {
        int caret = clampedOffset(state.text(), offset);
        return new Model(state.text(), caret, state.anchor(), state.preferredColumn(), state.focused());
    }

    /**
     * The state with the word around an offset selected.
     *
     * <p>The run is the one a word gesture would step over. A click past the end of the draft names
     * the word before it, which is what a click on the cell after the last cluster means.</p>
     */
    private static Model selectWord(Model state, int offset) {
        String text = state.text();
        int at = clampedOffset(text, offset);
        int probe = at == text.length() && at > 0 ? TextMeasure.previousBoundary(text, at) : at;
        int from = wordBoundary(text, probe, -1);
        int to = wordBoundary(text, from, 1);
        return new Model(text, to, from, 0, state.focused());
    }

    /** An offset clamped into the draft and moved back to a cluster boundary. */
    private static int clampedOffset(String text, int offset) {
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
    private enum ClusterClass {
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
    private static int wordBoundary(String text, int index, int direction) {
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

    private static Model moveWord(Model state, int direction) {
        int caret = wordBoundary(state.text(), state.caret(), direction);
        return new Model(state.text(), caret, caret, 0, state.focused());
    }

    private static Model deleteWord(Model state, int direction) {
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
    private static Model extendSelection(Model state, Config opts, Msg move) {
        // The movement is computed from a collapsed caret: a vertical move from a selection would
        // otherwise start at the caret of a range whose preferred column came from its other end.
        Model collapsed =
                new Model(state.text(), state.caret(), state.caret(), state.preferredColumn(), state.focused());
        Model moved = reduce(collapsed, opts, move).state();
        return new Model(moved.text(), moved.caret(), state.anchor(), moved.preferredColumn(), moved.focused());
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    /**
     * The largest wrap width. The column arithmetic adds a row's first column to it, so it stays far
     * inside the {@code int} range: an unbounded row must never be able to overflow into an early
     * break.
     */
    private static final int UNBOUNDED = Integer.MAX_VALUE / 2;

    /**
     * The frame one editor paints in: the columns before every row's draft, the cells one row's
     * draft may occupy, and the cell each kind of row leaves open to its first cluster.
     *
     * @param prefix the columns painted before every row: the gutter and the prompt
     * @param wrap the cells one row's draft may occupy, or {@link #UNBOUNDED}
     * @param promptCell the text of the cell the prompt ends with, or empty when it paints none
     * @param promptTabStop whether that cell is the space a tab stop ended with
     */
    private record Frame(int prefix, int wrap, String promptCell, boolean promptTabStop) {}

    /**
     * One painted row, with the logical line it belongs to.
     *
     * @param line the logical line the row shows
     * @param start the first document offset the row shows
     * @param end the offset just past the last one it shows
     * @param columns the cells the row occupies
     */
    private record RowAt(int line, int start, int end, int columns) {}

    /**
     * The rows one frame paints, and where the caret sits among them.
     *
     * @param rows the rows, top to bottom
     * @param caretRow the index of the row the caret is drawn on
     * @param firstLine the line the window starts at, which is the one carrying the prompt
     * @param frame the frame the rows were projected in
     * @param focused whether the editor holds focus, which is what a cursor line answers to
     * @param blockPainted whether the caret block is part of the painted rows
     */
    private record Painted(
            List<RowAt> rows, int caretRow, int firstLine, Frame frame, boolean focused, boolean blockPainted) {}

    /** How a painted row charges its clusters. */
    private static VisualRows.Cells paintCells(Config options) {
        return switch (options.masking()) {
            case NONE -> VisualRows.Cells.NATURAL;
            case PASSWORD -> VisualRows.Cells.MASKED;
            case HIDDEN -> VisualRows.Cells.HIDDEN;
        };
    }

    /**
     * How a movement charges its clusters.
     *
     * <p>A hidden row paints no glyph before its block - the block sits at the prompt's column
     * wherever the caret is - so it paints no column for a move to agree with; counting the masked
     * cells is what keeps a vertical move returning the caret to the cluster the masked layout
     * names. A movement therefore never counts {@link VisualRows.Cells#HIDDEN}.</p>
     */
    private static VisualRows.Cells movementCells(Config options) {
        return options.masking() == Config.Masking.NONE ? VisualRows.Cells.NATURAL : VisualRows.Cells.MASKED;
    }

    /**
     * The frame the editor paints in.
     *
     * <p>The declared width is what vertical movement and a dynamic height are computed for; the
     * space the view is actually given wins when it is narrower, so a resized pane still paints rows
     * that fit and puts its caret on the cell it drew. A width of zero means the caller knows of
     * none - the headless entry points - so a declared width is used alone.</p>
     */
    private static Frame frame(Model state, Config options, int allocatedCols) {
        int gutter = gutterColumns(state, options);
        String prompt = paintedPrompt(options);
        int prefix = gutter + TextMeasure.columns(prompt);
        int declared = options.wrapWidth().orElse(0);
        int width = declared > 0 ? (allocatedCols > 0 ? Math.min(declared, allocatedCols) : declared) : allocatedCols;
        int wrap = width > 0 ? Math.max(1, width - prefix) : UNBOUNDED;
        // With nothing painted before a row's draft there is no cell for its first cluster to join,
        // so the blank stand-in only opens one when it actually occupies columns.
        VisualRows.Open open = promptOpen(options, prompt);
        return new Frame(prefix, wrap, open.cell(), open.tabStop());
    }

    /**
     * The columns a line-number gutter occupies, or zero when no gutter is painted.
     *
     * <p>The widest number plus one separator column, so the draft of every row starts at the same
     * column and a wrapped row aligns under the line it continues.</p>
     */
    private static int gutterColumns(Model state, Config options) {
        if (!options.presentation().lineNumbers()) {
            return 0;
        }
        return Integer.toString(Math.max(1, state.lineCount())).length() + 1;
    }

    /**
     * The cell the row carrying the prompt leaves open to the draft's first cluster.
     *
     * <p>A prompt ending in a base character and a prompt ending in a tab's space open different
     * cells: the text view continues a stop's space only with the clusters that claim no column of
     * their own, while an ordinary last cluster takes a bare mark into the cell it is painted in.
     * That is the cell the paint path reads for the row's first cluster, so the caret's arithmetic
     * reads the same one.</p>
     */
    private static VisualRows.Open promptOpen(Config options, String prompt) {
        List<String> clusters = TextMeasure.graphemes(prompt);
        if (clusters.isEmpty()) {
            // Nothing is drawn before the draft, so its first cluster claims a cell of its own.
            return VisualRows.CLOSED;
        }
        return new VisualRows.Open(
                clusters.get(clusters.size() - 1), options.prompt().endsWith("\t"));
    }

    /**
     * The cell the row of {@code line} leaves open to its first cluster: the prompt's on the window's
     * first line, the blank stand-in's on every other.
     */
    private static VisualRows.Open openAt(int line, int firstLine, Frame frame) {
        if (line == firstLine) {
            return frame.promptCell().isEmpty()
                    ? VisualRows.CLOSED
                    : new VisualRows.Open(frame.promptCell(), frame.promptTabStop());
        }
        return indentOpen(frame);
    }

    /**
     * The cell a row painted after the blank stand-in leaves open. Nothing is painted before a row's
     * draft when the editor has no gutter and no prompt, so the row opens no cell at all.
     */
    private static VisualRows.Open indentOpen(Frame frame) {
        return frame.prefix() == 0 ? VisualRows.CLOSED : new VisualRows.Open(" ", false);
    }

    /**
     * The height budget one frame paints into: the content's rows for a dynamic height, or the
     * configured visible lines, and never more than the view was allocated.
     */
    private static int budget(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            Config options,
            Frame frame,
            VisualRows.Cells cells,
            int allocatedRows) {
        Presentation look = options.presentation();
        // A dynamic height is the content's own height, so it has to be counted before the window is
        // chosen and the count stops at the row the policy will not grow past. A fixed height needs
        // no count at all: the window is as tall as the policy says, and a short draft leaves the
        // rows it did not fill unused.
        int height = look.dynamicHeight()
                ? Math.max(look.minLines(), Math.min(look.maxLines(), contentRows(cache, state, options, frame, cells)))
                : options.maxVisibleLines();
        height = Math.max(1, height);
        return allocatedRows > 0 ? Math.max(1, Math.min(height, allocatedRows)) : height;
    }

    /**
     * How many rows the whole draft needs, counted no further than the policy can paint.
     *
     * <p>The count stops at the largest number of rows that can affect the height: a document a
     * million lines long is never measured whole on every frame, and a draft inside one enormous
     * line costs one projection of that line, which is what painting it costs anyway.</p>
     */
    private static int contentRows(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            Config options,
            Frame frame,
            VisualRows.Cells cells) {
        Presentation look = options.presentation();
        int cap = look.maxContentLines();
        int total = 0;
        for (int line = 0; line < state.lineCount() && total < cap; line++) {
            total += plainRows(cache, state, frame, cells, line).size();
        }
        return Math.min(total, cap);
    }

    /**
     * The rows one line breaks into with the blank stand-in's prefix and no caret block, memoized for
     * one frame: the counting pass, the window search and the painting pass would otherwise project
     * the same line more than once.
     */
    private static List<VisualRows.Row> plainRows(
            Map<Integer, List<VisualRows.Row>> cache, Model state, Frame frame, VisualRows.Cells cells, int line) {
        if (frame.wrap() == UNBOUNDED) {
            // One row per line, which needs no projection at all: nothing can break, so the row is
            // the line. The unwrapped path - a policy with no declared width - costs what it did
            // before rows existed.
            return trivialRow(state, line);
        }
        return cache.computeIfAbsent(
                line,
                key -> VisualRows.project(
                        state.text(),
                        state.lineStart(key),
                        state.lineEnd(key),
                        frame.prefix(),
                        frame.wrap(),
                        cells,
                        indentOpen(frame),
                        -1));
    }

    /**
     * The rows one line breaks into, projected with the prefix the line carries in its window.
     *
     * <p>The window's first line paints the prompt and every other line the blank stand-in, and the
     * two open different cells to the draft's first cluster; the caret's own line is charged the
     * block. A line that needs neither is the one the cache holds.</p>
     */
    private static List<VisualRows.Row> windowRows(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            Config options,
            Frame frame,
            VisualRows.Cells cells,
            int line,
            int firstLine,
            int caretOffset) {
        if (frame.wrap() == UNBOUNDED) {
            // A line that cannot wrap is one row whatever prefix it carries and wherever the caret
            // is, so the prefix and the block change no boundary.
            return trivialRow(state, line);
        }
        VisualRows.Open open = openAt(line, firstLine, frame);
        int caret = line == state.lineAt(state.caret()) ? caretOffset : -1;
        if (caret < 0 && open.equals(indentOpen(frame))) {
            return plainRows(cache, state, frame, cells, line);
        }
        return VisualRows.project(
                state.text(),
                state.lineStart(line),
                state.lineEnd(line),
                frame.prefix(),
                frame.wrap(),
                cells,
                open,
                caret);
    }

    /** The one row an unwrapped line paints. */
    private static List<VisualRows.Row> trivialRow(Model state, int line) {
        int start = state.lineStart(line);
        return List.of(new VisualRows.Row(start, state.lineEnd(line), 0));
    }

    /** The index of the row {@code offset} is drawn on: the last row that starts at or before it. */
    private static int rowOf(List<VisualRows.Row> rows, int offset) {
        int found = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start() <= offset) {
                found = i;
            }
        }
        return found;
    }

    /**
     * Where a window starts: the line it begins at, and how many of that line's top rows it drops.
     *
     * @param firstLine the first logical line the window paints
     * @param topSkip the rows of that line the window drops, so a line taller than the window is
     *     scrolled through instead of hiding the caret
     */
    private record WindowStart(int firstLine, int topSkip) {}

    /**
     * The window that keeps the caret's row visible and ends there, filling the rows still left
     * below it.
     *
     * <p>The window grows upward from the caret's row while the rows still fit. A line above that
     * does not fit whole contributes the rows at its end, which is where a viewport scrolls to; the
     * caret's own line, when it is taller than the whole window, is trimmed from the top so the
     * caret's row is the window's last.</p>
     */
    private static WindowStart windowStart(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            Frame frame,
            VisualRows.Cells cells,
            int caretLine,
            int caretRowInLine,
            int budget) {
        int need = caretRowInLine + 1;
        int above = 0;
        int firstLine = caretLine;
        int topSkip = 0;
        for (int line = caretLine - 1; line >= 0; line--) {
            int count = plainRows(cache, state, frame, cells, line).size();
            if (above + count + need <= budget) {
                above += count;
                firstLine = line;
                continue;
            }
            int room = budget - above - need;
            if (room > 0) {
                firstLine = line;
                topSkip = count - room;
                above += room;
            }
            break;
        }
        if (above + need > budget) {
            return new WindowStart(caretLine, need - budget);
        }
        return new WindowStart(firstLine, topSkip);
    }

    /**
     * Projects the frame the editor paints: the rows to draw, and the row and column of the caret.
     *
     * <p>The window ends at the caret's row, so the caret never scrolls out of the editor, and it
     * fills the rows still left below it, which is what makes a short draft sit at the top of a
     * taller view. A caret line too tall for the whole window is trimmed from the top instead, so a
     * draft line longer than the editor still shows the caret.
     *
     * @param state the editor state
     * @param options the editing and rendering policy
     * @param focused whether the editor holds focus, which paints the caret and its row
     * @param allocatedCols the columns the view was given, or zero when it was not told
     * @param allocatedRows the rows the view was given, or zero when it was not told
     * @return the frame
     */
    private static Painted painted(Model state, Config options, boolean focused, int allocatedCols, int allocatedRows) {
        Config opts = options == null ? Config.defaults() : options;
        // A painted editor puts its caret in the text it renders; a declared one is placed by the
        // layout from the same projection, which is why the block's cell is charged either way.
        boolean blockPainted = focused && opts.cursor().caret() == Caret.PAINTED;
        Frame frame = frame(state, opts, allocatedCols);
        VisualRows.Cells cells = paintCells(opts);
        Map<Integer, List<VisualRows.Row>> cache = new HashMap<>();
        int budget = budget(cache, state, opts, frame, cells, allocatedRows);
        int caret = state.caret();
        int caretOffset = blockPainted ? caret : -1;
        int caretLine = state.lineAt(caret);
        List<VisualRows.Row> caretRows = plainRows(cache, state, frame, cells, caretLine);
        int caretRowInLine = rowOf(caretRows, caret);
        WindowStart window = windowStart(cache, state, frame, cells, caretLine, caretRowInLine, budget);
        int firstLine = window.firstLine();
        int topSkip = window.topSkip();
        List<RowAt> rows = new ArrayList<>(Math.min(budget, 256));
        for (int line = firstLine; line < state.lineCount() && rows.size() < budget; line++) {
            List<VisualRows.Row> projected = windowRows(cache, state, opts, frame, cells, line, firstLine, caretOffset);
            for (int i = line == firstLine ? topSkip : 0; i < projected.size() && rows.size() < budget; i++) {
                VisualRows.Row row = projected.get(i);
                rows.add(new RowAt(line, row.start(), row.end(), row.columns()));
            }
        }
        if (rows.isEmpty()) {
            rows.add(new RowAt(caretLine, state.lineStart(caretLine), state.lineStart(caretLine), 0));
        }
        int caretRow = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start() <= caret) {
                caretRow = i;
            }
        }
        if (opts.presentation().dynamicHeight()) {
            // A dynamic height is a size the editor keeps, not a count of what the draft happens to
            // fill: below its minimum the rows that are left are painted empty, which is what makes
            // an empty textarea the height of the smallest one rather than one row tall. The caret
            // keeps the row it was on, so a blank row below it is never the caret's.
            int pad = state.lineEnd(rows.get(rows.size() - 1).line());
            while (rows.size() < budget) {
                rows.add(new RowAt(-1, pad, pad, 0));
            }
        }
        return new Painted(List.copyOf(rows), caretRow, firstLine, frame, focused, blockPainted);
    }

    /**
     * Painted column of {@code index} on the row that starts at {@code start}: the columns the row
     * draws before it, counted from {@code firstColumn} because that is where the row's own prefix
     * ends. A tab counts the columns the text view paints for it, a wide glyph its real width, and a
     * masked row one cell per cluster.
     */
    private static int rowColumn(
            String text, int start, int index, int firstColumn, VisualRows.Cells cells, VisualRows.Open open) {
        int end = Math.max(start, Math.min(index, text.length()));
        int used = firstColumn;
        VisualRows.Open held = open;
        for (String cluster : Graphemes.iterate(text.substring(start, end))) {
            used += VisualRows.cells(cluster, used, cells, held);
            held = VisualRows.openBehind(cluster, cells);
        }
        return used;
    }

    /**
     * Caret offset at {@code column} painted columns in one row, stopping before a glyph that would
     * overshoot. The row is counted from {@code firstColumn} for the same reason {@link #rowColumn}
     * is.
     */
    private static int indexAtColumn(
            String text,
            int start,
            int end,
            int column,
            int firstColumn,
            VisualRows.Cells cells,
            VisualRows.Open open) {
        int position = Math.max(0, Math.min(start, text.length()));
        int limit = Math.max(position, Math.min(end, text.length()));
        int used = firstColumn;
        VisualRows.Open held = open;
        for (String cluster : Graphemes.iterate(text.substring(position, limit))) {
            int width = VisualRows.cells(cluster, used, cells, held);
            held = VisualRows.openBehind(cluster, cells);
            if (used + width > column) {
                break;
            }
            used += width;
            position += cluster.length();
        }
        return position;
    }

    /**
     * The first line of the window the editor paints while the caret is on {@code caretLine}.
     *
     * <p>Used where only the prompt's prefix matters - the cell arithmetic of a vertical move - so it
     * stops at the first line, which is all {@link #openAt} needs.</p>
     */
    private static int firstVisibleLine(Model state, Config options, Frame frame, int caretLine) {
        VisualRows.Cells cells = paintCells(options);
        Map<Integer, List<VisualRows.Row>> cache = new HashMap<>();
        int budget = budget(cache, state, options, frame, cells, 0);
        List<VisualRows.Row> caretRows = plainRows(cache, state, frame, cells, caretLine);
        int caretRowInLine = rowOf(caretRows, state.caret());
        return windowStart(cache, state, frame, cells, caretLine, caretRowInLine, budget)
                .firstLine();
    }

    // ── view ─────────────────────────────────────────────────────────────────

    /**
     * Builds the editor view. The returned value declares focus identity and input handling; the
     * application maps each produced {@link Msg} into its own message type.
     *
     * <p>A {@link Caret#PAINTED} editor puts the block into the text it renders, which is why the
     * renderer is told whether the caret is painted at all. A declared caret - native or virtual -
     * instead wraps that text in a cursor at the caret's own row and column, owned by the editor's
     * focus identity so it is shown only while the editor holds focus.</p>
     *
     * <p>The rows the draft is wrapped into depend on how much width the editor is given, and the
     * space a split pane or a narrower terminal leaves it is decided by the ancestors it is placed
     * in. The view therefore declares itself as a {@link View.Sized} subtree: the same builder wraps
     * the draft and places the caret from the geometry it is handed, so the two cannot disagree.</p>
     */
    private static <Message> View<Message> paint(
            ElementId id, Model state, Config options, KeyMap<Msg> keys, boolean focused, Function<Msg, Message> map) {
        Config opts = options == null ? Config.defaults() : options;
        View.InputHandler<Message> handler =
                event -> actionFor(event, opts, keys).map(map);
        View.PointerHandler<Message> pointer = (event, region) ->
                pointerAction(state, opts, focused, region, event).map(map);
        boolean painted = opts.cursor().caret() == Caret.PAINTED;
        return Views.focus(id, focused, List.of(), handler, pointer, Views.sized(size -> {
            Painted frame = painted(state, opts, focused, size.cols(), size.rows());
            View<Message> content = Views.text(render(frame, state, opts));
            return focused && !painted ? Views.cursor(caret(id, state, opts, frame), content) : content;
        }));
    }

    /**
     * The message a pointer report produces, or empty when it is not an editing gesture.
     *
     * <p>A press puts the caret where the pointer was, a second press selects the word there, and
     * motion with the left button held extends the selection - which is the drag. The cell is mapped
     * through the same projection the rows were painted from, so a click lands on the cluster under
     * it across a soft wrap, after a tab, and on a two-column glyph.</p>
     *
     * <p>The geometry comes from the region the report arrived in, never from the size this handler
     * was built with: routing builds a size-aware subtree at zero columns to find the handlers in
     * it, and a caret placed from that geometry would be a caret placed nowhere.</p>
     */
    private static Optional<Msg> pointerAction(
            Model state, Config options, boolean focused, Size region, PointerEvent event) {
        boolean dragging = event.isMotion() && event.button() == PointerButton.LEFT;
        if (!event.isPress() && !dragging) {
            return Optional.empty();
        }
        int offset = offsetAt(state, options, focused, region, event);
        if (dragging) {
            return Optional.of(new Msg.ExtendSelection(offset));
        }
        return Optional.of(event.clickCount() > 1 ? new Msg.SelectWord(offset) : new Msg.PlaceCaret(offset));
    }

    /**
     * The document offset one cell of the painted rows falls on.
     *
     * <p>A row outside the painted window clamps to the nearest one, so a drag that leaves the
     * editor keeps selecting instead of stopping at its edge.</p>
     */
    private static int offsetAt(Model state, Config options, boolean focused, Size region, PointerEvent event) {
        Config opts = options == null ? Config.defaults() : options;
        Painted frame = painted(state, opts, focused, region.cols(), region.rows());
        List<RowAt> rows = frame.rows();
        if (rows.isEmpty()) {
            return state.caret();
        }
        RowAt row = rows.get(Math.max(0, Math.min(event.row(), rows.size() - 1)));
        return indexAtColumn(
                state.text(),
                row.start(),
                row.end(),
                Math.max(0, event.column()),
                frame.frame().prefix(),
                paintCells(opts),
                openAt(row.line(), frame.firstLine(), frame.frame()));
    }

    /**
     * The caret as cursor intent, at the cell the draft is painted in.
     *
     * <p>The row and column come from the same projection the rows were painted from, so a declared
     * caret lands on the cell the painted block would have covered - across a soft wrap, after a
     * tab, on a two-column glyph, and in a masked or hidden field.</p>
     */
    private static Cursor caret(ElementId id, Model state, Config options, Painted frame) {
        RowAt at = frame.rows().get(frame.caretRow());
        int column = rowColumn(
                state.text(),
                at.start(),
                state.caret(),
                frame.frame().prefix(),
                paintCells(options),
                openAt(at.line(), frame.firstLine(), frame.frame()));
        return new Cursor(
                options.cursor().shape(),
                options.cursor().mode(),
                options.cursor().strategy(),
                null,
                frame.caretRow(),
                column,
                id.asOptional());
    }

    /**
     * Pure text rendering, shared by the native view and headless tests.
     *
     * <p>The prompt marks the input area once, at the first visible row, and every other row is
     * indented by its painted width. It deliberately does not follow the caret: doing so repeats
     * the prompt whenever a new line is added to a multi-line draft, which reads as a second caret
     * beside the block cursor. The prompt itself is one line: {@link Config} turns a break
     * in it into a space, so the painted rows stay the window's rows.</p>
     *
     * <p>This overload paints for the width the policy declares: a draft with no declared width is
     * one row per logical line.</p>
     *
     * @param state the editor state to paint
     * @param options the editing and rendering policy
     * @param focused whether the caret is painted
     * @return the painted rows
     */
    public static StyledText render(Model state, Config options, boolean focused) {
        Config opts = options == null ? Config.defaults() : options;
        return render(state, opts, focused, 0);
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
    public static StyledText render(Model state, Config options, boolean focused, int cols) {
        Config opts = options == null ? Config.defaults() : options;
        return render(painted(state, opts, focused, cols, 0), state, opts);
    }

    /**
     * The prompt as the text view paints it, with its tabs expanded to the next four-column stop.
     * Every visible draft row carries it, continuation rows as the blank stand-in of its width, so
     * the caret's column arithmetic and the painted row start from one prefix.
     */
    private static String paintedPrompt(Config options) {
        return TabStops.expand(StyledText.plain(options.prompt())).plainText();
    }

    /** Paints one projected frame: a row per painted row, with its gutter, prompt and cursor line. */
    private static StyledText render(Painted frame, Model state, Config options) {
        Config opts = options == null ? Config.defaults() : options;
        Presentation look = opts.presentation();
        String placeholder = state.text().isEmpty() ? look.placeholder() : "";
        Style cursorLine = look.cursorLineStyle();
        StyledText out = StyledText.empty();
        for (int i = 0; i < frame.rows().size(); i++) {
            if (i > 0) {
                out = out.append("\n");
            }
            RowAt at = frame.rows().get(i);
            Style rowStyle = frame.focused() && i == frame.caretRow() ? cursorLine : Style.NONE;
            out = out.append(gutter(at, frame, state, opts, rowStyle));
            out = out.append(renderRow(state, opts, at, i == 0, i == frame.caretRow(), rowStyle, placeholder, frame));
        }
        return out;
    }

    /**
     * The line-number gutter of one row.
     *
     * <p>A logical line is numbered once, on the row it starts at; a row the line was wrapped into
     * continues under a blank gutter, so the numbers name lines rather than rows and the draft stays
     * aligned under the number it belongs to.</p>
     */
    private static StyledText gutter(RowAt at, Painted frame, Model state, Config opts, Style rowStyle) {
        int columns = gutterColumns(state, opts);
        if (columns == 0) {
            return StyledText.empty();
        }
        Style style = opts.presentation().lineNumberStyle().merge(rowStyle);
        if (at.line() < 0 || at.start() != state.lineStart(at.line())) {
            return StyledText.of(" ".repeat(columns), style);
        }
        String number = Integer.toString(at.line() + 1);
        return StyledText.of(" ".repeat(columns - 1 - number.length()) + number + " ", style);
    }

    /** Paints one row's prefix and its draft, with the selection, the block caret and the placeholder. */
    private static StyledText renderRow(
            Model state,
            Config opts,
            RowAt at,
            boolean firstRow,
            boolean caretRow,
            Style rowStyle,
            String placeholder,
            Painted frame) {
        StyledText out = StyledText.empty();
        if (!opts.prompt().isEmpty()) {
            out = out.append(
                    firstRow
                            ? StyledText.of(opts.prompt(), opts.promptStyle().merge(rowStyle))
                            : StyledText.of(
                                    " ".repeat(TextMeasure.columns(paintedPrompt(opts))),
                                    opts.promptStyle().merge(rowStyle)));
        }
        int start = at.start();
        int end = at.end();
        String text = state.text().substring(start, end);
        VisualRows.Cells cells = paintCells(opts);
        int selectionStart = state.hasSelection() ? state.selectionStart() : -1;
        int selectionEnd = state.hasSelection() ? state.selectionEnd() : -1;
        boolean masked = opts.masking() == Config.Masking.PASSWORD;
        boolean bounded = frame.frame().wrap() != UNBOUNDED;

        StringBuilder pending = new StringBuilder(Math.max(16, end - start));
        Style pendingStyle = opts.textStyle().merge(rowStyle);
        int position = start;
        // Painted columns before this row's draft: the gutter, the prompt, or the blank stand-in
        // that keeps later rows aligned. A tab advances to the next stop from there, so the caret's
        // block covers the cells the draft really occupies.
        int used = frame.frame().prefix();
        // The cell the prefix ends with, which the first cluster of the draft is painted inside when
        // it has no base of its own; a tab leaves the same opening at the last space of its stop.
        VisualRows.Open held = openAt(at.line(), frame.firstLine(), frame.frame());
        if (!placeholder.isEmpty()) {
            // The placeholder is painted where an empty draft would be, and is not part of it: it
            // occupies no document offset and is never submitted or copied. The block replaces its
            // first cluster, exactly as it replaces the first character of a draft, and a declared
            // cursor is already placed on that cell.
            Style style = opts.presentation().placeholderStyle().merge(rowStyle);
            int painted;
            if (frame.blockPainted()) {
                int first = Math.max(1, TextMeasure.boundaryAfter(placeholder, 0));
                int block =
                        Math.max(1, TextMeasure.clusterColumns(placeholder.substring(0, first), WidthPolicy.PINNED));
                out = out.append(StyledText.of(CURSOR.repeat(block), style))
                        .append(StyledText.of(placeholder.substring(first), style));
                painted = block + TextMeasure.columns(placeholder.substring(first));
            } else {
                out = out.append(StyledText.of(placeholder, style));
                painted = TextMeasure.columns(placeholder);
            }
            return out.append(filling(painted, rowStyle, frame));
        }
        // Segment this row once. A per-cluster boundaryAfter() over the whole draft is O(n^2) and
        // cost tens of milliseconds per frame on a draft at the editor's length limit; the row's own
        // grapheme boundaries are the draft's, because a row starts and ends on a boundary.
        for (String cluster : Graphemes.iterate(text)) {
            int next = position + cluster.length();
            boolean selected = selectionStart >= 0 && position >= selectionStart && position < selectionEnd;
            String display = opts.masking() == Config.Masking.HIDDEN ? "" : masked ? MASK : cluster;
            boolean cursor = frame.blockPainted() && state.caret() == position;
            int cost =
                    cursor ? VisualRows.blockCells(cluster, used, cells) : VisualRows.cells(cluster, used, cells, held);
            held = cursor ? VisualRows.CLOSED : VisualRows.openBehind(cluster, cells);
            if (cursor) {
                // The block covers the grapheme under the caret. A two-column grapheme, and a tab
                // reaching its stop, occupy those cells; one block would leave the row short and
                // slide everything after the caret to the left.
                display = CURSOR.repeat(cost);
            } else if (bounded
                    && "\t".equals(cluster)
                    && !masked
                    && opts.masking() != Config.Masking.HIDDEN
                    && cost > frame.frame().prefix() + frame.frame().wrap() - used) {
                // A stop wider than the whole row is painted as the spaces the row can hold, which
                // is the width the projection charged it; every other tab stays the character the
                // text view expands to the same stop the projection counted.
                cost = Math.max(1, frame.frame().prefix() + frame.frame().wrap() - used);
                display = " ".repeat(cost);
            }
            Style style = (selected ? opts.selectionStyle() : opts.textStyle()).merge(rowStyle);
            if (!display.isEmpty()) {
                if (style.equals(pendingStyle)) {
                    pending.append(display);
                } else {
                    out = out.append(StyledText.of(pending.toString(), pendingStyle));
                    pending.setLength(0);
                    pending.append(display);
                    pendingStyle = style;
                }
                used += cost;
            }
            position = next;
        }
        if (frame.blockPainted() && caretRow && state.caret() == end) {
            // A caret at the end of a row is drawn here unless a later painted row starts at the
            // same offset - a soft break is one offset that ends one row and starts the next, and
            // the block belongs to the row it opens.
            pending.append(CURSOR);
        }
        out = out.append(StyledText.of(pending.toString(), pendingStyle));
        return out.append(filling(used - frame.frame().prefix(), rowStyle, frame));
    }

    /**
     * The blanks that fill the rest of a row a style covers.
     *
     * <p>A cursor line is a row style, so it has to reach the row's last column rather than stopping
     * at the last glyph. An unstyled row, and one that is not wrapped to a width at all, adds
     * nothing.</p>
     *
     * @param cells the content cells the row's draft painted
     * @param rowStyle the style the row carries
     * @param frame the frame the row was projected in
     * @return the blanks, or empty
     */
    private static StyledText filling(int cells, Style rowStyle, Painted frame) {
        int padding = frame.frame().wrap() - cells;
        if (rowStyle.isPlain() || padding <= 0 || frame.frame().wrap() == UNBOUNDED) {
            return StyledText.empty();
        }
        return StyledText.of(" ".repeat(padding), rowStyle);
    }

    // ── input mapping ────────────────────────────────────────────────────────

    /**
     * Maps one normalized input event to an editor action, or empty when unhandled.
     *
     * <p>A configured action wins over the text its gesture carries, so a copy or paste binding, or
     * any action a caller remapped onto a printable key, stays reachable.</p>
     * @param event the normalized input event
     * @param options the editing and rendering policy
     * @return the editing message, or empty when the event is not an editor gesture
     */
    public static Optional<Msg> actionFor(InputEvent event, Config options) {
        Config opts = options == null ? Config.defaults() : options;
        return actionFor(event, opts, defaultKeys(opts));
    }

    /**
     * Maps one normalized input event against the actions this editor handles.
     *
     * <p>An action is matched before printable text: a gesture mapped to a printable key must fire
     * rather than insert its own character. A gesture no action names still inserts its text when
     * the modifiers are the ones a printable key carries, and is otherwise left for the next routing
     * phase.</p>
     */
    private static Optional<Msg> actionFor(InputEvent event, Config opts, KeyMap<Msg> keys) {
        if (event instanceof InputEvent.PasteInput paste) {
            return Optional.of(new Msg.Paste(paste.text()));
        }
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        Key key = keyInput.key();
        Optional<Msg> action = keys.actionFor(key).map(KeyAction::message);
        if (action.isPresent()) {
            return action;
        }
        if (!keyInput.text().isEmpty() && plainModifiers(key)) {
            return Optional.of(new Msg.Insert(keyInput.text()));
        }
        return Optional.empty();
    }

    /** The modifier states a printable key carries its text under: none, or shift alone. */
    private static boolean plainModifiers(Key key) {
        Modifiers modifiers = key.modifiers();
        return modifiers.none() || (modifiers.shift() && !modifiers.ctrl() && !modifiers.alt());
    }

    /**
     * All logical lines of a draft, used by projections that need the line count.
     *
     * @param state the editor state
     * @return its logical lines
     */
    public static List<String> lines(Model state) {
        List<String> lines = new ArrayList<>(state.lineCount());
        int start = 0;
        int newline;
        while ((newline = state.text().indexOf('\n', start)) >= 0) {
            lines.add(state.text().substring(start, newline));
            start = newline + 1;
        }
        lines.add(state.text().substring(start));
        return lines;
    }

    /**
     * Visible line count for layout projections.
     *
     * @param state the editor state
     * @param options the editing and rendering policy
     * @return the number of rows the editor paints
     */
    public static int visibleLineCount(Model state, Config options) {
        Config opts = options == null ? Config.defaults() : options;
        return Math.max(1, Math.min(opts.maxVisibleLines(), state.lineCount()));
    }

    /**
     * Convenience: number of visible lines under the defaults.
     *
     * @param state the editor state
     * @return the number of rows the editor paints
     */
    public static int visibleLineCount(Model state) {
        return visibleLineCount(state, Config.defaults());
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
    public static int visibleLineCount(Model state, Config options, int cols) {
        Config opts = options == null ? Config.defaults() : options;
        return painted(state, opts, false, cols, 0).rows().size();
    }

    /**
     * Convenience: the selection or the whole draft, honoring masking through {@code canCopy}.
     *
     * @param state the editor state to copy from
     * @param options the editing and rendering policy
     * @return the text copying would produce, or empty when copying is refused
     */
    public static Optional<String> copyableText(Model state, Config options) {
        Config opts = options == null ? Config.defaults() : options;
        return opts.canCopy() ? Optional.of(copyText(state)) : Optional.empty();
    }
}
