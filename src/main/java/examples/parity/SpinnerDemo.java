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
import io.github.dhoard.braxton.widget.Spinner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 07 parity demo: two independently controlled configurable spinners.
 *
 * <p>Two {@link Spinner} components are mounted side by side, each with its own model slot, its own
 * preset and its own subscription. The scene owns everything the widget deliberately does not: the
 * chosen preset, the cadence and the gestures. Every gesture acts on the focused spinner, so one
 * can be stopped while the other keeps turning, or carry a different preset entirely.</p>
 *
 * <ul>
 *   <li>{@code left}/{@code right} cycle the focused spinner's preset, which also restarts its
 *       cycle: a new frame list must not continue the phase of the old one.</li>
 *   <li>{@code space} starts or stops the focused spinner. A stopped spinner keeps the frame it was
 *       showing and drops its tick source; the other spinner is untouched.</li>
 *   <li>{@code c} changes the cadence both spinners are asked to run at, which is what makes the
 *       definition - not the widget - own the timing.</li>
 *   <li>{@code x} removes or restores the second spinner. A removed mount closes its interval, so
 *       the restored spinner starts over stopped at frame one.</li>
 * </ul>
 *
 * <p>Everything the scene reports is on status rows, so a terminal verifier can assert the state
 * without decoding styling. The two painted glyphs are the mounted components' own views.</p>
 */
public final class SpinnerDemo {

    private static final Style STATUS = Style.NONE.withBold();

    /** The preset the first spinner starts on. */
    public static final Spinner.Preset LEFT_PRESET = Spinner.Preset.LINE;

    /** The second spinner starts on a two-column emoji preset. */
    public static final Spinner.Preset RIGHT_PRESET = Spinner.Preset.GLOBE;

    private SpinnerDemo() {}

    /** How fast the two spinners are asked to run; the scene, not the widget, owns this choice. */
    public enum Cadence {
        /** The default: ten frames a second. */
        NORMAL(Duration.ofMillis(100), "100ms"),
        /** Twice as fast, for a busier indicator. */
        FAST(Duration.ofMillis(50), "50ms"),
        /** A slow, calm indicator. */
        SLOW(Duration.ofMillis(250), "250ms");

        private final Duration duration;
        private final String label;

        Cadence(Duration duration, String label) {
            this.duration = duration;
            this.label = label;
        }

        /**
         * The time one frame lasts at this cadence.
         *
         * @return the frame duration
         */
        public Duration duration() {
            return duration;
        }

        /**
         * The cadence's short label, for the status row.
         *
         * @return the label
         */
        public String label() {
            return label;
        }

        /**
         * Another cadence, {@code steps} away and wrapped into the cycle.
         *
         * @param steps how far to move
         * @return the selected cadence
         */
        public Cadence shift(int steps) {
            Cadence[] cadences = values();
            return cadences[Math.floorMod(ordinal() + steps, cadences.length)];
        }
    }

    /**
     * One mounted spinner: the state the widget owns and the definition choice the scene owns.
     *
     * @param model the spinner's own state
     * @param preset the frame definition the scene selected for it
     */
    public record Slot(Spinner.Model model, Spinner.Preset preset) {

        /** Substitutes the stopped state and the default preset for missing values. */
        public Slot {
            model = model == null ? Spinner.Model.stopped() : model;
            preset = preset == null ? Spinner.DEFAULT_PRESET : preset;
        }

        /**
         * The definition this slot paints at a cadence.
         *
         * @param cadence the cadence the scene selected; null is {@link Cadence#NORMAL}
         * @return the immutable definition
         */
        public Spinner.Config config(Cadence cadence) {
            return Spinner.Config.preset(preset)
                    .withFrameDuration((cadence == null ? Cadence.NORMAL : cadence).duration());
        }
    }

    /**
     * The scene state.
     *
     * @param left the first spinner
     * @param right the second spinner, which may be unmounted
     * @param rightMounted whether the second spinner's mount is declared
     * @param leftFocused whether the gestures act on the first spinner
     * @param cadence how fast both spinners are asked to run
     * @param last what the scene last did
     */
    public record Model(
            Slot left, Slot right, boolean rightMounted, boolean leftFocused, Cadence cadence, String last) {

        /** Substitutes a stopped slot, the normal cadence and no action for missing values. */
        public Model {
            left = left == null ? new Slot(null, null) : left;
            right = right == null ? new Slot(null, null) : right;
            cadence = cadence == null ? Cadence.NORMAL : cadence;
            last = last == null ? "none" : last;
        }

        /**
         * The slot the gestures act on.
         *
         * @return the focused slot
         */
        public Slot focused() {
            return leftFocused ? left : right;
        }

        /**
         * One slot.
         *
         * @param leftPane whether the first spinner is meant
         * @return that slot
         */
        public Slot slot(boolean leftPane) {
            return leftPane ? left : right;
        }

        /**
         * The same state with one slot replaced.
         *
         * @param leftPane whether the first spinner is meant
         * @param value the slot's new state
         * @return the new state
         */
        public Model withSlot(boolean leftPane, Slot value) {
            return new Model(
                    leftPane ? value : left, leftPane ? right : value, rightMounted, leftFocused, cadence, last);
        }

        /**
         * The definition one spinner is mounted with.
         *
         * @param leftPane whether the first spinner is meant
         * @return the immutable definition
         */
        public Spinner.Config config(boolean leftPane) {
            return slot(leftPane).config(cadence);
        }

        Model withRightMounted(boolean value) {
            return new Model(left, right, value, leftFocused, cadence, last);
        }

        Model withLeftFocused(boolean value) {
            return new Model(left, right, rightMounted, value, cadence, last);
        }

        Model withCadence(Cadence value) {
            return new Model(left, right, rightMounted, leftFocused, value, last);
        }

        Model withLast(String value) {
            return new Model(left, right, rightMounted, leftFocused, cadence, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the first spinner.
         *
         * @param value the spinner's message
         */
        record Left(Spinner.Msg value) implements Msg {}

        /**
         * A message from the second spinner.
         *
         * @param value the spinner's message
         */
        record Right(Spinner.Msg value) implements Msg {}

        /**
         * Cycles the focused spinner's preset and restarts its cycle.
         *
         * @param delta the direction: -1 for the previous preset, 1 for the next
         */
        record CyclePreset(int delta) implements Msg {}

        /** Starts or stops the focused spinner. */
        record ToggleRun() implements Msg {}

        /** Cycles the cadence both spinners are asked to run at. */
        record CycleCadence() implements Msg {}

        /** Moves the gestures to the other spinner. */
        record ToggleFocus() implements Msg {}

        /** Removes or restores the second spinner's mount. */
        record ToggleSecond() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>The spinners own no keys, so this one map is the whole keyboard: every gesture here acts
     * on the focused spinner, and the help line is rendered from the same map that routes.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Spinner",
                        KeyAction.<Msg>of(
                                "previous-preset", new Msg.CyclePreset(-1), "\u2190", "previous", "left", "h"),
                        KeyAction.<Msg>of("next-preset", new Msg.CyclePreset(1), "\u2192", "next", "right", "l"),
                        KeyAction.<Msg>of("toggle-run", new Msg.ToggleRun(), "space", "run", "space"),
                        KeyAction.<Msg>of("cycle-cadence", new Msg.CycleCadence(), "c", "cadence", "c")),
                KeyMap.group(
                        "Scene",
                        KeyAction.<Msg>of("toggle-focus", new Msg.ToggleFocus(), "tab", "focus", "tab"),
                        KeyAction.<Msg>of("toggle-second", new Msg.ToggleSecond(), "x", "mount", "x"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal. */
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

    /** Two mounted spinners, their status rows and one help line. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // Both spinners start running on different presets; their first tick records the origin,
            // so nothing here reads a clock.
            return Update.next(new Model(
                    new Slot(Spinner.Model.starting(), LEFT_PRESET),
                    new Slot(Spinner.Model.starting(), RIGHT_PRESET),
                    true,
                    true,
                    Cadence.NORMAL,
                    "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted spinner reduces its own message into the scene's slot.
                case Msg.Left value -> mount(model, true).update(model, value.value());
                case Msg.Right value ->
                    model.rightMounted() ? mount(model, false).update(model, value.value()) : Update.next(model);
                case Msg.CyclePreset cycle -> preset(model, cycle.delta());
                case Msg.ToggleRun toggle -> run(model);
                case Msg.CycleCadence cadence -> {
                    Cadence next = model.cadence().shift(1);
                    yield Update.next(model.withCadence(next).withLast("cadence-" + next.label()));
                }
                case Msg.ToggleFocus toggle ->
                    Update.next(model.withLeftFocused(!model.leftFocused())
                            .withLast(model.leftFocused() ? "focus-right" : "focus-left"));
                case Msg.ToggleSecond toggle -> second(model);
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * Each mounted spinner declares its own interval only while it runs.
         *
         * <p>An unmounted spinner declares nothing at all: its node is not in the tree, so a
         * subscription under that path would name an undeclared component.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            Subscription<Msg> left = mount(model, true).subscriptions(model);
            if (!model.rightMounted()) {
                return left;
            }
            return Subscription.batch(left, mount(model, false).subscriptions(model));
        }

        @Override
        public ComponentTree children(Model model) {
            List<ComponentTree.Node> nodes = new ArrayList<>(2);
            nodes.add(mount(model, true).tree(model));
            if (model.rightMounted()) {
                nodes.add(mount(model, false).tree(model));
            }
            return new ComponentTree(nodes);
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            List<View<Msg>> rows = new ArrayList<>(7);
            // Status rows are one row each however narrow the terminal is: a wrapped diagnostic
            // would push the painted glyphs and the help down and move what a verifier can assert.
            rows.add(Views.fixed(1, Views.text(status("left", model, true), STATUS)));
            rows.add(Views.fixed(
                    1, Views.text(model.rightMounted() ? status("right", model, false) : "right=absent", STATUS)));
            rows.add(Views.fixed(1, Views.text(policy(model), STATUS)));
            rows.add(Views.gap(1));
            // The two glyphs are the mounted components' own views, so what is painted is what the
            // widget renders rather than a second copy of its frame selection.
            rows.add(Views.row(
                    Views.text("left  "),
                    mount(model, true).view(model, context),
                    Views.text("    right  "),
                    model.rightMounted() ? mount(model, false).view(model, context) : Views.<Msg>blank()));
            rows.add(Views.gap(1));
            rows.add(Help.view(keys, false, cols));
            return Views.onKeys(keys.bindings(), Views.column(rows));
        }

        /**
         * One spinner, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's preset and cadence on every use. Nothing else
         * about the mount changes: its identity, its lens and its route are the same values every
         * time.</p>
         */
        private Mount<Model, Msg, Never, Spinner.Model, Spinner.Msg, Never> mount(Model model, boolean leftPane) {
            return leftPane
                    ? Mount.of(
                            ComponentId.of("left"),
                            new Spinner(model.config(true)),
                            ModelLens.of(
                                    state -> state.left().model(),
                                    (state, child) -> state.withSlot(
                                            true, new Slot(child, state.left().preset()))),
                            Msg.Left::new,
                            OutputRoute.toOutput(value -> value))
                    : Mount.of(
                            ComponentId.of("right"),
                            new Spinner(model.config(false)),
                            ModelLens.of(
                                    state -> state.right().model(),
                                    (state, child) -> state.withSlot(
                                            false, new Slot(child, state.right().preset()))),
                            Msg.Right::new,
                            OutputRoute.toOutput(value -> value));
        }

        /**
         * Cycles the focused spinner's preset.
         *
         * <p>The reset is what makes a new definition start at its own first frame instead of
         * continuing the phase of the definition it replaced.</p>
         */
        private Update<Model, Msg, Never> preset(Model model, int delta) {
            boolean leftPane = model.leftFocused();
            Slot slot = model.slot(leftPane);
            Spinner.Preset next = slot.preset().shift(delta);
            Model renamed = model.withSlot(leftPane, new Slot(slot.model(), next));
            Update<Model, Msg, Never> reset = mount(renamed, leftPane).update(renamed, new Spinner.Msg.Reset());
            return Update.next(reset.model().withLast("preset-" + next.label()));
        }

        /** Starts or stops the focused spinner, leaving the other one's cycle alone. */
        private Update<Model, Msg, Never> run(Model model) {
            boolean leftPane = model.leftFocused();
            boolean running = model.slot(leftPane).model().running();
            Spinner.Msg action = running ? new Spinner.Msg.Stop() : new Spinner.Msg.Start();
            Update<Model, Msg, Never> applied = mount(model, leftPane).update(model, action);
            return Update.next(applied.model().withLast(running ? "stopped" : "started"));
        }

        /**
         * Removes or restores the second spinner's mount.
         *
         * <p>Removing it drops the slot's cycle as well as the mount: the tick source belongs to the
         * mount, so a restored spinner begins from a fresh, stopped state.</p>
         */
        private Update<Model, Msg, Never> second(Model model) {
            if (model.rightMounted()) {
                Slot cleared = new Slot(Spinner.Model.stopped(), model.right().preset());
                return Update.next(
                        model.withRightMounted(false).withSlot(false, cleared).withLast("removed"));
            }
            return Update.next(model.withRightMounted(true).withLast("restored"));
        }

        /** One spinner's status row: preset, frame, running state and elapsed time. */
        private static String status(String name, Model model, boolean leftPane) {
            Slot slot = model.slot(leftPane);
            Spinner.Config config = model.config(leftPane);
            Spinner.Model state = slot.model();
            return name + "=" + slot.preset().label()
                    + " frame=" + (state.frameIndex(config) + 1) + "/" + config.frameCount()
                    + " run=" + (state.running() ? "on" : "off")
                    + " t=" + (state.elapsedNanos() / 1_000_000L) + "ms";
        }

        /** The policy row: what the gestures act on, how fast, and what they last did. */
        private static String policy(Model model) {
            return "focus=" + (model.leftFocused() ? "left" : "right")
                    + " cadence=" + model.cadence().label()
                    + " mounted=" + (model.rightMounted() ? "on" : "off")
                    + " last=" + model.last();
        }
    }
}
