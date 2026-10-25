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

package io.github.dhoard.braxton.view;

import java.util.List;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;

/** Immutable inspection result of Braxton's actual layout, including wide-cell continuations. */
public record RenderedScreen(int rows, int cols, List<List<Cell>> cells, List<Region> regions) {
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

    public record Region(ElementId id, Rect bounds) {
        public Region {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(bounds, "bounds");
        }
    }

    public RenderedScreen {
        cells = cells.stream().map(List::copyOf).toList();
        regions = List.copyOf(regions);
        if (rows < 0
                || cols < 0
                || cells.size() != rows
                || cells.stream().anyMatch(r -> r.size() != cols)) {
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
