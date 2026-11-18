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

package io.github.dhoard.braxton.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One named action: the gestures that trigger it, the message it produces, and the help text that
 * describes it.
 *
 * <p>An action is the single definition a caller needs. Routing and help both read it: {@link
 * KeyMap#bindings()} compiles its aliases into the bindings a view routes, {@link KeyMap#actionFor}
 * matches one gesture, and a help view renders {@link #label()} and {@link #description()}. Nothing
 * is restated per surface, so a remapped or disabled action cannot leave help describing a gesture
 * that no longer fires.</p>
 *
 * <p>Aliases match a gesture exactly, the rule {@link Binding} applies: an alias names the key
 * <em>and</em> its modifiers. A gesture the terminal reports with a modifier the alias does not name
 * is a different gesture, so an action must list each form it accepts. That keeps a map able to bind
 * {@code t} and {@code alt+t} to different actions without one shadowing the other.</p>
 *
 * @param id the stable identity of the action within its map; never blank
 * @param aliases the gestures that trigger it, in display order
 * @param label the display label help shows for the aliases; blank means the aliases joined by "/"
 * @param description the one-line description help shows, or empty
 * @param message the message value the action produces; never null
 * @param compact whether compact help includes the action
 * @param enabled whether the action matches gestures and appears in help
 * @param <Msg> the message type of the owning application or component
 */
public record KeyAction<Msg>(
        String id, List<Key> aliases, String label, String description, Msg message, boolean compact, boolean enabled) {

    /**
     * Rejects an incomplete action and prepares its display label.
     */
    public KeyAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("action id is required");
        }
        // Binding rejects a null message for the same reason: an alias that can never yield a
        // message would still occupy the gesture.
        Objects.requireNonNull(message, "message");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        description = description == null ? "" : description;
        label = label == null || label.isBlank() ? joined(aliases) : label;
    }

    /**
     * An enabled action shown in compact help, from parsed key specs.
     *
     * @param id the stable identity within the map
     * @param message the message the action produces
     * @param label the display label, or blank to derive it from the aliases
     * @param description the one-line description, or null for none
     * @param aliasSpecs the gestures, in {@link Key#parse} form
     * @param <Msg> the message type
     * @return the action
     */
    public static <Msg> KeyAction<Msg> of(
            String id, Msg message, String label, String description, String... aliasSpecs) {
        return new KeyAction<>(id, parse(aliasSpecs), label, description, message, true, true);
    }

    /**
     * An enabled action shown in compact help, from keys the caller already holds.
     *
     * @param id the stable identity within the map
     * @param message the message the action produces
     * @param label the display label, or blank to derive it from the aliases
     * @param description the one-line description, or null for none
     * @param aliases the gestures
     * @param <Msg> the message type
     * @return the action
     */
    public static <Msg> KeyAction<Msg> of(String id, Msg message, String label, String description, List<Key> aliases) {
        return new KeyAction<>(id, aliases, label, description, message, true, true);
    }

    private static List<Key> parse(String... specs) {
        List<Key> keys = new ArrayList<>(specs.length);
        for (String spec : specs) {
            keys.add(Key.parse(Objects.requireNonNull(spec, "spec")));
        }
        return keys;
    }

    private static String joined(List<Key> aliases) {
        StringBuilder label = new StringBuilder();
        for (Key alias : aliases) {
            if (!label.isEmpty()) {
                label.append('/');
            }
            label.append(alias);
        }
        return label.toString();
    }

    /**
     * True when one alias is this gesture.
     *
     * @param gesture the gesture to test
     * @return whether the action handles it
     */
    public boolean matches(Key gesture) {
        return aliases.contains(gesture);
    }

    /**
     * The same action with a different message.
     *
     * @param message the new message; never null
     * @return the new action
     */
    public KeyAction<Msg> withMessage(Msg message) {
        return new KeyAction<>(id, aliases, label, description, message, compact, enabled);
    }

    /**
     * The same action with different gestures. The label is a display decision, not a restatement of
     * the aliases, so it is kept: derive it again with {@code withLabel("")} when a remapped action
     * should show its new gestures.
     *
     * @param aliases the new gestures
     * @return the new action
     */
    public KeyAction<Msg> withAliases(List<Key> aliases) {
        return new KeyAction<>(id, aliases, label, description, message, compact, enabled);
    }

    /**
     * The same action with different gestures, from parsed key specs.
     *
     * @param aliasSpecs the new gestures, in {@link Key#parse} form
     * @return the new action
     */
    public KeyAction<Msg> withAliases(String... aliasSpecs) {
        return withAliases(parse(aliasSpecs));
    }

    /**
     * The same action with a different display label.
     *
     * @param label the new label, or blank to derive it from the aliases
     * @return the new action
     */
    public KeyAction<Msg> withLabel(String label) {
        return new KeyAction<>(id, aliases, label, description, message, compact, enabled);
    }

    /**
     * The same action with a different description.
     *
     * @param description the new description, or null for none
     * @return the new action
     */
    public KeyAction<Msg> withDescription(String description) {
        return new KeyAction<>(id, aliases, label, description, message, compact, enabled);
    }

    /**
     * The same action with a different compact-help membership.
     *
     * @param compact whether compact help includes it
     * @return the new action
     */
    public KeyAction<Msg> withCompact(boolean compact) {
        return new KeyAction<>(id, aliases, label, description, message, compact, enabled);
    }

    /**
     * The same action enabled or disabled. A disabled action matches no gesture and is left out of
     * help, but keeps its definition so it can be enabled again.
     *
     * @param enabled whether the action is active
     * @return the new action
     */
    public KeyAction<Msg> withEnabled(boolean enabled) {
        return new KeyAction<>(id, aliases, label, description, message, compact, enabled);
    }
}
