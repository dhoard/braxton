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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.ui.Widget;
import java.util.Objects;

/**
 * A leaf that paints nothing and takes whatever space its sizing gives it.
 *
 * <p>A spacer is how a row holds a gap open: a fixed one is a margin, a grown one pushes what
 * follows it to the far edge. It paints no cell, so it never leaves a background of its own, and it
 * answers neither focus nor pointer, so a click on a gap belongs to whatever is behind it.</p>
 *
 * @param <Msg> the message type of the tree this widget is placed in
 */
public final class Spacer<Msg> implements Widget<Spacer.Model, Msg> {

    private static final Spacer<?> SHARED = new Spacer<>();

    /**
     * A spacer holds no state.
     */
    public enum Model {
        /**
         * The only model a spacer has.
         */
        NONE
    }

    private Spacer() {}

    /**
     * The shared spacer.
     *
     * @param <Msg> the message type of the tree this widget is placed in
     * @return the widget
     */
    @SuppressWarnings("unchecked")
    public static <Msg> Spacer<Msg> of() {
        return (Spacer<Msg>) SHARED;
    }

    /**
     * A gap measures nothing: its sizing decides how much space it takes.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(0, 0);
    }

    /**
     * A gap fills whatever it is given.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Model model) {
        return available;
    }

    @Override
    public void paint(Surface surface, Rect bounds, Model model) {
        Objects.requireNonNull(surface, "surface");
    }

    /**
     * A gap never takes focus.
     */
    @Override
    public boolean canFocus(Model model) {
        return false;
    }

    /**
     * A click on a gap belongs to whatever is behind it.
     */
    @Override
    public boolean hit(Rect bounds, int row, int col, Model model) {
        return false;
    }
}
