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

import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A pager over the application's logical lines: a viewport that wraps them itself, pans them
 * horizontally and highlights the ranges the application found.
 *
 * <p>{@code TextViewport} is the rich counterpart of {@link ScrollView}. A {@code ScrollView} takes
 * rows the application has already wrapped and moves a window over them; a {@code TextViewport}
 * takes the <em>logical</em> lines themselves, wraps them to the width it is given, and keeps the
 * mapping between those lines and the screen rows its state names. That mapping is what lets a
 * resize reflow the text without losing the reader's place, lets a gutter name the line a wrapped
 * row continues, and lets a search highlight reach the cells it matched.</p>
 *
 * <h2>Content, geometry and the one cell mapping</h2>
 *
 * <p>Content is a {@link Content} snapshot of logical lines: one {@link StyledText} per line, which
 * must not contain a line break ({@link Content#of} splits any value that still holds one). The
 * widget never copies, splits or concatenates a snapshot's lines when it publishes one; a frame
 * reads the lines it paints and no others, so a frame over a document of a million lines costs what
 * a frame over a twenty-line document costs.</p>
 *
 * <p>Every cell decision comes from one place: {@link Mode#WRAP} projects a line into the rows
 * {@link VisualRows} wraps it into, {@link Mode#PAN} projects the one row it occupies into the
 * window the horizontal offset shows, and both charge a tab, a wide glyph and a combining mark the
 * same way. A gutter is a fixed number of cells taken off the left edge ({@link Gutter#width()}), and
 * the content column is exactly the rest, so a row's painted columns never depend on what the gutter
 * happened to render.</p>
 *
 * <h2>Anchor, offsets and follow-end</h2>
 *
 * <p>The state names its position as a logical <em>anchor</em> - {@link Model#topLine()} and the row
 * within that line, {@link Model#topRow()} - plus the first cell the content column shows
 * ({@link Model#firstColumn()}). A window over wrapped text has two offsets, and only the logical
 * one survives a reflow: after a resize, a rotation or a changed gutter the anchor still names the
 * same line, so the reader keeps their place instead of being dropped wherever a screen-row count
 * lands.</p>
 *
 * <p>Follow-end means the last window stays visible while content is appended; unlike
 * {@link ScrollView}, a viewport over logical lines cannot express that as one number, so it is a
 * flag: while it is set, a content change or a resize re-anchors the window to the end of the new
 * content. A move that really changes the window releases it, {@link Msg.Bottom} arms it again when
 * the configuration allows it, and reaching the bottom by hand does not - a reader who stepped down
 * does not silently start following.</p>
 *
 * <h2>Highlights and matches</h2>
 *
 * <p>The application finds matches and publishes them as {@link Highlight} ranges, one per logical
 * line, in UTF-16 offsets widened to grapheme clusters. The viewport styles them with
 * {@link Config.Styles#highlight()} and the active one with
 * {@link Config.Styles#selectedHighlight()}, and {@link Msg.NextMatch}/{@link Msg.PreviousMatch}
 * walk them in order, wrapping at both ends, and bring the active one into view both vertically and,
 * in {@link Mode#PAN}, horizontally. The widget computes no matches of its own: what "match" means
 * is the application's search policy, and the viewport only has to paint and reach them.</p>
 *
 * <p>A content version invalidates the ranges it described, so publishing new content clears them
 * rather than painting the old offsets over new text. The application publishes them again with
 * {@link Msg.Highlights} once it has found the matches in the new content.</p>
 *
 * <h2>Geometry, input and scope</h2>
 *
 * <p>The pane's height and width are model data, and the parent owns them: {@link Config#rows()} and
 * {@link Config#columns()} are the fallback a headless test or a preview uses, startup geometry is
 * the terminal's own size less {@link Config#reservedRows()} and {@link Config#reservedColumns()},
 * and every later change arrives as {@link Msg.Resized}. They are deliberately not
 * {@code ViewContext.viewport()}, because a mounted component receives its parent's context and the
 * root geometry is not this pane's geometry when it sits inside a box, a row or a shared column -
 * and a composed pager splits one terminal between several panes, which only the parent can do. The
 * pane therefore owns no resize source of its own: the parent reserves the height it reported
 * ({@code Views.fixed(model.rows(), pane)}) and tells the pane when that allocation changes.</p>
 *
 * <p>{@link #defaultKeys()} is the definition the component routes and the one {@link Help} renders.
 * The map is built from the state: horizontal gestures are disabled in {@link Mode#WRAP}, where
 * there is nothing to pan, and match gestures are disabled while no ranges are published. Focus is
 * model data, as in {@link ScrollView}: the application owns it and a reduction never changes it.</p>
 *
 * <p>The wheel scrolls the pane under the pointer - the pane the report names, not the one holding
 * the keyboard - three rows at a time. A pane has no selection of its own, so a
 * click on it reports nothing and is left to the application around the mount, which sees every
 * report as an observer.</p>
 *
 * <p>This component has no semantic outputs; it is {@link Never}-typed, like any component whose
 * interactions are all state changes the parent can observe.</p>
 */
public final class TextViewport implements Component<TextViewport.Model, TextViewport.Msg, Never> {

    /** The height a viewport paints when it has no terminal geometry to follow. */
    public static final int DEFAULT_ROWS = 10;

    /** The width a viewport wraps to when it has no terminal geometry to follow. */
    public static final int DEFAULT_COLUMNS = 80;

    /** What a {@link Gutter#lineNumbers} gutter paints between the number and the row. */
    public static final String LINE_NUMBER_SEPARATOR = " \u2502 ";

    /** How the viewport treats a logical line wider than the content column. */
    public enum Mode {

        /** A line is broken into as many rows as it needs: there is nothing to scroll sideways. */
        WRAP,

        /** A line is one row, and the horizontal offset decides which of its cells are shown. */
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

    /** Immutable list-backed snapshot. Reference equality is the content-version identity. */
    private static final class Snapshot implements Content {
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

        /** The number of UTF-16 units the range covers; zero for a position. */
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

        /** The pure function a gutter paints one row with. */
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

        /** A number right-aligned in a field of {@code width} cells. */
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
            requireNotNegative(rows, "rows");
            requireNotNegative(columns, "columns");
            requireNotNegative(reservedRows, "reservedRows");
            requireNotNegative(reservedColumns, "reservedColumns");
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
             * <p>Both are SGR attributes rather than colours, so a default pair is legible on any
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

        /** The cells the gutter occupies, zero when there is none. */
        private int gutterColumns() {
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

    /** One scrolling, panning, match or content message. */
    public sealed interface Msg {

        /**
         * Moves down by visual rows. The count is never negative, and zero changes nothing.
         *
         * @param rows how many rows to move down
         */
        record Down(int rows) implements Msg {
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
            public Up {
                rows = Math.max(0, rows);
            }
        }

        /** Moves down by the pane's height: one page is exactly one window, no overlap. */
        record PageDown() implements Msg {}

        /** Moves up by the pane's height: one page is exactly one window, no overlap. */
        record PageUp() implements Msg {}

        /** Moves down by half the pane's height, rounded down; a one-row pane has no half page. */
        record HalfPageDown() implements Msg {}

        /** Moves up by half the pane's height, rounded down; a one-row pane has no half page. */
        record HalfPageUp() implements Msg {}

        /** Moves to the first row, releasing follow-end when the window can move at all. */
        record Top() implements Msg {}

        /** Moves to the last window, arming follow-end when the configuration allows it. */
        record Bottom() implements Msg {}

        /**
         * Pans left by cells: the window moves back towards the content's first cell.
         *
         * <p>The count is never negative, zero changes nothing, and {@link Mode#WRAP} ignores the
         * gesture entirely because a wrapped line has no horizontal offset to move.</p>
         *
         * @param columns how many cells to scroll back towards the content's first cell
         */
        record Left(int columns) implements Msg {
            public Left {
                columns = Math.max(0, columns);
            }
        }

        /**
         * Pans right by cells: the window moves towards the widest line's end.
         *
         * <p>The count is never negative, zero changes nothing, and {@link Mode#WRAP} ignores the
         * gesture entirely because a wrapped line has no horizontal offset to move.</p>
         *
         * @param columns how many more cells to scroll off the content's left edge
         */
        record Right(int columns) implements Msg {
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
            public Highlights {
                highlights = highlights == null ? List.of() : List.copyOf(highlights);
            }
        }

        /** Makes the next published range active, wrapping to the first after the last. */
        record NextMatch() implements Msg {}

        /** Makes the previous published range active, wrapping to the last before the first. */
        record PreviousMatch() implements Msg {}
    }

    /** A reduction result. The pane has no semantic outputs, so the intent is always absent. */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    /** The logical position of the window's first row. */
    private record Anchor(int line, int row) {}

    /**
     * One painted row before its text is cut and styled: where it comes from and what it shows.
     *
     * <p>The line itself is carried along because the window already read it to project it. A frame
     * that read the line again to style it would cost two reads a painted row instead of one.</p>
     */
    private record Slot(int line, int row, int rows, int start, int end, StyledText text) {}

    /** The identity of the pane's focus element; a mount scopes it to its own path. */
    private static final ElementId ELEMENT = ElementId.of("viewport");

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
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<Msg>of("up", new Msg.Up(1), "\u2191/k", "up", "up", "k", "shift+up"),
                        KeyAction.<Msg>of("down", new Msg.Down(1), "\u2193/j", "down", "down", "j", "shift+down")),
                KeyMap.group(
                        "Page",
                        KeyAction.<Msg>of("page-up", new Msg.PageUp(), "b/pgup", "page up", "pageup", "b"),
                        KeyAction.<Msg>of("page-down", new Msg.PageDown(), "f/pgdn", "page down", "pagedown", "f"),
                        KeyAction.<Msg>of("half-page-up", new Msg.HalfPageUp(), "u", "half page up", "u")
                                .withCompact(false),
                        KeyAction.<Msg>of("half-page-down", new Msg.HalfPageDown(), "d", "half page down", "d")
                                .withCompact(false),
                        KeyAction.<Msg>of("top", new Msg.Top(), "home", "top", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("bottom", new Msg.Bottom(), "end", "bottom", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Pan",
                        KeyAction.<Msg>of("left", new Msg.Left(1), "\u2190/h", "left", "left", "h"),
                        KeyAction.<Msg>of("right", new Msg.Right(1), "\u2192/l", "right", "right", "l")),
                KeyMap.group(
                        "Match",
                        KeyAction.<Msg>of("next-match", new Msg.NextMatch(), "n", "next match", "n"),
                        // A legacy terminal reports an upper-case letter without any modifier the
                        // key map can name, so Alt is the portable spelling of the reference's "N"
                        // and Shift is the one an enhanced keyboard reports.
                        KeyAction.<Msg>of(
                                "previous-match",
                                new Msg.PreviousMatch(),
                                "alt+n",
                                "previous match",
                                "alt+n",
                                "shift+n")));
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
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        KeyMap<Msg> keys = defaultKeys();
        boolean panning = opts.mode() == Mode.PAN;
        keys = keys.withEnabled("left", panning).withEnabled("right", panning);
        boolean matches = !state.highlights().isEmpty();
        return keys.withEnabled("next-match", matches).withEnabled("previous-match", matches);
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
                action -> new Result(reduce(model, config, action), null),
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
    public View<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        List<Row> window = visible(model, config);
        // A gutter is told how many lines the content holds; a pane without one never asks.
        boolean gutters = config.gutter() != null && config.gutter().width() > 0;
        int totalLines = gutters ? model.content().lineCount() : 0;
        List<View<Msg>> rows = new ArrayList<>(window.size());
        for (Row row : window) {
            // One fixed row per painted row: a row of text wider than the pane would otherwise claim
            // a second screen row and move everything below it out of the window.
            rows.add(Views.fixed(1, Views.text(painted(row, totalLines))));
        }
        return Views.focus(
                ELEMENT,
                model.focused(),
                keys(model, config).bindings(),
                View.InputHandler.none(),
                pointer(),
                Views.column(rows));
    }

    /**
     * The pane's pointer handler: the wheel scrolls the rows under it.
     *
     * <p>The wheel is what a pane has to answer: a report names the cell the pointer is over, so the
     * pane under the pointer scrolls rather than whichever element happens to hold the keyboard. A
     * pane has no selection of its own, so a click selects nothing and reports nothing - it is left
     * to the application around the mount, which sees the report as an interceptor.</p>
     */
    private View.PointerHandler<Msg> pointer() {
        return (event, region) -> {
            if (!event.isWheel()) {
                return Optional.empty();
            }
            return Optional.of(
                    switch (event.button()) {
                        case WHEEL_UP, WHEEL_LEFT -> new Msg.Up(WHEEL_ROWS);
                        default -> new Msg.Down(WHEEL_ROWS);
                    });
        };
    }

    /** The rows one wheel notch scrolls, which is the step a terminal reader expects. */
    private static final int WHEEL_ROWS = 3;

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
        List<Slot> slots = slots(state, opts);
        if (slots.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>(slots.size());
        int styledLine = -1;
        StyledText styled = StyledText.empty();
        for (Slot slot : slots) {
            if (slot.line() != styledLine) {
                styledLine = slot.line();
                styled = highlighted(slot.text(), styledLine, state, opts.styles());
            }
            StyledText text = styled.slice(slot.start(), slot.end());
            rows.add(new Row(slot.line(), slot.row(), slot.rows(), TextMeasure.columns(text.plainText()), text));
        }
        return List.copyOf(rows);
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
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        Content content = state.content();
        int width = contentColumns(state, opts);
        int lines = content.lineCount();
        int total = 0;
        for (int line = 0; line < lines; line++) {
            total += rowsInLine(content, line, width, opts.mode());
        }
        return total;
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
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        Content content = state.content();
        int width = contentColumns(state, opts);
        Anchor anchor = anchor(state, opts);
        int first = 0;
        for (int line = 0; line < anchor.line(); line++) {
            first += rowsInLine(content, line, width, opts.mode());
        }
        return first + anchor.row();
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
        return fraction(firstVisibleRow(state, opts), total - height);
    }

    /**
     * How far right the window is, from 0 to 1.
     *
     * <p>It is defined as zero when there is no horizontal overflow, and it is zero in
     * {@link Mode#WRAP}, where nothing is ever clipped horizontally.</p>
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
        int widest = widestColumns(state.content());
        if (widest <= width) {
            return 0.0;
        }
        return fraction(effectiveFirstColumn(state, opts), widest - width);
    }

    /**
     * The cells the widest logical line occupies.
     *
     * @param state the state to measure; required
     * @return the widest line, in cells, never negative
     */
    public static int widestColumns(Model state) {
        Objects.requireNonNull(state, "state");
        return widestColumns(state.content());
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

    // ── reduction ────────────────────────────────────────────────────────────

    /** Applies one message. Never performs I/O, never mutates the input state. */
    private static Model reduce(Model state, Config config, Msg message) {
        return switch (message) {
            case Msg.Up up -> movedUp(state, config, up.rows());
            case Msg.Down down -> movedDown(state, config, down.rows());
            case Msg.PageUp pageUp -> movedUp(state, config, state.rows());
            case Msg.PageDown pageDown -> movedDown(state, config, state.rows());
            case Msg.HalfPageUp half -> movedUp(state, config, state.rows() / 2);
            case Msg.HalfPageDown half -> movedDown(state, config, state.rows() / 2);
            case Msg.Top top -> toTop(state, config);
            case Msg.Bottom bottom -> toBottom(state, config);
            case Msg.Left left -> panned(state, config, -left.columns());
            case Msg.Right right -> panned(state, config, right.columns());
            case Msg.ContentChanged changed -> changed(state, config, changed.content());
            case Msg.Resized resized -> resized(state, config, resized.rows(), resized.columns());
            case Msg.Highlights highlights -> highlightsChanged(state, config, highlights.highlights());
            case Msg.NextMatch next -> matched(state, config, 1);
            case Msg.PreviousMatch previous -> matched(state, config, -1);
        };
    }

    /** One deliberate move upwards, releasing follow-end only when the window really moved. */
    private static Model movedUp(Model state, Config config, int rows) {
        Anchor from = anchor(state, config);
        Anchor target = retreat(state.content(), config.mode(), contentColumns(state, config), from, rows);
        if (target.equals(from)) {
            return state;
        }
        return at(state, config, target, false);
    }

    /** One move downwards, clamped to the last window the content fills. */
    private static Model movedDown(Model state, Config config, int rows) {
        Anchor from = anchor(state, config);
        Anchor target = clampedDown(state, config, from, rows);
        return target.equals(from) ? state : at(state, config, target, state.followEnd());
    }

    /**
     * The first window. Follow-end is released only when the window can move: content that fits the
     * pane is already at its first row, and losing following there would surprise the next append.
     */
    private static Model toTop(Model state, Config config) {
        Anchor from = anchor(state, config);
        Anchor target = clampedDown(state, config, new Anchor(0, 0), 0);
        if (target.equals(from)) {
            return state;
        }
        return at(state, config, target, false);
    }

    /** The last window, with follow-end armed as far as the configuration allows. */
    private static Model toBottom(Model state, Config config) {
        Anchor target = bottom(state.content(), config.mode(), contentColumns(state, config), state.rows());
        return at(state, config, target, config.followEnd());
    }

    /** One horizontal move. Wrapping viewports have no horizontal offset to move. */
    private static Model panned(Model state, Config config, int columns) {
        if (config.mode() != Mode.PAN || columns == 0) {
            return state;
        }
        int width = contentColumns(state, config);
        int max = Math.max(0, widestColumns(state.content()) - width);
        int current = effectiveFirstColumn(state, config);
        // Widened before the addition: a pager can be asked to move by any count an int holds.
        int target = (int) Math.max(0L, Math.min((long) max, (long) current + columns));
        if (target == current && state.firstColumn() == current) {
            return state;
        }
        return new Model(
                state.topLine(),
                state.topRow(),
                target,
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                state.highlights(),
                state.active());
    }

    /** New content: a following window re-anchors, a moved one keeps its logical anchor. */
    private static Model changed(Model state, Config config, Content content) {
        int width = Math.max(0, state.columns() - config.gutterColumns());
        Anchor anchor = state.followEnd()
                ? bottom(content, config.mode(), width, state.rows())
                : clamped(content, config.mode(), width, anchor(state, config));
        // The highlights described the content version that is being replaced, so they are not
        // carried over: their offsets would mark whatever text now happens to sit at them.
        return new Model(
                anchor.line(),
                anchor.row(),
                clampFirstColumn(state.firstColumn(), content, width, config.mode()),
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                content,
                List.of(),
                -1);
    }

    /** New geometry: a following window re-anchors, a moved one keeps its logical line. */
    private static Model resized(Model state, Config config, int rows, int columns) {
        int width = Math.max(0, columns - config.gutterColumns());
        Anchor anchor = state.followEnd()
                ? bottom(state.content(), config.mode(), width, rows)
                : clamped(state.content(), config.mode(), width, anchor(state, config));
        return new Model(
                anchor.line(),
                anchor.row(),
                clampFirstColumn(state.firstColumn(), state.content(), width, config.mode()),
                state.followEnd(),
                state.focused(),
                rows,
                columns,
                state.content(),
                state.highlights(),
                state.active());
    }

    /** Published ranges, validated against the content they describe. */
    private static Model highlightsChanged(Model state, Config config, List<Highlight> highlights) {
        requireValid(state.content(), highlights);
        return new Model(
                state.topLine(),
                state.topRow(),
                state.firstColumn(),
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                highlights,
                -1);
    }

    /** One step through the published ranges, wrapping at both ends, then scrolled into view. */
    private static Model matched(Model state, Config config, int direction) {
        List<Highlight> highlights = state.highlights();
        if (highlights.isEmpty()) {
            return state;
        }
        int size = highlights.size();
        int active = state.active();
        int next = direction > 0
                ? (active < 0 || active + 1 >= size ? 0 : active + 1)
                : (active <= 0 ? size - 1 : active - 1);
        Highlight target = highlights.get(next);
        Model selected = new Model(
                state.topLine(),
                state.topRow(),
                state.firstColumn(),
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                highlights,
                next);
        return scrolledIntoView(selected, config, target);
    }

    /**
     * The state that shows one range: vertically, and in a panned viewport horizontally.
     *
     * <p>The window the state already covers is the range's own coordinates - the rows it paints,
     * each naming its line and row - so "is the match visible" is one comparison against the first
     * and last of them, and neither answer needs the whole content. A range above the window becomes
     * the anchor; one below it is placed on the last row by walking back from it, which is as far as
     * the pane needs to look.</p>
     */
    private static Model scrolledIntoView(Model state, Config config, Highlight highlight) {
        Content content = state.content();
        Mode mode = config.mode();
        int width = contentColumns(state, config);
        Anchor from = anchor(state, config);
        Anchor target = new Anchor(highlight.line(), rowAt(content, highlight.line(), highlight.start(), width, mode));
        Anchor anchor = from;
        List<Slot> window =
                slots(content, width, mode, effectiveFirstColumn(state, config), from.line(), from.row(), state.rows());
        if (window.isEmpty()) {
            anchor = target;
        } else {
            Slot first = window.get(0);
            Slot last = window.get(window.size() - 1);
            if (compare(target, new Anchor(first.line(), first.row())) < 0) {
                anchor = target;
            } else if (compare(target, new Anchor(last.line(), last.row())) > 0) {
                anchor = retreat(content, mode, width, target, Math.max(0, state.rows() - 1));
            }
        }
        int firstColumn = mode == Mode.PAN ? pannedTo(state, config, highlight) : effectiveFirstColumn(state, config);
        boolean moved = !anchor.equals(from) || firstColumn != effectiveFirstColumn(state, config);
        return new Model(
                anchor.line(),
                anchor.row(),
                firstColumn,
                moved ? false : state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                content,
                state.highlights(),
                state.active());
    }

    /** The horizontal offset that shows one range, as little moved as the content allows. */
    private static int pannedTo(Model state, Config config, Highlight highlight) {
        Content content = state.content();
        String text = content.line(highlight.line()).plainText();
        int start = Math.min(highlight.start(), text.length());
        int end = Math.min(highlight.end(), text.length());
        int column = TextMeasure.columns(text.substring(0, start));
        // An empty range is a position, so it needs the cell it sits on rather than its own width.
        int need = Math.max(1, TextMeasure.columns(text.substring(start, end)));
        int width = contentColumns(state, config);
        int offset = effectiveFirstColumn(state, config);
        if (column < offset) {
            offset = column;
        } else if (column + need > offset + width) {
            offset = Math.max(0, column + need - width);
        }
        int max = Math.max(0, widestColumns(content) - width);
        return Math.max(0, Math.min(offset, max));
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    /**
     * The window's anchor, clamped to the content this state holds.
     *
     * <p>Following is a property of the window, not a position in it: while the flag is set the
     * window covers the end of the content, wherever the stored anchor happens to point. Every
     * projection and every message therefore starts here, so a following window cannot disagree
     * with itself about where it is, and a gesture that leaves the end really moves.</p>
     */
    private static Anchor anchor(Model state, Config config) {
        int width = contentColumns(state, config);
        if (state.followEnd()) {
            return bottom(state.content(), config.mode(), width, state.rows());
        }
        return clamped(state.content(), config.mode(), width, new Anchor(state.topLine(), state.topRow()));
    }

    /** The same anchor, clamped so it names a row the content really has. */
    private static Anchor clamped(Content content, Mode mode, int width, Anchor anchor) {
        int lines = content.lineCount();
        if (lines == 0) {
            return new Anchor(0, 0);
        }
        int line = Math.min(anchor.line(), lines - 1);
        int rows = rowsInLine(content, line, width, mode);
        return new Anchor(line, Math.min(anchor.row(), rows - 1));
    }

    /** The state anchored at {@code target}, with the geometry it already has. */
    private static Model at(Model state, Config config, Anchor target, boolean followEnd) {
        Anchor clamped = clamped(state.content(), config.mode(), contentColumns(state, config), target);
        return new Model(
                clamped.line(),
                clamped.row(),
                state.firstColumn(),
                followEnd,
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                state.highlights(),
                state.active());
    }

    /** One downward move, clamped to the last window the content fills. */
    private static Anchor clampedDown(Model state, Config config, Anchor from, int rows) {
        int width = contentColumns(state, config);
        Anchor target = advance(state.content(), config.mode(), width, from, rows);
        Anchor last = bottom(state.content(), config.mode(), width, state.rows());
        return compare(target, last) > 0 ? last : target;
    }

    /** The anchor {@code count} visual rows below {@code from}, stopping at the content's last row. */
    private static Anchor advance(Content content, Mode mode, int width, Anchor from, int count) {
        int remaining = Math.max(0, count);
        int lines = content.lineCount();
        if (lines == 0 || remaining == 0) {
            return clamped(content, mode, width, from);
        }
        int line = Math.min(from.line(), lines - 1);
        int rowsIn = rowsInLine(content, line, width, mode);
        int row = Math.min(from.row(), rowsIn - 1);
        while (remaining > 0) {
            int below = rowsIn - 1 - row;
            if (remaining <= below) {
                row += remaining;
                remaining = 0;
            } else {
                remaining -= below;
                if (line + 1 >= lines) {
                    row = rowsIn - 1;
                    remaining = 0;
                } else {
                    line++;
                    row = 0;
                    remaining--;
                    rowsIn = rowsInLine(content, line, width, mode);
                }
            }
        }
        return new Anchor(line, row);
    }

    /** The anchor {@code count} visual rows above {@code from}, stopping at the content's first row. */
    private static Anchor retreat(Content content, Mode mode, int width, Anchor from, int count) {
        int remaining = Math.max(0, count);
        int lines = content.lineCount();
        if (lines == 0 || remaining == 0) {
            return clamped(content, mode, width, from);
        }
        int line = Math.min(from.line(), lines - 1);
        int rowsIn = rowsInLine(content, line, width, mode);
        int row = Math.min(from.row(), rowsIn - 1);
        while (remaining > 0) {
            if (row > 0) {
                int up = Math.min(row, remaining);
                row -= up;
                remaining -= up;
            } else if (line > 0) {
                line--;
                rowsIn = rowsInLine(content, line, width, mode);
                row = rowsIn - 1;
                remaining--;
            } else {
                remaining = 0;
            }
        }
        return new Anchor(line, row);
    }

    /**
     * The topmost anchor whose window still reaches the content's end.
     *
     * <p>The window is one pane tall, so this walks back from the last row for at most one window and
     * never measures the whole content. A zero-height pane still has a valid anchor - the last row -
     * and paints nothing from it.</p>
     */
    private static Anchor bottom(Content content, Mode mode, int width, int rows) {
        int lines = content.lineCount();
        if (lines == 0) {
            return new Anchor(0, 0);
        }
        int remaining = Math.max(1, rows);
        for (int line = lines - 1; line >= 0; line--) {
            int rowsIn = rowsInLine(content, line, width, mode);
            if (rowsIn >= remaining) {
                return new Anchor(line, rowsIn - remaining);
            }
            remaining -= rowsIn;
        }
        return new Anchor(0, 0);
    }

    /** How many visual rows one logical line occupies. */
    private static int rowsInLine(Content content, int line, int width, Mode mode) {
        return projection(content.line(line), width, mode, 0).size();
    }

    /**
     * The rows one logical line occupies, in the pane's mode.
     *
     * <p>Both modes answer with {@link VisualRows}, so a tab, a wide glyph and a combining mark cost
     * the same cells whether the line wraps or is panned, and the row a match lands on is the row the
     * cell mapping put it on.</p>
     */
    private static List<VisualRows.Row> projection(StyledText line, int width, Mode mode, int firstColumn) {
        String text = line.plainText();
        if (mode == Mode.PAN) {
            VisualRows.Window window = VisualRows.pane(text, 0, width, firstColumn);
            return List.of(new VisualRows.Row(window.start(), window.end(), window.columns()));
        }
        return VisualRows.project(text, 0, text.length(), 0, width, VisualRows.Cells.NATURAL, VisualRows.CLOSED, -1);
    }

    /** The row of one line an offset lands on: the last row that starts at or before it. */
    private static int rowAt(Content content, int line, int offset, int width, Mode mode) {
        List<VisualRows.Row> rows = projection(content.line(line), width, mode, 0);
        int found = 0;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).start() <= offset) {
                found = index;
            }
        }
        return found;
    }

    /** The painted rows of the window, before their text is cut and styled. */
    private static List<Slot> slots(Model state, Config config) {
        Anchor anchor = anchor(state, config);
        return slots(
                state.content(),
                contentColumns(state, config),
                config.mode(),
                effectiveFirstColumn(state, config),
                anchor.line(),
                anchor.row(),
                state.rows());
    }

    /** The painted rows from one anchor, never reading a line the window does not reach. */
    private static List<Slot> slots(
            Content content, int width, Mode mode, int firstColumn, int line, int row, int rows) {
        if (rows <= 0) {
            return List.of();
        }
        int lines = content.lineCount();
        if (lines == 0) {
            return List.of();
        }
        List<Slot> slots = new ArrayList<>(Math.min(rows, 256));
        int at = Math.min(Math.max(0, line), lines - 1);
        int from = Math.max(0, row);
        while (slots.size() < rows && at < lines) {
            StyledText text = content.line(at);
            List<VisualRows.Row> projected = projection(text, width, mode, firstColumn);
            int total = projected.size();
            for (int index = Math.min(from, total - 1); index < total && slots.size() < rows; index++) {
                VisualRows.Row projectedRow = projected.get(index);
                slots.add(new Slot(at, index, total, projectedRow.start(), projectedRow.end(), text));
            }
            at++;
            from = 0;
        }
        return slots;
    }

    /** The horizontal offset the pane really paints at. */
    private static int effectiveFirstColumn(Model state, Config config) {
        if (config.mode() != Mode.PAN || state.firstColumn() == 0) {
            return 0;
        }
        int max = Math.max(0, widestColumns(state.content()) - contentColumns(state, config));
        return Math.min(state.firstColumn(), max);
    }

    /** The same offset, clamped to the content its new geometry allows. */
    private static int clampFirstColumn(int firstColumn, Content content, int width, Mode mode) {
        if (mode != Mode.PAN || firstColumn <= 0) {
            return Math.max(0, firstColumn);
        }
        // Only a pane that is really panned pays for measuring the widest line, so the common case -
        // a viewport that never pans - stays as cheap as publishing content.
        return Math.min(firstColumn, Math.max(0, widestColumns(content) - width));
    }

    private static int widestColumns(Content content) {
        int widest = 0;
        int lines = content.lineCount();
        for (int line = 0; line < lines; line++) {
            widest = Math.max(widest, TextMeasure.columns(content.line(line).plainText()));
        }
        return widest;
    }

    /** A fraction of one range, clamped to it; zero when there is nothing to travel. */
    private static double fraction(int position, int travel) {
        if (travel <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) position / travel));
    }

    private static int compare(Anchor left, Anchor right) {
        return left.line() != right.line()
                ? Integer.compare(left.line(), right.line())
                : Integer.compare(left.row(), right.row());
    }

    /** Rejects ranges that do not describe the content they claim to. */
    private static void requireValid(Content content, List<Highlight> highlights) {
        int lines = content.lineCount();
        for (Highlight highlight : highlights) {
            if (highlight.line() >= lines) {
                throw new IllegalArgumentException(
                        "highlight line " + highlight.line() + " is outside the content's " + lines + " lines");
            }
            String text = content.line(highlight.line()).plainText();
            if (highlight.end() > text.length()) {
                throw new IllegalArgumentException("highlight range [" + highlight.start() + ", "
                        + highlight.end() + ") is outside line " + highlight.line() + "'s " + text.length()
                        + " units");
            }
            if (!TextMeasure.isBoundary(text, highlight.start()) || !TextMeasure.isBoundary(text, highlight.end())) {
                throw new IllegalArgumentException("highlight range [" + highlight.start() + ", " + highlight.end()
                        + ") of line " + highlight.line() + " does not fall on grapheme boundaries");
            }
        }
    }

    // ── painting ─────────────────────────────────────────────────────────────

    /** One line with every published range of it restyled, the active one last. */
    private static StyledText highlighted(StyledText text, int line, Model state, Config.Styles styles) {
        List<Highlight> highlights = state.highlights();
        if (highlights.isEmpty()) {
            return text;
        }
        StyledText styled = text;
        // The active range is applied last so that an overlapping range cannot paint over it.
        Highlight active = state.activeHighlight().orElse(null);
        for (Highlight highlight : highlights) {
            if (highlight.line() != line || highlight.equals(active)) {
                continue;
            }
            styled = styled.styled(highlight.start(), highlight.end(), styles.highlight());
        }
        if (active != null && active.line() == line) {
            styled = styled.styled(
                    active.start(), active.end(), styles.highlight().merge(styles.selectedHighlight()));
        }
        return styled;
    }

    /** One row as the pane paints it: the gutter, fitted to its width, then the row's own text. */
    private StyledText painted(Row row, int totalLines) {
        Gutter gutter = config.gutter();
        if (gutter == null || gutter.width() == 0) {
            return row.text();
        }
        StyledText rendered = gutter.renderer().render(row.facts(totalLines));
        return fit(rendered, gutter.width()).append(row.text());
    }

    /**
     * A value padded or truncated to exactly {@code columns} cells.
     *
     * <p>Truncation stops on a cluster boundary, so a gutter can never paint half a wide glyph, and
     * the padding is what guarantees the content column starts at one cell on every row.</p>
     */
    private static StyledText fit(StyledText text, int columns) {
        if (columns <= 0) {
            return StyledText.empty();
        }
        if (text == null || text.isEmpty()) {
            return StyledText.plain(" ".repeat(columns));
        }
        String plain = text.plainText();
        int used = 0;
        int end = 0;
        for (String cluster : Graphemes.iterate(plain)) {
            int cells = VisualRows.visualWidth(cluster, used);
            if (used + cells > columns) {
                break;
            }
            used += cells;
            end += cluster.length();
        }
        StyledText fitted = text.slice(0, end);
        return used >= columns ? fitted : fitted.append(StyledText.plain(" ".repeat(columns - used)));
    }

    /** Rejects a negative geometry component. */
    private static void requireNotNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
