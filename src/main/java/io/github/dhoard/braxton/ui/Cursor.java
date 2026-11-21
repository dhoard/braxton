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

package io.github.dhoard.braxton.ui;

import io.github.dhoard.braxton.style.Color;

/**
 * Cursor intent: where a caret sits on the screen and how it should look.
 *
 * <p>A cursor is data, not a terminal handle, so a widget states one and a test can assert it. The
 * position is in screen cells, which is what a widget knows: it is handed its own rectangle when it
 * paints, and the caret it reports sits inside that rectangle. Nothing here writes to a terminal,
 * so the shape, the blink policy and the color can be compared without one.</p>
 *
 * <p>A widget reports a cursor through {@link Widget#cursor}; the runtime asks the focused widget
 * and presents the answer. That is the whole protocol: there is no cursor node in the element tree
 * and no ownership to declare, because a caret is only ever the focused widget's own.</p>
 *
 * <p>{@link Strategy} chooses how the caret is presented. {@link Strategy#NATIVE} moves and styles
 * the terminal's own cursor, which is what a supporting terminal blinks and colors itself.
 * {@link Strategy#VIRTUAL} marks the cell instead, for a terminal that is not asked for a cursor or
 * an application that wants the caret inside its own painted text; a virtual cursor is a cell
 * style, so nothing moves outside the frame and the application drives its blink from model time
 * with {@link #atTime(long)}.</p>
 *
 * @param shape the glyph the caret takes; never null
 * @param mode whether the caret blinks, is steady, or is not shown; never null
 * @param strategy whether the terminal's cursor or a painted cell marks the caret; never null
 * @param color the caret color, or null for the terminal's own
 * @param row the screen row the caret sits on; never negative
 * @param column the screen column the caret sits on; never negative
 */
public record Cursor(Shape shape, Mode mode, Strategy strategy, Color color, int row, int column) {

    /**
     * The full blink period of a virtual cursor, in milliseconds.
     */
    public static final int BLINK_MILLIS = 500;

    /**
     * The glyph shape a terminal cursor takes.
     */
    public enum Shape {
        /**
         * A filled block over the cell.
         */
        BLOCK,
        /**
         * A line under the cell.
         */
        UNDERLINE,
        /**
         * A vertical bar at the left of the cell.
         */
        BAR
    }

    /**
     * Whether a cursor blinks, is steady, or is not shown.
     */
    public enum Mode {
        /**
         * Always visible.
         */
        STEADY,
        /**
         * Visible on the terminal's own blink cadence, or on the model's for a virtual cursor.
         */
        BLINK,
        /**
         * Not shown at all.
         */
        HIDDEN
    }

    /**
     * How a cursor is presented.
     */
    public enum Strategy {
        /**
         * The terminal's own cursor is moved, shaped and colored.
         */
        NATIVE,
        /**
         * The layout marks the cell and no terminal cursor is used.
         */
        VIRTUAL
    }

    /**
     * Rejects a negative position and substitutes defaults for the presentation values.
     */
    public Cursor {
        shape = shape == null ? Shape.BLOCK : shape;
        mode = mode == null ? Mode.BLINK : mode;
        strategy = strategy == null ? Strategy.NATIVE : strategy;
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException(
                    "cursor position must be non-negative: row=" + row + " column=" + column);
        }
    }

    /**
     * A blinking block for the terminal's own cursor at a screen cell.
     *
     * @param row the screen row; never negative
     * @param column the screen column; never negative
     * @return the cursor
     */
    public static Cursor at(int row, int column) {
        return new Cursor(Shape.BLOCK, Mode.BLINK, Strategy.NATIVE, null, row, column);
    }

    /**
     * The same cursor with a different shape.
     *
     * @param value the shape; null is {@link Shape#BLOCK}
     * @return the cursor
     */
    public Cursor withShape(Shape value) {
        return new Cursor(value == null ? Shape.BLOCK : value, mode, strategy, color, row, column);
    }

    /**
     * The same cursor with a different blink policy.
     *
     * @param value the mode; null is {@link Mode#BLINK}
     * @return the cursor
     */
    public Cursor withMode(Mode value) {
        return new Cursor(shape, value == null ? Mode.BLINK : value, strategy, color, row, column);
    }

    /**
     * The same cursor presented by a different strategy.
     *
     * @param value the strategy; null is {@link Strategy#NATIVE}
     * @return the cursor
     */
    public Cursor withStrategy(Strategy value) {
        return new Cursor(shape, mode, value == null ? Strategy.NATIVE : value, color, row, column);
    }

    /**
     * The same cursor in a different color.
     *
     * @param value the color, or null for the terminal's own
     * @return the cursor
     */
    public Cursor withColor(Color value) {
        return new Cursor(shape, mode, strategy, value, row, column);
    }

    /**
     * The same cursor at another screen cell.
     *
     * @param newRow the screen row; never negative
     * @param newColumn the screen column; never negative
     * @return the cursor
     */
    public Cursor movedTo(int newRow, int newColumn) {
        return new Cursor(shape, mode, strategy, color, newRow, newColumn);
    }

    /**
     * Whether this cursor asks to be shown at all.
     *
     * @return true unless the mode is {@link Mode#HIDDEN}
     */
    public boolean visible() {
        return mode != Mode.HIDDEN;
    }

    /**
     * The cursor as a virtual presentation shows it at {@code timeMillis}.
     *
     * <p>A virtual caret is a painted cell, so the terminal's blink timer cannot animate it: the
     * application owns the clock. This is that clock, as a pure function of model time - a
     * {@link Mode#BLINK} cursor is {@link Mode#STEADY} for the first half of each
     * {@value #BLINK_MILLIS}-millisecond period and {@link Mode#HIDDEN} for the second, and every
     * other mode is returned unchanged. A {@link Strategy#NATIVE} cursor is unaffected, because the
     * terminal blinks it.</p>
     *
     * @param timeMillis the application's own elapsed time in milliseconds
     * @return this cursor with the mode that time presents
     */
    public Cursor atTime(long timeMillis) {
        if (mode != Mode.BLINK || strategy != Strategy.VIRTUAL) {
            return this;
        }
        return withMode(Math.floorMod(timeMillis, BLINK_MILLIS) < BLINK_MILLIS / 2 ? Mode.STEADY : Mode.HIDDEN);
    }
}
