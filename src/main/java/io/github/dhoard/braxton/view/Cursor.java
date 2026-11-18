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

package io.github.dhoard.braxton.view;

import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.style.Color;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable cursor intent: where a caret sits and how it should look, described without a terminal.
 *
 * <p>A cursor is view data, not a terminal handle. An application states the shape, the blink
 * policy, an optional colour and a position; {@link View.CursorAt} places that intent inside a
 * view tree, and the layout resolves it to a screen cell. Nothing here writes to a terminal, so a
 * cursor can be built, compared and asserted in a unit test.</p>
 *
 * <p>The position is local to the {@code CursorAt} that carries it: row and column are measured
 * from that wrapper's own painted rectangle, so the same cursor value can sit inside padding, a
 * frame or a split pane and still resolve to the cell it names. A position outside the wrapper's
 * visible rectangle - scrolled out, clipped by an ancestor, or off the screen - is not shown at
 * all rather than drawn somewhere else.</p>
 *
 * <p>{@link Strategy} chooses how the caret is presented. {@link Strategy#NATIVE} moves and styles
 * the terminal's own cursor, which is what a supporting terminal blinks and colours itself.
 * {@link Strategy#VIRTUAL} asks the layout to mark the cell instead, for a terminal that is not
 * asked for a cursor or an application that wants the caret inside its own painted text; a virtual
 * cursor is a cell style, so nothing moves outside the frame and the application drives its blink
 * from model time with {@link #atTime(long)}.</p>
 *
 * @param shape the glyph the caret takes; never null
 * @param mode whether the caret blinks, is steady, or is not shown; never null
 * @param strategy whether the terminal's cursor or a painted cell marks the caret; never null
 * @param color the caret colour, or null for the terminal's own
 * @param row the row within the carrying {@code CursorAt}'s rectangle; never negative
 * @param column the column within the carrying {@code CursorAt}'s rectangle; never negative
 * @param owner the element whose focus makes this cursor eligible, or empty for one that is
 *     always eligible; never null
 */
public record Cursor(
        Shape shape, Mode mode, Strategy strategy, Color color, int row, int column, Optional<ElementId> owner) {

    /** The full blink period of a virtual cursor, in milliseconds. */
    public static final int BLINK_MILLIS = 500;

    /** The glyph shape a terminal cursor takes. */
    public enum Shape {
        /** A filled block over the cell. */
        BLOCK,
        /** A line under the cell. */
        UNDERLINE,
        /** A vertical bar at the left of the cell. */
        BAR
    }

    /** Whether a cursor blinks, is steady, or is not shown. */
    public enum Mode {
        /** Always visible. */
        STEADY,
        /** Visible on the terminal's own blink cadence, or on the model's for a virtual cursor. */
        BLINK,
        /** Not shown at all. */
        HIDDEN
    }

    /** How a cursor is presented. */
    public enum Strategy {
        /** The terminal's own cursor is moved, shaped and coloured. */
        NATIVE,
        /** The layout marks the cell and no terminal cursor is used. */
        VIRTUAL
    }

    /**
     * Rejects a negative position and substitutes defaults for the presentation values.
     */
    public Cursor {
        shape = shape == null ? Shape.BLOCK : shape;
        mode = mode == null ? Mode.BLINK : mode;
        strategy = strategy == null ? Strategy.NATIVE : strategy;
        owner = owner == null ? Optional.empty() : owner;
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException(
                    "cursor position must be non-negative: row=" + row + " column=" + column);
        }
    }

    /**
     * A blinking block for the terminal's own cursor at a local position.
     *
     * @param row the row within the carrying wrapper's rectangle; never negative
     * @param column the column within the carrying wrapper's rectangle; never negative
     * @return the cursor
     */
    public static Cursor at(int row, int column) {
        return new Cursor(Shape.BLOCK, Mode.BLINK, Strategy.NATIVE, null, row, column, Optional.empty());
    }

    /**
     * The same cursor with a different shape.
     *
     * @param value the shape; null is {@link Shape#BLOCK}
     * @return the cursor
     */
    public Cursor withShape(Shape value) {
        return new Cursor(value == null ? Shape.BLOCK : value, mode, strategy, color, row, column, owner);
    }

    /**
     * The same cursor with a different blink policy.
     *
     * @param value the mode; null is {@link Mode#BLINK}
     * @return the cursor
     */
    public Cursor withMode(Mode value) {
        return new Cursor(shape, value == null ? Mode.BLINK : value, strategy, color, row, column, owner);
    }

    /**
     * The same cursor presented by a different strategy.
     *
     * @param value the strategy; null is {@link Strategy#NATIVE}
     * @return the cursor
     */
    public Cursor withStrategy(Strategy value) {
        return new Cursor(shape, mode, value == null ? Strategy.NATIVE : value, color, row, column, owner);
    }

    /**
     * The same cursor in a different colour.
     *
     * @param value the colour, or null for the terminal's own
     * @return the cursor
     */
    public Cursor withColor(Color value) {
        return new Cursor(shape, mode, strategy, value, row, column, owner);
    }

    /**
     * The same cursor owned by another focusable element.
     *
     * @param value the owning element, or null for one that is always eligible
     * @return the cursor
     */
    public Cursor withOwner(ElementId value) {
        return new Cursor(shape, mode, strategy, color, row, column, Optional.ofNullable(value));
    }

    /**
     * The same cursor at another local position.
     *
     * @param newRow the row within the carrying wrapper's rectangle; never negative
     * @param newColumn the column within the carrying wrapper's rectangle; never negative
     * @return the cursor
     */
    public Cursor movedTo(int newRow, int newColumn) {
        return new Cursor(shape, mode, strategy, color, newRow, newColumn, owner);
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

    /**
     * The same cursor with its owning focus identity scoped by a mount.
     *
     * @param path the owning mount's structural path; null leaves the owner unchanged
     * @return the scoped cursor
     */
    public Cursor scoped(ComponentPath path) {
        Objects.requireNonNull(path, "path");
        return owner.map(id -> withOwner(id.scoped(path))).orElse(this);
    }
}
