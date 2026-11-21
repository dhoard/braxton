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
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Element;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A stopwatch: one elapsed duration that runs, stops, resets and runs on again.
 *
 * <p>A {@link Config} is the whole definition - how often a running stopwatch repaints, how the
 * elapsed time is written and how it is styled - so two watches on one screen can repaint at
 * different cadences without shared state.</p>
 *
 * <h2>Elapsed time is derived, never accumulated per tick</h2>
 *
 * <p>A running stopwatch stores the instant its current run began, not a total it adds a tick's
 * worth to. Every runtime reading then produces the elapsed time as the sum of what earlier runs
 * accumulated and the distance from the current run's origin, so a stalled runtime that delivers
 * one late reading lands on the time that has really elapsed instead of replaying the ticks it
 * missed, and an irregular cadence cannot make the watch drift.</p>
 *
 * <p>The component never reads a clock: {@link Msg.Start} and a reset of a running watch carry no
 * time, so they arm the run and return {@link Command#monotonicTime}, and the {@link Msg.Reading}
 * that command produces names the origin. That is why a run begins at the instant the application
 * started it rather than at the next tick. Until that reading arrives the model reports the time
 * the run has accumulated so far, which is what a caller that started a watch sees for one
 * owner-loop turn.</p>
 *
 * <p>A reading can arrive late, and arithmetic can overflow. A reading is clamped to the last one
 * the model saw, so a late or reordered reading can never make the painted time run backwards; the
 * accumulated total and the distance to the origin saturate instead of wrapping; and elapsed time
 * is never negative.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Every control message is idempotent as a statement about the state it produces, and a message
 * that changes nothing returns the incoming model instance:</p>
 *
 * <ul>
 *   <li>{@link Msg.Start} begins a run, keeping the time already accumulated. Starting a running
 *       stopwatch changes nothing; there is no separate resume, because for a stopwatch resuming is
 *       starting.</li>
 *   <li>{@link Msg.Stop} folds the current run into the accumulated total and drops the
 *       subscription, so a stopped stopwatch holds no timer and no time passes.</li>
 *   <li>{@link Msg.Toggle} is the one gesture a single key carries: it stops a running stopwatch
 *       and starts a stopped one.</li>
 *   <li>{@link Msg.Reset} zeroes the elapsed time and keeps the running state: a stopped watch
 *       stays stopped at zero, and a running one carries on from zero.</li>
 * </ul>
 *
 * <p>Every gesture the application sends increments {@link Model#epoch()}, which is what lets a
 * reading that belongs to an older run be recognized and dropped. A reading of a run that was
 * stopped, reset or replaced therefore cannot move the displayed time.</p>
 *
 * <h2>Formatting</h2>
 *
 * <p>{@link Model#elapsed()} is the exact elapsed {@link Duration}, which is what a caller should
 * read when it needs the value rather than the picture of it. The default {@link #CLOCK} formatter
 * writes {@code hh:mm:ss.mmm}: the hours field is at least two digits wide and widens for a longer
 * run, and the milliseconds field is truncated rather than rounded, so the painted time never
 * claims a millisecond that has not elapsed. The largest time a {@code long} of nanoseconds can
 * express - about 2562047 hours - still renders, so the display has no artificial ceiling. A
 * {@link Config#formatter()} replaces that policy for the whole widget - hundredths instead of
 * milliseconds, a word instead of digits - and is applied by the same pure renderer.</p>
 *
 * <h2>Mounting</h2>
 *
 * <p>As a component, a running stopwatch declares exactly one scoped {@link Subscription.Interval}
 * in whatever {@code SubscriptionId} namespace its mount scopes it to, and none while it is
 * stopped. The interval's period is the definition's tick period: it is how often the display is
 * refreshed, never how the elapsed time is computed. Unmounting closes the source with the
 * component's scope, so a removed stopwatch cannot keep ticking.</p>
 *
 * <p>The widget owns no keys and no focus: a stopwatch is measurement and display. Which gesture
 * starts, stops or resets it belongs to the owning application, which is what lets one screen drive
 * two watches independently. {@link #init} mounts a stopped watch at zero, because a watch the
 * application did not start must not be counting.</p>
 *
 * @see Countdown
 * @see Spinner
 */
public final class Stopwatch implements Component<Stopwatch.Model, Stopwatch.Msg, Never> {

    /**
     * How often a running stopwatch is asked to repaint unless a definition says otherwise.
     */
    public static final Duration DEFAULT_TICK_PERIOD = Duration.ofMillis(50);

    /**
     * The origin of a run that has not been told what time it is, so no elapsed time is known.
     *
     * <p>A monotonic reading of exactly this value is not a usable origin; every real reading is
     * greater, because the runtime's clock counts upwards from zero.</p>
     */
    public static final long UNSET_ORIGIN = Long.MIN_VALUE;

    /**
     * The default formatter: {@code hh:mm:ss.mmm}, with hours widening for a longer run.
     */
    public static final Formatter CLOCK = Stopwatch::clock;

    /**
     * The scoped identity of a running stopwatch's tick source.
     */
    private static final SubscriptionId TICK = SubscriptionId.of("tick");

    private static final long MILLIS_PER_SECOND = 1_000L;

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /**
     * How the elapsed time of a stopwatch is written.
     */
    @FunctionalInterface
    public interface Formatter {

        /**
         * Renders the time a stopwatch has measured.
         *
         * <p>The value is never negative: a watch that has not run reports {@link Duration#ZERO}.
         * A formatter must be pure - the same duration always renders the same text - because
         * rendering is part of the view.</p>
         *
         * @param elapsed the time measured; never negative
         * @return the text to paint; null paints nothing
         */
        String format(Duration elapsed);
    }

    /**
     * An immutable stopwatch definition.
     *
     * <p>A missing tick period, formatter or style is the default one. A tick period that is zero,
     * negative or too large to express in nanoseconds is rejected here rather than at mount time,
     * because it would be a schedule the runtime cannot honor.</p>
     *
     * @param tickPeriod how often a running stopwatch repaints; null is
     *     {@link #DEFAULT_TICK_PERIOD}
     * @param formatter how the elapsed time is written; null is {@link #CLOCK}
     * @param style the style the painted text carries; null is {@link Style#NONE}
     */
    public record Config(Duration tickPeriod, Formatter formatter, Style style) {

        /**
         * Rejects an unusable tick period and substitutes the defaults.
         */
        public Config {
            tickPeriod = checkedTickPeriod(tickPeriod);
            formatter = formatter == null ? CLOCK : formatter;
            style = style == null ? Style.NONE : style;
        }

        /**
         * The default definition: {@link #DEFAULT_TICK_PERIOD}, {@link #CLOCK} and no styling.
         *
         * @return the configuration
         */
        public static Config defaults() {
            return new Config(DEFAULT_TICK_PERIOD, CLOCK, Style.NONE);
        }

        /**
         * The default definition at a different repaint cadence.
         *
         * @param tickPeriod how often a running stopwatch repaints; strictly positive
         * @return the configuration
         */
        public static Config of(Duration tickPeriod) {
            return new Config(tickPeriod, CLOCK, Style.NONE);
        }

        /**
         * The same definition with a different repaint cadence.
         *
         * @param value how often a running stopwatch repaints; strictly positive
         * @return the new definition
         */
        public Config withTickPeriod(Duration value) {
            return new Config(value, formatter, style);
        }

        /**
         * The same definition written by a different formatter.
         *
         * @param value how the elapsed time is written; null is {@link #CLOCK}
         * @return the new definition
         */
        public Config withFormatter(Formatter value) {
            return new Config(tickPeriod, value, style);
        }

        /**
         * The same definition painted in a different style.
         *
         * @param value the style of the painted text; null is {@link Style#NONE}
         * @return the new definition
         */
        public Config withStyle(Style value) {
            return new Config(tickPeriod, formatter, value);
        }

        /**
         * How often a running stopwatch repaints, in nanoseconds.
         *
         * @return the configured period in nanoseconds
         */
        public long tickPeriodNanos() {
            return tickPeriod.toNanos();
        }
    }

    /**
     * Immutable stopwatch state.
     *
     * <p>{@code accumulatedNanos} is the time earlier runs measured, and {@code originNanos} is the
     * instant the current run began. The elapsed time is their sum while a run is live, so nothing
     * is advanced in step with a timer; {@code originNanos} is {@link #UNSET_ORIGIN} while the watch
     * is stopped and until the reading that names the current run's origin arrives, which is what
     * {@link #armed()} reports.</p>
     *
     * @param running whether a run is live and subscribed to runtime time
     * @param epoch the identity of the current run; a reading carrying another one is stale
     * @param accumulatedNanos the time earlier runs measured; not negative
     * @param originNanos the instant the current run began, or {@link #UNSET_ORIGIN}
     * @param nowNanos the last monotonic reading this stopwatch was reduced with
     */
    public record Model(boolean running, long epoch, long accumulatedNanos, long originNanos, long nowNanos) {

        /**
         * Clamps a negative accumulated total to zero, so a watch can never run backwards.
         */
        public Model {
            accumulatedNanos = Math.max(0L, accumulatedNanos);
        }

        /**
         * A stopped stopwatch that has measured nothing: the state a fresh mount is in.
         *
         * @return the starting state of a watch the application starts itself
         */
        public static Model stopped() {
            return stopped(Duration.ZERO);
        }

        /**
         * A stopped stopwatch holding an elapsed time.
         *
         * @param elapsed the time measured so far; not negative
         * @return the starting state of a watch with a total already on it
         */
        public static Model stopped(Duration elapsed) {
            Objects.requireNonNull(elapsed, "elapsed");
            if (elapsed.isNegative()) {
                throw new IllegalArgumentException("elapsed must not be negative");
            }
            return new Model(false, 0L, nanos(elapsed), UNSET_ORIGIN, 0L);
        }

        /**
         * Whether a running stopwatch is still waiting for the reading that names its origin.
         *
         * <p>A stopped stopwatch has no run to arm, however it was stopped.</p>
         *
         * @return whether the run is live with no origin recorded yet
         */
        public boolean armed() {
            return running && originNanos == UNSET_ORIGIN;
        }

        /**
         * The time the watch has measured, exactly and never negative.
         *
         * <p>This is the sum of what the earlier runs accumulated and, while a run has an origin,
         * the distance from it to the last reading. An armed run reports only what it accumulated,
         * because its origin is not known yet.</p>
         *
         * @return the elapsed nanoseconds, saturated
         */
        public long elapsedNanos() {
            long current = originNanos == UNSET_ORIGIN ? 0L : distance(originNanos, nowNanos);
            try {
                return Math.addExact(accumulatedNanos, current);
            } catch (ArithmeticException overflow) {
                // Two valid readings can be further apart than a long holds. Saturation keeps the
                // watch meaningful - it has measured a great deal - instead of wrapping into zero.
                return Long.MAX_VALUE;
            }
        }

        /**
         * The time the watch has measured.
         *
         * @return the elapsed duration, never negative
         */
        public Duration elapsed() {
            return Duration.ofNanos(elapsedNanos());
        }
    }

    /**
     * One lifecycle gesture or one runtime time reading.
     */
    public sealed interface Msg {

        /**
         * Begins a run, subscribing to runtime time and asking for the reading that names its origin.
         *
         * <p>Starting a running stopwatch changes nothing. The time already accumulated is kept, so
         * this is also how a stopped watch continues.</p>
         */
        record Start() implements Msg {}

        /**
         * Folds the current run into the accumulated total and drops the subscription.
         *
         * <p>Stopping a stopwatch that is not running changes nothing.</p>
         */
        record Stop() implements Msg {}

        /**
         * Stops a running stopwatch and starts a stopped one: the whole gesture one key carries.
         */
        record Toggle() implements Msg {}

        /**
         * Zeroes the elapsed time, keeping whether the watch is running.
         *
         * <p>A stopped watch stays stopped at zero. A running one carries on from zero under a new
         * epoch, measured from the instant the reset was applied.</p>
         */
        record Reset() implements Msg {}

        /**
         * One runtime monotonic time reading.
         *
         * <p>The same shape serves both roles: a reading that arrives while a run is armed names its
         * origin, and every later reading of that run measures the distance from it. Both come from
         * the runtime's clock, and a reading carrying the epoch of an older run is ignored.</p>
         *
         * @param epoch the run the reading belongs to
         * @param nowNanos the runtime's monotonic time, in nanoseconds
         */
        record Reading(long epoch, long nowNanos) implements Msg {}
    }

    /**
     * A reduction result. A stopwatch has no semantic outputs, so the intent is always absent.
     */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    private final Config config;

    /**
     * A stopwatch with a definition.
     *
     * @param config the immutable definition; required
     */
    public Stopwatch(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * This stopwatch's definition.
     *
     * @return the configuration
     */
    public Config config() {
        return config;
    }

    /**
     * The time between two monotonic readings, saturated and never negative.
     *
     * @param originNanos the time the run began, in nanoseconds
     * @param nowNanos the current monotonic reading, in nanoseconds
     * @return the elapsed nanoseconds; zero when the clock has not advanced
     */
    public static long distance(long originNanos, long nowNanos) {
        try {
            return Math.max(0L, Math.subtractExact(nowNanos, originNanos));
        } catch (ArithmeticException overflow) {
            return nowNanos > originNanos ? Long.MAX_VALUE : 0L;
        }
    }

    /**
     * The default rendering: {@code hh:mm:ss.mmm}, with the hours field widening.
     *
     * <p>The milliseconds field is truncated, not rounded, so the painted time never claims a
     * millisecond that has not passed, and only a watch that has measured nothing paints zero. A
     * negative or null duration paints zero, so the formatter is total.</p>
     *
     * @param elapsed the time measured; null and negative values paint {@code 00:00:00.000}
     * @return the painted text
     */
    public static String clock(Duration elapsed) {
        long millis = 0L;
        if (elapsed != null && !elapsed.isNegative()) {
            try {
                millis = elapsed.toMillis();
            } catch (ArithmeticException overflow) {
                // A duration far longer than a monotonic reading can carry still paints, at the
                // largest time the display can express.
                millis = Long.MAX_VALUE;
            }
        }
        long hours = millis / MILLIS_PER_HOUR;
        long minutes = (millis % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE;
        long seconds = (millis % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND;
        long fraction = millis % MILLIS_PER_SECOND;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, fraction);
    }

    /**
     * Paints the elapsed time at the definition's full length.
     *
     * @param model the stopwatch state; required
     * @param config the definition; required
     * @return the styled text
     */
    public static StyledText render(Model model, Config config) {
        return render(model, config, Integer.MAX_VALUE);
    }

    /**
     * Paints the elapsed time, cut to an available width.
     *
     * <p>The text is cut by display columns and never through a grapheme cluster, so a wide glyph a
     * custom formatter produced is dropped whole rather than half painted. A width of zero or less
     * paints nothing.</p>
     *
     * @param model the stopwatch state; required
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
        String text = config.formatter().format(model.elapsed());
        if (text == null || text.isEmpty()) {
            return StyledText.empty();
        }
        String painted = TextMeasure.columns(text) <= width ? text : TextMeasure.truncate(text, width);
        return StyledText.of(painted, config.style());
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        // A mounted stopwatch is stopped at zero: a watch the application did not start must not be
        // counting, and starting needs no clock reading here.
        return Update.next(Model.stopped());
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        // The stopwatch owns no focus of its own, so the reduced state is the whole answer apart from
        // the timestamp an armed run has to ask for.
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), UnaryOperator.identity(), Stopwatch::request);
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        if (!model.running()) {
            return Subscription.none();
        }
        // The interval is only a repaint cadence: the elapsed time is derived from the origin, so one
        // late tick lands on the right value instead of replaying the ticks it missed. The epoch is
        // captured so a tick mapped before a stop or reset is recognizable afterward.
        long epoch = model.epoch();
        return Subscription.interval(TICK, config.tickPeriod(), time -> new Msg.Reading(epoch, time));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return RenderedText.<Msg>of(cols -> render(model, config, cols));
    }

    /**
     * The timestamp an armed run waits for: a run with no origin asks for exactly one.
     */
    private static Command<Msg> request(Model state) {
        if (!state.armed()) {
            return Command.none();
        }
        long epoch = state.epoch();
        return Command.monotonicTime(time -> new Msg.Reading(epoch, time));
    }

    /**
     * Applies one lifecycle gesture or one time reading. Never performs I/O.
     */
    private Result reduce(Model state, Msg message) {
        return switch (message) {
            case Msg.Start start -> start(state);
            case Msg.Stop stop -> stop(state);
            case Msg.Toggle toggle -> toggle(state);
            case Msg.Reset reset -> reset(state);
            case Msg.Reading reading -> read(state, reading);
        };
    }

    /**
     * Begins a run, keeping the time already accumulated, or does nothing when one is live.
     */
    private static Result start(Model state) {
        if (state.running()) {
            return new Result(state, null);
        }
        return new Result(
                new Model(true, state.epoch() + 1L, state.accumulatedNanos(), UNSET_ORIGIN, state.nowNanos()), null);
    }

    /**
     * Folds the current run into the accumulated total and drops the origin.
     */
    private static Result stop(Model state) {
        if (!state.running()) {
            return new Result(state, null);
        }
        // The fold is what freezes the display: the stopped model has no origin, so no later reading
        // can add to the total it now holds.
        return new Result(
                new Model(false, state.epoch() + 1L, state.elapsedNanos(), UNSET_ORIGIN, state.nowNanos()), null);
    }

    /**
     * The one-key gesture: stop while running, start while stopped.
     */
    private Result toggle(Model state) {
        return state.running() ? stop(state) : start(state);
    }

    /**
     * Zeroes the elapsed time under a new epoch, keeping whether the watch is running.
     */
    private static Result reset(Model state) {
        return new Result(new Model(state.running(), state.epoch() + 1L, 0L, UNSET_ORIGIN, state.nowNanos()), null);
    }

    /**
     * One time reading: names the origin, measures the distance from it, or is ignored as stale.
     */
    private static Result read(Model state, Msg.Reading reading) {
        if (!state.running() || reading.epoch() != state.epoch()) {
            // A stopped run has nothing to measure, and a reading from another run can neither name
            // this run's origin nor move the time it shows.
            return new Result(state, null);
        }
        // A monotonic clock does not go backwards, but a reading can arrive late. Clamping to the last
        // reading keeps a reordered one from making the watch run backwards.
        long now = Math.max(state.nowNanos(), reading.nowNanos());
        if (state.armed()) {
            // The first reading of a run is a timestamp fact rather than a measurement: it names the
            // origin, so the run is measured from the instant it began.
            return new Result(new Model(true, state.epoch(), state.accumulatedNanos(), now, now), null);
        }
        if (now == state.nowNanos()) {
            return new Result(state, null);
        }
        return new Result(new Model(true, state.epoch(), state.accumulatedNanos(), state.originNanos(), now), null);
    }

    /**
     * An elapsed duration or total, saturated to what a monotonic reading can carry.
     */
    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * A repaint cadence the runtime can schedule.
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
