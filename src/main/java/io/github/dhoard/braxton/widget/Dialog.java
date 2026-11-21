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
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A modal dialog: a titled box in the middle of the screen, the application's content inside it, and
 * the row of buttons that answers it.
 *
 * <p>A dialog is the widget that owns the screen while a question is open. It is composed as an
 * {@link Elements#modal modal} layer, so nothing under it can be hit or take focus, and it owns three
 * things the content inside it cannot: its title, which of its buttons the keyboard is on, and the
 * answer the user gave. The content itself stays with the application - a message, a
 * {@link Form}, a {@link TextInput} - and is placed inside the box rather than rebuilt, so a field
 * the reader was typing into keeps its instance, its caret and its pointer gesture.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The box is the configured width and as tall as its title, its content and its buttons need, and
 * it sits in the middle of the layer it is given. The configured width is a maximum: a narrower
 * terminal clips the box, and the content then wraps at the width that is really there rather than
 * being cut off. A content taller than the screen is clipped, so an over-long body can never push
 * the buttons out of the only region the modal owns.</p>
 *
 * <h2>Focus</h2>
 *
 * <p>The dialog's own focus is model data, like every bundled widget's: {@link Model#focusedAction()}
 * is the button the keys are on and {@link Model#focused()} is whether the dialog itself holds the
 * keyboard, so an application that shows a dialog while it paints a progress bar can leave the
 * keyboard where it is. {@code left}, {@code right}, {@code tab} and {@code shift+tab} move between
 * buttons and wrap at either end; {@code enter} and {@code space} activate the one the keys are on;
 * {@code esc} asks the application to dismiss the dialog. The gestures are a {@link KeyMap} -
 * {@link #defaultKeys()} defines them and {@link #keys()} is what the dialog routes, so {@link Help}
 * describes what actually fires - and every key the map names produces a message, which is what
 * makes a dialog trap the keys it owns instead of letting them fall through to the screen under it.</p>
 *
 * <p>A content that holds a focusable widget of its own keeps the keyboard while it is focused, the
 * rule the whole kernel follows: the dialog's keys reach it only while the dialog's own leaf is the
 * focused one. An application that puts a field inside a dialog therefore installs the dialog's keys
 * as its own bindings, or claims the two it needs, exactly as it would for any other composition.</p>
 *
 * <h2>Pointer</h2>
 *
 * <p>A left press on a button activates it. A press anywhere else in the box - its border, its blank
 * cells, the gap between its buttons - answers nothing, and a press outside the box reaches nothing
 * at all, because a modal layer owns the whole screen it was placed in: the cells beside a dialog
 * belong to nobody rather than to the pane behind it.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>The box is the border's chrome in the theme's {@code BORDER} with its title on the top edge,
 * the content in whatever styles the application gave it, and each button a bracketed label in
 * {@code BORDER} and {@code TEXT}. The button the keys are on is filled with {@code SELECTED} and, in
 * a focused dialog, also carries {@code FOCUS}. A title wider than the top edge is clipped by the
 * frame, and a dialog given no cells paints nothing.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A dialog is one object per placed dialog, held across frames the way a component is, because the
 * router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Toast
 * @see Elements#dialog(Dialog, Dialog.Model, Element, java.util.function.Function)
 */
public final class Dialog
        implements Component<Dialog.Model, Dialog.Msg, Dialog.Output>, Interactive<Dialog.Model, Dialog.Msg> {

    /**
     * The width a dialog asks for unless its configuration says otherwise.
     */
    public static final int DEFAULT_WIDTH = 48;

    /**
     * The columns kept between two buttons.
     */
    public static final int BUTTON_GAP = 2;

    private final Config config;

    /**
     * The buttons as press targets, so a press on one activates it.
     */
    private final DialogButton<Msg> button = new DialogButton<>();

    /**
     * The box's chrome: a border and a title in the theme's border style.
     */
    private final DialogFrame<Msg> frame = new DialogFrame<>();

    /**
     * A dialog over its configuration.
     *
     * @param config the title, the buttons, the width and the border; required
     */
    public Dialog(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * How a dialog is configured.
     *
     * <p>The width is the box's width in columns, clipped to the layer it is placed in. A dialog
     * without buttons is legal: it is a box that says something and waits for {@code esc}, and its
     * activation gesture then changes nothing.</p>
     *
     * @param title the title painted on the top edge; null or empty for none
     * @param actions the buttons, left to right; null is none
     * @param width the box's width in columns; positive
     * @param border the border characters; null is {@link Border#ROUNDED}
     */
    public record Config(StyledText title, List<Option> actions, int width, Border border) {

        /**
         * Copies the actions, rejects an unusable width and substitutes the defaults.
         */
        public Config {
            title = title == null ? StyledText.empty() : title;
            actions = Option.copyAll(actions);
            if (width < 1) {
                throw new IllegalArgumentException("width must be positive");
            }
            border = border == null ? Border.ROUNDED : border;
        }

        /**
         * A dialog with a title and buttons, {@link #DEFAULT_WIDTH} wide and rounded.
         *
         * @param title the title; null or empty for none
         * @param actions the buttons, left to right
         * @return the configuration
         */
        public static Config of(StyledText title, List<Option> actions) {
            return new Config(title, actions, DEFAULT_WIDTH, Border.ROUNDED);
        }

        /**
         * A dialog with a plain title and buttons.
         *
         * @param title the plain title; null or empty for none
         * @param actions the buttons, left to right
         * @return the configuration
         */
        public static Config of(String title, List<Option> actions) {
            return of(StyledText.plain(title), actions);
        }

        /**
         * This configuration with another title.
         *
         * @param value the title; null or empty for none
         * @return the new configuration
         */
        public Config withTitle(StyledText value) {
            return new Config(value, actions, width, border);
        }

        /**
         * This configuration over other buttons.
         *
         * @param value the buttons, left to right; null is none
         * @return the new configuration
         */
        public Config withActions(List<Option> value) {
            return new Config(title, value, width, border);
        }

        /**
         * This configuration at another width.
         *
         * @param value the box's width in columns; positive
         * @return the new configuration
         */
        public Config withWidth(int value) {
            return new Config(title, actions, value, border);
        }

        /**
         * This configuration with another border.
         *
         * @param value the border characters; null is {@link Border#ROUNDED}
         * @return the new configuration
         */
        public Config withBorder(Border value) {
            return new Config(title, actions, width, value);
        }
    }

    /**
     * Immutable dialog state.
     *
     * <p>The canonical constructor keeps the buttons and resolves the focused button against them:
     * a dialog with buttons always has one of them focused, and a dialog without buttons focuses
     * nothing.</p>
     *
     * @param actions the buttons, left to right
     * @param focusedAction the id of the button the keys are on, or empty when there are none
     * @param focused whether the dialog itself holds the keyboard
     */
    public record Model(List<Option> actions, String focusedAction, boolean focused) {

        /**
         * Copies the actions and resolves the focused button.
         */
        public Model {
            actions = Option.copyAll(actions);
            focusedAction = resolve(actions, focusedAction);
        }

        /**
         * A focused dialog with its first button focused.
         *
         * @param actions the buttons, left to right
         * @return the model
         */
        public static Model of(List<Option> actions) {
            return new Model(actions, "", true);
        }

        /**
         * This dialog over other buttons, with the focused button resolved again.
         *
         * @param value the buttons, left to right; null is none
         * @return the new model
         */
        public Model withActions(List<Option> value) {
            return new Model(value, focusedAction, focused);
        }

        /**
         * This dialog with another button focused.
         *
         * @param value the id to focus; null or an unknown id focuses the first button
         * @return the new model
         */
        public Model withFocusedAction(String value) {
            return new Model(actions, value, focused);
        }

        /**
         * The same dialog, holding the keyboard or not.
         *
         * @param value whether the dialog holds the keyboard
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(actions, focusedAction, value);
        }

        /**
         * The button the keys are on, if there is one.
         *
         * @return the button, or empty
         */
        public Optional<Option> focusedOption() {
            for (Option action : actions) {
                if (action.id().equals(focusedAction)) {
                    return Optional.of(action);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The dialog's messages.
     */
    public sealed interface Msg {

        /**
         * Focuses the next button, wrapping to the first at the end.
         */
        record Next() implements Msg {}

        /**
         * Focuses the previous button, wrapping to the last at the beginning.
         */
        record Previous() implements Msg {}

        /**
         * Activates the button the keys are on.
         */
        record Activate() implements Msg {}

        /**
         * Activates one button, which is what a press on it reports.
         *
         * @param id the button; an unknown id activates nothing
         */
        record Choose(String id) implements Msg {}

        /**
         * Asks the application to dismiss the dialog.
         */
        record Cancel() implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * A button was activated.
         *
         * @param id the button's id
         * @param action the button itself
         */
        record Activated(String id, Option action) implements Output {}

        /**
         * The user asked to dismiss the dialog.
         */
        record Canceled() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How this dialog is configured.
     *
     * @return how this dialog is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The dialog's element with a blank content, which is a question with an answer row.
     *
     * @param model the state to paint; required
     * @return the modal layer, emitting this dialog's messages
     */
    public Element<Msg> element(Model model) {
        return element(model, null, message -> message);
    }

    /**
     * The dialog with the application's content inside its box.
     *
     * <p>This is a modal layer: place it over the scene it interrupts, in an {@link
     * Elements#stack stack}, and nothing under it can be hit or take focus while it is there. The
     * content is composed, never rebuilt, so a field inside the dialog keeps the instance that owns
     * its caret.</p>
     *
     * @param <Out> the message type of the composed tree
     * @param model the state to paint; required
     * @param content the content inside the box; null paints none
     * @param messages maps this dialog's messages into the composed tree's message type; required
     * @return the composed modal layer
     */
    public <Out> Element<Out> element(
            Model model, Element<Out> content, Function<? super Msg, ? extends Out> messages) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(messages, "messages");
        List<Element<Out>> rows = new ArrayList<>(3);
        if (content != null) {
            rows.add(content);
            rows.add(Elements.spacer(1));
        }
        Element<Out> actions = actionRow(model, messages);
        if (actions != null) {
            rows.add(actions);
        }
        Element<Out> box = box(Elements.column(rows), messages);
        // The dialog is its own interaction leaf, under the box rather than over it: a button inside
        // the box is what a press on a button reaches, and every other cell of the box - its border,
        // its blank rows, the gap between two buttons - is the dialog's.
        Element<Out> leaf = new Element.Leaf<>(this, model, Sizing.measured()).map(messages);
        return Elements.modal(Elements.row(
                Alignment.CENTER,
                Elements.grow(Elements.spacer()),
                Elements.fixed(config.width(), Elements.stack(leaf, box)),
                Elements.grow(Elements.spacer())));
    }

    /**
     * The default actions: moving between the buttons, activating one, and dismissing.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Buttons",
                        KeyAction.<Msg>of("next-button", new Msg.Next(), "\u2192", "next button", "right", "tab"),
                        KeyAction.<Msg>of(
                                "previous-button",
                                new Msg.Previous(),
                                "\u2190",
                                "previous button",
                                "left",
                                "shift+tab")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("activate", new Msg.Activate(), "enter", "activate", "enter", "space"),
                        KeyAction.<Msg>of("dismiss", new Msg.Cancel(), "esc", "dismiss", "esc", "escape")));
    }

    /**
     * The actions this dialog handles.
     *
     * @return the actions this dialog handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(Model.of(config.actions()));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        // Which button the keys are on is this widget's data, so the reduction is not wrapped in a
        // refocus that would put the incoming button back.
        return WidgetUpdate.settled(model, message, action -> reduce(model, action), state -> state);
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return element(model);
    }

    /**
     * The dialog's keys, from the same map {@link Help} renders.
     *
     * <p>Every key the map names emits a message, which is what makes the dialog trap the gestures it
     * owns: a gesture a widget answers never falls through to the application.</p>
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key its press did, so it never fires the press a second time.
            return;
        }
        keys().actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The box: a themed border and title over the content, with the content one cell inside it.
     *
     * <p>It is the composition {@link Elements#frame} builds, with a themed chrome instead of one
     * whose style was chosen when the scene was built, so a live theme switch repaints the box.</p>
     */
    private <Out> Element<Out> box(Element<Out> content, Function<? super Msg, ? extends Out> messages) {
        Element<Out> chrome = new Element.Leaf<DialogFrame.Model, Msg>(
                        frame, new DialogFrame.Model(config.border(), config.title()), Sizing.measured())
                .map(messages);
        // Only a visible border takes a cell on each side: an invisible one draws no chrome, so
        // insetting the content for it would be a frame nobody can see.
        Insets insets = config.border().isVisible() ? new Insets(1, 1, 1, 1) : Insets.NONE;
        return Elements.stack(chrome, Elements.pad(insets, content));
    }

    /**
     * The buttons, left to right, with the dialog itself answering for the box around them.
     */
    private <Out> Element<Out> actionRow(Model model, Function<? super Msg, ? extends Out> messages) {
        if (model.actions().isEmpty()) {
            return null;
        }
        List<Element<Out>> buttons = new ArrayList<>(model.actions().size() * 2);
        for (Option action : model.actions()) {
            if (!buttons.isEmpty()) {
                buttons.add(Elements.spacer(BUTTON_GAP));
            }
            buttons.add(new Element.Leaf<DialogButton.Model<Msg>, Msg>(
                            button,
                            new DialogButton.Model<>(
                                    action,
                                    action.id().equals(model.focusedAction()),
                                    model.focused(),
                                    new Msg.Choose(action.id())),
                            Sizing.measured())
                    .map(messages));
        }
        return Elements.row(buttons);
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Next next -> new Result(step(state, 1), null);
            case Msg.Previous previous -> new Result(step(state, -1), null);
            case Msg.Activate activate -> activate(state, state.focusedAction());
            case Msg.Choose choose -> activate(state, choose.id());
            case Msg.Cancel cancel -> new Result(state, new Output.Canceled());
        };
    }

    /**
     * The focused button moved by a number of buttons, wrapping at either end.
     */
    private static Model step(Model state, int delta) {
        List<Option> actions = state.actions();
        if (actions.isEmpty()) {
            return state;
        }
        int current = indexOf(actions, state.focusedAction());
        int target = Math.floorMod((current < 0 ? 0 : current) + delta, actions.size());
        return target == current
                ? state
                : state.withFocusedAction(actions.get(target).id());
    }

    /**
     * A button activated by id: one that is present is focused and reported, anything else is not.
     */
    private static Result activate(Model state, String id) {
        int index = indexOf(state.actions(), id);
        if (index < 0) {
            return new Result(state, null);
        }
        Option action = state.actions().get(index);
        return new Result(state.withFocusedAction(action.id()), new Output.Activated(action.id(), action));
    }

    /**
     * The row of a button, or -1.
     */
    private static int indexOf(List<Option> actions, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < actions.size(); index++) {
            if (actions.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The button a list admits: the named one when it exists, the first one otherwise.
     */
    private static String resolve(List<Option> actions, String id) {
        int index = indexOf(actions, id);
        return index >= 0
                ? actions.get(index).id()
                : actions.isEmpty() ? "" : actions.get(0).id();
    }
}
