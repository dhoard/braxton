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
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 09 parity demo: one local countdown with its whole lifecycle on the keyboard.
 *
 * <p>A single {@link Countdown} is mounted and driven by the scene's own gestures, so every
 * transition the widget defines is one keystroke away: start, pause, resume, reset and expiry. The
 * scene owns what the widget deliberately does not - the configured duration, the adjustment step
 * and the help - and the countdown owns the schedule, so what a user watches is the widget running
 * rather than a value the scene recomputed.</p>
 *
 * <ul>
 *   <li>{@code space} is the one-key gesture: it pauses a running countdown, resumes a paused one and
 *       starts a finished one over.</li>
 *   <li>{@code s} starts a run of the configured duration from the beginning, which is what makes it
 *       different from resuming: the time a paused countdown was holding is discarded.</li>
 *   <li>{@code r} resets to the configured duration, stopped.</li>
 *   <li>{@code left}/{@code right} shorten or lengthen the configured duration. A new duration resets
 *       the countdown, because a run of the old length would no longer mean anything.</li>
 * </ul>
 *
 * <p>A timeout is reported once, as the widget's {@link Countdown.Output.Timeout}, and the scene
 * counts what it was told: the {@code timeouts=} field only moves when an output crossed the mount,
 * so it is evidence of one expiry per run rather than a second guess at the state. Everything the
 * scene reports is on status rows, so a terminal verifier can assert the whole lifecycle without
 * decoding styled cells.</p>
 */
public final class CountdownDemo {

    /**
     * The countdown a user of this demo gets when no length is given.
     */
    public static final Duration DEFAULT_DURATION = Duration.ofSeconds(90);

    /**
     * The countdown the parity probe runs.
     *
     * <p>Short enough that a terminal verifier observes a whole run - start, pause, resume, reset and
     * the expiry - without waiting on a wall clock for a minute and a half, and long enough that each
     * second of it is visible.</p>
     */
    public static final Duration TEST_DURATION = Duration.ofSeconds(5);

    /**
     * How much one duration gesture adds or removes.
     */
    public static final Duration ADJUSTMENT = Duration.ofSeconds(15);

    /**
     * The shortest duration the scene allows.
     */
    public static final Duration MIN_DURATION = Duration.ofSeconds(5);

    /**
     * The longest duration the scene allows; an hour would already paint a third field.
     */
    public static final Duration MAX_DURATION = Duration.ofMinutes(10);

    private static final Style STATUS = Style.NONE.withBold();

    private CountdownDemo() {}

    /**
     * The scene state.
     *
     * @param countdown the mounted countdown's own state
     * @param duration the configured duration the scene owns
     * @param timeouts how many timeouts the scene was told about
     * @param last what the scene last did
     */
    public record Model(Countdown.Model countdown, Duration duration, int timeouts, String last) {

        /**
         * Substitutes an idle countdown, the default duration and no action for missing values.
         */
        public Model {
            duration = clamp(duration == null ? DEFAULT_DURATION : duration);
            countdown = countdown == null ? Countdown.Model.stopped(duration) : countdown;
            timeouts = Math.max(0, timeouts);
            last = last == null ? "none" : last;
        }

        /**
         * The countdown's definition: the scene's duration at the widget's default cadence.
         *
         * @return the immutable definition
         */
        public Countdown.Config config() {
            return Countdown.Config.of(duration);
        }

        /**
         * Whether the countdown has nothing left.
         *
         * @return whether the run expired
         */
        public boolean expired() {
            return countdown.expired();
        }

        Model withCountdown(Countdown.Model value) {
            return new Model(value, duration, timeouts, last);
        }

        Model withDuration(Duration value) {
            return new Model(countdown, value, timeouts, last);
        }

        Model withTimeouts(int value) {
            return new Model(countdown, duration, value, last);
        }

        Model withLast(String value) {
            return new Model(countdown, duration, timeouts, value);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * A message from the mounted countdown.
         *
         * @param value the countdown's message
         */
        record Count(Countdown.Msg value) implements Msg {}

        /**
         * An output the mounted countdown reported.
         *
         * @param value the countdown's output
         */
        record Timed(Countdown.Output value) implements Msg {}

        /**
         * Starts a run of the configured duration from the beginning.
         */
        record Start() implements Msg {}

        /**
         * Pauses a running countdown, resumes a paused one, or starts a finished one over.
         */
        record Toggle() implements Msg {}

        /**
         * Changes the configured duration.
         *
         * @param seconds how many seconds to add; a negative value shortens the countdown
         */
        record Adjust(long seconds) implements Msg {}

        /**
         * Resets to the configured duration, stopped.
         */
        record Reset() implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>The countdown owns no keys, so this one map is the whole keyboard: the gestures drive the
     * mounted countdown, and the help line is rendered from the same map that routes.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Countdown",
                        KeyAction.<Msg>of("start", new Msg.Start(), "s", "start over", "s"),
                        KeyAction.<Msg>of("toggle", new Msg.Toggle(), "space", "start/pause", "space"),
                        KeyAction.<Msg>of("reset", new Msg.Reset(), "r", "reset", "r")),
                KeyMap.group(
                        "Duration",
                        KeyAction.<Msg>of(
                                "shorter", new Msg.Adjust(-ADJUSTMENT.toSeconds()), "\u2190", "shorter", "left", "h"),
                        KeyAction.<Msg>of(
                                "longer", new Msg.Adjust(ADJUSTMENT.toSeconds()), "\u2192", "longer", "right", "l")),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /**
     * Runs the demo at its default duration against the owned terminal.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return run(DEFAULT_DURATION);
    }

    /**
     * Runs the demo at a length against the owned terminal.
     *
     * @param duration the countdown to configure, clamped to the scene's bounds
     * @return the session result
     */
    public static ExitResult run(Duration duration) {
        return Application.run(new Scene(duration));
    }

    /**
     * Runs the demo.
     *
     * @param args an optional countdown length in whole seconds, clamped to the scene's bounds; empty
     *     uses {@link #DEFAULT_DURATION}
     */
    public static void main(String[] args) {
        Duration duration = DEFAULT_DURATION;
        if (args.length > 1) {
            usage();
            return;
        }
        if (args.length == 1) {
            try {
                duration = Duration.ofSeconds(Long.parseLong(args[0]));
            } catch (NumberFormatException invalid) {
                usage();
                return;
            }
        }
        ExitResult result = run(duration);
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * Reports how to run the demo and ends the process with a usage status.
     */
    private static void usage() {
        System.err.println("usage: CountdownDemo [seconds]");
        System.exit(2);
    }

    /**
     * A duration clamped into the scene's bounds.
     *
     * @param duration the duration to clamp; null is {@link #DEFAULT_DURATION}
     * @return the clamped duration
     */
    public static Duration clamp(Duration duration) {
        Duration value = duration == null ? DEFAULT_DURATION : duration;
        if (value.compareTo(MIN_DURATION) < 0) {
            return MIN_DURATION;
        }
        if (value.compareTo(MAX_DURATION) > 0) {
            return MAX_DURATION;
        }
        return value;
    }

    /**
     * One mounted countdown, its status rows and one help line.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();
        private final Duration duration;

        /**
         * A scene whose countdown starts at a duration.
         *
         * @param duration the configured duration; clamped to the scene's bounds
         */
        public Scene(Duration duration) {
            this.duration = clamp(duration);
        }

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // The countdown mounts idle at the configured duration: the scene's timer does not run
            // until the user starts it, and nothing here reads a clock.
            return Update.next(new Model(null, duration, 0, "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted countdown reduces its own messages into the scene's slot.
                case Msg.Count value -> mount(model).update(model, value.value());
                case Msg.Timed timed -> timed(model, timed.value());
                case Msg.Start start -> gesture(model, new Countdown.Msg.Start(), "start");
                case Msg.Toggle toggle -> gesture(model, new Countdown.Msg.Toggle(), toggleWord(model));
                case Msg.Adjust adjust -> adjust(model, Duration.ofSeconds(adjust.seconds()));
                case Msg.Reset reset -> gesture(model, new Countdown.Msg.Reset(), "reset");
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * The countdown declares its own interval, and only while it runs.
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return mount(model).subscriptions(model);
        }

        @Override
        public ComponentTree children(Model model) {
            List<ComponentTree.Node> nodes = new ArrayList<>(1);
            nodes.add(mount(model).tree(model));
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
            return keys.bindings();
        }

        /**
         * The scene's rows as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            int cols = context.viewport().cols();
            List<Element<Msg>> rows = new ArrayList<>(6);
            // Status rows are one row each however narrow the terminal is: a wrapped diagnostic would
            // push the painted countdown and the help down and move what a verifier can assert.
            rows.add(Elements.fixed(1, Elements.text(status(model), STATUS)));
            rows.add(Elements.fixed(1, Elements.text(policy(model), STATUS)));
            rows.add(Elements.spacer(1));
            // The painted time is the mounted component's own view, so what a user sees is what the
            // widget renders rather than a second copy of its formatter.
            rows.add(Elements.fixed(
                    1,
                    Elements.row(
                            Elements.text("remaining  "),
                            mount(model).view(model, context),
                            Elements.text(model.expired() ? "   time is up" : ""))));
            rows.add(Elements.spacer(1));
            rows.add(Help.element(keys, Help.Config.defaults(), false, cols));
            return Elements.column(rows);
        }

        /**
         * The countdown, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's duration on every use; its identity, lens and
         * routes are the same values every time, so an adjusted duration is a changed definition and
         * not a changed mount.</p>
         */
        private Mount<Model, Msg, Never, Countdown.Model, Countdown.Msg, Countdown.Output> mount(Model model) {
            return Mount.of(
                    ComponentId.of("countdown"),
                    new Countdown(model.config()),
                    ModelLens.of(Model::countdown, Model::withCountdown),
                    Msg.Count::new,
                    OutputRoute.toMessage(Msg.Timed::new));
        }

        /**
         * Applies a gesture to the mounted countdown and logs what the scene asked for.
         *
         * <p>The lifted command is carried through: an armed run asks the runtime for the reading
         * that names its deadline, and dropping that command here would leave the countdown waiting
         * for a fact nobody requested.</p>
         */
        private Update<Model, Msg, Never> gesture(Model model, Countdown.Msg message, String word) {
            Update<Model, Msg, Never> reduced = mount(model).update(model, message);
            return new Update<>(reduced.model().withLast(word), reduced.command(), reduced.outputs());
        }

        /**
         * Counts what the countdown reported.
         *
         * <p>The counter only moves when an output crossed the mount, so a single termination of the
         * run reads as one timeout however many readings followed it.</p>
         */
        private static Update<Model, Msg, Never> timed(Model model, Countdown.Output output) {
            return switch (output) {
                case Countdown.Output.Timeout timeout ->
                    Update.next(model.withTimeouts(model.timeouts() + 1).withLast("timeout"));
            };
        }

        /**
         * Changes the configured duration and resets the countdown to it.
         */
        private Update<Model, Msg, Never> adjust(Model model, Duration delta) {
            Duration next = clamp(model.duration().plus(delta));
            if (next.equals(model.duration())) {
                // The scene's bounds are part of its contract, so a gesture past one says so instead
                // of pretending the duration changed.
                return Update.next(model.withLast("limit"));
            }
            Model changed = model.withDuration(next);
            Update<Model, Msg, Never> reset = mount(changed).update(changed, new Countdown.Msg.Reset());
            return new Update<>(
                    reset.model().withLast("duration-" + next.toSeconds()), reset.command(), reset.outputs());
        }

        /**
         * What the toggle gesture did, read from the state the gesture saw.
         */
        private static String toggleWord(Model model) {
            Countdown.Model countdown = model.countdown();
            if (countdown.running()) {
                return "pause";
            }
            // A countdown that has never run, and one with nothing left, begins rather than continues:
            // there is no remainder for either of them to carry on from.
            return countdown.epoch() == 0L || countdown.expired() ? "start" : "resume";
        }

        /**
         * The countdown's row: which state it is in, which run it is and how much time is left.
         *
         * <p>The state word is derived from the widget's own facts rather than stored beside them, so
         * it cannot disagree with them: a running run is {@code running}, a run with nothing left is
         * {@code expired}, and a stopped run is {@code idle} before its first start and {@code
         * paused} afterward.</p>
         */
        private static String status(Model model) {
            Countdown.Model countdown = model.countdown();
            return "countdown state=" + stateWord(countdown)
                    + " epoch=" + countdown.epoch()
                    + " nanos=" + countdown.remainingNanos()
                    + " timeouts=" + model.timeouts();
        }

        /**
         * One state word for the countdown's own facts.
         */
        private static String stateWord(Countdown.Model countdown) {
            if (countdown.running()) {
                return "running";
            }
            if (countdown.expired()) {
                return "expired";
            }
            return countdown.epoch() == 0L ? "idle" : "paused";
        }

        /**
         * The policy row: the duration and cadence the scene chose, and what it last did.
         */
        private static String policy(Model model) {
            return "duration=" + Countdown.clock(model.duration())
                    + " tick=" + model.config().tickPeriod().toMillis() + "ms"
                    + " last=" + model.last();
        }
    }
}
