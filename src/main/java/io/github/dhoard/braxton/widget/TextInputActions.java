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
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.widget.TextInput.Model;
import io.github.dhoard.braxton.widget.TextInput.Msg;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-line input and completion action translation.
 */
final class TextInputActions {

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
        Objects.requireNonNull(state, "state");
        TextInputConfig opts = config == null ? TextInputConfig.defaults() : config;
        return actionFor(state, event, opts, TextInputKeys.defaultKeys(opts));
    }

    /**
     * Maps one event against one policy and key map.
     */
    static Optional<Msg> actionFor(Model state, InputEvent event, TextInputConfig config, KeyMap<Msg> keys) {
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

    /**
     * Whether an action is one of the completion gestures, which are conditional.
     */
    static boolean completionAction(Msg message) {
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
    static boolean completes(Model state, Msg message, TextInputConfig config) {
        if (message instanceof Msg.AcceptSuggestion) {
            return TextInputEditing.selectedCandidate(state, config)
                    .filter(candidate -> !candidate.equals(state.text()))
                    .isPresent();
        }
        return TextInputEditing.matchedCandidates(state, config).size() >= 2;
    }

    /**
     * The modifier states a printable key carries its text under: none, or shift alone.
     */
    static boolean plainModifiers(Key key) {
        Modifiers modifiers = key.modifiers();
        return modifiers.none() || (modifiers.shift() && !modifiers.ctrl() && !modifiers.alt());
    }
}
