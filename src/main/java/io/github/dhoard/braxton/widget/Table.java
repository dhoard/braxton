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
        rows.add(List.of(cells));
        return this;
    }

    public Table row(List<StyledText> cells) {
        rows.add(List.copyOf(cells));
        return this;
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

    /** Rendered rows; each row is padded to its column boundaries. */
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
                for (int column = 0; column < widths.length; column++) {
                    List<StyledText> lines = cellLines.get(column);
                    StyledText value = line < lines.size() ? lines.get(line) : StyledText.empty();
                    String plain = value.plainText();
                    int used = TextMeasure.columns(plain);
                    int pad = Math.max(0, widths[column] - used);
                    rendered = rendered.append(value)
                            .append(StyledText.of(" ".repeat(pad), Style.NONE));
                }
                out.add(rendered);
            }
        }
        return out;
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
        int width = 0;
        for (StyledText line : lines) {
            width = Math.max(width, TextMeasure.columns(line.plainText()));
        }
        List<StyledText> framed = new ArrayList<>();
        framed.add(StyledText.of(
                border.topLeft() + String.valueOf(border.horizontal()).repeat(width + 2) + border.topRight(),
                borderStyle));
        for (StyledText line : lines) {
            int pad = Math.max(0, width - TextMeasure.columns(line.plainText()));
            framed.add(StyledText.concat(
                    StyledText.of(String.valueOf(border.vertical()), borderStyle), line,
                    StyledText.of(" ".repeat(pad) + border.vertical(), borderStyle)));
        }
        framed.add(StyledText.of(
                border.bottomLeft() + String.valueOf(border.horizontal()).repeat(width + 2) + border.bottomRight(),
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
