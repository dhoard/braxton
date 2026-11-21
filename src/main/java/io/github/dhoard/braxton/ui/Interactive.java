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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;

/**
 * The interaction a composite widget carries for the elements it composes.
 *
 * <p>A composite widget builds a tree of leaves - rows of text, a frame, a bar - and none of those
 * leaves is the widget: the selection a key moves belongs to the composite's model, not to a line of
 * text. This interface is that widget as a leaf: it occupies the rectangle it is placed in, paints
 * nothing of its own (its elements paint the cells), and answers the keys, pointer reports and caret
 * for everything inside.</p>
 *
 * <p>The placement rule is what makes it compose with the content: in an {@link Element.Stack} the
 * surface measures as no cells, so it never enlarges the rectangle its content asked for, and it is
 * arranged into the whole rectangle the stack resolved, so it is on top of every cell and is what a
 * pointer report and a focus walk reach first.</p>
 *
 * <pre>{@code
 * // A widget's scene: the content, and the widget that answers for it.
 * return Elements.stack(content, new Element.Leaf<>(this, model, Sizing.measured()));
 * }</pre>
 *
 * @param <M> the composite's model type
 * @param <Msg> the messages the composite emits
 */
public interface Interactive<M, Msg> extends Widget<M, Msg> {

    /**
     * Reserves no cells: the elements the composite composes decide the rectangle.
     */
    @Override
    default Size measure(Size max, M model) {
        return new Size(0, 0);
    }

    /**
     * Takes the whole rectangle it is placed in, so it covers the content it answers for.
     */
    @Override
    default Rect arrange(Rect available, Size measured, M model) {
        return available;
    }

    /**
     * Paints nothing: the composite's elements paint every cell.
     */
    @Override
    default void paint(Surface surface, Rect bounds, M model) {}

    /**
     * A composite takes its turn in the focus walk; its own model decides whether it holds focus.
     */
    @Override
    default boolean canFocus(M model) {
        return true;
    }
}
