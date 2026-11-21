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

import java.util.Locale;
import java.util.Objects;

/**
 * A normalized key identity: a lower-case name plus modifiers.
 *
 * <p>Keys compare by value, so two independent bindings for the same gesture are equal and
 * duplicate detection is meaningful. Escape-sequence bytes never leak into this type.</p>
 *
 * @param name the lower-case key name
 * @param modifiers the modifier state that must match
 */
public record Key(String name, Modifiers modifiers) implements Comparable<Key> {

    /**
     * The unmodified {@code enter} key.
     */
    public static final Key ENTER = of("enter");
    /**
     * The unmodified {@code tab} key.
     */
    public static final Key TAB = of("tab");
    /**
     * The unmodified {@code escape} key.
     */
    public static final Key ESCAPE = of("escape");
    /**
     * The unmodified {@code backspace} key.
     */
    public static final Key BACKSPACE = of("backspace");
    /**
     * The unmodified {@code delete} key.
     */
    public static final Key DELETE = of("delete");
    /**
     * The unmodified {@code up} arrow key.
     */
    public static final Key UP = of("up");
    /**
     * The unmodified {@code down} arrow key.
     */
    public static final Key DOWN = of("down");
    /**
     * The unmodified {@code left} arrow key.
     */
    public static final Key LEFT = of("left");
    /**
     * The unmodified {@code right} arrow key.
     */
    public static final Key RIGHT = of("right");
    /**
     * The unmodified {@code home} key.
     */
    public static final Key HOME = of("home");
    /**
     * The unmodified {@code end} key.
     */
    public static final Key END = of("end");
    /**
     * The unmodified {@code pageup} key.
     */
    public static final Key PAGE_UP = of("pageup");
    /**
     * The unmodified {@code pagedown} key.
     */
    public static final Key PAGE_DOWN = of("pagedown");
    /**
     * The unmodified {@code space} key.
     */
    public static final Key SPACE = of("space");
    /**
     * The unmodified {@code wheelup} gesture, reported as a key rather than a key press.
     */
    public static final Key WHEEL_UP = of("wheelup");
    /**
     * The unmodified {@code wheeldown} gesture, reported as a key rather than a key press.
     */
    public static final Key WHEEL_DOWN = of("wheeldown");

    /**
     * Creates a {@code Key} value.
     *
     * @param name the lower-case key name
     * @param modifiers the modifier state that must match
     */
    public Key {
        Objects.requireNonNull(name, "name");
        name = name.toLowerCase(Locale.ROOT);
        modifiers = modifiers == null ? Modifiers.NONE : modifiers;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("key name must not contain control characters");
            }
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("key name must not be blank (use \"space\" for the space key)");
        }
    }

    /**
     * Creates a {@code Key} value.
     *
     * @param name the lower-case key name
     * @return the normalized key
     */
    public static Key of(String name) {
        return new Key(name, Modifiers.NONE);
    }

    /**
     * Creates a {@code Key} value.
     *
     * @param name the lower-case key name
     * @param modifiers the modifier state that must match
     * @return the normalized key
     */
    public static Key of(String name, Modifiers modifiers) {
        return new Key(name, modifiers);
    }

    /**
     * Parses {@code ctrl+alt+x}, {@code shift+tab}, {@code ctrl++}, or a plain name.
     *
     * @param spec the key or style specification
     * @return the normalized key and modifiers
     */
    public static Key parse(String spec) {
        String[] parts = spec.toLowerCase(Locale.ROOT).split("\\+", -1);
        // The canonical spec of the '+' key ends in '+', so a doubled separator is a literal plus:
        // "+" names the plus key and "ctrl++" is Ctrl+plus. A single trailing separator ("ctrl+")
        // still names nothing, so it stays an error instead of silently dropping its modifier.
        int modifierCount = parts.length - 1;
        String name = parts[modifierCount];
        if (name.isEmpty() && modifierCount > 0 && parts[modifierCount - 1].isEmpty()) {
            name = "+";
            modifierCount--;
        }
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        for (int i = 0; i < modifierCount; i++) {
            switch (parts[i]) {
                case "ctrl", "control" -> ctrl = true;
                case "alt", "meta" -> alt = true;
                case "shift" -> shift = true;
                default -> throw new IllegalArgumentException("unknown modifier: " + parts[i]);
            }
        }
        return new Key(name, new Modifiers(ctrl, alt, shift));
    }

    /**
     * Returns the same key with the requested modifiers.
     *
     * @param modifiers the modifier state that must match
     * @return the key with the requested modifiers
     */
    public Key with(Modifiers modifiers) {
        return new Key(name, modifiers);
    }

    @Override
    public int compareTo(Key other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : modifiers.prefix().compareTo(other.modifiers.prefix());
    }

    /**
     * Canonical spec such as {@code ctrl+alt+x}.
     */
    @Override
    public String toString() {
        return modifiers.prefix() + name;
    }
}
