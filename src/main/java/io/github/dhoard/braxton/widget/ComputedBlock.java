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
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.Widget;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A block of computed rows: exactly the rows a widget reserved, rendered for the columns it is given.
 *
 * <p>A widget whose frame is computed - a list's chrome, a picker's rows, a table's lines - knows how
 * many rows it paints before it knows how wide the terminal is, and its renderer needs the width. This
 * leaf closes that gap: it reserves its row count, so the layout resolves without running the
 * renderer, and it renders once when it paints. A renderer that is pure but not free therefore runs
 * once per painted frame and never for a row the window hides.</p>
 *
 * <p>The text is rendered as a value with one line per row, and each line is written whole by
 * {@link Surface#writeLine}, so a style boundary or a cluster that continues the cell before it cannot
 * move a row's cells.</p>
 *
 * @param <Msg> the message type of the tree the block is placed in
 */
final class ComputedBlock<Msg> implements Widget<Void, Msg> {

    private final int rows;
    private final IntFunction<StyledText> body;

    private ComputedBlock(int rows, IntFunction<StyledText> body) {
        this.rows = rows;
        this.body = Objects.requireNonNull(body, "body");
    }

    /**
     * An element occupying exactly {@code rows} rows of its parent's main axis.
     *
     * @param <Msg> the message type of the tree
     * @param rows the rows the block occupies; never negative
     * @param body renders the block's text for a number of columns; required
     * @return the element
     */
    static <Msg> Element<Msg> of(int rows, IntFunction<StyledText> body) {
        return new Element.Leaf<Void, Msg>(new ComputedBlock<>(rows, body), null, Sizing.fixed(rows));
    }

    @Override
    public Size measure(Size max, Void model) {
        return new Size(Math.min(Math.max(0, max.rows()), rows), Math.max(0, max.cols()));
    }

    @Override
    public void paint(Surface surface, Rect bounds, Void model) {
        if (bounds.rows() <= 0 || bounds.cols() <= 0) {
            return;
        }
        List<StyledText> lines = body.apply(bounds.cols()).lines();
        int limit = Math.min(bounds.rows(), lines.size());
        for (int index = 0; index < limit; index++) {
            surface.writeLine(bounds.row() + index, bounds.col(), bounds.cols(), lines.get(index), WidthPolicy.PINNED);
        }
    }
}
