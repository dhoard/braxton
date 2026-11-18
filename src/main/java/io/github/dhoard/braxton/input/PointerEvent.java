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
 * One normalized pointer report: what happened, at which cell.
 *
 * <p>Coordinates are zero-based screen cells, which is what a layout, a rendered screen and a hit
 * test all count in. The decoder subtracts the terminal's own one-based origin once, so nothing
 * above it has to know the protocol reported {@code 1} for the first column.</p>
 *
 * <p>A hit test hands an element the same event translated into its own rectangle, so a widget
 * never has to know where on screen it was painted. That local event is the only one whose
 * coordinates can be negative or past the element: a drag that leaves its region keeps being
 * delivered to the element that captured the press, and clamping is the element's decision.</p>
 *
 * @param action what the report says happened
 * @param button the button it names; {@link PointerButton#NONE} for a hover or an unnamed release
 * @param modifiers the modifier state the terminal reported
 * @param row the zero-based row
 * @param column the zero-based column
 * @param clickCount the press this event is: 1 for a first press, 2 for a press that repeats the
 *     previous one at the same cell within the double-click window; always 1 for the other actions
 * @param capturedAtNanos the runtime capture time, or zero when unstamped
 */
public record PointerEvent(
        PointerAction action,
        PointerButton button,
        Modifiers modifiers,
        int row,
        int column,
        int clickCount,
        long capturedAtNanos) {

    /** Rejects a missing action and a wheel that names no wheel direction. */
    public PointerEvent {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        button = button == null ? PointerButton.NONE : button;
        modifiers = modifiers == null ? Modifiers.NONE : modifiers;
        if (button.wheel() != (action == PointerAction.WHEEL)) {
            // A wheel report is the only one that names a wheel direction, and every wheel report
            // names one: the two halves of that rule are what keep `isWheel()` and `button()` from
            // ever disagreeing.
            throw new IllegalArgumentException("a wheel action and a wheel button belong together");
        }
        clickCount = action == PointerAction.PRESS ? Math.max(1, clickCount) : 1;
        capturedAtNanos = Math.max(0L, capturedAtNanos);
    }

    /**
     * An unstamped event.
     *
     * @param action what the report says happened
     * @param button the button it names
     * @param modifiers the modifier state
     * @param row the zero-based row
     * @param column the zero-based column
     */
    public PointerEvent(PointerAction action, PointerButton button, Modifiers modifiers, int row, int column) {
        this(action, button, modifiers, row, column, 1, 0L);
    }

    /**
     * A press of one button.
     *
     * @param button the button that went down
     * @param modifiers the modifier state
     * @param row the zero-based row
     * @param column the zero-based column
     * @return the press
     */
    public static PointerEvent press(PointerButton button, Modifiers modifiers, int row, int column) {
        return new PointerEvent(PointerAction.PRESS, button, modifiers, row, column);
    }

    /**
     * A release of one button.
     *
     * @param button the button that came up
     * @param modifiers the modifier state
     * @param row the zero-based row
     * @param column the zero-based column
     * @return the release
     */
    public static PointerEvent release(PointerButton button, Modifiers modifiers, int row, int column) {
        return new PointerEvent(PointerAction.RELEASE, button, modifiers, row, column);
    }

    /**
     * Motion, with a held button or with none.
     *
     * @param button the button still held, or {@link PointerButton#NONE}
     * @param modifiers the modifier state
     * @param row the zero-based row
     * @param column the zero-based column
     * @return the motion
     */
    public static PointerEvent motion(PointerButton button, Modifiers modifiers, int row, int column) {
        return new PointerEvent(PointerAction.MOTION, button, modifiers, row, column);
    }

    /**
     * A wheel gesture in one direction.
     *
     * @param direction one of the four wheel directions
     * @param modifiers the modifier state
     * @param row the zero-based row
     * @param column the zero-based column
     * @return the wheel gesture
     */
    public static PointerEvent wheel(PointerButton direction, Modifiers modifiers, int row, int column) {
        return new PointerEvent(PointerAction.WHEEL, direction, modifiers, row, column);
    }

    /**
     * Whether this event is a press.
     *
     * @return true for a press
     */
    public boolean isPress() {
        return action == PointerAction.PRESS;
    }

    /**
     * Whether this event is a release.
     *
     * @return true for a release
     */
    public boolean isRelease() {
        return action == PointerAction.RELEASE;
    }

    /**
     * Whether this event is motion.
     *
     * @return true for motion
     */
    public boolean isMotion() {
        return action == PointerAction.MOTION;
    }

    /**
     * Whether this event is a wheel gesture.
     *
     * @return true for a wheel gesture
     */
    public boolean isWheel() {
        return action == PointerAction.WHEEL;
    }

    /**
     * Whether this event is a press of the left button.
     *
     * @return true for a left press
     */
    public boolean isLeftPress() {
        return action == PointerAction.PRESS && button == PointerButton.LEFT;
    }

    /**
     * The same event moved by a delta, for a hit test that has to undo a scroll or a pan.
     *
     * @param rowDelta the rows to add to the row
     * @param columnDelta the columns to add to the column
     * @return the moved event
     */
    public PointerEvent translated(int rowDelta, int columnDelta) {
        if (rowDelta == 0 && columnDelta == 0) {
            return this;
        }
        return new PointerEvent(
                action, button, modifiers, row + rowDelta, column + columnDelta, clickCount, capturedAtNanos);
    }

    /**
     * The same event in the coordinates of a rectangle whose origin is the given cell.
     *
     * @param originRow the row of the rectangle's first row
     * @param originColumn the column of the rectangle's first column
     * @return the local event
     */
    public PointerEvent localTo(int originRow, int originColumn) {
        return translated(-originRow, -originColumn);
    }

    /**
     * The same event reported as another press of the same gesture.
     *
     * @param value the press count; a value below one is treated as one
     * @return this instance when the count did not change
     */
    public PointerEvent withClickCount(int value) {
        int count = action == PointerAction.PRESS ? Math.max(1, value) : 1;
        return count == clickCount
                ? this
                : new PointerEvent(action, button, modifiers, row, column, count, capturedAtNanos);
    }

    /**
     * The same event with a runtime capture timestamp.
     *
     * @param nanos the capture time
     * @return this instance when it is already stamped
     */
    public PointerEvent stamped(long nanos) {
        long value = Math.max(0L, nanos);
        return value == capturedAtNanos
                ? this
                : new PointerEvent(action, button, modifiers, row, column, clickCount, value);
    }
}
