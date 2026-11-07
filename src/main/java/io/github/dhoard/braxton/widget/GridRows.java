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

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>Only a row's trailing cluster can take part in that join, so {@link Row} carries that cluster
 * and not the text painted before it. Charging a cell against the whole row text - measuring
 * {@code row + cell} and re-measuring {@code row} - copied and segmented the growing prefix once per
 * cell, which made assembling one row quadratic in its column count: a 200-row table of
 * ten-character cells cost 1.2 MB of garbage at 8 columns and 186 MB at 256, and an application that
 * renders a table from its state pays that once per frame.</p>
 */
final class GridRows {

    private GridRows() {}

    /**
     * The cells a row has painted so far, kept as the one cluster a following cell can join: the
     * trailing cluster of the row as it is painted, and the columns that cluster occupies.
     */
    static final class Row {
        private String tail;
        private int tailColumns;

        private Row(String tail, int tailColumns) {
            this.tail = tail;
            this.tailColumns = tailColumns;
        }

        /** A row with nothing painted yet. */
        static Row empty() {
            return new Row(null, 0);
        }

        /**
         * A row that already carries {@code painted}, such as a quote's bar and the space after it.
         * Its trailing cluster is what the first cell is charged against.
         */
        static Row after(String painted) {
            if (painted == null || painted.isEmpty()) {
                return empty();
            }
            String last = lastCluster(painted);
            return new Row(last, TextMeasure.columns(last));
        }

        /**
         * Spaces that pad {@code cell} to a column {@code width} columns wide, charging the cell -
         * and the pad, which the caller paints after it - to this row. The cell costs the difference
         * between the row with the cell appended and the row without it, so a cluster that joins the
         * cell before it is not charged a column the paint path never gives it, and one that widens
         * that cell is charged the column it really takes.
         */
        int pad(String cell, int width) {
            return pad(cell, width, 0);
        }

        /**
         * {@link #pad(String, int)} plus a {@code gap} of columns the grid paints after every cell,
         * which is the separator {@code Markup} puts between its columns. The gap is part of what
         * the row really paints, so it is charged to the row with the cell.
         */
        int pad(String cell, int width, int gap) {
            String value = cell == null ? "" : cell;
            int added = 0;
            String last = tail;
            int lastColumns = tailColumns;
            if (!value.isEmpty()) {
                if (tail != null && TextMeasure.isPrintableAscii(tail.charAt(tail.length() - 1)) && isAscii(value)) {
                    // No grapheme break rule joins two printable ASCII characters, so the cell is a
                    // run of clusters of its own and the seam needs neither a copy nor a
                    // segmentation - which is what an ordinary table row is made of.
                    added = value.length();
                    last = value.substring(value.length() - 1);
                    lastColumns = 1;
                } else if (tail == null) {
                    added = TextMeasure.columns(value);
                    last = lastCluster(value);
                    lastColumns = TextMeasure.columns(last);
                } else {
                    // Every cluster of the row before its trailing one stays where it was, so the
                    // seam is decided by that cluster and the cell alone.
                    String joined = tail + value;
                    added = TextMeasure.columns(joined) - tailColumns;
                    last = lastCluster(joined);
                    lastColumns = TextMeasure.columns(last);
                }
            }
            int pad = Math.max(0, width - added) + Math.max(0, gap);
            if (pad > 0) {
                // The pad's last space is the cluster the cell after this one is charged against.
                tail = " ";
                tailColumns = 1;
            } else {
                tail = last;
                tailColumns = lastColumns;
            }
            return pad;
        }
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

    /**
     * The last grapheme cluster of {@code text}, which is never empty here. The cluster boundary at
     * or before the last unit starts it, so no other cluster of the text is materialized.
     */
    private static String lastCluster(String text) {
        return text.substring(TextMeasure.boundaryBefore(text, text.length() - 1));
    }

    /** True when every character of {@code value} is printable ASCII; see the per-character rule. */
    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!TextMeasure.isPrintableAscii(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
