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

package io.github.dhoard.braxton.style;

import io.github.dhoard.braxton.internal.text.Graphemes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Wraps styled text into the lines a region of a given width can hold.
 *
 * <p>The columns a value occupies are the columns of its plain text, so a style boundary never
 * moves a cell: a span that splits one cluster of the plain text - a base and its mark, a character
 * and the selector that chooses its wide or text form - keeps that cluster in the cell it belongs
 * to, and a cell moves to the next line as a whole rather than being half painted at the right
 * edge. The engine wraps each widget's text through {@link #wrap} so that a value measures where it
 * paints.</p>
 */
public final class TextWrap {

    private TextWrap() {}

    /**
     * True when {@code cluster} is part of the same cluster as the plain text of {@code cell}. A
     * style boundary can split one cluster - a base and its mark, a character and the selector that
     * chooses its wide or text form - but the columns a value is measured at are the columns of its
     * plain text, so only a cluster the measurement reads as one may join the cell before it.
     *
     * @param cell the display cell
     * @param cluster the complete grapheme cluster
     * @return whether appending {@code cluster} continues the cell's grapheme
     */
    public static boolean continuesCluster(CharSequence cell, String cluster) {
        // The overwhelmingly common cell and cluster are a single printable ASCII character each.
        // No grapheme break rule joins two characters in that range - none of them is a carriage
        // return, line feed, extend, format, spacing mark, regional indicator, hangul element or
        // pictograph - so the gap between them is a boundary whatever the rest of the text holds,
        // and the answer needs neither a copy of the cell nor a segmentation of it. The same fact
        // is what TextMeasure answers caret questions with; see TextMeasureBoundaryCostTest.
        if (cluster.length() == 1 && cell.length() > 0) {
            char before = cell.charAt(cell.length() - 1);
            char after = cluster.charAt(0);
            if (TextMeasure.isPrintableAscii(before) && TextMeasure.isPrintableAscii(after)) {
                return false;
            }
        }
        return TextMeasure.graphemeCount(cell.toString() + cluster) == 1;
    }

    /**
     * True when a cluster holds no base character of its own - only combining marks and the format
     * characters that join them. Such a cluster measures a column of its own, so where no cell
     * before it continues it, {@link #wrap} draws the dotted circle in place of the missing
     * base.
     *
     * @param grapheme the complete grapheme cluster
     * @return whether the cluster contains marks or format characters without a base
     */
    public static boolean hasNoBase(String grapheme) {
        boolean mark = false;
        for (int offset = 0; offset < grapheme.length(); ) {
            int codePoint = grapheme.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
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
     *
     * @param text the text to process
     * @param policy the text width policy
     * @param cols the number of columns
     * @return the wrapped rows, bounded by the supplied geometry
     */
    public static List<StyledText> wrap(StyledText text, WidthPolicy policy, int cols) {
        return wrap(text, policy, cols, Integer.MAX_VALUE);
    }

    /**
     * Wraps styled text to {@code cols} columns and stops after {@code maxLines} lines.
     *
     * <p>The columns are the columns of the plain text under {@code policy}, so a value measures
     * the same at every wrap and style boundary: a span that splits one cluster of the plain text
     * keeps that cluster in one cell, and the clusters that continue a cell travel with it.</p>
     *
     * @param text the value to wrap; null wraps to no lines
     * @param policy how a cluster nobody has assigned a width is measured
     * @param cols the width to wrap to; zero or less wraps to no lines
     * @param maxLines the greatest number of lines to produce; zero or less produces none
     * @return the lines, each a styled value the width of {@code cols} or less
     */
    public static List<StyledText> wrap(StyledText text, WidthPolicy policy, int cols, int maxLines) {
        return wrapBy(text, policy == null ? WidthPolicy.PINNED : policy, cols, maxLines);
    }

    /**
     * Wraps text with a width policy that is known to be there.
     */
    private static List<StyledText> wrapBy(StyledText text, WidthPolicy policy, int cols, int maxLines) {
        List<StyledText> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        if (cols <= 0 || maxLines <= 0) {
            return lines;
        }
        if (text.spans().size() == 1 && simple(text.spans().getFirst().text())) {
            return wrapSimple(text, cols, maxLines);
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
            if (" ".equals(raw) && softWrapped && !cursor.gapSplitsCluster()) {
                // The blank a soft break leaves belongs at the end of the previous line, so the
                // space that opens the new one is dropped - but only when it is a cluster of the
                // value's plain text. A span boundary can cut one cluster of that plain text in
                // two, and then this space is its base: dropping it left the tail - a combining
                // mark, a variation selector, a joiner - with no base at all, so the row lost the
                // cell the plain text measures and gained a dotted circle in its place.
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
                int widened = widenedByClusters(policy, " ", joins);
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
            String cellStart = circle.isEmpty() ? (prefix.isEmpty() ? raw : prefix + raw) : circle + prefix + raw;
            String cellPlain = cellStart;
            int cellWidth = baseless ? TextMeasure.clusterColumns(cellPlain, policy) : width;
            List<Piece> pieces = null;
            if (attached != null) {
                // Each attached cluster keeps the style and link of the span it arrived in, and the
                // dotted circle is written where the whole cluster starts.
                pieces = new ArrayList<>(attached.size() + 2);
                for (int i = 0; i < attached.size(); i++) {
                    Piece piece = attached.get(i);
                    pieces.add(new Piece(i == 0 ? circle + piece.text() : piece.text(), piece.style(), piece.link()));
                }
                pieces.add(new Piece(raw, style, link));
            }
            for (String following = cursor.peek(); following != null; following = cursor.peek()) {
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

    /**
     * These characters each form one one-column cluster, with LF a hard row break.
     */
    private static boolean simple(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && !TextMeasure.isPrintableAscii(c) && !(c >= '\u2500' && c <= '\u257f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wrap a single uniform run without segmenting and rebuilding every character.
     */
    private static List<StyledText> wrapSimple(StyledText text, int cols, int maxLines) {
        StyledText.Span span = text.spans().getFirst();
        String value = span.text();
        List<StyledText> lines = new ArrayList<>();
        int start = 0;
        int used = 0;
        boolean soft = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                lines.add(StyledText.of(value.substring(start, i), span.style(), span.link()));
                if (lines.size() == maxLines) return lines;
                start = i + 1;
                used = 0;
                soft = false;
            } else {
                if (soft && c == ' ') {
                    start = i + 1;
                    continue;
                }
                if (used == cols) {
                    lines.add(StyledText.of(value.substring(start, i), span.style(), span.link()));
                    if (lines.size() == maxLines) return lines;
                    start = i;
                    used = 0;
                    soft = true;
                    if (c == ' ') {
                        start = i + 1;
                        continue;
                    }
                }
                used++;
                soft = false;
            }
        }
        lines.add(start == 0 ? text : StyledText.of(value.substring(start), span.style(), span.link()));
        return lines;
    }

    /**
     * One span-sized piece of a cell that a style boundary split into several pieces.
     */
    private record Piece(String text, Style style, String link) {}

    /**
     * Writes a run of invisible clusters to the line it was read on; it continues no cell.
     */
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
    private static int widenedByClusters(WidthPolicy policy, String cell, List<String> following) {
        String grown = cell;
        for (String cluster : following) {
            grown += cluster;
        }
        return Math.max(0, TextMeasure.clusterColumns(grown, policy) - TextMeasure.clusterColumns(cell, policy));
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
        // back. Neighbors inside one span were already split by the break iterator that segmented
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
        /**
         * Clusters {@link #clustersContinuing} read ahead of the current one, in order.
         */
        private List<Piece> buffered;

        private int bufferedIndex;
        private String text;
        private Style style = Style.NONE;
        private String link;

        ClusterCursor(StyledText value, WidthPolicy policy) {
            this.spans = value.spans();
            this.policy = policy;
        }

        /**
         * Consumes the next cluster; false once the value is spent.
         */
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

        /**
         * The cluster after the current one; null once the value is spent. Does not consume it.
         */
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
                if (TextMeasure.clusterColumns(ahead, policy) > 0 || !continuesCluster(grown, ahead)) {
                    break;
                }
                String head = ahead;
                Style headStyle = aheadStyle;
                String headLink = aheadLink;
                hasAhead = false;
                // A candidate that claims no column and continues the cell is only a cluster of the
                // value's plain text when the cluster after it does not continue it. A span boundary
                // can cut one cluster of the plain text in two, and the tail of that cluster is the
                // character that carries its columns: taking the head alone made the stop's last
                // space two columns wide and left the tail to open a row of its own, so the same
                // plain text painted different cells depending on how it was styled. Such a head
                // travels with its cluster instead, exactly as the same text in one span does.
                if (splitsAColumnCarryingCluster(head)) {
                    if (chain == null) {
                        buffered = new ArrayList<>(2);
                        bufferedIndex = 0;
                    }
                    // Everything the scan took, the head, and the tail that continues it go back in
                    // the value's own order for the loop that places cells; the stop keeps only its
                    // own columns.
                    buffered.add(new Piece(head, headStyle, headLink));
                    if (hasAhead) {
                        buffered.add(new Piece(ahead, aheadStyle, aheadLink));
                        hasAhead = false;
                    }
                    return List.of();
                }
                if (chain == null) {
                    chain = new ArrayList<>(2);
                    if (buffered == null) {
                        buffered = new ArrayList<>(2);
                    } else {
                        // The buffer is empty here: everything it held was delivered before the
                        // current cluster, which is the tab this scan follows.
                        buffered.clear();
                        bufferedIndex = 0;
                    }
                }
                chain.add(head);
                buffered.add(new Piece(head, headStyle, headLink));
                grown += head;
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
         * True when {@code head} is only the head of a cluster the value's plain text reads as one:
         * the cluster after it continues it, and the pair claims columns of its own. Reading that
         * cluster is what makes it available to the caller either way, so the head and the tail are
         * delivered in the value's own order whether or not the scan takes them.
         */
        private boolean splitsAColumnCarryingCluster(String head) {
            return (hasAhead || fetchFromSource())
                    && continuesCluster(head, ahead)
                    && TextMeasure.clusterColumns(head + ahead, policy) > 0;
        }

        /**
         * True when the cluster {@link #peek()} returns continues the one the cursor holds, so the
         * two are a single cluster of the value's plain text and no rule may act on the first of
         * them alone. Neighbors inside one span were already split by the segmentation that
         * produced them, which is the question {@link #gapIsSegmented()} answers; a span's first
         * cluster is the exception, because that span can start inside a cluster the whole value
         * reads as one.
         */
        boolean gapSplitsCluster() {
            String following = peek();
            if (following == null || gapIsSegmented()) {
                return false;
            }
            return continuesCluster(text, following);
        }

        /**
         * True when the gap between the current cluster and the one {@link #peek()} returns is one
         * the break iterator already segmented: neighbors in the same span, and the current cluster
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

        /**
         * Reads the next cluster of the value's spans, ignoring anything a scan read ahead.
         */
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

    /**
     * Accumulates a complete styled run before creating an immutable span.
     */
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
