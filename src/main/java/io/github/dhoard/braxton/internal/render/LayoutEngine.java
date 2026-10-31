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
            widest = Math.max(widest, contentWidth(crossAxis(column.items().get(i)), maxCols));
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

    /**
     * The rightmost column the row's content reaches when it is painted at {@code cols}. Each
     * child is counted at the share {@link #paintRow} gives it, so a {@code Grow} contributes what
     * it holds and a child that fills its share keeps the row as wide as the space it was given.
     */
    private int rowContentWidth(View.Row<?> row, int cols) {
        List<? extends View<?>> items = row.items();
        int[] widths = rowWidths(items, cols);
        int offset = 0;
        int widest = 0;
        for (int i = 0; i < items.size(); i++) {
            if (widths[i] > 0) {
                widest = Math.max(widest, offset + contentWidth(items.get(i), widths[i]));
            }
            offset += widths[i];
        }
        return Math.min(cols, widest);
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
            widest = Math.max(widest, contentWidth(crossAxis(overlay.layers().get(i)), maxCols));
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
        return Math.min(cols, contentWidth(crossAxis(child), cols));
    }

    /**
     * The columns a child's content occupies when it is painted at {@code cols}, the width a
     * cross-axis measurement has to use.
     *
     * <p>{@link #intrinsicWidth} answers for the main axis: a {@link View.Grow} takes its size from
     * its parent, so it measures as zero and a row's own width leaves its share out. A column's
     * cross axis is not a reservation but the rectangle its child is painted in, so what a
     * {@code Grow} holds has to be counted at the share of the width that child's own layout gives
     * it. Measuring it as zero narrows the rectangle below the width the child's height was
     * measured at and clips away the content those rows were reserved for.</p>
     */
    private int contentWidth(View<?> view, int cols) {
        if (cols <= 0) {
            return 0;
        }
        return switch (view) {
            case View.Text<?> text -> textWidth(text.text(), cols);
            case View.Blank<?> blank -> 0;
            case View.Fill<?> fill -> cols;
            case View.Column<?> column -> columnWidth(column, cols);
            case View.Row<?> row -> rowContentWidth(row, cols);
            case View.Padding<?> padding -> Math.min(cols,
                    padding.insets().horizontal() + contentWidth(padding.child(),
                            Math.max(0, cols - padding.insets().horizontal())));
            case View.BorderBox<?> box -> {
                int frame = box.border().isVisible() ? 2 : 0;
                yield Math.min(cols, frame + contentWidth(box.child(), Math.max(0, cols - frame)));
            }
            // A Fixed reserves its size on a parent row's main axis, so that row is as wide as the
            // size and not as the content inside it; a column's cross axis unwraps it (crossAxis).
            case View.Fixed<?> fixed -> Math.min(cols, fixed.size());
            case View.Grow<?> grow -> Math.min(cols, contentWidth(grow.child(), cols));
            case View.Overlay<?> overlay -> overlayWidth(overlay, cols);
            case View.Keyed<?> keyed -> contentWidth(keyed.child(), cols);
            case View.Focused<?> focused -> contentWidth(focused.child(), cols);
        };
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
        // A cell that nothing joins paints the cluster that opened it, so it keeps that cluster as
        // its text and no builder is allocated for it. A run is only assembled when a continuation
        // or an invisible cluster actually adds text to the cell, which is what a row of ordinary
        // text never does: one builder and one copy per cell made the paint path allocate several
        // objects for every column it filled.
        String cellBase = null;
        StringBuilder cellRun = null;
        StringBuilder leading = null;
        int cellCol = startCol;
        int cellWidth = 0;
        Style cellStyle = Style.NONE;
        String cellLink = null;
        // The span every cluster of the open cell came from, or -1 when the cell is not made only of
        // consecutive clusters of one span - the only kind of cell whose gap to the cluster after it
        // that span's own segmentation has already settled.
        int cellSpan = -1;
        int spanIndex = -1;
        for (StyledText.Span span : line.spans()) {
            spanIndex++;
            int clusterIndex = 0;
            Iterable<String> graphemes = Graphemes.iterate(span.text());
            for (String grapheme : graphemes) {
                clusterIndex++;
                int width = TextMeasure.clusterColumns(grapheme, policy);
                // A cluster that claims a column or holds a mark or selector is the only kind a cell
                // can be continued by; a format character that claims no column cannot be, and a run
                // of them must not re-segment the growing cell.
                // A cell built from clusters that sit consecutively inside one span, and did not open
                // at that span's first cluster, ends at a gap that span was segmented at: the cluster
                // after it is a cluster of its own there, so asking the break iterator again would
                // re-segment a cell it has already split. A cell that opened at the span's first
                // cluster is the exception - that cluster can be the tail of a cluster the plain text
                // reads as one, and the next cluster can continue it.
                if ((width > 0 || hasNoBase(grapheme)) && cellBase != null && cellSpan != spanIndex
                        && continuesCluster(cellText(cellBase, cellRun), grapheme)) {
                    // A span boundary can split one cluster the measurement read as one: the mark
                    // or selector in its own span continues the cell painted before it instead of
                    // claiming a cell of its own. Giving it one painted a column the plain text
                    // never counted, which pushed the rest of the row - a frame's right edge
                    // included - past the width the value was measured at.
                    cellRun = joinCell(cellBase, cellRun, grapheme);
                    cellWidth = TextMeasure.clusterColumns(cellRun.toString(), policy);
                    // A cell that took a cluster from a span it did not open in is no longer a run
                    // this row has a segmentation of.
                    cellSpan = -1;
                    continue;
                }
                if (width == 0) {
                    // Invisible format characters claim no column, so they stay inside the cell
                    // already painted instead of starting one of their own. One that leads the row
                    // is held for the cell that follows it: the plain text keeps it in that cluster,
                    // and a style boundary must not paint different content from the same value.
                    if (cellBase != null) {
                        cellRun = joinCell(cellBase, cellRun, grapheme);
                        if (cellSpan != spanIndex) {
                            // The cell now holds clusters from more than one span, and no span's
                            // segmentation covers the gap at its end.
                            cellSpan = -1;
                        }
                    } else {
                        if (leading == null) {
                            leading = new StringBuilder();
                        }
                        leading.append(grapheme);
                    }
                    continue;
                }
                if (cellBase != null) {
                    // This cluster does not continue the cell before it, so that cell is whole and
                    // its final width is the width the row was charged for. It is the cell the row
                    // has room for or not, and a cell that has none is not painted half: the rest of
                    // the row goes the way wrapLines sent it, to the next line.
                    if (nextCol + cellWidth > limit) {
                        return;
                    }
                    screen.put(row, cellCol, paintedText(cellText(cellBase, cellRun), leading),
                            cellStyle, cellLink, cellWidth);
                    nextCol = cellCol + cellWidth;
                    leading = null;
                }
                cellCol = nextCol;
                cellWidth = width;
                cellStyle = span.style();
                cellLink = span.link();
                cellBase = grapheme;
                cellRun = null;
                cellSpan = clusterIndex > 1 ? spanIndex : -1;
            }
        }
        if (cellBase != null && nextCol + cellWidth <= limit) {
            screen.put(row, cellCol, paintedText(cellText(cellBase, cellRun), leading),
                    cellStyle, cellLink, cellWidth);
        }
    }

    /** The plain text of the cell being assembled. */
    private static CharSequence cellText(String base, StringBuilder run) {
        return run == null ? base : run;
    }

    /** Adds a cluster to the cell, opening its run only when the cell has more than its base. */
    private static StringBuilder joinCell(String base, StringBuilder run, String grapheme) {
        StringBuilder joined = run == null ? new StringBuilder(base) : run;
        return joined.append(grapheme);
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
        // The overwhelmingly common cell and cluster are a single printable ASCII character each.
        // No grapheme break rule joins two characters in that range - none of them is a carriage
        // return, line feed, extend, format, spacing mark, regional indicator, hangul element or
        // pictograph - so the gap between them is a boundary whatever the rest of the text holds,
        // and the answer needs neither a copy of the cell nor a segmentation of it. The same fact
        // is what TextMeasure answers caret questions with; see TextMeasureBoundaryCostTest.
        if (cluster.length() == 1 && cell.length() > 0) {
            char before = cell.charAt(cell.length() - 1);
            char after = cluster.charAt(0);
            if (isPrintableAscii(before) && isPrintableAscii(after)) {
                return false;
            }
        }
        return TextMeasure.graphemeCount(cell.toString() + cluster) == 1;
    }

    private static boolean isPrintableAscii(char value) {
        return value >= 0x20 && value <= 0x7E;
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
        // A run of clusters that claim no column - invisible format characters. The plain text may
        // keep the tail of such a run inside the cluster that follows it, and a span boundary can
        // cut the two apart, so the run waits here: the columns of the cell that cluster starts are
        // only known with the run measured inside it rather than beside it.
        List<Piece> pending = null;
        ClusterCursor cursor = new ClusterCursor(text, policy);
        while (cursor.next()) {
            String raw = cursor.text();
            Style style = cursor.style();
            String link = cursor.link();
            if ("\n".equals(raw)) {
                // A newline is a cluster boundary whatever the run holds, so it all stays on the
                // line it was read on.
                commitRun(current, pending);
                pending = null;
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
                commitRun(current, pending);
                pending = null;
                continue;
            }
            if ("\t".equals(raw)) {
                commitRun(current, pending);
                pending = null;
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
                    // The stop is committed content on this line, not a break: its spaces occupy the
                    // columns in front of everything that follows, so a space after it belongs to
                    // the row rather than to the blank the break left behind. Only the clusters the
                    // scan read past the tab may still join the cell the stop ends with.
                    softWrapped = false;
                    if (!joins.isEmpty()) {
                        // The scan read these past the tab without consuming them; now that the
                        // stop is committed they join the cell it ends with, on the same line.
                        for (int i = 0; i < joins.size(); i++) {
                            cursor.next();
                            current.append(cursor.text(), cursor.style(), cursor.link());
                        }
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
                // space being the one cell not placed by the loop below, and charged here. The run
                // waits here instead of reaching the line at once because the plain text may keep
                // its tail inside the cluster that comes next; the loop below decides which part of
                // the run that is, and writes it into the cell that cluster starts.
                if (pending == null) {
                    pending = new ArrayList<>(2);
                }
                pending.add(new Piece(raw, style, link));
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
            // The tail of an invisible run that the plain text keeps inside this cluster belongs to
            // the cell as well, and it can be the character that decides the cell's width: the
            // U+FE0F a span boundary splits from a bare combining mark is what makes the dotted
            // circle's cell two columns instead of one. Measured without it, the same plain text
            // paints one column narrower than every measurement of it and shifts every cell behind
            // it a column left. The head of the run continues nothing, so it stays where it stands.
            List<Piece> attached = null;
            if (pending != null) {
                int joins = joinsRun(pending, raw);
                for (int i = 0; i < joins; i++) {
                    Piece piece = pending.get(i);
                    current.append(piece.text(), piece.style(), piece.link());
                }
                if (joins < pending.size()) {
                    attached = pending.subList(joins, pending.size());
                }
                pending = null;
            }
            String prefix = "";
            if (attached != null) {
                StringBuilder held = new StringBuilder();
                for (Piece piece : attached) {
                    held.append(piece.text());
                }
                prefix = held.toString();
            }
            // The dotted circle and the held run only become part of the cell when they exist, so a
            // cell that is just the cluster the cursor read is not copied out of it first.
            boolean baseless = prefix.isEmpty() ? hasNoBase(raw) : hasNoBase(prefix + raw);
            String circle = baseless ? TextMeasure.DOTTED_CIRCLE : "";
            String cellStart = circle.isEmpty()
                    ? (prefix.isEmpty() ? raw : prefix + raw)
                    : circle + prefix + raw;
            String cellPlain = cellStart;
            int cellWidth = baseless ? TextMeasure.clusterColumns(cellPlain, policy) : width;
            List<Piece> pieces = null;
            if (attached != null) {
                // Each attached cluster keeps the style and link of the span it arrived in, and the
                // dotted circle is written where the whole cluster starts.
                pieces = new ArrayList<>(attached.size() + 2);
                for (int i = 0; i < attached.size(); i++) {
                    Piece piece = attached.get(i);
                    pieces.add(new Piece(i == 0 ? circle + piece.text() : piece.text(),
                            piece.style(), piece.link()));
                }
                pieces.add(new Piece(raw, style, link));
            }
            for (String following = cursor.peek(); following != null;
                    following = cursor.peek()) {
                // The cursor took both clusters from one segmentation of the same span, so the cell
                // that ends at the first of them is whole there and the second opens a cluster of its
                // own: asking again would copy the cell out and segment it once per column. A cell
                // carrying the dotted circle, or an invisible run held in front of it, holds text
                // that segmentation never saw, so it is still asked - as is a cell that opens the
                // span, whose first cluster can be the tail of a cluster the plain text reads as one.
                if (attached == null && circle.isEmpty() && cursor.gapIsSegmented()) {
                    break;
                }
                if (!continuesCluster(cellPlain, following)) {
                    break;
                }
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
        // A run the value ends with continues nothing, so it closes the line it was read on.
        commitRun(current, pending);
        lines.add(current.build());
        return lines;
    }

    /** One span-sized piece of a cell that a style boundary split into several pieces. */
    private record Piece(String text, Style style, String link) {
    }

    /** Writes a run of invisible clusters to the line it was read on; it continues no cell. */
    private static void commitRun(LineBuilder line, List<Piece> run) {
        if (run == null) {
            return;
        }
        for (Piece piece : run) {
            line.append(piece.text(), piece.style(), piece.link());
        }
    }

    /**
     * The index in {@code run} where the part that continues {@code cluster} begins: the plain text
     * keeps that part of the run inside the cluster the cell paints, while the clusters before the
     * index stand on their own. The gaps are tested from the one nearest the cluster outwards, so a
     * run that joins nothing - a run of zero-width spaces - costs one test rather than one per
     * character, and the run is never copied or re-segmented as it grows.
     */
    private static int joinsRun(List<Piece> run, String cluster) {
        String grown = cluster;
        for (int i = run.size() - 1; i >= 0; i--) {
            if (!continuesCluster(run.get(i).text(), grown)) {
                return i + 1;
            }
            grown = run.get(i).text() + grown;
        }
        return 0;
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
        // Where the cluster the cursor is holding sits in the span it was segmented from, counting
        // from one, with -1 for a cluster whose place is unknown - one a scan read ahead and put
        // back. Neighbours inside one span were already split by the break iterator that segmented
        // it, which is the question continuesCluster answers; a span's first cluster is the
        // exception, because that span can start in the middle of a cluster the whole value reads
        // as one.
        private int spanClusterCount;
        private int aheadIndex = -1;
        private int textIndex = -1;
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
                textIndex = -1;
                return false;
            }
            text = ahead;
            textIndex = aheadIndex;
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

        /**
         * True when the gap between the current cluster and the one {@link #peek()} returns is one
         * the break iterator already segmented: neighbours in the same span, and the current cluster
         * is not that span's first cluster.
         */
        boolean gapIsSegmented() {
            return textIndex > 1 && aheadIndex == textIndex + 1;
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
                aheadIndex = -1;
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
                spanClusterCount = 0;
                spanStyle = span.style();
                spanLink = span.link();
            }
            ahead = clusters.next();
            aheadIndex = ++spanClusterCount;
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
