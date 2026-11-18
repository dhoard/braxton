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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered set of named actions, grouped for help.
 *
 * <p>A key map is the one definition an application or widget keeps of what its keys do. It compiles
 * to the {@link Binding} list a view routes ({@link #bindings()}), answers a single gesture in a
 * widget's input handler ({@link #actionFor}), and supplies the text a help view renders, so routing
 * and help cannot drift apart.</p>
 *
 * <p>The map is a value: {@link #with}, {@link #withAliases} and {@link #withEnabled} return a new
 * map and never change the one they were called on. Two enabled actions may not claim the same
 * gesture - with exact alias matching that would make the winner depend on declaration order - so a
 * map is built without an ambiguous gesture, and the duplicate is reported when the map is built
 * rather than when a key is pressed. A disabled action releases its gestures: it matches nothing, is
 * absent from {@link #bindings()} and from help, and its aliases may overlap another action's.</p>
 *
 * @param groups the groups, in display order
 * @param <Msg> the message type of the owning application or component
 */
public record KeyMap<Msg>(List<Group<Msg>> groups) {

    /**
     * One help column: a title and the actions under it.
     *
     * @param title the group heading; blank for an untitled group
     * @param actions the actions, in display order
     * @param <Msg> the message type of the owning application or component
     */
    public record Group<Msg>(String title, List<KeyAction<Msg>> actions) {

        /**
         * Rejects a missing action list and prepares the title.
         */
        public Group {
            title = title == null ? "" : title;
            actions = actions == null ? List.of() : List.copyOf(actions);
            for (KeyAction<Msg> action : actions) {
                Objects.requireNonNull(action, "action");
            }
        }
    }

    /**
     * Rejects duplicate identities and ambiguous gestures.
     */
    public KeyMap {
        groups = groups == null ? List.of() : List.copyOf(groups);
        Map<String, KeyAction<Msg>> byId = new HashMap<>();
        Map<Key, String> claimed = new HashMap<>();
        for (Group<Msg> group : groups) {
            Objects.requireNonNull(group, "group");
            for (KeyAction<Msg> action : group.actions()) {
                KeyAction<Msg> previous = byId.putIfAbsent(action.id(), action);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate action id: " + action.id());
                }
                if (!action.enabled()) {
                    continue;
                }
                for (Key alias : action.aliases()) {
                    String owner = claimed.putIfAbsent(alias, action.id());
                    if (owner != null) {
                        throw new IllegalArgumentException(
                                "duplicate key alias " + alias + " for actions " + owner + " and " + action.id());
                    }
                }
            }
        }
    }

    /**
     * A map with no actions.
     *
     * @param <Msg> the message type
     * @return the empty map
     */
    public static <Msg> KeyMap<Msg> empty() {
        return new KeyMap<>(List.of());
    }

    /**
     * A map from groups, in display order.
     *
     * @param groups the groups
     * @param <Msg> the message type
     * @return the map
     */
    @SafeVarargs
    public static <Msg> KeyMap<Msg> of(Group<Msg>... groups) {
        // Copy the array into a list here rather than handing it to another varargs method: the
        // parameter is non-reifiable, so passing it on is exactly the heap-pollution hazard
        // @SafeVarargs promises this method does not have.
        List<Group<Msg>> copy = new ArrayList<>(groups.length);
        for (Group<Msg> group : groups) {
            copy.add(group);
        }
        return new KeyMap<>(copy);
    }

    /**
     * A map from groups, in display order.
     *
     * @param groups the groups
     * @param <Msg> the message type
     * @return the map
     */
    public static <Msg> KeyMap<Msg> of(List<Group<Msg>> groups) {
        return new KeyMap<>(groups);
    }

    /**
     * One group of actions.
     *
     * @param title the group heading; blank for none
     * @param actions the actions, in display order
     * @param <Msg> the message type
     * @return the group
     */
    @SafeVarargs
    public static <Msg> Group<Msg> group(String title, KeyAction<Msg>... actions) {
        List<KeyAction<Msg>> copy = new ArrayList<>(actions.length);
        for (KeyAction<Msg> action : actions) {
            copy.add(action);
        }
        return new Group<>(title, copy);
    }

    /**
     * One group of actions.
     *
     * @param title the group heading; blank for none
     * @param actions the actions, in display order
     * @param <Msg> the message type
     * @return the group
     */
    public static <Msg> Group<Msg> group(String title, List<KeyAction<Msg>> actions) {
        return new Group<>(title, actions);
    }

    /**
     * Every action of every group, in order, disabled actions included.
     *
     * @return the actions
     */
    public List<KeyAction<Msg>> actions() {
        List<KeyAction<Msg>> all = new ArrayList<>();
        for (Group<Msg> group : groups) {
            all.addAll(group.actions());
        }
        return List.copyOf(all);
    }

    /**
     * The action with this identity.
     *
     * @param id the action identity
     * @return the action, or empty when the map does not define it
     */
    public Optional<KeyAction<Msg>> action(String id) {
        for (Group<Msg> group : groups) {
            for (KeyAction<Msg> action : group.actions()) {
                if (action.id().equals(id)) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The enabled action one gesture triggers.
     *
     * <p>An alias matches exactly, so this answer is the same one the compiled {@link Binding} list
     * answers.</p>
     * @param gesture the gesture to match
     * @return the action, or empty when no enabled action claims the gesture
     */
    public Optional<KeyAction<Msg>> actionFor(Key gesture) {
        if (gesture == null) {
            return Optional.empty();
        }
        for (Group<Msg> group : groups) {
            for (KeyAction<Msg> action : group.actions()) {
                if (action.enabled() && action.matches(gesture)) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The enabled actions compiled into routing bindings, in order.
     *
     * @return one binding per enabled alias
     */
    public List<Binding<Msg>> bindings() {
        List<Binding<Msg>> bindings = new ArrayList<>();
        for (Group<Msg> group : groups) {
            for (KeyAction<Msg> action : group.actions()) {
                if (!action.enabled()) {
                    continue;
                }
                for (Key alias : action.aliases()) {
                    bindings.add(new Binding<>(alias, action.message()));
                }
            }
        }
        return List.copyOf(bindings);
    }

    /**
     * The same groups with one action replaced by identity.
     *
     * @param replacement the replacement action
     * @return the new map
     * @throws IllegalArgumentException when no action has that identity
     */
    public KeyMap<Msg> with(KeyAction<Msg> replacement) {
        Objects.requireNonNull(replacement, "action");
        // Replacing an action the map does not define would silently add nothing, so the mistake is
        // reported where it was made rather than as a key that never fires.
        if (action(replacement.id()).isEmpty()) {
            throw new IllegalArgumentException("unknown action id: " + replacement.id());
        }
        List<Group<Msg>> replaced = new ArrayList<>(groups.size());
        for (Group<Msg> group : groups) {
            List<KeyAction<Msg>> actions = new ArrayList<>(group.actions().size());
            for (KeyAction<Msg> existing : group.actions()) {
                actions.add(existing.id().equals(replacement.id()) ? replacement : existing);
            }
            replaced.add(new Group<>(group.title(), actions));
        }
        return new KeyMap<>(replaced);
    }

    /**
     * The same map with one action's gestures replaced.
     *
     * @param id the action identity
     * @param aliasSpecs the new gestures, in {@link Key#parse} form
     * @return the new map
     * @throws IllegalArgumentException when no action has that identity
     */
    public KeyMap<Msg> withAliases(String id, String... aliasSpecs) {
        return with(require(id).withAliases(aliasSpecs));
    }

    /**
     * The same map with one action enabled or disabled.
     *
     * @param id the action identity
     * @param enabled whether the action is active
     * @return the new map
     * @throws IllegalArgumentException when no action has that identity
     */
    public KeyMap<Msg> withEnabled(String id, boolean enabled) {
        return with(require(id).withEnabled(enabled));
    }

    private KeyAction<Msg> require(String id) {
        return action(id).orElseThrow(() -> new IllegalArgumentException("unknown action id: " + id));
    }
}
