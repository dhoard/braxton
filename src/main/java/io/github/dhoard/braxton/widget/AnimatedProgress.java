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

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import java.time.Duration;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A {@link ProgressBar} whose fill eases toward a target instead of jumping to it.
 *
 * <p>The widget from the last phase that is closest to this one is {@link Spinner}: both derive
 * what they paint from a runtime-supplied time instead of storing it, both declare exactly one
 * scoped interval while they are moving, and neither reads a clock of its own. The difference is
 * that a spinner's frame is a function of elapsed time alone, while a progress bar has a position
 * to ease, so this widget also carries the position and velocity its current segment started from.</p>
 *
 * <h2>The scale and what it rejects</h2>
 *
 * <p>Progress is normalized: a target, a displayed position and a velocity are all measured
 * against the whole bar, so {@code 0.25} is a quarter of it however wide it is. A target is
 * always inside {@code [0,1]}: a finite value outside it clamps to an end of the scale, which is
 * what makes {@link Msg.Adjust} safe to use as a nudge. A value that is not a number is
 * rejected where it is offered - {@link Msg.Set}, {@link Msg.Adjust} and the
 * {@link Model} constructor throw an {@link IllegalArgumentException} for a NaN or an infinity -
 * because a bar that is a number the scale cannot hold has no honest rendering, and silently
 * showing nothing would hide a caller's bug.</p>
 *
 * <p>What the bar paints is a fraction of its own cells, so a resize does not change it: the same
 * model at a wider width is the same fraction of more cells. {@link ProgressBar} keeps its own
 * contract for the static case, where an unusable width or a non-positive maximum paints
 * nothing.</p>
 *
 * <h2>Easing</h2>
 *
 * <p>A target is approached with a critically damped response, evaluated in closed form from the
 * segment's origin, its start position and its start velocity. Because the response is analytic,
 * the painted position depends only on the model and the current time - a stalled runtime that
 * delivers one late tick lands where the animation really is, instead of replaying the steps it
 * missed - and it cannot overshoot the way a solver stepped at an irregular cadence can. Position
 * stays inside the scale; an overshoot after a retarget against the direction of travel may carry
 * the underlying response past the target, but never past an end of the scale once painted.</p>
 *
 * <p>{@link Config#responseTime()} is how long a full-scale change takes to come to rest: the
 * response constant scales the segment's frequency so that a full step from rest satisfies both
 * settle conditions below after exactly one response time. The default is 300 ms, which reads as
 * a smooth bar rather than a slow one.</p>
 *
 * <p>Retargeting never jumps: the position and the velocity the bar had reached become the new
 * segment's starting values, so the bar continues from where it is. A retarget toward the target
 * it already has changes nothing at all, and neither does a target it is already settled on.</p>
 *
 * <h2>Rest</h2>
 *
 * <p>An animation is at rest when both its remaining distance and its normalized velocity - a
 * fraction of the bar per second - are below {@link #SETTLE_EPSILON}. The tick that first sees
 * that lands on the target exactly and stops: {@link Model#running()} turns false, the interval
 * is no longer declared, and the bar paints the target rather than a value a hair away from it.
 * {@link Msg.Set} on a bar that is already at its target declares nothing, so a settled
 * bar never keeps a timer alive - which is what makes an idle screen idle.</p>
 *
 * <p>{@link Config#instant(ProgressBar)} is the reduced-motion definition: every target is reached
 * at once and the component never subscribes to runtime time at all. {@link Msg.Settle} is the
 * same gesture for one bar, for an application that has to honor a preference mid-animation.</p>
 *
 * <h2>Time and state</h2>
 *
 * <p>A {@link Model} is a value: whether the bar is moving, the epoch of the current segment, the
 * target, the position and velocity that segment started from, its origin and the last monotonic
 * time the runtime reported. Like a spinner's, every reduction that changes the segment increments
 * {@link Model#epoch()}, and a tick carries the epoch it was mapped with, so a tick that arrives
 * after a retarget can never move a newer segment. Elapsed arithmetic saturates instead of
 * overflowing, and a runtime time before the origin clamps to zero instead of reversing the
 * animation.</p>
 *
 * <p>{@link Msg.Set} does not carry a time, so a retarget arms the new segment and the next
 * tick names its origin. Until then the bar paints the position it had reached, which is why a
 * retarget cannot make the painted value jump.</p>
 *
 * <h2>Mounting</h2>
 *
 * <p>As a component, a moving bar declares exactly one scoped {@link Subscription.Interval} in
 * whatever {@code SubscriptionId} namespace its mount scopes it to, and none while it is at rest.
 * Unmounting closes the source with the component's scope, so a removed bar cannot keep ticking.
 * The widget owns no keys and no focus: what a bar shows, and every terminal gesture that changes
 * it, belong to the owning application, which is what lets one screen run an animated bar next to
 * a static one. {@link #init} mounts a bar settled at zero, because there is nothing to animate
 * until the application names a target.</p>
 */
public final class AnimatedProgress implements Component<AnimatedProgress.Model, AnimatedProgress.Msg, Never> {

    /**
     * How long a full-scale change takes to come to rest unless a definition says otherwise.
     */
    public static final Duration DEFAULT_RESPONSE_TIME = Duration.ofMillis(300);

    /**
     * How often a moving bar repaints unless a definition says otherwise: about sixty times a second.
     */
    public static final Duration DEFAULT_TICK_PERIOD = Duration.ofMillis(16);

    /**
     * The distance and normalized velocity below which a segment is at rest.
     *
     * <p>Both are measured on the normalized scale, so the threshold means the same thing however
     * wide the bar is and however far a step had to travel.</p>
     */
    public static final double SETTLE_EPSILON = 1e-4;

    /**
     * The origin of a segment whose first tick has not arrived yet, so no elapsed time is known.
     *
     * <p>A monotonic reading of exactly this value is not a usable origin; every real origin is
     * greater, because the runtime's clock counts upwards from zero.</p>
     */
    public static final long UNSET_ORIGIN = Long.MIN_VALUE;

    /**
     * The scoped identity of a moving bar's tick source.
     */
    private static final SubscriptionId TICK = SubscriptionId.of("tick");

    /**
     * How far a segment's response has to run, in units of {@code 1 / responseTime}, before a
     * full-scale step is both close enough to the target and slow enough to call it at rest.
     *
     * <p>A critically damped response from rest is {@code (1 + x) e^-x} of its distance and
     * {@code x e^-x} of its frequency, where {@code x = omega * t}; both are below
     * {@link #SETTLE_EPSILON} at {@code x = 16.5}. Scaling the frequency by that constant is what
     * gives the response time its meaning: one response time is one full-scale settle.</p>
     */
    private static final double SETTLE_FACTOR = 16.5;

    /**
     * The response argument past which a segment is treated as arrived.
     *
     * <p>{@code e^-100} is about {@code 4e-44}, so nothing a segment still holds can be seen or
     * measured past this point; short-circuiting also keeps an absurd gap between two clock
     * readings from multiplying a large elapsed time by a vanished exponential.</p>
     */
    private static final double DECAY_LIMIT = 100.0;

    /**
     * How a bar reaches its target.
     */
    public enum Motion {
        /**
         * Ease toward the target and hold it until the application names another one.
         */
        SMOOTH,

        /**
         * Reach every target at once and never subscribe to runtime time.
         *
         * <p>This is the definition an application builds when the user asked for reduced motion:
         * the bar still reports the same targets and settles at once, it simply never animates.</p>
         */
        INSTANT;

        /**
         * Another motion, {@code steps} away and wrapped into the set of motions.
         *
         * @param steps how far to move; negative moves toward the first motion
         * @return the selected motion
         */
        public Motion shift(int steps) {
            Motion[] motions = values();
            return motions[Math.floorMod(ordinal() + steps, motions.length)];
        }
    }

    /**
     * An immutable bar definition.
     *
     * <p>The bar itself - its width, icons, colors, labels and its own opt-in refinements - plus
     * the three facts this component adds: how the bar moves, how long a change takes to come to
     * rest, and how often a moving bar repaints. The bar is required; a missing motion, response
     * time or tick period is the default one, and a response time or tick period that is zero,
     * negative or too large to express in nanoseconds is rejected here rather than at paint time.</p>
     *
     * @param bar the bar this component paints; required
     * @param motion how the bar reaches a target; null is {@link Motion#SMOOTH}
     * @param responseTime how long a full-scale change takes to come to rest; null is
     *     {@link #DEFAULT_RESPONSE_TIME}
     * @param tickPeriod how often a moving bar repaints; null is {@link #DEFAULT_TICK_PERIOD}
     */
    public record Config(ProgressBar bar, Motion motion, Duration responseTime, Duration tickPeriod) {

        /**
         * Rejects a missing bar and an unusable response time or tick period.
         */
        public Config {
            Objects.requireNonNull(bar, "bar");
            motion = motion == null ? Motion.SMOOTH : motion;
            responseTime = checkedDuration(responseTime, DEFAULT_RESPONSE_TIME, "response time");
            tickPeriod = checkedDuration(tickPeriod, DEFAULT_TICK_PERIOD, "tick period");
        }

        /**
         * The default definition for a bar: smooth, 300 ms, about sixty repaints a second.
         *
         * @param bar the bar this component paints; required
         * @return the configuration
         */
        public static Config of(ProgressBar bar) {
            return new Config(bar, Motion.SMOOTH, DEFAULT_RESPONSE_TIME, DEFAULT_TICK_PERIOD);
        }

        /**
         * The reduced-motion definition for a bar: every target is reached at once.
         *
         * @param bar the bar this component paints; required
         * @return the configuration
         */
        public static Config instant(ProgressBar bar) {
            return new Config(bar, Motion.INSTANT, DEFAULT_RESPONSE_TIME, DEFAULT_TICK_PERIOD);
        }

        /**
         * The same definition with a different bar.
         *
         * @param value the bar this component paints; required
         * @return the new definition
         */
        public Config withBar(ProgressBar value) {
            return new Config(value, motion, responseTime, tickPeriod);
        }

        /**
         * The same definition with a different motion.
         *
         * @param value how the bar reaches a target; null is {@link Motion#SMOOTH}
         * @return the new definition
         */
        public Config withMotion(Motion value) {
            return new Config(bar, value, responseTime, tickPeriod);
        }

        /**
         * The same definition with a different response time.
         *
         * @param value how long a full-scale change takes to come to rest; null is the default
         * @return the new definition
         */
        public Config withResponseTime(Duration value) {
            return new Config(bar, motion, value, tickPeriod);
        }

        /**
         * The same definition with a different repaint cadence.
         *
         * @param value how often a moving bar repaints; null is the default
         * @return the new definition
         */
        public Config withTickPeriod(Duration value) {
            return new Config(bar, motion, responseTime, value);
        }

        /**
         * The segment frequency this definition's response time implies, in radians per second.
         *
         * @return the critically damped frequency of one segment
         */
        double frequency() {
            return SETTLE_FACTOR / (responseTime.toNanos() / 1e9);
        }
    }

    /**
     * Immutable bar state.
     *
     * <p>The position is not stored: a segment is the closed-form response of
     * {@code (from, fromVelocity)} toward {@code target} since {@code originNanos}, so equal model
     * and time always paint equal positions, and the state carries no history of the ticks that
     * got it there. {@code originNanos} is {@link #UNSET_ORIGIN} between a retarget and the tick
     * that names the new segment's origin, and for a bar that is at rest.</p>
     *
     * @param running whether the bar is moving and therefore subscribed to runtime time
     * @param epoch the identity of the current segment; a tick carrying another one is stale
     * @param target the normalized target, always in {@code [0,1]}
     * @param from the normalized position the segment started from; it may sit outside
     *     {@code [0,1]} while an overshoot decays
     * @param fromVelocity the normalized velocity, in bar fractions per second, the segment started with
     * @param originNanos the time the current segment is measured from, or {@link #UNSET_ORIGIN}
     * @param nowNanos the last monotonic time the runtime reported for this bar
     */
    public record Model(
            boolean running,
            long epoch,
            double target,
            double from,
            double fromVelocity,
            long originNanos,
            long nowNanos) {

        /**
         * Rejects a non-finite value and keeps the target on the scale.
         */
        public Model {
            target = clampScale(requiredFinite(target, "target"));
            from = requiredFinite(from, "from");
            fromVelocity = requiredFinite(fromVelocity, "from velocity");
        }

        /**
         * A bar at rest on a target.
         *
         * <p>This is the state a bar the application has not moved yet is in, and the state an
         * instant change lands in: nothing is subscribed and the painted value is the target.</p>
         *
         * @param target the normalized target; a finite value outside {@code [0,1]} clamps
         * @return the resting state
         */
        public static Model at(double target) {
            double clamped = clampScale(requiredFinite(target, "target"));
            return new Model(false, 0L, clamped, clamped, 0.0, UNSET_ORIGIN, 0L);
        }

        /**
         * Whether the bar is waiting for the first tick of its current segment to name an origin.
         *
         * <p>A bar at rest has no segment to arm, however it came to rest.</p>
         *
         * @return whether the bar is moving with no origin recorded yet
         */
        public boolean armed() {
            return running && originNanos == UNSET_ORIGIN;
        }

        /**
         * Whether the bar is at rest, and therefore holds no subscription.
         *
         * @return whether the bar is not moving
         */
        public boolean settled() {
            return !running;
        }

        /**
         * The time the current segment has run for, saturated and never negative.
         *
         * @return the elapsed nanoseconds, zero before the first tick of a segment
         */
        public long elapsedNanos() {
            return originNanos == UNSET_ORIGIN ? 0L : Spinner.elapsed(originNanos, nowNanos);
        }

        /**
         * The normalized position the bar paints under a definition, always inside {@code [0,1]}.
         *
         * @param config the definition; required
         * @return the painted fraction of the bar
         */
        public double displayed(Config config) {
            Objects.requireNonNull(config, "config");
            return clampScale(position(config));
        }

        /**
         * The normalized velocity the bar is moving at, in bar fractions per second.
         *
         * <p>This is the value a settle test compares with {@link #SETTLE_EPSILON}: it is zero at
         * rest, starts at whatever the retarget that created the segment carried over, and decays
         * as the segment comes in.</p>
         *
         * @param config the definition; required
         * @return the velocity, in fractions of the bar per second
         */
        public double velocity(Config config) {
            Objects.requireNonNull(config, "config");
            return moving() ? segmentVelocity(config) : 0.0;
        }

        /**
         * Whether the closed form still describes anything, as opposed to a resting target.
         */
        private boolean moving() {
            return running && originNanos != UNSET_ORIGIN;
        }

        /**
         * The segment's position at the last reported time, before clamping to the scale.
         */
        private double position(Config config) {
            if (!running) {
                return target;
            }
            if (originNanos == UNSET_ORIGIN) {
                return from;
            }
            double decay = decay(config);
            if (decay > DECAY_LIMIT) {
                return target;
            }
            double omega = config.frequency();
            double offset = from - target;
            double slope = fromVelocity + omega * offset;
            return target + (offset + slope * elapsedSeconds()) * Math.exp(-decay);
        }

        /**
         * The segment's velocity at the last reported time, in fractions of the bar per second.
         */
        private double segmentVelocity(Config config) {
            double decay = decay(config);
            if (decay > DECAY_LIMIT) {
                return 0.0;
            }
            double omega = config.frequency();
            double slope = fromVelocity + omega * (from - target);
            return (fromVelocity - omega * slope * elapsedSeconds()) * Math.exp(-decay);
        }

        /**
         * How far the segment's response has decayed: its frequency times its elapsed time.
         */
        private double decay(Config config) {
            return config.frequency() * elapsedSeconds();
        }

        /**
         * The elapsed time of the segment, in seconds.
         */
        private double elapsedSeconds() {
            return elapsedNanos() / 1e9;
        }
    }

    /**
     * One gesture or one runtime time reading.
     */
    public sealed interface Msg {

        /**
         * Animates toward a new target.
         *
         * <p>A target outside the scale clamps to an end of it, a target the bar is already
         * heading for changes nothing, and a target it is already settled on changes nothing
         * either. A bar already at rest on exactly this target declares no interval at all.</p>
         *
         * @param target the normalized target; must be finite
         */
        record Set(double target) implements Msg {

            /**
             * Rejects a target that is not a number and clamps one outside the scale.
             */
            public Set {
                target = clampScale(requiredFinite(target, "target"));
            }
        }

        /**
         * Moves the target by a normalized amount, clamped to the scale.
         *
         * @param delta the change to the target; must be finite
         */
        record Adjust(double delta) implements Msg {

            /**
             * Rejects a change that is not a number.
             */
            public Adjust {
                requiredFinite(delta, "delta");
            }
        }

        /**
         * Lands on the current target at once and drops the subscription.
         */
        record Settle() implements Msg {}

        /**
         * One runtime time reading.
         *
         * @param epoch the segment the reading belongs to; a tick from another segment is ignored
         * @param nowNanos the runtime's monotonic time, in nanoseconds
         */
        record Tick(long epoch, long nowNanos) implements Msg {}
    }

    /**
     * A reduction result. The bar has no semantic outputs, so the intent is always absent.
     */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    private final Config config;

    /**
     * An animated bar with a definition.
     *
     * @param config the immutable definition; required
     */
    public AnimatedProgress(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * This bar's definition.
     *
     * @return the configuration
     */
    public Config config() {
        return config;
    }

    /**
     * Paints the bar at the position this state has eased to.
     *
     * @param model the bar state; required
     * @param config the definition; required
     * @return the painted bar
     */
    public static StyledText render(Model model, Config config) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(config, "config");
        return config.bar().renderFraction(model.displayed(config));
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        // Nothing is moving yet, so a mounted bar holds no subscription and paints an empty bar;
        // the first Set is what names a target and begins the first segment.
        return Update.next(Model.at(0.0));
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        // The bar owns no focus of its own, so the reduced state is the whole answer.
        return WidgetUpdate.settled(
                model, message, action -> new Result(reduce(model, action), null), UnaryOperator.identity());
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        if (!model.running()) {
            return Subscription.none();
        }
        // The interval is the only timer: one tick per repaint, scoped to this mount, and closed
        // when the bar comes to rest or is unmounted. The epoch is captured so a tick mapped before
        // a retarget is recognizable afterward.
        long epoch = model.epoch();
        return Subscription.interval(TICK, config.tickPeriod(), time -> new Msg.Tick(epoch, time));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        // The bar is one row of its own width; a narrower viewport clips it like any other row.
        return RenderedText.<Msg>of(cols -> render(model, config));
    }

    /**
     * Applies one gesture or one time reading. Never performs I/O.
     */
    private Model reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Set set -> retarget(state, set.target());
            case Msg.Adjust adjust -> retarget(state, clampScale(state.target() + adjust.delta()));
            case Msg.Settle settle ->
                state.running() ? resting(state.epoch() + 1, state.target(), state.nowNanos()) : state;
            case Msg.Tick tick -> ticked(state, tick);
        };
    }

    /**
     * Starts a segment toward {@code target} from the position and velocity the bar has reached.
     *
     * <p>The new segment is armed rather than started, because the message carries no time: the
     * next tick names its origin. Until then the bar paints the position it already had, which is
     * what keeps a retarget from jumping.</p>
     */
    private Model retarget(Model state, double target) {
        if (config.motion() == Motion.INSTANT) {
            return state.settled() && state.target() == target
                    ? state
                    : resting(state.epoch() + 1, target, state.nowNanos());
        }
        if (state.target() == target) {
            // The segment already heads there, and the response through a point determines the
            // rest of the response, so an identical request cannot change what is painted.
            return state;
        }
        double position = state.position(config);
        double velocity = state.segmentVelocity(config);
        if (atRest(position, velocity, target)) {
            return resting(state.epoch() + 1, target, state.nowNanos());
        }
        return new Model(true, state.epoch() + 1, target, position, velocity, UNSET_ORIGIN, state.nowNanos());
    }

    /**
     * One time reading: stale segments are ignored, and the first reading of a segment is its origin.
     */
    private Model ticked(Model state, Msg.Tick tick) {
        if (!state.running() || tick.epoch() != state.epoch()) {
            return state;
        }
        if (state.originNanos() == UNSET_ORIGIN) {
            return new Model(
                    true,
                    state.epoch(),
                    state.target(),
                    state.from(),
                    state.fromVelocity(),
                    tick.nowNanos(),
                    tick.nowNanos());
        }
        if (tick.nowNanos() == state.nowNanos()) {
            return state;
        }
        Model moved = new Model(
                true,
                state.epoch(),
                state.target(),
                state.from(),
                state.fromVelocity(),
                state.originNanos(),
                tick.nowNanos());
        // Coming to rest lands on the target exactly and releases the interval; a segment that keeps
        // a hair of error alive would repaint for ever.
        return atRest(moved.position(config), moved.segmentVelocity(config), moved.target())
                ? resting(state.epoch(), moved.target(), tick.nowNanos())
                : moved;
    }

    /**
     * The state of a bar at rest on a target.
     */
    private static Model resting(long epoch, double target, long nowNanos) {
        return new Model(false, epoch, target, target, 0.0, UNSET_ORIGIN, nowNanos);
    }

    /**
     * Whether a position and its velocity are both within the settle threshold of a target.
     */
    private static boolean atRest(double position, double velocity, double target) {
        return Math.abs(target - position) < SETTLE_EPSILON && Math.abs(velocity) < SETTLE_EPSILON;
    }

    /**
     * A value on the normalized scale: a finite value outside it clamps to an end.
     */
    private static double clampScale(double value) {
        return Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * A value that has to be a number.
     */
    private static double requiredFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        return value;
    }

    /**
     * A duration a segment can be measured or repainted with.
     */
    private static Duration checkedDuration(Duration duration, Duration fallback, String name) {
        if (duration == null) {
            return fallback;
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds: " + duration, overflow);
        }
        return duration;
    }
}
