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
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
public final class Selector implements Component<Selector.Model, Selector.Msg, Selector.Output> {

    /** Immutable selector state. {@code focused} is focus, not selection. */
    public record Model(List<String> options, int selected, boolean focused) {

        public Model withFocus(boolean value) {
            // Matching the editor: unchanged focus keeps the instance, so a message that changed
            // nothing stays a no-op for the runtime's unchanged-reference view rule.
            return value == focused ? this : new Model(options, selected, value);
        }

        public Model {
            options = List.copyOf(options);
            selected = options.isEmpty() ? -1 : Math.max(0, Math.min(selected, options.size() - 1));
        }

        public static Model of(List<String> options) {
            return new Model(options, options.isEmpty() ? -1 : 0, true);
        }

        public String current() {
            return selected < 0 ? "" : options.get(selected);
        }
    }

    /** One navigation or confirmation gesture. */
    public sealed interface Msg {
        record Up() implements Msg {}

        record Down() implements Msg {}

        record Confirm() implements Msg {}

        record Cancel() implements Msg {}
    }

    /** What the owner should do after an action. */
    public sealed interface Output {
        record Chosen(int index, String value) implements Output {}

        record Cancelled() implements Output {}
    }

    private record Result(Model state, Output intent) {}

    public record Config(List<String> options, Style activeStyle, Style inactiveStyle) {
        public Config {
            options = List.copyOf(options);
            activeStyle = activeStyle == null ? Style.NONE : activeStyle;
            inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
        }

        public static Config defaults() {
            return new Config(List.of(), Style.NONE.withInvert(), Style.NONE);
        }
    }

    private final Config config;

    public Selector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        return Update.next(Model.of(config.options()));
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
                Function.identity());
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Up up ->
                    new Result(
                            new Model(
                                    state.options(),
                                    Math.max(0, state.selected() - 1),
                                    state.focused()),
                            null);
            case Msg.Down down ->
                    new Result(
                            new Model(
                                    state.options(),
                                    Math.min(
                                            Math.max(0, state.options().size() - 1),
                                            state.selected() + 1),
                                    state.focused()),
                            null);
            // With no options there is nothing to confirm; Chosen(-1, "") would not be a choice.
            case Msg.Confirm confirm ->
                    state.selected() < 0
                            ? new Result(state, null)
                            : new Result(
                                    state, new Output.Chosen(state.selected(), state.current()));
            case Msg.Cancel cancel -> new Result(state, new Output.Cancelled());
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

    private static <Message> View<Message> paint(
            ElementId id,
            Model state,
            boolean focused,
            Style activeStyle,
            Style inactiveStyle,
            Function<Msg, Message> map) {
        List<View<Message>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < state.options().size(); i++) {
            boolean selected = i == state.selected();
            // The marker is selection (which option) and is always drawn; the active style means
            // "keys land here" and so follows focus, exactly as TableSelector and the Editor's
            // caret do.
            String marker = selected ? "\u276F " : "  ";
            Style style = selected && focused ? activeStyle : inactiveStyle;
            rows.add(Views.text(StyledText.of(marker + singleLine(state.options().get(i)), style)));
        }
        View<Message> list = Views.column(Alignment.START, rows);
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
                                case "enter" -> Optional.of(new Msg.Confirm());
                                case "escape" -> Optional.of(new Msg.Cancel());
                                default -> Optional.empty();
                            };
                    return action.map(map);
                };
        return Views.focus(id, focused, List.of(), handler, list);
    }
}
