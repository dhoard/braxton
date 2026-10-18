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

    /** Intrinsic height at {@code cols} columns; flex children measure as zero main-axis size. */
    public int intrinsicHeight(View<?> view, int cols) {
        if (cols <= 0) {
            return 0;
        }
        return switch (view) {
            case View.Text<?> text -> wrapLines(text.text(), cols).size();
            case View.Blank<?> blank -> 0;
            case View.Fill<?> fill -> 1;
            case View.Column<?> column -> columnHeight(column, cols);
            case View.Row<?> row -> rowHeight(row, cols);
            // The child is painted inside the horizontal insets, so it must be measured at that
            // width; measuring at the full width can wrap into fewer rows than it paints.
            case View.Padding<?> padding -> padding.insets().vertical()
                    + intrinsicHeight(padding.child(),
                            Math.max(0, cols - padding.insets().horizontal()));
            case View.BorderBox<?> box -> (box.border().isVisible() ? 2 : 0)
                    + intrinsicHeight(box.child(), Math.max(0, cols - (box.border().isVisible() ? 2 : 0)));
            case View.Fixed<?> fixed -> fixed.size();
            case View.Grow<?> grow -> 0;
            case View.Overlay<?> overlay -> overlayHeight(overlay, cols);
            case View.Keyed<?> keyed -> intrinsicHeight(keyed.child(), cols);
            case View.Focused<?> focused -> intrinsicHeight(focused.child(), cols);
        };
    }

    /** Intrinsic width, capped at {@code maxCols}; flex children measure as zero main-axis size. */
    public int intrinsicWidth(View<?> view, int maxCols) {
        if (maxCols <= 0) {
            return 0;
        }
        return switch (view) {
            case View.Text<?> text -> Math.min(maxCols, textWidth(text.text()));
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

    private int columnHeight(View.Column<?> column, int cols) {
        int total = 0;
        for (int i = 0; i < column.items().size(); i++) {
            total += intrinsicHeight(column.items().get(i), cols);
        }
        return total;
    }

    private int columnWidth(View.Column<?> column, int maxCols) {
        int widest = 0;
        for (int i = 0; i < column.items().size(); i++) {
            widest = Math.max(widest, intrinsicWidth(crossAxis(column.items().get(i)), maxCols));
        }
        return widest;
    }

    private int rowHeight(View.Row<?> row, int cols) {
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
            tallest = Math.max(tallest, intrinsicHeight(crossAxis(items.get(i)), widths[i]));
        }
        return tallest;
    }

    /** Main-axis width {@link #paintRow} gives each item at {@code cols} columns. */
    private int[] rowWidths(List<? extends View<?>> items, int cols) {
        int[] widths = new int[items.size()];
        int used = 0;
        int flexWeight = 0;
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
                    widths[i] = remaining * grow.weight() / flexWeight;
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

    private int overlayHeight(View.Overlay<?> overlay, int cols) {
        int tallest = 0;
        for (int i = 0; i < overlay.layers().size(); i++) {
            tallest = Math.max(tallest, intrinsicHeight(crossAxis(overlay.layers().get(i)), cols));
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

    private int textWidth(StyledText text) {
        int widest = 0;
        for (String line : text.plainText().split("\n", -1)) {
            widest = Math.max(widest, expandedColumns(line));
        }
        return widest;
    }

    /** Columns of one line with tabs expanded to the same four-column stops the wrapper uses. */
    private int expandedColumns(String line) {
        int used = 0;
        for (String cluster : TextMeasure.graphemes(line)) {
            used += "\t".equals(cluster)
                    ? 4 - (used % 4)
                    : TextMeasure.clusterColumns(cluster, policy);
        }
        return used;
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
        int flexWeight = 0;
        for (int i = 0; i < items.size(); i++) {
            View<?> child = items.get(i);
            if (child instanceof View.Grow<?> grow) {
                flexWeight += grow.weight();
            } else if (child instanceof View.Fixed<?> fixed) {
                heights[i] = Math.min(fixed.size(), Math.max(0, bounds.rows() - used));
                used += heights[i];
            } else {
                heights[i] = Math.min(intrinsicHeight(child, bounds.cols()),
                        Math.max(0, bounds.rows() - used));
                used += heights[i];
            }
        }
        int remaining = Math.max(0, bounds.rows() - used);
        if (flexWeight > 0) {
            int distributed = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof View.Grow<?> grow) {
                    heights[i] = remaining * grow.weight() / flexWeight;
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
            int width = childWidth(child, bounds.cols());
            int col = bounds.col();
            if (width < bounds.cols() && !(child instanceof View.Fill<?>)) {
                col += switch (column.align()) {
                    case CENTER -> (bounds.cols() - width) / 2;
                    case END -> bounds.cols() - width;
                    case START -> 0;
                };
            }
            Rect childBounds = new Rect(row, col, height, Math.max(0, bounds.cols() - (col - bounds.col())));
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
                    : Math.min(intrinsicHeight(crossAxis(child), width), bounds.rows());
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
     * cross axis; only what the wrapper contains does.
     */
    private static View<?> crossAxis(View<?> child) {
        View<?> content = child;
        while (true) {
            if (content instanceof View.Fixed<?> fixed) {
                content = fixed.child();
            } else if (content instanceof View.Grow<?> grow) {
                content = grow.child();
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
        screen.put(bounds.row(), bounds.col(), String.valueOf(border.topLeft()), style, null, 1);
        screen.put(bounds.row(), bounds.col() + bounds.cols() - 1,
                String.valueOf(border.topRight()), style, null, 1);
        screen.put(bounds.row() + bounds.rows() - 1, bounds.col(),
                String.valueOf(border.bottomLeft()), style, null, 1);
        screen.put(bounds.row() + bounds.rows() - 1, bounds.col() + bounds.cols() - 1,
                String.valueOf(border.bottomRight()), style, null, 1);
        for (int col = bounds.col() + 1; col < bounds.col() + bounds.cols() - 1; col++) {
            putIfInside(screen, bounds.row(), col, String.valueOf(border.horizontal()), style, 1);
            putIfInside(screen, bounds.row() + bounds.rows() - 1, col,
                    String.valueOf(border.horizontal()), style, 1);
        }
        for (int row = bounds.row() + 1; row < bounds.row() + bounds.rows() - 1; row++) {
            putIfInside(screen, row, bounds.col(), String.valueOf(border.vertical()), style, 1);
            putIfInside(screen, row, bounds.col() + bounds.cols() - 1,
                    String.valueOf(border.vertical()), style, 1);
        }
        if (!box.title().isEmpty() && bounds.cols() > 4) {
            StyledText title = box.title();
            List<StyledText> lines = wrapLines(title, Math.max(0, bounds.cols() - 4));
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
        List<StyledText> lines = wrapLines(text, bounds.cols());
        int limit = Math.min(bounds.rows(), lines.size());
        for (int i = 0; i < limit; i++) {
            paintLine(lines.get(i), screen, bounds.row() + i, bounds.col(), bounds.cols());
        }
    }

    private void paintLine(StyledText line, Screen screen, int row, int startCol, int cols) {
        int col = startCol;
        int limit = startCol + cols;
        String lastText = null;
        int lastCol = -1;
        Style lastStyle = Style.NONE;
        String lastLink = null;
        int lastWidth = 0;
        for (StyledText.Span span : line.spans()) {
            List<String> graphemes = TextMeasure.graphemes(span.text());
            for (String grapheme : graphemes) {
                String rendered = withBase(grapheme);
                int width = TextMeasure.clusterColumns(rendered, policy);
                if (width == 0) {
                    // Invisible format characters claim no column, so they join the cell already
                    // painted instead of starting one of their own; a leading one has no cell to
                    // join and is dropped.
                    if (lastText != null) {
                        lastText += rendered;
                        screen.put(row, lastCol, lastText, lastStyle, lastLink, lastWidth);
                    }
                    continue;
                }
                if (col + width > limit) {
                    return;
                }
                lastText = rendered;
                lastCol = col;
                lastStyle = span.style();
                lastLink = span.link();
                lastWidth = width;
                screen.put(row, col, rendered, span.style(), span.link(), width);
                col += width;
            }
        }
    }

    private static String withBase(String grapheme) {
        if (grapheme.isEmpty()) {
            return grapheme;
        }
        int first = grapheme.codePointAt(0);
        int type = Character.getType(first);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) {
            return TextMeasure.DOTTED_CIRCLE + grapheme;
        }
        return grapheme;
    }

    /**
     * Wraps styled text to {@code cols} columns, preserving each span's style and link. A wide
     * cluster that would straddle the right edge starts the next line when the current line is
     * empty, otherwise it is clipped (never half-painted).
     */
    public List<StyledText> wrapLines(StyledText text, int cols) {
        List<StyledText> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        if (cols <= 0) {
            return lines;
        }
        StyledText current = StyledText.empty();
        int used = 0;
        boolean softWrapped = false;
        for (StyledText.Span span : text.spans()) {
            for (String raw : TextMeasure.graphemes(span.text())) {
                if ("\n".equals(raw)) {
                    lines.add(current);
                    current = StyledText.empty();
                    used = 0;
                    softWrapped = false;
                    continue;
                }
                if (" ".equals(raw) && softWrapped) {
                    continue;
                }
                if ("\t".equals(raw)) {
                    int stop = 4 - (used % 4);
                    if (used + stop > cols && used > 0) {
                        lines.add(current);
                        current = StyledText.empty();
                        used = 0;
                        stop = 4;
                    }
                    // A stop wider than an empty line has nowhere to wrap to, so it is clamped to
                    // the columns it can occupy; the painted line clips the same cells.
                    stop = Math.min(stop, cols - used);
                    current = current.append(StyledText.of(" ".repeat(stop), span.style(), span.link()));
                    used += stop;
                    continue;
                }
                String grapheme = withBase(raw);
                int width = TextMeasure.clusterColumns(grapheme, policy);
                if (used + width > cols) {
                    if (used > 0) {
                        lines.add(current);
                        current = StyledText.empty();
                        used = 0;
                        softWrapped = true;
                        if (" ".equals(raw)) {
                            continue;
                        }
                    }
                    if (width > cols) {
                        continue;
                    }
                }
                current = current.append(StyledText.of(grapheme, span.style(), span.link()));
                used += width;
                softWrapped = false;
            }
        }
        lines.add(current);
        return lines;
    }
}
