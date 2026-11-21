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
import io.github.dhoard.braxton.widget.Editor.Model;
import io.github.dhoard.braxton.widget.Editor.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Input actions and text coordinate helpers.
 */
final class EditorActions {

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
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return actionFor(event, opts, Editor.defaultKeys(opts));
    }

    /**
     * Maps one normalized input event against the actions this editor handles.
     *
     * <p>An action is matched before printable text: a gesture mapped to a printable key must fire
     * rather than insert its own character. A gesture no action names still inserts its text when
     * the modifiers are the ones a printable key carries, and is otherwise left for the next routing
     * phase.</p>
     */
    static Optional<Msg> actionFor(InputEvent event, EditorConfig opts, KeyMap<Msg> keys) {
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

    /**
     * The modifier states a printable key carries its text under: none, or shift alone.
     */
    static boolean plainModifiers(Key key) {
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
    public static int visibleLineCount(Model state, EditorConfig options) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return Math.max(1, Math.min(opts.maxVisibleLines(), state.lineCount()));
    }

    /**
     * Convenience: number of visible lines under the defaults.
     *
     * @param state the editor state
     * @return the number of rows the editor paints
     */
    public static int visibleLineCount(Model state) {
        return visibleLineCount(state, EditorConfig.defaults());
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
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return EditorRendering.painted(state, opts, false, cols, 0).rows().size();
    }

    /**
     * Convenience: the selection or the whole draft, honoring masking through {@code canCopy}.
     *
     * @param state the editor state to copy from
     * @param options the editing and rendering policy
     * @return the text copying would produce, or empty when copying is refused
     */
    public static Optional<String> copyableText(Model state, EditorConfig options) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return opts.canCopy() ? Optional.of(EditorEditing.copyText(state)) : Optional.empty();
    }
}
