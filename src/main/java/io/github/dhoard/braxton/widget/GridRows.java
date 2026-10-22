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

import java.util.ArrayList;
import java.util.List;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/**
 * Row arithmetic shared by the widgets that lay a grid of cells out themselves.
 *
 * <p>A rendered row is painted as one grapheme-cluster sequence, so a cell's columns are charged to
 * the row it is painted in rather than to the cell measured on its own. A cluster with no base
 * character of its own - a combining mark, a variation selector, a joiner - at the head of a cell
 * joins the cell before it, which is that cell's pad space or a frame's edge character, instead of
 * occupying a cell of its own, and a variation selector widens the cluster it joins. The row then
 * occupies a different number of columns from the sum of its cells: padding each cell from its own
 * measurement leaves the row short of the column boundary and lands every cell after it - a frame's
 * right edge included - left of the row above.</p>
 */
final class GridRows {

    private GridRows() {
    }

    /**
     * Spaces that pad {@code cell} to a column {@code width} columns wide, where {@code row} is the
     * plain text already painted before it. The cell costs the difference between the row with the
     * cell appended and the row without it, so a cluster that joins the cell before it is not charged
     * a column the paint path never gives it, and one that widens that cell is charged the column it
     * really takes.
     */
    static int pad(String row, String cell, int width) {
        int before = TextMeasure.columns(row);
        return Math.max(0, width - (TextMeasure.columns(row + cell) - before));
    }

    /**
     * {@code lines} padded with spaces so every row occupies the columns of the widest one.
     *
     * <p>A cell that opens with a cluster which widens the cell before it - a variation selector
     * joined to the base of the previous cell's pad - is painted a column more than the column
     * measured for it, and no pad can take that column back. The grid pays it on every row rather
     * than leaving a ragged edge.</p>
     */
    static List<StyledText> shareOneWidth(List<StyledText> lines) {
        int width = 0;
        for (StyledText line : lines) {
            width = Math.max(width, TextMeasure.columns(line.plainText()));
        }
        List<StyledText> padded = new ArrayList<>(lines.size());
        for (StyledText line : lines) {
            int pad = width - TextMeasure.columns(line.plainText());
            padded.add(pad <= 0 ? line : line.append(StyledText.of(" ".repeat(pad), Style.NONE)));
        }
        return padded;
    }
}
