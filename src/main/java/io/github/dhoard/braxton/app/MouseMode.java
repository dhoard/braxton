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

package io.github.dhoard.braxton.app;

/**
 * How much the terminal is asked to report about the pointer.
 *
 * <p>The modes are the terminal's own tracking levels, in the order they cost: nothing, presses and
 * releases, then presses and drags, then every motion. A terminal only sends what it was asked for,
 * so an application that never asks pays nothing for pointer input - and one that asks for
 * {@link #ALL_MOTION} pays for a report on every cell the pointer crosses.</p>
 *
 * <p>{@link #CLICK} is what a clickable interface needs: presses, releases and wheel gestures are
 * reported, and nothing is reported while the pointer merely moves. Drag selection needs
 * {@link #CELL_MOTION}, which adds motion while a button is held. Hover effects need
 * {@link #ALL_MOTION}.</p>
 */
public enum MouseMode {

    /** Report nothing; the terminal sends no pointer input at all. */
    NONE,

    /** Report presses, releases and wheel gestures. */
    CLICK,

    /** Report presses, releases, wheel gestures, and motion while a button is held. */
    CELL_MOTION,

    /** Report presses, releases, wheel gestures, and all motion, with or without a button held. */
    ALL_MOTION
}
