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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A focusable table with a selected row and column.
 *
 * <p>Selection is explicit state and is clamped on every action; the view inverts the selected cell
 * and never mutates the rows it was given.</p>
 *
 * <p>Every row is one painted line: tab stops are expanded to the shared four-column boundaries and
 * line breaks become spaces, so a cell can never split the selection grid across rows.</p>
 */
public final class TableSelector {

    /** Immutable selection state. */
    public record State(List<String> header, List<List<String>> rows, int row, int column) {

        public State {
            header = List.copyOf(header);
            List<List<String>> copy = new ArrayList<>();
            for (List<String> values : rows) {
                copy.add(List.copyOf(values));
            }
            rows = List.copyOf(copy);
            row = rows.isEmpty() ? -1 : Math.max(0, Math.min(row, rows.size() - 1));
            column = header.isEmpty() ? -1 : Math.max(0, Math.min(column, header.size() - 1));
        }

        public static State of(List<String> header, List<List<String>> rows) {
            return new State(header, rows, rows.isEmpty() ? -1 : 0, header.isEmpty() ? -1 : 0);
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
    public sealed interface Action {
        record Up() implements Action {
        }

        record Down() implements Action {
        }

        record Left() implements Action {
        }

        record Right() implements Action {
        }

        record Confirm() implements Action {
        }

        record Cancel() implements Action {
        }
    }

    /** What the owner should do after an action. */
    public sealed interface Intent {
        record None() implements Intent {
        }

        record Chosen(int row, int column, String value) implements Intent {
        }

        record Cancelled() implements Intent {
        }
    }

    public record Result(State state, Intent intent) {
    }

    private TableSelector() {
    }

    public static Result update(State state, Action action) {
        int rows = state.rows().size();
        int columns = Math.max(1, state.header().size());
        return switch (action) {
            case Action.Up up -> moved(state, Math.max(0, state.row() - 1), state.column());
            case Action.Down down -> moved(state, Math.min(Math.max(0, rows - 1), state.row() + 1),
                    state.column());
            case Action.Left left -> moved(state, state.row(), Math.max(0, state.column() - 1));
            case Action.Right right -> moved(state, state.row(),
                    Math.min(columns - 1, state.column() + 1));
            // Without a selected cell (no rows, or no header to define columns) there is nothing
            // to confirm; Chosen(-1, -1, "") would not name a cell.
            case Action.Confirm confirm -> state.row() < 0 || state.column() < 0
                    ? new Result(state, new Intent.None())
                    : new Result(state, new Intent.Chosen(state.row(), state.column(),
                            state.selectedValue()));
            case Action.Cancel cancel -> new Result(state, new Intent.Cancelled());
        };
    }

    private static Result moved(State state, int row, int column) {
        return new Result(new State(state.header(), state.rows(), row, column), new Intent.None());
    }

    public static <Msg> View<Msg> view(ElementId id, State state, boolean focused,
            Style activeStyle, Style inactiveStyle, Style headerStyle,
            Function<Action, Msg> map) {
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
        List<View<Msg>> lines = new ArrayList<>();
        StyledText headerLine = StyledText.empty();
        for (int i = 0; i < columns; i++) {
            headerLine = headerLine.append(StyledText.of(
                    pad(header.get(i), widths[i] + 2), headerStyle));
        }
        lines.add(Views.text(headerLine));
        for (int r = 0; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            StyledText line = StyledText.empty();
            for (int c = 0; c < columns; c++) {
                String value = c < values.size() ? values.get(c) : "";
                Style style = focused && r == state.row() && c == state.column()
                        ? activeStyle : inactiveStyle;
                line = line.append(StyledText.of(pad(value, widths[c] + 2), style));
            }
            lines.add(Views.text(line));
        }
        View.InputHandler<Msg> handler = event -> {
            if (!(event instanceof InputEvent.KeyInput key)) {
                return Optional.empty();
            }
            Key gesture = key.key();
            Optional<Action> action = switch (gesture.name()) {
                case "up" -> Optional.of(new Action.Up());
                case "down" -> Optional.of(new Action.Down());
                case "left" -> Optional.of(new Action.Left());
                case "right" -> Optional.of(new Action.Right());
                case "enter" -> Optional.of(new Action.Confirm());
                case "escape" -> Optional.of(new Action.Cancel());
                default -> Optional.empty();
            };
            return action.map(map);
        };
        return Views.focus(id, focused, List.of(), handler, Views.column(lines));
    }

    /**
     * Prepares every cell the way the text view paints it: line breaks become spaces, and tab stops
     * are expanded to the shared four-column boundaries, so a measured column and a displayed
     * column agree and each row stays a single line.
     */
    private static List<String> expanded(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            String singleRow = value.replace('\n', ' ').replace('\r', ' ');
            out.add(TabStops.expand(StyledText.plain(singleRow)).plainText());
        }
        return out;
    }

    private static String pad(String text, int width) {
        String fitted = TextMeasure.truncate(text, width);
        int missing = width - TextMeasure.columns(fitted);
        return missing <= 0 ? fitted : fitted + " ".repeat(missing);
    }
}
