/*
 * Copyright (c) 2026-present Doug Hoard
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

/** Keyboard modifier state carried by a normalized input event. */
public record Modifiers(boolean ctrl, boolean alt, boolean shift) {

    public static final Modifiers NONE = new Modifiers(false, false, false);

    public static final Modifiers CTRL = new Modifiers(true, false, false);
    public static final Modifiers ALT = new Modifiers(false, true, false);
    public static final Modifiers SHIFT = new Modifiers(false, false, true);

    public Modifiers withCtrl(boolean value) {
        return new Modifiers(value, alt, shift);
    }

    public Modifiers withAlt(boolean value) {
        return new Modifiers(ctrl, value, shift);
    }

    public Modifiers withShift(boolean value) {
        return new Modifiers(ctrl, alt, value);
    }

    public boolean none() {
        return !ctrl && !alt && !shift;
    }

    /** Canonical {@code ctrl+alt+shift+} prefix, empty when no modifier is set. */
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
