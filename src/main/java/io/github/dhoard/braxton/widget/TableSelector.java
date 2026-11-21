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
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A focusable table with a selected row and column.
 *
 * <p>Selection is explicit state and is clamped on every action; the view inverts the selected cell
 * while the table is focused, so the active style means "keys land here" exactly as it does in
 * {@link Selector} and {@link Editor}, and an unfocused table shows no inversion. The rows it was
 * given are never mutated.</p>
 *
 * <p>Every row is one painted line: tab stops are expanded to the shared four-column boundaries and
 * line breaks become spaces, so a cell can never split the selection grid across rows. Each cell is
 * padded inside the row it is painted in, the rule {@link Table}, {@link Markup} and {@link Quote}
 * share, so a cell that opens with a cluster that has no base of its own cannot make one row of the
 * grid a different width from another.</p>
 *
 * <p>A pointer press selects the cell it landed on, and a second press on the selected cell confirms
 * it. The header row names the columns, so a press there selects nothing.</p>
 *
 * <p>The gestures this selector handles are a {@link KeyMap}: {@link #defaultKeys()} defines the
 * defaults, {@link #keys()} is the definition a caller can render through {@link Help}, and the
 * {@link #TableSelector(Config, KeyMap)} constructor replaces it.</p>
 */
public final class TableSelector
        implements Component<TableSelector.Model, TableSelector.Msg, TableSelector.Output>,
                Interactive<TableSelector.Model, TableSelector.Msg> {

    /**
     * Immutable selection state.
     *
     * @param header the column headers
     * @param rows the selectable rows
     * @param row the selected row index
     * @param column the selected column index
     * @param focused whether the selector currently owns focus
     */
    public record Model(List<String> header, List<List<String>> rows, int row, int column, boolean focused) {

        /**
         * Creates a {@code Model} value.
         *
         * @param header the column headers
         * @param rows the selectable rows
         * @param row the selected row index
         * @param column the selected column index
         */
        public Model(List<String> header, List<List<String>> rows, int row, int column) {
            this(header, rows, row, column, true);
        }

        /**
         * Returns the same value with focus replaced.
         *
         * @param value the requested focus
         * @return the value with the requested focus
         */
        public Model withFocus(boolean value) {
            // Matching the editor and selector: unchanged focus keeps the instance, so a message that
            // changed nothing stays a no-op for the runtime's unchanged-reference view rule.
            return value == focused ? this : new Model(header, rows, row, column, value);
        }

        /**
         * Creates a {@code Model} value.
         *
         * @param header the column headers
         * @param rows the selectable rows
         * @param row the selected row index
         * @param column the selected column index
         * @param focused whether the selector currently owns focus
         */
        public Model {
            header = List.copyOf(header);
            List<List<String>> copy = new ArrayList<>();
            for (List<String> values : rows) {
                copy.add(List.copyOf(values));
            }
            rows = List.copyOf(copy);
            row = rows.isEmpty() ? -1 : Math.max(0, Math.min(row, rows.size() - 1));
            column = header.isEmpty() ? -1 : Math.max(0, Math.min(column, header.size() - 1));
        }

        /**
         * Creates a {@code Model} value.
         *
         * @param header the column headers
         * @param rows the selectable rows
         * @return the normalized table selection state
         */
        public static Model of(List<String> header, List<List<String>> rows) {
            return new Model(header, rows, rows.isEmpty() ? -1 : 0, header.isEmpty() ? -1 : 0);
        }

        /**
         * Returns the selected cell text, or an empty string outside the table.
         *
         * @return the selected cell text, or an empty string outside the table
         */
        public String selectedValue() {
            if (row < 0 || row >= rows.size()) {
                return "";
            }
            List<String> values = rows.get(row);
            return column < 0 || column >= values.size() ? "" : values.get(column);
        }
    }

    /**
     * One navigation or confirmation gesture.
     */
    public sealed interface Msg {
        /**
         * Moves the selection up.
         */
        record Up() implements Msg {}

        /**
         * Selects one cell: a pointer press.
         *
         * @param row the row to select; clamped into the rows
         * @param column the column to select; clamped into the header's columns
         */
        record Select(int row, int column) implements Msg {}

        /**
         * Moves the selection down.
         */
        record Down() implements Msg {}

        /**
         * Moves the selection left.
         */
        record Left() implements Msg {}

        /**
         * Moves the selection right.
         */
        record Right() implements Msg {}

        /**
         * Confirms the current selection.
         */
        record Confirm() implements Msg {}

        /**
         * Requests cancellation.
         */
        record Cancel() implements Msg {}
    }

    /**
     * What the owner should do after an action.
     */
    public sealed interface Output {
        /**
         * Reports the selected coordinates and display value.
         *
         * @param row the zero-based row
         * @param column the zero-based column
         * @param value the display value of the chosen cell
         */
        record Chosen(int row, int column, String value) implements Output {}

        /**
         * Reports that the user canceled the selection.
         */
        record Cancelled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * The initial table selection and focused, unfocused, and header styles.
     *
     * @param initial the initial selected cell
     * @param activeStyle the selected item style
     * @param inactiveStyle the unselected item style
     * @param headerStyle the header cell style
     */
    public record Config(Model initial, Style activeStyle, Style inactiveStyle, Style headerStyle) {
        /**
         * Creates a {@code Config} value.
         *
         * @param initial the initial selected cell
         * @param activeStyle the selected item style
         * @param inactiveStyle the unselected item style
         * @param headerStyle the header cell style
         */
        public Config {
            Objects.requireNonNull(initial, "initial");
            activeStyle = activeStyle == null ? Style.NONE : activeStyle;
            inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
            headerStyle = headerStyle == null ? Style.NONE : headerStyle;
        }

        /**
         * Returns the default table selector configuration.
         *
         * @return the default table selector configuration
         */
        public static Config defaults() {
            return new Config(Model.of(List.of(), List.of()), Style.NONE.withInvert(), Style.NONE, Style.NONE);
        }
    }

    private final Config config;
    private final KeyMap<Msg> keys;

    /**
     * A table selector with the default keys.
     *
     * @param config the rendering policy; required
     */
    public TableSelector(Config config) {
        this(config, defaultKeys());
    }

    /**
     * A table selector with its own keys.
     *
     * @param config the rendering policy; required
     * @param keys the actions this selector handles, matched while it is focused; required
     */
    public TableSelector(Config config, KeyMap<Msg> keys) {
        this.config = Objects.requireNonNull(config, "config");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * The default actions: four-way navigation, confirm and cancel.
     *
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "move up", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "move down", "down"),
                        KeyAction.<Msg>of("left", new Msg.Left(), "\u2190", "move left", "left"),
                        KeyAction.<Msg>of("right", new Msg.Right(), "\u2192", "move right", "right")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("confirm", new Msg.Confirm(), "enter", "choose", "enter", "alt+enter"),
                        KeyAction.<Msg>of("cancel", new Msg.Cancel(), "esc", "cancel", "escape")));
    }

    /**
     * The actions this selector handles.
     *
     * @return the key map
     */
    public KeyMap<Msg> keys() {
        return keys;
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        return Update.next(config.initial());
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    private static Result reduce(Model state, Msg action) {
        int rows = state.rows().size();
        int columns = Math.max(1, state.header().size());
        return switch (action) {
            case Msg.Select select -> moved(state, select.row(), select.column());
            case Msg.Up up -> moved(state, Math.max(0, state.row() - 1), state.column());
            case Msg.Down down -> moved(state, Math.min(Math.max(0, rows - 1), state.row() + 1), state.column());
            case Msg.Left left -> moved(state, state.row(), Math.max(0, state.column() - 1));
            case Msg.Right right -> moved(state, state.row(), Math.min(columns - 1, state.column() + 1));
            // Without a selected cell (no rows, or no header to define columns) there is nothing
            // to confirm; Chosen(-1, -1, "") would not name a cell.
            case Msg.Confirm confirm ->
                state.row() < 0 || state.column() < 0
                        ? new Result(state, null)
                        : new Result(state, new Output.Chosen(state.row(), state.column(), state.selectedValue()));
            case Msg.Cancel cancel -> new Result(state, new Output.Cancelled());
        };
    }

    private static Result moved(Model state, int row, int column) {
        return new Result(new Model(state.header(), state.rows(), row, column), null);
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        return scene(model);
    }

    /**
     * The grid, with the table itself as the leaf that answers for its cells.
     */
    private Element<Msg> scene(Model model) {
        List<String> header = expanded(model.header());
        int columns = header.size();
        int[] widths = widths(model);
        List<Element<Msg>> lines = new ArrayList<>();
        StyledText headerLine = StyledText.empty();
        // A rendered row is one cluster sequence, so each cell is charged inside the row rather
        // than measured on its own: a cluster with no base of its own at the head of a cell joins
        // the pad of the cell before it and takes no column of its own there, while a variation
        // selector there widens that pad by a column. Padding each cell from its own measurement
        // charged columns the row never spent, so the rows of one grid came out different widths,
        // every cell after the shifted one moved, and the selected cell's inversion missed a
        // column.
        GridRows.Row headerRow = GridRows.Row.empty();
        for (int i = 0; i < columns; i++) {
            String value = header.get(i);
            int pad = headerRow.pad(value, widths[i] + 2);
            headerLine = headerLine.append(StyledText.of(value + " ".repeat(pad), config.headerStyle()));
        }
        lines.add(Elements.text(headerLine));
        for (int r = 0; r < model.rows().size(); r++) {
            List<String> values = expanded(model.rows().get(r));
            StyledText line = StyledText.empty();
            GridRows.Row row = GridRows.Row.empty();
            for (int c = 0; c < columns; c++) {
                String value = c < values.size() ? values.get(c) : "";
                Style style = model.focused() && r == model.row() && c == model.column()
                        ? config.activeStyle()
                        : config.inactiveStyle();
                int pad = row.pad(value, widths[c] + 2);
                line = line.append(StyledText.of(value + " ".repeat(pad), style));
            }
            lines.add(Elements.text(line));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The painted width of every column: the widest of its header and its cells.
     */
    private static int[] widths(Model model) {
        List<String> header = expanded(model.header());
        int[] widths = new int[header.size()];
        for (int index = 0; index < header.size(); index++) {
            widths[index] = TextMeasure.columns(header.get(index));
        }
        for (List<String> row : model.rows()) {
            List<String> values = expanded(row);
            for (int index = 0; index < Math.min(widths.length, values.size()); index++) {
                widths[index] = Math.max(widths[index], TextMeasure.columns(values.get(index)));
            }
        }
        return widths;
    }

    /**
     * The table's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        keys.actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A press selects the cell it landed on; a second press on the selected cell confirms it, which
     * is the pointer form of enter. The header row names the columns, so a press there selects
     * nothing.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (!local.isLeftPress()) {
            return;
        }
        int row = local.row() - 1;
        if (row < 0 || row >= model.rows().size()) {
            return;
        }
        if (local.clickCount() > 1 && row == model.row()) {
            emit.accept(new Msg.Confirm());
            return;
        }
        emit.accept(new Msg.Select(row, columnAt(widths(model), local.column())));
    }

    /**
     * The column a painted cell falls in: each cell occupies its width and the two cells of padding.
     */
    private static int columnAt(int[] widths, int column) {
        int start = 0;
        for (int index = 0; index < widths.length; index++) {
            int cells = widths[index] + 2;
            if (column >= start && column < start + cells) {
                return index;
            }
            start += cells;
        }
        return Math.max(0, widths.length - 1);
    }

    /**
     * Prepares every cell the way the text view paints it: CRLF, CR and LF each become one space,
     * and tab stops are expanded to the shared four-column boundaries, so a measured column and a
     * displayed column agree and each row stays a single line.
     */
    private static List<String> expanded(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(TabStops.expand(StyledText.plain(singleRow(value))).plainText());
        }
        return out;
    }

    /**
     * A cell is one painted row, so every break form is one space - the rule {@link Selector},
     * {@link Markup}, {@link Quote}, {@link io.github.dhoard.braxton.asset.ImageView} and {@link ProgressBar} apply. Flattening
     * per character turned CRLF into two spaces and widened the shared column.
     */
    private static String singleRow(String value) {
        return LineBreaks.singleRow(value);
    }
}
