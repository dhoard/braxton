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
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parallel packed arrays backing one frame: grapheme, style, link and cell occupancy.
 *
 * <p>Graphemes, links and styles are interned per buffer. A painted frame repeats a handful of
 * values across thousands of cells, so identity comparison is enough for frame diffing and a cell
 * write allocates no per-cell record. Occupancy is a byte rather than a boolean because a wide glyph
 * needs to distinguish its leading half from its continuation.</p>
 */
final class PackedCells {

    private static final byte EMPTY = 0;
    private static final byte SINGLE = 1;
    private static final byte WIDE = 2;
    private static final byte CONTINUATION = 3;
    private static final int INHERITED_CAP = 4;
    private static final String EMPTY_TEXT = "";
    private static final String[] ASCII = new String[128];

    static {
        for (int i = 0; i < ASCII.length; i++) ASCII[i] = String.valueOf((char) i);
    }

    private final int rows;
    private final int cols;
    private final String[] graphemes;
    private final Style[] styles;
    private String[] links;
    private final byte[] occupancy;
    private final Map<String, String> textPool = new HashMap<>();
    private final Map<Style, Style> stylePool = new HashMap<>();
    private final Inherited[] inherited = new Inherited[INHERITED_CAP];
    private int inheritedSlot;

    PackedCells(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        int size = rows * cols;
        graphemes = new String[size];
        styles = new Style[size];
        // Links are rare: allocate this array only when a frame actually paints one.
        occupancy = new byte[size];
        Arrays.fill(graphemes, EMPTY_TEXT);
        Arrays.fill(styles, Style.NONE);
    }

    int rows() {
        return rows;
    }

    int cols() {
        return cols;
    }

    boolean inside(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    private int index(int row, int col) {
        return row * cols + col;
    }

    Cell cell(int row, int col) {
        if (!inside(row, col)) return Cell.EMPTY;
        int at = index(row, col);
        if (occupancy[at] == EMPTY) return Cell.EMPTY;
        return new Cell(graphemes[at], styles[at], links == null ? null : links[at]);
    }

    boolean isContinuation(int row, int col) {
        return inside(row, col) && occupancy[index(row, col)] == CONTINUATION;
    }

    String text(int row, int col) {
        if (!inside(row, col)) return EMPTY_TEXT;
        int at = index(row, col);
        return occupancy[at] == EMPTY ? EMPTY_TEXT : graphemes[at];
    }

    Style style(int row, int col) {
        return inside(row, col) ? styles[index(row, col)] : Style.NONE;
    }

    String link(int row, int col) {
        if (!inside(row, col) || links == null) return null;
        return links[index(row, col)];
    }

    boolean isEmptyCell(int row, int col) {
        return cell(row, col).isEmpty();
    }

    void clear(int row, int col) {
        if (!inside(row, col)) return;
        int at = index(row, col);
        if (occupancy[at] == CONTINUATION && col > 0) clearCell(row, col - 1);
        if (col + 1 < cols && occupancy[index(row, col + 1)] == CONTINUATION) clearCell(row, col + 1);
        clearCell(row, col);
    }

    private void clearCell(int row, int col) {
        int at = index(row, col);
        occupancy[at] = EMPTY;
        graphemes[at] = EMPTY_TEXT;
        styles[at] = Style.NONE;
        if (links != null) links[at] = null;
    }

    void put(int row, int col, String grapheme, Style style, String link, int width) {
        if (!inside(row, col) || grapheme == null || grapheme.isEmpty()) return;
        int span = Math.max(1, Math.min(2, width));
        if (span == 2 && col + 1 >= cols) {
            clear(row, col);
            return;
        }
        if (span == 2) {
            clearIfContinuation(row, col + 1);
            clearContinuationAt(row, col + 2);
        }
        clearIfContinuation(row, col);
        int at = index(row, col);
        graphemes[at] = intern(grapheme);
        styles[at] = intern(inheritBackground(row, col, style));
        if (link == null || link.isBlank()) {
            if (links != null) links[at] = null;
        } else {
            ensureLinks();
            links[at] = intern(link);
        }
        occupancy[at] = span == 2 ? WIDE : SINGLE;
        if (span == 2) {
            int next = index(row, col + 1);
            graphemes[next] = intern("");
            styles[next] = intern(Style.NONE);
            if (links != null) links[next] = null;
            occupancy[next] = CONTINUATION;
        } else {
            clearOrphanedContinuation(row, col);
        }
    }

    void restyle(int row, int col, Style style) {
        if (!inside(row, col)) return;
        styles[index(row, col)] = intern(style == null ? Style.NONE : style);
    }

    private Style inheritBackground(int row, int col, Style style) {
        Style safe = style == null ? Style.NONE : style;
        if (safe.background() != null) return safe;
        Color beneath = styles[index(row, col)].background();
        return beneath == null ? safe : inherit(safe, beneath);
    }

    private Style inherit(Style style, Color background) {
        for (Inherited known : inherited) {
            if (known != null && known.style.equals(style) && known.background.equals(background)) {
                return known.merged;
            }
        }
        Style merged = style.withBackground(background);
        inherited[inheritedSlot] = new Inherited(style, background, merged);
        inheritedSlot = (inheritedSlot + 1) % INHERITED_CAP;
        return merged;
    }

    private record Inherited(Style style, Color background, Style merged) {}

    private void clearOrphanedContinuation(int row, int col) {
        int next = col + 1;
        if (inside(row, next) && occupancy[index(row, next)] == CONTINUATION) clearCell(row, next);
    }

    private void clearContinuationAt(int row, int col) {
        if (inside(row, col) && occupancy[index(row, col)] == CONTINUATION) clearCell(row, col);
    }

    private void clearIfContinuation(int row, int col) {
        if (!inside(row, col) || occupancy[index(row, col)] != CONTINUATION || col == 0) return;
        Color beneath = styles[index(row, col - 1)].background();
        if (beneath == null) {
            clearCell(row, col - 1);
            clearCell(row, col);
            return;
        }
        Style blankStyle = intern(inherit(Style.NONE, beneath));
        String blankText = ASCII[32];
        for (int target : new int[] {index(row, col - 1), index(row, col)}) {
            graphemes[target] = blankText;
            styles[target] = blankStyle;
            if (links != null) links[target] = null;
            occupancy[target] = SINGLE;
        }
    }

    void fill(Rect rect, Style style) {
        Rect clipped = rect.clip(new Rect(0, 0, rows, cols));
        for (int row = clipped.row(); row < clipped.row() + clipped.rows(); row++) {
            for (int col = clipped.col(); col < clipped.col() + clipped.cols(); col++) clear(row, col);
        }
        if (style == null || style.isPlain()) return;
        String blank = ASCII[32];
        Style painted = intern(style);
        for (int row = clipped.row(); row < clipped.row() + clipped.rows(); row++) {
            for (int col = clipped.col(); col < clipped.col() + clipped.cols(); col++) {
                int at = index(row, col);
                graphemes[at] = blank;
                styles[at] = painted;
                if (links != null) links[at] = null;
                occupancy[at] = SINGLE;
            }
        }
    }

    String plainLine(int row) {
        if (row < 0 || row >= rows) return "";
        StringBuilder text = new StringBuilder();
        for (int col = 0; col < cols; col++) {
            if (occupancy[index(row, col)] == CONTINUATION) continue;
            String value = graphemes[index(row, col)];
            text.append(value.isEmpty() ? " " : value);
        }
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        return text.substring(0, end);
    }

    List<String> plainLines() {
        List<String> lines = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) lines.add(plainLine(row));
        return List.copyOf(lines);
    }

    private String intern(String value) {
        if (value.isEmpty()) return EMPTY_TEXT;
        if (value.length() == 1) {
            char single = value.charAt(0);
            if (single < ASCII.length) return ASCII[single];
        }
        String known = textPool.get(value);
        if (known == null) {
            textPool.put(value, value);
            return value;
        }
        return known;
    }

    private Style intern(Style value) {
        if (value == Style.NONE) return value;
        Style known = stylePool.get(value);
        if (known == null) {
            stylePool.put(value, value);
            return value;
        }
        return known;
    }

    private void ensureLinks() {
        if (links == null) links = new String[rows * cols];
    }

    private boolean linksEqual(PackedCells other) {
        if (links == null) return other.links == null;
        return other.links != null && Arrays.equals(links, other.links);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PackedCells packed) || rows != packed.rows || cols != packed.cols) return false;
        return graphemes == packed.graphemes
                || (Arrays.equals(graphemes, packed.graphemes)
                        && Arrays.equals(styles, packed.styles)
                        && linksEqual(packed)
                        && Arrays.equals(occupancy, packed.occupancy));
    }

    @Override
    public int hashCode() {
        return 31
                        * (31 * (31 * Arrays.hashCode(graphemes) + Arrays.hashCode(styles))
                                + (links == null ? 0 : Arrays.hashCode(links)))
                + Arrays.hashCode(occupancy)
                + rows * 31
                + cols;
    }
}
