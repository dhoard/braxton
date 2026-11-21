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

package io.github.dhoard.braxton.internal.render;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.render.Canvas;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import java.util.List;

/**
 * A fixed-size grid of painted cells, backed by {@link PackedCells}.
 *
 * <p>This is the temporary bridge from the object-cell renderer to the public {@link Canvas}: the
 * layout engine still paints through it, while applications can read a frame without importing an
 * internal package. WP-3.6 removes the old view API and this bridge together.</p>
 */
public final class Screen implements Surface {

    // Bound both allocation and iteration (including an empty screen with one enormous axis).
    private static final int MAX_CELLS = 4_194_304;

    private final PackedCells cells;

    /**
     * Creates a {@code Screen} value.
     *
     * @param rows the number of rows
     * @param cols the number of columns
     */
    public Screen(int rows, int cols) {
        int safeRows = Math.max(0, rows);
        int safeCols = Math.max(0, cols);
        if ((long) Math.max(1, safeRows) * Math.max(1, safeCols) > MAX_CELLS) {
            throw new IllegalArgumentException(
                    "screen dimensions exceed " + MAX_CELLS + " cells: " + rows + "x" + cols);
        }
        cells = new PackedCells(safeRows, safeCols);
    }

    /**
     * Returns the number of rows.
     *
     * @return the number of rows
     */
    public int rows() {
        return cells.rows();
    }

    /**
     * Returns the number of columns.
     *
     * @return the number of columns
     */
    public int cols() {
        return cells.cols();
    }

    /**
     * Tests whether at least one cell dimension is zero.
     *
     * @return {@code true} if at least one cell dimension is zero; otherwise {@code false}
     */
    public boolean isEmpty() {
        return rows() == 0 || cols() == 0;
    }

    /**
     * Returns a value snapshot of the specified cell.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return a value snapshot of the specified cell
     */
    public Cell cell(int row, int col) {
        return cells.cell(row, col);
    }

    /**
     * Tests whether the cell continues a wide grapheme from its left neighbor.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return {@code true} if the cell continues a wide grapheme from its left neighbor; otherwise {@code false}
     */
    public boolean isContinuation(int row, int col) {
        return cells.isContinuation(row, col);
    }

    /**
     * Non-allocating field reads for the encoder's hot path.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return non-allocating field reads for the encoder's hot path
     */
    public String text(int row, int col) {
        return cells.text(row, col);
    }

    /**
     * Returns the resolved style stored at the specified cell.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the resolved style stored at the specified cell
     */
    public Style style(int row, int col) {
        return cells.style(row, col);
    }

    /**
     * Returns the hyperlink target stored at the specified cell.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the hyperlink target stored at the specified cell
     */
    public String link(int row, int col) {
        return cells.link(row, col);
    }

    /**
     * Tests whether the specified cell holds no painted content.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return {@code true} if the specified cell holds no painted content; otherwise {@code false}
     */
    public boolean isEmptyCell(int row, int col) {
        return cells.isEmptyCell(row, col);
    }

    /**
     * Clears a cell and the other half of any wide glyph it belongs to.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     */
    public void clear(int row, int col) {
        cells.clear(row, col);
    }

    /**
     * Writes one grapheme cluster; a two-column glyph marks the next cell as a continuation.
     */
    @Override
    public void put(int row, int col, String grapheme, Style style, String link, int width) {
        cells.put(row, col, grapheme, style, link, width);
    }

    /**
     * Replaces one cell's style, keeping its text, link and continuation flag.
     */
    @Override
    public void restyle(int row, int col, Style style) {
        cells.restyle(row, col, style);
    }

    /**
     * Fills a rectangle with a style (spaces), clearing continuations first.
     */
    @Override
    public void fill(Rect rect, Style style) {
        cells.fill(rect, style);
    }

    /**
     * Plain text of one row, trailing blanks trimmed.
     *
     * @param row the zero-based row
     * @return plain text of one row, trailing blanks trimmed
     */
    public String plainLine(int row) {
        return cells.plainLine(row);
    }

    /**
     * Returns the painted text as separate rows.
     *
     * @return the painted text as separate rows
     */
    public List<String> plainLines() {
        return cells.plainLines();
    }

    /**
     * Returns the painted text joined with line breaks.
     *
     * @return the painted text joined with line breaks
     */
    public String plainText() {
        return String.join("\n", plainLines());
    }

    /**
     * A read-only application-facing view over this frame.
     *
     * @return a read-only application-facing view over this frame
     */
    public Canvas toCanvas() {
        return new Canvas(
                rows(),
                cols(),
                (row, col) -> {
                    Cell cell = cells.cell(row, col);
                    return new Canvas.Cell(cell.text(), cell.style(), cell.link());
                },
                cells::isContinuation,
                this::plainLines);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Screen screen && cells.equals(screen.cells);
    }

    @Override
    public int hashCode() {
        return cells.hashCode();
    }

    @Override
    public String toString() {
        return "Screen[" + rows() + "x" + cols() + "]";
    }
}
