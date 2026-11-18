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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;

/**
 * Row/column table with deterministic column widths.
 *
 * <p>Columns are as wide as their widest cell plus the configured buffer. Measuring never mutates
 * the rows the caller supplied. Styling is part of a cell, not of its width. Tab stops are expanded
 * to the same four-column boundary the text view uses, so a measured column and a displayed column
 * always agree.</p>
 *
 * <p>A frame reserves one cell per edge character, so every row a bordered table renders occupies
 * the width of that frame. A row is painted as one grapheme-cluster sequence, so a cell's columns -
 * and the row's - are charged inside the sequence they are painted in: a cluster with no base
 * character of its own, such as a combining mark, a variation selector or a joiner, joins the cell
 * beside it instead of occupying a cell, which is a different number of columns from measuring that
 * cell on its own.</p>
 *
 * <h2>Fitting the table to a width</h2>
 *
 * <p>{@link #width(int)} is the opt-in budget: the cells a rendered row may occupy, frame included.
 * A table that declares none is measured and painted exactly as it always was, so this is additive.
 * A table that declares one is fitted to it - the widest columns give cells up until the row fits,
 * and no column is shrunk below {@value #MIN_COLUMN} cells - and then clipped: a cell wider than the
 * column it was fitted into is truncated on a cluster boundary, and a row that still overflows the
 * budget is truncated the same way. The columns stay deterministic and every row still occupies one
 * width, which is what a reader sees as a grid. A column narrower than the floor is never widened:
 * the floor is where shrinking stops, not a width the table imposes.</p>
 *
 * <h2>Column alignment</h2>
 *
 * <p>{@link #alignments(List)} is the opt-in padding rule: a column can put the space its cell does
 * not use before the cell ({@link Alignment#RIGHT}), on both sides of it ({@link Alignment#CENTER})
 * or after it ({@link Alignment#LEFT}, the default). Alignment moves padding, never content: a column
 * keeps the width its widest cell measured, a row keeps the columns it painted before, and a column
 * the list does not name is left-aligned, so a table that declares none is painted exactly as it
 * always was.</p>
 */
public final class Table {

    /** The fewest cells a column keeps while a table is fitted to a narrower width. */
    public static final int MIN_COLUMN = 3;

    /** Where one column puts the cells its content does not use. */
    public enum Alignment {

        /** Content first, padding after it. */
        LEFT,

        /** Padding on both sides of the content, the smaller half before it. */
        CENTER,

        /** Padding first, content last. */
        RIGHT
    }

    private final List<List<StyledText>> rows = new ArrayList<>();
    private List<Alignment> alignments = List.of();
    private int buffer = 2;
    private int width = 0;
    private Border border = Border.NONE;
    private Style borderStyle = Style.NONE;

    /**
     * An empty table with the default buffer and no frame.
     */
    public Table() {}

    /**
     * An empty table.
     *
     * @return the builder
     */
    public static Table of() {
        return new Table();
    }

    /**
     * The same table with a different cell buffer.
     *
     * @param value the cells added to each column width
     * @return this builder
     */
    public Table buffer(int value) {
        buffer = Math.max(0, value);
        return this;
    }

    /**
     * The same table with a width budget.
     *
     * <p>The budget is the cells one rendered row may occupy, frame included; it is fitted and
     * clipped to, and {@link #columnWidths()} reports the widths that results in. Zero, the default,
     * means no budget: the table is as wide as its columns need.</p>
     *
     * @param value the widest row the table may paint, in cells; zero or negative for no budget
     * @return this builder
     */
    public Table width(int value) {
        width = Math.max(0, value);
        return this;
    }

    /**
     * The same table with a padding rule per column.
     *
     * <p>The list is read positionally: entry {@code i} aligns column {@code i}, a column the list
     * does not name is left-aligned, and a null or empty list removes every rule. Alignment is
     * padding, so it never changes a column's width, a row's width or the cells a cell paints.</p>
     *
     * @param values the alignment of each column, or null for none
     * @return this builder
     */
    public Table alignments(List<Alignment> values) {
        if (values == null || values.isEmpty()) {
            alignments = List.of();
            return this;
        }
        List<Alignment> normalized = new ArrayList<>(values.size());
        for (Alignment value : values) {
            normalized.add(value == null ? Alignment.LEFT : value);
        }
        alignments = List.copyOf(normalized);
        return this;
    }

    /**
     * The same table with a frame.
     *
     * @param value the frame characters, or null for none
     * @return this builder
     */
    public Table border(Border value) {
        border = value == null ? Border.NONE : value;
        return this;
    }

    /**
     * The same table with a frame style.
     *
     * @param value the frame style, or null for plain
     * @return this builder
     */
    public Table borderStyle(Style value) {
        borderStyle = value == null ? Style.NONE : value;
        return this;
    }

    /**
     * The same table with one more row.
     *
     * @param cells the row's cells
     * @return this builder
     */
    public Table row(StyledText... cells) {
        if (cells == null) {
            throw new IllegalArgumentException("table cells are required");
        }
        List<StyledText> values = new ArrayList<>(cells.length);
        for (StyledText cell : cells) {
            values.add(requireCell(cell));
        }
        rows.add(List.copyOf(values));
        return this;
    }

    /**
     * The same table with one more row.
     *
     * @param cells the row's cells
     * @return this builder
     */
    public Table row(List<StyledText> cells) {
        if (cells == null) {
            throw new IllegalArgumentException("table cells are required");
        }
        List<StyledText> values = new ArrayList<>(cells.size());
        for (StyledText cell : cells) {
            values.add(requireCell(cell));
        }
        rows.add(List.copyOf(values));
        return this;
    }

    private static StyledText requireCell(StyledText cell) {
        if (cell == null) {
            throw new IllegalArgumentException("table cell is required");
        }
        return cell;
    }

    /**
     * The rows added so far.
     *
     * @return an immutable copy of the rows
     */
    public List<List<StyledText>> rows() {
        return List.copyOf(rows);
    }

    /**
     * Widest cell per column plus the buffer, fitted to the width budget when one is set; returns an
     * empty array for an empty table.
     *
     * @return the width of each column, including the buffer, never wider than the budget allows
     */
    public int[] columnWidths() {
        int columns = 0;
        for (List<StyledText> row : rows) {
            columns = Math.max(columns, row.size());
        }
        int[] widths = new int[columns];
        for (List<StyledText> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], GridRows.widest(row.get(i)));
            }
        }
        for (int i = 0; i < widths.length; i++) {
            widths[i] += buffer;
        }
        return fitted(widths);
    }

    /**
     * The same widths reduced until the row fits the budget, widest column first.
     *
     * <p>The widest column gives a cell up at a time, so the narrow columns are the ones that keep
     * their width and the result is deterministic whatever order the widths were measured in. A
     * column is never shrunk below {@value #MIN_COLUMN} cells, so a budget too small even for that is
     * left to the clipping pass rather than to a column that paints nothing.</p>
     */
    private int[] fitted(int[] widths) {
        if (width <= 0) {
            return widths;
        }
        int available = Math.max(0, width - (border.isVisible() ? 2 : 0));
        long total = 0;
        for (int value : widths) {
            total += value;
        }
        while (total > available) {
            int widest = -1;
            for (int i = 0; i < widths.length; i++) {
                if (widths[i] > MIN_COLUMN && (widest < 0 || widths[i] > widths[widest])) {
                    widest = i;
                }
            }
            if (widest < 0) {
                break;
            }
            widths[widest]--;
            total--;
        }
        return widths;
    }

    /**
     * Rendered rows; each row is padded to its column boundaries and every row to the width of the
     * widest one.
     *
     * @return the rendered rows
     */
    public List<StyledText> renderLines() {
        int[] widths = columnWidths();
        List<StyledText> out = new ArrayList<>();
        if (widths.length == 0) {
            return out;
        }
        for (List<StyledText> row : rows) {
            List<List<StyledText>> cellLines = new ArrayList<>();
            int height = 1;
            for (int i = 0; i < widths.length; i++) {
                StyledText cell = i < row.size() ? TabStops.expand(row.get(i)) : StyledText.empty();
                List<StyledText> lines = cell.lines();
                cellLines.add(lines);
                height = Math.max(height, lines.size());
            }
            for (int line = 0; line < height; line++) {
                StyledText rendered = StyledText.empty();
                // The row is painted as one cluster sequence, so each cell is charged inside the row
                // rather than measured on its own: a cluster with no base of its own at the head of
                // a cell joins the cell before it - the previous cell's pad, or the frame's left
                // edge once the row is framed - and takes no column of its own, while a variation
                // selector there widens that cell by a column it never paid for.
                GridRows.Row paintedRow = GridRows.Row.empty();
                for (int column = 0; column < widths.length; column++) {
                    List<StyledText> lines = cellLines.get(column);
                    StyledText value = line < lines.size() ? lines.get(line) : StyledText.empty();
                    // A fitted column is narrower than its content, so the cell is truncated here -
                    // on a cluster boundary, and never through a wide glyph - instead of being
                    // painted over the column beside it.
                    value = clipped(value, widths[column]);
                    // Alignment splits the pad the cell is owed, so the row keeps painting the same
                    // width: the leading spaces are painted first, and the cell is then charged
                    // against a row whose trailing cluster is one of those spaces. The buffer stays
                    // trailing space for every column, which is what separates a right-aligned cell
                    // from the column after it.
                    int usable = Math.max(0, widths[column] - buffer);
                    int slack = Math.max(0, usable - TextMeasure.columns(value.plainText()));
                    int lead = switch (alignment(column)) {
                        case LEFT -> 0;
                        case CENTER -> slack / 2;
                        case RIGHT -> slack;
                    };
                    if (lead > 0) {
                        rendered = rendered.append(StyledText.of(" ".repeat(lead), Style.NONE));
                        paintedRow = GridRows.Row.after(" ");
                    }
                    int pad = paintedRow.pad(value.plainText(), widths[column] - lead);
                    rendered = rendered.append(value).append(StyledText.of(" ".repeat(pad), Style.NONE));
                }
                out.add(clipped(rendered, budget()));
            }
        }
        return GridRows.shareOneWidth(out);
    }

    /**
     * Table as a single styled value with newlines between rows.
     *
     * @return the rendered table
     */
    public StyledText render() {
        StyledText out = StyledText.empty();
        List<StyledText> lines = renderLines();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append("\n");
            }
            out = out.append(lines.get(i));
        }
        if (!border.isVisible() || lines.isEmpty()) {
            return out;
        }
        return frame(lines);
    }

    private StyledText frame(List<StyledText> lines) {
        String vertical = Border.glyph(border.vertical());
        // A framed row is one cluster sequence that starts with the left edge cell, so the row is
        // measured inside that cell: a cluster with no base of its own at the head of the row joins
        // the edge instead of starting a cell of its own, and a variation selector there widens it.
        // Padding the row from its own measurement leaves it short or long by that column, and the
        // right edge lands off the corners of the frame the same call drew.
        int painted = 0;
        for (StyledText line : lines) {
            painted = Math.max(painted, TextMeasure.columns(vertical + line.plainText()));
        }
        // One cell for the left edge, already counted above, and one for the right edge.
        int frameWidth = painted + 1;
        String topLeft = Border.glyph(border.topLeft());
        String topRight = Border.glyph(border.topRight());
        String bottomLeft = Border.glyph(border.bottomLeft());
        String bottomRight = Border.glyph(border.bottomRight());
        String horizontal = Border.glyph(border.horizontal());
        List<StyledText> framed = new ArrayList<>();
        framed.add(StyledText.of(topLeft + horizontal.repeat(Math.max(0, frameWidth - 2)) + topRight, borderStyle));
        for (StyledText line : lines) {
            int pad = Math.max(0, painted - TextMeasure.columns(vertical + line.plainText()));
            framed.add(StyledText.concat(
                    StyledText.of(vertical, borderStyle),
                    line,
                    StyledText.of(" ".repeat(pad) + vertical, borderStyle)));
        }
        framed.add(
                StyledText.of(bottomLeft + horizontal.repeat(Math.max(0, frameWidth - 2)) + bottomRight, borderStyle));
        StyledText out = StyledText.empty();
        for (int i = 0; i < framed.size(); i++) {
            if (i > 0) {
                out = out.append("\n");
            }
            // A budget too small for the frame's own two edges leaves a truncated frame rather than
            // one painted over whatever sits beside the table.
            out = out.append(width <= 0 ? framed.get(i) : clipped(framed.get(i), width));
        }
        return out;
    }

    /** The alignment of one column, left when the table declared none for it. */
    private Alignment alignment(int column) {
        return column < alignments.size() ? alignments.get(column) : Alignment.LEFT;
    }

    /** The cells one rendered row may occupy, which is the budget less the frame's two edges. */
    private int budget() {
        return width <= 0 ? Integer.MAX_VALUE : Math.max(0, width - (border.isVisible() ? 2 : 0));
    }

    /**
     * A value truncated to {@code columns} cells on a cluster boundary, with its styles kept.
     *
     * <p>Truncation is what a fitted column needs and what a row that still overflows the budget
     * needs: a wide glyph is dropped whole rather than half painted, and a span that straddles the
     * boundary is split there, so the cells that survive are the cells the value had.</p>
     */
    private static StyledText clipped(StyledText value, int columns) {
        if (columns == Integer.MAX_VALUE) {
            return value;
        }
        if (columns <= 0 || value.isEmpty()) {
            return StyledText.empty();
        }
        String plain = value.plainText();
        String truncated = TextMeasure.truncate(plain, columns);
        return truncated.length() == plain.length() ? value : value.slice(0, truncated.length());
    }

    /**
     * A one-element view of the rendered table.
     *
     * @return a text view holding {@link #render()}
     */
    public View<Object> view() {
        return Views.text(render());
    }

    /**
     * Convenience for callers that build plain cells.
     *
     * @param text the cell text; null is empty
     * @param color the text colour, or null for the inherited colour
     * @return the styled cell
     */
    public static StyledText cell(String text, Color color) {
        return StyledText.of(text == null ? "" : text, color == null ? Style.NONE : Style.fg(color));
    }
}
