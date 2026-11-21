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

package io.github.dhoard.braxton.render;

import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.TextWrap;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.util.List;

/**
 * The cell-safe text painter behind {@link Surface#write} and {@link Surface#writeLine}.
 */
final class SurfaceWriter {

    private SurfaceWriter() {}

    static void write(Surface surface, Rect bounds, StyledText text, WidthPolicy policy) {
        if (text == null || bounds == null || bounds.rows() <= 0 || bounds.cols() <= 0) {
            return;
        }
        List<StyledText> lines = TextWrap.wrap(text, policy, bounds.cols(), bounds.rows());
        int limit = Math.min(bounds.rows(), lines.size());
        for (int index = 0; index < limit; index++) {
            writeLine(surface, bounds.row() + index, bounds.col(), bounds.cols(), lines.get(index), policy);
        }
    }

    static void writeLine(Surface surface, int row, int col, int cols, StyledText line, WidthPolicy policy) {
        if (line == null || cols <= 0) {
            return;
        }
        int next = col;
        int limit = col + cols;
        String cell = null;
        int cellCol = col;
        int cellWidth = 0;
        Style cellStyle = Style.NONE;
        String cellLink = null;
        StringBuilder leading = null;
        for (StyledText.Span span : line.spans()) {
            for (String cluster : Graphemes.iterate(span.text())) {
                int width = TextMeasure.clusterColumns(cluster, policy);
                // A slow path only for the clusters that can continue a cell: a mark or a selector a
                // style boundary split off the base it belongs to widens that base instead of taking
                // a column of its own, and the cell is measured again with it.
                if ((width > 0 || TextWrap.hasNoBase(cluster))
                        && cell != null
                        && TextWrap.continuesCluster(cell, cluster)) {
                    cell = cell + cluster;
                    cellWidth = TextMeasure.clusterColumns(cell, policy);
                    continue;
                }
                if (width == 0) {
                    // An invisible cluster claims no column, so it stays inside the cell already
                    // painted; one that leads the line is held for the cell that follows it.
                    if (cell == null) {
                        if (leading == null) {
                            leading = new StringBuilder();
                        }
                        leading.append(cluster);
                    } else {
                        cell = cell + cluster;
                    }
                    continue;
                }
                if (cell != null) {
                    if (next + cellWidth > limit) {
                        return;
                    }
                    surface.put(row, cellCol, cell, cellStyle, cellLink, cellWidth);
                    next = cellCol + cellWidth;
                }
                cell = open(cluster, leading);
                cellWidth = charged(cluster, leading, policy);
                leading = null;
                cellCol = next;
                cellStyle = span.style();
                cellLink = span.link();
            }
        }
        if (cell != null && next + cellWidth <= limit) {
            surface.put(row, cellCol, cell, cellStyle, cellLink, cellWidth);
        }
    }

    /**
     * The columns a cell is charged, which is what the row was measured with.
     *
     * <p>A cluster with a base of its own is charged for itself: the invisible run that led the line
     * travels with the cell it precedes but is not part of it, because measuring the cell with the
     * run in front of it charges a column the row never spent - a selector before a space turns that
     * space into two columns and every cell after it moves.</p>
     *
     * <p>A cluster with no base of its own is charged for the dotted circle drawn behind it, run
     * included: the circle is the cell's base, so a run that widens the circle widens the cell, and
     * that is the width the row's own measurement charges for the cluster.</p>
     */
    private static int charged(String cluster, CharSequence leading, WidthPolicy policy) {
        if (!TextWrap.hasNoBase(cluster)) {
            return TextMeasure.clusterColumns(cluster, policy);
        }
        return TextMeasure.clusterColumns(TextMeasure.DOTTED_CIRCLE + text(leading) + cluster, policy);
    }

    private static String text(CharSequence leading) {
        return leading == null ? "" : leading.toString();
    }

    /**
     * The text a cell paints.
     *
     * <p>A cell with no base character of its own - a combining mark that leads a line - is painted
     * with the dotted circle that stands in for the missing base, exactly as the wrap charged the
     * row for it. A line the wrap produced already carries the circle, so this only fills it in for
     * text that was never wrapped. The invisible run that opened the line travels with the cell it
     * precedes, between the circle and the cluster: the circle stands for the missing base, and the
     * run belongs to the cell that follows it.</p>
     */
    private static String open(String cluster, CharSequence leading) {
        if (leading == null && !TextWrap.hasNoBase(cluster)) {
            return cluster;
        }
        StringBuilder opened = new StringBuilder();
        if (TextWrap.hasNoBase(cluster)) {
            opened.append(TextMeasure.DOTTED_CIRCLE);
        }
        if (leading != null) {
            opened.append(leading);
        }
        return opened.append(cluster).toString();
    }
}
