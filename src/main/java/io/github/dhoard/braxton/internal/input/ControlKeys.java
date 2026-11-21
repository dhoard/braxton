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

package io.github.dhoard.braxton.internal.input;

import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;

/**
 * Normalizes legacy control bytes for bare and ALT-prefixed input.
 */
final class ControlKeys {
    private ControlKeys() {}

    /**
     * Normalizes C0 controls other than Escape, plus DEL, into key gestures.
     * ESC plus one of them is that gesture with ALT (see {@link InputDecoder}), so the pair and the
     * bare byte agree on the key and only differ by the modifier. A control byte is never used as a
     * key name or text, because {@link Key} rejects control characters in a name.
     *
     * @param value a C0 control byte other than Escape, or DEL
     */
    static Key keyForControlByte(int value) {
        if (value == 0x0D) {
            return Key.ENTER;
        }
        if (value == 0x0A) {
            return Key.of("j", Modifiers.CTRL);
        }
        if (value == 0x09) {
            return Key.TAB;
        }
        if (value == 0x7F || value == 0x08) {
            return Key.BACKSPACE;
        }
        if (value == 0x00) {
            return Key.of("space", Modifiers.CTRL);
        }
        if (value == 0x1C) {
            return Key.of("\\", Modifiers.CTRL);
        }
        if (value == 0x1D) {
            return Key.of("]", Modifiers.CTRL);
        }
        if (value == 0x1E) {
            return Key.of("^", Modifiers.CTRL);
        }
        if (value == 0x1F) {
            return Key.of("_", Modifiers.CTRL);
        }
        // Remaining C0 controls are Ctrl+letter.
        char letter = (char) ('a' + value - 1);
        return Key.of(String.valueOf(letter), Modifiers.CTRL);
    }
}
