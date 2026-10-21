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

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A single-choice list: immutable state, pure actions, and a focusable view.
 *
 * <p>Confirming and cancelling are intents; the owning application decides whether they close a
 * dialog, advance a workflow, or exit.</p>
 *
 * <p>The view draws two independent signals. The marker is selection - which option is chosen - and
 * is always painted. The active style is interaction - that keys land here - and is painted only
 * while the view is focused, matching {@link TableSelector} and the {@link Editor}'s caret. An
 * unfocused selector therefore still shows its selection, in the inactive style.</p>
 *
 * <p>Every option is one painted row, so the marker always names a whole option. A line break in an
 * option is painted as a space rather than splitting the row and pushing every later option down,
 * the same one-line rule {@link TableSelector} applies to its cells.</p>
 */
public final class Selector {

    /** Immutable selector state. */
    public record State(List<String> options, int selected, boolean active) {

        public State {
            options = List.copyOf(options);
            selected = options.isEmpty() ? -1 : Math.max(0, Math.min(selected, options.size() - 1));
        }

        public static State of(List<String> options) {
            return new State(options, options.isEmpty() ? -1 : 0, true);
        }

        public String current() {
            return selected < 0 ? "" : options.get(selected);
        }
    }

    /** One navigation or confirmation gesture. */
    public sealed interface Action {
        record Up() implements Action {
        }

        record Down() implements Action {
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

        record Chosen(int index, String value) implements Intent {
        }

        record Cancelled() implements Intent {
        }
    }

    public record Result(State state, Intent intent) {
    }

    private Selector() {
    }

    public static Result update(State state, Action action) {
        return switch (action) {
            case Action.Up up -> new Result(new State(state.options(),
                    Math.max(0, state.selected() - 1), state.active()), new Intent.None());
            case Action.Down down -> new Result(new State(state.options(),
                    Math.min(Math.max(0, state.options().size() - 1), state.selected() + 1),
                    state.active()), new Intent.None());
            // With no options there is nothing to confirm; Chosen(-1, "") would not be a choice.
            case Action.Confirm confirm -> state.selected() < 0
                    ? new Result(state, new Intent.None())
                    : new Result(state, new Intent.Chosen(state.selected(), state.current()));
            case Action.Cancel cancel -> new Result(state, new Intent.Cancelled());
        };
    }

    /**
     * Flattens an option to the one row it is painted on: CRLF, CR and LF each become one space,
     * so no break can split the row and the styled-text boundary cannot drop a CR and merge the
     * words on either side of it.
     */
    private static String singleLine(String value) {
        return LineBreaks.singleRow(value);
    }

    public static <Msg> View<Msg> view(ElementId id, State state, boolean focused,
            Style activeStyle, Style inactiveStyle, Function<Action, Msg> map) {
        List<View<Msg>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < state.options().size(); i++) {
            boolean selected = i == state.selected();
            // The marker is selection (which option) and is always drawn; the active style means
            // "keys land here" and so follows focus, exactly as TableSelector and the Editor's
            // caret do.
            String marker = selected ? "\u276F " : "  ";
            Style style = selected && focused ? activeStyle : inactiveStyle;
            rows.add(Views.text(StyledText.of(marker + singleLine(state.options().get(i)), style)));
        }
        View<Msg> list = Views.column(Alignment.START, rows);
        View.InputHandler<Msg> handler = event -> {
            if (!(event instanceof InputEvent.KeyInput key)) {
                return Optional.empty();
            }
            Key gesture = key.key();
            Optional<Action> action = switch (gesture.name()) {
                case "up" -> Optional.of(new Action.Up());
                case "down" -> Optional.of(new Action.Down());
                case "enter" -> Optional.of(new Action.Confirm());
                case "escape" -> Optional.of(new Action.Cancel());
                default -> Optional.empty();
            };
            return action.map(map);
        };
        return Views.focus(id, focused, List.of(), handler, list);
    }
}
