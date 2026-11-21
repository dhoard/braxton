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

package io.github.dhoard.braxton.line;

import io.github.dhoard.braxton.style.Style;
import java.util.List;

/**
 * The styles one row of text paints in, cluster by cluster.
 *
 * <p>A highlighter is a function of the text alone: the editor asks for the styles of the row it is
 * painting and merges each one over the clusters it names, so a highlighter never sees a surface, a
 * theme, a caret or a frame, and the row's geometry is not its business. That is what keeps
 * highlighting from moving the caret or changing how a wide glyph is measured - the renderer's
 * cluster arithmetic is the same with and without one.</p>
 *
 * <p>The answer is aligned with the row's grapheme clusters, so element {@code i} styles cluster
 * {@code i}. A shorter list leaves the remaining clusters in the line's own style, and a null
 * element leaves its cluster alone, which is what a highlighter that colors only some tokens
 * returns. The style is merged over the line's style rather than replacing it, so the theme's
 * foreground still applies where the highlighter sets only a weight or a background.</p>
 *
 * @see LineEditor#withHighlighter(Highlighter)
 */
@FunctionalInterface
public interface Highlighter {

    /**
     * The styles for the clusters of one row of text.
     *
     * @param text the one row being painted; never null and never two rows
     * @return the per-cluster styles, or null or an empty list for a row with no highlighting
     */
    List<Style> styles(String text);
}
