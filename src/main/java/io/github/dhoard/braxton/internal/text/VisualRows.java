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

package io.github.dhoard.braxton.internal.text;

import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * The visual-row projection: how one logical line's clusters break into painted rows of a given
 * width, and the number of cells one cluster occupies on one of those rows.
 *
 * <p>A widget that wraps its own content needs the same answer twice. It needs to know where the
 * rows end so it can paint them one per row, and it needs to know which cells its caret occupies so
 * a vertical move can preserve the painted column. Both answers come from here, so a widget that
 * projects its rows cannot disagree with itself about a tab stop, a wide glyph, or a masked cell.</p>
 *
 * <p>Offsets are document offsets; wrapping is derived and never inserted into the text. A row is
 * the half-open document range it shows, so a row that shows nothing - the row of an empty line -
 * has {@code start == end}.</p>
 *
 * <p>The projection is the counterpart of the layout engine's own wrapping for the case a widget
 * wraps first: text the widget already broke into rows that fit is not wrapped again when the
 * layout paints it, because each row it emits is already narrower than the space it is given.</p>
 */
public final class VisualRows {

    /**
     * Columns between tab stops, matching the layout engine's text wrapping.
     */
    public static final int TAB_WIDTH = 4;

    private VisualRows() {}

    /**
     * How many cells one cluster occupies when it is painted.
     */
    public enum Cells {
        /**
         * The cluster's own columns: a tab reaches its stop and a wide glyph its width.
         */
        NATURAL,
        /**
         * One cell per cluster, because the row paints one mask glyph instead of the cleartext.
         */
        MASKED,
        /**
         * No cell at all: a hidden row paints no glyph before its caret.
         */
        HIDDEN
    }

    /**
     * Where a row breaks when the next cluster does not fit.
     */
    public enum Wrap {
        /**
         * At the next cluster boundary, so a word is cut wherever the row ends.
         */
        CELL,
        /**
         * At the last blank the row painted, so a word moves to the next row whole. A word that is
         * wider than a whole row has no blank to break at and falls back to a cell break, which is
         * what keeps a long token - a path, a URL - from overflowing the row it starts on.
         */
        WORD
    }

    /**
     * How a field marks the caret in the row it shows.
     */
    public enum CaretMark {
        /**
         * The field shows no caret, so nothing is reserved for one.
         */
        NONE,
        /**
         * A declared cursor sits on the caret's cell, which is a cell of its own at the row's end.
         */
        DECLARED,
        /**
         * A caret block replaces the cluster under it, so it is painted into the row.
         */
        BLOCK
    }

    /**
     * The cell a painted run leaves open to the cluster behind it.
     *
     * <p>A cluster that claims no column of its own - a bare combining mark, a variation selector -
     * joins the cell before it instead of starting one, and can widen or narrow that cell. The
     * {@code cell} is the text of the cell the run ends with, empty when the run ends on no cell at
     * all; {@code tabStop} marks the space a tab reached, which only a cluster claiming no column of
     * its own joins.</p>
     *
     * @param cell the text of the cell the run ends with, or empty when it leaves none open
     * @param tabStop whether that cell is the space a tab stop ended with
     */
    public record Open(String cell, boolean tabStop) {

        /**
         * Creates a {@code Open} value.
         *
         * @param cell the text of the cell the run ends with, or empty when it leaves none open
         * @param tabStop whether that cell is the space a tab stop ended with
         */
        public Open {
            cell = cell == null ? "" : cell;
        }

        /**
         * Whether the run ends with no cell for the next cluster to continue.
         *
         * @return {@code true} if the run ends with no cell for the next cluster to continue; otherwise {@code false}
         */
        public boolean closed() {
            return cell.isEmpty();
        }
    }

    /**
     * The state of a run that ends with no open cell.
     */
    public static final Open CLOSED = new Open("", false);

    /**
     * One painted row of one logical line.
     *
     * @param start the first document offset the row shows
     * @param end the offset just past the last one it shows
     * @param columns the cells the row occupies
     */
    public record Row(int start, int end, int columns) {}

    /**
     * The part of one row a fixed-width field shows, scrolled to keep the caret visible.
     *
     * <p>A field that paints one row cannot wrap, so content wider than the field is not broken
     * into another row: the row is scrolled horizontally instead. The window is the range of the
     * row that is painted, the columns scrolled off to its left, and the cell the caret is at.</p>
     *
     * <p>{@code columns} counts the cells the row would occupy unscrolled, and it includes the
     * cell a painted caret adds when it sits at the end of the text, which is what lets the window
     * reach the caret without painting one cell too many.</p>
     *
     * @param start the first document offset the window paints
     * @param end the offset just past the last one it paints
     * @param scroll the columns scrolled off the row's left edge, so a painted cluster sits at
     *     {@code itsColumn - scroll}
     * @param caretColumn the caret's cell, counted from the row's own first column
     * @param columns the cells the row occupies, caret included
     */
    public record Window(int start, int end, int scroll, int caretColumn, int columns) {}

    /**
     * Cells one cluster occupies when it is painted after {@code used} cells of its row.
     *
     * @param cluster the cluster, as {@link TextMeasure#graphemes} segments it
     * @param used the columns painted before it on this row, counted from the row's own start
     * @param mode how the row paints its clusters
     * @param open the cell the run before it leaves open
     * @return the cells it occupies
     */
    public static int cells(String cluster, int used, Cells mode, Open open) {
        return switch (mode) {
            case HIDDEN -> 0;
            case MASKED -> 1;
            case NATURAL -> {
                if (!open.closed()
                        && TextMeasure.graphemeCount(open.cell() + cluster) == 1
                        && (!open.tabStop() || TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED) == 0)) {
                    // The cluster continues the cell the row already painted, so it costs the
                    // columns that cell gains or loses. A bare combining mark adds no column this
                    // way, and a lone variation selector adds the column it gives the base it
                    // joins. Where the cell is the space a tab reached, only the clusters that claim
                    // no column join it.
                    yield TextMeasure.clusterColumns(open.cell() + cluster, WidthPolicy.PINNED)
                            - TextMeasure.clusterColumns(open.cell(), WidthPolicy.PINNED);
                }
                yield visualWidth(cluster, used);
            }
        };
    }

    /**
     * The cell the row leaves open to the cluster behind {@code cluster}.
     *
     * <p>A tab reaches its stop as spaces, and those spaces are the row's own plain text, so it
     * re-segments the stop's last space with the clusters behind the tab. Every other cluster is a
     * whole cluster of the row's text, so nothing follows it into its cell. A masked row paints one
     * glyph per cluster, and a hidden row paints none, so neither leaves a joinable cell.</p>
     *
     * @param cluster the cluster just painted
     * @param mode how the row paints its clusters
     * @return the cell left open
     */
    public static Open openBehind(String cluster, Cells mode) {
        if (mode == Cells.NATURAL && "\t".equals(cluster)) {
            // No columns of its own: the stop's columns were already charged to the tab.
            return new Open(" ", true);
        }
        return CLOSED;
    }

    /**
     * Cells the painted caret block occupies in place of one cluster.
     *
     * <p>The block is one glyph per cell, at least one: it is never narrower than the cell a block
     * always covers, and it never joins the cell before it, so a bare mark under the caret is drawn
     * as a block rather than folded into its base.</p>
     *
     * @param cluster the cluster the block replaces
     * @param used the columns painted before it on this row
     * @param mode how the row paints its clusters
     * @return the cells the block occupies
     */
    public static int blockCells(String cluster, int used, Cells mode) {
        return Math.max(1, cells(cluster, used, mode, CLOSED));
    }

    /**
     * Columns one cluster occupies after {@code used} columns of its row, ignoring any cell it
     * could join.
     *
     * <p>A tab advances to the next four-column stop, exactly as the layout engine paints text; a
     * masked or hidden row never reaches this rule, because it has no tab of its own to draw.</p>
     *
     * @param cluster the cluster
     * @param used the columns painted before it on this row
     * @return the columns it occupies
     */
    public static int visualWidth(String cluster, int used) {
        if ("\t".equals(cluster)) {
            return TAB_WIDTH - (used % TAB_WIDTH);
        }
        return TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
    }

    /**
     * The offset one painted cell of a row falls on: the inverse of {@link #cells}.
     *
     * <p>It walks the row's clusters charging each the cells the row charged it and stops before the
     * first cluster that would reach past the cell, so the answer is always a cluster boundary and a
     * click can never land inside a grapheme. A cell before the row's first cluster is its start; a
     * cell past its last cluster is its end.</p>
     *
     * @param text the document the row is a range of
     * @param start the first offset of the row
     * @param end the offset just past the row's last one
     * @param column the painted cell, counted from {@code firstColumn}
     * @param firstColumn the column the row starts at on screen, so a tab reaches its painted stop
     * @param mode how the row paints its clusters
     * @param firstOpen the cell the row's prefix leaves open to its first cluster
     * @return the document offset the cell falls on
     */
    public static int indexAt(
            String text, int start, int end, int column, int firstColumn, Cells mode, Open firstOpen) {
        String value = text == null ? "" : text;
        int position = Math.max(0, Math.min(start, value.length()));
        int limit = Math.max(position, Math.min(end, value.length()));
        int used = firstColumn;
        Open open = firstOpen == null ? CLOSED : firstOpen;
        for (String cluster : Graphemes.iterate(value.substring(position, limit))) {
            int width = cells(cluster, used, mode, open);
            open = openBehind(cluster, mode);
            if (used + width > column) {
                break;
            }
            used += width;
            position += cluster.length();
        }
        return position;
    }

    /**
     * Projects one row into the window a field of {@code width} cells shows of it.
     *
     * <p>The window keeps the caret visible: it scrolls as little as it can while the caret's own
     * cells stay inside it, and never past either end of the row. It starts on a cluster, so a
     * wide glyph is never painted as half of itself, and it ends before the first cluster that
     * would not fit whole. A width of zero shows nothing, and a row that fits is shown from its
     * start with nothing scrolled off.</p>
     *
     * <p>The cells are charged exactly as {@link #project} charges them, so a field that scrolls
     * measures and paints a tab stop, a wide glyph and a masked cell the same way a field that
     * wraps does. {@code firstOpen} and {@code caretOffset} mean what they mean there: the cell the
     * prefix leaves open to the row's first cluster, and the offset a painted caret block replaces,
     * or -1 for none.</p>
     *
     * @param text the row's text
     * @param firstColumn the column the row starts at on screen, so a tab reaches its painted stop
     * @param width the cells the window may show; a negative value is treated as zero
     * @param mode how the row paints its clusters
     * @param firstOpen the cell the prefix leaves open to the row's first cluster
     * @param caretOffset the caret's offset, or a value past the end for a caret at the end
     * @param caretMark how the field marks the caret, which decides what is reserved for it
     * @return the window
     */
    public static Window window(
            String text, int firstColumn, int width, Cells mode, Open firstOpen, int caretOffset, CaretMark caretMark) {
        String value = text == null ? "" : text;
        CaretMark mark = caretMark == null ? CaretMark.NONE : caretMark;
        boolean caretBlock = mark == CaretMark.BLOCK;
        int available = Math.max(0, width);
        int caret = Math.max(0, Math.min(caretOffset, value.length()));
        Open open = firstOpen == null ? CLOSED : firstOpen;

        // First pass: what a painted caret charges, and how wide the row is. Both the caret's cell
        // and the row's width come from the same walk the painting pass will take, so a block over
        // a two-column glyph reserves the two cells it really covers.
        boolean caretSeen = false;
        int caretColumn = 0;
        int caretCells = 1;
        int used = firstColumn;
        Open held = open;
        int position = 0;
        for (String cluster : Graphemes.iterate(value)) {
            boolean here = position == caret && caretBlock;
            int cells = here ? blockCells(cluster, used, mode) : cells(cluster, used, mode, held);
            if (here) {
                caretColumn = used - firstColumn;
                caretCells = Math.max(1, cells);
                caretSeen = true;
            }
            used += cells;
            held = here ? CLOSED : openBehind(cluster, mode);
            position += cluster.length();
        }
        // A caret past the last cluster is a cell of its own - the block painted there or the cell
        // a declared cursor sits on - so the row is one cell wider than its text.
        if (!caretSeen) {
            caretColumn = used - firstColumn;
            caretCells = 1;
        }
        int trailing = mark == CaretMark.NONE || caretSeen ? 0 : 1;
        int columns = used - firstColumn + trailing;
        int reserved = Math.min(caretCells, available);
        int scrolled = available == 0
                ? 0
                : Math.max(0, Math.min(caretColumn - (available - reserved), Math.max(0, columns - available)));

        // Second pass: the painted range. The window opens on the first cluster that starts at or
        // after the scrolled column - a wide glyph that straddles that column is skipped rather than
        // cut in half, and the caret stays visible because the scroll was chosen to reach it - and
        // closes before the first cluster that does not fit.
        int start = value.length();
        int end = value.length();
        int origin = -1;
        used = firstColumn;
        held = open;
        position = 0;
        for (String cluster : Graphemes.iterate(value)) {
            boolean here = position == caret && caretBlock;
            int cells = here ? blockCells(cluster, used, mode) : cells(cluster, used, mode, held);
            int from = used - firstColumn;
            if (origin < 0 && cells > 0 && from >= scrolled) {
                origin = from;
                start = position;
            }
            if (origin >= 0) {
                if (from + cells > origin + available) {
                    break;
                }
                end = position + cluster.length();
            }
            used += cells;
            held = here ? CLOSED : openBehind(cluster, mode);
            position += cluster.length();
        }
        return new Window(start, end, Math.max(0, origin), caretColumn, columns);
    }

    /**
     * Projects the part of one row a pane of {@code width} cells shows when it is scrolled to
     * {@code scroll}.
     *
     * <p>This is the no-caret counterpart of {@link #window}: the caller names the scroll instead of
     * deriving it from a caret. The window opens on the first cluster that starts at or after the
     * scrolled column - a wide glyph straddling that column is skipped rather than painted as half
     * of itself - and closes before the first cluster that would not fit, so a pan can never split a
     * grapheme or claim a cell it does not show.</p>
     *
     * <p>The cells are charged exactly as {@link #project} charges them: a tab reaches its next
     * four-column stop, a wide cluster costs its columns, and a bare combining mark joins the cell
     * before it. A width of zero shows nothing, and a scrolled column past the row's end leaves an
     * empty window at the end of the row.</p>
     *
     * @param text the row's text
     * @param firstColumn the column the row starts at on screen, so a tab reaches its painted stop
     * @param width the cells the window may show; a negative value is treated as zero
     * @param scroll the columns scrolled off the row's left edge; a negative value is treated as zero
     * @return the window
     */
    public static Window pane(String text, int firstColumn, int width, int scroll) {
        String value = text == null ? "" : text;
        int available = Math.max(0, width);
        int scrolled = Math.max(0, scroll);
        int start = value.length();
        int end = value.length();
        int origin = -1;
        int used = firstColumn;
        Open open = CLOSED;
        int position = 0;
        for (String cluster : Graphemes.iterate(value)) {
            int cells = cells(cluster, used, Cells.NATURAL, open);
            int from = used - firstColumn;
            if (origin < 0 && cells > 0 && from >= scrolled) {
                origin = from;
                start = position;
            }
            if (origin >= 0) {
                if (from + cells > origin + available) {
                    break;
                }
                end = position + cluster.length();
            }
            used += cells;
            open = openBehind(cluster, Cells.NATURAL);
            position += cluster.length();
        }
        int columns = used - firstColumn;
        return new Window(start, end, Math.max(0, origin), scrolled, columns);
    }

    /**
     * Projects one logical line into the rows it paints, wrapped at {@code width} cells.
     *
     * <p>The rules are the layout engine's for text it is given: a blank that opens a row after a
     * soft break stays with the row it broke from, a cluster that does not fit starts the next row,
     * and a tab reaches the next four-column stop measured from the row's own first column. A
     * cluster wider than a whole row keeps a row of its own and is clipped when it is painted, which
     * is what makes a one-column row still name the caret's row.</p>
     *
     * @param text the document
     * @param lineStart the first offset of the logical line
     * @param lineEnd the offset of the line's break, or the end of the document
     * @param firstColumn the column the row starts at on screen, so a tab reaches its painted stop
     * @param width the cells available to one row; a value below one is treated as one
     * @param mode how the row paints its clusters
     * @param firstOpen the cell the first row's prefix leaves open to its first cluster
     * @param caretOffset the offset a painted caret block replaces, or -1 for none
     * @return the rows, at least one
     */
    public static List<Row> project(
            String text,
            int lineStart,
            int lineEnd,
            int firstColumn,
            int width,
            Cells mode,
            Open firstOpen,
            int caretOffset) {
        return project(text, lineStart, lineEnd, firstColumn, width, mode, firstOpen, caretOffset, Wrap.CELL);
    }

    /**
     * Projects one logical line into the rows it paints, breaking at {@code wrap}.
     *
     * <p>{@link Wrap#WORD} moves a word that does not fit to the next row as a whole: the row ends
     * before the run of blanks that separates it from the word, and the run is not painted at all.
     * The offsets the run covers therefore belong to no row - a one-row-per-offset mapping still
     * answers with the row before the break, which is the line the run ended - and no row is ever
     * wider than {@code width}.</p>
     *
     * <p>A caret inside the run keeps the cell break for that row, because dropping the run would
     * drop the caret with it.</p>
     *
     * @param text the document
     * @param lineStart the first offset of the logical line
     * @param lineEnd the offset of the line's break, or the end of the document
     * @param firstColumn the column the row starts at on screen, so a tab reaches its painted stop
     * @param width the cells available to one row; a value below one is treated as one
     * @param mode how the row paints its clusters
     * @param firstOpen the cell the first row's prefix leaves open to its first cluster
     * @param caretOffset the offset a painted caret block replaces, or -1 for none
     * @param wrap where a row breaks when the next cluster does not fit; null is {@link Wrap#CELL}
     * @return the rows, at least one
     */
    public static List<Row> project(
            String text,
            int lineStart,
            int lineEnd,
            int firstColumn,
            int width,
            Cells mode,
            Open firstOpen,
            int caretOffset,
            Wrap wrap) {
        int start = Math.max(0, Math.min(lineStart, text.length()));
        int end = Math.max(start, Math.min(lineEnd, text.length()));
        int limit = firstColumn + Math.max(1, width);
        Wrap policy = wrap == null ? Wrap.CELL : wrap;
        List<String> clusters = new ArrayList<>();
        for (String cluster : Graphemes.iterate(text.substring(start, end))) {
            clusters.add(cluster);
        }
        List<Row> rows = new ArrayList<>();
        int rowStart = start;
        int used = firstColumn;
        Open open = firstOpen;
        int position = start;
        // The blank run painted last on this row: where it starts, and the columns before it. A word
        // break ends the row there, so the run separates the rows without being painted.
        int runIndex = -1;
        int runOffset = -1;
        int runColumns = 0;
        int index = 0;
        while (index < clusters.size()) {
            String cluster = clusters.get(index);
            boolean caret = position == caretOffset;
            int cells = caret ? blockCells(cluster, used, mode) : cells(cluster, used, mode, open);
            if (mode == Cells.NATURAL && "\t".equals(cluster)) {
                // A stop that would reach past a whole row is clamped to the cells the row has: the
                // row is painted with the spaces it can hold rather than the column the tab names.
                cells = Math.max(1, Math.min(cells, limit - used));
            }
            boolean blank = !caret && mode == Cells.NATURAL && " ".equals(cluster);
            if (used > firstColumn && used + cells > limit) {
                if (policy == Wrap.WORD) {
                    // The row ends before the blanks that separate it from the cluster that did not
                    // fit: the last run it painted when that cluster is a word, or the run that
                    // starts at this cluster when the blank itself is what does not fit.
                    // A blank that does not fit is part of a run: the run as a whole is what
                    // separates the rows, so the whole run is dropped rather than the blanks
                    // painted so far being kept and the rest skipped.
                    boolean wholeRun = runIndex >= 0 && runOffset > rowStart;
                    int breakAt = blank ? (wholeRun ? runOffset : position) : runOffset;
                    int breakColumns = blank ? (wholeRun ? runColumns : used - firstColumn) : runColumns;
                    int from = blank ? (wholeRun ? runIndex : index) : runIndex;
                    if (breakAt > rowStart) {
                        int afterRun = breakAt;
                        int next = from;
                        while (next < clusters.size() && " ".equals(clusters.get(next))) {
                            afterRun += clusters.get(next).length();
                            next++;
                        }
                        boolean caretKept = caretOffset >= breakAt && caretOffset <= afterRun;
                        if (!caretKept) {
                            rows.add(new Row(rowStart, breakAt, breakColumns));
                            rowStart = afterRun;
                            position = afterRun;
                            index = next;
                            used = firstColumn;
                            open = firstOpen;
                            runIndex = -1;
                            runOffset = -1;
                            runColumns = 0;
                            continue;
                        }
                    }
                }
                // The blank a soft break leaves belongs at the end of the row it broke from: the
                // offset the caret names is that row's, and the new row starts behind it. A block
                // is never dropped that way - it is the caret, so it opens the row it moves to.
                int breakEnd = blank ? position + cluster.length() : position;
                rows.add(new Row(rowStart, breakEnd, used - firstColumn));
                rowStart = breakEnd;
                used = firstColumn;
                open = firstOpen;
                runIndex = -1;
                runOffset = -1;
                runColumns = 0;
                if (blank) {
                    position = breakEnd;
                    index++;
                    continue;
                }
                cells = caret ? blockCells(cluster, used, mode) : cells(cluster, used, mode, open);
                if (mode == Cells.NATURAL && "\t".equals(cluster)) {
                    cells = Math.max(1, Math.min(cells, limit - used));
                }
            }
            int columnsBefore = used - firstColumn;
            used += cells;
            // A block replaces the cluster and joins nothing, so the cell before it cannot continue
            // into the next cluster either.
            open = caret ? CLOSED : openBehind(cluster, mode);
            if (mode == Cells.NATURAL && !caret && " ".equals(cluster)) {
                if (runIndex < 0) {
                    runIndex = index;
                    runOffset = position;
                    runColumns = columnsBefore;
                }
            }
            position += cluster.length();
            index++;
        }
        rows.add(new Row(rowStart, position, used - firstColumn));
        return rows;
    }
}
