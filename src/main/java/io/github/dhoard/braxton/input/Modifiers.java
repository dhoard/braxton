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

/**
 * Keyboard modifier state carried by a normalized input event.
 *
 * @param ctrl whether Ctrl was held
 * @param alt whether Alt or Meta was held
 * @param shift whether Shift was held
 */
public record Modifiers(boolean ctrl, boolean alt, boolean shift) {

    /**
     * No modifier held.
     */
    public static final Modifiers NONE = new Modifiers(false, false, false);

    /**
     * Control held.
     */
    public static final Modifiers CTRL = new Modifiers(true, false, false);
    /**
     * Alt held.
     */
    public static final Modifiers ALT = new Modifiers(false, true, false);
    /**
     * Shift held.
     */
    public static final Modifiers SHIFT = new Modifiers(false, false, true);

    /**
     * Returns the same value with ctrl replaced.
     *
     * @param value the requested ctrl
     * @return the value with the requested ctrl
     */
    public Modifiers withCtrl(boolean value) {
        return new Modifiers(value, alt, shift);
    }

    /**
     * Returns the same value with alt replaced.
     *
     * @param value the requested alt
     * @return the value with the requested alt
     */
    public Modifiers withAlt(boolean value) {
        return new Modifiers(ctrl, value, shift);
    }

    /**
     * Returns the same value with shift replaced.
     *
     * @param value the requested shift
     * @return the value with the requested shift
     */
    public Modifiers withShift(boolean value) {
        return new Modifiers(ctrl, alt, value);
    }

    /**
     * Tests whether no modifier flag is set.
     *
     * @return {@code true} if no modifier flag is set; otherwise {@code false}
     */
    public boolean none() {
        return !ctrl && !alt && !shift;
    }

    /**
     * Canonical {@code ctrl+alt+shift+} prefix, empty when no modifier is set.
     *
     * @return the canonical modifier prefix, ending in {@code +}, or empty
     */
    public String prefix() {
        StringBuilder text = new StringBuilder();
        if (ctrl) {
            text.append("ctrl+");
        }
        if (alt) {
            text.append("alt+");
        }
        if (shift) {
            text.append("shift+");
        }
        return text.toString();
    }
}
