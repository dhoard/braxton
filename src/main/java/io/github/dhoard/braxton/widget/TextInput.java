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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A pure single-line text field with validation, completion, and application-owned effects.
 *
 * <p>Editing delegates to {@link Editor}, sharing its grapheme-safe caret, selection, length cap,
 * paste policy, masking, and copying rules. Breaks become spaces; overflow scrolls instead of wraps.
 * A no-op preserves model identity. Outputs describe submit, copy, paste, and completion intents;
 * the application performs I/O.</p>
 *
 * <p>A pure {@link Validator} runs after edits. Invalid drafts remain editable; {@link SubmitPolicy}
 * controls submission, which rejects invalid input by default. Completion requests carry the draft
 * revision; {@link Msg.Candidates} for another revision is ignored.</p>
 *
 * <p>Clicks place the caret, second clicks select a word, and dragging extends selection. Coordinates
 * use the painted prompt, scroll offset, and mask geometry. Hidden fields paint no draft glyphs,
 * so clicking them places no caret.</p>
 */
public final class TextInput
        implements Component<TextInput.Model, TextInput.Msg, TextInput.Output>,
                Interactive<TextInput.Model, TextInput.Msg> {

    /**
     * The block glyph painted at the caret.
     */
    public static final String CURSOR = "\u2588";

    /**
     * The largest window. The cell arithmetic adds the prompt's columns to it, so the sum stays far
     * inside the {@code int} range and a field with no declared width can never overflow into an
     * early scroll.
     */
    static final int UNBOUNDED = Integer.MAX_VALUE / 2;

    /**
     * The outcome of validating one draft.
     */
    public sealed interface Validation {

        /**
         * The draft passes the policy.
         */
        record Valid() implements Validation {}

        /**
         * The draft does not pass the policy.
         *
         * @param message the reason, for the user to read; never blank
         */
        record Invalid(String message) implements Validation {

            /**
             * Rejects a blank reason, which would show as nothing at all.
             */
            public Invalid {
                message = message == null || message.isBlank() ? "invalid" : message;
            }
        }

        /**
         * A passing outcome.
         *
         * @return the outcome
         */
        static Validation ok() {
            return new Valid();
        }

        /**
         * A failing outcome.
         *
         * @param message the reason; blank becomes {@code "invalid"}
         * @return the outcome
         */
        static Validation invalid(String message) {
            return new Invalid(message);
        }

        /**
         * Whether the draft passes.
         *
         * @return whether the draft passes
         */
        default boolean valid() {
            return this instanceof Valid;
        }

        /**
         * The reason the draft does not pass, or empty when it does.
         *
         * @return the reason, or empty
         */
        default String message() {
            return this instanceof Invalid invalid ? invalid.message() : "";
        }
    }

    /**
     * A pure validation policy: the same draft always produces the same outcome.
     *
     * <p>The validator runs after every edit, when a completion is accepted, after a clear and on
     * submission. It must not perform I/O, and an exception it throws is not swallowed: a validator
     * that fails is a configuration failure, and the runtime reports it as one.</p>
     */
    @FunctionalInterface
    public interface Validator {

        /**
         * Validates one draft.
         *
         * @param text the draft, never containing a line break; never null
         * @return the outcome; never null
         */
        Validation validate(String text);

        /**
         * A policy that accepts every draft.
         *
         * @return the policy
         */
        static Validator valid() {
            return text -> Validation.ok();
        }
    }

    /**
     * What submission does with a draft the validator refuses.
     */
    public enum SubmitPolicy {
        /**
         * The draft does not submit; the attempt is reported as {@link Output.Rejected}.
         */
        VALID_ONLY,
        /**
         * Any draft submits, the refused ones included.
         */
        ALLOW_INVALID
    }

    /**
     * Immutable completion policy.
     *
     * <p>Completion is off unless it is asked for, because a field that shows a ghost suffix its
     * application never supplied would be inventing data. When it is on, a candidate matches by a
     * case-sensitive prefix unless {@link #ignoreCase()} says otherwise, and candidates keep the
     * order they were supplied in - the field never sorts them.</p>
     *
     * <p>A mask hides what the user typed, so a masked draft never completes, whatever this policy
     * says: a ghost suffix would show the candidate the mask exists to hide, and accepting one would
     * replace text the user cannot read. There is no override, because no field can make that safe.
     * </p>
     *
     * @param enabled whether the field completes at all
     * @param ignoreCase whether a candidate matches a prefix that differs only in case
     * @param mode the requested behavior mode
     */
    public record Completion(boolean enabled, boolean ignoreCase, Mode mode) {

        /**
         * Matching strategy for supplied candidates.
         */
        public enum Mode {
            /**
             * Matches a contiguous prefix.
             */
            PREFIX,
            /**
             * Matches a contiguous substring.
             */
            SUBSTRING,
            /**
             * Matches an ordered subsequence and ranks its compactness.
             */
            FUZZY
        }

        /**
         * The original two-field form, using prefix matching.
         *
         * @param enabled whether the field completes at all
         * @param ignoreCase whether a candidate matches a prefix that differs only in case
         */
        public Completion(boolean enabled, boolean ignoreCase) {
            this(enabled, ignoreCase, Mode.PREFIX);
        }

        /**
         * Case-sensitive substring completion.
         *
         * @return case-sensitive substring completion
         */
        public static Completion substring() {
            return new Completion(true, false, Mode.SUBSTRING);
        }

        /**
         * Case-insensitive fuzzy completion.
         *
         * @return case-insensitive fuzzy completion
         */
        public static Completion fuzzy() {
            return new Completion(true, true, Mode.FUZZY);
        }

        /**
         * Completion off, which is the default.
         *
         * @return the policy
         */
        public static Completion disabled() {
            return new Completion(false, false);
        }

        /**
         * Case-sensitive prefix completion over a visible draft.
         *
         * @return the policy
         */
        public static Completion prefix() {
            return new Completion(true, false);
        }

        /**
         * The same policy, switched on or off.
         *
         * @param value whether the field completes
         * @return the new policy
         */
        public Completion withEnabled(boolean value) {
            return new Completion(value, ignoreCase, mode);
        }

        /**
         * The same policy with case-sensitive or case-insensitive matching.
         *
         * @param value whether case is ignored
         * @return the new policy
         */
        public Completion withIgnoreCase(boolean value) {
            return new Completion(enabled, value, mode);
        }
    }

    /**
     * Immutable presentation policy: what the field paints around its draft, and how wide.
     *
     * <p>None of this is content. A prompt is painted once before the row and is never submitted or
     * copied, a placeholder stands in for an empty draft, and a suggestion style dresses the ghost
     * suffix of the selected candidate. The width is the whole field's columns - the prompt
     * included - and the space a view is actually given still wins when it is narrower, so a
     * resized terminal scrolls the draft instead of wrapping it.</p>
     *
     * @param width the columns the field paints in, or zero to use the space it is given
     * @param prompt the label painted before the draft; line breaks become spaces
     * @param promptStyle the style applied to the prompt
     * @param textStyle the style applied to the draft
     * @param selectionStyle the style applied to the selection
     * @param placeholder the text painted in place of an empty draft; never content
     * @param placeholderStyle the style applied to the placeholder
     * @param suggestionStyle the style applied to the ghost suffix of the selected candidate
     * @param cursor how the caret is drawn while the field is focused
     */
    public record Appearance(
            int width,
            String prompt,
            Style promptStyle,
            Style textStyle,
            Style selectionStyle,
            String placeholder,
            Style placeholderStyle,
            Style suggestionStyle,
            Editor.CursorPolicy cursor) {

        /**
         * Rejects a negative width and prepares the one-row prompt and placeholder.
         */
        public Appearance {
            if (width < 0) {
                throw new IllegalArgumentException("width must not be negative");
            }
            // A prompt prefixes one row and a placeholder stands in for one, so a break in either
            // becomes the one space a one-row value keeps.
            prompt = LineBreaks.singleRow(prompt);
            promptStyle = promptStyle == null ? Style.NONE : promptStyle;
            textStyle = textStyle == null ? Style.NONE : textStyle;
            selectionStyle = selectionStyle == null ? Style.NONE.withInvert() : selectionStyle;
            placeholder = LineBreaks.singleRow(placeholder);
            placeholderStyle = placeholderStyle == null ? Style.NONE : placeholderStyle;
            suggestionStyle = suggestionStyle == null ? Style.NONE : suggestionStyle;
            cursor = cursor == null ? Editor.CursorPolicy.painted() : cursor;
        }

        /**
         * No prompt, no placeholder, no decoration, and the width the field is given.
         *
         * @return the plain presentation
         */
        public static Appearance plain() {
            return new Appearance(
                    0,
                    "",
                    Style.NONE,
                    Style.NONE,
                    Style.NONE.withInvert(),
                    "",
                    Style.NONE,
                    Style.NONE,
                    Editor.CursorPolicy.painted());
        }

        /**
         * The same presentation at a different width.
         *
         * @param value the columns the field paints in; zero uses the space it is given
         * @return the new presentation
         */
        public Appearance withWidth(int value) {
            return new Appearance(
                    value,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    placeholder,
                    placeholderStyle,
                    suggestionStyle,
                    cursor);
        }

        /**
         * The same presentation with a different prompt.
         *
         * @param value the label before the draft; null paints none
         * @param style the style applied to it
         * @return the new presentation
         */
        public Appearance withPrompt(String value, Style style) {
            return new Appearance(
                    width,
                    value,
                    style,
                    textStyle,
                    selectionStyle,
                    placeholder,
                    placeholderStyle,
                    suggestionStyle,
                    cursor);
        }

        /**
         * The same presentation with a different placeholder.
         *
         * @param value the text painted in place of an empty draft; null paints none
         * @param style the style applied to it
         * @return the new presentation
         */
        public Appearance withPlaceholder(String value, Style style) {
            return new Appearance(
                    width, prompt, promptStyle, textStyle, selectionStyle, value, style, suggestionStyle, cursor);
        }

        /**
         * The same presentation with different draft styles.
         *
         * @param text the style applied to the draft
         * @param selection the style applied to the selection
         * @param suggestion the style applied to the ghost suffix
         * @return the new presentation
         */
        public Appearance withStyles(Style text, Style selection, Style suggestion) {
            return new Appearance(
                    width, prompt, promptStyle, text, selection, placeholder, placeholderStyle, suggestion, cursor);
        }

        /**
         * The same presentation with a different caret.
         *
         * @param value how the caret is drawn; null keeps the painted block
         * @return the new presentation
         */
        public Appearance withCursor(Editor.CursorPolicy value) {
            return new Appearance(
                    width,
                    prompt,
                    promptStyle,
                    textStyle,
                    selectionStyle,
                    placeholder,
                    placeholderStyle,
                    suggestionStyle,
                    value == null ? Editor.CursorPolicy.painted() : value);
        }
    }

    /**
     * The candidates a field completes from, and which one is selected.
     *
     * <p>The list is what the application supplied, in the order it supplied it. {@code selected}
     * indexes the candidates that match the current draft and is read modulo that count, so the
     * selection stays meaningful as the draft narrows the match set. Candidates are de-duplicated
     * as they are supplied: two equal entries would otherwise cycle to the same text twice.</p>
     *
     * @param revision the draft revision the candidates were supplied for
     * @param candidates the candidates, in the application's order
     * @param selected the selected match, offset by any rotation
     */
    public record Suggestions(long revision, List<String> candidates, int selected) {

        /**
         * Rejects a negative revision and prepares the candidate list.
         */
        public Suggestions {
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            candidates = prepare(candidates);
        }

        /**
         * No candidates at all.
         *
         * @return the empty suggestions
         */
        public static Suggestions none() {
            return new Suggestions(0, List.of(), 0);
        }

        /**
         * The same candidates with a different selection.
         *
         * @param value the selection, read modulo the match count
         * @return the new suggestions
         */
        public Suggestions withSelected(int value) {
            return new Suggestions(revision, candidates, value);
        }

        /**
         * Drops missing entries and keeps the first of each duplicate, in order.
         */
        private static List<String> prepare(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<String> kept = new LinkedHashSet<>(values.size());
            for (String candidate : values) {
                if (candidate != null && !candidate.isEmpty()) {
                    kept.add(candidate);
                }
            }
            return List.copyOf(kept);
        }
    }

    /**
     * Immutable field state: the draft, its validation, the candidates and the draft revision.
     *
     * <p>The draft is an {@link Editor.Model}, so the caret, the anchor, the preferred column and
     * the focus flag are the editor's own values with the same meanings and the same grapheme
     * boundary guarantees. It never holds a line break: a draft given to the canonical constructor is
     * flattened to one row, and every insertion and paste is flattened before it is applied.</p>
     *
     * @param draft the draft, its caret, its selection and its focus
     * @param validation the validator's answer for this draft
     * @param suggestions the candidates the field completes from
     * @param revision the draft revision, incremented by every edit
     */
    public record Model(Editor.Model draft, Validation validation, Suggestions suggestions, long revision) {

        /**
         * Rejects a negative revision and flattens the draft to one row.
         */
        public Model {
            draft = draft == null ? Editor.Model.empty() : oneRow(draft);
            validation = validation == null ? Validation.ok() : validation;
            suggestions = suggestions == null ? Suggestions.none() : suggestions;
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
        }

        /**
         * An empty, unfocused field with no candidates.
         *
         * @return the empty state
         */
        public static Model empty() {
            return new Model(Editor.Model.empty(), Validation.ok(), Suggestions.none(), 0);
        }

        /**
         * A draft with the caret at its end and no candidates.
         *
         * <p>The draft is prepared the same way an edit prepares it, so a restored value carries
         * none of the breaks the platform it came from used. The validation starts {@link
         * Validation.Valid valid}: a model cannot run a policy it does not hold, so the component
         * validates the draft it is given on its first message, and {@link TextInput#validated}
         * runs the policy immediately.</p>
         *
         * @param text the draft; null is empty
         * @return the state holding that draft
         */
        public static Model of(String text) {
            String value = text == null ? "" : text;
            return new Model(Editor.Model.of(value), Validation.ok(), Suggestions.none(), 0);
        }

        /**
         * A draft is one row: the one break the editor prepares becomes the space a row keeps.
         */
        private static Editor.Model oneRow(Editor.Model draft) {
            String flat = LineBreaks.singleRow(draft.text());
            // withText puts the offsets at the end of the flattened draft, which is the only
            // position a caller can name after a CR/LF pair collapsed into one space.
            return flat.equals(draft.text()) ? draft : draft.withText(flat);
        }

        /**
         * The draft text, without any line break.
         *
         * @return the draft text
         */
        public String text() {
            return draft.text();
        }

        /**
         * The caret offset, on a grapheme boundary.
         *
         * @return the caret offset
         */
        public int caret() {
            return draft.caret();
        }

        /**
         * Whether the field currently owns focus.
         *
         * @return whether the field owns focus
         */
        public boolean focused() {
            return draft.focused();
        }

        /**
         * Whether the draft is empty.
         *
         * @return whether the draft is empty
         */
        public boolean isEmpty() {
            return draft.isEmpty();
        }

        /**
         * Whether the anchor and the caret differ.
         *
         * @return whether the draft has a selection
         */
        public boolean hasSelection() {
            return draft.hasSelection();
        }

        /**
         * The selected text, or an empty string.
         *
         * @return the selected text
         */
        public String selection() {
            return draft.selection();
        }

        /**
         * Whether the draft passes its policy.
         *
         * @return whether the draft passes
         */
        public boolean valid() {
            return validation.valid();
        }

        /**
         * The same state with a different focus.
         *
         * @param value whether the field owns focus
         * @return this instance when the focus did not change
         */
        public Model withFocus(boolean value) {
            Editor.Model refocused = draft.withFocus(value);
            return refocused == draft ? this : new Model(refocused, validation, suggestions, revision);
        }

        /**
         * The same state with a different validation, which is what an application that restored a
         * draft uses after running the policy itself.
         *
         * @param value the validator's answer for this draft; null keeps the current one
         * @return this instance when the answer did not change
         */
        public Model withValidation(Validation value) {
            Validation next = value == null ? validation : value;
            return next.equals(validation) ? this : new Model(draft, next, suggestions, revision);
        }

        /**
         * The same state with different candidates.
         *
         * @param value the candidates; null clears them
         * @return this instance when the candidates did not change
         */
        public Model withSuggestions(Suggestions value) {
            Suggestions next = value == null ? Suggestions.none() : value;
            return next.equals(suggestions) ? this : new Model(draft, validation, next, revision);
        }
    }

    /**
     * Editing, completion and intent messages.
     */
    public sealed interface Msg {

        /**
         * Inserts printable text at the caret, replacing the selection.
         *
         * <p>A line break in the payload - CR/LF, CR or LF - becomes one space, so no insertion can
         * turn the field into a two-row one.</p>
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
         * Inserts one complete paste payload, sanitized and flattened to one row.
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
         * Replaces the draft with an empty one.
         */
        record Clear() implements Msg {}

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
         * Moves the caret to the start of the word before it.
         */
        record MoveWordLeft() implements Msg {}

        /**
         * Moves the caret past the end of the word after it.
         */
        record MoveWordRight() implements Msg {}

        /**
         * Moves the caret to the start of the draft, which is the start of its only row.
         */
        record MoveStart() implements Msg {}

        /**
         * Moves the caret to the end of the draft, which is the end of its only row.
         */
        record MoveEnd() implements Msg {}

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
         * @param move the navigation action to apply from the caret
         */
        record Extend(Msg move) implements Msg {

            /**
             * Rejects a missing action and one that is not a navigation.
             */
            public Extend {
                if (!(move instanceof MoveLeft
                        || move instanceof MoveRight
                        || move instanceof MoveWordLeft
                        || move instanceof MoveWordRight
                        || move instanceof MoveStart
                        || move instanceof MoveEnd)) {
                    throw new IllegalArgumentException("only a navigation action extends a selection");
                }
            }
        }

        /**
         * Selects the whole draft.
         */
        record SelectAll() implements Msg {}

        /**
         * Puts the caret at an offset and collapses the selection there.
         *
         * <p>This is what a click on the field means. The offset is a document offset, counted in
         * the draft rather than in the cells the field paints, so a masked or scrolled field places
         * the caret where the click landed rather than where the glyph is.</p>
         *
         * @param offset the document offset the caret moves to
         */
        record PlaceCaret(int offset) implements Msg {}

        /**
         * Moves the caret to an offset, keeping the selection anchor where it is.
         *
         * @param offset the document offset the caret moves to
         */
        record ExtendSelection(int offset) implements Msg {}

        /**
         * Selects the word around an offset.
         *
         * @param offset the document offset the clicked cell falls on
         */
        record SelectWord(int offset) implements Msg {}

        /**
         * Replaces the candidates with the application's answer for one revision.
         *
         * @param revision the draft revision the answer was computed for
         * @param candidates the candidates, in the application's order
         */
        record Candidates(long revision, List<String> candidates) implements Msg {

            /**
             * Rejects a negative revision and drops a missing entry rather than storing it.
             */
            public Candidates {
                if (revision < 0) {
                    throw new IllegalArgumentException("revision must not be negative");
                }
                List<String> kept = new ArrayList<>();
                for (String candidate : candidates == null ? List.<String>of() : candidates) {
                    if (candidate != null) {
                        kept.add(candidate);
                    }
                }
                candidates = List.copyOf(kept);
            }
        }

        /**
         * Replaces the draft with the selected candidate.
         */
        record AcceptSuggestion() implements Msg {}

        /**
         * Selects the next matching candidate, wrapping past the last.
         */
        record NextSuggestion() implements Msg {}

        /**
         * Selects the previous matching candidate, wrapping before the first.
         */
        record PreviousSuggestion() implements Msg {}

        /**
         * Requests submission; the application decides what that means.
         */
        record Submit() implements Msg {}

        /**
         * Copies the selection, or the whole draft when nothing is selected.
         */
        record Copy() implements Msg {}

        /**
         * Requests the internal clipboard buffer; the application answers with {@link Paste}.
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
         * The user asked to submit text the policy refuses.
         *
         * @param validation the refusal, with its reason
         */
        record Rejected(Validation validation) implements Output {}

        /**
         * The draft's validation changed.
         *
         * @param validation the new answer
         */
        record ValidationChanged(Validation validation) implements Output {}

        /**
         * The application is asked for candidates for this draft.
         *
         * @param revision the draft revision to answer for
         * @param text the draft text to complete
         */
        record SuggestionsRequested(long revision, String text) implements Output {}

        /**
         * The user asked to copy this text (empty when copying is not permitted).
         *
         * <p>Where it goes is the parent's decision: {@code Command.copy} writes the system clipboard,
         * {@code Command.copy(ClipboardSelection, ...)} names the primary selection instead. The copy
         * result the command reports is the only evidence about the transport - OSC 52 has no
         * acknowledgment - so this value carries the text and nothing about what happened to it.</p>
         *
         * @param text the copied text, empty when copying is refused
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
     * What one reduction produced: the next state and the outputs it asks the application for.
     *
     * @param state the next state
     * @param outputs the outputs, in the order they were produced
     */
    record Result(Model state, List<Output> outputs) {

        Result {
            if (state == null) {
                throw new IllegalArgumentException("state is required");
            }
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        static Result unchanged(Model state) {
            return new Result(state, List.of());
        }
    }

    private final TextInputConfig config;
    private final KeyMap<Msg> keys;
    private final Editor draft;
    private final TextInputEditing textInputEditing;

    /**
     * Creates the field with the default keys for its policy.
     *
     * @param config the immutable policy; null is {@link TextInputConfig#defaults()}
     */
    public TextInput(TextInputConfig config) {
        this(config, null);
    }

    /**
     * Creates the field with its own keys.
     *
     * @param config the immutable policy; null is {@link TextInputConfig#defaults()}
     * @param keys the actions this field handles, matched while it is focused; null is
     *     {@link #defaultKeys(TextInputConfig)}
     */
    public TextInput(TextInputConfig config, KeyMap<Msg> keys) {
        this.config = config == null ? TextInputConfig.defaults() : config;
        this.keys = keys == null ? TextInputKeys.defaultKeys(this.config) : keys;
        // The editing machinery is the editor's, configured for one row: the grapheme-safe length
        // cap, the paste policy, the mask and whether copying is allowed all come from it, so a
        // field and an editor configured the same way cannot disagree about any of them.
        this.draft = new Editor(TextInputRendering.editorConfig(this.config), KeyMap.empty());
        this.textInputEditing = new TextInputEditing(draft);
    }

    /**
     * The default actions for one policy.
     *
     * <p>Enter submits. Tab accepts a candidate or falls through to the parent when none is
     * available. Next/previous fall through when fewer than two candidates match.</p>
     *
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys(TextInputConfig config) {
        return TextInputKeys.defaultKeys(config);
    }

    /**
     * The actions this field handles.
     *
     * @return the key map
     */
    public KeyMap<Msg> keys() {
        return keys;
    }

    /**
     * The same state with its draft validated by this field's policy.
     *
     * <p>A model built with {@link Model#of(String)}, or given a different draft by its
     * application, carries no validation of its own; this runs the policy now rather than waiting
     * for the first edit.</p>
     *
     * @param state the state to validate; required
     * @return the state carrying the policy's answer for its draft
     */
    public Model validated(Model state) {
        Objects.requireNonNull(state, "state");
        return state.withValidation(TextInputEditing.validate(config, state.text()));
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Model empty = Model.empty();
        return Update.next(
                new Model(empty.draft(), TextInputEditing.validate(config, empty.text()), Suggestions.none(), 0));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        Result result = textInputEditing.reduce(model, config, message);
        // The application owns focus: an action edits the draft, never where the cursor of the
        // application's list of fields is. A reduction that changed nothing keeps the instance, so
        // the runtime's unchanged-reference repaint rule stays meaningful.
        Model next = result.state().withFocus(model.focused());
        if (next.equals(model)) {
            next = model;
        }
        return new Update<>(next, Command.none(), result.outputs());
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        return scene(model);
    }

    /**
     * The field's row, with the field itself as the leaf that answers for it.
     */
    private Element<Msg> scene(Model model) {
        return Elements.stack(
                ComputedBlock.of(
                        1,
                        cols -> TextInputRendering.render(
                                TextInputRendering.painted(model, config, cols), model, config)),
                new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The field's keys: whatever {@link #actionFor} maps, including the gestures it edits with.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        actionFor(model, key).ifPresent(emit);
    }

    /**
     * A paste is text to insert, never the bindings its characters happen to spell.
     */
    @Override
    public void onInput(InputEvent event, Model model, Consumer<Msg> emit) {
        actionFor(model, event).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The caret the field places, in screen cells.
     *
     * <p>Placement uses the painted prompt, scroll offset, and draft projection. A field that
     * paints its caret reports no additional cursor.</p>
     */
    @Override
    public Cursor cursor(Rect bounds, Model model) {
        return TextInputRendering.cursor(bounds, model, config);
    }

    /**
     * A press places the caret, a second press selects the word and a drag extends the selection.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        TextInputRendering.pointerAction(
                        model,
                        config,
                        new io.github.dhoard.braxton.layout.Size(bounds.rows(), bounds.cols()),
                        pointer.localTo(bounds.row(), bounds.col()))
                .ifPresent(emit);
    }

    /**
     * The candidates that match the draft, in the order they were supplied.
     *
     * <p>Empty drafts offer nothing. Matching requires a prefix ending at a grapheme boundary;
     * accepting a candidate inserts the whole value.</p>
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the matching candidates, possibly empty
     */
    public static List<String> matchedCandidates(Model state, TextInputConfig config) {
        return TextInputEditing.matchedCandidates(state, config);
    }

    /**
     * The candidate that accepting would insert, or empty when there is nothing to accept.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the selected candidate, or empty
     */
    public static Optional<String> selectedCandidate(Model state, TextInputConfig config) {
        return TextInputEditing.selectedCandidate(state, config);
    }

    /**
     * The ghost suffix of the selected candidate: what accepting it would add after the draft.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the suffix, or empty
     */
    public static String suggestedSuffix(Model state, TextInputConfig config) {
        return TextInputEditing.suggestedSuffix(state, config);
    }

    /**
     * The columns scrolled off the field's left edge for a given allocation.
     *
     * <p>This is the window the field paints rather than a separate policy: what a projection that
     * has to place something beside the field needs, without painting it.</p>
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @param cols the columns the field is given, or zero to use the policy's declared width alone
     * @return the columns scrolled off, zero when the whole draft fits
     */
    public static int scrollOffset(Model state, TextInputConfig config, int cols) {
        return TextInputEditing.scrollOffset(state, config, cols);
    }

    /**
     * The row the field paints, as styled text, for the width the policy declares.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @param focused whether the caret is painted into the text
     * @return the painted row
     */
    public static StyledText render(Model state, TextInputConfig config, boolean focused) {
        return render(state, config, focused, 0);
    }

    /**
     * The row the field paints, as styled text, for an explicit width.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @param focused whether the caret is painted into the text
     * @param cols the columns the field is given, or zero to use the policy's declared width alone
     * @return the painted row
     */
    public static StyledText render(Model state, TextInputConfig config, boolean focused, int cols) {
        Objects.requireNonNull(state, "state");
        Model shown = state.withFocus(focused);
        return TextInputRendering.render(TextInputRendering.painted(shown, config, cols), shown, config);
    }

    // ── projection ───────────────────────────────────────────────────────────

    // ── input mapping ────────────────────────────────────────────────────────

    /**
     * Maps one normalized input event to a field action, or empty when the field leaves it alone.
     *
     * <p>This is the mapping the field's own view uses, exposed so a component that composes the
     * field - a filter bar, a form row - routes exactly the gestures this policy defines instead of
     * restating them: a configured action is matched before the printable text its gesture carries,
     * and a completion gesture with nothing to complete is left unhandled so the same key reaches
     * the component around the field. The state is part of the question because that completion
     * answer depends on it.</p>
     *
     * @param state the field state the event applies to; required
     * @param event the normalized input event
     * @param config the field's policy; null is {@link TextInputConfig#defaults()}
     * @return the field message, or empty when the field leaves the event alone
     */
    public static Optional<Msg> actionFor(Model state, InputEvent event, TextInputConfig config) {
        return TextInputActions.actionFor(state, event, config);
    }

    /**
     * Maps one event against the actions this instance handles.
     */
    private Optional<Msg> actionFor(Model state, InputEvent event) {
        return TextInputActions.actionFor(state, event, config, keys);
    }
}
