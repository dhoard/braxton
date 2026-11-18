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
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A countdown: a configured duration that runs, pauses, resumes and expires exactly once.
 *
 * <p>A {@link Config} is the whole definition - how long the countdown lasts, how often a running
 * countdown repaints, how the remaining time is written and how it is styled - so two countdowns on
 * one screen can run different durations at different cadences without shared state.</p>
 *
 * <h2>Remaining time is derived, never accumulated</h2>
 *
 * <p>A running countdown stores the instant it expires, not a remaining amount it subtracts a tick
 * from. Every runtime reading then produces the remaining time as the distance to that deadline, so
 * a stalled runtime that delivers one late reading lands on the time that is really left instead of
 * replaying the ticks it missed, and an irregular cadence cannot make the countdown drift. The
 * component never reads a clock: a running deadline and the frozen remainder of a paused countdown
 * both come from runtime-supplied readings.</p>
 *
 * <p>{@link Msg.Start} and {@link Msg.Resume} carry no time, so they arm the run and return {@link
 * Command#monotonicTime}, and the {@link Msg.Reading} that command produces names the deadline. The
 * first reading of an armed run is therefore a timestamp fact rather than a tick: the countdown
 * begins at the instant the application started it, not at the next tick. Until that reading
 * arrives the model reports the duration it was armed with, which is what a caller that mounted an
 * armed countdown sees for one owner-loop turn.</p>
 *
 * <p>A reading can arrive late, and arithmetic can overflow. A reading is clamped to the last one
 * the model saw, so a late or reordered reading can only ever shorten the remaining time and never
 * lengthen it; the deadline and the remaining time saturate instead of wrapping; and remaining time
 * is never negative, so a countdown that passed its deadline reports zero rather than going
 * backwards.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Every control message is idempotent and returns the incoming model instance when it changes
 * nothing:</p>
 *
 * <ul>
 *   <li>{@link Msg.Start} begins a run of the configured duration. Starting a running countdown
 *       changes nothing, and starting a paused one discards the time it had paused with - resuming
 *       is what continues.</li>
 *   <li>{@link Msg.Stop} freezes the remaining time at the last reading the run had and drops the
 *       subscription, so no time passes while a countdown is paused.</li>
 *   <li>{@link Msg.Resume} continues a paused countdown from the remainder it is holding. Resuming
 *       a running countdown, or one that has nothing left, changes nothing; {@link Msg.Start} is
 *       how a finished countdown begins again.</li>
 *   <li>{@link Msg.Toggle} is the one gesture a single key can carry: it stops a running countdown,
 *       resumes a paused one, and starts a finished or idle one from the configured duration.</li>
 *   <li>{@link Msg.Reset} stops the countdown and puts the configured duration back.</li>
 * </ul>
 *
 * <p>A countdown of zero length is legal: it has nothing to wait for, so {@link Msg.Start} expires
 * it at once and delivers its one {@link Output.Timeout} without arming anything.</p>
 *
 * <p>An expiry is reported as exactly one {@link Output.Timeout} per run that reached zero. The
 * reading that sees the deadline pass emits it and stops the run; later readings of that run match
 * no running countdown and are ignored, so a duplicate tick, a queued emission or a paused
 * countdown's leftover reading can never report a second timeout. Every gesture the application
 * sends - and so every transition between runs - increments {@link Model#epoch()}, which is what
 * lets a reading that belongs to an older run be recognised and dropped; the expiry itself keeps
 * the epoch of the run it ended, because that run is over rather than replaced.</p>
 *
 * <h2>Formatting</h2>
 *
 * <p>{@link Model#remaining()} is the exact remaining {@link Duration}, which is what a caller
 * should read when it needs the value rather than the picture of it. The default {@link
 * #CLOCK} formatter writes whole seconds as {@code mm:ss}, and {@code h:mm:ss} once an hour or
 * more is left; a positive fraction of a second rounds <em>up</em>, so a countdown that still has
 * time left never paints the zero it has not reached. A {@link Config#formatter()} replaces that
 * policy for the whole widget - hundredths for a race timer, a word instead of digits - and is
 * applied by the same pure renderer.</p>
 *
 * <h2>Mounting</h2>
 *
 * <p>As a component, a running countdown declares exactly one scoped {@link Subscription.Interval}
 * in whatever {@code SubscriptionId} namespace its mount scopes it to, and none while it is
 * stopped. The interval's period is the definition's tick period: it is how often the display is
 * refreshed, never how the remaining time is computed. Unmounting closes the source with the
 * component's scope, so a removed countdown cannot keep ticking.</p>
 *
 * <p>The widget owns no keys and no focus: a countdown is schedule and display. Which gesture
 * starts, pauses or resets it belongs to the owning application, which is what lets one screen
 * drive two countdowns independently. {@link #init} mounts an idle countdown at the configured
 * duration, because a timer the application did not start must not be counting.</p>
 *
 * @see Spinner
 * @see AnimatedProgress
 */
public final class Countdown implements Component<Countdown.Model, Countdown.Msg, Countdown.Output> {

    /** How often a running countdown is asked to repaint unless a definition says otherwise. */
    public static final Duration DEFAULT_TICK_PERIOD = Duration.ofMillis(100);

    /**
     * The deadline of a countdown that is not waiting for one, so no instant expires it yet.
     *
     * <p>A monotonic reading of exactly this value is not a usable deadline; every real reading is
     * greater, because the runtime's clock counts upwards from zero.</p>
     */
    public static final long UNSET_DEADLINE = Long.MIN_VALUE;

    /** The default formatter: whole seconds as {@code mm:ss}, or {@code h:mm:ss} past an hour. */
    public static final Formatter CLOCK = Countdown::clock;

    /** The scoped identity of a running countdown's tick source. */
    private static final SubscriptionId TICK = SubscriptionId.of("tick");

    private static final long SECONDS_PER_HOUR = 3_600L;

    private static final long SECONDS_PER_MINUTE = 60L;

    /** How the remaining time of a countdown is written. */
    @FunctionalInterface
    public interface Formatter {

        /**
         * Renders the time a countdown has left.
         *
         * <p>The value is never negative: a countdown that reached its deadline reports {@link
         * Duration#ZERO}. A formatter must be pure - the same duration always renders the same
         * text - because rendering is part of the view.</p>
         *
         * @param remaining the time left; never negative
         * @return the text to paint; null paints nothing
         */
        String format(Duration remaining);
    }

    /**
     * An immutable countdown definition.
     *
     * <p>The duration is required and is the one thing a countdown cannot default: a negative or
     * unrepresentably large duration is rejected here rather than at paint time, and zero is
     * allowed because a countdown of no length expires once as soon as it is started. A missing
     * tick period, formatter or style is the default one; a tick period that is zero, negative or
     * too large to express in nanoseconds is rejected, because it would be a schedule the runtime
     * cannot honour.</p>
     *
     * @param duration how long the countdown lasts; not negative
     * @param tickPeriod how often a running countdown repaints; null is
     *     {@link #DEFAULT_TICK_PERIOD}
     * @param formatter how the remaining time is written; null is {@link #CLOCK}
     * @param style the style the painted text carries; null is {@link Style#NONE}
     */
    public record Config(Duration duration, Duration tickPeriod, Formatter formatter, Style style) {

        /** Rejects an unusable duration or tick period and substitutes the defaults. */
        public Config {
            duration = checkedDuration(duration);
            tickPeriod = checkedTickPeriod(tickPeriod);
            formatter = formatter == null ? CLOCK : formatter;
            style = style == null ? Style.NONE : style;
        }

        /**
         * The default definition for a duration: {@link #DEFAULT_TICK_PERIOD}, {@link #CLOCK} and
         * no styling.
         *
         * @param duration how long the countdown lasts; not negative
         * @return the configuration
         */
        public static Config of(Duration duration) {
            return new Config(duration, DEFAULT_TICK_PERIOD, CLOCK, Style.NONE);
        }

        /**
         * The same definition with a different duration.
         *
         * @param value how long the countdown lasts; not negative
         * @return the new definition
         */
        public Config withDuration(Duration value) {
            return new Config(value, tickPeriod, formatter, style);
        }

        /**
         * The same definition with a different repaint cadence.
         *
         * @param value how often a running countdown repaints; strictly positive
         * @return the new definition
         */
        public Config withTickPeriod(Duration value) {
            return new Config(duration, value, formatter, style);
        }

        /**
         * The same definition written by a different formatter.
         *
         * @param value how the remaining time is written; null is {@link #CLOCK}
         * @return the new definition
         */
        public Config withFormatter(Formatter value) {
            return new Config(duration, tickPeriod, value, style);
        }

        /**
         * The same definition painted in a different style.
         *
         * @param value the style of the painted text; null is {@link Style#NONE}
         * @return the new definition
         */
        public Config withStyle(Style value) {
            return new Config(duration, tickPeriod, formatter, value);
        }

        /**
         * How long the countdown lasts, in nanoseconds.
         *
         * @return the configured duration in nanoseconds, saturated
         */
        public long durationNanos() {
            return nanos(duration);
        }
    }

    /**
     * Immutable countdown state.
     *
     * <p>{@code remainingNanos} is the remaining time at {@code nowNanos}: while a run is running it
     * is the distance to {@code deadlineNanos}, and while it is stopped it is the remainder that run
     * was frozen at. {@code deadlineNanos} is {@link #UNSET_DEADLINE} outside a run and until the
     * reading that names it arrives, which is what {@link #armed()} reports.</p>
     *
     * @param running whether a run is live and subscribed to runtime time
     * @param epoch the identity of the current run; a reading carrying another one is stale
     * @param remainingNanos the time left at {@code nowNanos}; a negative value is clamped to zero
     * @param deadlineNanos the instant the current run expires, or {@link #UNSET_DEADLINE}
     * @param nowNanos the last monotonic reading this countdown was reduced with
     */
    public record Model(boolean running, long epoch, long remainingNanos, long deadlineNanos, long nowNanos) {

        /** Clamps a negative remaining time to zero, so a countdown can never run backwards. */
        public Model {
            remainingNanos = Math.max(0L, remainingNanos);
        }

        /**
         * A stopped countdown holding a remainder: the state a reset or a fresh mount is in.
         *
         * @param remaining the time left; not negative
         * @return the starting state of a countdown the application starts itself
         */
        public static Model stopped(Duration remaining) {
            Objects.requireNonNull(remaining, "remaining");
            if (remaining.isNegative()) {
                throw new IllegalArgumentException("remaining must not be negative");
            }
            return new Model(false, 0L, nanos(remaining), UNSET_DEADLINE, 0L);
        }

        /**
         * Whether a running countdown is still waiting for the reading that names its deadline.
         *
         * <p>A stopped countdown has no deadline to arm, however it was stopped.</p>
         *
         * @return whether the run is live with no deadline recorded yet
         */
        public boolean armed() {
            return running && deadlineNanos == UNSET_DEADLINE;
        }

        /**
         * Whether the countdown has nothing left.
         *
         * @return whether the remaining time is zero
         */
        public boolean expired() {
            return remainingNanos == 0L;
        }

        /**
         * The time left, exactly and never negative.
         *
         * @return the remaining duration
         */
        public Duration remaining() {
            return Duration.ofNanos(remainingNanos);
        }
    }

    /** One lifecycle gesture or one runtime time reading. */
    public sealed interface Msg {

        /**
         * Begins a run of the configured duration, subscribing to runtime time.
         *
         * <p>Starting a running countdown changes nothing; starting a paused one discards the
         * remainder it was holding.</p>
         */
        record Start() implements Msg {}

        /**
         * Freezes the remaining time and drops the subscription.
         *
         * <p>Stopping a countdown that is not running changes nothing.</p>
         */
        record Stop() implements Msg {}

        /**
         * Continues a paused countdown from the remainder it is holding.
         *
         * <p>Resuming a running countdown, or one with nothing left, changes nothing.</p>
         */
        record Resume() implements Msg {}

        /**
         * Stops a running countdown, resumes a paused one, and starts a finished one over.
         *
         * <p>An idle countdown begins from the configured duration, which is the same thing
         * resuming it from a full remainder means. This is the whole gesture one key can carry, and
         * the reason the scene behind it needs no branch of its own.</p>
         */
        record Toggle() implements Msg {}

        /** Stops the countdown and puts the configured duration back. */
        record Reset() implements Msg {}

        /**
         * One runtime monotonic time reading.
         *
         * <p>The same shape serves both roles: a reading that arrives while the run is armed names
         * its deadline, and every later reading of that run measures the distance to it. Both come
         * from the runtime's clock, and a reading carrying the epoch of an older run is ignored.</p>
         *
         * @param epoch the run the reading belongs to
         * @param nowNanos the runtime's monotonic time, in nanoseconds
         */
        record Reading(long epoch, long nowNanos) implements Msg {}
    }

    /** What a countdown reports to its owner. */
    public sealed interface Output {

        /**
         * The run reached zero.
         *
         * <p>Exactly one is reported per run that expires. A countdown that was stopped before its
         * deadline never reports one, and starting a new run arms a new one.</p>
         */
        record Timeout() implements Output {}
    }

    /** A reduction result: what the countdown asks its owner to observe, if anything. */
    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    private final Config config;

    /**
     * A countdown with a definition.
     *
     * @param config the immutable definition; required
     */
    public Countdown(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * This countdown's definition.
     *
     * @return the configuration
     */
    public Config config() {
        return config;
    }

    /**
     * The instant a remaining time ends at, saturated instead of overflowing.
     *
     * @param nowNanos the monotonic reading the run starts from, in nanoseconds
     * @param remainingNanos the time left at that reading, in nanoseconds; not negative
     * @return the deadline, in nanoseconds
     */
    public static long deadlineFrom(long nowNanos, long remainingNanos) {
        long remaining = Math.max(0L, remainingNanos);
        try {
            return Math.addExact(nowNanos, remaining);
        } catch (ArithmeticException overflow) {
            // Two valid readings can be further apart than a long holds. Saturation keeps the
            // countdown meaningful - it has time left - instead of wrapping into the past.
            return Long.MAX_VALUE;
        }
    }

    /**
     * The time left until a deadline, saturated and never negative.
     *
     * @param deadlineNanos the instant the run expires, in nanoseconds, or {@link #UNSET_DEADLINE}
     * @param nowNanos the current monotonic reading, in nanoseconds
     * @return the remaining nanoseconds; zero for an unset deadline and once one has passed
     */
    public static long remainingFrom(long deadlineNanos, long nowNanos) {
        if (deadlineNanos == UNSET_DEADLINE) {
            return 0L;
        }
        try {
            return Math.max(0L, Math.subtractExact(deadlineNanos, nowNanos));
        } catch (ArithmeticException overflow) {
            return deadlineNanos > nowNanos ? Long.MAX_VALUE : 0L;
        }
    }

    /**
     * The default rendering: whole seconds, rounded up, as {@code mm:ss} or {@code h:mm:ss}.
     *
     * <p>Rounding a positive fraction of a second up is what keeps a countdown honest at the end:
     * {@code 1 ns} left paints {@code 00:01} and only a deadline that has passed paints {@code
     * 00:00}. A negative or null duration paints zero, so the formatter is total.</p>
     *
     * @param remaining the time left; null and negative values paint {@code 00:00}
     * @return the painted text
     */
    public static String clock(Duration remaining) {
        long seconds = 0L;
        if (remaining != null && !remaining.isNegative()) {
            seconds = remaining.getSeconds();
            if (remaining.getNano() > 0 && seconds < Long.MAX_VALUE) {
                seconds++;
            }
        }
        long hours = seconds / SECONDS_PER_HOUR;
        long minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long rest = seconds % SECONDS_PER_MINUTE;
        return hours == 0L
                ? String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
    }

    /**
     * Paints the remaining time at the definition's full length.
     *
     * @param model the countdown state; required
     * @param config the definition; required
     * @return the styled text
     */
    public static StyledText render(Model model, Config config) {
        return render(model, config, Integer.MAX_VALUE);
    }

    /**
     * Paints the remaining time, cut to an available width.
     *
     * <p>The text is cut by display columns and never through a grapheme cluster, so a wide glyph
     * a custom formatter produced is dropped whole rather than half painted. A width of zero or
     * less paints nothing.</p>
     *
     * @param model the countdown state; required
     * @param config the definition; required
     * @param width the available display columns
     * @return the styled text, never wider than {@code width}
     */
    public static StyledText render(Model model, Config config, int width) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(config, "config");
        if (width <= 0) {
            return StyledText.empty();
        }
        String text = config.formatter().format(model.remaining());
        if (text == null || text.isEmpty()) {
            return StyledText.empty();
        }
        String painted = TextMeasure.columns(text) <= width ? text : TextMeasure.truncate(text, width);
        return StyledText.of(painted, config.style());
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        // A mounted countdown is idle at its configured duration: a timer the application did not
        // start must not be counting, and arming needs no clock reading here.
        return Update.next(Model.stopped(config.duration()));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        // The countdown owns no focus of its own, so the reduced state is the whole answer apart
        // from the timestamp an armed run has to ask for.
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), UnaryOperator.identity(), Countdown::request);
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        if (!model.running()) {
            return Subscription.none();
        }
        // The interval is only a repaint cadence: the remaining time is derived from the deadline, so
        // one late tick lands on the right value instead of replaying the ticks it missed. The epoch
        // is captured so a tick mapped before a stop or restart is recognisable afterwards.
        long epoch = model.epoch();
        return Subscription.interval(TICK, config.tickPeriod(), time -> new Msg.Reading(epoch, time));
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return Views.sized(size -> Views.text(render(model, config, size.cols())));
    }

    /** The timestamp an armed run waits for: a run with no deadline asks for exactly one. */
    private static Command<Msg> request(Model state) {
        if (!state.armed()) {
            return Command.none();
        }
        long epoch = state.epoch();
        return Command.monotonicTime(time -> new Msg.Reading(epoch, time));
    }

    /** Applies one lifecycle gesture or one time reading. Never performs I/O. */
    private Result reduce(Model state, Msg message) {
        return switch (message) {
            case Msg.Start start -> start(state);
            case Msg.Stop stop -> stop(state);
            case Msg.Resume resume -> resume(state);
            case Msg.Toggle toggle -> toggle(state);
            case Msg.Reset reset -> reset(state);
            case Msg.Reading reading -> read(state, reading);
        };
    }

    /** Begins a run of the configured duration, or does nothing when one is already live. */
    private Result start(Model state) {
        if (state.running()) {
            return new Result(state, null);
        }
        long epoch = state.epoch() + 1L;
        long configured = config.durationNanos();
        if (configured == 0L) {
            // Nothing to wait for, so the run expires where it starts and reports its one timeout
            // without arming a deadline or a subscription.
            return new Result(new Model(false, epoch, 0L, UNSET_DEADLINE, state.nowNanos()), new Output.Timeout());
        }
        return new Result(new Model(true, epoch, configured, UNSET_DEADLINE, state.nowNanos()), null);
    }

    /** Freezes the remaining time at the last reading the run had. */
    private static Result stop(Model state) {
        if (!state.running()) {
            return new Result(state, null);
        }
        return new Result(
                new Model(false, state.epoch() + 1L, state.remainingNanos(), UNSET_DEADLINE, state.nowNanos()), null);
    }

    /** Continues a paused countdown from its remainder, or does nothing when there is none. */
    private Result resume(Model state) {
        if (state.running() || state.expired()) {
            return new Result(state, null);
        }
        return new Result(
                new Model(true, state.epoch() + 1L, state.remainingNanos(), UNSET_DEADLINE, state.nowNanos()), null);
    }

    /** The one-key gesture: stop while running, resume while paused, start once finished or idle. */
    private Result toggle(Model state) {
        if (state.running()) {
            return stop(state);
        }
        return state.expired() ? start(state) : resume(state);
    }

    /** Puts the configured duration back, stopped, under a new epoch. */
    private Result reset(Model state) {
        return new Result(
                new Model(false, state.epoch() + 1L, config.durationNanos(), UNSET_DEADLINE, state.nowNanos()), null);
    }

    /** One time reading: names the deadline, measures the distance to it, or reports the expiry. */
    private static Result read(Model state, Msg.Reading reading) {
        if (!state.running() || reading.epoch() != state.epoch()) {
            // A stopped run has nothing to measure, and a reading from another run can neither name a
            // deadline nor end a run it does not belong to.
            return new Result(state, null);
        }
        // A monotonic clock does not go backwards, but a reading can arrive late. Clamping to the last
        // reading keeps a reordered one from lengthening the time that is left.
        long now = Math.max(state.nowNanos(), reading.nowNanos());
        long deadline = state.armed() ? deadlineFrom(now, state.remainingNanos()) : state.deadlineNanos();
        long remaining = remainingFrom(deadline, now);
        if (remaining > 0L) {
            return new Result(new Model(true, state.epoch(), remaining, deadline, now), null);
        }
        // The deadline has passed: the run is over, and this reading is its one timeout. The epoch is
        // kept, so a duplicate of this reading still finds a countdown that is no longer running.
        return new Result(new Model(false, state.epoch(), 0L, UNSET_DEADLINE, now), new Output.Timeout());
    }

    /** A configured duration or remainder, saturated to what a monotonic reading can carry. */
    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** A duration a countdown can be configured with. */
    private static Duration checkedDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("countdown duration must not be negative");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("countdown duration must fit in nanoseconds: " + duration, overflow);
        }
        return duration;
    }

    /** A repaint cadence the runtime can schedule. */
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
