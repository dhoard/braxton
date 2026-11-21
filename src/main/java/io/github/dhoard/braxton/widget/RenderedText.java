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
import java.util.function.IntFunction;

/**
 * A leaf that renders styled text for the width it is given.
 *
 * <p>A widget whose frame is computed rather than painted cell by cell - a bar, a gradient, a
 * rendered table - keeps its renderer pure and lets this leaf place the result: the text is
 * rendered again at the width the layout resolved, so a responsive widget fills its region and a
 * fixed one keeps its own columns, and every row is written whole by
 * {@link Surface#writeLine}.</p>
 *
 * @param <Msg> the message type of the tree this widget is placed in
 */
final class RenderedText<Msg> implements Widget<Void, Msg> {

    private final IntFunction<StyledText> body;

    private RenderedText(IntFunction<StyledText> body) {
        this.body = body;
    }

    /**
     * An element whose content is rendered for the columns it is given.
     *
     * @param <Msg> the message type of the tree
     * @param body renders the text for a number of columns; required
     * @return the element
     */
    static <Msg> Element<Msg> of(IntFunction<StyledText> body) {
        return new Element.Leaf<Void, Msg>(new RenderedText<>(body), null, Sizing.measured());
    }

    @Override
    public Size measure(Size max, Void model) {
        if (max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        List<StyledText> lines = render(max.cols()).lines();
        int cols = 0;
        for (StyledText line : lines) {
            cols = Math.max(
                    cols, io.github.dhoard.braxton.style.TextMeasure.columns(line.plainText(), WidthPolicy.PINNED));
        }
        return new Size(Math.min(max.rows(), lines.size()), Math.min(max.cols(), cols));
    }

    @Override
    public void paint(Surface surface, Rect bounds, Void model) {
        if (bounds.rows() <= 0 || bounds.cols() <= 0) {
            return;
        }
        List<StyledText> lines = render(bounds.cols()).lines();
        int limit = Math.min(bounds.rows(), lines.size());
        for (int index = 0; index < limit; index++) {
            surface.writeLine(bounds.row() + index, bounds.col(), bounds.cols(), lines.get(index), WidthPolicy.PINNED);
        }
    }

    private StyledText render(int cols) {
        StyledText rendered = body.apply(cols);
        return rendered == null ? StyledText.empty() : rendered;
    }
}
