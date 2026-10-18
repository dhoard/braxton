/*
 * Copyright (c) 2026-present Doug Hoard
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

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * Tab expansion shared by the widgets that lay out text themselves.
 *
 * <p>The layout engine expands a tab to the next four-column stop, so a widget that measures or pads
 * a cell must expand it the same way; otherwise a measured column and a displayed column disagree.
 * Styles and links are preserved.</p>
 */
final class TabStops {

    /** Columns between stops, matching the layout engine's text wrapping. */
    static final int WIDTH = 4;

    private TabStops() {
    }

    /** {@code text} with every tab replaced by spaces up to the next stop on its own line. */
    static StyledText expand(StyledText text) {
        StyledText out = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            StringBuilder expanded = new StringBuilder();
            int used = 0;
            for (String cluster : TextMeasure.graphemes(span.text())) {
                if ("\n".equals(cluster)) {
                    expanded.append('\n');
                    used = 0;
                } else if ("\t".equals(cluster)) {
                    int stop = WIDTH - (used % WIDTH);
                    expanded.append(" ".repeat(stop));
                    used += stop;
                } else {
                    expanded.append(cluster);
                    used += TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
                }
            }
            out = out.append(StyledText.of(expanded.toString(), span.style(), span.link()));
        }
        return out;
    }
}
