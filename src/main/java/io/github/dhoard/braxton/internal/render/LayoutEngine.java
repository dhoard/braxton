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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;

/**
 * Turns a {@link View} tree into a painted {@link Screen}.
 *
 * <p>Layout is deterministic: children are measured, non-flex children take their intrinsic size,
 * and remaining main-axis cells are divided between {@code Grow} children by weight in declaration
 * order, with the remainder going to the earliest children. Everything is clipped to its allocated
 * rectangle and to ancestor bounds, so no coordinate can fall outside the viewport.</p>
 */
public final class LayoutEngine {

    /** One focusable element's painted rectangle, in paint order. */
    public record Region(ElementId id, Rect bounds) {
    }

    /** The painted screen plus the focus regions declared while painting it. */
    public record Layout(Screen screen, List<Region> regions) {
    }

    private final WidthPolicy policy;

    public LayoutEngine() {
        this(WidthPolicy.PINNED);
    }

    public LayoutEngine(WidthPolicy policy) {
        this.policy = policy == null ? WidthPolicy.PINNED : policy;
    }

    public Layout layout(View<?> root, Rect bounds) {
        Screen screen = new Screen(bounds.rows(), bounds.cols());
        List<Region> regions = new ArrayList<>();
        paint(root, screen, bounds, regions);
        return new Layout(screen, List.copyOf(regions));
    }

    // ── measurement ──────────────────────────────────────────────────────────

    /**
     * Intrinsic height at {@code cols} columns; flex children measure as zero main-axis size.
     *
     * <p>Content taller than {@link Integer#MAX_VALUE} rows saturates at that ceiling rather than
     * wrapping into a negative measurement.</p>
     */
    public int intrinsicHeight(View<?> view, int cols) {
        return intrinsicHeight(view, cols, Integer.MAX_VALUE);
    }

    private int intrinsicHeight(View<?> view, int cols, int maxRows) {
        if (cols <= 0 || maxRows <= 0) {
            return 0;
        }
        // Every case answers at most maxRows, so the sums that accumulate child heights stay inside
        // the int range and a measurement can never go negative or claim rows that were not offered.
        return switch (view) {
            case View.Text<?> text -> wrapLines(text.text(), cols, maxRows).size();
            case View.Blank<?> blank -> 0;
            case View.Fill<?> fill -> 1;
            case View.Column<?> column -> columnHeight(column, cols, maxRows);
            case View.Row<?> row -> rowHeight(row, cols, maxRows);
            // The child is painted inside the horizontal insets, so it must be measured at that
            // width; measuring at the full width can wrap into fewer rows than it paints.
            case View.Padding<?> padding -> {
                int vertical = padding.insets().vertical();
                int inner = intrinsicHeight(padding.child(),
                        Math.max(0, cols - padding.insets().horizontal()),
                        Math.max(0, maxRows - vertical));
                yield cappedSum(vertical, inner, maxRows);
            }
            case View.BorderBox<?> box -> {
                int frame = box.border().isVisible() ? 2 : 0;
                int inner = intrinsicHeight(box.child(), Math.max(0, cols - frame),
                        Math.max(0, maxRows - frame));
                yield cappedSum(frame, inner, maxRows);
            }
            // The reservation is capped by the rows still available: a child cannot need more than
            // its parent offers, and the uncapped size would overflow the sums that accumulate it.
            case View.Fixed<?> fixed -> Math.min(fixed.size(), maxRows);
            case View.Grow<?> grow -> 0;
            case View.Overlay<?> overlay -> overlayHeight(overlay, cols, maxRows);
            case View.Keyed<?> keyed -> intrinsicHeight(keyed.child(), cols, maxRows);
            case View.Focused<?> focused -> intrinsicHeight(focused.child(), cols, maxRows);
        };
    }

    /** Intrinsic width, capped at {@code maxCols}; flex children measure as zero main-axis size. */
    public int intrinsicWidth(View<?> view, int maxCols) {
        if (maxCols <= 0) {
            return 0;
        }
        return switch (view) {
            case View.Text<?> text -> textWidth(text.text(), maxCols);
            case View.Blank<?> blank -> 0;
            case View.Fill<?> fill -> maxCols;
            case View.Column<?> column -> columnWidth(column, maxCols);
            case View.Row<?> row -> rowWidth(row, maxCols);
            case View.Padding<?> padding -> Math.min(maxCols,
                    padding.insets().horizontal() + intrinsicWidth(padding.child(),
                            Math.max(0, maxCols - padding.insets().horizontal())));
            case View.BorderBox<?> box -> Math.min(maxCols, (box.border().isVisible() ? 2 : 0)
                    + intrinsicWidth(box.child(), Math.max(0, maxCols - (box.border().isVisible() ? 2 : 0))));
            case View.Fixed<?> fixed -> Math.min(maxCols, fixed.size());
            case View.Grow<?> grow -> 0;
            case View.Overlay<?> overlay -> overlayWidth(overlay, maxCols);
            case View.Keyed<?> keyed -> intrinsicWidth(keyed.child(), maxCols);
            case View.Focused<?> focused -> intrinsicWidth(focused.child(), maxCols);
        };
    }

    private int columnHeight(View.Column<?> column, int cols, int maxRows) {
        int total = 0;
        for (int i = 0; i < column.items().size(); i++) {
            total += intrinsicHeight(column.items().get(i), cols, maxRows - total);
            if (total >= maxRows) {
                return maxRows;
            }
        }
        return total;
    }

    /** {@code a + b}, saturated at {@code maxRows}: a measurement never claims rows it was not offered. */
    private static int cappedSum(int a, int b, int maxRows) {
        return (int) Math.min(maxRows, (long) a + b);
    }

    private int columnWidth(View.Column<?> column, int maxCols) {
        int widest = 0;
        for (int i = 0; i < column.items().size(); i++) {
            widest = Math.max(widest, intrinsicWidth(crossAxis(column.items().get(i)), maxCols));
        }
        return widest;
    }

    private int rowHeight(View.Row<?> row, int cols, int maxRows) {
        List<? extends View<?>> items = row.items();
        if (items.isEmpty()) {
            return 0;
        }
        // Children paint inside their own share of the row, so each is measured at the width it
        // will be painted at; measuring at the row's full width can wrap into fewer rows than it
        // paints and clip the overflow.
        int[] widths = rowWidths(items, cols);
        int tallest = 0;
        for (int i = 0; i < items.size(); i++) {
            if (widths[i] <= 0) {
                continue;
            }
            tallest = Math.max(tallest, intrinsicHeight(crossAxis(items.get(i)), widths[i], maxRows));
            if (tallest >= maxRows) {
                return maxRows;
            }
        }
        return tallest;
    }

    /** Main-axis width {@link #paintRow} gives each item at {@code cols} columns. */
    private int[] rowWidths(List<? extends View<?>> items, int cols) {
        int[] widths = new int[items.size()];
        int used = 0;
        // Weights are any non-negative int, so the sum and the per-child product are widened:
        // int overflow would leave flex children with no cells at all.
        long flexWeight = 0;
        for (int i = 0; i < items.size(); i++) {
            View<?> child = items.get(i);
            if (child instanceof View.Grow<?> grow) {
                flexWeight += grow.weight();
            } else if (child instanceof View.Fixed<?> fixed) {
                widths[i] = Math.min(fixed.size(), Math.max(0, cols - used));
                used += widths[i];
            } else {
                widths[i] = Math.min(intrinsicWidth(child, Math.max(0, cols - used)),
                        Math.max(0, cols - used));
                used += widths[i];
            }
        }
        int remaining = Math.max(0, cols - used);
        if (flexWeight > 0) {
            int distributed = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof View.Grow<?> grow) {
                    widths[i] = (int) (remaining * (long) grow.weight() / flexWeight);
                    distributed += widths[i];
                }
            }
            int remainder = remaining - distributed;
            for (int i = 0; i < items.size() && remainder > 0; i++) {
                if (items.get(i) instanceof View.Grow<?>) {
                    widths[i]++;
                    remainder--;
                }
            }
        }
        return widths;
    }

    private int rowWidth(View.Row<?> row, int maxCols) {
        int used = 0;
        for (int i = 0; i < row.items().size(); i++) {
            used += intrinsicWidth(row.items().get(i), Math.max(0, maxCols - used));
        }
        return Math.min(maxCols, used);
    }

    private int overlayHeight(View.Overlay<?> overlay, int cols, int maxRows) {
        int tallest = 0;
        for (int i = 0; i < overlay.layers().size(); i++) {
            tallest = Math.max(tallest, intrinsicHeight(crossAxis(overlay.layers().get(i)), cols, maxRows));
            if (tallest >= maxRows) {
                return maxRows;
            }
        }
        return tallest;
    }

    private int overlayWidth(View.Overlay<?> overlay, int maxCols) {
        int widest = 0;
        for (int i = 0; i < overlay.layers().size(); i++) {
            widest = Math.max(widest, intrinsicWidth(crossAxis(overlay.layers().get(i)), maxCols));
        }
        return widest;
    }

    private int textWidth(StyledText text, int maxCols) {
        int widest = 0;
        int used = 0;
        // The cell the last cluster of the line was charged into, spelled the way the wrapped row's
        // own plain text will hold it: a tab is expanded to the spaces of its stop here, and a
        // cluster with no base of its own carries the dotted circle wrapLines writes in front of it.
        // Reading the cell that way keeps the columns charged for a row equal to the columns the
        // same text occupies once it is painted.
        String cell = null;
        int cellColumns = 0;
        for (String cluster : Graphemes.iterate(text.plainText())) {
            if ("\n".equals(cluster)) {
                widest = Math.max(widest, used);
                used = 0;
                cell = null;
                continue;
            }
            if ("\t".equals(cluster)) {
                used += 4 - (used % 4);
                cell = " ";
                cellColumns = 1;
            } else if (TextMeasure.clusterColumns(cluster, policy) > 0) {
                cell = hasNoBase(cluster) ? TextMeasure.DOTTED_CIRCLE + cluster : cluster;
                cellColumns = TextMeasure.clusterColumns(cell, policy);
                used += cellColumns;
            } else if (cell != null) {
                // A cluster claiming no column joins the text of the cell before it. It claims
                // nothing itself but can widen that cell - the selector after a tab's last space
                // makes the cell two columns - and one that joins nothing closes the cell, since it
                // stays between that cell and whatever follows it.
                if (!continuesCluster(cell, cluster)) {
                    cell = null;
                } else {
                    int joined = TextMeasure.clusterColumns(cell + cluster, policy);
                    used += joined - cellColumns;
                    cell = cell + cluster;
                    cellColumns = joined;
                }
            }
            if (used >= maxCols) {
                return maxCols;
            }
        }
        return Math.max(widest, used);
    }

    // ── painting ─────────────────────────────────────────────────────────────

    private void paint(View<?> view, Screen screen, Rect bounds, List<Region> regions) {
        Rect clip = bounds.clip(new Rect(0, 0, screen.rows(), screen.cols()));
        if (clip.isEmpty()) {
            return;
        }
        switch (view) {
            case View.Text<?> text -> paintText(text.text(), screen, clip);
            case View.Blank<?> blank -> { }
            case View.Fill<?> fill -> screen.fill(clip, fill.style());
            case View.Column<?> column -> paintColumn(column, screen, clip, regions);
            case View.Row<?> row -> paintRow(row, screen, clip, regions);
            case View.Padding<?> padding -> paint(padding.child(), screen, clip.inset(padding.insets()), regions);
            case View.BorderBox<?> box -> paintBorder(box, screen, clip, regions);
            case View.Fixed<?> fixed -> paint(fixed.child(), screen, clip, regions);
            case View.Grow<?> grow -> paint(grow.child(), screen, clip, regions);
            case View.Overlay<?> overlay -> {
                for (int i = 0; i < overlay.layers().size(); i++) {
                    paint(overlay.layers().get(i), screen, clip, regions);
                }
            }
            case View.Keyed<?> keyed -> paint(keyed.child(), screen, clip, regions);
            case View.Focused<?> focused -> {
                regions.add(new Region(focused.id(), clip));
                paint(focused.child(), screen, clip, regions);
            }
        }
    }

    private void paintColumn(View.Column<?> column, Screen screen, Rect bounds, List<Region> regions) {
        List<? extends View<?>> items = List.copyOf(column.items());
        if (items.isEmpty()) {
            return;
        }
        int[] heights = new int[items.size()];
        int used = 0;
        // Same widened weight sum and product as rowWidths: a weight can be any non-negative int.
        long flexWeight = 0;
        for (int i = 0; i < items.size(); i++) {
            View<?> child = items.get(i);
            if (child instanceof View.Grow<?> grow) {
                flexWeight += grow.weight();
            } else if (child instanceof View.Fixed<?> fixed) {
                heights[i] = Math.min(fixed.size(), Math.max(0, bounds.rows() - used));
                used += heights[i];
            } else {
                heights[i] = Math.min(intrinsicHeight(child, bounds.cols(), Math.max(0, bounds.rows() - used)),
                        Math.max(0, bounds.rows() - used));
                used += heights[i];
            }
        }
        int remaining = Math.max(0, bounds.rows() - used);
        if (flexWeight > 0) {
            int distributed = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof View.Grow<?> grow) {
                    heights[i] = (int) (remaining * (long) grow.weight() / flexWeight);
                    distributed += heights[i];
                }
            }
            int remainder = remaining - distributed;
            for (int i = 0; i < items.size() && remainder > 0; i++) {
                if (items.get(i) instanceof View.Grow<?>) {
                    heights[i]++;
                    remainder--;
                }
            }
        }
        int row = bounds.row();
        for (int i = 0; i < items.size(); i++) {
            int height = heights[i];
            if (height <= 0) {
                continue;
            }
            View<?> child = items.get(i);
            // Start alignment always gives the child the full width, so no cross-axis
            // measurement is needed (especially for text whose tail is offscreen).
            int width = column.align() == Alignment.START
                    ? bounds.cols() : childWidth(child, bounds.cols());
            int col = bounds.col();
            int childCols = bounds.cols();
            if (width < bounds.cols() && !(child instanceof View.Fill<?>)) {
                // A child that does not fill the width keeps its own size at the aligned offset;
                // only a start-aligned child takes the rest of the column, so a frame placed at
                // the centre stays centred instead of stretching to the right edge.
                switch (column.align()) {
                    case CENTER -> {
                        col += (bounds.cols() - width) / 2;
                        childCols = width;
                    }
                    case END -> {
                        col += bounds.cols() - width;
                        childCols = width;
                    }
                    case START -> { }
                }
            }
            Rect childBounds = new Rect(row, col, height, Math.max(0, childCols));
            paint(child, screen, childBounds, regions);
            row += height;
        }
    }

    private void paintRow(View.Row<?> row, Screen screen, Rect bounds, List<Region> regions) {
        List<? extends View<?>> items = List.copyOf(row.items());
        if (items.isEmpty()) {
            return;
        }
        int[] widths = rowWidths(items, bounds.cols());
        int col = bounds.col();
        for (int i = 0; i < items.size(); i++) {
            int width = widths[i];
            if (width <= 0) {
                continue;
            }
            View<?> child = items.get(i);
            // A fill is the row's background, so it spans the full height rather than the one row
            // its main-axis measurement reserves in a column.
            int height = child instanceof View.Fill<?>
                    ? bounds.rows()
                    : Math.min(intrinsicHeight(crossAxis(child), width, bounds.rows()), bounds.rows());
            int offset = 0;
            if (height < bounds.rows()) {
                offset = switch (row.align()) {
                    case CENTER -> (bounds.rows() - height) / 2;
                    case END -> bounds.rows() - height;
                    case START -> 0;
                };
            }
            Rect childBounds = new Rect(bounds.row() + offset, col, height, width);
            paint(child, screen, childBounds, regions);
            col += width;
        }
    }

    private int childWidth(View<?> child, int cols) {
        if (child instanceof View.Fill<?>) {
            return cols;
        }
        return Math.min(cols, intrinsicWidth(crossAxis(child), cols));
    }

    /**
     * The content that decides a child's cross axis. {@link View.Fixed} reserves and {@link View.Grow}
     * takes main-axis space from its parent, so neither wrapper's own measurement describes the
     * cross axis; only what the wrapper contains does. {@link View.Focused} and {@link View.Keyed}
     * are routing structure and reserve nothing, so they are transparent too: a routing wrapper
     * must not change what the child it wraps measures on either axis.
     */
    private static View<?> crossAxis(View<?> child) {
        View<?> content = child;
        while (true) {
            if (content instanceof View.Fixed<?> fixed) {
                content = fixed.child();
            } else if (content instanceof View.Grow<?> grow) {
                content = grow.child();
            } else if (content instanceof View.Keyed<?> keyed) {
                content = keyed.child();
            } else if (content instanceof View.Focused<?> focused) {
                content = focused.child();
            } else {
                return content;
            }
        }
    }

    private void paintBorder(View.BorderBox<?> box, Screen screen, Rect bounds, List<Region> regions) {
        Border border = box.border();
        if (!border.isVisible()) {
            paint(box.child(), screen, bounds, regions);
            return;
        }
        Style style = box.style();
        // One cell value per edge character, so the perimeter loop does not rebuild it per cell.
        String topLeft = Border.glyph(border.topLeft());
        String topRight = Border.glyph(border.topRight());
        String bottomLeft = Border.glyph(border.bottomLeft());
        String bottomRight = Border.glyph(border.bottomRight());
        String horizontal = Border.glyph(border.horizontal());
        String vertical = Border.glyph(border.vertical());
        screen.put(bounds.row(), bounds.col(), topLeft, style, null, 1);
        screen.put(bounds.row(), bounds.col() + bounds.cols() - 1, topRight, style, null, 1);
        screen.put(bounds.row() + bounds.rows() - 1, bounds.col(), bottomLeft, style, null, 1);
        screen.put(bounds.row() + bounds.rows() - 1, bounds.col() + bounds.cols() - 1,
                bottomRight, style, null, 1);
        for (int col = bounds.col() + 1; col < bounds.col() + bounds.cols() - 1; col++) {
            putIfInside(screen, bounds.row(), col, horizontal, style, 1);
            putIfInside(screen, bounds.row() + bounds.rows() - 1, col, horizontal, style, 1);
        }
        for (int row = bounds.row() + 1; row < bounds.row() + bounds.rows() - 1; row++) {
            putIfInside(screen, row, bounds.col(), vertical, style, 1);
            putIfInside(screen, row, bounds.col() + bounds.cols() - 1, vertical, style, 1);
        }
        if (!box.title().isEmpty() && bounds.cols() > 4) {
            StyledText title = box.title();
            List<StyledText> lines = wrapLines(title, Math.max(0, bounds.cols() - 4), 1);
            if (!lines.isEmpty()) {
                paintText(lines.get(0),
                        new Rect(bounds.row(), bounds.col() + 2, 1, Math.max(0, bounds.cols() - 4)),
                        screen);
            }
        }
        Rect inner = new Rect(bounds.row() + 1, bounds.col() + 1,
                Math.max(0, bounds.rows() - 2), Math.max(0, bounds.cols() - 2));
        paint(box.child(), screen, inner, regions);
    }

    private static void putIfInside(Screen screen, int row, int col, String grapheme, Style style, int width) {
        if (row >= 0 && row < screen.rows() && col >= 0 && col < screen.cols()) {
            screen.put(row, col, grapheme, style, null, width);
        }
    }

    private void paintText(StyledText text, Screen screen, Rect bounds) {
        paintText(text, bounds, screen);
    }

    private void paintText(StyledText text, Rect bounds, Screen screen) {
        List<StyledText> lines = wrapLines(text, bounds.cols(), bounds.rows());
        int limit = Math.min(bounds.rows(), lines.size());
        for (int i = 0; i < limit; i++) {
            paintLine(lines.get(i), screen, bounds.row() + i, bounds.col(), bounds.cols());
        }
    }

    private void paintLine(StyledText line, Screen screen, int row, int startCol, int cols) {
        int nextCol = startCol;
        int limit = startCol + cols;
        // The cell being assembled: the plain text it paints. A cell is only painted once no later
        // cluster continues it, because a continuation can widen or narrow it. Its columns are
        // therefore only known once it is whole, and that is where the row is tested for room:
        // wrapLines charged the row for the same whole cluster, reading the clusters that continue
        // it across the span boundary, while a cell started from one span's cluster can be narrower
        // or wider by the time it ends. Testing the width it started with gave up on a cell that
        // the row was measured to hold - a wide base whose U+FE0E arrives in the next span is one
        // column, not two.
        StringBuilder cell = null;
        StringBuilder leading = null;
        int cellCol = startCol;
        int cellWidth = 0;
        Style cellStyle = Style.NONE;
        String cellLink = null;
        for (StyledText.Span span : line.spans()) {
            Iterable<String> graphemes = Graphemes.iterate(span.text());
            for (String grapheme : graphemes) {
                int width = TextMeasure.clusterColumns(grapheme, policy);
                // A cluster that claims a column or holds a mark or selector is the only kind a cell
                // can be continued by; a format character that claims no column cannot be, and a run
                // of them must not re-segment the growing cell.
                if ((width > 0 || hasNoBase(grapheme)) && cell != null
                        && continuesCluster(cell, grapheme)) {
                    // A span boundary can split one cluster the measurement read as one: the mark
                    // or selector in its own span continues the cell painted before it instead of
                    // claiming a cell of its own. Giving it one painted a column the plain text
                    // never counted, which pushed the rest of the row - a frame's right edge
                    // included - past the width the value was measured at.
                    cell.append(grapheme);
                    cellWidth = TextMeasure.clusterColumns(cell.toString(), policy);
                    continue;
                }
                if (width == 0) {
                    // Invisible format characters claim no column, so they stay inside the cell
                    // already painted instead of starting one of their own. One that leads the row
                    // is held for the cell that follows it: the plain text keeps it in that cluster,
                    // and a style boundary must not paint different content from the same value.
                    if (cell != null) {
                        cell.append(grapheme);
                    } else {
                        if (leading == null) {
                            leading = new StringBuilder();
                        }
                        leading.append(grapheme);
                    }
                    continue;
                }
                if (cell != null) {
                    // This cluster does not continue the cell before it, so that cell is whole and
                    // its final width is the width the row was charged for. It is the cell the row
                    // has room for or not, and a cell that has none is not painted half: the rest of
                    // the row goes the way wrapLines sent it, to the next line.
                    if (nextCol + cellWidth > limit) {
                        return;
                    }
                    screen.put(row, cellCol, paintedText(cell, leading), cellStyle, cellLink,
                            cellWidth);
                    nextCol = cellCol + cellWidth;
                    leading = null;
                }
                cellCol = nextCol;
                cellWidth = width;
                cellStyle = span.style();
                cellLink = span.link();
                cell = new StringBuilder(grapheme);
            }
        }
        if (cell != null && nextCol + cellWidth <= limit) {
            screen.put(row, cellCol, paintedText(cell, leading), cellStyle, cellLink, cellWidth);
        }
    }

    /**
     * The text a cell paints. Zero-width clusters that led the row are kept in front of it; they
     * take no columns, so the cell keeps the columns its own plain text measured.
     */
    private static String paintedText(CharSequence cell, CharSequence leading) {
        return leading == null ? cell.toString() : leading.toString() + cell;
    }

    /**
     * True when {@code cluster} is part of the same cluster as the plain text of {@code cell}. A
     * style boundary can split one cluster - a base and its mark, a character and the selector that
     * chooses its wide or text form - but the columns a value is measured at are the columns of its
     * plain text, so only a cluster the measurement reads as one may join the cell before it.
     */
    private static boolean continuesCluster(CharSequence cell, String cluster) {
        return TextMeasure.graphemeCount(cell.toString() + cluster) == 1;
    }

    /**
     * True when a cluster holds no base character of its own - only combining marks and the format
     * characters that join them. Such a cluster measures a column of its own, so where no cell
     * before it continues it, {@link #wrapLines} draws the dotted circle in place of the missing
     * base.
     */
    private static boolean hasNoBase(String grapheme) {
        boolean mark = false;
        for (int offset = 0; offset < grapheme.length();) {
            int codePoint = grapheme.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                mark = true;
            } else if (type != Character.FORMAT) {
                return false;
            }
        }
        return mark;
    }

    /**
     * Wraps styled text to {@code cols} columns, preserving each span's style and link. A wide
     * cluster that would straddle the right edge starts the next line when the current line is
     * empty, otherwise it is clipped (never half-painted).
     */
    public List<StyledText> wrapLines(StyledText text, int cols) {
        return wrapLines(text, cols, Integer.MAX_VALUE);
    }

    private List<StyledText> wrapLines(StyledText text, int cols, int maxLines) {
        List<StyledText> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        if (cols <= 0 || maxLines <= 0) {
            return lines;
        }
        LineBuilder current = new LineBuilder();
        int used = 0;
        boolean softWrapped = false;
        ClusterCursor cursor = new ClusterCursor(text, policy);
        while (cursor.next()) {
            String raw = cursor.text();
            Style style = cursor.style();
            String link = cursor.link();
            if ("\n".equals(raw)) {
                lines.add(current.build());
                if (lines.size() >= maxLines) {
                    return lines;
                }
                current = new LineBuilder();
                used = 0;
                softWrapped = false;
                continue;
            }
            if (" ".equals(raw) && softWrapped) {
                continue;
            }
            if ("\t".equals(raw)) {
                // The stop reaches the line as spaces, so that row's own plain text re-segments
                // them together with the clusters that follow the tab: the last space of the stop
                // is a cell those clusters continue, exactly as a typed space would be. A cluster
                // that claims no column of its own - a variation selector, a joiner - joins that
                // cell, and a selector makes it two columns wide. That column has to be reserved
                // with the stop: charging the stop alone left the row a column wider than the width
                // it wrapped to, and paint dropped the row's tail. A style boundary can split the
                // chain of clusters that continues the cell - a joiner in one span, the selector
                // after it in the next - so the whole chain is read ahead and appended with the
                // stop, not only the cluster behind the tab: the joiner claims nothing itself and
                // the selector behind it is what widens the cell. A cluster with columns of its own
                // - a bare combining mark - starts its own cell with the dotted circle in front of
                // it, which keeps it clear of the stop's last space.
                List<String> joins = cursor.clustersContinuing(" ");
                int widened = widenedByClusters(" ", joins);
                int stop = 4 - (used % 4);
                if (used + stop + widened > cols && used > 0) {
                    lines.add(current.build());
                    if (lines.size() >= maxLines) {
                        return lines;
                    }
                    current = new LineBuilder();
                    used = 0;
                    stop = 4;
                }
                // A stop wider than an empty line has nowhere to wrap to, so it is clamped to
                // the columns it can occupy; the painted line clips the same cells. The column a
                // selector after the tab adds is part of that clamp, and a stop left with no room
                // for the cell it ends with is clipped whole, selector included - a clipped stop
                // leaves the clusters that join it to the loop below, which appends them where the
                // stop's spaces would have been.
                stop = Math.min(stop, cols - used - widened);
                if (stop > 0) {
                    current.append(" ".repeat(stop), style, link);
                    used += stop + widened;
                    if (!joins.isEmpty()) {
                        // The scan read these past the tab without consuming them; now that the
                        // stop is committed they join the cell it ends with, on the same line.
                        for (int i = 0; i < joins.size(); i++) {
                            cursor.next();
                            current.append(cursor.text(), cursor.style(), cursor.link());
                        }
                        softWrapped = false;
                    }
                }
                continue;
            }
            int width = TextMeasure.clusterColumns(raw, policy);
            if (width == 0) {
                // Invisible format characters claim no column: the cell before them keeps the
                // columns it measured, and a leading one has no cell to join. A selector that does
                // widen the character it follows was already placed with that cell, which looked at
                // the clusters continuing it before it committed them to a line - a tab stop's last
                // space being the one cell not placed by the loop below, and charged here.
                current.append(raw, style, link);
                softWrapped = false;
                continue;
            }
            // One cell is one cluster of the plain text the value is measured by, so the clusters
            // that continue this one belong to the same cell. Its columns - and whether it fits the
            // row at all - are only known once it is whole, and it has to move to the next line as
            // a unit: a selector that widens the base cannot be dropped at the edge, and leaving it
            // behind would change the content a style boundary must not touch. The cell is measured
            // as the text the row will hold, not as the cluster it started from: a cluster with no
            // base of its own is written with the dotted circle that stands in for that base, and
            // that circle is part of the cell's columns.
            boolean baseless = hasNoBase(raw);
            String cellStart = baseless ? TextMeasure.DOTTED_CIRCLE + raw : raw;
            String cellPlain = cellStart;
            int cellWidth = baseless ? TextMeasure.clusterColumns(cellPlain, policy) : width;
            List<Piece> pieces = null;
            for (String following = cursor.peek(); following != null
                    && continuesCluster(cellPlain, following); following = cursor.peek()) {
                cursor.next();
                if (pieces == null) {
                    pieces = new ArrayList<>(2);
                    pieces.add(new Piece(cellStart, style, link));
                }
                pieces.add(new Piece(following, cursor.style(), cursor.link()));
                cellPlain = cellPlain + following;
                cellWidth = TextMeasure.clusterColumns(cellPlain, policy);
            }
            if (used + cellWidth > cols) {
                if (used > 0) {
                    lines.add(current.build());
                    if (lines.size() >= maxLines) {
                        return lines;
                    }
                    current = new LineBuilder();
                    used = 0;
                    softWrapped = true;
                    if (" ".equals(raw) && pieces == null) {
                        // A space at the break is dropped: the blank it would leave belongs at the
                        // end of the previous line, not at the start of this one.
                        continue;
                    }
                }
                if (cellWidth > cols) {
                    // A cell wider than the whole line is clipped, never half painted.
                    continue;
                }
            }
            if (pieces == null) {
                current.append(cellPlain, style, link);
            } else {
                for (Piece piece : pieces) {
                    current.append(piece.text(), piece.style(), piece.link());
                }
            }
            used += cellWidth;
            softWrapped = false;
        }
        lines.add(current.build());
        return lines;
    }

    /** One span-sized piece of a cell that a style boundary split into several pieces. */
    private record Piece(String text, Style style, String link) {
    }

    /**
     * The columns the clusters after a cell add to that cell while claiming no column of their own -
     * a variation selector widens the base it joins, a joiner does not. The whole chain that
     * continues the cell has to be measured with it: a style boundary can split one cluster of the
     * plain text into several clusters of a styled value, and the joiner that claims nothing of its
     * own can sit in one span while the selector that widens the cell follows it in the next.
     * Measuring only the first left the cell a column wider than the row was charged for.
     */
    private int widenedByClusters(String cell, List<String> following) {
        String grown = cell;
        for (String cluster : following) {
            grown += cluster;
        }
        return Math.max(0, TextMeasure.clusterColumns(grown, policy)
                - TextMeasure.clusterColumns(cell, policy));
    }

    /**
     * Iterates the clusters of a styled value in order, with the style and link of the span each
     * one came from, and can inspect the clusters after the current one without consuming them: the
     * wrap arithmetic has to know the columns of a whole cell before it commits it to a line.
     */
    private static final class ClusterCursor {
        private final List<StyledText.Span> spans;
        private final WidthPolicy policy;
        private int spanIndex;
        private Iterator<String> clusters = Collections.emptyIterator();
        private Style spanStyle = Style.NONE;
        private String spanLink;
        private String ahead;
        private Style aheadStyle = Style.NONE;
        private String aheadLink;
        private boolean hasAhead;
        /** Clusters {@link #clustersContinuing} read ahead of the current one, in order. */
        private List<Piece> buffered;
        private int bufferedIndex;
        private String text;
        private Style style = Style.NONE;
        private String link;

        ClusterCursor(StyledText value, WidthPolicy policy) {
            this.spans = value.spans();
            this.policy = policy;
        }

        /** Consumes the next cluster; false once the value is spent. */
        boolean next() {
            if (!hasAhead && !fetch()) {
                text = null;
                return false;
            }
            text = ahead;
            style = aheadStyle;
            link = aheadLink;
            hasAhead = false;
            return true;
        }

        /** The cluster after the current one; null once the value is spent. Does not consume it. */
        String peek() {
            if (!hasAhead && !fetch()) {
                return null;
            }
            return ahead;
        }

        /**
         * The clusters after the current one that continue {@code cell} as one cluster of the
         * value's plain text while claiming no column of their own, in order, without consuming
         * them. A style boundary can split one cluster of the plain text into a run of a value's
         * clusters, so the run - not just the cluster behind the current one - is what shows how
         * wide the cell it continues ends up. They stay in the cursor: the caller reads them back
         * with {@link #next()} once the cell they widen is committed to a line.
         */
        List<String> clustersContinuing(String cell) {
            List<String> chain = null;
            String grown = cell;
            while (hasAhead || fetchFromSource()) {
                if (TextMeasure.clusterColumns(ahead, policy) > 0
                        || !continuesCluster(grown, ahead)) {
                    break;
                }
                if (chain == null) {
                    chain = new ArrayList<>(2);
                    if (buffered == null) {
                        buffered = new ArrayList<>(2);
                    } else {
                        // The buffer is empty here: everything it held was delivered before the
                        // current cluster, which is the tab this scan follows.
                        buffered.clear();
                    }
                    bufferedIndex = 0;
                }
                chain.add(ahead);
                buffered.add(new Piece(ahead, aheadStyle, aheadLink));
                hasAhead = false;
                grown += ahead;
            }
            if (chain != null && hasAhead) {
                // The scan stopped at a cluster that does not continue the cell. It was read past
                // the chain, so it queues behind it and is delivered in the value's own order.
                buffered.add(new Piece(ahead, aheadStyle, aheadLink));
                hasAhead = false;
            }
            return chain == null ? List.of() : chain;
        }

        String text() {
            return text;
        }

        Style style() {
            return style;
        }

        String link() {
            return link;
        }

        private boolean fetch() {
            if (buffered != null && bufferedIndex < buffered.size()) {
                Piece piece = buffered.get(bufferedIndex++);
                ahead = piece.text();
                aheadStyle = piece.style();
                aheadLink = piece.link();
                hasAhead = true;
                return true;
            }
            return fetchFromSource();
        }

        /** Reads the next cluster of the value's spans, ignoring anything a scan read ahead. */
        private boolean fetchFromSource() {
            while (!clusters.hasNext()) {
                if (spanIndex >= spans.size()) {
                    return false;
                }
                StyledText.Span span = spans.get(spanIndex++);
                clusters = Graphemes.iterate(span.text()).iterator();
                spanStyle = span.style();
                spanLink = span.link();
            }
            ahead = clusters.next();
            aheadStyle = spanStyle;
            aheadLink = spanLink;
            hasAhead = true;
            return true;
        }
    }

    /** Accumulates a complete styled run before creating an immutable span. */
    private static final class LineBuilder {
        private final List<StyledText> parts = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Style style;
        private String link;

        void append(String value, Style nextStyle, String nextLink) {
            if (!Objects.equals(style, nextStyle) || !Objects.equals(link, nextLink)) {
                flush();
                style = nextStyle;
                link = nextLink;
            }
            text.append(value);
        }

        private void flush() {
            if (!text.isEmpty()) {
                parts.add(StyledText.of(text.toString(), style, link));
                text.setLength(0);
            }
        }

        StyledText build() {
            flush();
            return StyledText.concat(parts.toArray(StyledText[]::new));
        }
    }
}
