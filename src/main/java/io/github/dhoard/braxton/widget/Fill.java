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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Widget;

/**
 * A leaf that paints every cell of its rectangle with one style.
 *
 * <p>A fill is a background: it takes the whole rectangle it is offered and writes a space carrying
 * its style into every cell, so what an earlier layer painted underneath is replaced rather than
 * tinted. It is opaque on purpose - a translucent fill would have to blend, and a terminal has no
 * blend - so it belongs at the bottom of a stack or over a region being cleared.</p>
 *
 * <p>It answers neither focus nor pointer: a background is not a target, and a click on a filled
 * region belongs to the content above it or to nothing at all.</p>
 *
 * @param <Msg> the message type of the tree this widget is placed in
 */
public final class Fill<Msg> implements Widget<Style, Msg> {

    private static final Fill<?> INSTANCE = new Fill<>();

    private Fill() {}

    /**
     * The fill widget.
     *
     * @param <Msg> the message type of the tree this widget is placed in
     * @return the widget, which holds no state and is safe to share
     */
    @SuppressWarnings("unchecked")
    public static <Msg> Fill<Msg> of() {
        return (Fill<Msg>) INSTANCE;
    }

    /**
     * Takes every cell it is offered: a fill backs whatever space its parent gives it.
     */
    @Override
    public Size measure(Size max, Style model) {
        return max;
    }

    /**
     * Writes a styled space into every cell of the rectangle, in paint order.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Style model) {
        Style style = model == null ? Style.NONE : model;
        String blank = " ";
        for (int row = bounds.row(); row < bounds.row() + bounds.rows(); row++) {
            for (int col = bounds.col(); col < bounds.col() + bounds.cols(); col++) {
                surface.put(row, col, blank, style, null, 1);
            }
        }
    }

    /**
     * A background never takes focus.
     */
    @Override
    public boolean canFocus(Style model) {
        return false;
    }

    /**
     * A click on a filled cell belongs to what is above the fill, never to the fill.
     */
    @Override
    public boolean hit(Rect bounds, int row, int col, Style model) {
        return false;
    }
}
