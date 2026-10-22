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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

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
 */
public final class Table {

    private final List<List<StyledText>> rows = new ArrayList<>();
    private int buffer = 2;
    private Border border = Border.NONE;
    private Style borderStyle = Style.NONE;

    public Table() {
    }

    public static Table of() {
        return new Table();
    }

    public Table buffer(int value) {
        buffer = Math.max(0, value);
        return this;
    }

    public Table border(Border value) {
        border = value == null ? Border.NONE : value;
        return this;
    }

    public Table borderStyle(Style value) {
        borderStyle = value == null ? Style.NONE : value;
        return this;
    }

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

    public List<List<StyledText>> rows() {
        return List.copyOf(rows);
    }

    /** Widest cell per column plus the buffer; returns an empty array for an empty table. */
    public int[] columnWidths() {
        int columns = 0;
        for (List<StyledText> row : rows) {
            columns = Math.max(columns, row.size());
        }
        int[] widths = new int[columns];
        for (List<StyledText> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                int width = 0;
                for (String line : TabStops.expand(row.get(i)).plainText().split("\n", -1)) {
                    width = Math.max(width, TextMeasure.columns(line));
                }
                widths[i] = Math.max(widths[i], width);
            }
        }
        for (int i = 0; i < widths.length; i++) {
            widths[i] += buffer;
        }
        return widths;
    }

    /**
     * Rendered rows; each row is padded to its column boundaries and every row to the width of the
     * widest one.
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
                String painted = "";
                for (int column = 0; column < widths.length; column++) {
                    List<StyledText> lines = cellLines.get(column);
                    StyledText value = line < lines.size() ? lines.get(line) : StyledText.empty();
                    String plain = value.plainText();
                    int pad = GridRows.pad(painted, plain, widths[column]);
                    painted = painted + plain + " ".repeat(pad);
                    rendered = rendered.append(value)
                            .append(StyledText.of(" ".repeat(pad), Style.NONE));
                }
                out.add(rendered);
            }
        }
        return GridRows.shareOneWidth(out);
    }

    /** Table as a single styled value with newlines between rows. */
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
        int width = painted + 1;
        String topLeft = Border.glyph(border.topLeft());
        String topRight = Border.glyph(border.topRight());
        String bottomLeft = Border.glyph(border.bottomLeft());
        String bottomRight = Border.glyph(border.bottomRight());
        String horizontal = Border.glyph(border.horizontal());
        List<StyledText> framed = new ArrayList<>();
        framed.add(StyledText.of(
                topLeft + horizontal.repeat(Math.max(0, width - 2)) + topRight, borderStyle));
        for (StyledText line : lines) {
            int pad = Math.max(0, painted - TextMeasure.columns(vertical + line.plainText()));
            framed.add(StyledText.concat(
                    StyledText.of(vertical, borderStyle), line,
                    StyledText.of(" ".repeat(pad) + vertical, borderStyle)));
        }
        framed.add(StyledText.of(
                bottomLeft + horizontal.repeat(Math.max(0, width - 2)) + bottomRight,
                borderStyle));
        StyledText out = StyledText.empty();
        for (int i = 0; i < framed.size(); i++) {
            if (i > 0) {
                out = out.append("\n");
            }
            out = out.append(framed.get(i));
        }
        return out;
    }

    public View<Object> view() {
        return Views.text(render());
    }

    /** Convenience for callers that build plain cells. */
    public static StyledText cell(String text, Color color) {
        return StyledText.of(text == null ? "" : text, color == null ? Style.NONE : Style.fg(color));
    }
}
