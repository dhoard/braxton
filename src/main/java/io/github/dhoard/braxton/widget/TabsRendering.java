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

import static io.github.dhoard.braxton.widget.Tabs.CHROME;
import static io.github.dhoard.braxton.widget.Tabs.ELLIPSIS;
import static io.github.dhoard.braxton.widget.Tabs.MIN_CELL;
import static io.github.dhoard.braxton.widget.Tabs.OVERFLOW_LEFT;
import static io.github.dhoard.braxton.widget.Tabs.OVERFLOW_RIGHT;
import static io.github.dhoard.braxton.widget.Tabs.PADDING;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.Tabs.Appearance;
import io.github.dhoard.braxton.widget.Tabs.Cell;
import io.github.dhoard.braxton.widget.Tabs.Config;
import io.github.dhoard.braxton.widget.Tabs.Model;
import io.github.dhoard.braxton.widget.Tabs.Tab;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab windowing, border joins, and cell rendering.
 */
final class TabsRendering {

    /**
     * The cells the bar paints at this width.
     *
     * <p>Every tab at its own width when they fit. Otherwise the widest window of consecutive tabs
     * that fits around the shown one, with a marker on each side where tabs were left out - so the
     * label the user selected is always on screen, and the cost of a frame follows the width
     * rather than the number of tabs. When not even the shown tab fits with its own label, it is
     * the only cell and its label is cut.</p>
     */
    static List<Cell> cells(Model state, Config config, int width) {
        List<Tab> tabs = state.tabs();
        int count = tabs.size();
        if (count == 0 || width < MIN_CELL) {
            return List.of();
        }
        int anchor = Math.max(0, state.activeIndex());
        // Prefix sums keep the widest fitting window a linear scan rather than a quadratic one: a
        // list of thousands of tabs costs the same as a list of three.
        long[] prefix = new long[count + 1];
        for (int index = 0; index < count; index++) {
            prefix[index + 1] = prefix[index] + cellWidth(tabs.get(index).label());
        }
        for (int size = count; size >= 1; size--) {
            int start = windowStart(anchor, count, size);
            int end = start + size;
            long total = prefix[end] - prefix[start];
            if (start > 0) {
                total += overflowWidth();
            }
            if (end < count) {
                total += overflowWidth();
            }
            if (total <= width) {
                return window(tabs, start, end);
            }
        }
        return cut(tabs, anchor, width);
    }

    /**
     * The window's cells, with a marker on each side that left tabs out.
     */
    static List<Cell> window(List<Tab> tabs, int start, int end) {
        List<Cell> cells = new ArrayList<>(end - start + 2);
        if (start > 0) {
            cells.add(new Cell(-1, true, OVERFLOW_LEFT, overflowWidth()));
        }
        for (int index = start; index < end; index++) {
            cells.add(cell(tabs, index, tabs.get(index).label()));
        }
        if (end < tabs.size()) {
            cells.add(new Cell(-1, true, OVERFLOW_RIGHT, overflowWidth()));
        }
        return cells;
    }

    /**
     * One tab's cell, with its label cut to whatever the markers beside it leave.
     */
    static List<Cell> cut(List<Tab> tabs, int anchor, int width) {
        int markers = (anchor > 0 ? overflowWidth() : 0) + (anchor < tabs.size() - 1 ? overflowWidth() : 0);
        // A marker is worth a cell only when the shown label keeps a column to paint in: two markers
        // that leave an empty cell reveal nothing, which is the one thing a window must not do.
        if (width - markers < MIN_CELL + 1) {
            markers = 0;
        }
        Tab tab = tabs.get(anchor);
        int budget = Math.min(width - markers, cellWidth(tab.label()));
        List<Cell> cells = new ArrayList<>(3);
        if (anchor > 0 && markers > 0) {
            cells.add(new Cell(-1, true, OVERFLOW_LEFT, overflowWidth()));
        }
        cells.add(cell(tabs, anchor, fit(tab.label(), budget - CHROME)));
        if (anchor < tabs.size() - 1 && markers > 0) {
            cells.add(new Cell(-1, true, OVERFLOW_RIGHT, overflowWidth()));
        }
        return cells;
    }

    static Cell cell(List<Tab> tabs, int index, String label) {
        return new Cell(index, false, label, cellWidth(label));
    }

    /**
     * The first tab of a window of {@code size} tabs, centered on {@code anchor} where it can be.
     */
    static int windowStart(int anchor, int count, int size) {
        int centered = anchor - (size - 1) / 2;
        return Math.max(0, Math.min(centered, count - size));
    }

    static int cellWidth(String label) {
        return TextMeasure.columns(label) + CHROME;
    }

    static int overflowWidth() {
        return 1 + 2;
    }

    /**
     * A label cut to the columns it has, ending in an ellipsis when anything was dropped.
     */
    static String fit(String label, int columns) {
        if (columns <= 0) {
            return "";
        }
        if (TextMeasure.columns(label) <= columns) {
            return label;
        }
        int ellipsis = TextMeasure.columns(ELLIPSIS);
        return columns <= ellipsis
                ? TextMeasure.truncate(label, columns)
                : TextMeasure.truncate(label, columns - ellipsis) + ELLIPSIS;
    }

    /**
     * The bar alone: the cell tops, the labels, and the bar's own closed bottom edge.
     */
    static StyledText standalone(Model state, Config config, int width) {
        if (width <= 0 || state.tabs().isEmpty()) {
            return StyledText.empty();
        }
        StyledText bar = barRows(state, config, width);
        return bar.isEmpty()
                ? bottom(state, config, width, false)
                : bar.append("\n").append(bottom(state, config, width, false));
    }

    /**
     * The bar's open rows - the cell tops and the labels - for a composition that closes them.
     */
    static StyledText barRows(Model state, Config config, int width) {
        StyledText row = tops(state, config, width);
        StyledText labelRow = labels(state, config, width);
        if (labelRow.isEmpty()) {
            return row;
        }
        return row.isEmpty() ? labelRow : row.append("\n").append(labelRow);
    }

    /**
     * The top row of every cell: its corners and the horizontal run between them.
     */
    static StyledText tops(Model state, Config config, int width) {
        if (width <= 0) {
            return StyledText.empty();
        }
        List<Cell> cells = cells(state, config, width);
        if (cells.isEmpty()) {
            // No cell fits: the bar is only the frame's edge below the content, not two blank rows
            // where cells would have been.
            return StyledText.empty();
        }
        Border border = config.appearance().border();
        String horizontal = Border.glyph(border.horizontal());
        StyledText row = StyledText.empty();
        int used = 0;
        for (Cell cell : cells) {
            row = row.append(StyledText.of(
                    Border.glyph(border.topLeft())
                            + horizontal.repeat(Math.max(0, cell.width() - 2))
                            + Border.glyph(border.topRight()),
                    style(config, state, cell)));
            used += cell.width();
        }
        if (used < width) {
            row = row.append(StyledText.of(" ".repeat(width - used), Style.NONE));
        }
        return row;
    }

    /**
     * The label row: one padded label per cell.
     */
    static StyledText labels(Model state, Config config, int width) {
        if (width <= 0) {
            return StyledText.empty();
        }
        List<Cell> cells = cells(state, config, width);
        if (cells.isEmpty()) {
            return StyledText.empty();
        }
        Border border = config.appearance().border();
        String vertical = Border.glyph(border.vertical());
        StyledText row = StyledText.empty();
        int used = 0;
        for (Cell cell : cells) {
            int inner = Math.max(0, cell.width() - 2);
            // A marker has no padding to spend: its three columns are a border, the glyph and a
            // border. A tab's content is its label inside the padding, and `fit` is what keeps the
            // row exactly as wide as the cell it belongs to.
            String content = cell.overflow()
                    ? TextMeasure.fit(cell.label(), inner)
                    : TextMeasure.fit(" ".repeat(PADDING) + cell.label() + " ".repeat(PADDING), inner);
            row = row.append(StyledText.of(vertical + content + vertical, style(config, state, cell)));
            used += cell.width();
        }
        if (used < width) {
            row = row.append(StyledText.of(" ".repeat(width - used), Style.NONE));
        }
        return row;
    }

    /**
     * The bar's last row: the edge the shown tab opens into, or the bar's closed bottom.
     *
     * <p>Joined, the cells are the top edge of the frame below them and the shown tab's span is the
     * opening the content shows through, so the two read as one shape. Standalone, every cell is
     * closed and the rest of the row is the bar's own rule.</p>
     */
    static StyledText bottom(Model state, Config config, int width, boolean joined) {
        if (width <= 0) {
            return StyledText.empty();
        }
        Border border = config.appearance().border();
        Style frameStyle = config.appearance().frame();
        List<Cell> cells = cells(state, config, width);
        if (cells.isEmpty()) {
            // No cell to join: a joined bar is the frame's top edge, and a standalone one is the
            // rule its cells would have drawn, open at both ends because there is no cell there.
            return StyledText.of(
                    joined
                            ? edge(border, width, false)
                            : Border.glyph(border.horizontal()).repeat(width),
                    frameStyle);
        }
        Joins joins = Joins.of(border);
        String horizontal = Border.glyph(border.horizontal());
        StyledText row = StyledText.empty();
        int used = 0;
        for (Cell cell : cells) {
            boolean shown = !cell.overflow() && isActive(state, cell);
            boolean opening = joined && shown;
            boolean first = used == 0;
            boolean last = used + cell.width() == width;
            String left = junction(border, joins, first, true, shown, joined);
            String right = junction(border, joins, last, false, shown, joined);
            String middle = opening
                    ? " ".repeat(Math.max(0, cell.width() - 2))
                    : horizontal.repeat(Math.max(0, cell.width() - 2));
            row = row.append(StyledText.of(left + middle + right, style(config, state, cell)));
            used += cell.width();
        }
        if (used < width) {
            int run = width - used;
            String rest = joined
                    ? horizontal.repeat(Math.max(0, run - 1)) + Border.glyph(border.topRight())
                    : horizontal.repeat(run);
            row = row.append(StyledText.of(rest, frameStyle));
        }
        return row;
    }

    /**
     * One end of a cell's bottom edge.
     *
     * <p>Four things meet there: the cell's own side, the cell beside it, the frame below, and
     * whether the shown tab is open. The character says which of them continue - the frame's own
     * vertical through a closed end at the edge, a tee where a closed cell meets its neighbor, and
     * an open corner where the shown tab's opening meets one.</p>
     */
    static String junction(Border border, Joins joins, boolean atEdge, boolean left, boolean shown, boolean joined) {
        if (atEdge) {
            if (joined) {
                // The frame's own edge runs through this cell: down into the frame, and up into the
                // cell that owns the column.
                return shown ? Border.glyph(border.vertical()) : String.valueOf(left ? joins.left() : joins.right());
            }
            return Border.glyph(left ? border.bottomLeft() : border.bottomRight());
        }
        if (shown && joined) {
            return String.valueOf(left ? joins.openLeft() : joins.openRight());
        }
        return String.valueOf(joins.tee());
    }

    /**
     * The frame's top or bottom edge: its corners and a full run of its horizontal character.
     */
    static String edge(Border border, int width, boolean bottom) {
        if (width <= 0) {
            return "";
        }
        String horizontal = Border.glyph(border.horizontal());
        char first = bottom ? border.bottomLeft() : border.topLeft();
        char last = bottom ? border.bottomRight() : border.topRight();
        if (width == 1) {
            return Border.glyph(first);
        }
        return Border.glyph(first) + horizontal.repeat(width - 2) + Border.glyph(last);
    }

    static boolean isActive(Model state, Cell cell) {
        if (cell.overflow() || cell.tab() < 0) {
            return false;
        }
        return state.tabs().get(cell.tab()).id().equals(state.activeId());
    }

    static Style style(Config config, Model state, Cell cell) {
        Appearance look = config.appearance();
        if (cell.overflow() || cell.tab() < 0) {
            return look.inactiveTab();
        }
        Tab tab = state.tabs().get(cell.tab());
        if (!tab.enabled()) {
            return look.disabledTab();
        }
        return isActive(state, cell) ? look.activeTab() : look.inactiveTab();
    }

    /**
     * Styled text cut to the columns it has, on a grapheme boundary.
     */
    static StyledText cut(StyledText text, int columns) {
        if (columns <= 0) {
            return StyledText.empty();
        }
        String plain = text.plainText();
        if (TextMeasure.columns(plain) <= columns) {
            return text;
        }
        String truncated = TextMeasure.truncate(plain, columns);
        return text.slice(0, truncated.length());
    }

    /**
     * The junction glyphs a bar needs, chosen for the frame it joins.
     *
     * <p>A double frame joins with double junctions and every other frame with single ones, so the
     * cells and the frame below them read as one line rather than as a frame with mismatched
     * corners. A custom border keeps the single-line junctions: its own characters are still what a
     * cell and a frame draw, and the junction is the only part this widget chooses.</p>
     */
    record Joins(char left, char right, char tee, char openLeft, char openRight) {

        private static Joins of(Border border) {
            if (Border.DOUBLE.equals(border)) {
                return new Joins('\u2560', '\u2563', '\u2569', '\u255D', '\u255A');
            }
            return new Joins('\u251C', '\u2524', '\u2534', '\u2518', '\u2514');
        }
    }
}
