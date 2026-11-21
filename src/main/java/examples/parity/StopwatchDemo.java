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
import io.github.dhoard.braxton.input.Binding;
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
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Stopwatch;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 10 parity demo: two stopwatches with their whole lifecycle on the keyboard.
 *
 * <p>Two {@link Stopwatch} components are mounted into one scene and driven by the scene's own
 * gestures, so the watch keeps exactly the state the plan gives it - elapsed time, a run identity
 * and whether a run is live - and the scene owns everything else. Both watches are mounted at once,
 * so what a user watches is that starting, stopping or resetting one of them leaves the other's
 * measurement alone.</p>
 *
 * <ul>
 *   <li>{@code s} starts the left watch while it is stopped and stops it while it runs. The key
 *       carries two actions and the help line shows the one that is enabled, which is the gesture
 *       the reference stopwatch binds: one key, two words.</li>
 *   <li>{@code r} resets the left watch: elapsed back to zero, running state kept.</li>
 *   <li>{@code k} and {@code l} are the same two gestures for the right watch.</li>
 * </ul>
 *
 * <p>Every status field is derived from the mounted widgets' own facts rather than stored beside
 * them, so a row cannot disagree with the watch it describes: {@code state=} is the liveness,
 * {@code epoch=} the identity of the current run and {@code nanos=} the exact elapsed time. The
 * painted rows are the components' own views, so what a terminal verifier reads is what the widget
 * renders rather than a second copy of its formatter.</p>
 */
public final class StopwatchDemo {

    private static final Style STATUS = Style.NONE.withBold();

    /**
     * The prefix the left watch's painted row carries, so both rows line up.
     */
    private static final String LEFT_LABEL = "left   ";

    /**
     * The prefix the right watch's painted row carries, so both rows line up.
     */
    private static final String RIGHT_LABEL = "right  ";

    private StopwatchDemo() {}

    /**
     * The scene state.
     *
     * @param left the left mount's own stopwatch state
     * @param right the right mount's own stopwatch state
     * @param last what the scene last did
     */
    public record Model(Stopwatch.Model left, Stopwatch.Model right, String last) {

        /**
         * Substitutes a stopped watch and no action for missing values.
         */
        public Model {
            left = left == null ? Stopwatch.Model.stopped() : left;
            right = right == null ? Stopwatch.Model.stopped() : right;
            last = last == null ? "none" : last;
        }

        /**
         * The definition both watches are mounted with.
         *
         * @return the immutable definition
         */
        public Stopwatch.Config config() {
            return Stopwatch.Config.defaults();
        }

        /**
         * One watch's state.
         *
         * @param leftPane whether the left watch is wanted
         * @return the left or the right watch's state
         */
        private Stopwatch.Model watch(boolean leftPane) {
            return leftPane ? left : right;
        }

        Model withWatch(boolean leftPane, Stopwatch.Model value) {
            return leftPane ? new Model(value, right, last) : new Model(left, value, last);
        }

        Model withLast(String value) {
            return new Model(left, right, value);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * A message from the left mounted stopwatch.
         *
         * @param value the stopwatch's message
         */
        record Left(Stopwatch.Msg value) implements Msg {}

        /**
         * A message from the right mounted stopwatch.
         *
         * @param value the stopwatch's message
         */
        record Right(Stopwatch.Msg value) implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's actions for one state.
     *
     * <p>The stopwatches own no keys, so this one map is the whole keyboard: every gesture here is a
     * mounted watch's own message, wrapped so the mount can route it. Each watch has a start and a
     * stop action on one key and exactly one of the pair is enabled, so {@code s} means what the
     * watch is doing and the help line says the same word. The map is built from the state because
     * that enabled flag is state: after a start, help offers {@code s stop left} instead.</p>
     *
     * @param model the scene state the actions are built for
     * @return the map, in help order
     */
    public static KeyMap<Msg> keys(Model model) {
        return KeyMap.of(
                KeyMap.group(
                        "Left watch",
                        KeyAction.<Msg>of("left-start", new Msg.Left(new Stopwatch.Msg.Start()), "s", "start left", "s")
                                .withEnabled(!model.left().running()),
                        KeyAction.<Msg>of("left-stop", new Msg.Left(new Stopwatch.Msg.Stop()), "s", "stop left", "s")
                                .withEnabled(model.left().running()),
                        KeyAction.<Msg>of(
                                "left-reset", new Msg.Left(new Stopwatch.Msg.Reset()), "r", "reset left", "r")),
                KeyMap.group(
                        "Right watch",
                        KeyAction.<Msg>of(
                                        "right-start",
                                        new Msg.Right(new Stopwatch.Msg.Start()),
                                        "k",
                                        "start right",
                                        "k")
                                .withEnabled(!model.right().running()),
                        KeyAction.<Msg>of("right-stop", new Msg.Right(new Stopwatch.Msg.Stop()), "k", "stop right", "k")
                                .withEnabled(model.right().running()),
                        KeyAction.<Msg>of(
                                "right-reset", new Msg.Right(new Stopwatch.Msg.Reset()), "l", "reset right", "l")),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
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

    /**
     * Two mounted stopwatches, their status rows and one help line.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // Both watches mount stopped at zero: a watch the user did not start must not be
            // counting, and starting needs no clock reading here.
            return Update.next(new Model(null, null, "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted stopwatch reduces its own message into the scene's slot.
                case Msg.Left value -> gesture(model, true, value.value());
                case Msg.Right value -> gesture(model, false, value.value());
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * Each mounted stopwatch declares its own interval, and only while it runs.
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.batch(
                    mount(model, true).subscriptions(model), mount(model, false).subscriptions(model));
        }

        @Override
        public ComponentTree children(Model model) {
            List<ComponentTree.Node> nodes = new ArrayList<>(2);
            nodes.add(mount(model, true).tree(model));
            nodes.add(mount(model, false).tree(model));
            return new ComponentTree(nodes);
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
            return keys(model).bindings();
        }

        /**
         * The scene's rows as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            int cols = context.viewport().cols();
            // The key map is built from this state: the start/stop pair each watch shows in help is
            // the one its own running state enables.
            KeyMap<Msg> keys = keys(model);
            List<Element<Msg>> rows = new ArrayList<>(8);
            // Status rows are one row each however narrow the terminal is: a wrapped diagnostic would
            // push the painted watches and the help down and move what a verifier can assert.
            rows.add(Elements.fixed(1, Elements.text(status("left", model, true), STATUS)));
            rows.add(Elements.fixed(1, Elements.text(status("right", model, false), STATUS)));
            rows.add(Elements.fixed(1, Elements.text(policy(model), STATUS)));
            rows.add(Elements.spacer(1));
            // The painted times are the mounted components' own views, so what a user sees is what
            // the widget renders rather than a second copy of its formatter.
            rows.add(Elements.fixed(
                    1,
                    Elements.row(Elements.text(LEFT_LABEL), mount(model, true).view(model, context))));
            rows.add(Elements.fixed(
                    1,
                    Elements.row(Elements.text(RIGHT_LABEL), mount(model, false).view(model, context))));
            rows.add(Elements.spacer(1));
            rows.add(Help.element(keys, Help.Config.defaults(), false, cols));
            return Elements.column(rows);
        }

        /**
         * One stopwatch, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's definition on every use; its identity, lens and
         * route are the same values every time, so the two mounts stay independent.</p>
         */
        private Mount<Model, Msg, Never, Stopwatch.Model, Stopwatch.Msg, Never> mount(Model model, boolean leftPane) {
            return Mount.of(
                    ComponentId.of(leftPane ? "left" : "right"),
                    new Stopwatch(model.config()),
                    ModelLens.of(state -> state.watch(leftPane), (state, child) -> state.withWatch(leftPane, child)),
                    message -> leftPane ? new Msg.Left(message) : new Msg.Right(message),
                    OutputRoute.toOutput(value -> value));
        }

        /**
         * Applies a message to one mounted watch and logs the gesture it carried.
         *
         * <p>The lifted command is carried through: an armed run asks the runtime for the reading that
         * names its origin, and dropping that command here would leave the watch waiting for a fact
         * nobody requested. A time reading is not a gesture, so the log a verifier reads does not
         * churn with every repaint.</p>
         */
        private Update<Model, Msg, Never> gesture(Model model, boolean leftPane, Stopwatch.Msg message) {
            Update<Model, Msg, Never> reduced = mount(model, leftPane).update(model, message);
            if (message instanceof Stopwatch.Msg.Reading) {
                return reduced;
            }
            return new Update<>(
                    reduced.model().withLast(word(leftPane, message)), reduced.command(), reduced.outputs());
        }

        /**
         * What one gesture did, as the scene's log spells it.
         */
        private static String word(boolean leftPane, Stopwatch.Msg message) {
            String side = leftPane ? "left" : "right";
            return switch (message) {
                case Stopwatch.Msg.Start start -> "start-" + side;
                case Stopwatch.Msg.Stop stop -> "stop-" + side;
                case Stopwatch.Msg.Toggle toggle -> "toggle-" + side;
                case Stopwatch.Msg.Reset reset -> "reset-" + side;
                case Stopwatch.Msg.Reading reading -> "reading-" + side;
            };
        }

        /**
         * One watch's row: which state it is in, which run it is and how much time it measured.
         */
        private static String status(String name, Model model, boolean leftPane) {
            Stopwatch.Model watch = model.watch(leftPane);
            return name + " state=" + (watch.running() ? "running" : "stopped")
                    + " epoch=" + watch.epoch()
                    + " nanos=" + watch.elapsedNanos();
        }

        /**
         * The policy row: the cadence the scene chose and what it last did.
         */
        private static String policy(Model model) {
            return "tick=" + model.config().tickPeriod().toMillis() + "ms last=" + model.last();
        }
    }
}
