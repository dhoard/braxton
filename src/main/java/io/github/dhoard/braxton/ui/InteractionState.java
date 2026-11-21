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

/**
 * The interaction flags a widget paints with.
 *
 * @param focused whether the widget owns keyboard focus
 * @param hovered whether the pointer is over the widget
 * @param pressed whether a pointer press is active
 * @param disabled whether interaction is disabled
 */
public record InteractionState(boolean focused, boolean hovered, boolean pressed, boolean disabled) {

    /**
     * No interaction flags set.
     */
    public static final InteractionState NONE = new InteractionState(false, false, false, false);

    /**
     * Returns a copy with one flag changed.
     *
     * @param value whether the element is focused
     * @return the value with the requested focused
     */
    public InteractionState withFocused(boolean value) {
        return new InteractionState(value, hovered, pressed, disabled);
    }

    /**
     * Returns the same value with hovered replaced.
     *
     * @param value whether the pointer is over the element
     * @return the value with the requested hovered
     */
    public InteractionState withHovered(boolean value) {
        return new InteractionState(focused, value, pressed, disabled);
    }

    /**
     * Returns the same value with pressed replaced.
     *
     * @param value whether the element is pressed
     * @return the value with the requested pressed
     */
    public InteractionState withPressed(boolean value) {
        return new InteractionState(focused, hovered, value, disabled);
    }

    /**
     * Returns the same value with disabled replaced.
     *
     * @param value the requested disabled
     * @return the value with the requested disabled
     */
    public InteractionState withDisabled(boolean value) {
        return new InteractionState(focused, hovered, pressed, value);
    }
}
