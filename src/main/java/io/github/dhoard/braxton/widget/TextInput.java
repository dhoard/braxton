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
import io.github.dhoard.braxton.effect.Command;
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
import io.github.dhoard.braxton.view.Cursor;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * A single-line text field: one row of editable text, with validation and completion.
 *
 * <p>The field is a component in the same shape as {@link Editor}: nested immutable {@code Model},
 * sealed {@code Msg}/{@code Output}, and immutable {@code Config}. Editing is not a second
 * implementation - the draft, caret, selection, word motion, grapheme-safe length cap and paste
 * policy are the editor's own, reached through an {@link Editor} the field owns. What the field
 * adds is what makes a draft one row: a line break becomes a space, and text too wide for the field
 * is scrolled instead of wrapped.</p>
 *
 * <p>Everything the field can decide is model data or a semantic {@link Output}: submitting,
 * rejecting, copying, requesting a paste and asking for completions are intents the owning
 * application consumes. Nothing here performs I/O, and a message that changes nothing returns the
 * incoming model instance.</p>
 *
 * <p>Validation is a pure {@link Validator} run after every edit, whose {@link Validation} the model
 * carries. Invalid text stays editable; what submission does with it is {@link SubmitPolicy}, and
 * the default refuses it with a {@link Output.Rejected}.</p>
 *
 * <p>Completion is application data: the field publishes the draft revision it wants completions
 * for as {@link Output.SuggestionsRequested}, and accepts an answer as {@link Msg.Candidates}. An
 * answer that names another revision is ignored, so a provider that outlived the draft it was asked
 * about cannot replace the candidates of the text the user is looking at.</p>
 *
 * <h2>Pointer input</h2>
 *
 * <p>A click places the caret ({@link Msg.PlaceCaret}), a drag extends the selection
 * ({@link Msg.ExtendSelection}) and a second click selects the word ({@link Msg.SelectWord}). The
 * cell is counted in the row the field painted - its prompt, whatever was scrolled off and the mask
 * glyphs - and converted back to a draft offset, so a click on a scrolled or masked field lands on
 * the cluster it covered. A {@link Editor.Config.Masking#HIDDEN} field paints no glyphs, so a click
 * on it places no caret.</p>
 */
public final class TextInput implements Component<TextInput.Model, TextInput.Msg, TextInput.Output> {

    /** The block glyph painted at the caret. */
    public static final String CURSOR = "\u2588";

    /**
     * The largest window. The cell arithmetic adds the prompt's columns to it, so the sum stays far
     * inside the {@code int} range and a field with no declared width can never overflow into an
     * early scroll.
     */
    private static final int UNBOUNDED = Integer.MAX_VALUE / 2;

    /** The outcome of validating one draft. */
    public sealed interface Validation {

        /** The draft passes the policy. */
        record Valid() implements Validation {}

        /**
         * The draft does not pass the policy.
         *
         * @param message the reason, for the user to read; never blank
         */
        record Invalid(String message) implements Validation {

            /** Rejects a blank reason, which would show as nothing at all. */
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

    /** What submission does with a draft the validator refuses. */
    public enum SubmitPolicy {
        /** The draft does not submit; the attempt is reported as {@link Output.Rejected}. */
        VALID_ONLY,
        /** Any draft submits, the refused ones included. */
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
     */
    public record Completion(boolean enabled, boolean ignoreCase) {

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
            return new Completion(value, ignoreCase);
        }

        /**
         * The same policy with case-sensitive or case-insensitive matching.
         *
         * @param value whether case is ignored
         * @return the new policy
         */
        public Completion withIgnoreCase(boolean value) {
            return new Completion(enabled, value);
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

        /** Rejects a negative width and prepares the one-row prompt and placeholder. */
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

        /** Rejects a negative revision and prepares the candidate list. */
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

        /** Drops missing entries and keeps the first of each duplicate, in order. */
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

        /** Rejects a negative revision and flattens the draft to one row. */
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

        /** A draft is one row: the one break the editor prepares becomes the space a row keeps. */
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

    /** Editing, completion and intent messages. */
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

            /** An insertion never carries null. */
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

            /** A paste payload never carries null. */
            public Paste {
                text = text == null ? "" : text;
            }
        }

        /** Replaces the draft with an empty one. */
        record Clear() implements Msg {}

        /** Deletes the selection, or the cluster before the caret. */
        record Backspace() implements Msg {}

        /** Deletes the selection, or the cluster after the caret. */
        record Delete() implements Msg {}

        /** Moves the caret one cluster left. */
        record MoveLeft() implements Msg {}

        /** Moves the caret one cluster right. */
        record MoveRight() implements Msg {}

        /** Moves the caret to the start of the word before it. */
        record MoveWordLeft() implements Msg {}

        /** Moves the caret past the end of the word after it. */
        record MoveWordRight() implements Msg {}

        /** Moves the caret to the start of the draft, which is the start of its only row. */
        record MoveStart() implements Msg {}

        /** Moves the caret to the end of the draft, which is the end of its only row. */
        record MoveEnd() implements Msg {}

        /** Deletes the selection, or the run of blanks and the word before the caret. */
        record DeleteWordBackward() implements Msg {}

        /** Deletes the selection, or the word after the caret and the blanks behind it. */
        record DeleteWordForward() implements Msg {}

        /**
         * Extends the selection by one navigation action, keeping the anchor where it is.
         *
         * @param move the navigation action to apply from the caret
         */
        record Extend(Msg move) implements Msg {

            /** Rejects a missing action and one that is not a navigation. */
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

        /** Selects the whole draft. */
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

            /** Rejects a negative revision and drops a missing entry rather than storing it. */
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

        /** Replaces the draft with the selected candidate. */
        record AcceptSuggestion() implements Msg {}

        /** Selects the next matching candidate, wrapping past the last. */
        record NextSuggestion() implements Msg {}

        /** Selects the previous matching candidate, wrapping before the first. */
        record PreviousSuggestion() implements Msg {}

        /** Requests submission; the application decides what that means. */
        record Submit() implements Msg {}

        /** Copies the selection, or the whole draft when nothing is selected. */
        record Copy() implements Msg {}

        /** Requests the internal clipboard buffer; the application answers with {@link Paste}. */
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
         * acknowledgement - so this value carries the text and nothing about what happened to it.</p>
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
     * Immutable editing and presentation policy.
     *
     * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
     * @param masking how the draft is displayed and whether it may be copied
     * @param appearance what the field paints around the draft, and how wide it is
     * @param completion whether and how the field completes from supplied candidates
     * @param validator the pure policy the draft is validated with after every edit
     * @param submitPolicy what submission does with a draft the validator refuses
     * @param copyEnabled whether a visible draft may be copied
     * @param copyKey the key gesture that copies
     * @param pasteKey the key gesture that requests a paste
     */
    public record Config(
            OptionalInt maxLength,
            Editor.Config.Masking masking,
            Appearance appearance,
            Completion completion,
            Validator validator,
            SubmitPolicy submitPolicy,
            boolean copyEnabled,
            Key copyKey,
            Key pasteKey) {

        /** The copy gesture a field uses unless it is configured otherwise. */
        public static final Key DEFAULT_COPY_KEY = Editor.Config.DEFAULT_COPY_KEY;

        /** The paste gesture a field uses unless it is configured otherwise. */
        public static final Key DEFAULT_PASTE_KEY = Editor.Config.DEFAULT_PASTE_KEY;

        /** Rejects an inconsistent policy and substitutes defaults for what is missing. */
        public Config {
            maxLength = maxLength == null ? OptionalInt.empty() : maxLength;
            maxLength.ifPresent(limit -> {
                if (limit < 0) {
                    throw new IllegalArgumentException("maxLength must not be negative");
                }
            });
            masking = masking == null ? Editor.Config.Masking.NONE : masking;
            appearance = appearance == null ? Appearance.plain() : appearance;
            completion = completion == null ? Completion.disabled() : completion;
            validator = validator == null ? Validator.valid() : validator;
            submitPolicy = submitPolicy == null ? SubmitPolicy.VALID_ONLY : submitPolicy;
            copyKey = copyKey == null ? DEFAULT_COPY_KEY : copyKey;
            pasteKey = pasteKey == null ? DEFAULT_PASTE_KEY : pasteKey;
        }

        /**
         * The default policy: an unlimited, visible, copyable field that accepts every draft, with
         * no prompt, no placeholder and no completion.
         *
         * @return the default configuration
         */
        public static Config defaults() {
            return new Config(
                    OptionalInt.empty(),
                    Editor.Config.Masking.NONE,
                    Appearance.plain(),
                    Completion.disabled(),
                    Validator.valid(),
                    SubmitPolicy.VALID_ONLY,
                    true,
                    DEFAULT_COPY_KEY,
                    DEFAULT_PASTE_KEY);
        }

        /**
         * The same policy with a length limit in UTF-16 units.
         *
         * @param limit the maximum draft length; never negative
         * @return the new configuration
         */
        public Config withMaxLength(int limit) {
            return new Config(
                    OptionalInt.of(limit),
                    masking,
                    appearance,
                    completion,
                    validator,
                    submitPolicy,
                    copyEnabled,
                    copyKey,
                    pasteKey);
        }

        /**
         * The same policy with no length limit.
         *
         * @return the new configuration
         */
        public Config withUnlimitedLength() {
            return new Config(
                    OptionalInt.empty(),
                    masking,
                    appearance,
                    completion,
                    validator,
                    submitPolicy,
                    copyEnabled,
                    copyKey,
                    pasteKey);
        }

        /**
         * The same policy with a different masking policy.
         *
         * @param value how the draft is displayed and copied
         * @return the new configuration
         */
        public Config withMasking(Editor.Config.Masking value) {
            return new Config(
                    maxLength, value, appearance, completion, validator, submitPolicy, copyEnabled, copyKey, pasteKey);
        }

        /**
         * The same policy with a different presentation.
         *
         * @param value what the field paints around its draft; null paints none
         * @return the new configuration
         */
        public Config withAppearance(Appearance value) {
            return new Config(
                    maxLength,
                    masking,
                    value == null ? Appearance.plain() : value,
                    completion,
                    validator,
                    submitPolicy,
                    copyEnabled,
                    copyKey,
                    pasteKey);
        }

        /**
         * The same policy with a different completion policy.
         *
         * @param value the completion policy; null turns completion off
         * @return the new configuration
         */
        public Config withCompletion(Completion value) {
            return new Config(
                    maxLength,
                    masking,
                    appearance,
                    value == null ? Completion.disabled() : value,
                    validator,
                    submitPolicy,
                    copyEnabled,
                    copyKey,
                    pasteKey);
        }

        /**
         * The same policy with a different validator.
         *
         * @param value the validation policy; null accepts every draft
         * @return the new configuration
         */
        public Config withValidator(Validator value) {
            return new Config(
                    maxLength,
                    masking,
                    appearance,
                    completion,
                    value == null ? Validator.valid() : value,
                    submitPolicy,
                    copyEnabled,
                    copyKey,
                    pasteKey);
        }

        /**
         * The same policy with a different submission policy.
         *
         * @param value what submission does with a refused draft; null refuses it
         * @return the new configuration
         */
        public Config withSubmitPolicy(SubmitPolicy value) {
            return new Config(
                    maxLength,
                    masking,
                    appearance,
                    completion,
                    validator,
                    value == null ? SubmitPolicy.VALID_ONLY : value,
                    copyEnabled,
                    copyKey,
                    pasteKey);
        }

        /**
         * The same policy with copying allowed or refused.
         *
         * @param value whether a visible draft may be copied
         * @return the new configuration
         */
        public Config withCopyEnabled(boolean value) {
            return new Config(
                    maxLength, masking, appearance, completion, validator, submitPolicy, value, copyKey, pasteKey);
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
                    maxLength, masking, appearance, completion, validator, submitPolicy, copyEnabled, copy, paste);
        }

        /**
         * The same policy at a different width.
         *
         * @param columns the columns the field paints in; zero uses the space it is given
         * @return the new configuration
         */
        public Config withWidth(int columns) {
            return withAppearance(appearance.withWidth(columns));
        }

        /**
         * The same policy with a different prompt.
         *
         * @param value the label painted before the draft
         * @param style the style applied to it
         * @return the new configuration
         */
        public Config withPrompt(String value, Style style) {
            return withAppearance(appearance.withPrompt(value, style));
        }

        /**
         * The same policy with a different placeholder.
         *
         * @param value the text painted in place of an empty draft
         * @param style the style applied to it
         * @return the new configuration
         */
        public Config withPlaceholder(String value, Style style) {
            return withAppearance(appearance.withPlaceholder(value, style));
        }

        /**
         * The same policy with different draft styles.
         *
         * @param text the style applied to the draft
         * @param selection the style applied to the selection
         * @param suggestion the style applied to the ghost suffix
         * @return the new configuration
         */
        public Config withStyles(Style text, Style selection, Style suggestion) {
            return withAppearance(appearance.withStyles(text, selection, suggestion));
        }

        /**
         * The same policy with a different caret.
         *
         * @param value how the caret is drawn; null keeps the painted block
         * @return the new configuration
         */
        public Config withCursor(Editor.CursorPolicy value) {
            return withAppearance(appearance.withCursor(value));
        }

        /**
         * Whether copying is allowed under the masking policy.
         *
         * @return whether copying is permitted
         */
        public boolean canCopy() {
            return copyEnabled && masking == Editor.Config.Masking.NONE;
        }
    }

    /**
     * What one reduction produced: the next state and the outputs it asks the application for.
     *
     * @param state the next state
     * @param outputs the outputs, in the order they were produced
     */
    private record Result(Model state, List<Output> outputs) {

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

    /**
     * One painted field: the slice of its row that is visible, and what dressed it.
     *
     * <p>The projection the slice came from is flattened into offsets here rather than kept as the
     * value it was returned in, so nothing below this widget's public surface leaks an internal
     * type through an accessor.</p>
     *
     * @param display the text the row paints
     * @param suffix the ghost suffix of the selected candidate, or empty
     * @param blockPainted whether the caret is part of the painted text
     * @param available the cells the field's draft may occupy
     * @param caret the caret's offset in {@code display}, which a mask moves
     * @param start the first display offset the window paints
     * @param end the offset just past the last one it paints
     * @param scroll the columns scrolled off the row's left edge
     * @param caretColumn the caret's cell, counted from the row's own first column
     */
    private record Painted(
            String display,
            String suffix,
            boolean blockPainted,
            int available,
            int caret,
            int start,
            int end,
            int scroll,
            int caretColumn) {}

    private final Config config;
    private final KeyMap<Msg> keys;
    private final Editor draft;

    /**
     * Creates the field with the default keys for its policy.
     *
     * @param config the immutable policy; null is {@link Config#defaults()}
     */
    public TextInput(Config config) {
        this(config, null);
    }

    /**
     * Creates the field with its own keys.
     *
     * @param config the immutable policy; null is {@link Config#defaults()}
     * @param keys the actions this field handles, matched while it is focused; null is
     *     {@link #defaultKeys(Config)}
     */
    public TextInput(Config config, KeyMap<Msg> keys) {
        this.config = config == null ? Config.defaults() : config;
        this.keys = keys == null ? defaultKeys(this.config) : keys;
        // The editing machinery is the editor's, configured for one row: the grapheme-safe length
        // cap, the paste policy, the mask and whether copying is allowed all come from it, so a
        // field and an editor configured the same way cannot disagree about any of them.
        this.draft = new Editor(editorConfig(this.config), KeyMap.empty());
    }

    /** The editor policy this field's policy derives. */
    private static Editor.Config editorConfig(Config opts) {
        Editor.Config base = Editor.Config.defaults()
                .withVisibleLines(1)
                .withMasking(opts.masking())
                .withCopyEnabled(opts.copyEnabled())
                .withKeys(opts.copyKey(), opts.pasteKey());
        return opts.maxLength().isPresent()
                ? base.withMaxLength(opts.maxLength().getAsInt())
                : base;
    }

    /**
     * The default actions for one policy.
     *
     * <p>Enter submits, because a single-line field has no line to break. Tab accepts the selected
     * candidate, and with nothing to accept the gesture is not consumed: it falls through to
     * whatever the surrounding application binds it to, which is how a form moves focus with the
     * same key that completes a field. Next and previous fall through as well when fewer than two
     * candidates match.</p>
     *
     * @param config the policy; null is {@link Config#defaults()}
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys(Config config) {
        Config opts = config == null ? Config.defaults() : config;
        return KeyMap.of(
                KeyMap.group(
                        "Editing",
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
                        KeyAction.<Msg>of("word-left", new Msg.MoveWordLeft(), "", "word left", "ctrl+left", "alt+b")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "word-right", new Msg.MoveWordRight(), "", "word right", "ctrl+right", "alt+f")
                                .withCompact(false),
                        KeyAction.<Msg>of("start", new Msg.MoveStart(), "home", "line start", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("end", new Msg.MoveEnd(), "end", "line end", "end")
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
                                        "select-start",
                                        new Msg.Extend(new Msg.MoveStart()),
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
                                .withCompact(false)),
                KeyMap.group(
                        "Completion",
                        KeyAction.<Msg>of("accept-suggestion", new Msg.AcceptSuggestion(), "tab", "complete", "tab"),
                        KeyAction.<Msg>of(
                                "next-suggestion", new Msg.NextSuggestion(), "ctrl+n", "next completion", "ctrl+n"),
                        KeyAction.<Msg>of(
                                "previous-suggestion",
                                new Msg.PreviousSuggestion(),
                                "ctrl+p",
                                "previous completion",
                                "ctrl+p")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("submit", new Msg.Submit(), "enter", "submit", "enter", "alt+enter"),
                        KeyAction.<Msg>of("copy", new Msg.Copy(), "", "copy", List.of(opts.copyKey())),
                        KeyAction.<Msg>of("paste", new Msg.PasteRequest(), "", "paste", List.of(opts.pasteKey()))));
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
        return state.withValidation(validate(config, state.text()));
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Model empty = Model.empty();
        return Update.next(new Model(empty.draft(), validate(config, empty.text()), Suggestions.none(), 0));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        Result result = reduce(model, config, message);
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
    public View<Msg> view(Model model, ViewContext context) {
        return paint(ElementId.of("text-input"), model, Function.identity());
    }

    /** Applies one action. Never performs I/O, never mutates the input state and never swallows. */
    private Result reduce(Model state, Config opts, Msg message) {
        return switch (message) {
            case Msg.Insert insert -> edit(state, opts, new Editor.Msg.Insert(LineBreaks.singleRow(insert.text())));
            case Msg.Paste paste -> edit(state, opts, new Editor.Msg.Insert(flatPaste(paste.text())));
            case Msg.Backspace backspace -> edit(state, opts, new Editor.Msg.Backspace());
            case Msg.Delete delete -> edit(state, opts, new Editor.Msg.Delete());
            case Msg.MoveLeft left -> edit(state, opts, new Editor.Msg.MoveLeft());
            case Msg.MoveRight right -> edit(state, opts, new Editor.Msg.MoveRight());
            case Msg.MoveWordLeft left -> edit(state, opts, new Editor.Msg.MoveWordLeft());
            case Msg.MoveWordRight right -> edit(state, opts, new Editor.Msg.MoveWordRight());
            case Msg.MoveStart start -> edit(state, opts, new Editor.Msg.MoveDocumentStart());
            case Msg.MoveEnd end -> edit(state, opts, new Editor.Msg.MoveDocumentEnd());
            case Msg.DeleteWordBackward backward -> edit(state, opts, new Editor.Msg.DeleteWordBackward());
            case Msg.DeleteWordForward forward -> edit(state, opts, new Editor.Msg.DeleteWordForward());
            case Msg.Extend extend -> edit(state, opts, new Editor.Msg.Extend(navigation(extend.move())));
            case Msg.SelectAll all -> edit(state, opts, new Editor.Msg.SelectAll());
            case Msg.PlaceCaret place -> edit(state, opts, new Editor.Msg.PlaceCaret(place.offset()));
            case Msg.ExtendSelection extend -> edit(state, opts, new Editor.Msg.ExtendSelection(extend.offset()));
            case Msg.SelectWord word -> edit(state, opts, new Editor.Msg.SelectWord(word.offset()));
            case Msg.Clear clear -> clear(state, opts);
            case Msg.Candidates candidates -> candidates(state, candidates);
            case Msg.AcceptSuggestion accept -> accept(state, opts);
            case Msg.NextSuggestion next -> cycle(state, opts, 1);
            case Msg.PreviousSuggestion previous -> cycle(state, opts, -1);
            case Msg.Submit submit -> submit(state, opts);
            case Msg.Copy copy ->
                new Result(state, List.of(new Output.Copy(opts.canCopy() ? Editor.copyText(state.draft()) : "")));
            case Msg.PasteRequest request -> new Result(state, List.of(new Output.PasteRequest()));
        };
    }

    /** A paste payload as one row: sanitized exactly as the editor sanitizes it, then flattened. */
    private static String flatPaste(String text) {
        return LineBreaks.singleRow(PasteSanitizer.sanitize(text, PasteMode.NORMAL));
    }

    /** The editor action a navigation message names, for a selection extension that wraps one. */
    private static Editor.Msg navigation(Msg move) {
        return switch (move) {
            case Msg.MoveLeft left -> new Editor.Msg.MoveLeft();
            case Msg.MoveRight right -> new Editor.Msg.MoveRight();
            case Msg.MoveWordLeft left -> new Editor.Msg.MoveWordLeft();
            case Msg.MoveWordRight right -> new Editor.Msg.MoveWordRight();
            case Msg.MoveStart start -> new Editor.Msg.MoveDocumentStart();
            case Msg.MoveEnd end -> new Editor.Msg.MoveDocumentEnd();
            default -> throw new IllegalArgumentException("only a navigation action extends a selection");
        };
    }

    /** One editing action, followed by whatever the resulting draft asks for. */
    private Result edit(Model state, Config opts, Editor.Msg action) {
        Editor.Model edited = draft.update(state.draft(), action).model();
        if (edited.equals(state.draft())) {
            return Result.unchanged(state);
        }
        return advanced(state, opts, edited);
    }

    /**
     * The state after a draft change: a new revision, the policy's answer for it, and the outputs an
     * application acts on - the validation if it changed, and a request for candidates for the
     * revision the draft is now at.
     */
    private static Result advanced(Model state, Config opts, Editor.Model edited) {
        long revision = state.revision() + 1;
        Validation validation = validate(opts, edited.text());
        List<Output> outputs = new ArrayList<>(2);
        if (!validation.equals(state.validation())) {
            outputs.add(new Output.ValidationChanged(validation));
        }
        if (completionActive(opts)) {
            outputs.add(new Output.SuggestionsRequested(revision, edited.text()));
        }
        // Editing re-opens the candidate list at its first entry: the prefix moved, so the candidate
        // the user had cycled to is not the one the field would have shown for this draft.
        Suggestions suggestions = state.suggestions().withSelected(0);
        return new Result(new Model(edited, validation, suggestions, revision), outputs);
    }

    /** Clears the draft, which is an edit like any other. */
    private static Result clear(Model state, Config opts) {
        Editor.Model empty = new Editor.Model("", 0, 0, 0, state.focused());
        return empty.equals(state.draft()) ? Result.unchanged(state) : advanced(state, opts, empty);
    }

    /**
     * Accepts the application's candidate list, unless it answers for a draft that no longer
     * exists.
     */
    private static Result candidates(Model state, Msg.Candidates message) {
        if (message.revision() != state.revision()) {
            return Result.unchanged(state);
        }
        Suggestions supplied = new Suggestions(message.revision(), message.candidates(), 0);
        return supplied.equals(state.suggestions())
                ? Result.unchanged(state)
                : new Result(state.withSuggestions(supplied), List.of());
    }

    /** Replaces the draft with the selected candidate, through the same editing path as typing. */
    private Result accept(Model state, Config opts) {
        Optional<String> candidate = selectedCandidate(state, opts);
        if (candidate.isEmpty()) {
            return Result.unchanged(state);
        }
        Editor.Model selected =
                draft.update(state.draft(), new Editor.Msg.SelectAll()).model();
        Editor.Model accepted =
                draft.update(selected, new Editor.Msg.Insert(candidate.get())).model();
        return accepted.equals(state.draft()) ? Result.unchanged(state) : advanced(state, opts, accepted);
    }

    /** Selects the next or previous matching candidate, wrapping around the match set. */
    private static Result cycle(Model state, Config opts, int direction) {
        List<String> matched = matchedCandidates(state, opts);
        if (matched.size() < 2) {
            return Result.unchanged(state);
        }
        int selected = Math.floorMod(state.suggestions().selected() + direction, matched.size());
        return new Result(state.withSuggestions(state.suggestions().withSelected(selected)), List.of());
    }

    /**
     * Submits the draft, validating it first so a model that was built rather than typed into cannot
     * submit on a stale answer.
     */
    private static Result submit(Model state, Config opts) {
        Validation validation = validate(opts, state.text());
        Model validated = state.withValidation(validation);
        if (validation.valid() || opts.submitPolicy() == SubmitPolicy.ALLOW_INVALID) {
            return new Result(validated, List.of(new Output.Submit(state.text())));
        }
        return new Result(validated, List.of(new Output.Rejected(validation)));
    }

    /** Runs the policy, refusing a policy that answers nothing. */
    private static Validation validate(Config opts, String text) {
        Validation result = opts.validator().validate(text);
        if (result == null) {
            throw new IllegalStateException("the text input validator returned no validation");
        }
        return result;
    }

    /**
     * Whether completion runs at all: it has to be asked for, and a masked draft never completes.
     */
    private static boolean completionActive(Config opts) {
        return opts.completion().enabled() && opts.masking() == Editor.Config.Masking.NONE;
    }

    /**
     * The candidates that match the draft, in the order they were supplied.
     *
     * <p>A prefix completes only once there is something to complete, so an empty field shows its
     * placeholder rather than the first candidate. Matching is a prefix comparison, which is what
     * makes it cluster-safe: a prefix that ends inside a cluster it typed cannot match, and a
     * candidate is only ever accepted whole.</p>
     *
     * @param state the field state; required
     * @param config the policy; null is {@link Config#defaults()}
     * @return the matching candidates, possibly empty
     */
    public static List<String> matchedCandidates(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        if (!completionActive(opts) || state.isEmpty()) {
            return List.of();
        }
        String prefix = state.text();
        List<String> matched = new ArrayList<>();
        for (String candidate : state.suggestions().candidates()) {
            boolean matches = opts.completion().ignoreCase()
                    ? candidate.regionMatches(true, 0, prefix, 0, prefix.length())
                    : candidate.startsWith(prefix);
            if (matches) {
                matched.add(candidate);
            }
        }
        return List.copyOf(matched);
    }

    /**
     * The candidate that accepting would insert, or empty when there is nothing to accept.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link Config#defaults()}
     * @return the selected candidate, or empty
     */
    public static Optional<String> selectedCandidate(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        List<String> matched = matchedCandidates(state, config);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matched.get(Math.floorMod(state.suggestions().selected(), matched.size())));
    }

    /**
     * The ghost suffix of the selected candidate: what accepting it would add after the draft.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link Config#defaults()}
     * @return the suffix, or empty
     */
    public static String suggestedSuffix(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Optional<String> candidate = selectedCandidate(state, config);
        if (candidate.isEmpty()) {
            return "";
        }
        return candidate
                .get()
                .substring(Math.min(state.text().length(), candidate.get().length()));
    }

    /**
     * The columns scrolled off the field's left edge for a given allocation.
     *
     * <p>This is the window the field paints rather than a separate policy: what a projection that
     * has to place something beside the field needs, without painting it.</p>
     *
     * @param state the field state; required
     * @param config the policy; null is {@link Config#defaults()}
     * @param cols the columns the field is given, or zero to use the policy's declared width alone
     * @return the columns scrolled off, zero when the whole draft fits
     */
    public static int scrollOffset(Model state, Config config, int cols) {
        Objects.requireNonNull(state, "state");
        return painted(state, config, cols).scroll();
    }

    /**
     * The row the field paints, as styled text, for the width the policy declares.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link Config#defaults()}
     * @param focused whether the caret is painted into the text
     * @return the painted row
     */
    public static StyledText render(Model state, Config config, boolean focused) {
        return render(state, config, focused, 0);
    }

    /**
     * The row the field paints, as styled text, for an explicit width.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link Config#defaults()}
     * @param focused whether the caret is painted into the text
     * @param cols the columns the field is given, or zero to use the policy's declared width alone
     * @return the painted row
     */
    public static StyledText render(Model state, Config config, boolean focused, int cols) {
        Objects.requireNonNull(state, "state");
        Model shown = state.withFocus(focused);
        return render(painted(shown, config, cols), shown, config);
    }

    // ── projection ───────────────────────────────────────────────────────────

    /** How a row charges its clusters: a masked row charges one cell, a hidden one charges none. */
    private static VisualRows.Cells paintCells(Config opts) {
        return switch (opts.masking()) {
            case NONE -> VisualRows.Cells.NATURAL;
            case PASSWORD -> VisualRows.Cells.MASKED;
            case HIDDEN -> VisualRows.Cells.HIDDEN;
        };
    }

    /** The prompt as the layout paints it, with its tabs expanded to the same four-column stops. */
    private static String paintedPrompt(Appearance look) {
        return TabStops.expand(StyledText.plain(look.prompt())).plainText();
    }

    /**
     * The cell the prompt leaves open to the draft's first cluster.
     *
     * <p>A prompt ending in a base character and one ending in a tab's space open different cells:
     * the text view continues a stop's space only with the clusters that claim no column of their
     * own, while an ordinary last cluster takes a bare mark into its own cell. The projection the
     * caret is placed from reads the same cell the painting path does.</p>
     */
    private static VisualRows.Open promptOpen(Appearance look) {
        List<String> clusters = TextMeasure.graphemes(paintedPrompt(look));
        if (clusters.isEmpty()) {
            return VisualRows.CLOSED;
        }
        return new VisualRows.Open(
                clusters.get(clusters.size() - 1), look.prompt().endsWith("\t"));
    }

    /**
     * The columns the field paints in.
     *
     * <p>The declared width is what the row scrolls for; the space the view is actually given wins
     * when it is narrower, so a resized terminal scrolls the draft rather than letting the layout
     * wrap it into a second row. A width of zero means the caller knows of none, so the field paints
     * whatever it holds.</p>
     */
    private static int width(Config opts, int allocatedCols) {
        int declared = opts.appearance().width();
        return declared > 0 ? (allocatedCols > 0 ? Math.min(declared, allocatedCols) : declared) : allocatedCols;
    }

    /** Projects the one row the field paints into the window it shows of it. */
    private static Painted painted(Model state, Config config, int allocatedCols) {
        Config opts = config == null ? Config.defaults() : config;
        Appearance look = opts.appearance();
        String display = state.draft().displayText(editorConfig(opts));
        boolean blockPainted = state.focused() && look.cursor().caret() == Editor.Caret.PAINTED;
        int bounded = width(opts, allocatedCols);
        int available = bounded > 0 ? bounded : UNBOUNDED;
        VisualRows.CaretMark mark = !state.focused()
                ? VisualRows.CaretMark.NONE
                : blockPainted ? VisualRows.CaretMark.BLOCK : VisualRows.CaretMark.DECLARED;
        // A masked row paints one glyph per cluster and a hidden row paints none, so the caret's
        // offset in the painted text is not the draft's: the block has to be placed where the glyph
        // it covers was drawn.
        int caret = displayCaret(state, opts);
        VisualRows.Window window = VisualRows.window(
                display,
                TextMeasure.columns(paintedPrompt(look)),
                available,
                paintCells(opts),
                promptOpen(look),
                caret,
                mark);
        // Completion is off for a masked draft, so this is a second guard rather than the only one:
        // the suffix is the candidate's own text, and the mask is what keeps that text off screen.
        String suffix = opts.masking() == Editor.Config.Masking.NONE ? suggestedSuffix(state, opts) : "";
        return new Painted(
                display,
                suffix,
                blockPainted,
                available,
                caret,
                window.start(),
                window.end(),
                window.scroll(),
                window.caretColumn());
    }

    /**
     * The caret's offset in the text the row paints.
     *
     * <p>A visible draft paints its own text, so the offsets are the same. A masked draft paints one
     * glyph per cluster, so the caret's offset is the number of clusters before it; a hidden draft
     * paints nothing, so the caret's offset is the start of the row.</p>
     */
    private static int displayCaret(Model state, Config opts) {
        if (opts.masking() == Editor.Config.Masking.NONE) {
            return state.caret();
        }
        if (opts.masking() == Editor.Config.Masking.HIDDEN) {
            return 0;
        }
        int offset = 0;
        int position = 0;
        for (String cluster : Graphemes.iterate(state.text())) {
            if (position == state.caret()) {
                break;
            }
            position += cluster.length();
            offset++;
        }
        return offset;
    }

    /**
     * Builds the field view.
     *
     * <p>The view declares itself as a {@link View.Sized} subtree, so the row it scrolls and the
     * caret it places come from the geometry its ancestors actually allocate: a field in a narrower
     * pane scrolls instead of wrapping, exactly as one with a narrower declared width would.</p>
     */
    private <Message> View<Message> paint(ElementId id, Model state, Function<Msg, Message> map) {
        boolean focused = state.focused();
        View.InputHandler<Message> handler = event -> actionFor(state, event).map(map);
        View.PointerHandler<Message> pointer =
                (event, region) -> pointerAction(state, config, region, event).map(map);
        boolean paintedCaret = config.appearance().cursor().caret() == Editor.Caret.PAINTED;
        return Views.focus(id, focused, List.of(), handler, pointer, Views.sized(size -> {
            Painted frame = painted(state, config, size.cols());
            View<Message> content = Views.text(render(frame, state, config));
            return focused && !paintedCaret ? Views.cursor(caret(id, frame), content) : content;
        }));
    }

    /**
     * The message a pointer report produces, or empty when the field paints nothing to point at.
     *
     * <p>A press puts the caret where the pointer was, a second press selects the word there, and
     * motion with the left button held extends the selection. The cell is mapped through the window
     * the field painted - its prompt, and whatever was scrolled off - so a click on a scrolled or
     * masked field still lands on the cluster it covered.</p>
     *
     * <p>A hidden field paints no glyphs at all, so there is no cluster under any cell: a click on
     * one places no caret and reports nothing.</p>
     */
    private Optional<Msg> pointerAction(Model state, Config config, Size region, PointerEvent event) {
        boolean dragging = event.isMotion() && event.button() == PointerButton.LEFT;
        if ((!event.isPress() && !dragging) || config.masking() == Editor.Config.Masking.HIDDEN) {
            return Optional.empty();
        }
        int offset = offsetAt(state, config, region, event);
        if (dragging) {
            return Optional.of(new Msg.ExtendSelection(offset));
        }
        return Optional.of(event.clickCount() > 1 ? new Msg.SelectWord(offset) : new Msg.PlaceCaret(offset));
    }

    /**
     * The draft offset one cell of the painted row falls on.
     *
     * <p>The cell is counted in the display text - the masked glyphs, if any - and the index it
     * reaches is then converted back to a draft offset, so a masked field's caret lands where the
     * user clicked instead of one glyph per mask too far along.</p>
     */
    private static int offsetAt(Model state, Config config, Size region, PointerEvent event) {
        Config opts = config == null ? Config.defaults() : config;
        Painted frame = painted(state, opts, region.cols());
        Appearance look = opts.appearance();
        int prompt = TextMeasure.columns(paintedPrompt(look));
        // A click on the prompt, or in the cells scrolled off the row, is the row's first painted
        // cluster: the field scrolls rather than wraps, so there is nothing before it to land on.
        int column = Math.max(0, event.column() - prompt) + frame.scroll();
        int display = VisualRows.indexAt(
                frame.display(), 0, frame.display().length(), column, prompt, paintCells(opts), promptOpen(look));
        return draftOffset(state, opts, display);
    }

    /** The draft offset a painted display index names in a masked or plain row. */
    private static int draftOffset(Model state, Config opts, int displayIndex) {
        if (opts.masking() == Editor.Config.Masking.NONE) {
            return Math.max(0, Math.min(displayIndex, state.text().length()));
        }
        int position = 0;
        for (int index = 0; index < displayIndex; index++) {
            if (position >= state.text().length()) {
                break;
            }
            position = TextMeasure.boundaryAfter(state.text(), position);
        }
        return position;
    }

    /** The caret as cursor intent, at the cell the draft is painted in. */
    private Cursor caret(ElementId id, Painted frame) {
        Appearance look = config.appearance();
        int column = TextMeasure.columns(paintedPrompt(look)) + frame.caretColumn() - frame.scroll();
        return new Cursor(
                look.cursor().shape(),
                look.cursor().mode(),
                look.cursor().strategy(),
                null,
                0,
                Math.max(0, column),
                id.asOptional());
    }

    // ── painting ─────────────────────────────────────────────────────────────

    /** Paints one projected field: its prompt, the visible part of its row, and the ghost suffix. */
    private static StyledText render(Painted frame, Model state, Config config) {
        Config opts = config == null ? Config.defaults() : config;
        Appearance look = opts.appearance();
        StyledText out = StyledText.empty();
        if (!look.prompt().isEmpty()) {
            out = out.append(StyledText.of(look.prompt(), look.promptStyle()));
        }
        Run run = new Run(look.textStyle());
        if (state.isEmpty() && !look.placeholder().isEmpty()) {
            placeholder(run, frame, look);
            return out.append(run.flush());
        }
        String text = frame.display();
        VisualRows.Cells cells = paintCells(opts);
        int prefix = TextMeasure.columns(paintedPrompt(look));
        int scroll = frame.scroll();
        int caret = frame.caret();
        int selectionStart = state.hasSelection() ? state.draft().selectionStart() : -1;
        int selectionEnd = state.hasSelection() ? state.draft().selectionEnd() : -1;
        VisualRows.Open open = promptOpen(look);
        int used = 0;
        int position = frame.start();
        // The window ends before the first cluster that would not fit whole, so every cluster it
        // names is painted in full; only the caret cell can overrun the last cells of the field.
        for (String cluster : Graphemes.iterate(text.substring(frame.start(), frame.end()))) {
            int absolute = prefix + scroll + used;
            boolean atCaret = frame.blockPainted() && position == caret;
            int cost = atCaret
                    ? VisualRows.blockCells(cluster, absolute, cells)
                    : VisualRows.cells(cluster, absolute, cells, open);
            Style style = selectionStart >= 0 && position >= selectionStart && position < selectionEnd
                    ? look.selectionStyle()
                    : look.textStyle();
            int room = frame.available() - used;
            if (cost > room) {
                // Only the caret's own cell can overrun the field's last cells: every cluster the
                // window names fits. Paint the cells that are left and stop.
                if (atCaret) {
                    run.append(CURSOR.repeat(Math.max(0, room)), style);
                }
                used += Math.max(0, room);
                break;
            }
            if (atCaret) {
                run.append(CURSOR.repeat(cost), style);
            } else if ("\t".equals(cluster) && cells == VisualRows.Cells.NATURAL) {
                // A tab is painted as the spaces of its stop, so a scrolled row paints the cells the
                // projection counted: the stop was counted from the row's own start, which the
                // layout cannot see once the beginning has been scrolled off.
                run.append(" ".repeat(cost), style);
            } else {
                run.append(cluster, style);
            }
            used += cost;
            open = atCaret ? VisualRows.CLOSED : VisualRows.openBehind(cluster, cells);
            position += cluster.length();
        }
        if (frame.blockPainted() && caret == text.length() && used < frame.available()) {
            run.append(CURSOR, look.textStyle());
            used++;
        }
        if (!frame.suffix().isEmpty()) {
            run.append(TextMeasure.truncate(frame.suffix(), frame.available() - used), look.suggestionStyle());
        }
        return out.append(run.flush());
    }

    /**
     * Paints the placeholder of an empty draft, with the caret block on its first cell.
     *
     * <p>The placeholder stands in for a draft that has none: it occupies no offset, is never
     * submitted and is never copied.</p>
     */
    private static void placeholder(Run run, Painted frame, Appearance look) {
        String placeholder = look.placeholder();
        Style style = look.placeholderStyle();
        int remaining = frame.available();
        if (remaining <= 0) {
            return;
        }
        if (!frame.blockPainted()) {
            run.append(TextMeasure.truncate(placeholder, remaining), style);
            return;
        }
        int first = Math.max(1, TextMeasure.boundaryAfter(placeholder, 0));
        String head = placeholder.substring(0, Math.min(first, placeholder.length()));
        int painted = Math.min(Math.max(1, TextMeasure.columns(head)), remaining);
        run.append(CURSOR.repeat(painted), style);
        // The block covers the placeholder's first cluster; the rest of it follows.
        String tail = TextMeasure.truncate(placeholder.substring(head.length()), remaining - painted);
        run.append(tail, style);
    }

    /**
     * One painted run: the text and style still being accumulated.
     *
     * <p>Flushing on a style change keeps a styled row to the runs it really needs, and the cell
     * arithmetic is kept beside it because a tab is painted as spaces, whose length is no longer the
     * glyph the row was counted from.</p>
     */
    private static final class Run {

        private final StringBuilder text = new StringBuilder();
        private StyledText out = StyledText.empty();
        private Style style;

        Run(Style initial) {
            this.style = initial == null ? Style.NONE : initial;
        }

        void append(String value, Style valueStyle) {
            if (value == null || value.isEmpty()) {
                return;
            }
            Style next = valueStyle == null ? Style.NONE : valueStyle;
            if (!next.equals(style) && !text.isEmpty()) {
                out = out.append(StyledText.of(text.toString(), style));
                text.setLength(0);
            }
            style = next;
            text.append(value);
        }

        StyledText flush() {
            return out.append(StyledText.of(text.toString(), style));
        }
    }

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
     * @param config the field's policy; null is {@link Config#defaults()}
     * @return the field message, or empty when the field leaves the event alone
     */
    public static Optional<Msg> actionFor(Model state, InputEvent event, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        return actionFor(state, event, opts, defaultKeys(opts));
    }

    /** Maps one event against the actions this instance handles. */
    private Optional<Msg> actionFor(Model state, InputEvent event) {
        return actionFor(state, event, config, keys);
    }

    /** Maps one event against one policy and key map. */
    private static Optional<Msg> actionFor(Model state, InputEvent event, Config config, KeyMap<Msg> keys) {
        if (event instanceof InputEvent.PasteInput paste) {
            return Optional.of(new Msg.Paste(paste.text()));
        }
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        Optional<KeyAction<Msg>> action = keys.actionFor(keyInput.key());
        if (action.isPresent()) {
            Msg message = action.get().message();
            // A completion gesture with nothing to complete is not this field's: leaving it
            // unhandled is what lets the same key reach the form around the field, so a form can
            // move focus with the key that completes a field.
            return completionAction(message) && !completes(state, message, config)
                    ? Optional.empty()
                    : Optional.of(message);
        }
        if (!keyInput.text().isEmpty() && plainModifiers(keyInput.key())) {
            return Optional.of(new Msg.Insert(keyInput.text()));
        }
        return Optional.empty();
    }

    /** Whether an action is one of the completion gestures, which are conditional. */
    private static boolean completionAction(Msg message) {
        return message instanceof Msg.AcceptSuggestion
                || message instanceof Msg.NextSuggestion
                || message instanceof Msg.PreviousSuggestion;
    }

    /**
     * Whether a completion gesture would change anything.
     *
     * <p>Accepting needs a candidate that adds something: a candidate the draft already spells is
     * nothing to accept, so the gesture falls through and a form's {@code tab} still moves on.
     * Cycling needs two matching candidates, because one has nowhere to cycle to.</p>
     */
    private static boolean completes(Model state, Msg message, Config config) {
        if (message instanceof Msg.AcceptSuggestion) {
            return selectedCandidate(state, config)
                    .filter(candidate -> !candidate.equals(state.text()))
                    .isPresent();
        }
        return matchedCandidates(state, config).size() >= 2;
    }

    /** The modifier states a printable key carries its text under: none, or shift alone. */
    private static boolean plainModifiers(Key key) {
        Modifiers modifiers = key.modifiers();
        return modifiers.none() || (modifiers.shift() && !modifiers.ctrl() && !modifiers.alt());
    }
}
