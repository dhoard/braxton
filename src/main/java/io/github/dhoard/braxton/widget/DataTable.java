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
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A scrollable, selectable table over typed columns and identity-carrying rows.
 *
 * <p>This is the interactive table of the family. The static {@link Table} renders cells once and
 * knows nothing about selection; {@link TableSelector} selects one cell of an already-complete grid
 * and renders every row of it. A {@code DataTable} owns a schema, a bounded window over any number of
 * rows, a selection that survives the rows changing underneath it, and the horizontal pan that shows
 * columns too wide for the pane. It is a component with nested immutable {@code Model}, sealed
 * {@code Msg}/{@code Output} and immutable {@code Config}, the shape {@link Editor},
 * {@link TableSelector} and {@link TextViewport} share.</p>
 *
 * <h2>Schema and content</h2>
 *
 * <p>A {@link Column} has a stable {@link Column#key()}, a header, a {@link Width} policy and an
 * {@link Alignment}. A {@link Row} has a stable {@link Row#id()} and one value per column. A {@link
 * Content} is the pair, immutable and validated where it is made: two columns may not share a key and
 * two rows may not share an id, because both the selection and the model's own messages name them.
 * Content is also where the column widths are resolved - once, when the content is built - so a frame
 * paints the window it was given instead of measuring the table again.</p>
 *
 * <p>A row may be <em>ragged</em>: it may hold fewer values than the schema has columns. A value a row
 * does not hold is the empty value, which is what a partially filled record looks like rather than an
 * error, and a row may hold more values than the schema - the extra ones are simply not painted. Tab
 * stops are expanded to the shared four-column boundaries and every break form becomes one space, so a
 * cell is one painted row and a cell's columns are the columns {@link Table} and {@link TableSelector}
 * would give it. Wide graphemes, combining marks and joiners are charged inside the row they are
 * painted in, the rule {@link GridRows} states, so the header and the body rows of one table place
 * their columns on the same cells.</p>
 *
 * <h2>The window</h2>
 *
 * <p>{@link Model#height()} is the rows the parent allocates the whole table - the header, when {@link
 * Config#header()} paints one, and the body rows under it - and {@link Model#width()} is the cells it
 * allocates, header included. Both are fallbacks in {@link Config} until the parent reports the real
 * allocation as {@link Msg.Resized}, exactly as {@link TextViewport} does: a table inside a box or a
 * row is not the whole terminal, so only the parent knows the geometry. {@link #bodyHeight} answers
 * how many body rows that allocation leaves, and {@link #lines} is what a frame paints.</p>
 *
 * <p>The header line is painted first and stays where it is while the body scrolls under it. The body
 * is a window of at most {@link #bodyHeight} rows starting at {@link Model#top()}, and every
 * navigation message moves the selection and then moves the window the least it can to keep it
 * visible - so a selection is never scrolled out of sight, and a page gesture moves exactly one
 * window. A projection reveals the selection too, so a state an application built by hand still
 * paints it.</p>
 *
 * <h2>Selection and identity</h2>
 *
 * <p>Selection is {@link Mode#ROW} or {@link Mode#CELL}: a row is selected as a whole, or one cell of
 * it is. The mode is state, not a definition, so {@link Msg.ToggleMode} switches between them without
 * losing the row or the column the reader was on. The selected row is stored by <em>index</em> and the
 * identity rule lives in the messages that change the data: replacing, reordering, inserting or
 * removing rows keeps the selected row's id selected, and removing the selected row selects the
 * nearest survivor - the row that takes its place, or the last row when it was the last. An empty
 * table has nothing selected and answers no {@link Output.Chosen}.</p>
 *
 * <p>Horizontally, the window is clamped to keep the selected cell of a {@link Mode#CELL} selection
 * visible, so panning reaches the columns beside the selection and never loses it. In {@link
 * Mode#ROW} the whole row is the selection, so panning is free to reach any cell of it. The row's
 * painted width, the column starts and the horizontal offset are one cell mapping, and that mapping
 * is the projection the editor and the pager use, so a tab, a wide glyph or a combining mark costs the
 * same columns wherever the window is.</p>
 *
 * <h2>Presentation</h2>
 *
 * <p>Every column resolves its own {@link Width}: a {@linkplain Width#fixed fixed} width is used as
 * declared, and an automatic one is the widest of the header and the column's cells, kept inside the
 * {@link Width#min() minimum} and {@link Width#max() maximum} the schema declares. A value wider than
 * its column is truncated on a cluster boundary rather than split, and a value narrower is padded on
 * the side its {@link Alignment} names. {@link Theme} is where an application paints: {@link
 * Theme#header} styles one header cell, {@link Theme#row} styles a whole row, and {@link Theme#cell}
 * styles one cell with its selection and focus facts in hand. The styles are merged in that order and
 * the selection is merged last, so a theme cannot hide what is selected.</p>
 *
 * <p>Measuring is the one cost that grows with the data, so it happens when the content changes and
 * nowhere else: a frame, a selection move, a pan and a resize all keep the widths the content resolved
 * and paint at most {@link #bodyHeight} rows of the window.</p>
 *
 * <h2>Keys and help</h2>
 *
 * <p>{@link #defaultKeys()} is the definition the component routes and the one {@link Help} renders;
 * {@link #keys(Model)} is that map for one state, where a row selection has no column to move and a
 * table that fits has nothing to pan. Printable text and every unnamed gesture reach the parent.
 * Focus is model data the application owns, as in every other bundled component: a reduction never
 * changes it.</p>
 *
 * <h2>Pointer input</h2>
 *
 * <p>A click selects the row painted on that row - {@link Msg.SelectCell} in {@link Mode#CELL}, so
 * the column under the pointer is selected with it - and a second click chooses it. The header, the
 * rows below the content and the cells past the last column select nothing by column: a click there
 * selects the row and its first column. The wheel steps the selection.</p>
 */
public final class DataTable implements Component<DataTable.Model, DataTable.Msg, DataTable.Output> {

    /** The rows a table paints when it has no terminal geometry to follow. */
    public static final int DEFAULT_HEIGHT = 10;

    /** The cells a table paints when it has no terminal geometry to follow. */
    public static final int DEFAULT_WIDTH = 80;

    /** The blank cells between two columns, part of the row the columns are painted in. */
    public static final int GAP = 1;

    /** The identity of the table's focus element; a mount scopes it to its own path. */
    private static final ElementId ELEMENT = ElementId.of("data-table");

    /** How a value narrower than its column is placed in it. */
    public enum Alignment {

        /** The value starts at the column's first cell and the padding follows it. */
        LEFT,

        /** The padding is split around the value, the extra cell of an odd pad at its left. */
        CENTER,

        /** The value ends at the column's last cell and the padding precedes it. */
        RIGHT
    }

    /** What the table's selection covers. */
    public enum Mode {

        /** The selected row as a whole: the row is inverted and the column gestures are disabled. */
        ROW,

        /** One cell of the selected row: that cell is inverted and the column gestures move it. */
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

        /** Rejects a policy no column could be painted at. */
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

        /** Rejects a column no selection could name. */
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

        /** Rejects a row no selection could name. */
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
     * <p>Both indices are clamped into the content by {@link Model}, and {@code -1} is the absence a
     * table with no rows or no columns has. The mode is part of the selection because it decides what
     * the selection covers and what the column gestures do.</p>
     *
     * @param mode what the selection covers
     * @param row the selected row index, or -1 for none
     * @param column the selected column index, or -1 for none
     */
    public record Selection(Mode mode, int row, int column) {

        /** Substitutes the default mode and keeps the absence representable. */
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
     * An immutable snapshot of a table: its columns, its rows and the widths the columns resolved to.
     *
     * <p>The widths are resolved once, from the columns and the rows, when the content is built.
     * Nothing else in this class measures a cell, so a table of a hundred thousand rows paints a
     * window in the time a table of ten does. Content is also where the schema's invariants are
     * enforced: two columns may not share a key and two rows may not share an id.</p>
     *
     * <p>A content value is compared by its columns and rows. The header text is measured even when
     * {@link Config#header()} paints no header, so toggling the header can never reflow the
     * columns.</p>
     */
    public static final class Content {

        private static final Content EMPTY = new Content(List.of(), List.of());

        private final List<Column> columns;
        private final List<Row> rows;
        private final List<Integer> widths;
        private final int rowWidth;

        private Content(List<Column> columns, List<Row> rows) {
            this.columns = columns;
            this.rows = rows;
            this.widths = measured(columns, rows);
            this.rowWidth = paintedWidth(this.widths);
        }

        /**
         * Content with no columns and no rows.
         *
         * @return the empty content
         */
        public static Content empty() {
            return EMPTY;
        }

        /**
         * A snapshot of a schema and its rows.
         *
         * @param columns the columns, in paint order; null or empty is a table with no columns
         * @param rows the rows, in display order; null or empty is a table with no rows
         * @return the content
         * @throws IllegalArgumentException when two columns share a key or two rows share an id
         */
        public static Content of(List<Column> columns, List<Row> rows) {
            List<Column> schema = columns == null ? List.of() : List.copyOf(columns);
            List<Row> values = rows == null ? List.of() : List.copyOf(rows);
            requireUniqueColumns(schema);
            requireUniqueRows(values);
            return new Content(schema, values);
        }

        /**
         * The columns, in paint order.
         *
         * @return the columns
         */
        public List<Column> columns() {
            return columns;
        }

        /**
         * The rows, in display order.
         *
         * @return the rows
         */
        public List<Row> rows() {
            return rows;
        }

        /**
         * The cells each column resolved to, in column order.
         *
         * @return the column widths
         */
        public List<Integer> widths() {
            return widths;
        }

        /**
         * How many rows the content holds.
         *
         * @return the row count
         */
        public int rowCount() {
            return rows.size();
        }

        /**
         * How many columns the schema holds.
         *
         * @return the column count
         */
        public int columnCount() {
            return columns.size();
        }

        /**
         * The cells one row paints: every column's resolved width and the gap after it.
         *
         * @return the painted width of a row, never negative
         */
        public int rowWidth() {
            return rowWidth;
        }

        /**
         * The first painted cell one column occupies in a row.
         *
         * @param column the column index
         * @return the cell offset of the column, or zero when it does not exist
         */
        public int columnStart(int column) {
            if (column <= 0) {
                return 0;
            }
            long start = 0;
            for (int index = 0; index < column && index < widths.size(); index++) {
                start += widths.get(index) + GAP;
            }
            return (int) Math.min(Integer.MAX_VALUE, start);
        }

        /**
         * The index of a row.
         *
         * @param id the row id
         * @return the row's index, or -1 when the content does not hold it
         */
        public int indexOf(String id) {
            if (id == null) {
                return -1;
            }
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).id().equals(id)) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * The index of a column.
         *
         * @param key the column key
         * @return the column's index, or -1 when the schema does not hold it
         */
        public int indexOfColumn(String key) {
            if (key == null) {
                return -1;
            }
            for (int index = 0; index < columns.size(); index++) {
                if (columns.get(index).key().equals(key)) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * The same schema over different rows.
         *
         * @param value the rows, in display order; null is no rows
         * @return the content
         * @throws IllegalArgumentException when two of the new rows share an id
         */
        public Content withRows(List<Row> value) {
            return of(columns, value);
        }

        /**
         * The same rows under a different schema.
         *
         * @param value the columns, in paint order; null is no columns
         * @return the content
         * @throws IllegalArgumentException when two of the new columns share a key
         */
        public Content withColumns(List<Column> value) {
            return of(value, rows);
        }

        /**
         * The same content with one row inserted.
         *
         * @param index the position the row takes, clamped into {@code [0, rowCount()]}
         * @param value the row; required
         * @return the content
         * @throws IllegalArgumentException when the id is already taken
         */
        public Content inserted(int index, Row value) {
            Objects.requireNonNull(value, "row");
            int at = Math.max(0, Math.min(index, rows.size()));
            List<Row> grown = new ArrayList<>(rows.size() + 1);
            grown.addAll(rows.subList(0, at));
            grown.add(value);
            grown.addAll(rows.subList(at, rows.size()));
            return of(columns, grown);
        }

        /**
         * The same content with one row replaced, matched by id.
         *
         * @param value the new row; required
         * @return the content
         * @throws IllegalArgumentException when no row holds that id
         */
        public Content replaced(Row value) {
            Objects.requireNonNull(value, "row");
            int index = indexOf(value.id());
            if (index < 0) {
                throw new IllegalArgumentException("unknown row id: " + value.id());
            }
            List<Row> replaced = new ArrayList<>(rows);
            replaced.set(index, value);
            return of(columns, replaced);
        }

        /**
         * The same content without one row.
         *
         * @param id the id of the row to remove; required
         * @return the content
         * @throws IllegalArgumentException when no row holds that id
         */
        public Content removed(String id) {
            int index = indexOf(id);
            if (index < 0) {
                throw new IllegalArgumentException("unknown row id: " + id);
            }
            List<Row> fewer = new ArrayList<>(rows);
            fewer.remove(index);
            return of(columns, fewer);
        }

        /**
         * The value at one row and column of the schema.
         *
         * <p>A value a row holds beyond the schema is not part of the table, so it is not answered
         * here and never painted.</p>
         *
         * @param row the row index
         * @param column the column index
         * @return the value, or the empty value when either index names nothing
         */
        public StyledText cell(int row, int column) {
            return row >= 0 && row < rows.size() && column >= 0 && column < columns.size()
                    ? rows.get(row).cell(column)
                    : StyledText.empty();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Content that && columns.equals(that.columns) && rows.equals(that.rows);
        }

        @Override
        public int hashCode() {
            return 31 * columns.hashCode() + rows.hashCode();
        }

        @Override
        public String toString() {
            return "Content[" + rows.size() + " rows of " + columns.size() + " columns, " + rowWidth + " cells]";
        }

        /** Rejects two columns with one key. */
        private static void requireUniqueColumns(List<Column> columns) {
            Map<String, Integer> keys = new HashMap<>(columns.size() * 2);
            for (int index = 0; index < columns.size(); index++) {
                Column column = Objects.requireNonNull(columns.get(index), "column");
                Integer previous = keys.putIfAbsent(column.key(), index);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate column key: " + column.key());
                }
            }
        }

        /** Rejects two rows with one id. */
        private static void requireUniqueRows(List<Row> rows) {
            Map<String, Integer> ids = new HashMap<>(rows.size() * 2);
            for (int index = 0; index < rows.size(); index++) {
                Row row = Objects.requireNonNull(rows.get(index), "row");
                Integer previous = ids.putIfAbsent(row.id(), index);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate row id: " + row.id());
                }
            }
        }

        /** The cells each column resolves to: the header and its widest cell, inside its policy. */
        private static List<Integer> measured(List<Column> columns, List<Row> rows) {
            int count = columns.size();
            int[] widest = new int[count];
            for (int column = 0; column < count; column++) {
                widest[column] = GridRows.widest(
                        singleRow(StyledText.plain(columns.get(column).header())));
            }
            for (Row row : rows) {
                int cells = Math.min(count, row.cells().size());
                for (int column = 0; column < cells; column++) {
                    widest[column] = Math.max(
                            widest[column],
                            GridRows.widest(singleRow(row.cells().get(column))));
                }
            }
            List<Integer> resolved = new ArrayList<>(count);
            for (int column = 0; column < count; column++) {
                resolved.add(columns.get(column).width().resolve(widest[column]));
            }
            return List.copyOf(resolved);
        }

        /** What one row paints: every column's width and the gap after it. */
        private static int paintedWidth(List<Integer> widths) {
            long total = 0;
            for (int width : widths) {
                total += width + GAP;
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
    }

    /**
     * Table state: the content, the selection, the window and the geometry the parent allocated.
     *
     * <p>The canonical constructor keeps the state representable: a selection outside the content
     * falls on the first row or column, and the window's offsets and geometry are never negative.
     * What needs the content's own geometry - the last window and the last pan - is clamped when the
     * window is painted or a message is reduced, so a hand-built state is still paintable.</p>
     *
     * @param content the columns and rows, and the widths they resolved to
     * @param selection the selected row and cell
     * @param top the index of the first body row the window shows
     * @param firstColumn the first painted cell the window shows
     * @param height the rows the parent allocated, header included
     * @param width the cells the parent allocated
     * @param focused whether the table owns focus
     */
    public record Model(
            Content content, Selection selection, int top, int firstColumn, int height, int width, boolean focused) {

        /** Keeps the state representable and every value non-null. */
        public Model {
            content = content == null ? Content.empty() : content;
            Selection value = selection == null ? Selection.none() : selection;
            int rows = content.rowCount();
            int columns = content.columnCount();
            selection = new Selection(
                    value.mode(),
                    rows <= 0 ? -1 : Math.max(0, Math.min(value.row(), rows - 1)),
                    columns <= 0 ? -1 : Math.max(0, Math.min(value.column(), columns - 1)));
            top = Math.max(0, Math.min(top, Math.max(0, rows - 1)));
            height = Math.max(0, height);
            width = Math.max(0, width);
            firstColumn = Math.max(0, Math.min(firstColumn, Math.max(0, content.rowWidth() - width)));
        }

        /**
         * A table showing the first row of some content.
         *
         * @param content the columns and rows; null is empty content
         * @return the state
         */
        public static Model of(Content content) {
            Content values = content == null ? Content.empty() : content;
            boolean present = values.rowCount() > 0 && values.columnCount() > 0;
            return new Model(
                    values,
                    new Selection(Mode.ROW, present ? 0 : -1, present ? 0 : -1),
                    0,
                    0,
                    DEFAULT_HEIGHT,
                    DEFAULT_WIDTH,
                    true);
        }

        /**
         * The same state over different content, with the selected row carried over by id.
         *
         * <p>A selection the new content does not hold falls on the row that takes its place, or on
         * the last row when it was the last one; a table that was empty selects the new first row.</p>
         *
         * @param value the new content; null is empty content
         * @return the new state
         */
        public Model withContent(Content value) {
            Content next = value == null ? Content.empty() : value;
            Row selected = selectedRow().orElse(null);
            int row = -1;
            if (next.rowCount() > 0) {
                row = selected == null ? 0 : next.indexOf(selected.id());
                if (row < 0) {
                    row = Math.max(0, Math.min(selection.row(), next.rowCount() - 1));
                }
            }
            return new Model(
                    next,
                    new Selection(selection.mode(), row, selection.column()),
                    top,
                    firstColumn,
                    height,
                    width,
                    focused);
        }

        /**
         * The same content with a different selection.
         *
         * <p>The window is not moved here: a projection reveals the selection, so a state built this
         * way still paints it.</p>
         *
         * @param value the selection; null is no selection
         * @return the new state
         */
        public Model withSelection(Selection value) {
            return new Model(content, value, top, firstColumn, height, width, focused);
        }

        /**
         * The same state with a different focus.
         *
         * <p>A reduction never changes focus; the application owns it, exactly as it does for {@link
         * Editor} and {@link TextViewport}.</p>
         *
         * @param value whether the table owns focus
         * @return this instance when the focus did not change
         */
        public Model withFocus(boolean value) {
            return value == focused ? this : new Model(content, selection, top, firstColumn, height, width, value);
        }

        /**
         * The same state at a different geometry.
         *
         * @param rows the rows the parent allocated; negative is zero
         * @param cells the cells the parent allocated; negative is zero
         * @return the new state
         */
        public Model withGeometry(int rows, int cells) {
            return new Model(content, selection, top, firstColumn, rows, cells, focused);
        }

        /**
         * The selected row.
         *
         * @return the row, or empty when nothing is selected
         */
        public Optional<Row> selectedRow() {
            int row = selection.row();
            return row >= 0 && row < content.rowCount()
                    ? Optional.of(content.rows().get(row))
                    : Optional.empty();
        }

        /**
         * The selected column.
         *
         * @return the column, or empty when the schema is empty
         */
        public Optional<Column> selectedColumn() {
            int column = selection.column();
            return column >= 0 && column < content.columnCount()
                    ? Optional.of(content.columns().get(column))
                    : Optional.empty();
        }

        /**
         * The selected value.
         *
         * @return the value, or empty when nothing is selected
         */
        public Optional<StyledText> selectedValue() {
            int row = selection.row();
            int column = selection.column();
            if (row < 0 || row >= content.rowCount() || column < 0 || column >= content.columnCount()) {
                return Optional.empty();
            }
            return Optional.of(content.cell(row, column));
        }
    }

    /** One navigation, presentation or content message. */
    public sealed interface Msg {

        /** Selects the row above, revealing it. */
        record Up() implements Msg {}

        /** Selects the row below, revealing it. */
        record Down() implements Msg {}

        /**
         * Selects the column before the selected one, in {@link Mode#CELL}; the gesture does nothing
         * in {@link Mode#ROW}, where the selection covers the whole row.
         */
        record Left() implements Msg {}

        /**
         * Selects the column after the selected one, in {@link Mode#CELL}; the gesture does nothing
         * in {@link Mode#ROW}, where the selection covers the whole row.
         */
        record Right() implements Msg {}

        /** Selects the row one window below, revealing it. */
        record PageDown() implements Msg {}

        /** Selects the row one window above, revealing it. */
        record PageUp() implements Msg {}

        /** Selects the row half a window below, revealing it. */
        record HalfPageDown() implements Msg {}

        /** Selects the row half a window above, revealing it. */
        record HalfPageUp() implements Msg {}

        /** Selects the first row, revealing it. */
        record Top() implements Msg {}

        /** Selects the last row, revealing it. */
        record Bottom() implements Msg {}

        /**
         * Pans towards the first cell.
         *
         * <p>The count is never negative and zero changes nothing. A {@link Mode#CELL} selection
         * stays visible, so the pan stops where the selected cell's right edge would leave the
         * window.</p>
         *
         * @param columns how many cells to show from further left
         */
        record PanLeft(int columns) implements Msg {
            public PanLeft {
                columns = Math.max(0, columns);
            }
        }

        /**
         * Pans towards the last cell.
         *
         * <p>The count is never negative and zero changes nothing. A {@link Mode#CELL} selection
         * stays visible, so the pan stops where the selected cell's left edge would leave the
         * window.</p>
         *
         * @param columns how many more cells to show from further right
         */
        record PanRight(int columns) implements Msg {
            public PanRight {
                columns = Math.max(0, columns);
            }
        }

        /** Switches the selection between a whole row and one cell of it. */
        record ToggleMode() implements Msg {}

        /**
         * Selects one of the two selection modes.
         *
         * @param mode the mode; required
         */
        record SetMode(Mode mode) implements Msg {
            public SetMode {
                Objects.requireNonNull(mode, "mode");
            }
        }

        /**
         * Selects one row by id, revealing it.
         *
         * @param rowId the row id; required
         */
        record Select(String rowId) implements Msg {
            public Select {
                Objects.requireNonNull(rowId, "rowId");
            }
        }

        /**
         * Selects one cell: its row, and the column the pointer was over.
         *
         * <p>This is what a click on a cellwise table means. A row-mode table has no column to
         * select, so a click there asks for {@link Select} alone.</p>
         *
         * @param rowId the id of the row to select; required
         * @param column the column index to select; clamped into the schema
         */
        record SelectCell(String rowId, int column) implements Msg {

            /** Rejects no id; an unknown one is rejected by the reduction. */
            public SelectCell {
                rowId = rowId == null ? "" : rowId;
            }
        }

        /** Reports the selection to the application as an {@link Output.Chosen}, if there is one. */
        record Choose() implements Msg {}

        /** Reports a cancelled table as an {@link Output.Cancelled}. */
        record Cancel() implements Msg {}

        /**
         * Reports the geometry the parent allocated.
         *
         * <p>Negative values become zero, which paints and scrolls nothing. The window keeps its
         * offsets as far as the new geometry allows and the selection stays visible.</p>
         *
         * @param height the rows the parent allocated, header included
         * @param width the cells the parent allocated
         */
        record Resized(int height, int width) implements Msg {
            public Resized {
                height = Math.max(0, height);
                width = Math.max(0, width);
            }
        }

        /**
         * Replaces every row, keeping the selected row's id selected.
         *
         * @param rows the new rows, in display order; null is no rows
         */
        record SetRows(List<Row> rows) implements Msg {
            public SetRows {
                rows = rows == null ? List.of() : List.copyOf(rows);
            }
        }

        /**
         * Inserts one row.
         *
         * @param index the position the row takes, clamped into the table
         * @param row the row; required
         */
        record Insert(int index, Row row) implements Msg {
            public Insert {
                Objects.requireNonNull(row, "row");
            }
        }

        /**
         * Replaces one row, matched by id.
         *
         * @param row the new row; required
         */
        record Replace(Row row) implements Msg {
            public Replace {
                Objects.requireNonNull(row, "row");
            }
        }

        /**
         * Removes one row, matched by id.
         *
         * @param rowId the id of the row to remove; required
         */
        record Remove(String rowId) implements Msg {
            public Remove {
                Objects.requireNonNull(rowId, "rowId");
            }
        }
    }

    /** What the application should do after an action. */
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
         */
        record Chosen(String rowId, int row, String columnKey, int column, StyledText value) implements Output {
            public Chosen {
                rowId = rowId == null ? "" : rowId;
                columnKey = columnKey == null ? "" : columnKey;
                value = value == null ? StyledText.empty() : value;
            }
        }

        /** The reader left the table without choosing anything. */
        record Cancelled() implements Output {}
    }

    /**
     * Immutable table definition.
     *
     * <p>It holds what does not change while the table runs: the content it starts with, whether a
     * header is painted, how cells are painted, the fallback geometry and the rows and columns the
     * application paints outside the table.</p>
     *
     * @param content the initial columns and rows
     * @param mode the selection mode the state starts in
     * @param header whether a header line is painted
     * @param theme how header cells, rows and cells are painted
     * @param activeStyle the style merged over the selection while the table is focused
     * @param inactiveStyle the style merged over the selection while it is not
     * @param height the rows used before the terminal's own geometry arrives
     * @param width the cells used before the terminal's own geometry arrives
     * @param reservedRows the rows the application paints outside the table
     * @param reservedColumns the columns the application paints outside the table
     */
    public record Config(
            Content content,
            Mode mode,
            boolean header,
            Theme theme,
            Style activeStyle,
            Style inactiveStyle,
            int height,
            int width,
            int reservedRows,
            int reservedColumns) {

        /** Keeps the definition paintable. */
        public Config {
            content = content == null ? Content.empty() : content;
            mode = mode == null ? Mode.ROW : mode;
            theme = theme == null ? Theme.plain() : theme;
            activeStyle = activeStyle == null ? Style.NONE : activeStyle;
            inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
            height = Math.max(0, height);
            width = Math.max(0, width);
            reservedRows = Math.max(0, reservedRows);
            reservedColumns = Math.max(0, reservedColumns);
        }

        /**
         * A row-selecting table with a header, an inverting selection and no content.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(
                    Content.empty(),
                    Mode.ROW,
                    true,
                    Theme.plain(),
                    Style.NONE.withInvert(),
                    Style.NONE,
                    DEFAULT_HEIGHT,
                    DEFAULT_WIDTH,
                    0,
                    0);
        }

        /**
         * The same definition over different content.
         *
         * @param value the content
         * @return the new definition
         */
        public Config withContent(Content value) {
            return new Config(
                    value,
                    mode,
                    header,
                    theme,
                    activeStyle,
                    inactiveStyle,
                    height,
                    width,
                    reservedRows,
                    reservedColumns);
        }

        /**
         * The same definition starting in a different selection mode.
         *
         * @param value the mode
         * @return the new definition
         */
        public Config withMode(Mode value) {
            return new Config(
                    content,
                    value,
                    header,
                    theme,
                    activeStyle,
                    inactiveStyle,
                    height,
                    width,
                    reservedRows,
                    reservedColumns);
        }

        /**
         * The same definition with or without a header.
         *
         * @param value whether a header is painted
         * @return the new definition
         */
        public Config withHeader(boolean value) {
            return new Config(
                    content,
                    mode,
                    value,
                    theme,
                    activeStyle,
                    inactiveStyle,
                    height,
                    width,
                    reservedRows,
                    reservedColumns);
        }

        /**
         * The same definition with a different theme.
         *
         * @param value the theme; null is {@link Theme#plain()}
         * @return the new definition
         */
        public Config withTheme(Theme value) {
            return new Config(
                    content,
                    mode,
                    header,
                    value,
                    activeStyle,
                    inactiveStyle,
                    height,
                    width,
                    reservedRows,
                    reservedColumns);
        }

        /**
         * The same definition with different selection styles.
         *
         * @param active the style merged over a focused table's selection
         * @param inactive the style merged over an unfocused table's selection
         * @return the new definition
         */
        public Config withSelectionStyles(Style active, Style inactive) {
            return new Config(
                    content, mode, header, theme, active, inactive, height, width, reservedRows, reservedColumns);
        }

        /**
         * The same definition with a different fallback height.
         *
         * @param value the fallback rows
         * @return the new definition
         */
        public Config withHeight(int value) {
            return new Config(
                    content,
                    mode,
                    header,
                    theme,
                    activeStyle,
                    inactiveStyle,
                    value,
                    width,
                    reservedRows,
                    reservedColumns);
        }

        /**
         * The same definition with a different fallback width.
         *
         * @param value the fallback cells
         * @return the new definition
         */
        public Config withWidth(int value) {
            return new Config(
                    content,
                    mode,
                    header,
                    theme,
                    activeStyle,
                    inactiveStyle,
                    height,
                    value,
                    reservedRows,
                    reservedColumns);
        }

        /**
         * The same definition with a different number of reserved rows.
         *
         * @param value the rows the application paints outside the table
         * @return the new definition
         */
        public Config withReservedRows(int value) {
            return new Config(
                    content, mode, header, theme, activeStyle, inactiveStyle, height, width, value, reservedColumns);
        }

        /**
         * The same definition with a different number of reserved columns.
         *
         * @param value the columns the application paints outside the table
         * @return the new definition
         */
        public Config withReservedColumns(int value) {
            return new Config(
                    content, mode, header, theme, activeStyle, inactiveStyle, height, width, reservedRows, value);
        }
    }

    /** A reduction result. */
    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    private final Config config;

    /**
     * Creates the component.
     *
     * @param config the immutable definition; required
     */
    public DataTable(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The definition this table was built from.
     *
     * @return the definition
     */
    public Config config() {
        return config;
    }

    /**
     * The gestures a table defines, before the state is taken into account.
     *
     * <p>They are the table's own: rows and pages vertically, columns in {@link Mode#CELL}, cells
     * horizontally, the mode switch, choosing and cancelling. {@link #keys(Model)} is the map for one
     * state - it drops the column gestures in {@link Mode#ROW} and the pan when nothing is clipped -
     * and it is what the component routes and what help renders.</p>
     *
     * @return the default gestures
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191/k", "up a row", "up", "k"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193/j", "down a row", "down", "j"),
                        KeyAction.<Msg>of("left", new Msg.Left(), "\u2190", "left a column", "left"),
                        KeyAction.<Msg>of("right", new Msg.Right(), "\u2192", "right a column", "right")),
                KeyMap.group(
                        "Page",
                        KeyAction.<Msg>of("page-up", new Msg.PageUp(), "b/pgup", "page up", "pageup", "b"),
                        KeyAction.<Msg>of("page-down", new Msg.PageDown(), "f/pgdn", "page down", "pagedown", "f"),
                        KeyAction.<Msg>of("half-page-up", new Msg.HalfPageUp(), "u", "half page up", "u")
                                .withCompact(false),
                        KeyAction.<Msg>of("half-page-down", new Msg.HalfPageDown(), "d", "half page down", "d")
                                .withCompact(false),
                        KeyAction.<Msg>of("top", new Msg.Top(), "home", "first row", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("bottom", new Msg.Bottom(), "end", "last row", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Pan",
                        KeyAction.<Msg>of("pan-left", new Msg.PanLeft(1), "h", "pan left", "h"),
                        KeyAction.<Msg>of("pan-right", new Msg.PanRight(1), "l", "pan right", "l")),
                KeyMap.group(
                        "Select",
                        KeyAction.<Msg>of("mode", new Msg.ToggleMode(), "m", "row/cell", "m"),
                        KeyAction.<Msg>of("choose", new Msg.Choose(), "enter", "choose", "enter", "alt+enter"),
                        KeyAction.<Msg>of("cancel", new Msg.Cancel(), "esc", "cancel", "escape")));
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
    public static KeyMap<Msg> keys(Model state) {
        Objects.requireNonNull(state, "state");
        boolean cells = state.selection().cellwise();
        boolean clipped = state.content().rowWidth() > state.width();
        return defaultKeys()
                .withEnabled("left", cells)
                .withEnabled("right", cells)
                .withEnabled("pan-left", clipped)
                .withEnabled("pan-right", clipped);
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        // The startup geometry is the only size the table can know before a resize arrives: what the
        // application paints outside it is taken out of the terminal's own geometry, and the
        // configured fallback is what a headless test or a preview uses.
        int height = context.viewport().rows() > 0
                ? Math.max(0, context.viewport().rows() - config.reservedRows())
                : config.height();
        int width = context.viewport().cols() > 0
                ? Math.max(0, context.viewport().cols() - config.reservedColumns())
                : config.width();
        Content content = config.content();
        boolean present = content.rowCount() > 0 && content.columnCount() > 0;
        Selection selection = new Selection(config.mode(), present ? 0 : -1, present ? 0 : -1);
        return Update.next(new Model(content, selection, 0, 0, height, width, true));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, config, action), state -> state.withFocus(model.focused()));
    }

    /**
     * Describes the window this state covers.
     *
     * <p>The context is deliberately unused: its viewport is the one the table's parent lays out for,
     * not the table's own geometry. {@link Model#height()} and {@link Model#width()} are what the
     * parent reserved and reported, and they are the only geometry a window may use.</p>
     */
    @Override
    public View<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        List<View<Msg>> rows = new ArrayList<>();
        for (Line line : lines(model, config)) {
            // One fixed row per painted line: a line wider than the table would otherwise claim a
            // second screen row and move everything below it out of the window.
            rows.add(Views.fixed(1, Views.text(line.text())));
        }
        return Views.focus(
                ELEMENT,
                model.focused(),
                keys(model).bindings(),
                View.InputHandler.none(),
                pointer(model),
                Views.column(rows));
    }

    /**
     * The table's pointer handler: a click selects the row it landed on - or the cell, in a cellwise
     * table - a second click chooses what the first selected, and the wheel steps the selection.
     *
     * <p>The row is found the way {@link #lines} paints it: the header row first when the definition
     * paints one, then the body rows of the window from {@link #top}. A click on the header, or on a
     * row past the content, selects nothing.</p>
     */
    private View.PointerHandler<Msg> pointer(Model model) {
        return (event, region) -> {
            if (event.isWheel()) {
                return Optional.of(
                        switch (event.button()) {
                            case WHEEL_UP, WHEEL_LEFT -> new Msg.Up();
                            default -> new Msg.Down();
                        });
            }
            if (!event.isLeftPress()) {
                return Optional.empty();
            }
            int row = bodyRowAt(model, config, event.row());
            if (row < 0) {
                return Optional.empty();
            }
            String id = model.content().rows().get(row).id();
            if (event.clickCount() > 1) {
                return Optional.of(new Msg.Choose());
            }
            return Optional.of(
                    model.selection().cellwise()
                            ? new Msg.SelectCell(id, columnAt(model, event.column()))
                            : new Msg.Select(id));
        };
    }

    /** The content row index painted on one row of the table, or -1 for a chrome or blank row. */
    private static int bodyRowAt(Model state, Config config, int row) {
        Config opts = config == null ? Config.defaults() : config;
        int header = opts.header() && state.content().columnCount() > 0 ? 1 : 0;
        int body = row - header;
        if (body < 0 || body >= bodyHeight(state, opts)) {
            return -1;
        }
        int index = top(state, opts) + body;
        return index < state.content().rowCount() ? index : -1;
    }

    /** The column index one painted cell falls in, or 0 when the cells do not reach it. */
    private static int columnAt(Model state, int column) {
        Content content = state.content();
        for (int index = 0; index < content.columnCount(); index++) {
            int start = content.columnStart(index);
            if (column >= start && column < start + content.widths().get(index)) {
                return index;
            }
        }
        return 0;
    }

    // ── projections ──────────────────────────────────────────────────────────

    /**
     * The body rows a window of this geometry holds.
     *
     * <p>It is the allocated height less the header line {@link Config#header()} may paint, which is
     * what a page gesture moves by and what a parent's own layout arithmetic needs.</p>
     *
     * @param state the current state; required
     * @param config the definition whose header flag applies; null is {@link Config#defaults()}
     * @return the body rows the window holds, never negative
     */
    public static int bodyHeight(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        int header = opts.header() && state.content().columnCount() > 0 ? 1 : 0;
        return Math.max(0, state.height() - header);
    }

    /**
     * The lines the window paints, in paint order.
     *
     * <p>The header comes first when the definition paints one, and at most {@link #bodyHeight} body
     * rows follow it. Every line is what the table paints for it: the horizontal window already cut,
     * the cells already padded to their columns and the styles already applied. A line is never
     * wider than {@link Model#width()}.</p>
     *
     * @param state the current state; required
     * @param config the definition whose header, theme and styles apply; null is
     *     {@link Config#defaults()}
     * @return the painted lines, empty when the table has no columns or no geometry
     */
    public static List<Line> lines(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        Content content = state.content();
        if (content.columnCount() == 0 || state.width() == 0 || state.height() == 0) {
            return List.of();
        }
        // The horizontal window is one number for every row, so it is resolved once per frame
        // rather than once per painted row.
        int firstColumn = effectiveFirstColumn(state, opts);
        List<Line> lines = new ArrayList<>();
        if (opts.header()) {
            lines.add(new Line(true, -1, paint(state, opts, -1, headerValues(content), true, firstColumn)));
        }
        int top = top(state, opts);
        int body = bodyHeight(state, opts);
        for (int index = 0; index < body && top + index < content.rowCount(); index++) {
            lines.add(new Line(false, top + index, paint(state, opts, top + index, null, false, firstColumn)));
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
     *     {@link Config#defaults()}
     * @return the first visible body row, never negative
     */
    public static int top(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        return revealedTop(state, config == null ? Config.defaults() : config);
    }

    /**
     * The first painted cell the window shows.
     *
     * @param state the current state; required
     * @param config the definition the window's geometry is read from; null is
     *     {@link Config#defaults()}
     * @return the horizontal offset, never negative
     */
    public static int firstColumn(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        return effectiveFirstColumn(state, config == null ? Config.defaults() : config);
    }

    /**
     * The cells one row paints.
     *
     * @param state the current state; required
     * @return the painted width of a row, never negative
     */
    public static int rowWidth(Model state) {
        Objects.requireNonNull(state, "state");
        return state.content().rowWidth();
    }

    /**
     * The first painted cell one column occupies.
     *
     * @param state the current state; required
     * @param column the column index
     * @return the cell offset of the column, or zero when it does not exist
     */
    public static int columnStart(Model state, int column) {
        Objects.requireNonNull(state, "state");
        return state.content().columnStart(column);
    }

    // ── reduction ────────────────────────────────────────────────────────────

    /** Applies one message. Never performs I/O, never mutates the input state. */
    private static Result reduce(Model state, Config config, Msg message) {
        return switch (message) {
            case Msg.Up up ->
                new Result(selected(state, config, state.selection().row() - 1), null);
            case Msg.Down down ->
                new Result(selected(state, config, state.selection().row() + 1), null);
            case Msg.PageUp pageUp -> new Result(movedBy(state, config, -bodyHeight(state, config)), null);
            case Msg.PageDown pageDown -> new Result(movedBy(state, config, bodyHeight(state, config)), null);
            case Msg.HalfPageUp half -> new Result(movedBy(state, config, -(bodyHeight(state, config) / 2)), null);
            case Msg.HalfPageDown half -> new Result(movedBy(state, config, bodyHeight(state, config) / 2), null);
            case Msg.Top top -> new Result(selected(state, config, 0), null);
            case Msg.Bottom bottom ->
                new Result(selected(state, config, state.content().rowCount() - 1), null);
            case Msg.Left left -> new Result(columnsMoved(state, config, -1), null);
            case Msg.Right right -> new Result(columnsMoved(state, config, 1), null);
            case Msg.PanLeft pan -> new Result(panned(state, config, -pan.columns()), null);
            case Msg.PanRight pan -> new Result(panned(state, config, pan.columns()), null);
            case Msg.ToggleMode toggle ->
                new Result(
                        modeChanged(state, config, state.selection().mode() == Mode.ROW ? Mode.CELL : Mode.ROW), null);
            case Msg.SetMode mode -> new Result(modeChanged(state, config, mode.mode()), null);
            case Msg.Select select -> new Result(selectRequested(state, config, select.rowId()), null);
            case Msg.SelectCell cell ->
                new Result(selectCellRequested(state, config, cell.rowId(), cell.column()), null);
            case Msg.Choose choose -> new Result(state, chosen(state));
            case Msg.Cancel cancel -> new Result(state, new Output.Cancelled());
            case Msg.Resized resized -> new Result(resized(state, config, resized.height(), resized.width()), null);
            case Msg.SetRows rows -> new Result(rowsChanged(state, config, rows.rows()), null);
            case Msg.Insert insert -> new Result(inserted(state, config, insert.index(), insert.row()), null);
            case Msg.Replace replace -> new Result(replaced(state, config, replace.row()), null);
            case Msg.Remove remove -> new Result(removed(state, config, remove.rowId()), null);
        };
    }

    /** The state with one row selected, the row revealed and the geometry unchanged. */
    private static Model selected(Model state, Config config, int row) {
        int rows = state.content().rowCount();
        int target = rows <= 0 ? -1 : Math.max(0, Math.min(row, rows - 1));
        Model moved = new Model(
                state.content(),
                new Selection(
                        state.selection().mode(), target, state.selection().column()),
                state.top(),
                state.firstColumn(),
                state.height(),
                state.width(),
                state.focused());
        return revealed(moved, config);
    }

    /** The state with the selection moved by rows, which is what a page gesture does. */
    private static Model movedBy(Model state, Config config, int rows) {
        int count = state.content().rowCount();
        if (count == 0) {
            return state;
        }
        int current = Math.max(0, state.selection().row());
        int maxTop = Math.max(0, count - bodyHeight(state, config));
        // A page moves the window as well as the selection: a page that only moved the selection
        // would reveal one new row and hide one, which is not what a reader asking for the next
        // page wants. Both are clamped to the content, so the last page lands on the last window.
        int selection = Math.max(0, Math.min(current + rows, count - 1));
        int top = Math.max(0, Math.min(revealedTop(state, config) + rows, maxTop));
        Model moved = new Model(
                state.content(),
                new Selection(
                        state.selection().mode(), selection, state.selection().column()),
                top,
                state.firstColumn(),
                state.height(),
                state.width(),
                state.focused());
        return revealed(moved, config);
    }

    /** The state with the selected column moved, which only a cell selection does. */
    private static Model columnsMoved(Model state, Config config, int columns) {
        if (!state.selection().cellwise() || state.content().columnCount() == 0) {
            return state;
        }
        int current = state.selection().column();
        int target = Math.max(
                0,
                Math.min((current < 0 ? 0 : current) + columns, state.content().columnCount() - 1));
        Model moved = new Model(
                state.content(),
                new Selection(Mode.CELL, state.selection().row(), target),
                state.top(),
                state.firstColumn(),
                state.height(),
                state.width(),
                state.focused());
        return revealed(moved, config);
    }

    /** The state panned by cells, clamped to the content and to the selected cell. */
    private static Model panned(Model state, Config config, int columns) {
        if (columns == 0) {
            return state;
        }
        int max = Math.max(0, state.content().rowWidth() - state.width());
        int current = effectiveFirstColumn(state, config);
        int target = (int) Math.max(0L, Math.min((long) max, (long) current + columns));
        if (target == current && state.firstColumn() == current) {
            return state;
        }
        Model moved = new Model(
                state.content(),
                state.selection(),
                state.top(),
                target,
                state.height(),
                state.width(),
                state.focused());
        // A cell selection keeps its cell on screen, so the pan is re-clamped; a row selection has
        // the whole row selected and is free to reach any of its cells.
        return revealed(moved, config);
    }

    /** The same state in another selection mode. */
    private static Model modeChanged(Model state, Config config, Mode mode) {
        if (state.selection().mode() == mode) {
            return state;
        }
        Model changed = new Model(
                state.content(),
                new Selection(mode, state.selection().row(), state.selection().column()),
                state.top(),
                state.firstColumn(),
                state.height(),
                state.width(),
                state.focused());
        return revealed(changed, config);
    }

    /** The same state with one row selected by id. */
    private static Model selectRequested(Model state, Config config, String rowId) {
        int row = state.content().indexOf(rowId);
        if (row < 0) {
            throw new IllegalArgumentException("unknown row id: " + rowId);
        }
        return selected(state, config, row);
    }

    /** The state with one cell selected: the row, and the column clicked in a cellwise table. */
    private static Model selectCellRequested(Model state, Config config, String rowId, int column) {
        int row = state.content().indexOf(rowId);
        if (row < 0) {
            throw new IllegalArgumentException("unknown row id: " + rowId);
        }
        Model selected = selected(state, config, row);
        if (!selected.selection().cellwise()) {
            return selected;
        }
        int columns = selected.content().columnCount();
        int target = Math.max(0, Math.min(column, columns - 1));
        return new Model(
                selected.content(),
                new Selection(selected.selection().mode(), selected.selection().row(), target),
                selected.top(),
                selected.firstColumn(),
                selected.height(),
                selected.width(),
                selected.focused());
    }

    /** The new geometry, with the window clamped to it and the selection revealed. */
    private static Model resized(Model state, Config config, int height, int width) {
        return revealed(state.withGeometry(height, width), config);
    }

    /** Every row replaced, with the selected id carried over. */
    private static Model rowsChanged(Model state, Config config, List<Row> rows) {
        return afterData(state, config, state.content().withRows(rows));
    }

    /** One row inserted, with the selected id carried over. */
    private static Model inserted(Model state, Config config, int index, Row row) {
        return afterData(state, config, state.content().inserted(index, row));
    }

    /** One row replaced by id, with the selected id carried over. */
    private static Model replaced(Model state, Config config, Row row) {
        return afterData(state, config, state.content().replaced(row));
    }

    /** One row removed, with the selection falling on the nearest survivor. */
    private static Model removed(Model state, Config config, String rowId) {
        return afterData(state, config, state.content().removed(rowId));
    }

    /**
     * The state over new content, with the selected row carried over by id and the window revealed.
     *
     * <p>The identity rule is here rather than in {@link Content}, which knows nothing about a
     * selection: a row that is still there keeps its selection wherever it moved to, and a row that
     * is gone hands the selection to the row that took its place.</p>
     */
    private static Model afterData(Model state, Config config, Content content) {
        return revealed(state.withContent(content), config);
    }

    /** The state with the window moved the least it can to show the selection. */
    private static Model revealed(Model state, Config config) {
        int top = revealedTop(state, config);
        int first = effectiveFirstColumn(state, config);
        if (top == state.top() && first == state.firstColumn()) {
            return state;
        }
        return new Model(
                state.content(), state.selection(), top, first, state.height(), state.width(), state.focused());
    }

    /** The intent one choose gesture produces, or null when there is nothing to choose. */
    private static Output chosen(Model state) {
        Row row = state.selectedRow().orElse(null);
        Column column = state.selectedColumn().orElse(null);
        if (row == null || column == null) {
            return null;
        }
        if (!state.selection().cellwise()) {
            return new Output.Chosen(row.id(), state.selection().row(), "", -1, StyledText.empty());
        }
        int index = state.selection().column();
        return new Output.Chosen(row.id(), state.selection().row(), column.key(), index, row.cell(index));
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    /** The first visible body row: the stored offset clamped and revealed. */
    private static int revealedTop(Model state, Config config) {
        int body = bodyHeight(state, config);
        int rows = state.content().rowCount();
        int max = Math.max(0, rows - body);
        int top = Math.min(Math.max(0, state.top()), max);
        int selected = state.selection().row();
        if (body <= 0 || selected < 0) {
            return top;
        }
        if (selected < top) {
            return selected;
        }
        return selected >= top + body ? Math.min(max, selected - body + 1) : top;
    }

    /**
     * The horizontal offset the window really paints at.
     *
     * <p>It is the stored offset clamped to the content and, in {@link Mode#CELL}, kept inside the
     * range that still shows the selected cell. A table that fits paints at zero.</p>
     */
    private static int effectiveFirstColumn(Model state, Config config) {
        Content content = state.content();
        int width = Math.max(0, state.width());
        int max = Math.max(0, content.rowWidth() - width);
        int offset = Math.min(Math.max(0, state.firstColumn()), max);
        int column = state.selection().column();
        if (!state.selection().cellwise() || column < 0) {
            return offset;
        }
        int start = content.columnStart(column);
        int visible = content.widths().get(column) + GAP;
        int highest = Math.min(max, start);
        // A cell wider than the window keeps its left edge on screen: no offset shows all of it, and
        // the reader is better served by the cell's start than by its end.
        int lowest = Math.min(highest, Math.max(0, start + visible - width));
        return Math.max(lowest, Math.min(offset, highest));
    }

    // ── painting ─────────────────────────────────────────────────────────────

    /** The header's values, one per column. */
    private static List<StyledText> headerValues(Content content) {
        List<StyledText> values = new ArrayList<>(content.columnCount());
        for (Column column : content.columns()) {
            values.add(StyledText.plain(column.header()));
        }
        return values;
    }

    /**
     * One line as the table paints it: every column padded to its width, the horizontal window cut
     * and the result padded to the cells the window shows.
     */
    private static StyledText paint(
            Model state, Config config, int row, List<StyledText> header, boolean headings, int firstColumn) {
        Content content = state.content();
        int columns = content.columnCount();
        Style selection = state.focused() ? config.activeStyle() : config.inactiveStyle();
        // The row's own style is asked for once, not once per cell: a theme that styles a whole row
        // should not be told about it four times.
        Style rowStyle =
                headings ? Style.NONE : config.theme().row(content.rows().get(row), row);
        GridRows.Row charge = GridRows.Row.empty();
        StyledText line = StyledText.empty();
        Style last = Style.NONE;
        for (int column = 0; column < columns; column++) {
            Column schema = content.columns().get(column);
            int width = content.widths().get(column);
            StyledText value = headings ? header.get(column) : content.cell(row, column);
            Style style = headings ? config.theme().header(schema) : style(state, config, row, column, rowStyle);
            if (!headings && isSelected(state, row, column)) {
                style = (style == null ? Style.NONE : style).merge(selection);
            }
            StyledText fitted = clipped(value, width);
            int used = TextMeasure.columns(fitted.plainText());
            int leading = leading(schema.alignment(), width, used);
            // The cell is charged to the row it is painted in, so a cluster with no base of its own
            // joins the pad beside it instead of starting a cell of its own - the rule the static
            // table, the selector and markup share - and a row keeps its columns lined up with the
            // header above it.
            int left = charge.pad("", leading);
            int trailing = charge.pad(fitted.plainText(), Math.max(0, width - leading)) + charge.pad("", GAP);
            line = line.append(StyledText.of(" ".repeat(left), style))
                    .append(fitted.styled(0, fitted.length(), style))
                    .append(StyledText.of(" ".repeat(trailing), style));
            last = style;
        }
        return window(state, line, last, firstColumn);
    }

    /** The share of a cell's padding that precedes its value. */
    private static int leading(Alignment alignment, int width, int used) {
        int pad = Math.max(0, width - used);
        return switch (alignment) {
            case LEFT -> 0;
            case CENTER -> pad / 2;
            case RIGHT -> pad;
        };
    }

    /** Whether one cell is part of the selection. */
    private static boolean isSelected(Model state, int row, int column) {
        Selection selection = state.selection();
        if (selection.row() != row) {
            return false;
        }
        return !selection.cellwise() || selection.column() == column;
    }

    /** The style the theme gives one body cell, merged over the row's own style. */
    private static Style style(Model state, Config config, int row, int column, Style rowStyle) {
        Column schema = state.content().columns().get(column);
        Row source = state.content().rows().get(row);
        Cell cell = new Cell(row, column, schema, source, isSelected(state, row, column), state.focused());
        return (rowStyle == null ? Style.NONE : rowStyle).merge(config.theme().cell(cell));
    }

    /** A value prepared as the table paints it and truncated to a column. */
    private static StyledText clipped(StyledText value, int width) {
        StyledText prepared = prepared(value);
        String plain = prepared.plainText();
        String truncated = TextMeasure.truncate(plain, width);
        return truncated.length() == plain.length() ? prepared : prepared.slice(0, truncated.length());
    }

    /** One line cut to the horizontal window and padded to the cells the window shows. */
    private static StyledText window(Model state, StyledText line, Style style, int firstColumn) {
        int width = Math.max(0, state.width());
        if (width == 0) {
            return StyledText.empty();
        }
        VisualRows.Window pane = VisualRows.pane(line.plainText(), 0, width, firstColumn);
        StyledText visible = line.slice(pane.start(), pane.end());
        int span = Math.max(0, Math.min(state.content().rowWidth(), firstColumn + width) - firstColumn);
        int pad = span - TextMeasure.columns(visible.plainText());
        return pad <= 0 ? visible : visible.append(StyledText.of(" ".repeat(pad), style));
    }

    /** A cell as the table paints it: one row, tab-expanded, spans kept. */
    private static StyledText prepared(StyledText value) {
        return TabStops.expand(singleRow(value));
    }

    /**
     * A value with every break form turned into one space, spans and links kept.
     *
     * <p>A cell is one painted row, the rule {@link TableSelector} and {@link Markup} apply, so the
     * conversion is the one policy {@link LineBreaks} owns. A break is one character and is never
     * split across spans, so converting each span on its own is the same conversion the whole value
     * would get; a bare CR cannot reach a cell at all, because {@link StyledText} drops an
     * unrepresentable control character where the span is made. An application that reads raw text
     * prepares it with {@link LineBreaks#singleRow} before it becomes a cell.</p>
     */
    private static StyledText singleRow(StyledText value) {
        if (value.plainText().indexOf('\n') < 0) {
            return value;
        }
        StyledText out = StyledText.empty();
        for (StyledText.Span span : value.spans()) {
            out = out.append(StyledText.of(LineBreaks.singleRow(span.text()), span.style(), span.link()));
        }
        return out;
    }
}
