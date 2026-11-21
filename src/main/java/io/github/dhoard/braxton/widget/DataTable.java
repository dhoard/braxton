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
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A scrollable, selectable table with typed columns, stable row identities, and horizontal pan.
 *
 * <p>{@link DataTableContent} validates unique column keys and row ids and resolves column widths
 * when built. Ragged rows show missing values as empty cells and ignore values beyond the schema.
 * Cells flatten line breaks, expand tabs at four-column stops, and preserve grapheme boundaries.
 * Header and body use the same cell mapping.</p>
 *
 * <p>The parent reports the table's allocation with {@link DataTableMsg.Resized}; configuration
 * supplies fallback dimensions. An optional fixed header leaves {@link #bodyHeight} body rows
 * starting at {@link DataTableModel#top()}. Navigation moves the window only enough to reveal
 * selection; paging moves one window. Rendering also reveals selections supplied in manually
 * constructed models.</p>
 *
 * <p>{@link Mode#ROW} selects a row; {@link Mode#CELL} selects a cell. {@link
 * DataTableMsg.ToggleMode} preserves row and column. Data replacement, insertion, removal, and
 * reordering preserve the selected row id. Removing it selects the next row at that position, or
 * the last survivor. Empty content has no selection and produces no {@link Output.Chosen}.
 * Cell-mode pan keeps the selected cell visible; row-mode pan can reach any column.</p>
 *
 * <p>{@link DataTableMsg.SetFilter} requires every whitespace-separated term to occur in a row's
 * cells, ignoring case. The application owns the query field. {@link DataTableMsg.ToggleSort}
 * cycles ascending, descending, and content order; {@link DataTableMsg.SortSelected} applies it to
 * the selected column. {@link DataTableModel#rows()} holds the filtered, ordered projection.
 * Reordering retains identity; hiding the selected row chooses its positional replacement.</p>
 *
 * <p>Space marks a row and steps down. {@link DataTableMsg.MarkAll} marks visible rows; {@link
 * DataTableMsg.ClearMarks} clears marks. {@link Output.Chosen} carries marked rows in display order
 * alongside the selected row/cell; a single choice has an empty marked list.</p>
 *
 * <p>Fixed widths use their declared size; automatic widths measure headers and cells within schema
 * bounds. {@link DataTableMsg.ResizeColumn} and {@link DataTableMsg.AdjustColumn} store overrides
 * by key. Navigation, pan, and resize reuse resolved widths rather than remeasure data. Values
 * truncate at grapheme boundaries or pad according to alignment. An optional {@link Bar} reserves
 * its own column; {@link #thumb} describes its visible fraction.</p>
 *
 * <p>{@link Theme} styles merge header, row, and cell policy before selection. {@link
 * #defaultKeys()} defines routing and help; {@link #keys(DataTableModel)} disables irrelevant
 * gestures. Unbound input reaches the parent, which also owns focus.</p>
 *
 * <p>Clicks select rows or cells; a second click chooses. Header clicks cycle sorting. Cells beyond
 * the last column select the row's first column. The wheel steps selection.</p>
 */
public final class DataTable
        implements Component<DataTableModel, DataTableMsg, DataTable.Output>,
                Interactive<DataTableModel, DataTableMsg> {

    /**
     * The rows a table paints when it has no terminal geometry to follow.
     */
    public static final int DEFAULT_HEIGHT = 10;

    /**
     * The cells a table paints when it has no terminal geometry to follow.
     */
    public static final int DEFAULT_WIDTH = 80;

    /**
     * The blank cells between two columns, part of the row the columns are painted in.
     */
    public static final int GAP = 1;

    /**
     * How a value narrower than its column is placed in it.
     */
    public enum Alignment {

        /**
         * The value starts at the column's first cell and the padding follows it.
         */
        LEFT,

        /**
         * The padding is split around the value, the extra cell of an odd pad at its left.
         */
        CENTER,

        /**
         * The value ends at the column's last cell and the padding precedes it.
         */
        RIGHT
    }

    /**
     * How the visible rows are ordered: by one column, ascending or descending.
     *
     * <p>The order is total and stable: equal values keep the order the content holds them in, so a
     * sort never shuffles rows that compare equal. The column is named by key rather than index, so a
     * schema change cannot silently sort by another column, and a key the schema no longer holds
     * leaves the content's own order.</p>
     *
     * @param column the column key the rows are ordered by, empty for the content's own order
     * @param descending whether the order is reversed
     */
    public record Sort(String column, boolean descending) {

        /**
         * Keeps the key non-null.
         */
        public Sort {
            column = column == null ? "" : column;
        }

        /**
         * The content's own order.
         *
         * @return no sort
         */
        public static Sort none() {
            return new Sort("", false);
        }

        /**
         * Whether a sort is applied.
         *
         * @return true when the rows are ordered by a column
         */
        public boolean active() {
            return !column.isEmpty();
        }
    }

    /**
     * What the table's selection covers.
     */
    public enum Mode {

        /**
         * The selected row as a whole: the row is inverted and the column gestures are disabled.
         */
        ROW,

        /**
         * One cell of the selected row: that cell is inverted and the column gestures move it.
         */
        CELL
    }

    /**
     * The width policy of one column: a floor, a preferred width and a cap.
     *
     * <p>The natural width of a column is its preferred width or the widest of its header and cells,
     * whichever is larger, and the width it is painted at is that natural width kept between {@link
     * #min()} and {@link #max()}. A {@linkplain #fixed fixed} policy sets all three to one value, so
     * it is exactly as wide as it says; an {@linkplain #auto automatic} one measures the content and
     * caps nothing. A policy is a definition, so an impossible one - a negative floor, a cap below
     * the floor, a preferred width above the cap - is rejected where it is made rather than repaired
     * while painting.</p>
     *
     * @param min the fewest cells the column may occupy
     * @param preferred the width the column aims for; zero measures the content instead
     * @param max the most cells the column may occupy
     */
    public record Width(int min, int preferred, int max) {

        /**
         * Rejects a policy no column could be painted at.
         */
        public Width {
            if (min < 0) {
                throw new IllegalArgumentException("column minimum width must not be negative");
            }
            if (max < min) {
                throw new IllegalArgumentException("column maximum width must not be below its minimum");
            }
            if (preferred < 0 || preferred > max) {
                throw new IllegalArgumentException("column preferred width must be between zero and its maximum");
            }
        }

        /**
         * A column as wide as its content, with no floor and no cap.
         *
         * @return the automatic policy
         */
        public static Width auto() {
            return new Width(0, 0, Integer.MAX_VALUE);
        }

        /**
         * A column that is exactly {@code value} cells wide, whatever its content.
         *
         * @param value the width, in cells
         * @return the fixed policy
         */
        public static Width fixed(int value) {
            return new Width(value, value, value);
        }

        /**
         * A column between a floor and a cap that aims for a preferred width.
         *
         * @param min the fewest cells the column may occupy
         * @param preferred the width the column aims for; zero measures the content instead
         * @param max the most cells the column may occupy
         * @return the policy
         */
        public static Width of(int min, int preferred, int max) {
            return new Width(min, preferred, max);
        }

        /**
         * The cells this column occupies for content {@code measured} cells wide.
         *
         * @param measured the widest of the column's header and cells
         * @return the resolved width, inside this policy's bounds
         */
        public int resolve(int measured) {
            long natural = Math.max(Math.max(0, measured), preferred);
            return (int) Math.max(min, Math.min((long) max, natural));
        }
    }

    /**
     * One column of the schema.
     *
     * @param key the stable identity of the column; never blank
     * @param header the header text; null is empty
     * @param width the width policy; null is {@link Width#auto()}
     * @param alignment how a value narrower than the column is placed
     */
    public record Column(String key, String header, Width width, Alignment alignment) {

        /**
         * Rejects a column no selection could name.
         */
        public Column {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("column key is required");
            }
            header = header == null ? "" : header;
            width = width == null ? Width.auto() : width;
            alignment = alignment == null ? Alignment.LEFT : alignment;
        }

        /**
         * An automatic left-aligned column.
         *
         * @param key the stable identity of the column
         * @param header the header text
         * @return the column
         */
        public static Column of(String key, String header) {
            return new Column(key, header, Width.auto(), Alignment.LEFT);
        }

        /**
         * A fixed-width left-aligned column.
         *
         * @param key the stable identity of the column
         * @param header the header text
         * @param width the width, in cells
         * @return the column
         */
        public static Column of(String key, String header, int width) {
            return new Column(key, header, Width.fixed(width), Alignment.LEFT);
        }

        /**
         * The same column with a different width policy.
         *
         * @param value the policy; null is {@link Width#auto()}
         * @return the new column
         */
        public Column withWidth(Width value) {
            return new Column(key, header, value, alignment);
        }

        /**
         * The same column with a different alignment.
         *
         * @param value the alignment; null is {@link Alignment#LEFT}
         * @return the new column
         */
        public Column withAlignment(Alignment value) {
            return new Column(key, header, width, value);
        }
    }

    /**
     * One row of the table.
     *
     * @param id the stable identity of the row; never blank
     * @param cells the values, one per schema column in schema order; a missing one is empty
     */
    public record Row(String id, List<StyledText> cells) {

        /**
         * Rejects a row no selection could name.
         */
        public Row {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("row id is required");
            }
            cells = cells == null ? List.of() : List.copyOf(cells);
            for (StyledText cell : cells) {
                Objects.requireNonNull(cell, "cell");
            }
        }

        /**
         * A row of plain cells.
         *
         * @param id the stable identity of the row
         * @param cells the cell texts, one per column; null is empty
         * @return the row
         */
        public static Row of(String id, String... cells) {
            if (cells == null) {
                return new Row(id, List.of());
            }
            List<StyledText> values = new ArrayList<>(cells.length);
            for (String cell : cells) {
                values.add(StyledText.plain(cell == null ? "" : cell));
            }
            return new Row(id, values);
        }

        /**
         * A row of styled cells.
         *
         * @param id the stable identity of the row
         * @param cells the values, one per column; null is empty
         * @return the row
         */
        public static Row of(String id, List<StyledText> cells) {
            return new Row(id, cells);
        }

        /**
         * The value at one column.
         *
         * @param column the column index
         * @return the value, or the empty value when this row does not hold that column
         */
        public StyledText cell(int column) {
            return column >= 0 && column < cells.size() ? cells.get(column) : StyledText.empty();
        }
    }

    /**
     * The selected row and cell.
     *
     * <p>Both indices are clamped into the content by {@link DataTableModel}, and {@code -1} is the absence a
     * table with no rows or no columns has. The mode is part of the selection because it decides what
     * the selection covers and what the column gestures do.</p>
     *
     * @param mode what the selection covers
     * @param row the selected row index, or -1 for none
     * @param column the selected column index, or -1 for none
     */
    public record Selection(Mode mode, int row, int column) {

        /**
         * Substitutes the default mode and keeps the absence representable.
         */
        public Selection {
            mode = mode == null ? Mode.ROW : mode;
            row = Math.max(-1, row);
            column = Math.max(-1, column);
        }

        /**
         * No selection.
         *
         * @return the empty selection
         */
        public static Selection none() {
            return new Selection(Mode.ROW, -1, -1);
        }

        /**
         * A selection of one row as a whole.
         *
         * @param row the row index
         * @return the selection
         */
        public static Selection row(int row) {
            return new Selection(Mode.ROW, row, -1);
        }

        /**
         * A selection of one cell.
         *
         * @param row the row index
         * @param column the column index
         * @return the selection
         */
        public static Selection cell(int row, int column) {
            return new Selection(Mode.CELL, row, column);
        }

        /**
         * Whether a row is selected.
         *
         * @return whether the selection names a row
         */
        public boolean present() {
            return row >= 0;
        }

        /**
         * Whether the selection covers one cell rather than a whole row.
         *
         * @return whether the mode is {@link Mode#CELL}
         */
        public boolean cellwise() {
            return mode == Mode.CELL;
        }
    }

    /**
     * What a {@link Theme} is told about one cell, and what its style is asked of.
     *
     * @param row the row index
     * @param column the column index
     * @param schema the column the cell belongs to
     * @param source the row the cell belongs to
     * @param selected whether the cell is part of the selection
     * @param focused whether the table owns focus
     */
    public record Cell(int row, int column, Column schema, Row source, boolean selected, boolean focused) {

        /**
         * The value this cell paints.
         *
         * @return the cell's value, or the empty value when the row does not hold it
         */
        public StyledText value() {
            return source.cell(column);
        }
    }

    /**
     * How a table paints: the styles of one header cell, one row and one cell.
     *
     * <p>A callback is a pure function of the facts it is given and runs only for what is painted:
     * {@link #header} once per painted header cell, {@link #row} once per painted body row and
     * {@link #cell} once per painted body cell. A cell outside the window is never asked about, so a
     * theme can style by value, by parity or by a domain rule without the table knowing anything
     * about it. A returned style is merged over the cell's own styles, and the selection is merged
     * over the result, so what is selected stays visible whatever the theme says.</p>
     */
    public interface Theme {

        /**
         * The style of one header cell.
         *
         * @param column the column the cell heads
         * @return the style; null or a plain style paints nothing extra
         */
        default Style header(Column column) {
            return Style.NONE;
        }

        /**
         * The style of one whole row.
         *
         * @param row the row
         * @param index its index in the content
         * @return the style; null or a plain style paints nothing extra
         */
        default Style row(Row row, int index) {
            return Style.NONE;
        }

        /**
         * The style of one cell.
         *
         * @param cell the cell's facts; never null
         * @return the style; null or a plain style paints nothing extra
         */
        default Style cell(Cell cell) {
            return Style.NONE;
        }

        /**
         * The style merged over a row the user marked.
         *
         * <p>It is merged after the row's own style and before the cells', so a theme can paint a
         * marked row whatever its values are, and the selection is still merged last.</p>
         *
         * @param row the marked row
         * @param index its position in the visible rows
         * @return the style; null or a plain style paints nothing extra
         */
        default Style marked(Row row, int index) {
            return Style.NONE;
        }

        /**
         * A theme that adds nothing.
         *
         * @return the plain theme
         */
        static Theme plain() {
            return new Theme() {};
        }

        /**
         * A theme from three fixed styles.
         *
         * @param header the header cells' style
         * @param row the rows' style
         * @param cell the cells' style
         * @return the theme
         */
        static Theme of(Style header, Style row, Style cell) {
            Style heading = header == null ? Style.NONE : header;
            Style line = row == null ? Style.NONE : row;
            Style value = cell == null ? Style.NONE : cell;
            return new Theme() {
                @Override
                public Style header(Column column) {
                    return heading;
                }

                @Override
                public Style row(Row source, int index) {
                    return line;
                }

                @Override
                public Style cell(Cell facts) {
                    return value;
                }
            };
        }
    }

    /**
     * One painted line of the table: the header, or one body row of the window.
     *
     * <p>{@code text} is exactly what the table paints for this line, already cut to the horizontal
     * window and padded to the cells the window shows, so a parent can copy, inspect or assert the
     * same cells the terminal receives.</p>
     *
     * @param header whether the line is the header
     * @param row the row index the line shows, or -1 for the header
     * @param text the line's painted text
     */
    public record Line(boolean header, int row, StyledText text) {}

    /**
     * What the application should do after an action.
     */
    public sealed interface Output {

        /**
         * The selection the reader chose.
         *
         * <p>A {@link Mode#CELL} selection names its column and its value; a {@link Mode#ROW}
         * selection names the row as a whole, with {@code columnKey} empty, {@code column} -1 and an
         * empty value.</p>
         *
         * @param rowId the selected row's id
         * @param row the selected row's index
         * @param columnKey the selected column's key, or empty for a row selection
         * @param column the selected column's index, or -1 for a row selection
         * @param value the selected value, or the empty value for a row selection
         * @param marked the rows the user marked, in display order; empty for a single choice
         */
        record Chosen(String rowId, int row, String columnKey, int column, StyledText value, List<Row> marked)
                implements Output {
            /**
             * Creates a {@code Chosen} value.
             *
             * @param rowId the selected row's id
             * @param row the selected row's index
             * @param columnKey the selected column's key, or empty for a row selection
             * @param column the selected column's index, or -1 for a row selection
             * @param value the selected value, or the empty value for a row selection
             * @param marked the rows the user marked, in display order; empty for a single choice
             */
            public Chosen {
                rowId = rowId == null ? "" : rowId;
                columnKey = columnKey == null ? "" : columnKey;
                value = value == null ? StyledText.empty() : value;
                marked = marked == null ? List.of() : List.copyOf(marked);
            }

            /**
             * The same choice with no marked rows.
             *
             * @param rowId the selected row's id
             * @param row the selected row's index
             * @param columnKey the selected column's key, or empty
             * @param column the selected column's index, or -1
             * @param value the selected value, or the empty value
             */
            public Chosen(String rowId, int row, String columnKey, int column, StyledText value) {
                this(rowId, row, columnKey, column, value, List.of());
            }
        }

        /**
         * The reader left the table without choosing anything.
         */
        record Cancelled() implements Output {}
    }

    /**
     * The scrollbar the table paints on its right edge.
     *
     * <p>It takes exactly one column, and only while the visible rows are taller than the body: the
     * thumb shows how much of the rows the window covers and where it sits, and the track fills the
     * rows around it. A table that fits paints no scrollbar, so enabling one changes the width the
     * cells are painted at and nothing else.</p>
     *
     * @param visible whether the table paints its own scrollbar
     * @param thumb the style the thumb cells paint with; never null
     * @param track the style the track cells paint with; never null
     */
    public record Bar(boolean visible, Style thumb, Style track) {

        /**
         * The glyph a thumb cell paints.
         */
        public static final String THUMB = "\u2588";

        /**
         * The glyph a track cell paints.
         */
        public static final String TRACK = "\u2502";

        /**
         * Substitutes a plain style for a missing one.
         */
        public Bar {
            thumb = thumb == null ? Style.NONE : thumb;
            track = track == null ? Style.NONE : track;
        }

        /**
         * No scrollbar at all.
         *
         * @return the disabled scrollbar
         */
        public static Bar none() {
            return new Bar(false, Style.NONE, Style.NONE);
        }

        /**
         * An enabled scrollbar with its two styles.
         *
         * @param thumb the style the thumb paints with; null is plain
         * @param track the style the track paints with; null is plain
         * @return the scrollbar
         */
        public static Bar of(Style thumb, Style track) {
            return new Bar(true, thumb, track);
        }

        /**
         * The same scrollbar, shown or hidden.
         *
         * @param value whether the table paints this scrollbar
         * @return the new scrollbar
         */
        public Bar withVisible(boolean value) {
            return new Bar(value, thumb, track);
        }
    }

    /**
     * Where the scrollbar's thumb sits in the body.
     *
     * @param start the first body row the thumb covers, counted from the window's top
     * @param size the rows the thumb covers; zero when the rows fit
     */
    public record Thumb(int start, int size) {}

    /**
     * The thumb geometry a state paints.
     *
     * @param state the state to measure; required
     * @param config the definition whose header decides the body height; null is
     *     {@link DataTableConfig#defaults()}
     * @return the thumb geometry
     */
    public static Thumb thumb(DataTableModel state, DataTableConfig config) {
        Objects.requireNonNull(state, "state");
        DataTableConfig opts = config == null ? DataTableConfig.defaults() : config;
        if (!opts.scrollbar().visible()) {
            return new Thumb(0, 0);
        }
        int body = bodyHeight(state, opts);
        int rows = state.rows().size();
        if (body <= 0 || rows <= body) {
            return new Thumb(0, 0);
        }
        long size = Math.max(1L, Math.min(body, Math.round((double) body * body / rows)));
        long travel = rows - body;
        long start = Math.round((double) (body - size) * top(state, opts) / travel);
        return new Thumb((int) Math.max(0, Math.min(start, body - size)), (int) size);
    }

    /**
     * A reduction result.
     */
    record Result(DataTableModel state, Output intent) implements WidgetResult<DataTableModel, Output> {}

    private final DataTableConfig config;

    /**
     * Creates the component.
     *
     * @param config the immutable definition; required
     */
    public DataTable(DataTableConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The definition this table was built from.
     *
     * @return the definition
     */
    public DataTableConfig config() {
        return config;
    }

    /**
     * The gestures a table defines, before the state is taken into account.
     *
     * <p>They are the table's own: rows and pages vertically, columns in {@link Mode#CELL}, cells
     * horizontally, the mode switch, choosing and canceling. {@link #keys(DataTableModel)} is the map for one
     * state - it drops the column gestures in {@link Mode#ROW} and the pan when nothing is clipped -
     * and it is what the component routes and what help renders.</p>
     *
     * @return the default gestures
     */
    public static KeyMap<DataTableMsg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<DataTableMsg>of("up", new DataTableMsg.Up(), "\u2191/k", "up a row", "up", "k"),
                        KeyAction.<DataTableMsg>of(
                                "down", new DataTableMsg.Down(), "\u2193/j", "down a row", "down", "j"),
                        KeyAction.<DataTableMsg>of("left", new DataTableMsg.Left(), "\u2190", "left a column", "left"),
                        KeyAction.<DataTableMsg>of(
                                "right", new DataTableMsg.Right(), "\u2192", "right a column", "right")),
                KeyMap.group(
                        "Page",
                        KeyAction.<DataTableMsg>of(
                                "page-up", new DataTableMsg.PageUp(), "b/pgup", "page up", "pageup", "b"),
                        KeyAction.<DataTableMsg>of(
                                "page-down", new DataTableMsg.PageDown(), "f/pgdn", "page down", "pagedown", "f"),
                        KeyAction.<DataTableMsg>of(
                                        "half-page-up", new DataTableMsg.HalfPageUp(), "u", "half page up", "u")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of(
                                        "half-page-down", new DataTableMsg.HalfPageDown(), "d", "half page down", "d")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of("top", new DataTableMsg.Top(), "home", "first row", "home")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of("bottom", new DataTableMsg.Bottom(), "end", "last row", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Pan",
                        KeyAction.<DataTableMsg>of("pan-left", new DataTableMsg.PanLeft(1), "h", "pan left", "h"),
                        KeyAction.<DataTableMsg>of("pan-right", new DataTableMsg.PanRight(1), "l", "pan right", "l")),
                KeyMap.group(
                        "Order",
                        KeyAction.<DataTableMsg>of("sort", new DataTableMsg.SortSelected(), "s", "sort column", "s")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of(
                                        "narrow", new DataTableMsg.ResizeSelected(-1), "<", "narrow column", "<")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of(
                                        "widen", new DataTableMsg.ResizeSelected(1), ">", "widen column", ">")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of("mark", new DataTableMsg.ToggleMark(), "space", "mark row", "space")
                                .withCompact(false),
                        KeyAction.<DataTableMsg>of(
                                        "mark-all", new DataTableMsg.MarkAll(), "ctrl+a", "mark all", "ctrl+a")
                                .withCompact(false)),
                KeyMap.group(
                        "Select",
                        KeyAction.<DataTableMsg>of("mode", new DataTableMsg.ToggleMode(), "m", "row/cell", "m"),
                        KeyAction.<DataTableMsg>of(
                                "choose", new DataTableMsg.Choose(), "enter", "choose", "enter", "alt+enter"),
                        KeyAction.<DataTableMsg>of("cancel", new DataTableMsg.Cancel(), "esc", "cancel", "escape")));
    }

    /**
     * The gestures one state handles.
     *
     * <p>Help and routing read the same map, so a gesture help shows is a gesture that fires: a row
     * selection has no column to move, and a table whose rows fit has nothing to pan.</p>
     *
     * @param state the state the gestures are built for; required
     * @return the gestures
     */
    public static KeyMap<DataTableMsg> keys(DataTableModel state) {
        Objects.requireNonNull(state, "state");
        boolean cells = state.selection().cellwise();
        boolean clipped = state.paintedRowWidth() > state.width();
        boolean schema = state.content().columnCount() > 0;
        boolean browse = schema && !state.rows().isEmpty();
        return defaultKeys()
                .withEnabled("left", cells)
                .withEnabled("right", cells)
                .withEnabled("pan-left", clipped)
                .withEnabled("pan-right", clipped)
                .withEnabled("sort", browse)
                .withEnabled("narrow", schema)
                .withEnabled("widen", schema)
                .withEnabled("mark", browse)
                .withEnabled("mark-all", browse);
    }

    @Override
    public Update<DataTableModel, DataTableMsg, Output> init(InitContext context) {
        // The startup geometry is the only size the table can know before a resize arrives: what the
        // application paints outside it is taken out of the terminal's own geometry, and the
        // configured fallback is what a headless test or a preview uses.
        int height = context.viewport().rows() > 0
                ? Math.max(0, context.viewport().rows() - config.reservedRows())
                : config.height();
        int width = context.viewport().cols() > 0
                ? Math.max(0, context.viewport().cols() - config.reservedColumns())
                : config.width();
        DataTableContent content = config.content();
        boolean present = content.rowCount() > 0 && content.columnCount() > 0;
        Selection selection = new Selection(config.mode(), present ? 0 : -1, present ? 0 : -1);
        return Update.next(new DataTableModel(content, selection, 0, 0, height, width, true));
    }

    @Override
    public Update<DataTableModel, DataTableMsg, Output> update(DataTableModel model, DataTableMsg message) {
        return WidgetUpdate.settled(
                model,
                message,
                action -> DataTableEditing.reduce(model, config, action),
                state -> state.withFocus(model.focused()));
    }

    /**
     * Describes the window this state covers.
     *
     * <p>The context is deliberately unused: its viewport is the one the table's parent lays out for,
     * not the table's own geometry. {@link DataTableModel#height()} and {@link DataTableModel#width()} are what the
     * parent reserved and reported, and they are the only geometry a window may use.</p>
     */
    @Override
    public Element<DataTableMsg> view(DataTableModel model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The rows the window shows, with the table itself as the leaf that answers for them.
     *
     * <p>The content is a column of one fixed row per line, so a line wider than the table cannot
     * claim a second screen row and move everything below it out of the window. The table's own
     * interaction is a second layer of the same stack: it measures as no cells, is arranged into the
     * whole rectangle, and is therefore what a key, a pointer report and a focus walk reach - while
     * the cells stay exactly the ones the rows painted.</p>
     */
    private Element<DataTableMsg> scene(DataTableModel model) {
        List<Element<DataTableMsg>> rows = new ArrayList<>();
        Bar bar = config.scrollbar();
        boolean barPainted = bar.visible() && thumb(model, config).size() > 0;
        Thumb thumb = barPainted ? thumb(model, config) : new Thumb(0, 0);
        int top = top(model, config);
        for (Line line : lines(model, config)) {
            Element<DataTableMsg> text = Elements.fixed(1, Elements.text(line.text()));
            if (!barPainted) {
                rows.add(text);
                continue;
            }
            // The bar keeps its own column on every line, so the header stays aligned with the body
            // and the thumb covers exactly the rows the window shows.
            int bodyRow = line.header() ? -1 : line.row() - top;
            boolean onThumb = bodyRow >= thumb.start() && bodyRow < thumb.start() + thumb.size();
            String glyph = line.header() ? " " : onThumb ? Bar.THUMB : Bar.TRACK;
            Style style = line.header() ? Style.NONE : onThumb ? bar.thumb() : bar.track();
            rows.add(Elements.row(Elements.grow(text), Elements.fixed(1, Elements.text(glyph, style))));
        }
        return Elements.stack(Elements.column(rows), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The table's keys: exactly the actions {@link #keys(DataTableModel)} enables, so help cannot drift.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, DataTableModel model, Consumer<DataTableMsg> emit) {
        keys(model).actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(DataTableModel model) {
        return model.focused();
    }

    /**
     * The table's pointer handler: a click selects the row it landed on - or the cell, in a cellwise
     * table - a second click chooses what the first selected, and the wheel steps the selection.
     *
     * <p>The row is found the way {@link #lines} paints it: the header row first when the definition
     * paints one, then the body rows of the window from {@link #top}. A click on the header, or on a
     * row past the content, selects nothing.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, DataTableModel model, Consumer<DataTableMsg> emit) {
        pointer(pointer.localTo(bounds.row(), bounds.col()), model).ifPresent(emit);
    }

    private Optional<DataTableMsg> pointer(PointerEvent event, DataTableModel model) {
        if (event.isWheel()) {
            return Optional.of(
                    switch (event.button()) {
                        case WHEEL_UP, WHEEL_LEFT -> new DataTableMsg.Up();
                        default -> new DataTableMsg.Down();
                    });
        }
        if (!event.isLeftPress()) {
            return Optional.empty();
        }
        if (config.header() && model.content().columnCount() > 0 && event.row() == 0) {
            // A header cell is the sort control: pressing it orders the rows by that column, the
            // same cycle the sorted-by gesture runs.
            return Optional.of(new DataTableMsg.ToggleSort(model.content()
                    .columns()
                    .get(columnAt(model, event.column()))
                    .key()));
        }
        int row = bodyRowAt(model, config, event.row());
        if (row < 0) {
            return Optional.empty();
        }
        String id = model.rows().get(row).id();
        if (event.clickCount() > 1) {
            return Optional.of(new DataTableMsg.Choose());
        }
        return Optional.of(
                model.selection().cellwise()
                        ? new DataTableMsg.SelectCell(id, columnAt(model, event.column()))
                        : new DataTableMsg.Select(id));
    }

    /**
     * The content row index painted on one row of the table, or -1 for a chrome or blank row.
     */
    static int bodyRowAt(DataTableModel state, DataTableConfig config, int row) {
        DataTableConfig opts = config == null ? DataTableConfig.defaults() : config;
        int header = opts.header() && state.content().columnCount() > 0 ? 1 : 0;
        int body = row - header;
        if (body < 0 || body >= bodyHeight(state, opts)) {
            return -1;
        }
        int index = top(state, opts) + body;
        return index < state.rows().size() ? index : -1;
    }

    /**
     * The column index one painted cell falls in, or 0 when the cells do not reach it.
     */
    static int columnAt(DataTableModel state, int column) {
        for (int index = 0; index < state.content().columnCount(); index++) {
            int start = state.columnStart(index);
            if (column >= start && column < start + state.columnWidth(index)) {
                return index;
            }
        }
        return 0;
    }

    // ── projections ──────────────────────────────────────────────────────────

    /**
     * The cells the table paints its content in: the allocated width less the scrollbar's own column.
     *
     * @param state the current state; required
     * @param config the definition whose scrollbar decides the lost column; null is
     *     {@link DataTableConfig#defaults()}
     * @return the cells the cells and the header are painted in, never negative
     */
    public static int contentWidth(DataTableModel state, DataTableConfig config) {
        Objects.requireNonNull(state, "state");
        DataTableConfig opts = config == null ? DataTableConfig.defaults() : config;
        return Math.max(0, state.width() - (barPainted(state, opts) ? 1 : 0));
    }

    /**
     * Whether a scrollbar is painted at all: enabled, and there are more rows than the body holds.
     */
    static boolean barPainted(DataTableModel state, DataTableConfig config) {
        return config.scrollbar().visible() && thumb(state, config).size() > 0;
    }

    /**
     * The body rows a window of this geometry holds.
     *
     * <p>It is the allocated height less the header line {@link DataTableConfig#header()} may paint, which is
     * what a page gesture moves by and what a parent's own layout arithmetic needs.</p>
     *
     * @param state the current state; required
     * @param config the definition whose header flag applies; null is {@link DataTableConfig#defaults()}
     * @return the body rows the window holds, never negative
     */
    public static int bodyHeight(DataTableModel state, DataTableConfig config) {
        Objects.requireNonNull(state, "state");
        DataTableConfig opts = config == null ? DataTableConfig.defaults() : config;
        int header = opts.header() && state.content().columnCount() > 0 ? 1 : 0;
        return Math.max(0, state.height() - header);
    }

    /**
     * The lines the window paints, in paint order.
     *
     * <p>The header comes first when the definition paints one, and at most {@link #bodyHeight} body
     * rows follow it. Every line is what the table paints for it: the horizontal window already cut,
     * the cells already padded to their columns and the styles already applied. A line is never
     * wider than {@link DataTableModel#width()}.</p>
     *
     * @param state the current state; required
     * @param config the definition whose header, theme and styles apply; null is
     *     {@link DataTableConfig#defaults()}
     * @return the painted lines, empty when the table has no columns or no geometry
     */
    public static List<Line> lines(DataTableModel state, DataTableConfig config) {
        Objects.requireNonNull(state, "state");
        DataTableConfig opts = config == null ? DataTableConfig.defaults() : config;
        DataTableContent content = state.content();
        if (content.columnCount() == 0 || state.width() == 0 || state.height() == 0) {
            return List.of();
        }
        // The horizontal window is one number for every row, so it is resolved once per frame
        // rather than once per painted row.
        int firstColumn = DataTableEditing.effectiveFirstColumn(state, opts);
        List<Line> lines = new ArrayList<>();
        if (opts.header()) {
            lines.add(new Line(
                    true,
                    -1,
                    DataTableRendering.paint(
                            state, opts, -1, DataTableRendering.headerValues(state), true, firstColumn)));
        }
        int top = top(state, opts);
        int body = bodyHeight(state, opts);
        for (int index = 0; index < body && top + index < state.rows().size(); index++) {
            lines.add(new Line(
                    false, top + index, DataTableRendering.paint(state, opts, top + index, null, false, firstColumn)));
        }
        return List.copyOf(lines);
    }

    /**
     * The index of the first body row the window shows.
     *
     * <p>The stored offset is clamped to the content and then moved the least it can to keep the
     * selection visible, so this is the row the window really starts on however the state was
     * built.</p>
     *
     * @param state the current state; required
     * @param config the definition whose header flag decides the window's height; null is
     *     {@link DataTableConfig#defaults()}
     * @return the first visible body row, never negative
     */
    public static int top(DataTableModel state, DataTableConfig config) {
        Objects.requireNonNull(state, "state");
        return DataTableEditing.revealedTop(state, config == null ? DataTableConfig.defaults() : config);
    }

    /**
     * The first painted cell the window shows.
     *
     * @param state the current state; required
     * @param config the definition the window's geometry is read from; null is
     *     {@link DataTableConfig#defaults()}
     * @return the horizontal offset, never negative
     */
    public static int firstColumn(DataTableModel state, DataTableConfig config) {
        Objects.requireNonNull(state, "state");
        return DataTableEditing.effectiveFirstColumn(state, config == null ? DataTableConfig.defaults() : config);
    }

    /**
     * The cells one row paints.
     *
     * @param state the current state; required
     * @return the painted width of a row, never negative
     */
    public static int rowWidth(DataTableModel state) {
        Objects.requireNonNull(state, "state");
        return state.paintedRowWidth();
    }

    /**
     * The first painted cell one column occupies.
     *
     * @param state the current state; required
     * @param column the column index
     * @return the cell offset of the column, or zero when it does not exist
     */
    public static int columnStart(DataTableModel state, int column) {
        Objects.requireNonNull(state, "state");
        return state.columnStart(column);
    }

    // ── reduction ────────────────────────────────────────────────────────────

    // ── geometry ─────────────────────────────────────────────────────────────

    // ── painting ─────────────────────────────────────────────────────────────

}
