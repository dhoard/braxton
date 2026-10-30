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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
 */
public final class TableSelector
        implements Component<TableSelector.Model, TableSelector.Msg, TableSelector.Output> {

    /** Immutable selection state. */
    public record Model(
            List<String> header, List<List<String>> rows, int row, int column, boolean focused) {

        public Model(List<String> header, List<List<String>> rows, int row, int column) {
            this(header, rows, row, column, true);
        }

        public Model withFocus(boolean value) {
            // Matching the editor and selector: unchanged focus keeps the instance, so a message that
            // changed nothing stays a no-op for the runtime's unchanged-reference view rule.
            return value == focused ? this : new Model(header, rows, row, column, value);
        }

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

        public static Model of(List<String> header, List<List<String>> rows) {
            return new Model(header, rows, rows.isEmpty() ? -1 : 0, header.isEmpty() ? -1 : 0);
        }

        public String selectedValue() {
            if (row < 0 || row >= rows.size()) {
                return "";
            }
            List<String> values = rows.get(row);
            return column < 0 || column >= values.size() ? "" : values.get(column);
        }
    }

    /** One navigation or confirmation gesture. */
    public sealed interface Msg {
        record Up() implements Msg {}

        record Down() implements Msg {}

        record Left() implements Msg {}

        record Right() implements Msg {}

        record Confirm() implements Msg {}

        record Cancel() implements Msg {}
    }

    /** What the owner should do after an action. */
    public sealed interface Output {
        record Chosen(int row, int column, String value) implements Output {}

        record Cancelled() implements Output {}
    }

    private record Result(Model state, Output intent) {}

    public record Config(Model initial, Style activeStyle, Style inactiveStyle, Style headerStyle) {
        public Config {
            Objects.requireNonNull(initial, "initial");
            activeStyle = activeStyle == null ? Style.NONE : activeStyle;
            inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
            headerStyle = headerStyle == null ? Style.NONE : headerStyle;
        }

        public static Config defaults() {
            return new Config(
                    Model.of(List.of(), List.of()),
                    Style.NONE.withInvert(),
                    Style.NONE,
                    Style.NONE);
        }
    }

    private final Config config;

    public TableSelector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        return Update.next(config.initial());
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        Result result = reduce(model, message);
        Model next = result.state().withFocus(model.focused());
        // An action that changed nothing keeps the incoming instance: the view is a pure function of
        // the model, so the runtime's unchanged-reference repaint rule stays meaningful.
        if (next.equals(model)) next = model;
        return result.intent() == null ? Update.next(next) : Update.emit(next, result.intent());
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        return paint(
                ElementId.of("input"),
                model,
                model.focused(),
                config.activeStyle(),
                config.inactiveStyle(),
                config.headerStyle(),
                Function.identity());
    }

    private static Result reduce(Model state, Msg action) {
        int rows = state.rows().size();
        int columns = Math.max(1, state.header().size());
        return switch (action) {
            case Msg.Up up -> moved(state, Math.max(0, state.row() - 1), state.column());
            case Msg.Down down ->
                    moved(state, Math.min(Math.max(0, rows - 1), state.row() + 1), state.column());
            case Msg.Left left -> moved(state, state.row(), Math.max(0, state.column() - 1));
            case Msg.Right right ->
                    moved(state, state.row(), Math.min(columns - 1, state.column() + 1));
            // Without a selected cell (no rows, or no header to define columns) there is nothing
            // to confirm; Chosen(-1, -1, "") would not name a cell.
            case Msg.Confirm confirm ->
                    state.row() < 0 || state.column() < 0
                            ? new Result(state, null)
                            : new Result(
                                    state,
                                    new Output.Chosen(
                                            state.row(), state.column(), state.selectedValue()));
            case Msg.Cancel cancel -> new Result(state, new Output.Cancelled());
        };
    }

    private static Result moved(Model state, int row, int column) {
        return new Result(new Model(state.header(), state.rows(), row, column), null);
    }

    private static <Message> View<Message> paint(
            ElementId id,
            Model state,
            boolean focused,
            Style activeStyle,
            Style inactiveStyle,
            Style headerStyle,
            Function<Msg, Message> map) {
        List<String> header = expanded(state.header());
        int columns = header.size();
        int[] widths = new int[columns];
        for (int i = 0; i < columns; i++) {
            widths[i] = TextMeasure.columns(header.get(i));
        }
        List<List<String>> rows = new ArrayList<>(state.rows().size());
        for (List<String> row : state.rows()) {
            List<String> expanded = expanded(row);
            rows.add(expanded);
            for (int i = 0; i < Math.min(columns, expanded.size()); i++) {
                widths[i] = Math.max(widths[i], TextMeasure.columns(expanded.get(i)));
            }
        }
        List<View<Message>> lines = new ArrayList<>();
        StyledText headerLine = StyledText.empty();
        // A rendered row is one cluster sequence, so each cell is charged inside the row rather
        // than measured on its own: a cluster with no base of its own at the head of a cell joins
        // the pad of the cell before it and takes no column of its own there, while a variation
        // selector there widens that pad by a column. Padding each cell from its own measurement
        // charged columns the row never spent, so the rows of one grid came out different widths,
        // every cell after the shifted one moved, and the selected cell's inversion missed a
        // column.
        String headerPainted = "";
        for (int i = 0; i < columns; i++) {
            String value = header.get(i);
            int pad = GridRows.pad(headerPainted, value, widths[i] + 2);
            headerPainted = headerPainted + value + " ".repeat(pad);
            headerLine = headerLine.append(StyledText.of(value + " ".repeat(pad), headerStyle));
        }
        lines.add(Views.text(headerLine));
        for (int r = 0; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            StyledText line = StyledText.empty();
            String rowPainted = "";
            for (int c = 0; c < columns; c++) {
                String value = c < values.size() ? values.get(c) : "";
                Style style =
                        focused && r == state.row() && c == state.column()
                                ? activeStyle
                                : inactiveStyle;
                int pad = GridRows.pad(rowPainted, value, widths[c] + 2);
                rowPainted = rowPainted + value + " ".repeat(pad);
                line = line.append(StyledText.of(value + " ".repeat(pad), style));
            }
            lines.add(Views.text(line));
        }
        View.InputHandler<Message> handler =
                event -> {
                    if (!(event instanceof InputEvent.KeyInput key)) {
                        return Optional.empty();
                    }
                    Key gesture = key.key();
                    Optional<Msg> action =
                            switch (gesture.name()) {
                                case "up" -> Optional.of(new Msg.Up());
                                case "down" -> Optional.of(new Msg.Down());
                                case "left" -> Optional.of(new Msg.Left());
                                case "right" -> Optional.of(new Msg.Right());
                                case "enter" -> Optional.of(new Msg.Confirm());
                                case "escape" -> Optional.of(new Msg.Cancel());
                                default -> Optional.empty();
                            };
                    return action.map(map);
                };
        return Views.focus(id, focused, List.of(), handler, Views.column(lines));
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
     * {@link Markup}, {@link Quote}, {@link ImageView} and {@link ProgressBar} apply. Flattening
     * per character turned CRLF into two spaces and widened the shared column.
     */
    private static String singleRow(String value) {
        return LineBreaks.singleRow(value);
    }
}
