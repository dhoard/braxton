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
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Selector;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Phase 01 parity demo: one key map drives routing and help.
 *
 * <p>The scene owns a {@link KeyMap} of its own actions and mounts two {@link Selector}s that keep
 * their own maps. The scene's map compiles to the capture bindings the view routes, so every action
 * in the help is an action that fires. Four things are visible on screen:</p>
 *
 * <ul>
 *   <li>{@code ?} switches between compact help and the grouped full help.
 *   <li>{@code r} remaps the toggle-help action onto {@code h} and disables itself; the help line
 *       follows both changes, and the disabled action no longer fires.
 *   <li>{@code tab} moves focus between the two mounted selectors: only the focused one takes
 *       navigation keys, and only the focused one paints the active style.
 *   <li>{@code q} (or {@code ctrl+c}) quits.
 * </ul>
 *
 * <p>The status row names what the model holds, so a terminal verifier can assert the scene without
 * reading styled cells: {@code left=alpha right=alpha focus=left help=compact keys=default
 * last=none}.</p>
 */
public final class KeymapHelpDemo {

    /** One selector column, wide enough for the marker and a five-letter option. */
    private static final int COLUMN = 16;

    private static final Style TITLE = Style.NONE.withBold();

    private static final Style ACTIVE = Style.NONE.withInvert();

    private static final Style INACTIVE = Style.NONE;

    private static final List<String> OPTIONS = List.of("alpha", "beta", "gamma");

    private KeymapHelpDemo() {}

    /** The scene state: both children, focus, help expansion, the scene's own actions, and the last gesture.
     *
     * @param left the left selector's state
     * @param right the right selector's state
     * @param leftFocused whether the left selector owns focus
     * @param expanded whether the grouped full help is shown
     * @param keys the scene's own actions, which are also the capture bindings it routes
     * @param remapped whether the remap has been applied
     * @param last a readable name for the last gesture or output the scene handled
     */
    public record Model(
            Selector.Model left,
            Selector.Model right,
            boolean leftFocused,
            boolean expanded,
            KeyMap<Msg> keys,
            boolean remapped,
            String last) {

        Model withLeft(Selector.Model value) {
            return new Model(value, right, leftFocused, expanded, keys, remapped, last);
        }

        Model withRight(Selector.Model value) {
            return new Model(left, value, leftFocused, expanded, keys, remapped, last);
        }

        Model withFocus(boolean value, Selector.Model leftModel, Selector.Model rightModel) {
            return new Model(leftModel, rightModel, value, expanded, keys, remapped, last);
        }

        Model withExpanded(boolean value) {
            return new Model(left, right, leftFocused, value, keys, remapped, last);
        }

        Model withKeys(KeyMap<Msg> value, boolean remappedKeys) {
            return new Model(left, right, leftFocused, expanded, value, remappedKeys, last);
        }

        Model withLast(String value) {
            return new Model(left, right, leftFocused, expanded, keys, remapped, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {
        record Left(Selector.Msg value) implements Msg {}

        record Right(Selector.Msg value) implements Msg {}

        record Output(String side, Selector.Output value) implements Msg {}

        record ToggleHelp() implements Msg {}

        record Remap() implements Msg {}

        record ToggleFocus() implements Msg {}

        record Quit() implements Msg {}
    }

    /**
     * The scene's default actions.
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Help",
                        KeyAction.<Msg>of("toggle-help", new Msg.ToggleHelp(), "?", "toggle help", "?"),
                        KeyAction.<Msg>of("remap", new Msg.Remap(), "r", "remap help key", "r")),
                KeyMap.group(
                        "Focus", KeyAction.<Msg>of("focus-next", new Msg.ToggleFocus(), "tab", "next field", "tab")),
                KeyMap.group("General", KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /**
     * The same actions after the remap: toggle-help answers {@code h} as well, and remap is disabled
     * so it neither fires nor appears in help.
     *
     * @return the remapped map
     */
    public static KeyMap<Msg> remappedKeys() {
        // The label is a display decision a remap keeps, so this one clears it to derive the new
        // label from the new aliases.
        KeyMap<Msg> keys = defaultKeys().withAliases("toggle-help", "?", "h");
        KeyAction<Msg> relabelled = keys.action("toggle-help").orElseThrow().withLabel("");
        return keys.with(relabelled).withEnabled("remap", false);
    }

    /**
     * The selectors' actions, kept here so the help renders the same definition the widgets route.
     *
     * @return the selector map
     */
    public static KeyMap<Selector.Msg> selectorKeys() {
        return Selector.defaultKeys();
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /** The demo scene: two mounted selectors, a status row, and the help block. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> initialKeys = defaultKeys();

        private final KeyMap<Msg> alternateKeys = remappedKeys();

        private final KeyMap<Selector.Msg> childKeys = selectorKeys();

        private final Mount<Model, Msg, Never, Selector.Model, Selector.Msg, Selector.Output> left =
                selectorMount("left", childKeys, Model::left, Model::withLeft, Msg.Left::new);

        private final Mount<Model, Msg, Never, Selector.Model, Selector.Msg, Selector.Output> right =
                selectorMount("right", childKeys, Model::right, Model::withRight, Msg.Right::new);

        private static Mount<Model, Msg, Never, Selector.Model, Selector.Msg, Selector.Output> selectorMount(
                String side,
                KeyMap<Selector.Msg> keys,
                Function<Model, Selector.Model> get,
                BiFunction<Model, Selector.Model, Model> replace,
                Function<Selector.Msg, Msg> messages) {
            return Mount.of(
                    ComponentId.of(side),
                    new Selector(new Selector.Config(OPTIONS, ACTIVE, INACTIVE), keys),
                    ModelLens.of(get, replace),
                    messages,
                    OutputRoute.toMessage(output -> new Msg.Output(side, output)));
        }

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // A selector starts focused, so the scene decides: the left field owns focus, and the
            // right one paints its selection in the inactive style until Tab moves focus there.
            Model model = new Model(
                    left.initialize(context).model().withFocus(true),
                    right.initialize(context).model().withFocus(false),
                    true,
                    false,
                    initialKeys,
                    false,
                    "none");
            return Update.next(model);
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Left value -> stamped(left.update(model, value.value()), describe(value.value()));
                case Msg.Right value -> stamped(right.update(model, value.value()), describe(value.value()));
                case Msg.Output output -> Update.next(model.withLast(describe(output)));
                case Msg.ToggleHelp toggle ->
                    Update.next(model.withExpanded(!model.expanded()).withLast("toggle-help"));
                case Msg.Remap remap ->
                    Update.next(model.withKeys(alternateKeys, true).withLast("remap"));
                case Msg.ToggleFocus focus -> {
                    boolean toLeft = !model.leftFocused();
                    Model next = model.withFocus(
                            toLeft,
                            model.left().withFocus(toLeft),
                            model.right().withFocus(!toLeft));
                    yield Update.next(next.withLast(toLeft ? "focus-left" : "focus-right"));
                }
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            View<Msg> fields = Views.row(
                    Views.fixed(COLUMN, Views.column(Views.text("left", TITLE), left.view(model, context))),
                    Views.fixed(COLUMN, Views.column(Views.text("right", TITLE), right.view(model, context))));
            View<Msg> help = Views.column(
                    Help.view(model.keys(), model.expanded(), width), Help.view(childKeys, model.expanded(), width));
            View<Msg> body = Views.column(Views.text(status(model)), Views.gap(1), fields, Views.gap(1), help);
            // The scene's actions are capture bindings: they run before the focused selector, so a
            // scene shortcut is consumed once and never also reaches the child.
            return Views.keyed(model.keys().bindings(), body, List.of());
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(left.tree(model), right.tree(model));
        }

        /**
         * Keeps everything a mount produced - its command carries an output routed to a parent
         * message - and records the gesture the status row names.
         */
        private static Update<Model, Msg, Never> stamped(Update<Model, Msg, Never> result, String last) {
            return new Update<>(result.model().withLast(last), result.command(), result.outputs());
        }

        private static String describe(Selector.Msg message) {
            return message.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        }

        private static String status(Model model) {
            return "left=" + value(model.left())
                    + " right=" + value(model.right())
                    + " focus=" + (model.leftFocused() ? "left" : "right")
                    + " help=" + (model.expanded() ? "full" : "compact")
                    + " keys=" + (model.remapped() ? "remapped" : "default")
                    + " last=" + model.last();
        }

        private static String value(Selector.Model model) {
            return model.selected() < 0 ? "none" : model.current();
        }

        private static String describe(Msg.Output output) {
            return switch (output.value()) {
                case Selector.Output.Chosen chosen -> "chosen:" + chosen.value();
                case Selector.Output.Cancelled ignored -> "cancelled:" + output.side();
            };
        }
    }
}
