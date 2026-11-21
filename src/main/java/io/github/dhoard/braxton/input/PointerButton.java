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
 * The button a pointer report names.
 *
 * <p>Three buttons and four wheel directions are what the terminal mouse protocol can report, and
 * they are distinct values here rather than an ordinal plus flags: a wheel gesture is a wheel
 * gesture, not "button 4 pressed". {@link #NONE} is the terminal saying no button is involved - a
 * hover, or a legacy release report that never names the button it ends.</p>
 */
public enum PointerButton {

    /**
     * No button: motion with nothing held, or a report that does not name one.
     */
    NONE,

    /**
     * The left (primary) button.
     */
    LEFT,

    /**
     * The middle button.
     */
    MIDDLE,

    /**
     * The right (secondary) button.
     */
    RIGHT,

    /**
     * The wheel turned away from the user.
     */
    WHEEL_UP,

    /**
     * The wheel turned toward the user.
     */
    WHEEL_DOWN,

    /**
     * The wheel tilted left.
     */
    WHEEL_LEFT,

    /**
     * The wheel tilted right.
     */
    WHEEL_RIGHT;

    /**
     * Whether this value names a wheel direction rather than a pressable button.
     *
     * @return true for the four wheel directions
     */
    public boolean wheel() {
        return this == WHEEL_UP || this == WHEEL_DOWN || this == WHEEL_LEFT || this == WHEEL_RIGHT;
    }

    /**
     * Whether this value names a button a user can hold down.
     *
     * @return true for the left, middle and right buttons
     */
    public boolean pressable() {
        return this == LEFT || this == MIDDLE || this == RIGHT;
    }
}
