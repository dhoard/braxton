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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.DataTableConfig;
import io.github.dhoard.braxton.widget.DataTableContent;
import io.github.dhoard.braxton.widget.DataTableModel;
import io.github.dhoard.braxton.widget.DataTableMsg;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 13 parity demo: a scrollable, editable table over an original seed inventory.
 *
 * <p>The scene owns the table's data and the window's geometry; the {@link DataTable} owns the
 * selection, the vertical window and the horizontal pan. Every gesture the table defines is routed by
 * the table's own {@link KeyMap}, so help describes what fires, and the scene's keys bubble beside it.
 * The rows can be added, edited and removed while the selection keeps its identity, which is what the
 * status rows report.</p>
 *
 * <p>Below the interactive table is a static {@link Table} fitted to the terminal's width. It is the
 * other half of "responsive": the interactive table pans the columns it cannot fit, and the static one
 * shrinks its own columns and truncates what is left over, so the same terminal width exercises both
 * answers.</p>
 *
 * <p>The inventory is written for this demo. One note is deliberately longer than a terminal so the
 * table has something to pan, the numbers are right-aligned so the alignment survives the pan, and the
 * ids are fixed-width so the header cannot move.</p>
 */
public final class TableDemo {

    /**
     * The rows the scene paints above the table: two status rows.
     */
    public static final int STATUS_ROWS = 2;

    /**
     * The rows the scene paints below the table: the summary frame and its two help lines.
     */
    public static final int SUMMARY_ROWS = 4;

    /**
     * The rows the scene paints below the summary: one help line per key map.
     */
    public static final int HELP_ROWS = 2;

    /**
     * The rows the scene reserves outside the table.
     */
    private static final int FIXED_ROWS = STATUS_ROWS + SUMMARY_ROWS + HELP_ROWS;

    /**
     * The fewest rows the table is given, so a short terminal still paints a header and one row.
     */
    public static final int MIN_TABLE_ROWS = 3;

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style HEADER = Style.NONE.withBold();

    private static final Style NUMBER = Style.NONE.withBold();

    private static final Style NOTE = Style.NONE.withItalic();

    /**
     * The schema: an id, three text columns, a right-aligned count and a bounded note column.
     */
    public static final List<DataTable.Column> COLUMNS = List.of(
            DataTable.Column.of("id", "id", 4),
            DataTable.Column.of("name", "name"),
            DataTable.Column.of("family", "family"),
            DataTable.Column.of("sown", "sown", 5).withAlignment(DataTable.Alignment.RIGHT),
            // A note is prose: it wants room, it may not take the whole terminal, and it may not be
            // narrower than its own heading. The policy is the three bounds in one line, and the
            // interactive table pans the cells the column does not fit into.
            DataTable.Column.of("note", "note").withWidth(DataTable.Width.of(8, 30, 60)));

    /**
     * The column the demo edits, by key.
     */
    public static final String EDITED_COLUMN = "name";

    /**
     * The inventory: id, name, family, sown and note, one row per line.
     */
    private static final String[][] SEEDS = {
        {"S-01", "artichoke", "thistle", "12", "Sow under cover in early spring and prick out hard."},
        {"S-02", "cardoon", "thistle", "8", "Wants a meter of room and a wall behind it."},
        {"S-03", "borlotti", "legume", "40", "Climbing: give it a pole before it decides for you."},
        {"S-04", "cavolo nero", "brassica", "36", "Pick from the bottom and it keeps coming."},
        {"S-05", "chicory", "chicory", "24", "Bitter, patient, and better after a frost."},
        {"S-06", "fennel", "umbellifer", "30", "A long taproot; sow it where it is to stay."},
        {"S-07", "garlic", "allium", "60", "Plant the cloves in autumn, points up, and wait."},
        {"S-08", "kale", "brassica", "32", "The ragged leaves are the point, not a fault."},
        {"S-09", "leek", "allium", "48", "Earth up the stems for the white you were promised."},
        {"S-10", "lettuce", "aster", "50", "Little and often beats one enormous sowing."},
        {"S-11", "mangetout", "legume", "45", "Pick them flat and young, before the peas fill."},
        {"S-12", "onion", "allium", "55", "Sets are quicker; seed is cheaper; both work."},
        {"S-13", "parsley", "umbellifer", "20", "Slow to start, then it seeds itself everywhere."},
        {"S-14", "radish", "brassica", "28", "Four weeks from sowing to the first sharp slice."},
        {"S-15", "runner bean", "legume", "18", "Water it like you mean it once the flowers open."},
        {
            "S-16",
            "salsify",
            "aster",
            "22",
            "An heirloom root with the flavour of oysters that nobody under thirty has knowingly"
                    + " eaten; sow it in a deep bed, leave it alone all summer, and lift it only when"
                    + " the frost has done its work, which is the sort of patience a seed catalogue"
                    + " rarely asks for in one line."
        },
        {"S-17", "spinach", "amaranth", "34", "Bolts at the first hint of a dry week."},
        {"S-18", "tomato", "nightshade", "26", "Pinch the side shoots out and keep the water even."},
    };

    /**
     * The initial content: the schema and the inventory.
     */
    public static final DataTableContent CONTENT = content();

    /**
     * How the table paints its header, its numbers and its notes.
     */
    public static final DataTable.Theme THEME = new DataTable.Theme() {
        @Override
        public Style header(DataTable.Column column) {
            return HEADER;
        }

        @Override
        public Style cell(DataTable.Cell cell) {
            return switch (cell.schema().key()) {
                case "sown" -> NUMBER;
                case "note" -> NOTE;
                default -> Style.NONE;
            };
        }
    };

    private TableDemo() {}

    /**
     * The scene state.
     *
     * @param table the mounted table's own state
     * @param chosen what the reader last chose, or a dash
     * @param last what the scene last did
     * @param edits how many rows the scene edited
     * @param added how many rows it added
     * @param removed how many rows it removed
     */
    public record Model(DataTableModel table, String chosen, String last, int edits, int added, int removed) {

        /**
         * Substitutes the defaults for a missing value.
         */
        public Model {
            table = table == null ? DataTableModel.of(CONTENT) : table;
            chosen = chosen == null ? "-" : chosen;
            last = last == null ? "none" : last;
        }

        Model withTable(DataTableModel value) {
            return new Model(value, chosen, last, edits, added, removed);
        }

        Model withChosen(String value) {
            return new Model(table, value, last, edits, added, removed);
        }

        Model withLast(String value) {
            return new Model(table, chosen, value, edits, added, removed);
        }

        Model withEdits(int value) {
            return new Model(table, chosen, last, value, added, removed);
        }

        Model withAdded(int value) {
            return new Model(table, chosen, last, edits, value, removed);
        }

        Model withRemoved(int value) {
            return new Model(table, chosen, last, edits, added, value);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * A message from the mounted table.
         *
         * @param value the table's message
         */
        record TableMsg(DataTableMsg value) implements Msg {}

        /**
         * An output the mounted table routed to the scene.
         *
         * @param value the table's output
         */
        record TableOutput(DataTable.Output value) implements Msg {}

        /**
         * Reports the terminal's geometry, which the scene splits between its components.
         *
         * @param rows the terminal's rows
         * @param columns the terminal's columns
         */
        record Resized(int rows, int columns) implements Msg {}

        /**
         * Adds one row below the selection.
         */
        record Add() implements Msg {}

        /**
         * Edits the selected row's name.
         */
        record Edit() implements Msg {}

        /**
         * Removes the selected row.
         */
        record Delete() implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions: the ones that change the table's data rather than its window.
     *
     * <p>They are bubble bindings, so the focused table sees every key first: the gestures the table
     * owns never reach this map, and its arrows, pages and mode switch keep working beside them.</p>
     *
     * @return the scene's actions, in help order
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Rows",
                        KeyAction.<Msg>of("add", new Msg.Add(), "a", "add a row", "a"),
                        KeyAction.<Msg>of("edit", new Msg.Edit(), "e", "edit the name", "e"),
                        KeyAction.<Msg>of("delete", new Msg.Delete(), "x", "delete the row", "x")),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
    }

    /**
     * Runs the demo against the owned terminal.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    // ── definitions ──────────────────────────────────────────────────────────

    /**
     * The table's definition for one scene state.
     *
     * <p>The definition carries the state's own content, so the content a message changes is the
     * content the definition starts from; the window's geometry is what the scene allocates, not what
     * the terminal is.</p>
     *
     * @param model the scene state
     * @param height the rows this scene allocates the table
     * @param width the cells this scene allocates the table
     * @return the table's definition
     */
    public static DataTableConfig tableConfig(Model model, int height, int width) {
        return DataTableConfig.defaults()
                .withContent(model.table().content())
                .withTheme(THEME)
                .withHeight(height)
                .withWidth(width);
    }

    /**
     * The rows the scene allocates the table at one terminal height.
     *
     * @param rows the terminal's rows
     * @return the table's rows, never below {@value #MIN_TABLE_ROWS}
     */
    public static int tableRows(int rows) {
        return Math.max(MIN_TABLE_ROWS, Math.max(0, rows) - FIXED_ROWS);
    }

    /**
     * The inventory as content: the schema and one row per seed.
     */
    private static DataTableContent content() {
        List<DataTable.Row> rows = new ArrayList<>(SEEDS.length);
        for (String[] seed : SEEDS) {
            rows.add(DataTable.Row.of(seed[0], seed));
        }
        return DataTableContent.of(COLUMNS, rows);
    }

    /**
     * The summary the static table paints: how many rows the inventory holds, how many are sown and
     * what the table is showing.
     *
     * @param model the scene state
     * @return the summary as a table fitted to the terminal
     */
    public static Table summary(Model model) {
        DataTableContent content = model.table().content();
        long sown = 0;
        for (DataTable.Row row : content.rows()) {
            String value = row.cell(3).plainText();
            try {
                sown += Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // A row the scene added has no count yet; it simply does not add to the total.
            }
        }
        return Table.of()
                .buffer(1)
                .border(Border.SINGLE)
                .row(StyledText.plain("rows"), StyledText.plain(Integer.toString(content.rowCount())))
                .row(StyledText.plain("sown"), StyledText.plain(Long.toString(sown)));
    }

    /**
     * The line the summary paints at one width, framed.
     */
    private static List<StyledText> summaryLines(Model model, int width) {
        Style plain = Style.NONE;
        Table table = summary(model).borderStyle(plain).width(width);
        return table.render().lines();
    }

    // ── the scene ────────────────────────────────────────────────────────────

    /**
     * Two status rows, the table, the summary frame and two help lines.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            int rows = context.viewport().rows() > 0 ? context.viewport().rows() : 24;
            int columns = context.viewport().cols() > 0 ? context.viewport().cols() : 80;
            Model empty = new Model(DataTableModel.of(CONTENT), "-", "none", 0, 0, 0);
            // The table is initialized through its own mount, so its definition and its state agree,
            // and then told the geometry this scene allocates it: the terminal's rows are shared with
            // the status rows, the summary and the help, so only the scene can report the table's own.
            Model started = empty.withTable(mount(empty, tableRows(rows), columns)
                            .initialize(context)
                            .model())
                    .withLast("started");
            return Update.next(geometry(started, rows, columns).withLast("started"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.TableMsg table -> {
                    Update<Model, Msg, Never> applied = mount(model, 0, 0).update(model, table.value());
                    yield Update.next(applied.model().withLast(action(table.value())), applied.command());
                }
                case Msg.TableOutput output -> Update.next(model.withChosen(described(output.value())));
                case Msg.Resized resized -> Update.next(geometry(model, resized.rows(), resized.columns()));
                case Msg.Add add -> added(model);
                case Msg.Edit edit -> edited(model);
                case Msg.Delete delete -> removed(model);
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * One resize source for the whole scene.
         *
         * <p>The table declares nothing: this layout is the only thing that knows how the terminal's
         * rows are split between the status rows, the table, the summary and the help, so it is the
         * only thing that can report the table's geometry.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.resize(SIZE, size -> new Msg.Resized(size.rows(), size.cols()));
        }

        @Override
        public Element<Msg> view(Model model, ViewContext context) {
            return scene(model, context);
        }

        /**
         * The gestures this scene answers once no widget has taken them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            return keys().bindings();
        }

        /**
         * The scene as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            int rows = context.viewport().rows();
            int cols = context.viewport().cols();
            int tableRows = tableRows(rows);
            List<Element<Msg>> sections = new ArrayList<>(8);
            sections.add(Elements.fixed(1, Elements.text(status(model), STATUS)));
            sections.add(Elements.fixed(1, Elements.text(counts(model), STATUS)));
            sections.add(
                    Elements.fixed(tableRows, (mount(model, tableRows, cols).view(model, context))));
            for (StyledText line : summaryLines(model, cols)) {
                sections.add(Elements.fixed(1, Elements.text(line)));
            }
            sections.add(Elements.fixed(1, Elements.text(Help.render(DataTable.keys(model.table()), false, cols))));
            sections.add(Elements.fixed(1, Elements.text(Help.render(keys(), false, cols))));
            return Elements.column(sections);
        }

        /**
         * The table, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the definition the scene's state and allocation ask for on
         * every use. Nothing else about the mount changes: its identity, its lens and its routes are
         * the same values every time.</p>
         */
        private Mount<Model, Msg, Never, DataTableModel, DataTableMsg, DataTable.Output> mount(
                Model model, int height, int width) {
            return Mount.of(
                    ComponentId.of("table"),
                    new DataTable(tableConfig(model, height, width)),
                    ModelLens.of(Model::table, (Model state, DataTableModel child) -> state.withTable(child)),
                    Msg.TableMsg::new,
                    OutputRoute.toMessage(Msg.TableOutput::new));
        }

        /**
         * The scene's own row-adding gesture: one new row below the selection.
         */
        private Update<Model, Msg, Never> added(Model model) {
            int number = model.added() + 1;
            String id = "S-9" + number;
            DataTable.Row row = DataTable.Row.of(
                    id, id, "new seed " + number, "unknown", "0", "Added by the demo; edit it with e.");
            int index = model.table().selection().row() + 1;
            Model grown = model.withAdded(number);
            return Update.next(inserted(grown, index, row).withLast("added-" + number));
        }

        /**
         * The scene's own edit: the selected row's name gains a marker, through one message.
         */
        private Update<Model, Msg, Never> edited(Model model) {
            DataTable.Row selected = model.table().selectedRow().orElse(null);
            if (selected == null) {
                return Update.next(model.withLast("empty"));
            }
            int column = model.table().content().indexOfColumn(EDITED_COLUMN);
            List<StyledText> cells = new ArrayList<>(selected.cells());
            while (cells.size() <= column) {
                cells.add(StyledText.empty());
            }
            cells.set(column, StyledText.plain(selected.cell(column).plainText() + "*"));
            DataTable.Row row = DataTable.Row.of(selected.id(), cells);
            Model edited = model.withEdits(model.edits() + 1);
            return Update.next(replaced(edited, row).withLast("edited-" + selected.id()));
        }

        /**
         * The scene's own delete, which hands the selection to the nearest survivor.
         */
        private Update<Model, Msg, Never> removed(Model model) {
            DataTable.Row selected = model.table().selectedRow().orElse(null);
            if (selected == null) {
                return Update.next(model.withLast("empty"));
            }
            Model removed = model.withRemoved(model.removed() + 1);
            return Update.next(deleted(removed, selected.id()).withLast("removed-" + selected.id()));
        }

        /**
         * One row inserted through the mount that owns it.
         */
        private Model inserted(Model model, int index, DataTable.Row row) {
            return mount(model, 0, 0)
                    .update(model, new DataTableMsg.Insert(index, row))
                    .model();
        }

        /**
         * One row replaced through the mount that owns it.
         */
        private Model replaced(Model model, DataTable.Row row) {
            return mount(model, 0, 0)
                    .update(model, new DataTableMsg.Replace(row))
                    .model();
        }

        /**
         * One row removed through the mount that owns it.
         */
        private Model deleted(Model model, String rowId) {
            return mount(model, 0, 0)
                    .update(model, new DataTableMsg.Remove(rowId))
                    .model();
        }

        /**
         * The geometry the scene reports to the table for one terminal size.
         */
        private Model geometry(Model model, int rows, int columns) {
            return mount(model, 0, 0)
                    .update(model, new DataTableMsg.Resized(tableRows(rows), Math.max(0, columns)))
                    .model();
        }

        /**
         * The table's own state, as one verifier-friendly row.
         */
        private static String status(Model model) {
            DataTableModel table = model.table();
            DataTableConfig config = tableConfig(model, 0, 0);
            return "mode=" + name(table.selection().mode())
                    + " row=" + (table.selection().row() + 1) + "/"
                    + table.content().rowCount()
                    + " cell=" + cell(model)
                    + " top=" + DataTable.top(table, config)
                    + " pan=" + DataTable.firstColumn(table, config)
                    + " last=" + model.last();
        }

        /**
         * What the scene changed, how much room the table has, and what was last chosen.
         */
        private static String counts(Model model) {
            DataTableModel table = model.table();
            return "edits=" + model.edits()
                    + " added=" + model.added()
                    + " removed=" + model.removed()
                    + " rows=" + table.content().rowCount()
                    + " body=" + DataTable.bodyHeight(table, tableConfig(model, 0, 0))
                    + " width=" + DataTable.rowWidth(table)
                    + " chosen=" + model.chosen();
        }

        /**
         * The selected column's key, or a dash for a row selection.
         */
        private static String cell(Model model) {
            if (!model.table().selection().cellwise()) {
                return "-";
            }
            return model.table().selectedColumn().map(DataTable.Column::key).orElse("-");
        }

        private static String described(DataTable.Output output) {
            return switch (output) {
                case DataTable.Output.Chosen chosen ->
                    chosen.rowId()
                            + (chosen.columnKey().isEmpty()
                                    ? ""
                                    : "/" + chosen.columnKey() + "="
                                            + chosen.value().plainText());
                case DataTable.Output.Cancelled cancelled -> "-";
            };
        }

        private static String action(DataTableMsg message) {
            return switch (message) {
                case DataTableMsg.Up up -> "up";
                case DataTableMsg.Down down -> "down";
                case DataTableMsg.Left left -> "left";
                case DataTableMsg.Right right -> "right";
                case DataTableMsg.PageUp pageUp -> "page-up";
                case DataTableMsg.PageDown pageDown -> "page-down";
                case DataTableMsg.HalfPageUp half -> "half-page-up";
                case DataTableMsg.HalfPageDown half -> "half-page-down";
                case DataTableMsg.Top top -> "top";
                case DataTableMsg.Bottom bottom -> "bottom";
                case DataTableMsg.PanLeft pan -> "pan-left";
                case DataTableMsg.PanRight pan -> "pan-right";
                case DataTableMsg.ToggleMode toggle -> "mode";
                case DataTableMsg.SetMode mode -> "mode-" + name(mode.mode());
                case DataTableMsg.Select select -> "select";
                case DataTableMsg.SelectCell cell -> "select-cell";
                case DataTableMsg.Choose choose -> "choose";
                case DataTableMsg.Cancel cancel -> "cancel";
                case DataTableMsg.Resized resized -> "resized";
                case DataTableMsg.SetRows rows -> "rows";
                case DataTableMsg.Insert insert -> "insert";
                case DataTableMsg.Replace replace -> "replace";
                case DataTableMsg.Remove remove -> "remove";
                case DataTableMsg.SetFilter filter -> "filter";
                case DataTableMsg.ClearFilter clear -> "clear-filter";
                case DataTableMsg.ToggleSort sort -> "sort";
                case DataTableMsg.SortBy sort -> "sort-by";
                case DataTableMsg.ToggleMark mark -> "mark";
                case DataTableMsg.MarkAll all -> "mark-all";
                case DataTableMsg.ClearMarks clear -> "clear-marks";
                case DataTableMsg.ResizeColumn resize -> "resize-column";
                case DataTableMsg.AdjustColumn adjust -> "adjust-column";
                case DataTableMsg.SortSelected sort -> "sort";
                case DataTableMsg.ResizeSelected resize -> "resize";
            };
        }

        private static String name(DataTable.Mode mode) {
            return mode == DataTable.Mode.ROW ? "row" : "cell";
        }
    }

    /**
     * The terminal size, which is the one number the scene splits between its sections.
     */
    private static final SubscriptionId SIZE = SubscriptionId.of("size");
}
