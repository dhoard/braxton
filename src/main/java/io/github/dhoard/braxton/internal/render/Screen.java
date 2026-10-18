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

package io.github.dhoard.braxton.internal.render;

import java.util.ArrayList;
import java.util.List;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;

/**
 * A fixed-size grid of painted cells.
 *
 * <p>Layout writes graphemes into the grid; the encoder reads rows back out. Writing a two-column
 * grapheme marks the following cell as a continuation, so a later narrow glyph or background fill
 * clears both halves instead of leaving a stale shadow.</p>
 */
public final class Screen {

    private final int rows;
    private final int cols;
    private final Cell[] cells;
    private final boolean[] continuation;

    public Screen(int rows, int cols) {
        this.rows = Math.max(0, rows);
        this.cols = Math.max(0, cols);
        this.cells = new Cell[this.rows * this.cols];
        this.continuation = new boolean[this.rows * this.cols];
        java.util.Arrays.fill(cells, Cell.EMPTY);
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public boolean isEmpty() {
        return rows == 0 || cols == 0;
    }

    public Cell cell(int row, int col) {
        if (!inside(row, col)) {
            return Cell.EMPTY;
        }
        return cells[index(row, col)];
    }

    public boolean isContinuation(int row, int col) {
        return inside(row, col) && continuation[index(row, col)];
    }

    private boolean inside(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    private int index(int row, int col) {
        return row * cols + col;
    }

    /**
     * Clears a cell's content and continuation flag, together with the other half of any wide glyph
     * it belongs to, so no half-painted glyph survives.
     */
    public void clear(int row, int col) {
        if (!inside(row, col)) {
            return;
        }
        if (continuation[index(row, col)] && col > 0) {
            cells[index(row, col - 1)] = Cell.EMPTY;
            continuation[index(row, col - 1)] = false;
        }
        if (col + 1 < cols && continuation[index(row, col + 1)]) {
            cells[index(row, col + 1)] = Cell.EMPTY;
            continuation[index(row, col + 1)] = false;
        }
        cells[index(row, col)] = Cell.EMPTY;
        continuation[index(row, col)] = false;
    }

    /**
     * Writes one grapheme cluster.
     *
     * <p>A style without a background is transparent: it inherits the background already painted in
     * that cell, so text drawn over a background fill (or any earlier layer) keeps the layer's
     * colour instead of reverting to the terminal default.</p>
     *
     * @param width 1 or 2 columns. A width of 2 marks the next cell as a continuation; when the
     *     second column falls outside the screen the glyph is clipped entirely. Writing a narrower
     *     glyph clears whichever half of a previous wide glyph it covers, so no stale
     *     continuation flag is left to erase later content.
     */
    public void put(int row, int col, String grapheme, Style style, String link, int width) {
        if (!inside(row, col) || grapheme == null || grapheme.isEmpty()) {
            return;
        }
        int span = Math.max(1, Math.min(2, width));
        if (span == 2 && col + 1 >= cols) {
            clear(row, col);
            return;
        }
        // Repainting over the second half of a previous wide glyph must clear its first half.
        if (span == 2) {
            clearIfContinuation(row, col + 1);
        }
        clearIfContinuation(row, col);
        cells[index(row, col)] = new Cell(grapheme, inheritBackground(row, col, style), link);
        continuation[index(row, col)] = false;
        if (span == 2) {
            cells[index(row, col + 1)] = Cell.EMPTY;
            continuation[index(row, col + 1)] = true;
        } else {
            // The new glyph occupies the first half, so the old wide glyph's second half is gone.
            clearOrphanedContinuation(row, col);
        }
    }

    /** A style with no background picks up the background already painted in the cell. */
    private Style inheritBackground(int row, int col, Style style) {
        Style safe = style == null ? Style.NONE : style;
        if (safe.background() != null) {
            return safe;
        }
        Color beneath = cells[index(row, col)].style().background();
        return beneath == null ? safe : safe.withBackground(beneath);
    }

    /** Drops a continuation flag whose wide glyph was just overwritten by a narrower one. */
    private void clearOrphanedContinuation(int row, int col) {
        int next = col + 1;
        if (inside(row, next) && continuation[index(row, next)]) {
            cells[index(row, next)] = Cell.EMPTY;
            continuation[index(row, next)] = false;
        }
    }

    private void clearIfContinuation(int row, int col) {
        if (inside(row, col) && continuation[index(row, col)] && col > 0) {
            Cell previous = cells[index(row, col - 1)];
            Cell blank = previous.style().background() == null
                    ? Cell.EMPTY
                    : new Cell("", Style.NONE.withBackground(previous.style().background()), null);
            cells[index(row, col - 1)] = blank;
            cells[index(row, col)] = blank;
            continuation[index(row, col)] = false;
        }
    }

    /** Fills a rectangle with a style (spaces), clearing continuations first. */
    public void fill(Rect rect, Style style) {
        Rect clipped = rect.clip(new Rect(0, 0, rows, cols));
        for (int row = clipped.row(); row < clipped.row() + clipped.rows(); row++) {
            for (int col = clipped.col(); col < clipped.col() + clipped.cols(); col++) {
                clear(row, col);
            }
        }
        if (style == null || style.isPlain()) {
            return;
        }
        for (int row = clipped.row(); row < clipped.row() + clipped.rows(); row++) {
            for (int col = clipped.col(); col < clipped.col() + clipped.cols(); col++) {
                cells[index(row, col)] = new Cell(" ", style, null);
            }
        }
    }

    /** Plain text of one row, trailing blanks trimmed. */
    public String plainLine(int row) {
        if (row < 0 || row >= rows) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int col = 0; col < cols; col++) {
            if (continuation[index(row, col)]) {
                continue;
            }
            text.append(cells[index(row, col)].text());
        }
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    public List<String> plainLines() {
        List<String> lines = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            lines.add(plainLine(row));
        }
        return List.copyOf(lines);
    }

    public String plainText() {
        return String.join("\n", plainLines());
    }

    /** Structural equality, used by diffing and tests. */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Screen screen) || screen.rows != rows || screen.cols != cols) {
            return false;
        }
        if (!java.util.Arrays.equals(cells, screen.cells)) {
            return false;
        }
        return java.util.Arrays.equals(continuation, screen.continuation);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * java.util.Arrays.hashCode(cells) + java.util.Arrays.hashCode(continuation))
                + rows * 31 + cols;
    }

    @Override
    public String toString() {
        return "Screen[" + rows + "x" + cols + "]";
    }
}
