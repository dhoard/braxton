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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A pager over logical lines, with wrapping, horizontal panning, gutters, and search highlights.
 *
 * <p>{@link Content} is an immutable snapshot of {@link StyledText} lines. {@link Content#of}
 * splits line breaks. A frame reads only visible lines; geometry queries may index more content.
 * {@link Mode#WRAP} wraps clusters, {@link Mode#WORD_WRAP} prefers word boundaries, and {@link
 * Mode#PAN} shows one horizontal slice per logical line. Tabs, wide glyphs, and combining marks
 * share the same cell mapping. A {@link Gutter} reserves a fixed width from the content area.</p>
 *
 * <p>{@link Model#topLine()} and {@link Model#topRow()} anchor the window to a logical line through
 * reflow; {@link Model#firstColumn()} records horizontal pan. Follow-end re-anchors on content
 * changes and resize. Moving the window releases it; {@link Msg.Bottom} re-arms it when configured.
 * Reaching the bottom by ordinary motion does not arm it.</p>
 *
 * <p>Applications publish UTF-16 {@link Highlight} ranges, widened to grapheme boundaries. {@link
 * #find} provides literal matching. {@link Msg.NextMatch} and {@link Msg.PreviousMatch} wrap
 * through matches and reveal the active one vertically and, in PAN mode, horizontally. New content
 * clears old highlights; applications republish ranges for the new snapshot.</p>
 *
 * <p>The parent owns pane geometry. Configuration provides fallback dimensions; initialization
 * subtracts reserved space from the terminal size. Later allocations arrive through {@link
 * Msg.Resized}; the pane owns no resize subscription. Reserve the reported height with {@code
 * Elements.fixed(model.rows(), pane)} and report changes to that allocation.</p>
 *
 * <p>{@link #defaultKeys()} supplies routing and help. Horizontal gestures are disabled outside PAN
 * mode, and match gestures require highlights. Focus remains application-owned model data. The
 * wheel scrolls the pane under the pointer three rows per step; clicks have no selection action.
 * All interactions are state changes, so semantic output is {@link Never}.</p>
 */
public final class TextViewport
        implements Component<TextViewport.Model, TextViewport.Msg, Never>,
                Interactive<TextViewport.Model, TextViewport.Msg> {

    /**
     * The height a viewport paints when it has no terminal geometry to follow.
     */
    public static final int DEFAULT_ROWS = 10;

    /**
     * The width a viewport wraps to when it has no terminal geometry to follow.
     */
    public static final int DEFAULT_COLUMNS = 80;

    /**
     * What a {@link Gutter#lineNumbers} gutter paints between the number and the row.
     */
    public static final String LINE_NUMBER_SEPARATOR = " \u2502 ";

    /**
     * How the viewport treats a logical line wider than the content column.
     */
    public enum Mode {

        /**
         * A line is broken into as many rows as it needs: there is nothing to scroll sideways.
         */
        WRAP,

        /**
         * A line is broken at blanks where it can be, so a word moves to the next row whole; a word
         * wider than the row itself is still cut at the row's last cell.
         */
        WORD_WRAP,

        /**
         * A line is one row, and the horizontal offset decides which of its cells are shown.
         */
        PAN
    }

    /**
     * The application's logical lines: an immutable, line-indexed snapshot.
     *
     * <p>An implementation must answer the same way for every call while a model holds it, must
     * answer {@link #lineCount()} in constant time and {@link #line(int)} for one index in constant
     * time over an immutable structure, and must treat one instance as one content version. Two
     * snapshots holding equal lines are still two versions, so implementations should keep reference
     * equality (the default): {@link Model} compares content by {@code equals}, and a value-based
     * {@code equals} would make every model comparison proportional to the content it holds. The
     * viewport never mutates a snapshot; replacing one is a new {@link Msg.ContentChanged}.</p>
     *
     * <p>A line is one <em>logical</em> line - the text between two line breaks - and must not
     * contain one; {@link #of(List)} splits any value that still holds a break, so a content built
     * through it is always line-broken. An implementation of its own must keep that rule, because it
     * is what makes a row's logical line and its screen rows one mapping. A line must not be
     * null.</p>
     */
    public interface Content {

        /**
         * The number of logical lines in this snapshot.
         *
         * @return a non-negative line count, answered in constant time
         */
        int lineCount();

        /**
         * The logical line at {@code index}.
         *
         * @param index the line to read, in {@code [0, lineCount())}
         * @return that line, never null, and holding no line break
         */
        StyledText line(int index);

        /**
         * A snapshot with no lines.
         *
         * @return an empty content value
         */
        static Content empty() {
            return Snapshot.EMPTY;
        }

        /**
         * A snapshot of {@code lines}, copied once by reference and split at line breaks.
         *
         * <p>The list is copied so a caller's list cannot change under a model; the lines are
         * application-owned values and are not copied. A value that still contains a line break is
         * split into the logical lines it holds, which is what an application that has one styled
         * document rather than a list of lines wants. For zero-copy publishing implement
         * {@link Content} directly over an immutable structure of your own.</p>
         *
         * @param lines the logical lines in paint order, or null for empty content
         * @return an immutable snapshot of those lines
         */
        static Content of(List<StyledText> lines) {
            if (lines == null || lines.isEmpty()) {
                return Snapshot.EMPTY;
            }
            List<StyledText> split = new ArrayList<>(lines.size());
            for (StyledText line : lines) {
                Objects.requireNonNull(line, "line");
                split.addAll(line.lines());
            }
            // lines() answers at least one value per input, so the snapshot is never empty here
            // unless the caller passed no lines at all, which is handled above.
            return new Snapshot(split);
        }
    }

    /**
     * Immutable list-backed snapshot. Reference equality is the content-version identity.
     */
    static final class Snapshot implements Content {
        private static final Content EMPTY = new Snapshot(List.of());

        private final List<StyledText> lines;

        private Snapshot(List<StyledText> lines) {
            this.lines = List.copyOf(lines);
        }

        @Override
        public int lineCount() {
            return lines.size();
        }

        @Override
        public StyledText line(int index) {
            return lines.get(index);
        }

        @Override
        public String toString() {
            return "Content[" + lines.size() + " lines]";
        }
    }

    /**
     * One highlighted range of one logical line.
     *
     * <p>Ranges are per line, so no range can span a line break, and they are UTF-16 offsets into
     * the line's own text: the unit an application's matcher already uses. A range must fall on
     * grapheme boundaries, which {@link Msg.Highlights} rejects a violation of, and an empty range
     * ({@code start == end}) paints nothing while still being a match that can be traversed - a
     * zero-width match is a position, not a mistake.</p>
     *
     * <p>Ranges may overlap. The viewport paints them in the order they are published, so a later
     * range's style is merged over an earlier one's, and the active range is painted last.</p>
     *
     * @param line the logical line the range is on
     * @param start the first UTF-16 offset of the range, within the line
     * @param end the offset just past the range's last unit, within the line
     */
    public record Highlight(int line, int start, int end) {

        /**
         * Rejects a range that cannot be a range.
         */
        public Highlight {
            if (line < 0) {
                throw new IllegalArgumentException("highlight line must not be negative");
            }
            if (start < 0) {
                throw new IllegalArgumentException("highlight start must not be negative");
            }
            if (end < start) {
                throw new IllegalArgumentException("highlight end must not precede its start");
            }
        }

        /**
         * The number of UTF-16 units the range covers; zero for a position.
         *
         * @return the number of UTF-16 units the range covers; zero for a position
         */
        public int length() {
            return end - start;
        }
    }

    /**
     * What a {@link Gutter} is told about one painted row.
     *
     * <p>The facts are the mapping the viewport already made: which logical line the row belongs to,
     * which of that line's rows it is, how many rows the line occupies, and how many logical lines
     * the content holds. A gutter therefore never re-wraps or guesses - it names the line a
     * continuation row belongs to and can tell the first row of a line from the rest.</p>
     *
     * @param line the zero-based logical line the row shows
     * @param row the zero-based row of that line
     * @param rows how many rows the logical line occupies
     * @param totalLines how many logical lines the content holds
     */
    public record Facts(int line, int row, int rows, int totalLines) {

        /**
         * Whether this row is the first row of its logical line.
         *
         * @return whether the row opens its line
         */
        public boolean first() {
            return row == 0;
        }
    }

    /**
     * A left gutter of a fixed width: the line numbers, the fold markers or the diff signs a pager
     * paints beside its content.
     *
     * <p>A gutter is a pure function of {@link Facts} and a fixed column budget. The width is
     * declared rather than measured, because the content column must start at the same cell on every
     * row: a gutter's reported text is fitted to that width - truncated on a cluster boundary and
     * padded with spaces - so a renderer that returns too much or too little cannot shift the text it
     * sits beside.</p>
     *
     * @param width the cells the gutter occupies; zero paints no gutter
     * @param renderer the pure renderer of one row's gutter text; required
     */
    public record Gutter(int width, Renderer renderer) {

        /**
         * Rejects an impossible gutter.
         */
        public Gutter {
            if (width < 0) {
                throw new IllegalArgumentException("gutter width must not be negative");
            }
            Objects.requireNonNull(renderer, "renderer");
        }

        /**
         * The pure function a gutter paints one row with.
         */
        @FunctionalInterface
        public interface Renderer {

            /**
             * The gutter text for one painted row.
             *
             * @param facts the row's own facts; never null
             * @return the text to paint, fitted to the gutter's width; null paints blank cells
             */
            StyledText render(Facts facts);
        }

        /**
         * Right-aligned line numbers followed by {@link TextViewport#LINE_NUMBER_SEPARATOR}.
         *
         * <p>A continuation row of a wrapped line paints blanks in place of its number, so the
         * separator stays in one column and the reader can see that the row continues the line
         * above. The width must hold the separator plus the widest number the content can reach; a
         * number that does not fit is truncated like any other gutter text.</p>
         *
         * @param width the cells the gutter occupies, separator included
         * @param style the style the numbers are painted with
         * @return the gutter
         */
        public static Gutter lineNumbers(int width, Style style) {
            int numberWidth = Math.max(0, width - TextMeasure.columns(LINE_NUMBER_SEPARATOR));
            Style painted = style == null ? Style.NONE : style;
            return new Gutter(width, facts -> {
                String number = facts.first() ? Integer.toString(facts.line() + 1) : "";
                return StyledText.of(pad(number, numberWidth) + LINE_NUMBER_SEPARATOR, painted);
            });
        }

        /**
         * Right-aligned line numbers in the default style.
         *
         * @param width the cells the gutter occupies, separator included
         * @return the gutter
         */
        public static Gutter lineNumbers(int width) {
            return lineNumbers(width, Style.NONE);
        }

        /**
         * A number right-aligned in a field of {@code width} cells.
         */
        private static String pad(String number, int width) {
            if (number.length() >= width) {
                return number;
            }
            return " ".repeat(width - number.length()) + number;
        }
    }

    /**
     * One painted row of the window.
     *
     * <p>{@code text} is exactly what the pane paints for this row, gutter excluded and highlight
     * styles applied, so an application can copy, inspect or assert the same cells the terminal
     * receives. The facts are the same ones a {@link Gutter} is told.</p>
     *
     * @param line the zero-based logical line the row shows
     * @param row the zero-based row of that line
     * @param rows how many rows the logical line occupies
     * @param columns the cells {@code text} occupies
     * @param text the row's painted text
     */
    public record Row(int line, int row, int rows, int columns, StyledText text) {

        /**
         * Whether this row is the first row of its logical line.
         *
         * @return whether the row opens its line
         */
        public boolean first() {
            return row == 0;
        }

        /**
         * The same facts a gutter is told.
         *
         * @param totalLines how many logical lines the content holds
         * @return the facts for this row
         */
        public Facts facts(int totalLines) {
            return new Facts(line, row, rows, totalLines);
        }
    }

    /**
     * Immutable definition of a viewport: how it wraps, how tall and wide it starts, and how it
     * paints.
     *
     * @param mode how a line wider than the content column is treated
     * @param followEnd whether this viewport follows appended content: the initial flag, and whether
     *     {@link Msg.Bottom} may arm it again
     * @param rows the height used before the terminal's own geometry arrives
     * @param columns the width used before the terminal's own geometry arrives
     * @param reservedRows the rows the application paints outside the viewport
     * @param reservedColumns the columns the application paints outside the viewport
     * @param gutter the left gutter, or null for none
     * @param styles how highlight ranges are painted
     */
    public record Config(
            Mode mode,
            boolean followEnd,
            int rows,
            int columns,
            int reservedRows,
            int reservedColumns,
            Gutter gutter,
            Styles styles) {

        /**
         * Rejects a geometry that cannot be allocated.
         */
        public Config {
            mode = mode == null ? Mode.WRAP : mode;
            styles = styles == null ? Styles.defaults() : styles;
            TextViewportNavigation.requireNotNegative(rows, "rows");
            TextViewportNavigation.requireNotNegative(columns, "columns");
            TextViewportNavigation.requireNotNegative(reservedRows, "reservedRows");
            TextViewportNavigation.requireNotNegative(reservedColumns, "reservedColumns");
        }

        /**
         * How the viewport paints the ranges an application published.
         *
         * @param highlight the style merged over every highlighted range
         * @param selectedHighlight the style merged over the active range
         */
        public record Styles(Style highlight, Style selectedHighlight) {

            /**
             * Substitutes a plain style for a missing one.
             */
            public Styles {
                highlight = highlight == null ? Style.NONE : highlight;
                selectedHighlight = selectedHighlight == null ? Style.NONE : selectedHighlight;
            }

            /**
             * An underline for matches and reverse video for the active one.
             *
             * <p>Both are SGR attributes rather than colors, so a default pair is legible on any
             * terminal profile the runtime negotiates, including a monochrome one.</p>
             *
             * @return the default styles
             */
            public static Styles defaults() {
                return new Styles(
                        Style.NONE.withUnderline(), Style.NONE.withUnderline().withInvert());
            }

            /**
             * The same styles with a different match style.
             *
             * @param value the style merged over every highlighted range
             * @return the new styles
             */
            public Styles withHighlight(Style value) {
                return new Styles(value, selectedHighlight);
            }

            /**
             * The same styles with a different active-match style.
             *
             * @param value the style merged over the active range
             * @return the new styles
             */
            public Styles withSelectedHighlight(Style value) {
                return new Styles(highlight, value);
            }
        }

        /**
         * A wrapping, following viewport with no gutter.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(Mode.WRAP, true, DEFAULT_ROWS, DEFAULT_COLUMNS, 0, 0, null, Styles.defaults());
        }

        /**
         * The same definition in another mode.
         *
         * @param value the mode
         * @return the new definition
         */
        public Config withMode(Mode value) {
            return new Config(value, followEnd, rows, columns, reservedRows, reservedColumns, gutter, styles);
        }

        /**
         * The same definition with a different follow-end policy.
         *
         * @param value whether the viewport may follow appended content
         * @return the new definition
         */
        public Config withFollowEnd(boolean value) {
            return new Config(mode, value, rows, columns, reservedRows, reservedColumns, gutter, styles);
        }

        /**
         * The same definition with a different fallback height.
         *
         * @param value the height used before the terminal's geometry arrives
         * @return the new definition
         */
        public Config withRows(int value) {
            return new Config(mode, followEnd, value, columns, reservedRows, reservedColumns, gutter, styles);
        }

        /**
         * The same definition with a different fallback width.
         *
         * @param value the width used before the terminal's geometry arrives
         * @return the new definition
         */
        public Config withColumns(int value) {
            return new Config(mode, followEnd, rows, value, reservedRows, reservedColumns, gutter, styles);
        }

        /**
         * The same definition with a different number of reserved rows.
         *
         * @param value the rows the application paints outside the viewport
         * @return the new definition
         */
        public Config withReservedRows(int value) {
            return new Config(mode, followEnd, rows, columns, value, reservedColumns, gutter, styles);
        }

        /**
         * The same definition with a different number of reserved columns.
         *
         * @param value the columns the application paints outside the viewport
         * @return the new definition
         */
        public Config withReservedColumns(int value) {
            return new Config(mode, followEnd, rows, columns, reservedRows, value, gutter, styles);
        }

        /**
         * The same definition with a gutter, or without one.
         *
         * @param value the gutter, or null for none
         * @return the new definition
         */
        public Config withGutter(Gutter value) {
            return new Config(mode, followEnd, rows, columns, reservedRows, reservedColumns, value, styles);
        }

        /**
         * The same definition with different highlight styles.
         *
         * @param value the styles
         * @return the new definition
         */
        public Config withStyles(Styles value) {
            return new Config(mode, followEnd, rows, columns, reservedRows, reservedColumns, gutter, value);
        }

        /**
         * The cells the gutter occupies, zero when there is none.
         */
        int gutterColumns() {
            return gutter == null ? 0 : gutter.width();
        }
    }

    /**
     * Viewport state: where the window starts, how large it is, and what it is over.
     *
     * <p>The anchor is a logical line plus the row within it, so a reflow cannot leave the window
     * pointing at a screen row that no longer exists. The canonical constructor clamps the values
     * that have no valid alternative - a negative size, a negative offset, a negative anchor - and
     * leaves the ones that need the content's own geometry to the moment a projection or a message
     * has it: {@code topRow} is clamped to the rows its line really has, and {@code firstColumn} to
     * the columns the content really has, when the state is next reduced or painted.</p>
     *
     * @param topLine the logical line the window starts on
     * @param topRow the row of {@code topLine} the window starts on
     * @param firstColumn the first cell of the content column the window shows
     * @param followEnd whether the window follows appended content
     * @param focused whether the application has focused this pane
     * @param rows the height of the pane, in rows
     * @param columns the width of the pane, in cells
     * @param content the application's current logical lines
     * @param highlights the ranges the application found, in publication order
     * @param active the index of the active range in {@code highlights}, or -1 for none
     */
    public record Model(
            int topLine,
            int topRow,
            int firstColumn,
            boolean followEnd,
            boolean focused,
            int rows,
            int columns,
            Content content,
            List<Highlight> highlights,
            int active) {

        /**
         * Keeps the state representable and every collection non-null.
         */
        public Model {
            content = content == null ? Content.empty() : content;
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
            topLine = Math.max(0, topLine);
            topRow = Math.max(0, topRow);
            firstColumn = Math.max(0, firstColumn);
            rows = Math.max(0, rows);
            columns = Math.max(0, columns);
            active = active >= 0 && active < highlights.size() ? active : -1;
        }

        /**
         * An empty viewport over some content.
         *
         * @param content the logical lines to show; null is empty content
         * @return the state
         */
        public static Model of(Content content) {
            return new Model(0, 0, 0, true, true, DEFAULT_ROWS, DEFAULT_COLUMNS, content, List.of(), -1);
        }

        /**
         * The same state with a new focus.
         *
         * <p>A reduction never changes focus; the application owns it, exactly as it does for
         * {@link Editor} and {@link ScrollView}.</p>
         *
         * @param value whether the pane owns focus
         * @return this instance when the focus did not change, otherwise the new state
         */
        public Model withFocus(boolean value) {
            return value == focused
                    ? this
                    : new Model(
                            topLine, topRow, firstColumn, followEnd, value, rows, columns, content, highlights, active);
        }

        /**
         * The number of logical lines currently published.
         *
         * @return the line count of {@link #content()}
         */
        public int lineCount() {
            return content.lineCount();
        }

        /**
         * The active highlight range.
         *
         * @return the active range, or empty when there is none
         */
        public Optional<Highlight> activeHighlight() {
            return active < 0 ? Optional.empty() : Optional.of(highlights.get(active));
        }
    }

    /**
     * One scrolling, panning, match or content message.
     */
    public sealed interface Msg {

        /**
         * Moves down by visual rows. The count is never negative, and zero changes nothing.
         *
         * @param rows how many rows to move down
         */
        record Down(int rows) implements Msg {
            /**
             * Creates a {@code Down} value.
             *
             * @param rows how many rows to move down
             */
            public Down {
                rows = Math.max(0, rows);
            }
        }

        /**
         * Moves up by visual rows. The count is never negative, and zero changes nothing.
         *
         * @param rows how many rows to move up
         */
        record Up(int rows) implements Msg {
            /**
             * Creates a {@code Up} value.
             *
             * @param rows how many rows to move up
             */
            public Up {
                rows = Math.max(0, rows);
            }
        }

        /**
         * Moves down by the pane's height: one page is exactly one window, no overlap.
         */
        record PageDown() implements Msg {}

        /**
         * Moves up by the pane's height: one page is exactly one window, no overlap.
         */
        record PageUp() implements Msg {}

        /**
         * Moves down by half the pane's height, rounded down; a one-row pane has no half page.
         */
        record HalfPageDown() implements Msg {}

        /**
         * Moves up by half the pane's height, rounded down; a one-row pane has no half page.
         */
        record HalfPageUp() implements Msg {}

        /**
         * Moves to the first row, releasing follow-end when the window can move at all.
         */
        record Top() implements Msg {}

        /**
         * Moves to the last window, arming follow-end when the configuration allows it.
         */
        record Bottom() implements Msg {}

        /**
         * Pans left by cells: the window moves back toward the content's first cell.
         *
         * <p>The count is never negative, zero changes nothing, and a mode other than
         * {@link Mode#PAN} ignores the gesture entirely because a wrapped line has no horizontal
         * offset to move.</p>
         *
         * @param columns how many cells to scroll back toward the content's first cell
         */
        record Left(int columns) implements Msg {
            /**
             * Creates a {@code Left} value.
             *
             * @param columns how many cells to scroll back toward the content's first cell
             */
            public Left {
                columns = Math.max(0, columns);
            }
        }

        /**
         * Pans right by cells: the window moves toward the widest line's end.
         *
         * <p>The count is never negative, zero changes nothing, and a mode other than
         * {@link Mode#PAN} ignores the gesture entirely because a wrapped line has no horizontal
         * offset to move.</p>
         *
         * @param columns how many more cells to scroll off the content's left edge
         */
        record Right(int columns) implements Msg {
            /**
             * Creates a {@code Right} value.
             *
             * @param columns how many more cells to scroll off the content's left edge
             */
            public Right {
                columns = Math.max(0, columns);
            }
        }

        /**
         * Publishes new logical lines, replacing the previous snapshot and its highlights.
         *
         * <p>A following window re-anchors to the end of the new content; a window the reader moved
         * keeps its logical anchor as far as the new content allows. The highlights belonged to the
         * content version that is being replaced, so they are cleared: painting old offsets over new
         * text would mark the wrong cells.</p>
         *
         * @param content the new logical lines
         */
        record ContentChanged(Content content) implements Msg {
            /**
             * Creates a {@code ContentChanged} value.
             *
             * @param content the new logical lines
             */
            public ContentChanged {
                content = content == null ? Content.empty() : content;
            }
        }

        /**
         * Reports the geometry the parent allocated to this pane.
         *
         * <p>A following window re-anchors to the end at the new size; a window the reader moved
         * keeps its logical anchor and is clamped only where the new geometry leaves it outside the
         * content. Negative values become zero, which paints and scrolls nothing.</p>
         *
         * @param rows the height the parent allocated
         * @param columns the width the parent allocated
         */
        record Resized(int rows, int columns) implements Msg {
            /**
             * Creates a {@code Resized} value.
             *
             * @param rows the height the parent allocated
             * @param columns the width the parent allocated
             */
            public Resized {
                rows = Math.max(0, rows);
                columns = Math.max(0, columns);
            }
        }

        /**
         * Publishes the ranges an application found, replacing the previous ones.
         *
         * <p>The ranges must name lines the current content holds, stay inside those lines' text and
         * fall on grapheme boundaries; anything else is rejected with an
         * {@link IllegalArgumentException} where the mistake is made rather than painted as a
         * restyled half-cluster. Publishing ranges clears the active one, because an index into the
         * previous list names nothing in this one; a caller that wants a match selected sends
         * {@link NextMatch} or {@link PreviousMatch} after publishing.</p>
         *
         * @param highlights the ranges, in the order they should be painted
         */
        record Highlights(List<Highlight> highlights) implements Msg {
            /**
             * Creates a {@code Highlights} value.
             *
             * @param highlights the ranges, in the order they should be painted
             */
            public Highlights {
                highlights = highlights == null ? List.of() : List.copyOf(highlights);
            }
        }

        /**
         * Makes the next published range active, wrapping to the first after the last.
         */
        record NextMatch() implements Msg {}

        /**
         * Makes the previous published range active, wrapping to the last before the first.
         */
        record PreviousMatch() implements Msg {}
    }

    /**
     * A reduction result. The pane has no semantic outputs, so the intent is always absent.
     */
    record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    /**
     * The logical position of the window's first row.
     */
    record Anchor(int line, int row) {}

    /**
     * One painted row before its text is cut and styled: where it comes from and what it shows.
     *
     * <p>The line itself is carried along because the window already read it to project it. A frame
     * that read the line again to style it would cost two reads a painted row instead of one.</p>
     */
    record Slot(int line, int row, int rows, int start, int end, StyledText text) {}

    /**
     * The identity of the pane's focus element; a mount scopes it to its own path.
     */
    private final Config config;

    /**
     * Creates the component.
     *
     * @param config the immutable definition; required
     */
    public TextViewport(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The definition this viewport was built from.
     *
     * @return the definition
     */
    public Config config() {
        return config;
    }

    /**
     * The gestures a pager defines, before the state is taken into account.
     *
     * <p>They are the pager's own: rows and pages vertically, cells horizontally, and the two match
     * gestures. {@link #keys(Model, Config)} is the map for one state - it disables the horizontal
     * gestures where there is nothing to pan and the match gestures while there is no match - and it
     * is what the component routes and what help renders.</p>
     *
     * @return the default gestures
     */
    public static KeyMap<Msg> defaultKeys() {
        return TextViewportKeys.defaultKeys();
    }

    /**
     * The gestures one state handles.
     *
     * <p>Help and routing read the same map, so a gesture help shows is a gesture that fires: a
     * wrapping viewport has no horizontal offset to move, and a viewport with no published ranges
     * has no match to step to.</p>
     *
     * @param state the state the gestures are built for; required
     * @param config the definition, which decides whether there is anything to pan; null is
     *     {@link Config#defaults()}
     * @return the gestures
     */
    public static KeyMap<Msg> keys(Model state, Config config) {
        return TextViewportKeys.keys(state, config);
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        // The startup geometry is the only size the pane can know before a resize arrives: what the
        // application paints outside the pane is taken out of it, and the configured fallback is
        // what a headless test or a preview uses.
        int rows = context.viewport().rows() > 0
                ? Math.max(0, context.viewport().rows() - config.reservedRows())
                : config.rows();
        int columns = context.viewport().cols() > 0
                ? Math.max(0, context.viewport().cols() - config.reservedColumns())
                : config.columns();
        return Update.next(new Model(0, 0, 0, config.followEnd(), true, rows, columns, Content.empty(), List.of(), -1));
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model,
                message,
                action -> new Result(TextViewportNavigation.reduce(model, config, action), null),
                state -> state.withFocus(model.focused()));
    }

    /**
     * Describes the window this state covers.
     *
     * <p>The context is deliberately unused: its viewport is the one the pane's parent lays out for,
     * not the pane's own geometry. {@link Model#rows()} and {@link Model#columns()} are what the
     * parent reserved and reported, and they are the only geometry a wrap or a pan may use.</p>
     */
    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The pane's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        TextViewportKeys.keys(model, config)
                .actionFor(key.key())
                .map(KeyAction::message)
                .ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The pane's pointer handler: the wheel scrolls the rows under it.
     *
     * <p>The wheel is what a pane has to answer: a report names the cell the pointer is over, so the
     * pane under the pointer scrolls rather than whichever element happens to hold the keyboard. A
     * pane has no selection of its own, so a click selects nothing and reports nothing - it is left
     * to the application, which sees the key or the report as its own.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!pointer.isWheel()) {
            return;
        }
        emit.accept(
                switch (pointer.button()) {
                    case WHEEL_UP, WHEEL_LEFT -> new Msg.Up(WHEEL_ROWS);
                    default -> new Msg.Down(WHEEL_ROWS);
                });
    }

    /**
     * The painted rows, with the pane itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Row> window = visible(model, config);
        // A gutter is told how many lines the content holds; a pane without one never asks.
        boolean gutters = config.gutter() != null && config.gutter().width() > 0;
        int totalLines = gutters ? model.content().lineCount() : 0;
        List<Element<Msg>> rows = new ArrayList<>(window.size());
        for (Row row : window) {
            // One fixed row per painted row: a row of text wider than the pane would otherwise claim
            // a second screen row and move everything below it out of the window.
            rows.add(Elements.fixed(1, Elements.text(painted(row, totalLines))));
        }
        return Elements.stack(Elements.column(rows), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The rows one wheel notch scrolls, which is the step a terminal reader expects.
     */
    static final int WHEEL_ROWS = 3;

    // ── projections ──────────────────────────────────────────────────────────

    /**
     * The rows the window covers, in paint order.
     *
     * <p>This is what a frame paints, and the only content a frame reads: one line per line the
     * window touches and none for a row it does not show. Every row carries the facts of the line it
     * came from and the text it paints, with the highlight styles already applied and, in
     * {@link Mode#PAN}, the horizontal window already cut.</p>
     *
     * @param state the current state; required
     * @param config the definition whose mode, gutter and styles apply; null is
     *     {@link Config#defaults()}
     * @return the visible rows, empty when the pane or its content is empty
     */
    public static List<Row> visible(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        List<Slot> slots = TextViewportNavigation.slots(state, opts);
        if (slots.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>(slots.size());
        int styledLine = -1;
        StyledText styled = StyledText.empty();
        for (Slot slot : slots) {
            if (slot.line() != styledLine) {
                styledLine = slot.line();
                styled = TextViewportNavigation.highlighted(slot.text(), styledLine, state, opts.styles());
            }
            StyledText text = styled.slice(slot.start(), slot.end());
            rows.add(new Row(slot.line(), slot.row(), slot.rows(), TextMeasure.columns(text.plainText()), text));
        }
        return List.copyOf(rows);
    }

    /**
     * Finds literal matches in logical lines, widening their UTF-16 offsets to grapheme boundaries.
     *
     * <p>Case-insensitive matching uses simple Unicode case folding without changing offsets.</p>
     *
     * @param content the logical lines to search; required
     * @param needle the text to find; null or empty finds nothing
     * @param ignoreCase whether case alone cannot prevent a match
     * @return matches in line and offset order
     */
    public static List<Highlight> find(Content content, String needle, boolean ignoreCase) {
        return TextViewportNavigation.find(content, needle, ignoreCase);
    }

    /**
     * How many visual rows the content holds in this geometry.
     *
     * <p>A wrapped line contributes as many rows as it needs at the content column's width, and a
     * panned line contributes exactly one. This projection walks the whole content, so it is what an
     * application asks for when it wants to know how far there is to go - a scrollbar, a percentage -
     * and not something a frame asks for to paint a window.</p>
     *
     * @param state the state to measure; required
     * @param config the definition whose mode and gutter decide the content width; null is
     *     {@link Config#defaults()}
     * @return the total visual rows, never negative
     */
    public static int totalRows(Model state, Config config) {
        return TextViewportNavigation.totalRows(state, config);
    }

    /**
     * The visual row the window starts on, counted from the content's first row.
     *
     * <p>The state's anchor is logical, so this projection converts it: it walks the lines above the
     * anchor, which is a whole-content projection like {@link #totalRows}.</p>
     *
     * @param state the state to measure; required
     * @param config the definition whose mode and gutter decide the content width; null is
     *     {@link Config#defaults()}
     * @return the first visible visual row, never negative
     */
    public static int firstVisibleRow(Model state, Config config) {
        return TextViewportNavigation.firstVisibleRow(state, config);
    }

    /**
     * How far down the window is, from 0 to 1.
     *
     * <p>It is defined as zero when there is no overflow - content that fits the pane is not "at the
     * end" of anything - and otherwise as the first visible row over the rows the pane cannot show.
     * Both ends are therefore representable: 0 at the first window and 1 at the last. Like
     * {@link #firstVisibleRow} and {@link #totalRows} it walks the content, so an application paints
     * a scrollbar from it and a frame does not.</p>
     *
     * @param state the state to measure; required
     * @param config the definition whose mode and gutter decide the content width; null is
     *     {@link Config#defaults()}
     * @return the vertical fraction scrolled, from 0 to 1
     */
    public static double verticalPercent(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        int total = totalRows(state, opts);
        int height = state.rows();
        if (total <= height) {
            return 0.0;
        }
        return TextViewportNavigation.fraction(firstVisibleRow(state, opts), total - height);
    }

    /**
     * How far right the window is, from 0 to 1.
     *
     * <p>It is defined as zero when there is no horizontal overflow, and it is zero outside
     * {@link Mode#PAN}, where nothing is ever clipped horizontally.</p>
     *
     * @param state the state to measure; required
     * @param config the definition whose mode and gutter decide the content width; null is
     *     {@link Config#defaults()}
     * @return the horizontal fraction scrolled, from 0 to 1
     */
    public static double horizontalPercent(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        if (opts.mode() != Mode.PAN) {
            return 0.0;
        }
        int width = contentColumns(state, opts);
        int widest = TextViewportNavigation.widestColumns(state.content());
        if (widest <= width) {
            return 0.0;
        }
        return TextViewportNavigation.fraction(
                TextViewportNavigation.effectiveFirstColumn(state, opts), widest - width);
    }

    /**
     * The cells the widest logical line occupies.
     *
     * @param state the state to measure; required
     * @return the widest line, in cells, never negative
     */
    public static int widestColumns(Model state) {
        Objects.requireNonNull(state, "state");
        return TextViewportNavigation.widestColumns(state.content());
    }

    /**
     * The cells the pane gives its content: its width less the gutter's.
     *
     * <p>This is the one number wrapping, panning and clipping use. It is what a gutter costs, and
     * so it is also the answer to "how wide is a row before it wraps or needs panning".</p>
     *
     * @param state the state to measure; required
     * @param config the definition whose gutter decides the budget; null is
     *     {@link Config#defaults()}
     * @return the content column's cells, never negative
     */
    public static int contentColumns(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        return Math.max(0, state.columns() - opts.gutterColumns());
    }

    /**
     * One row as the pane paints it: the gutter, fitted to its width, then the row's own text.
     */
    private StyledText painted(Row row, int totalLines) {
        return TextViewportNavigation.painted(row, totalLines, config);
    }
}
