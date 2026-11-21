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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextWrap;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Widget;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A frame's chrome: the border and the title a framed region draws around content it does not own.
 *
 * <p>A frame is a decoration, not a container. It paints the perimeter of the rectangle it is given
 * and nothing inside it, so content is composed beside it - {@code Element.Stack} holds the chrome
 * and a {@code Element.Pad} of the content's inset, and the two never have to agree on a size. That
 * is also why the frame answers no pointer report: a click inside a bordered region belongs to what
 * is inside it, never to the edge that surrounds it.</p>
 *
 * <p>The painting matches the renderer's border node cell for cell: corners in the border's
 * characters, edges between them, and the title on the top edge starting two columns in, wrapped to
 * the width that leaves a column of edge on each side. A frame whose top row the application builds
 * itself - a tab bar joining the content below it - takes that row instead of the title: the builder
 * is given the columns the frame resolved and paints no further than them.</p>
 *
 * @param <Msg> the message type of the tree this widget is placed in
 */
public final class Frame<Msg> implements Widget<Frame.Model, Msg> {

    private static final Frame<?> SINGLE = new Frame<>(WidthPolicy.PINNED);

    private final WidthPolicy policy;

    private Frame(WidthPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * What a frame draws.
     *
     * @param border the border characters; never null, and {@link Border#NONE} draws nothing
     * @param title the title on the top edge; empty for none, never null
     * @param style the style of the border and the title
     * @param topEdge builds the top row from the columns the frame resolved; null draws the title
     */
    public record Model(Border border, StyledText title, Style style, Function<Integer, StyledText> topEdge) {

        /**
         * Rejects nothing: a frame with no border draws nothing.
         *
         * <p>A frame that builds its own top row ignores the title, because the builder owns that
         * row: two owners of one row would paint over each other in a fixed order that no caller can
         * see.</p>
         */
        public Model {
            border = border == null ? Border.NONE : border;
            title = title == null ? StyledText.of("", Style.NONE) : title;
            style = style == null ? Style.NONE : style;
            if (topEdge != null) {
                title = StyledText.of("", Style.NONE);
            }
        }

        /**
         * A frame with a border, no title and no top-row builder.
         *
         * @param border the border characters; required
         * @param title the title on the top edge; null or empty for none
         * @param style the style of the border and the title
         */
        public Model(Border border, StyledText title, Style style) {
            this(border, title, style, null);
        }

        /**
         * A frame with a border and no title.
         *
         * @param border the border characters; required
         * @return the model
         */
        public static Model of(Border border) {
            return new Model(border, null, null);
        }

        /**
         * This frame with a title on its top edge.
         *
         * @param value the title; null or empty for none
         * @return the model
         */
        public Model withTitle(StyledText value) {
            return new Model(border, value, style, topEdge);
        }

        /**
         * This frame drawn in another style.
         *
         * @param value the style; null for none
         * @return the model
         */
        public Model withStyle(Style value) {
            return new Model(border, title, value, topEdge);
        }

        /**
         * This frame with a top row the application builds.
         *
         * @param value builds the row from the available columns; null draws the title instead
         * @return the model
         */
        public Model withTopEdge(Function<Integer, StyledText> value) {
            return new Model(border, title, style, value);
        }
    }

    /**
     * A frame that draws the default single-line border.
     *
     * @param <Msg> the message type of the tree this widget is placed in
     * @return the widget
     */
    @SuppressWarnings("unchecked")
    public static <Msg> Frame<Msg> of() {
        return (Frame<Msg>) SINGLE;
    }

    /**
     * A frame measuring with the given width policy.
     *
     * @param <Msg> the message type of the tree this widget is placed in
     * @param policy how a cluster nobody has assigned a width is measured; required
     * @return the widget
     */
    public static <Msg> Frame<Msg> of(WidthPolicy policy) {
        return new Frame<>(policy);
    }

    /**
     * A decoration asks for nothing: the content beside it decides the size.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(0, 0);
    }

    /**
     * A frame never takes focus; the widget inside it does.
     */
    @Override
    public boolean canFocus(Model model) {
        return false;
    }

    /**
     * A click inside a framed region belongs to its content, never to the edge.
     */
    @Override
    public boolean hit(Rect bounds, int row, int col, Model model) {
        return false;
    }

    /**
     * A decoration fills the rectangle its parent gives it; the content beside it sets the size.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Model model) {
        return available;
    }

    @Override
    public void paint(Surface surface, Rect bounds, Model model) {
        if (model == null || !model.border().isVisible() || bounds.rows() < 2 || bounds.cols() < 2) {
            return;
        }
        Border border = model.border();
        Style style = model.style();
        int top = bounds.row();
        int left = bounds.col();
        int bottom = top + bounds.rows() - 1;
        int right = left + bounds.cols() - 1;
        boolean built = model.topEdge() != null;
        if (!built) {
            surface.put(top, left, Border.glyph(border.topLeft()), style, null, 1);
            surface.put(top, right, Border.glyph(border.topRight()), style, null, 1);
        }
        surface.put(bottom, left, Border.glyph(border.bottomLeft()), style, null, 1);
        surface.put(bottom, right, Border.glyph(border.bottomRight()), style, null, 1);
        for (int col = left + 1; col < right; col++) {
            if (!built) {
                surface.put(top, col, Border.glyph(border.horizontal()), style, null, 1);
            }
            surface.put(bottom, col, Border.glyph(border.horizontal()), style, null, 1);
        }
        for (int row = top + 1; row < bottom; row++) {
            surface.put(row, left, Border.glyph(border.vertical()), style, null, 1);
            surface.put(row, right, Border.glyph(border.vertical()), style, null, 1);
        }
        if (built) {
            // The builder owns the row and paints no further than the frame's own columns.
            StyledText row = model.topEdge().apply(bounds.cols());
            if (row != null && !row.isEmpty()) {
                surface.writeLine(top, left, bounds.cols(), row, policy);
            }
        } else {
            paintTitle(surface, bounds, model);
        }
    }

    /**
     * The title sits on the top edge two columns in, so an edge character stays either side of it.
     */
    private void paintTitle(Surface surface, Rect bounds, Model model) {
        StyledText title = model.title();
        int cols = Math.max(0, bounds.cols() - 4);
        if (title.isEmpty() || cols <= 0) {
            return;
        }
        List<StyledText> lines = TextWrap.wrap(title, policy, cols, 1);
        if (lines.isEmpty()) {
            return;
        }
        surface.writeLine(bounds.row(), bounds.col() + 2, cols, lines.get(0), policy);
    }
}
