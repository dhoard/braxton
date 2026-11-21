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

import io.github.dhoard.braxton.style.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * A read-only application view of one painted frame.
 *
 * <p>A canvas exposes the cells the renderer actually produced: grapheme, style, link and wide-cell
 * continuation. It is a public value type, so a custom widget or a test can inspect a frame without
 * importing an internal renderer or depending on MVU.</p>
 */
public final class Canvas {

    /**
     * One painted cell in application terms.
     *
     * @param text the text to process
     * @param style the style to apply
     * @param link the hyperlink target, or null for none
     */
    public record Cell(String text, Style style, String link) {
        /**
         * Creates a {@code Cell} value.
         *
         * @param text the text to process
         * @param style the style to apply
         * @param link the hyperlink target, or null for none
         */
        public Cell {
            text = text == null ? "" : text;
            style = style == null ? Style.NONE : style;
        }
    }

    private final int rows;
    private final int cols;
    private final BiFunction<Integer, Integer, Cell> cellAt;
    private final BiPredicate<Integer, Integer> continuationAt;
    private final Supplier<List<String>> lineSupplier;

    /**
     * Construction seam used by the internal renderer; no native or MVU type crosses it.
     *
     * @param cellAt the cell reader, taking row then column
     * @param cols the number of columns
     * @param continuationAt the wide-cell continuation test, taking row then column
     * @param lineSupplier the supplier of plain-text frame rows
     * @param rows the number of rows
     */
    public Canvas(
            int rows,
            int cols,
            BiFunction<Integer, Integer, Cell> cellAt,
            BiPredicate<Integer, Integer> continuationAt,
            Supplier<List<String>> lineSupplier) {
        if (rows < 0 || cols < 0) throw new IllegalArgumentException("canvas size must be non-negative");
        this.rows = rows;
        this.cols = cols;
        this.cellAt = Objects.requireNonNull(cellAt, "cellAt");
        this.continuationAt = Objects.requireNonNull(continuationAt, "continuationAt");
        this.lineSupplier = Objects.requireNonNull(lineSupplier, "lineSupplier");
    }

    /**
     * A plain-text canvas, one cell per character. Wide glyphs need a renderer-produced canvas.
     *
     * @param lines the rows in display order
     * @return a plain-text canvas, one cell per character
     */
    public static Canvas fromLines(List<String> lines) {
        List<String> copy = List.copyOf(Objects.requireNonNull(lines, "lines"));
        int width = copy.stream().mapToInt(String::length).max().orElse(0);
        List<List<Cell>> cells = new ArrayList<>(copy.size());
        for (String line : copy) {
            List<Cell> row = new ArrayList<>(width);
            for (int col = 0; col < width; col++) {
                String text = col < line.length() ? String.valueOf(line.charAt(col)) : "";
                row.add(new Cell(text, Style.NONE, null));
            }
            cells.add(List.copyOf(row));
        }
        List<List<Cell>> immutable = List.copyOf(cells);
        return new Canvas(copy.size(), width, (row, col) -> cell(immutable, row, col), (row, col) -> false, () -> copy);
    }

    private static Cell cell(List<List<Cell>> cells, int row, int col) {
        if (row < 0 || row >= cells.size() || col < 0 || col >= cells.get(row).size()) {
            return new Cell("", Style.NONE, null);
        }
        return cells.get(row).get(col);
    }

    /**
     * The painted grapheme at one cell, or empty when nothing was painted.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the painted grapheme at one cell, or empty when nothing was painted
     */
    public String text(int row, int col) {
        return cell(row, col).text();
    }

    /**
     * The style at one cell.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the style at one cell
     */
    public Style style(int row, int col) {
        return cell(row, col).style();
    }

    /**
     * The hyperlink target at one cell, or null.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the hyperlink target at one cell, or null
     */
    public String link(int row, int col) {
        return cell(row, col).link();
    }

    /**
     * Whether this cell is the second column of a wide grapheme.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return {@code true} if this cell is the second column of a wide grapheme; otherwise {@code false}
     */
    public boolean isContinuation(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols && continuationAt.test(row, col);
    }

    /**
     * Returns the number of rows.
     *
     * @return the number of rows
     */
    public int rows() {
        return rows;
    }

    /**
     * Returns the number of columns.
     *
     * @return the number of columns
     */
    public int cols() {
        return cols;
    }

    /**
     * One cell at a location, empty outside the canvas.
     *
     * @param row the zero-based row
     * @param col the zero-based column
     * @return one cell at a location, empty outside the canvas
     */
    public Cell cell(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return new Cell("", Style.NONE, null);
        return cellAt.apply(row, col);
    }

    /**
     * Plain text of one row, trailing blanks trimmed.
     *
     * @param row the zero-based row
     * @return plain text of one row, trailing blanks trimmed
     */
    public String plainLine(int row) {
        List<String> lines = lineSupplier.get();
        return row < 0 || row >= lines.size() ? "" : lines.get(row);
    }

    /**
     * Returns the painted text as separate rows.
     *
     * @return the painted text as separate rows
     */
    public List<String> plainLines() {
        return List.copyOf(lineSupplier.get());
    }

    /**
     * Returns the painted text joined with line breaks.
     *
     * @return the painted text joined with line breaks
     */
    public String plainText() {
        return String.join("\n", plainLines());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Canvas canvas) || rows != canvas.rows || cols != canvas.cols) return false;
        for (int row = 0; row < rows; row++) {
            if (!plainLine(row).equals(canvas.plainLine(row))) return false;
            for (int col = 0; col < cols; col++) {
                if (!cell(row, col).equals(canvas.cell(row, col))) return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 31 * rows + cols;
        for (int row = 0; row < rows; row++) {
            hash = 31 * hash + plainLine(row).hashCode();
            for (int col = 0; col < cols; col++)
                hash = 31 * hash + cell(row, col).hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return "Canvas[" + rows + "x" + cols + "]";
    }
}
