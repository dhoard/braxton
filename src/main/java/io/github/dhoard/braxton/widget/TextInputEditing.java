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
import io.github.dhoard.braxton.internal.text.CompletionEngine;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.widget.TextInput.Model;
import io.github.dhoard.braxton.widget.TextInput.Msg;
import io.github.dhoard.braxton.widget.TextInput.Output;
import io.github.dhoard.braxton.widget.TextInput.Result;
import io.github.dhoard.braxton.widget.TextInput.SubmitPolicy;
import io.github.dhoard.braxton.widget.TextInput.Suggestions;
import io.github.dhoard.braxton.widget.TextInput.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-line editing, validation, and completion transitions.
 */
final class TextInputEditing {
    private final Editor draft;

    TextInputEditing(Editor draft) {
        this.draft = draft;
    }

    /**
     * Applies one action. Never performs I/O, never mutates the input state and never swallows.
     */
    Result reduce(Model state, TextInputConfig opts, Msg message) {
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
                new Result(
                        state, List.of(new Output.Copy(opts.canCopy() ? EditorEditing.copyText(state.draft()) : "")));
            case Msg.PasteRequest request -> new Result(state, List.of(new Output.PasteRequest()));
        };
    }

    /**
     * A paste payload as one row: sanitized exactly as the editor sanitizes it, then flattened.
     */
    static String flatPaste(String text) {
        return LineBreaks.singleRow(PasteSanitizer.sanitize(text, PasteMode.NORMAL));
    }

    /**
     * The editor action a navigation message names, for a selection extension that wraps one.
     */
    static Editor.Msg navigation(Msg move) {
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

    /**
     * One editing action, followed by whatever the resulting draft asks for.
     */
    Result edit(Model state, TextInputConfig opts, Editor.Msg action) {
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
    static Result advanced(Model state, TextInputConfig opts, Editor.Model edited) {
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

    /**
     * Clears the draft, which is an edit like any other.
     */
    static Result clear(Model state, TextInputConfig opts) {
        Editor.Model empty = new Editor.Model("", 0, 0, 0, state.focused());
        return empty.equals(state.draft()) ? Result.unchanged(state) : advanced(state, opts, empty);
    }

    /**
     * Accepts the application's candidate list, unless it answers for a draft that no longer
     * exists.
     */
    static Result candidates(Model state, Msg.Candidates message) {
        if (message.revision() != state.revision()) {
            return Result.unchanged(state);
        }
        Suggestions supplied = new Suggestions(message.revision(), message.candidates(), 0);
        return supplied.equals(state.suggestions())
                ? Result.unchanged(state)
                : new Result(state.withSuggestions(supplied), List.of());
    }

    /**
     * Replaces the draft with the selected candidate, through the same editing path as typing.
     */
    Result accept(Model state, TextInputConfig opts) {
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

    /**
     * Selects the next or previous matching candidate, wrapping around the match set.
     */
    static Result cycle(Model state, TextInputConfig opts, int direction) {
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
    static Result submit(Model state, TextInputConfig opts) {
        Validation validation = validate(opts, state.text());
        Model validated = state.withValidation(validation);
        if (validation.valid() || opts.submitPolicy() == SubmitPolicy.ALLOW_INVALID) {
            return new Result(validated, List.of(new Output.Submit(state.text())));
        }
        return new Result(validated, List.of(new Output.Rejected(validation)));
    }

    /**
     * Runs the policy, refusing a policy that answers nothing.
     */
    static Validation validate(TextInputConfig opts, String text) {
        Validation result = opts.validator().validate(text);
        if (result == null) {
            throw new IllegalStateException("the text input validator returned no validation");
        }
        return result;
    }

    /**
     * Whether completion runs at all: it has to be asked for, and a masked draft never completes.
     */
    static boolean completionActive(TextInputConfig opts) {
        return opts.completion().enabled() && opts.masking() == EditorConfig.Masking.NONE;
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
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the matching candidates, possibly empty
     */
    public static List<String> matchedCandidates(Model state, TextInputConfig config) {
        Objects.requireNonNull(state, "state");
        TextInputConfig opts = config == null ? TextInputConfig.defaults() : config;
        if (!completionActive(opts) || state.isEmpty()) {
            return List.of();
        }
        CompletionEngine.Mode engineMode = switch (opts.completion().mode()) {
            case PREFIX -> CompletionEngine.Mode.PREFIX;
            case SUBSTRING -> CompletionEngine.Mode.SUBSTRING;
            case FUZZY -> CompletionEngine.Mode.FUZZY;
        };
        return CompletionEngine.complete(
                state.text(),
                state.suggestions().candidates(),
                engineMode,
                opts.completion().ignoreCase(),
                Integer.MAX_VALUE);
    }

    /**
     * The candidate that accepting would insert, or empty when there is nothing to accept.
     *
     * @param state the field state; required
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the selected candidate, or empty
     */
    public static Optional<String> selectedCandidate(Model state, TextInputConfig config) {
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
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the suffix, or empty
     */
    public static String suggestedSuffix(Model state, TextInputConfig config) {
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
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @param cols the columns the field is given, or zero to use the policy's declared width alone
     * @return the columns scrolled off, zero when the whole draft fits
     */
    public static int scrollOffset(Model state, TextInputConfig config, int cols) {
        Objects.requireNonNull(state, "state");
        return TextInputRendering.painted(state, config, cols).scroll();
    }
}
