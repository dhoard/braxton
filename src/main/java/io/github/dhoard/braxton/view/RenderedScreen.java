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

package io.github.dhoard.braxton.view;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import java.util.List;

/** Immutable inspection result of Braxton's actual layout, including wide-cell continuations.
 *
 * @param rows the number of rows
 * @param cols the number of columns
 * @param cells the cells of each row, in paint order
 * @param regions the focus regions declared while painting, in paint order
 * @param cursor the cursor the layout resolved, in screen cells, or empty when none is shown
 */
public record RenderedScreen(
        int rows, int cols, List<List<Cell>> cells, List<Region> regions, java.util.Optional<Cursor> cursor) {
    public record Cell(String text, Style style, String link, int columns, boolean continuation) {
        public Cell {
            java.util.Objects.requireNonNull(text, "text");
            java.util.Objects.requireNonNull(style, "style");
            if (columns < 0 || columns > 2 || continuation != (columns == 0)) {
                throw new IllegalArgumentException("invalid cell occupancy");
            }
        }

        public boolean isEmpty() {
            return text.isEmpty() && style.isPlain() && link == null;
        }
    }

    /**
     * One focusable element's painted rectangle, in paint order.
     *
     * @param id the focus identity that declared the region
     * @param bounds the rectangle it was painted in
     * @param reachable whether pointer input can reach it: false for a layer a modal covers, which
     *     is painted but can no longer be pointed at
     */
    public record Region(ElementId id, Rect bounds, boolean reachable) {
        public Region {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(bounds, "bounds");
        }

        /**
         * A reachable region: the shape every region had before pointer input existed.
         *
         * @param id the focus identity that declared the region
         * @param bounds the rectangle it was painted in
         */
        public Region(ElementId id, Rect bounds) {
            this(id, bounds, true);
        }
    }

    /**
     * A snapshot with no cursor, for callers that only read cells.
     *
     * @param rows the number of rows
     * @param cols the number of columns
     * @param cells the cells of each row, in paint order
     * @param regions the focus regions declared while painting, in paint order
     */
    public RenderedScreen(int rows, int cols, List<List<Cell>> cells, List<Region> regions) {
        this(rows, cols, cells, regions, java.util.Optional.empty());
    }

    public RenderedScreen {
        cells = cells.stream().map(List::copyOf).toList();
        regions = List.copyOf(regions);
        cursor = cursor == null ? java.util.Optional.empty() : cursor;
        if (rows < 0 || cols < 0 || cells.size() != rows || cells.stream().anyMatch(r -> r.size() != cols)) {
            throw new IllegalArgumentException("snapshot dimensions do not match cells");
        }
    }

    public Cell cell(int row, int col) {
        return cells.get(row).get(col);
    }

    public boolean isContinuation(int row, int col) {
        return cell(row, col).continuation();
    }

    public boolean isEmpty() {
        return rows == 0 || cols == 0;
    }

    /**
     * Plain text of one row, trailing blanks trimmed.
     *
     * <p>Only U+0020 is trimmed: every padding path in Braxton pads with a blank, so any other
     * trailing whitespace is a painted cell, reported exactly as {@code Screen.plainLine} keeps it
     * and the encoder emits it.</p>
     */
    public String plainLine(int row) {
        StringBuilder out = new StringBuilder();
        for (Cell cell : cells.get(row))
            if (!cell.continuation()) out.append(cell.text().isEmpty() ? " " : cell.text());
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }

    public List<String> plainLines() {
        return java.util.stream.IntStream.range(0, rows)
                .mapToObj(this::plainLine)
                .toList();
    }

    public String plainText() {
        return String.join("\n", plainLines());
    }
}
