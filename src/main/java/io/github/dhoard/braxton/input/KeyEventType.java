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
 * Which kind of key event a {@link InputEvent.KeyInput} reports.
 *
 * <p>An unenhanced terminal reports presses only, and a key held down arrives as a stream of
 * presses. A terminal that negotiated {@link KeyboardEnhancements#reportEventTypes()} reports the
 * three kinds separately, so a release is distinguishable from a press of the same key.</p>
 *
 * <p>A press and a repeat activate: they run bindings and may insert text. A release never does;
 * it exists so an application can observe the gesture ending.</p>
 */
public enum KeyEventType {

    /** The key went down. */
    PRESS(1),

    /** The key is being held down and the terminal repeated it. */
    REPEAT(2),

    /** The key came up. */
    RELEASE(3);

    private final int code;

    KeyEventType(int code) {
        this.code = code;
    }

    /** The protocol number reporting this kind, as the Kitty keyboard protocol encodes it.
     *
     * @return 1 for a press, 2 for a repeat, 3 for a release
     */
    public int code() {
        return code;
    }

    /**
     * The kind a protocol number names.
     *
     * @param code the reported event type
     * @return the kind, or {@code null} when the number names none
     */
    public static KeyEventType fromCode(int code) {
        return switch (code) {
            case 1 -> PRESS;
            case 2 -> REPEAT;
            case 3 -> RELEASE;
            default -> null;
        };
    }
}
