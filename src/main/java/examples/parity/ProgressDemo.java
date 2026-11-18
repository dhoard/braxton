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
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Size;
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
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.AnimatedProgress;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.ProgressBar;
import io.github.dhoard.braxton.widget.Spinner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 08 parity demo: an animated progress bar, a static one, and a mock package manager.
 *
 * <p>The scene is a local, seeded installation run: a fixed list of six packages, an
 * {@link AnimatedProgress} that eases towards how many of them are done, a static
 * {@link ProgressBar} painted from the same target, and a {@link Spinner} turning while work
 * remains. Nothing is fetched, installed or read from the file system - the whole run is the
 * scene's own data - so the demo shows the two progress regimes side by side without a real
 * package manager.</p>
 *
 * <p>Every gesture advances the scene's own state and lets the widget animate towards it, so what
 * a user watches is the widget easing rather than a value the scene computed:</p>
 *
 * <ul>
 *   <li>{@code s} completes the next package, which retargets the bar. Pressing it again while the
 *       bar is still moving retargets from wherever the fill has reached, so the bar bends towards
 *       the new target instead of restarting.</li>
 *   <li>{@code u} gives a package back, which is the same thing in the other direction.</li>
 *   <li>{@code r} starts the run over.</li>
 *   <li>{@code m} cycles the bar between smooth motion and the instant, reduced-motion
 *       definition, which reaches the target at once and holds no timer.</li>
 *   <li>{@code b} cycles how both bars paint their fill: whole cells, a graded partial cell, a
 *       start-to-end colour blend, or both refinements together.</li>
 * </ul>
 *
 * <p>Everything the scene reports is on status rows, so a terminal verifier can assert the state
 * without decoding styled cells. The two painted bars come from one definition, so the animated row
 * is the static row with its own label as soon as the bar comes to rest.</p>
 */
public final class ProgressDemo {

    /** The seeded package list the mock installer works through; nothing is fetched. */
    public static final List<String> PACKAGES =
            List.of("figlet", "glamour", "lipgloss", "harmonica", "bubbles", "bubbletea");

    /** The demo's response time: slower than the widget default so the easing can be watched. */
    public static final Duration RESPONSE_TIME = Duration.ofMillis(600);

    /** The cadence the animated bar is asked to repaint at. */
    public static final Duration TICK_PERIOD = Duration.ofMillis(16);

    /** The narrowest bar the scene lays out. */
    public static final int MIN_BAR_WIDTH = 8;

    /** The widest bar the scene lays out. */
    public static final int MAX_BAR_WIDTH = 32;

    /** The colour a finished cell is painted with when no blend overrides it. */
    public static final Color FILLED = new Color(0x4C, 0xC0, 0x7A);

    /** The colour a cell that is still to come is painted with. */
    public static final Color EMPTY = new Color(0x5A, 0x5A, 0x5A);

    /** The colour the blended fill starts at. */
    public static final Color BLEND_START = new Color(0x2B, 0x8C, 0xFF);

    /** The colour the blended fill ends at. */
    public static final Color BLEND_END = new Color(0xC0, 0x5B, 0xFF);

    private static final Style STATUS = Style.NONE.withBold();

    private static final SubscriptionId RESIZE = SubscriptionId.of("resize");

    private ProgressDemo() {}

    /** How the fill of both bars is painted; the scene owns this choice, not the widget. */
    public enum Fill {
        /** Whole cells only: what a bar has always painted. */
        PLAIN("plain"),
        /** Whole cells plus an eighth-block glyph where the fill stops. */
        FRACTIONAL("partial"),
        /** Whole cells, blended from the start colour to the end colour. */
        BLEND("blend"),
        /** Both refinements together. */
        FRACTIONAL_BLEND("partial+blend");

        private final String label;

        Fill(String label) {
            this.label = label;
        }

        /**
         * The fill's name, for the status row.
         *
         * @return the label
         */
        public String label() {
            return label;
        }

        /**
         * This fill's builder refinements applied to a bar.
         *
         * @param bar the bar to refine
         * @return the refined bar
         */
        public ProgressBar apply(ProgressBar bar) {
            return switch (this) {
                case PLAIN -> bar;
                case FRACTIONAL -> bar.fractional(true);
                case BLEND -> bar.blend(BLEND_START, BLEND_END);
                case FRACTIONAL_BLEND -> bar.fractional(true).blend(BLEND_START, BLEND_END);
            };
        }

        /**
         * Another fill, {@code steps} away and wrapped into the set.
         *
         * @param steps how far to move
         * @return the selected fill
         */
        public Fill shift(int steps) {
            Fill[] fills = values();
            return fills[Math.floorMod(ordinal() + steps, fills.length)];
        }
    }

    /**
     * The scene state.
     *
     * @param bar the animated bar's own state
     * @param spinner the install spinner's own state
     * @param installed how many of the seeded packages are done
     * @param width the bar width the scene last laid out, derived from the viewport
     * @param motion how the bar is asked to reach its target
     * @param fill how both bars paint their fill
     * @param last what the scene last did
     */
    public record Model(
            AnimatedProgress.Model bar,
            Spinner.Model spinner,
            int installed,
            int width,
            AnimatedProgress.Motion motion,
            Fill fill,
            String last) {

        /** Substitutes a resting bar, a stopped spinner and the scene defaults for missing values. */
        public Model {
            bar = bar == null ? AnimatedProgress.Model.at(0.0) : bar;
            spinner = spinner == null ? Spinner.Model.stopped() : spinner;
            installed = Math.max(0, installed);
            width = Math.max(1, width);
            motion = motion == null ? AnimatedProgress.Motion.SMOOTH : motion;
            fill = fill == null ? Fill.PLAIN : fill;
            last = last == null ? "none" : last;
        }

        /**
         * The normalized target the run has reached.
         *
         * @return the fraction of the seeded packages that are done
         */
        public double target() {
            return (double) installed / PACKAGES.size();
        }

        /**
         * The package the mock installer would work on next.
         *
         * @return the package name, or an empty string when the run is complete
         */
        public String currentPackage() {
            return installed < PACKAGES.size() ? PACKAGES.get(installed) : "";
        }

        /**
         * Whether every seeded package is done.
         *
         * @return whether the run is complete
         */
        public boolean complete() {
            return installed >= PACKAGES.size();
        }

        /**
         * The animated bar's definition: the scene's width, fill and motion at the demo's cadence.
         *
         * @return the immutable definition
         */
        public AnimatedProgress.Config config() {
            return AnimatedProgress.Config.of(fill.apply(ProgressBar.of(width)
                            .colors(FILLED, EMPTY)
                            .showPercentage(true)
                            .prepend(StyledText.plain("install "))))
                    .withMotion(motion)
                    .withResponseTime(RESPONSE_TIME)
                    .withTickPeriod(TICK_PERIOD);
        }

        /**
         * The static bar painted from the same target.
         *
         * <p>It is a pure value rather than a component: nothing about it moves, so it holds no
         * subscription and is simply rendered.</p>
         *
         * @return the bar definition
         */
        public ProgressBar staticBar() {
            return fill.apply(ProgressBar.of(width)
                    .colors(FILLED, EMPTY)
                    .showPercentage(true)
                    .prepend(StyledText.plain("detail  ")));
        }

        /**
         * The bar width a terminal {@code cols} columns wide is laid out at.
         *
         * @param cols the viewport columns
         * @return the bar width, clamped to the scene's own bounds
         */
        public static int barWidth(int cols) {
            return Math.max(MIN_BAR_WIDTH, Math.min(MAX_BAR_WIDTH, cols - 16));
        }

        Model withBar(AnimatedProgress.Model value) {
            return new Model(value, spinner, installed, width, motion, fill, last);
        }

        Model withSpinner(Spinner.Model value) {
            return new Model(bar, value, installed, width, motion, fill, last);
        }

        Model withInstalled(int value) {
            return new Model(bar, spinner, value, width, motion, fill, last);
        }

        Model withWidth(int value) {
            return new Model(bar, spinner, installed, value, motion, fill, last);
        }

        Model withMotion(AnimatedProgress.Motion value) {
            return new Model(bar, spinner, installed, width, value, fill, last);
        }

        Model withFill(Fill value) {
            return new Model(bar, spinner, installed, width, motion, value, last);
        }

        Model withLast(String value) {
            return new Model(bar, spinner, installed, width, motion, fill, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the animated bar.
         *
         * @param value the bar's message
         */
        record Bar(AnimatedProgress.Msg value) implements Msg {}

        /**
         * A message from the install spinner.
         *
         * @param value the spinner's message
         */
        record Spin(Spinner.Msg value) implements Msg {}

        /** Completes the next seeded package. */
        record Install() implements Msg {}

        /** Gives the last completed package back. */
        record Uninstall() implements Msg {}

        /** Starts the run over. */
        record Reset() implements Msg {}

        /** Cycles the animated bar between smooth and instant motion. */
        record CycleMotion() implements Msg {}

        /** Cycles how both bars paint their fill. */
        record CycleFill() implements Msg {}

        /**
         * The terminal was resized, so the bars are laid out again.
         *
         * @param size the new terminal size
         */
        record Resized(Size size) implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>The widgets own no keys, so this one map is the whole keyboard: the install gestures drive
     * the bar, and the help line is rendered from the same map that routes.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Packages",
                        KeyAction.<Msg>of("install", new Msg.Install(), "s", "install", "space", "s"),
                        KeyAction.<Msg>of("uninstall", new Msg.Uninstall(), "u", "uninstall", "backspace", "u"),
                        KeyAction.<Msg>of("reset", new Msg.Reset(), "r", "reset", "r")),
                KeyMap.group(
                        "Fill",
                        KeyAction.<Msg>of("cycle-motion", new Msg.CycleMotion(), "m", "motion", "m"),
                        KeyAction.<Msg>of("cycle-fill", new Msg.CycleFill(), "b", "fill", "b")),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
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

    /** One animated bar, one static bar, one spinner and the status rows that spell them out. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // The bar starts at rest on zero and the spinner turns from its first tick, so nothing
            // here reads a clock; the width comes from the startup geometry the runtime measured.
            return Update.next(new Model(
                    AnimatedProgress.Model.at(0.0),
                    Spinner.Model.starting(),
                    0,
                    Model.barWidth(context.viewport().cols()),
                    AnimatedProgress.Motion.SMOOTH,
                    Fill.PLAIN,
                    "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted components reduce their own messages into the scene's slots.
                case Msg.Bar bar -> mountBar(model).update(model, bar.value());
                case Msg.Spin spin -> mountSpinner(model).update(model, spin.value());
                case Msg.Install install -> install(model);
                case Msg.Uninstall uninstall -> uninstall(model);
                case Msg.Reset reset -> reset(model);
                case Msg.CycleMotion motion -> cycleMotion(model);
                case Msg.CycleFill fill -> {
                    Fill next = model.fill().shift(1);
                    yield Update.next(model.withFill(next).withLast("fill-" + next.label()));
                }
                case Msg.Resized resized -> {
                    int width = Model.barWidth(resized.size().cols());
                    yield Update.next(width == model.width() ? model : model.withWidth(width));
                }
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /** The bar and the spinner declare their own intervals; the scene adds the resize source. */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.batch(
                    mountBar(model).subscriptions(model),
                    mountSpinner(model).subscriptions(model),
                    Subscription.resize(RESIZE, Msg.Resized::new));
        }

        @Override
        public ComponentTree children(Model model) {
            List<ComponentTree.Node> nodes = new ArrayList<>(2);
            nodes.add(mountBar(model).tree(model));
            nodes.add(mountSpinner(model).tree(model));
            return new ComponentTree(nodes);
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            List<View<Msg>> rows = new ArrayList<>(9);
            // Status rows are one row each however narrow the terminal is: a wrapped diagnostic
            // would push the painted bars and the help down and move what a verifier can assert.
            rows.add(Views.fixed(1, Views.text(barStatus(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(policy(model), STATUS)));
            rows.add(Views.gap(1));
            // The animated row is the mounted component's own view; the static row is the pure
            // renderer, so the two regimes are painted from the same bar definition.
            rows.add(Views.fixed(1, mountBar(model).view(model, context)));
            rows.add(Views.fixed(1, Views.text(model.staticBar().render(model.target(), 1.0))));
            rows.add(Views.gap(1));
            rows.add(Views.row(mountSpinner(model).view(model, context), Views.text(" " + installLine(model))));
            rows.add(Views.gap(1));
            rows.add(Help.view(keys, false, cols));
            return Views.onKeys(keys.bindings(), Views.column(rows));
        }

        /**
         * One animated bar, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's definition on every use; its identity, lens
         * and route are the same values every time, so the mount - and the interval it owns - is
         * stable across a resize that changes nothing else.</p>
         */
        private Mount<Model, Msg, Never, AnimatedProgress.Model, AnimatedProgress.Msg, Never> mountBar(Model model) {
            return Mount.of(
                    ComponentId.of("bar"),
                    new AnimatedProgress(model.config()),
                    ModelLens.of(Model::bar, Model::withBar),
                    Msg.Bar::new,
                    OutputRoute.toOutput(value -> value));
        }

        /** The install spinner, mounted beside the bar so both run on their own intervals. */
        private Mount<Model, Msg, Never, Spinner.Model, Spinner.Msg, Never> mountSpinner(Model model) {
            return Mount.of(
                    ComponentId.of("spinner"),
                    new Spinner(Spinner.Config.preset(Spinner.Preset.DOT)),
                    ModelLens.of(Model::spinner, Model::withSpinner),
                    Msg.Spin::new,
                    OutputRoute.toOutput(value -> value));
        }

        /** Completes the next package: the bar eases towards the new target from wherever it is. */
        private Update<Model, Msg, Never> install(Model model) {
            if (model.complete()) {
                return Update.next(model.withLast("complete"));
            }
            String done = PACKAGES.get(model.installed());
            Model advanced = model.withInstalled(model.installed() + 1);
            Model reduced = mountBar(advanced)
                    .update(advanced, new AnimatedProgress.Msg.Set(advanced.target()))
                    .model();
            if (!reduced.complete()) {
                return Update.next(reduced.withLast("install-" + done));
            }
            // The last package stops the spinner: work is done, so the screen is quiet apart from
            // the bar finishing its own ease, which releases its interval when it arrives.
            return Update.next(mountSpinner(reduced)
                    .update(reduced, new Spinner.Msg.Stop())
                    .model()
                    .withLast("done"));
        }

        /** Gives the last completed package back, which retargets the bar in the other direction. */
        private Update<Model, Msg, Never> uninstall(Model model) {
            if (model.installed() == 0) {
                return Update.next(model.withLast("empty"));
            }
            String gone = PACKAGES.get(model.installed() - 1);
            Model reduced = model.withInstalled(model.installed() - 1);
            Model animated = mountBar(reduced)
                    .update(reduced, new AnimatedProgress.Msg.Set(reduced.target()))
                    .model();
            return Update.next(mountSpinner(animated)
                    .update(animated, new Spinner.Msg.Start())
                    .model()
                    .withLast("back-" + gone));
        }

        /** Starts the run over: zero targets, and a spinner turning again. */
        private Update<Model, Msg, Never> reset(Model model) {
            Model cleared = model.withInstalled(0);
            Model animated = mountBar(cleared)
                    .update(cleared, new AnimatedProgress.Msg.Set(cleared.target()))
                    .model();
            return Update.next(mountSpinner(animated)
                    .update(animated, new Spinner.Msg.Start())
                    .model()
                    .withLast("reset"));
        }

        /**
         * Cycles the bar between smooth and instant motion.
         *
         * <p>Turning animation off lands the bar on the target it was already easing towards, which
         * is what a user who asked for reduced motion should see rather than a bar that finishes
         * moving because it started moving before the preference changed.</p>
         */
        private Update<Model, Msg, Never> cycleMotion(Model model) {
            AnimatedProgress.Motion next = model.motion().shift(1);
            Model changed =
                    model.withMotion(next).withLast("motion-" + next.name().toLowerCase(java.util.Locale.ROOT));
            if (next == AnimatedProgress.Motion.INSTANT) {
                return mountBar(changed).update(changed, new AnimatedProgress.Msg.Settle());
            }
            return Update.next(changed);
        }

        /** The animated bar's row: what it was asked for, where it has reached, and whether it moves. */
        private static String barStatus(Model model) {
            AnimatedProgress.Config config = model.config();
            return "animated target=" + fraction(model.target())
                    + " displayed=" + fraction(model.bar().displayed(config))
                    + " run=" + (model.bar().running() ? "on" : "off");
        }

        /** The policy row: the scene's own choices and what it last did. */
        private static String policy(Model model) {
            // The bar width leads the row: it is what a resize changes, and at a narrow terminal
            // the fields behind it are the ones that may be clipped.
            return "width=" + model.width()
                    + " pkgs=" + model.installed() + "/" + PACKAGES.size()
                    + " motion=" + model.motion().name().toLowerCase(java.util.Locale.ROOT)
                    + " fill=" + model.fill().label()
                    + " last=" + model.last();
        }

        /** What the mock installer is doing right now. */
        private static String installLine(Model model) {
            return model.complete()
                    ? "complete " + PACKAGES.size() + "/" + PACKAGES.size()
                    : "installing " + model.currentPackage() + " " + model.installed() + "/" + PACKAGES.size();
        }

        /**
         * A normalized value with three decimals.
         *
         * <p>The status row reports the scale, not the bar's percentage: a verifier comparing a
         * value that is still moving needs finer steps than a whole percent of a sixth.</p>
         */
        private static String fraction(double value) {
            return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, Math.min(1, value)));
        }
    }
}
