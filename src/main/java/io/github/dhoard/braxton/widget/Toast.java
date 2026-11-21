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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A transient notification: one message with a severity that goes away on its own.
 *
 * <p>A toast is the answer to a keystroke that needs an acknowledgment and nothing else - "copied",
 * "saved", "that filter matched nothing". It paints one row, it disappears after a configured
 * duration, and it never takes the keyboard: an application shows it beside what the user is doing
 * rather than in front of it. That is the difference between a toast and a {@link Dialog}.</p>
 *
 * <h2>Remaining time is model data</h2>
 *
 * <p>The model holds the instant the toast expires and the time that is left at the last reading it
 * saw, never an amount it ticks down. A reading that arrives late therefore lands on the time that
 * is really left instead of replaying the ticks it missed, and an irregular cadence cannot make the
 * toast linger. The arithmetic is {@link Countdown}'s, for the same reason: one derived-deadline
 * rule serves both, and this widget does not restate it.</p>
 *
 * <p>{@link Msg.Show} arms a run: it stores the message, the severity and a new run epoch, and it
 * asks the runtime for one monotonic reading, which is the reading that names the deadline. The
 * first reading of a run is thus a timestamp fact rather than a tick. A zero-length duration has
 * nothing to wait for, so {@link Msg.Show} expires it at once and delivers its one {@link
 * Output.Expired} without arming anything.</p>
 *
 * <p>Every reading carries the epoch of the run it belongs to, so a reading mapped before a dismiss
 * or a replacement can neither end the new run nor lengthen it. An expiry is reported as exactly one
 * {@link Output.Expired} per run that reached zero; later readings of that run find a toast that is
 * no longer showing and are ignored.</p>
 *
 * <h2>One scoped interval</h2>
 *
 * <p>While a toast shows, it declares exactly one scoped {@link Subscription.Interval} in whatever
 * {@link SubscriptionId} namespace its mount scopes it to, and none while it is hidden. The
 * interval's period is only how often expiry is checked; it is never how the remaining time is
 * computed. The subscription therefore stops by itself when the toast expires or is dismissed,
 * because the model that declares it is no longer showing.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>A toast owns no keys and no focus: it is a notification, not a control, and a widget that
 * silently took the keyboard would break whatever the user was typing. A left press on it dismisses
 * it, which is the one gesture a reader tries on a thing that is in the way.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>The severity's {@link Severity#marker() marker} is painted in the role its {@link
 * Severity#token()} names and the message in the theme's {@code TEXT}, through the shared {@link
 * SeverityRow}. A message wider than the row is clipped rather than wrapped, because a toast is one
 * line, and a toast given no cells paints nothing.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A toast is one object per placed toast, held across frames the way a component is, because the
 * router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see NotificationCenter
 * @see Elements#toast(Toast, Toast.Model)
 */
public final class Toast
        implements Component<Toast.Model, Toast.Msg, Toast.Output>, ThemedWidget<Toast.Model, Toast.Msg> {

    /**
     * How long a toast shows unless its configuration says otherwise.
     */
    public static final Duration DEFAULT_DURATION = Duration.ofSeconds(3);

    /**
     * How often a showing toast checks whether it has expired unless a definition says otherwise.
     */
    public static final Duration DEFAULT_TICK_PERIOD = Duration.ofMillis(100);

    /**
     * The scoped identity of a showing toast's tick source.
     */
    private static final SubscriptionId TICK = SubscriptionId.of("tick");

    private final Config config;

    /**
     * The row painter: a severity marker and the message in the theme's styles.
     */
    private final SeverityRow<Msg> row = new SeverityRow<>();

    /**
     * A toast with the default duration and cadence.
     */
    public Toast() {
        this(Config.defaults());
    }

    /**
     * A toast over its configuration.
     *
     * @param config how long it shows and how often it checks; required
     */
    public Toast(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * How a toast is configured.
     *
     * <p>A duration of zero is legal: such a toast has nothing to wait for, so showing it expires it
     * at once. A tick period that is zero, negative or too large to express in nanoseconds is
     * rejected, because it would be a schedule the runtime cannot honor.</p>
     *
     * @param duration how long a shown toast lasts; not negative
     * @param tickPeriod how often a showing toast checks for expiry; null is {@link
     *     #DEFAULT_TICK_PERIOD}
     */
    public record Config(Duration duration, Duration tickPeriod) {

        /**
         * Rejects an unusable duration or tick period and substitutes the default cadence.
         */
        public Config {
            duration = checkedDuration(duration);
            tickPeriod = checkedTickPeriod(tickPeriod);
        }

        /**
         * The default configuration.
         *
         * @return a toast lasting {@link #DEFAULT_DURATION} at {@link #DEFAULT_TICK_PERIOD}
         */
        public static Config defaults() {
            return new Config(DEFAULT_DURATION, DEFAULT_TICK_PERIOD);
        }

        /**
         * A configuration for a duration, with the default cadence.
         *
         * @param duration how long a shown toast lasts; not negative
         * @return the configuration
         */
        public static Config of(Duration duration) {
            return new Config(duration, DEFAULT_TICK_PERIOD);
        }

        /**
         * This configuration with another duration.
         *
         * @param value how long a shown toast lasts; not negative
         * @return the new configuration
         */
        public Config withDuration(Duration value) {
            return new Config(value, tickPeriod);
        }

        /**
         * This configuration with another cadence.
         *
         * @param value how often a showing toast checks for expiry; strictly positive
         * @return the new configuration
         */
        public Config withTickPeriod(Duration value) {
            return new Config(duration, value);
        }

        /**
         * How long a toast lasts, in nanoseconds.
         *
         * @return the configured duration in nanoseconds
         */
        public long durationNanos() {
            return duration.toNanos();
        }
    }

    /**
     * Immutable toast state.
     *
     * <p>{@code remainingNanos} is the time left at {@code nowNanos}: while the toast is showing it
     * is the distance to {@code deadlineNanos}, and before the first reading of a run it is the
     * configured duration. {@code deadlineNanos} is {@link Countdown#UNSET_DEADLINE} until the
     * reading that names it arrives.</p>
     *
     * @param severity how urgent the message is
     * @param message the message painted after the severity's marker
     * @param showing whether the toast is on screen
     * @param epoch the identity of the current run; a reading carrying another one is stale
     * @param remainingNanos the time left at {@code nowNanos}; a negative value is clamped to zero
     * @param deadlineNanos the instant the current run expires, or {@link Countdown#UNSET_DEADLINE}
     * @param nowNanos the last monotonic reading this toast was reduced with
     */
    public record Model(
            Severity severity,
            StyledText message,
            boolean showing,
            long epoch,
            long remainingNanos,
            long deadlineNanos,
            long nowNanos) {

        /**
         * Substitutes the defaults and clamps a negative remaining time to zero.
         */
        public Model {
            severity = severity == null ? Severity.INFO : severity;
            message = message == null ? StyledText.empty() : message;
            remainingNanos = Math.max(0L, remainingNanos);
        }

        /**
         * A toast that is not showing anything.
         *
         * @return the hidden state, which is what a fresh toast starts in
         */
        public static Model hidden() {
            return new Model(Severity.INFO, StyledText.empty(), false, 0L, 0L, Countdown.UNSET_DEADLINE, 0L);
        }

        /**
         * The time left, exactly and never negative.
         *
         * @return the remaining duration
         */
        public Duration remaining() {
            return Duration.ofNanos(remainingNanos);
        }

        /**
         * Whether a showing toast is still waiting for the reading that names its deadline.
         *
         * @return whether the toast shows with no deadline recorded yet
         */
        public boolean armed() {
            return showing && deadlineNanos == Countdown.UNSET_DEADLINE;
        }
    }

    /**
     * One gesture or one runtime time reading.
     */
    public sealed interface Msg {

        /**
         * Shows a message with a severity for the configured duration.
         *
         * <p>Showing a toast that is already up replaces what it shows and starts its time again,
         * which is what the newest message arriving means.</p>
         *
         * @param severity how urgent the message is; null is {@link Severity#INFO}
         * @param message the message; null is empty
         */
        record Show(Severity severity, StyledText message) implements Msg {

            /**
             * Substitutes the defaults for a missing severity or message.
             */
            public Show {
                severity = severity == null ? Severity.INFO : severity;
                message = message == null ? StyledText.empty() : message;
            }

            /**
             * A plain informational message.
             *
             * @param message the message; null is empty
             * @return the message
             */
            public static Show of(String message) {
                return new Show(Severity.INFO, StyledText.plain(message));
            }

            /**
             * A message with a severity.
             *
             * @param severity how urgent the message is; null is {@link Severity#INFO}
             * @param message the message; null is empty
             * @return the message
             */
            public static Show of(Severity severity, String message) {
                return new Show(severity, StyledText.plain(message));
            }
        }

        /**
         * Hides the toast now, which is what a press on it does.
         */
        record Dismiss() implements Msg {}

        /**
         * One runtime monotonic time reading.
         *
         * <p>The reading that arrives while the run is armed names its deadline, and every later
         * reading of that run measures the distance to it. A reading carrying the epoch of an older
         * run is ignored.</p>
         *
         * @param epoch the run the reading belongs to
         * @param nowNanos the runtime's monotonic time, in nanoseconds
         */
        record Reading(long epoch, long nowNanos) implements Msg {}
    }

    /**
     * What a toast reports to its owner.
     */
    public sealed interface Output {

        /**
         * The toast reached the end of its time and hid itself.
         *
         * <p>Exactly one is reported per run that expires. A toast dismissed by the user never
         * reports one, and showing another toast arms a new run.</p>
         */
        record Expired() implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How this toast is configured.
     *
     * @return how this toast is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The toast's element: one row while it shows, nothing while it does not.
     *
     * @param model the state to paint; required
     * @return the element, emitting this toast's messages
     */
    public Element<Msg> element(Model model) {
        return new Element.Leaf<>(this, Objects.requireNonNull(model, "model"), Sizing.measured());
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(Model.hidden());
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        // A toast owns no focus of its own, so the reduced state is the whole answer apart from the
        // timestamp a newly armed run has to ask for.
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), UnaryOperator.identity(), Toast::request);
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        if (!model.showing()) {
            return Subscription.none();
        }
        // The interval is only how often expiry is checked: the remaining time is derived from the
        // deadline, so one late tick lands on the right value instead of replaying the ticks it
        // missed. The epoch is captured so a tick mapped before a dismiss is recognizable after it.
        long epoch = model.epoch();
        return Subscription.interval(TICK, config.tickPeriod(), time -> new Msg.Reading(epoch, time));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return element(model);
    }

    /**
     * A hidden toast reserves no cells, so it never grows the row it is placed in.
     */
    @Override
    public Size measure(Size max, Model model) {
        SeverityRow.Row painted = row(max, model);
        return painted == null ? new Size(0, 0) : row.measure(max, painted);
    }

    /**
     * One row while the toast shows, no cells while it does not.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Model model) {
        SeverityRow.Row painted = row(new Size(available.rows(), available.cols()), model);
        return painted == null
                ? new Rect(available.row(), available.col(), 0, 0)
                : row.arrange(available, measured, painted);
    }

    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        SeverityRow.Row painted = row(new Size(bounds.rows(), bounds.cols()), model);
        if (painted != null) {
            row.paint(surface, bounds, painted, theme);
        }
    }

    /**
     * A left press on the toast dismisses it; any other report is not a gesture on it.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (pointer == null || !pointer.isLeftPress() || bounds.isEmpty() || !model.showing()) {
            return;
        }
        emit.accept(new Msg.Dismiss());
    }

    /**
     * The timestamp a newly armed run waits for: a showing toast with no deadline asks for one.
     */
    private static Command<Msg> request(Model state) {
        if (!state.armed()) {
            return Command.none();
        }
        long epoch = state.epoch();
        return Command.monotonicTime(time -> new Msg.Reading(epoch, time));
    }

    /**
     * Applies one gesture or one time reading. Never performs I/O.
     */
    private Result reduce(Model state, Msg message) {
        return switch (message) {
            case Msg.Show show -> show(state, show);
            case Msg.Dismiss dismiss -> dismiss(state);
            case Msg.Reading reading -> read(state, reading);
        };
    }

    /**
     * Shows a message for the configured duration, replacing what is up and starting time again.
     */
    private Result show(Model state, Msg.Show show) {
        long epoch = state.epoch() + 1L;
        long duration = config.durationNanos();
        if (duration == 0L) {
            // Nothing to wait for, so the run expires where it starts and reports its one expiry
            // without arming a deadline or a subscription.
            return new Result(
                    new Model(
                            show.severity(),
                            show.message(),
                            false,
                            epoch,
                            0L,
                            Countdown.UNSET_DEADLINE,
                            state.nowNanos()),
                    new Output.Expired());
        }
        return new Result(
                new Model(
                        show.severity(),
                        show.message(),
                        true,
                        epoch,
                        duration,
                        Countdown.UNSET_DEADLINE,
                        state.nowNanos()),
                null);
    }

    /**
     * Hides the toast under a new epoch, so a reading of the old run cannot revive it.
     */
    private static Result dismiss(Model state) {
        if (!state.showing()) {
            return new Result(state, null);
        }
        return new Result(
                new Model(
                        state.severity(),
                        state.message(),
                        false,
                        state.epoch() + 1L,
                        0L,
                        Countdown.UNSET_DEADLINE,
                        state.nowNanos()),
                null);
    }

    /**
     * One time reading: names the deadline, measures the distance to it, or reports the expiry.
     */
    private static Result read(Model state, Msg.Reading reading) {
        if (!state.showing() || reading.epoch() != state.epoch()) {
            // A hidden toast has nothing to measure, and a reading from another run can neither name
            // a deadline nor end a run it does not belong to.
            return new Result(state, null);
        }
        // A monotonic clock does not go backwards, but a reading can arrive late. Clamping to the
        // last reading keeps a reordered one from lengthening the time that is left.
        long now = Math.max(state.nowNanos(), reading.nowNanos());
        long deadline = state.armed() ? Countdown.deadlineFrom(now, state.remainingNanos()) : state.deadlineNanos();
        long remaining = Countdown.remainingFrom(deadline, now);
        if (remaining > 0L) {
            return new Result(
                    new Model(state.severity(), state.message(), true, state.epoch(), remaining, deadline, now), null);
        }
        // The deadline has passed: the toast is over, and this reading is its one expiry. The epoch
        // is kept, so a duplicate of this reading still finds a toast that is no longer showing.
        return new Result(
                new Model(state.severity(), state.message(), false, state.epoch(), 0L, Countdown.UNSET_DEADLINE, now),
                new Output.Expired());
    }

    /**
     * The row this model paints, or null when there is nothing to paint.
     */
    private SeverityRow.Row row(Size max, Model model) {
        if (model == null || !model.showing() || max.rows() <= 0 || max.cols() <= 0) {
            return null;
        }
        return new SeverityRow.Row(model.severity(), model.message(), false, false, false);
    }

    /**
     * A duration a toast can be configured with.
     */
    private static Duration checkedDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("toast duration must not be negative");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("toast duration must fit in nanoseconds: " + duration, overflow);
        }
        return duration;
    }

    /**
     * A cadence the runtime can schedule.
     */
    private static Duration checkedTickPeriod(Duration tickPeriod) {
        if (tickPeriod == null) {
            return DEFAULT_TICK_PERIOD;
        }
        if (tickPeriod.isZero() || tickPeriod.isNegative()) {
            throw new IllegalArgumentException("tick period must be positive");
        }
        try {
            tickPeriod.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("tick period must fit in nanoseconds: " + tickPeriod, overflow);
        }
        return tickPeriod;
    }
}
