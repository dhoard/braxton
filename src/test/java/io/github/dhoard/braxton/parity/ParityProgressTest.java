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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import examples.parity.ProgressDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.runtime.Mailbox;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.SubscriptionManager;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.AllocationProbe;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.AnimatedProgress;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.ProgressBar;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 08 acceptance: an eased progress bar, graded and blended fills and the package-manager demo.
 *
 * <p>The animation cases drive {@code update} with explicit monotonic readings, so the asserted
 * timing is exact and involves no sleeps; a "response time" is therefore a number of ticks, not a
 * wall clock. The lifecycle cases reconcile the demo's own subscription declarations against
 * {@link ManualTimers}, so "at rest" is proven by the interval that stops being scheduled rather
 * than by a value that happened to hold still. The terminal behaviour of the packaged demo is
 * verified separately by {@code python3 tools/terminal/verify_terminal.py --parity-phase 08}.</p>
 */
@Timeout(20)
class ParityProgressTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final long MILLIS = 1_000_000L;

    /** How far a demo bar is advanced to be sure it has come to rest: past the demo's response time. */
    private static final long SETTLED_MILLIS = 900L;

    private static final ProgressBar BAR = ProgressBar.of(20);

    private static final AnimatedProgress.Config CONFIG = AnimatedProgress.Config.of(BAR);

    private static final Color RED = new Color(255, 0, 0);

    private static final Color BLUE = new Color(0, 0, 255);

    private static final String FULL = "\u2588";

    private static final String EMPTY = "\u2591";

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AnimatedProgress progress() {
        return new AnimatedProgress(CONFIG);
    }

    private static AnimatedProgress progress(AnimatedProgress.Config config) {
        return new AnimatedProgress(config);
    }

    private static AnimatedProgress.Model apply(
            AnimatedProgress widget, AnimatedProgress.Model model, AnimatedProgress.Msg message) {
        return widget.update(model, message).model();
    }

    /** A bar that has been asked for a target: the segment is armed until its first tick. */
    private static AnimatedProgress.Model armed(AnimatedProgress widget, double target) {
        return apply(widget, AnimatedProgress.Model.at(0.0), new AnimatedProgress.Msg.Set(target));
    }

    /** One tick of the model's current segment, at an absolute monotonic time in milliseconds. */
    private static AnimatedProgress.Model at(AnimatedProgress widget, AnimatedProgress.Model model, long millis) {
        return apply(widget, model, new AnimatedProgress.Msg.Tick(model.epoch(), millis * MILLIS));
    }

    /** A segment whose origin has been named at zero, then advanced to {@code millis}. */
    private static AnimatedProgress.Model ran(AnimatedProgress widget, AnimatedProgress.Model model, long millis) {
        return model.armed() ? at(widget, at(widget, model, 0L), millis) : at(widget, model, millis);
    }

    private static double displayed(AnimatedProgress widget, AnimatedProgress.Model model) {
        return model.displayed(widget.config());
    }

    private static String painted(ProgressBar bar, double value, double max) {
        return bar.render(value, max).plainText();
    }

    private static InputEvent.KeyInput key(String spec) {
        Key gesture = Key.parse(spec);
        return InputEvent.KeyInput.of(gesture, gesture.name());
    }

    private static List<String> painted(View<?> view) {
        return new ScreenRenderer().plainLines(view, new Viewport(24, 80));
    }

    private static List<String> painted(View<?> view, int rows, int cols) {
        return new ScreenRenderer().plainLines(view, new Viewport(rows, cols));
    }

    /** The demo the packaged launch runs, driven as its own component. */
    private static ProgressDemo.Scene demo() {
        return new ProgressDemo.Scene();
    }

    private static ProgressDemo.Model start() {
        return demo().init(new InitContext(new Viewport(24, 80), null)).model();
    }

    private static ProgressDemo.Model apply(ProgressDemo.Model model, ProgressDemo.Msg message) {
        return demo().update(model, message).model();
    }

    /** What one key does to the demo's screen, as the scene's own message. */
    private static Optional<ProgressDemo.Msg> route(ProgressDemo.Model model, String keyName) {
        return InputRouter.route(demo().view(model, CONTEXT), key(keyName));
    }

    private static ProgressDemo.Model send(ProgressDemo.Model model, String keyName) {
        return apply(model, route(model, keyName).orElseThrow(() -> new AssertionError("no message for " + keyName)));
    }

    /** The demo's status and bar rows, so a test can assert the scene without decoding styles. */
    private static String row(ProgressDemo.Model model, int index) {
        return painted(demo().view(model, CONTEXT)).get(index).strip();
    }

    private static String row(ProgressDemo.Model model, int index, int cols) {
        return painted(demo().view(model, CONTEXT), 24, cols).get(index).strip();
    }

    /** Feeds one tick to the demo's bar, as the runtime's mapper would. */
    private static ProgressDemo.Model tick(ProgressDemo.Model model, long millis) {
        return apply(
                model,
                new ProgressDemo.Msg.Bar(
                        new AnimatedProgress.Msg.Tick(model.bar().epoch(), millis * MILLIS)));
    }

    /** The demo's bar, with its segment origin named at zero and then advanced to {@code millis}. */
    private static ProgressDemo.Model ease(ProgressDemo.Model model, long millis) {
        return model.bar().armed() ? tick(tick(model, 0L), millis) : tick(model, millis);
    }

    private static double displayed(ProgressDemo.Model model) {
        return model.bar().displayed(model.config());
    }

    /**
     * Feeds the demo's bar one runtime reading far enough ahead that the segment comes to rest.
     *
     * <p>An armed segment's first reading only names its origin, so coming to rest takes as many
     * readings as the segment needs - never more than two.</p>
     */
    private static ProgressDemo.Model rest(ProgressDemo.Scene scene, ProgressDemo.Model model, long millis) {
        for (int reading = 0; reading < 2 && model.bar().running(); reading++) {
            AnimatedProgress.Model segment = model.bar();
            model = scene.update(
                            model,
                            new ProgressDemo.Msg.Bar(
                                    new AnimatedProgress.Msg.Tick(segment.epoch(), segment.nowNanos() + millis)))
                    .model();
        }
        return model;
    }

    // ── R-01: the normalized scale and its error policy ──────────────────────

    @Test
    void aFiniteTargetClampsToAnEndOfTheScale() {
        assertThat(new AnimatedProgress.Msg.Set(1.5).target())
                .as("a target above the scale clamps to a full bar")
                .isEqualTo(1.0);
        assertThat(new AnimatedProgress.Msg.Set(-0.25).target()).isEqualTo(0.0);
        assertThat(new AnimatedProgress.Msg.Set(0.375).target()).isEqualTo(0.375);
        assertThat(AnimatedProgress.Model.at(3.0).target()).isEqualTo(1.0);
        assertThat(AnimatedProgress.Model.at(-3.0).target()).isEqualTo(0.0);

        // A position the response overshoots may sit outside the scale; only the target is clamped.
        AnimatedProgress.Model overshoot = new AnimatedProgress.Model(true, 1L, 0.5, 1.4, 0.0, 0L, 0L);
        assertThat(overshoot.from())
                .as("the segment's own starting position is not clamped away")
                .isEqualTo(1.4);
        assertThat(overshoot.target()).isEqualTo(0.5);
    }

    @Test
    void aValueThatIsNotANumberIsRejectedRatherThanPainted() {
        for (double unusable : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThatThrownBy(() -> new AnimatedProgress.Msg.Set(unusable))
                    .as("target %s", unusable)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be finite");
            assertThatThrownBy(() -> new AnimatedProgress.Msg.Adjust(unusable))
                    .as("delta %s", unusable)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be finite");
            assertThatThrownBy(() -> AnimatedProgress.Model.at(unusable))
                    .as("resting target %s", unusable)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be finite");
            assertThatThrownBy(() -> new AnimatedProgress.Model(false, 0L, unusable, 0.0, 0.0, 0L, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AnimatedProgress.Model(false, 0L, 0.0, unusable, 0.0, 0L, 0L))
                    .as("a non-finite starting position")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AnimatedProgress.Model(false, 0L, 0.0, 0.0, unusable, 0L, 0L))
                    .as("a non-finite starting velocity")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anUnusableBarStillPaintsNothing() {
        // The static contract this phase must preserve: a bar no cells wide, or a non-positive or
        // non-finite maximum, paints nothing at all rather than throwing.
        assertThat(ProgressBar.of(0).render(0.5, 1.0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(-4).render(0.5, 1.0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(0.5, 0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(0.5, Double.NaN)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(Double.NaN, 1.0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(0).fractional(true).blend(RED, BLUE).render(0.5, 1.0))
                .as("the opt-in refinements cannot revive an unusable bar")
                .isEqualTo(StyledText.empty());

        // The animated renderer paints a zero-width bar the same way.
        AnimatedProgress.Config none = AnimatedProgress.Config.of(ProgressBar.of(0));
        assertThat(AnimatedProgress.render(AnimatedProgress.Model.at(0.5), none))
                .isEqualTo(StyledText.empty());
    }

    @Test
    void aRestingBarPaintsExactlyTheStaticBar() {
        AnimatedProgress widget = progress();
        for (double target : new double[] {0.0, 0.1, 0.5, 1.0, 1.0 / 6.0}) {
            assertThat(AnimatedProgress.render(AnimatedProgress.Model.at(target), CONFIG))
                    .as("target %s", target)
                    .isEqualTo(BAR.render(target, 1.0));
        }
        // Graded and blended bars share the same renderer, so the two paths cannot disagree.
        AnimatedProgress.Config graded = AnimatedProgress.Config.of(
                ProgressBar.of(7).fractional(true).blend(RED, BLUE).showPercentage(true));
        for (double target : new double[] {0.0, 0.125, 0.5, 0.9375, 1.0}) {
            assertThat(AnimatedProgress.render(AnimatedProgress.Model.at(target), graded))
                    .as("graded target %s", target)
                    .isEqualTo(graded.bar().render(target, 1.0));
        }
        assertThat(displayed(widget, AnimatedProgress.Model.at(0.25))).isEqualTo(0.25);
    }

    @Test
    void aTargetTheBarAlreadyHasChangesNothing() {
        AnimatedProgress widget = progress();
        AnimatedProgress.Model resting = AnimatedProgress.Model.at(0.4);
        assertThat(apply(widget, resting, new AnimatedProgress.Msg.Set(0.4)))
                .as("a settled bar asked for its own target keeps the instance it had")
                .isSameAs(resting);
        assertThat(apply(widget, resting, new AnimatedProgress.Msg.Settle()))
                .as("settling a resting bar changes nothing")
                .isSameAs(resting);

        AnimatedProgress.Model moving = ran(widget, armed(widget, 0.9), 16L);
        assertThat(apply(widget, moving, new AnimatedProgress.Msg.Set(0.9)))
                .as("the segment already heads there, so an identical request cannot change it")
                .isSameAs(moving);
        assertThat(apply(widget, moving, new AnimatedProgress.Msg.Tick(moving.epoch() + 1, 500 * MILLIS)))
                .as("a tick from another segment is stale")
                .isSameAs(moving);
        assertThat(apply(widget, moving, new AnimatedProgress.Msg.Tick(moving.epoch(), 16 * MILLIS)))
                .as("a tick for a time already applied changes nothing")
                .isSameAs(moving);
        assertThat(apply(widget, resting, new AnimatedProgress.Msg.Tick(0L, 1_000 * MILLIS)))
                .as("a bar at rest has no segment for a tick to move")
                .isSameAs(resting);
    }

    @Test
    void aDefinitionNeedsAUsableBarAndTiming() {
        assertThatThrownBy(() -> AnimatedProgress.Config.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bar");
        for (Duration refused : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(-1))) {
            assertThatThrownBy(() -> new AnimatedProgress.Config(BAR, null, refused, null))
                    .as("response time %s", refused)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("response time must be positive");
            assertThatThrownBy(() -> new AnimatedProgress.Config(BAR, null, null, refused))
                    .as("tick period %s", refused)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tick period must be positive");
        }
        assertThatThrownBy(() -> new AnimatedProgress.Config(BAR, null, Duration.ofSeconds(Long.MAX_VALUE), null))
                .as("a response time no clock can express is not a definition")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fit in nanoseconds");
        assertThatThrownBy(() -> new AnimatedProgress.Config(BAR, null, null, Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fit in nanoseconds");

        assertThat(CONFIG.motion()).as("a missing motion is smooth").isEqualTo(AnimatedProgress.Motion.SMOOTH);
        assertThat(CONFIG.responseTime()).isEqualTo(AnimatedProgress.DEFAULT_RESPONSE_TIME);
        assertThat(CONFIG.tickPeriod()).isEqualTo(AnimatedProgress.DEFAULT_TICK_PERIOD);
        assertThat(new AnimatedProgress.Config(BAR, null, null, null).responseTime())
                .as("a missing timing is the default timing")
                .isEqualTo(Duration.ofMillis(300));
        assertThat(AnimatedProgress.Config.of(BAR)
                        .withMotion(AnimatedProgress.Motion.INSTANT)
                        .motion())
                .isEqualTo(AnimatedProgress.Motion.INSTANT);
        assertThat(AnimatedProgress.Config.instant(BAR).motion()).isEqualTo(AnimatedProgress.Motion.INSTANT);
        assertThat(AnimatedProgress.Motion.SMOOTH.shift(1)).isEqualTo(AnimatedProgress.Motion.INSTANT);
        assertThat(AnimatedProgress.Motion.INSTANT.shift(1)).isEqualTo(AnimatedProgress.Motion.SMOOTH);

        // The withers replace one fact and keep the rest, and a null timing means the default again.
        ProgressBar other = ProgressBar.of(3);
        AnimatedProgress.Config custom = CONFIG.withBar(other).withTickPeriod(Duration.ofMillis(8));
        assertThat(custom.bar()).isSameAs(other);
        assertThat(custom.motion()).isEqualTo(CONFIG.motion());
        assertThat(custom.tickPeriod()).isEqualTo(Duration.ofMillis(8));
        assertThat(custom.withTickPeriod(null).tickPeriod()).isEqualTo(AnimatedProgress.DEFAULT_TICK_PERIOD);
        assertThat(custom.withResponseTime(Duration.ofMillis(120)).responseTime())
                .isEqualTo(Duration.ofMillis(120));
    }

    // ── R-02: deterministic easing ───────────────────────────────────────────

    @Test
    void aStepFromRestEasesUpToItsTargetAndSettlesInItsResponseTime() {
        AnimatedProgress widget = progress();
        AnimatedProgress.Model model = armed(widget, 1.0);
        assertThat(model.armed()).isTrue();
        assertThat(displayed(widget, model))
                .as("a retarget paints the position it had")
                .isZero();
        assertThat(model.running()).isTrue();

        // The two readings below are the same model and time, so the painted value cannot depend on
        // how many ticks happened to arrive.
        assertThat(displayed(widget, ran(widget, model, 32L))).isEqualTo(displayed(widget, ran(widget, model, 32L)));

        List<Double> samples = new ArrayList<>();
        model = at(widget, model, 0L);
        for (long millis : new long[] {16, 32, 48, 64, 96, 128, 160, 200, 250}) {
            model = at(widget, model, millis);
            samples.add(displayed(widget, model));
            assertThat(model.running()).as("still moving at %dms", millis).isTrue();
        }
        assertThat(samples).isSorted();
        assertThat(samples).allSatisfy(value -> assertThat(value).isStrictlyBetween(0.0, 1.0));
        assertThat(samples.get(0)).as("the first reading is already moving").isGreaterThan(0.1);

        // One response time is one settle: the bar lands on the target exactly and stops.
        AnimatedProgress.Model rested = at(widget, model, 300L);
        assertThat(rested.running())
                .as("a full step comes to rest in one response time")
                .isFalse();
        assertThat(rested.settled()).isTrue();
        assertThat(rested.target()).isEqualTo(1.0);
        assertThat(displayed(widget, rested))
                .as("the settled bar paints the target, not a value a hair away from it")
                .isEqualTo(1.0);
        assertThat(rested.velocity(CONFIG)).isZero();
        assertThat(Subscription.leaves(widget.subscriptions(rested)))
                .as("a bar at rest declares no source")
                .isEmpty();
    }

    @Test
    void aRetargetContinuesFromThePositionItReachedInsteadOfResetting() {
        AnimatedProgress widget = progress();
        AnimatedProgress.Model model = ran(widget, armed(widget, 1.0), 48L);
        double reached = displayed(widget, model);
        assertThat(reached).isBetween(0.6, 0.9);

        // The new target is named without a time, so the segment is armed and the bar keeps
        // painting the position it had reached: nothing jumps.
        AnimatedProgress.Model retargeted = apply(widget, model, new AnimatedProgress.Msg.Set(0.2));
        assertThat(retargeted.armed()).isTrue();
        assertThat(displayed(widget, retargeted)).isEqualTo(reached);
        assertThat(retargeted.epoch())
                .as("the retarget starts a new segment, so older ticks are stale")
                .isEqualTo(model.epoch() + 1);
        assertThat(apply(widget, retargeted, new AnimatedProgress.Msg.Tick(model.epoch(), 64 * MILLIS)))
                .as("a tick of the replaced segment cannot move the new one")
                .isSameAs(retargeted);

        // The velocity carried over is what makes the bar bend rather than stop dead: it keeps
        // rising for a moment before the new target pulls it back.
        AnimatedProgress.Model origin = at(widget, retargeted, 48L);
        assertThat(displayed(widget, origin)).isEqualTo(reached);
        AnimatedProgress.Model later = at(widget, origin, 56L);
        assertThat(displayed(widget, later))
                .as("the fill continued in the direction it was already moving")
                .isGreaterThan(reached);
        AnimatedProgress.Model later2 = at(widget, origin, 64L);
        assertThat(displayed(widget, later2))
                .as("the carried velocity decays and the new target pulls the fill back down")
                .isBetween(0.2, displayed(widget, later));

        AnimatedProgress.Model settled = at(widget, origin, 48L + 300L);
        assertThat(settled.running()).isFalse();
        assertThat(displayed(widget, settled)).isEqualTo(0.2);
    }

    @Test
    void aDelayedOrBackwardReadingCannotBreakTheAnimation() {
        AnimatedProgress widget = progress();

        // A runtime that delivers one reading after a long gap lands where the animation really is.
        AnimatedProgress.Model late = at(widget, at(widget, armed(widget, 1.0), 0L), 10_000L);
        assertThat(late.running())
                .as("a long gap settles rather than replaying the missed ticks")
                .isFalse();
        assertThat(displayed(widget, late)).isEqualTo(1.0);

        // A reading before the origin clamps to no elapsed time instead of reversing the fill.
        AnimatedProgress.Model backward = at(widget, at(widget, armed(widget, 1.0), 1_000L), 500L);
        assertThat(backward.running())
                .as("the segment is not finished, only young")
                .isTrue();
        assertThat(displayed(widget, backward)).isZero();
        assertThat(backward.elapsedNanos()).isZero();
        assertThat(displayed(widget, at(widget, backward, 1_000L)))
                .as("the next reading in the right direction moves the fill again")
                .isZero();
        assertThat(displayed(widget, at(widget, backward, 1_048L))).isGreaterThan(0.0);

        // Saturating arithmetic: two readings at the ends of the range cannot overflow.
        AnimatedProgress.Model extreme =
                new AnimatedProgress.Model(true, 1L, 1.0, 0.0, 0.0, Long.MIN_VALUE + 1, Long.MAX_VALUE);
        assertThat(displayed(widget, extreme))
                .as("an unrepresentable gap clamps to a value on the scale")
                .isBetween(0.0, 1.0);
    }

    @Test
    void aDisplayedValueNeverLeavesTheScale() {
        AnimatedProgress widget = progress();
        // A response that overshoots its target because the segment started with built-up velocity.
        AnimatedProgress.Model overshooting = new AnimatedProgress.Model(true, 1L, 0.0, 1.0, 100.0, 0L, 0L);
        double highest = 0.0;
        AnimatedProgress.Model model = at(widget, overshooting, 0L);
        for (long millis = 0; millis <= 400; millis += 8) {
            model = at(widget, model, millis);
            double value = displayed(widget, model);
            assertThat(value).as("value at %dms", millis).isBetween(0.0, 1.0);
            highest = Math.max(highest, value);
        }
        assertThat(highest)
                .as("the response really does overshoot before it settles")
                .isEqualTo(1.0);
        assertThat(model.running()).isFalse();
        assertThat(displayed(widget, model)).isZero();
    }

    @Test
    void reducedMotionLandsAtOnceAndDeclaresNothing() {
        AnimatedProgress instant = progress(AnimatedProgress.Config.instant(BAR));
        AnimatedProgress.Model model =
                apply(instant, AnimatedProgress.Model.at(0.0), new AnimatedProgress.Msg.Set(0.6));
        assertThat(model.running()).as("an instant bar never subscribes").isFalse();
        assertThat(model.armed()).isFalse();
        assertThat(displayed(instant, model)).isEqualTo(0.6);
        assertThat(Subscription.leaves(instant.subscriptions(model))).isEmpty();
        assertThat(apply(instant, model, new AnimatedProgress.Msg.Adjust(0.2)).target())
                .isEqualTo(0.8);
        assertThat(apply(instant, model, new AnimatedProgress.Msg.Set(0.6)))
                .as("an instant bar asked for the target it holds changes nothing")
                .isSameAs(model);

        // The same gesture mid-animation: settle now, and drop the interval.
        AnimatedProgress smooth = progress();
        AnimatedProgress.Model moving = ran(smooth, armed(smooth, 1.0), 32L);
        assertThat(Subscription.leaves(smooth.subscriptions(moving))).hasSize(1);
        AnimatedProgress.Model settled = apply(smooth, moving, new AnimatedProgress.Msg.Settle());
        assertThat(settled.running()).isFalse();
        assertThat(displayed(smooth, settled)).isEqualTo(1.0);
        assertThat(Subscription.leaves(smooth.subscriptions(settled))).isEmpty();
    }

    @Test
    void anAdjustmentMovesTheTargetAndClampsIt() {
        AnimatedProgress widget = progress(AnimatedProgress.Config.instant(BAR));
        AnimatedProgress.Model half = apply(widget, AnimatedProgress.Model.at(0.0), new AnimatedProgress.Msg.Set(0.5));
        assertThat(apply(widget, half, new AnimatedProgress.Msg.Adjust(0.25)).target())
                .isEqualTo(0.75);
        assertThat(apply(widget, half, new AnimatedProgress.Msg.Adjust(-0.9)).target())
                .isEqualTo(0.0);
        assertThat(apply(widget, half, new AnimatedProgress.Msg.Adjust(9.0)).target())
                .isEqualTo(1.0);
        assertThat(apply(widget, half, new AnimatedProgress.Msg.Adjust(0.0)))
                .as("a zero adjustment asks for the target the bar already has")
                .isSameAs(half);
        assertThat(apply(widget, half, new AnimatedProgress.Msg.Adjust(Double.MAX_VALUE))
                        .target())
                .as("a huge but finite adjustment clamps instead of overflowing")
                .isEqualTo(1.0);
    }

    @Test
    void aBarDeclaresATickOnlyWhileItMoves() {
        AnimatedProgress widget = progress();
        assertThat(Subscription.leaves(widget.subscriptions(AnimatedProgress.Model.at(0.5))))
                .as("a resting bar is not a source")
                .isEmpty();

        List<Subscription<AnimatedProgress.Msg>> moving = Subscription.leaves(widget.subscriptions(armed(widget, 1.0)));
        assertThat(moving).hasSize(1);
        Subscription.Interval<AnimatedProgress.Msg> interval =
                (Subscription.Interval<AnimatedProgress.Msg>) moving.get(0);
        assertThat(interval.period()).isEqualTo(CONFIG.tickPeriod());
        assertThat(interval.id().value()).isEqualTo("tick");

        // The mapper carries the segment of the model it was declared from, so a tick mapped before
        // a retarget is recognisable afterwards.
        AnimatedProgress.Model segment = new AnimatedProgress.Model(true, 7L, 1.0, 0.0, 0.0, 0L, 0L);
        Subscription.Interval<AnimatedProgress.Msg> mapped = (Subscription.Interval<AnimatedProgress.Msg>)
                Subscription.leaves(widget.subscriptions(segment)).get(0);
        assertThat(mapped.onTick().apply(5_000L)).isEqualTo(new AnimatedProgress.Msg.Tick(7L, 5_000L));
    }

    @Test
    void aMountedBarIsSettledAtZeroAndPaintsItsOwnValue() {
        AnimatedProgress widget = progress();
        AnimatedProgress.Model model = widget.init(new InitContext(null, null)).model();
        assertThat(model.settled()).isTrue();
        assertThat(model.target()).isZero();
        assertThat(Subscription.leaves(widget.subscriptions(model))).isEmpty();
        assertThat(painted(widget.view(model, CONTEXT)).get(0).strip())
                .as("the painted view is the shared renderer's output")
                .isEqualTo(EMPTY.repeat(20));
    }

    // ── R-03: graded fills and blended colours ───────────────────────────────

    @Test
    void aPartialFillGradesTheCellTheFillStopsIn() {
        ProgressBar graded = ProgressBar.of(4).fractional(true);
        assertThat(painted(graded, 0.0, 1.0)).isEqualTo(EMPTY.repeat(4));
        assertThat(painted(graded, 1.0, 1.0)).isEqualTo(FULL.repeat(4));
        // A quarter of four cells is exactly one cell, so nothing is graded.
        assertThat(painted(graded, 0.25, 1.0)).isEqualTo(FULL + EMPTY.repeat(3));
        // Half a cell is the half-block glyph, and every eighth is its own glyph.
        assertThat(painted(graded, 0.125, 1.0)).isEqualTo("\u258C" + EMPTY.repeat(3));
        assertThat(painted(graded, 0.03125, 1.0)).isEqualTo("\u258F" + EMPTY.repeat(3));
        assertThat(painted(graded, 0.09375, 1.0)).isEqualTo("\u258D" + EMPTY.repeat(3));
        assertThat(painted(graded, 0.15625, 1.0)).isEqualTo("\u258B" + EMPTY.repeat(3));
        assertThat(painted(graded, 0.21875, 1.0)).isEqualTo("\u2589" + EMPTY.repeat(3));
        assertThat(painted(graded, 0.9, 1.0)).isEqualTo(FULL.repeat(3) + "\u258B");
        // A remainder of half an eighth or more rounds up; below that the cell stays empty.
        assertThat(painted(graded, 0.2421875, 1.0))
                .as("just under a whole cell")
                .isEqualTo(FULL + EMPTY.repeat(3));
        assertThat(painted(graded, 0.0078125, 1.0))
                .as("one thirty-second of the bar")
                .isEqualTo(EMPTY.repeat(4));

        // The default bar keeps rounding to whole cells, which is what the phase must preserve.
        assertThat(painted(ProgressBar.of(4), 0.125, 1.0))
                .as("a bar that did not opt in still rounds half a cell up")
                .isEqualTo(FULL + EMPTY.repeat(3));

        // The smallest bars still grade, and a one-cell bar has nowhere to put a whole cell.
        assertThat(painted(ProgressBar.of(1).fractional(true), 0.5, 1.0)).isEqualTo("\u258C");
        assertThat(painted(ProgressBar.of(1).fractional(true), 0.99, 1.0)).isEqualTo(FULL);
        assertThat(painted(ProgressBar.of(1).fractional(true), 0.1, 1.0)).isEqualTo("\u258F");
        assertThat(painted(ProgressBar.of(1).fractional(true), 0.01, 1.0))
                .as("below half an eighth the cell is still empty")
                .isEqualTo(EMPTY);
        assertThat(painted(ProgressBar.of(4), 0.9, 1.0)).isEqualTo(FULL.repeat(4));
    }

    @Test
    void aPartialFillKeepsTheRowsWidthExact() {
        // A graded cell is padded to the active icon's width, so crossing a cell boundary cannot
        // change the number of columns a row occupies.
        ProgressBar narrow = ProgressBar.of(5).fractional(true);
        ProgressBar wide = ProgressBar.of(5).fractional(true).icons("\u6F22", "\u65E5");
        // The percentage label is as wide as the number it prints, which is the static bar's own
        // documented behaviour, so the constant-width invariant is asserted on the cells and the
        // labels rather than on the label's digits.
        ProgressBar dressed = ProgressBar.of(5)
                .fractional(true)
                .buffer(2)
                .prepend(StyledText.plain("pre"))
                .append(StyledText.plain("post"));
        for (int step = 0; step <= 256; step++) {
            double fraction = step / 256.0;
            assertThat(TextMeasure.columns(narrow.render(fraction, 1.0).plainText()))
                    .as("single-column icons at %s", fraction)
                    .isEqualTo(5);
            assertThat(TextMeasure.columns(wide.render(fraction, 1.0).plainText()))
                    .as("two-column icons at %s", fraction)
                    .isEqualTo(10);
            assertThat(TextMeasure.columns(dressed.render(fraction, 1.0).plainText()))
                    .as("buffers and labels at %s", fraction)
                    .isEqualTo(3 + 5 + 4 * 2 + 4);
        }
    }

    @Test
    void aGradedBarReportsTheSamePercentageAsTheStaticOne() {
        for (double value : new double[] {0.0, 0.125, 0.4, 0.9375, 1.0}) {
            assertThat(ProgressBar.of(9)
                            .fractional(true)
                            .showPercentage(true)
                            .render(value, 1.0)
                            .plainText())
                    .as("value %s", value)
                    .endsWith(ProgressBar.of(9)
                            .showPercentage(true)
                            .render(value, 1.0)
                            .plainText()
                            .substring(10));
        }
    }

    @Test
    void aPartialFillNeverSplitsAGrapheme() {
        // A two-column glyph is painted whole; the graded cell that replaces it is padded with a
        // space rather than half a glyph.
        String row = painted(ProgressBar.of(3).fractional(true).icons("\u6F22", " "), 0.5, 1.0);
        assertThat(row).isEqualTo("\u6F22\u258C  ");
        assertThat(TextMeasure.graphemes(row)).hasSize(4);
        assertThat(TextMeasure.columns(row))
                .as("the graded cell is padded to the active icon's own width")
                .isEqualTo(2 + 2 + 1);
    }

    @Test
    void aBlendRunsFromTheFirstCellToTheLastInBarOrder() {
        ProgressBar blended = ProgressBar.of(4).blend(RED, BLUE);
        assertThat(foregrounds(blended.render(1.0, 1.0)))
                .containsExactly(RED, new Color(170, 0, 85), new Color(85, 0, 170), BLUE);
        // The ramp is anchored to the bar, so a cell keeps its colour as the fill grows.
        assertThat(foregrounds(blended.render(0.5, 1.0)))
                .as("the first two cells of a half-filled bar, then the empty ones")
                .containsExactly(RED, new Color(170, 0, 85), null, null);
        assertThat(foregrounds(blended.render(0.25, 1.0)))
                .as("a quarter of the bar is the start of the same ramp")
                .containsExactly(RED, null, null, null);

        // A graded fill's partial cell is on the ramp too, and the empty cells keep their own style.
        ProgressBar graded = ProgressBar.of(4).fractional(true).blend(RED, BLUE);
        StyledText half = graded.render(0.375, 1.0);
        assertThat(half.plainText()).isEqualTo(FULL + "\u258C" + EMPTY.repeat(2));
        assertThat(foregrounds(half)).containsExactly(RED, new Color(170, 0, 85), null, null);

        // One cell is the start of the ramp; a missing colour means no blend at all.
        assertThat(foregrounds(ProgressBar.of(1).blend(RED, BLUE).render(1.0, 1.0)))
                .as("one cell is the start of the ramp")
                .containsExactly(RED);
        assertThat(foregrounds(
                        ProgressBar.of(2).blend(null, BLUE).colors(RED, null).render(1.0, 1.0)))
                .as("a blend with no start is not a blend")
                .containsOnly(RED);
        assertThat(foregrounds(ProgressBar.of(2).blend(RED, null).render(1.0, 1.0)))
                .as("a blend with no end is not a blend")
                .containsOnlyNulls();
    }

    @Test
    void theStaticBarPaintsWhatItAlwaysPainted() {
        assertThat(painted(ProgressBar.of(4), 2.0, 4.0)).isEqualTo(FULL.repeat(2) + EMPTY.repeat(2));
        assertThat(painted(ProgressBar.of(4), 20.0, 10.0)).isEqualTo(FULL.repeat(4));
        assertThat(painted(ProgressBar.of(4).showPercentage(true), 1.0, 3.0))
                .isEqualTo(FULL + EMPTY.repeat(3) + " 33%");
        assertThat(painted(ProgressBar.of(3).buffer(2), 1.0, 1.0)).isEqualTo(FULL + "  " + FULL + "  " + FULL);
        assertThat(ProgressBar.of(3).fractional(false).columns(0.5, 1.0)).isEqualTo(3);
        assertThat(ProgressBar.of(3).blend(null, null).columns(0.5, 1.0)).isEqualTo(3);
    }

    @Test
    void aLongGradedBarStaysWithinItsBoundedCost() {
        ProgressBar graded = ProgressBar.of(4096).fractional(true);
        assertThat(AllocationProbe.bytes(() -> graded.render(0.5 + 1.0 / 4096.0, 1.0)))
                .as("a graded bar does not copy its growing runs for every cell")
                .isLessThan(2_000_000L);
        assertThat(painted(graded, 0.5, 1.0))
                .as("half of an even bar is exactly whole cells")
                .isEqualTo(FULL.repeat(2048) + EMPTY.repeat(2048));
    }

    @Test
    void renderingIsPureAndRepeatable() {
        ProgressBar graded = ProgressBar.of(9).fractional(true).blend(RED, BLUE).showPercentage(true);
        StyledText first = graded.render(0.4, 1.0);
        StyledText second = graded.render(0.4, 1.0);
        assertThat(second).isEqualTo(first);
        assertThat(graded.render(0.4, 1.0)).isEqualTo(first);

        AnimatedProgress widget = progress(AnimatedProgress.Config.of(graded));
        AnimatedProgress.Model model = ran(widget, armed(widget, 0.8), 40L);
        StyledText once = AnimatedProgress.render(model, widget.config());
        assertThat(AnimatedProgress.render(model, widget.config())).isEqualTo(once);
        assertThat(painted(widget.view(model, CONTEXT)).get(0).strip()).isEqualTo(once.plainText());
    }

    private static List<Color> foregrounds(StyledText text) {
        List<Color> colors = new ArrayList<>();
        for (StyledText.Span span : text.spans()) {
            for (int index = 0; index < TextMeasure.graphemes(span.text()).size(); index++) {
                colors.add(span.style().foreground());
            }
        }
        return colors;
    }

    // ── R-04: the demo, its gestures and its two regimes ─────────────────────

    @Test
    void theDemoStartsIdleWithBothBarsEmptyAndTheSpinnerTurning() {
        ProgressDemo.Model model = start();
        assertThat(row(model, 0)).isEqualTo("animated target=0.000 displayed=0.000 run=off");
        assertThat(row(model, 1)).isEqualTo("width=32 pkgs=0/6 motion=smooth fill=plain last=none");
        assertThat(row(model, 3)).isEqualTo("install " + EMPTY.repeat(32) + " 0%");
        assertThat(row(model, 4)).isEqualTo("detail  " + EMPTY.repeat(32) + " 0%");
        assertThat(row(model, 6)).endsWith("installing figlet 0/6");
        assertThat(demo().children(model).nodes()).hasSize(2);
        assertThat(Subscription.leaves(demo().subscriptions(model)).stream()
                        .map(leaf -> leaf.id().toString())
                        .toList())
                .as("only the spinner's interval and the scene's resize source")
                .containsExactlyInAnyOrder("/spinner:tick", "resize");
        assertThat(Subscription.leaves(demo().subscriptions(model)))
                .filteredOn(leaf -> leaf.id().value().equals("tick"))
                .singleElement()
                .satisfies(leaf ->
                        assertThat(((Subscription.Interval<?>) leaf).period()).isEqualTo(Duration.ofMillis(80)));
    }

    @Test
    void installRetargetsTheBarAndFinishingTheRunStopsTheSpinner() {
        ProgressDemo.Model model = send(start(), "s");
        assertThat(row(model, 0))
                .as("the retarget paints the position it had, and the segment is live")
                .isEqualTo("animated target=0.167 displayed=0.000 run=on");
        assertThat(row(model, 1)).contains("pkgs=1/6").contains("last=install-figlet");
        assertThat(Subscription.leaves(demo().subscriptions(model)))
                .as("the moving bar adds its own interval")
                .hasSize(3);

        ProgressDemo.Model moving = ease(model, 32L);
        assertThat(row(moving, 0)).endsWith("run=on");
        assertThat(displayed(moving)).isStrictlyBetween(0.0, 1.0 / 6.0);
        assertThat(row(moving, 4))
                .as("the static bar shows its target while the animated one is still behind it")
                .isEqualTo("detail  " + FULL.repeat(5) + EMPTY.repeat(27) + " 17%");

        ProgressDemo.Model rested = ease(moving, SETTLED_MILLIS);
        assertThat(row(rested, 0)).isEqualTo("animated target=0.167 displayed=0.167 run=off");
        assertThat(row(rested, 3))
                .as("the rested row is the static row with its own label")
                .isEqualTo("install " + FULL.repeat(5) + EMPTY.repeat(27) + " 17%");
        assertThat(Subscription.leaves(demo().subscriptions(rested))).hasSize(2);

        ProgressDemo.Model finished = rested;
        for (int packageIndex = 2; packageIndex <= ProgressDemo.PACKAGES.size(); packageIndex++) {
            finished = ease(send(finished, "s"), SETTLED_MILLIS);
            assertThat(row(finished, 1)).contains("pkgs=" + packageIndex + "/6");
        }
        assertThat(row(finished, 0)).isEqualTo("animated target=1.000 displayed=1.000 run=off");
        assertThat(row(finished, 1)).contains("last=done");
        assertThat(row(finished, 3)).isEqualTo("install " + FULL.repeat(32) + " 100%");
        assertThat(row(finished, 6)).endsWith("complete 6/6");
        assertThat(finished.spinner().running())
                .as("the work the spinner was turning for is done")
                .isFalse();
        assertThat(Subscription.leaves(demo().subscriptions(finished)))
                .as("a finished run leaves only the scene's resize source")
                .hasSize(1);
        assertThat(apply(finished, new ProgressDemo.Msg.Install()).installed())
                .as("a finished run cannot install a seventh package")
                .isEqualTo(ProgressDemo.PACKAGES.size());
    }

    @Test
    void uninstallEasesTheBarBackDownAndRestartsTheSpinner() {
        ProgressDemo.Model finished = start();
        for (int packageIndex = 1; packageIndex <= ProgressDemo.PACKAGES.size(); packageIndex++) {
            finished = ease(send(finished, "s"), SETTLED_MILLIS);
        }
        ProgressDemo.Model model = send(finished, "u");
        assertThat(row(model, 0))
                .as("the bar keeps the position it had while the new segment arms")
                .isEqualTo("animated target=0.833 displayed=1.000 run=on");
        assertThat(row(model, 1)).contains("pkgs=5/6").contains("last=back-bubbletea");
        assertThat(model.spinner().running()).as("work is pending again").isTrue();

        ProgressDemo.Model settled = ease(model, SETTLED_MILLIS);
        assertThat(row(settled, 0)).isEqualTo("animated target=0.833 displayed=0.833 run=off");
        assertThat(apply(settled, new ProgressDemo.Msg.Uninstall()).installed()).isEqualTo(4);
        ProgressDemo.Model empty = send(start(), "u");
        assertThat(row(empty, 1)).contains("pkgs=0/6").contains("last=empty");
    }

    @Test
    void theMotionKeySnapsToTheTargetAndDropsTheTimer() {
        ProgressDemo.Model moving = ease(send(start(), "s"), 32L);
        assertThat(row(moving, 0)).endsWith("run=on");
        ProgressDemo.Model instant = send(moving, "m");
        assertThat(row(instant, 1)).contains("motion=instant").contains("last=motion-instant");
        assertThat(row(instant, 0))
                .as("a bar that was eased is landed on its target when motion is turned off")
                .isEqualTo("animated target=0.167 displayed=0.167 run=off");
        assertThat(Subscription.leaves(demo().subscriptions(instant)))
                .as("the spinner's interval and the resize source are all that is left")
                .hasSize(2);

        // An instant install is the target immediately, with no intermediate frames at all.
        ProgressDemo.Model installed = send(instant, "s");
        assertThat(row(installed, 0)).isEqualTo("animated target=0.333 displayed=0.333 run=off");
        assertThat(row(installed, 3)).isEqualTo("install " + FULL.repeat(11) + EMPTY.repeat(21) + " 33%");
        assertThat(send(installed, "m").motion()).isEqualTo(AnimatedProgress.Motion.SMOOTH);
    }

    @Test
    void theFillKeyCyclesBothBarsPaint() {
        ProgressDemo.Model settled = ease(send(start(), "s"), SETTLED_MILLIS);
        assertThat(row(settled, 4)).isEqualTo("detail  " + FULL.repeat(5) + EMPTY.repeat(27) + " 17%");

        ProgressDemo.Model partial = send(settled, "b");
        assertThat(row(partial, 1)).contains("fill=partial").contains("last=fill-partial");
        String graded = "detail  " + FULL.repeat(5) + "\u258D" + EMPTY.repeat(26) + " 17%";
        assertThat(row(partial, 4)).isEqualTo(graded);
        assertThat(row(partial, 3)).isEqualTo(graded.replace("detail  ", "install "));
        // The percentage follows the same fill, so a graded bar still reports its own value.
        assertThat(partial.staticBar()
                        .showPercentage(true)
                        .render(partial.target(), 1.0)
                        .plainText())
                .endsWith(" 17%");

        ProgressDemo.Model blended = send(partial, "b");
        assertThat(row(blended, 1)).contains("fill=blend");
        assertThat(row(blended, 4))
                .as("a blend changes the colours, not the cells")
                .isEqualTo("detail  " + FULL.repeat(5) + EMPTY.repeat(27) + " 17%");
        StyledText painted = blended.staticBar().render(blended.target(), 1.0);
        assertThat(painted.spans().get(1).style().foreground())
                .as("the first filled cell is the start of the blend")
                .isEqualTo(ProgressDemo.BLEND_START);

        ProgressDemo.Model both = send(blended, "b");
        assertThat(row(both, 1)).contains("fill=partial+blend");
        assertThat(row(both, 4)).isEqualTo(graded);
        assertThat(send(both, "b").fill()).isEqualTo(ProgressDemo.Fill.PLAIN);
    }

    @Test
    void aResizeLaysTheSameValueOutAtTheNewWidth() {
        ProgressDemo.Model model = ease(send(start(), "s"), SETTLED_MILLIS);
        double value = displayed(model);
        ProgressDemo.Model narrow = apply(model, new ProgressDemo.Msg.Resized(new Size(24, 40)));
        assertThat(row(narrow, 1)).startsWith("width=24 ");
        assertThat(displayed(narrow)).as("the normalized value does not move").isEqualTo(value);
        assertThat(row(narrow, 3)).isEqualTo("install " + FULL.repeat(4) + EMPTY.repeat(20) + " 17%");
        assertThat(row(narrow, 4)).isEqualTo("detail  " + FULL.repeat(4) + EMPTY.repeat(20) + " 17%");
        assertThat(row(narrow, 1, 40)).startsWith("width=24 pkgs=1/6");
        assertThat(painted(demo().view(narrow, CONTEXT), 24, 40))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(40));

        ProgressDemo.Model wide = apply(narrow, new ProgressDemo.Msg.Resized(new Size(24, 200)));
        assertThat(row(wide, 1)).as("the scene's own maximum width").startsWith("width=32 ");
        ProgressDemo.Model tiny = apply(narrow, new ProgressDemo.Msg.Resized(new Size(24, 10)));
        assertThat(row(tiny, 1)).as("the scene's own minimum width").startsWith("width=8 ");
        assertThat(apply(narrow, new ProgressDemo.Msg.Resized(new Size(24, 40))))
                .as("a resize that changes nothing keeps the model it had")
                .isSameAs(narrow);
    }

    @Test
    void everySceneGestureIsReachableFromTheRoutedKeyMap() {
        ProgressDemo.Model model = start();
        assertThat(route(model, "s")).contains(new ProgressDemo.Msg.Install());
        assertThat(route(model, "space")).contains(new ProgressDemo.Msg.Install());
        assertThat(route(model, "u")).contains(new ProgressDemo.Msg.Uninstall());
        assertThat(route(model, "backspace")).contains(new ProgressDemo.Msg.Uninstall());
        assertThat(route(model, "r")).contains(new ProgressDemo.Msg.Reset());
        assertThat(route(model, "m")).contains(new ProgressDemo.Msg.CycleMotion());
        assertThat(route(model, "b")).contains(new ProgressDemo.Msg.CycleFill());
        assertThat(route(model, "escape")).contains(new ProgressDemo.Msg.Quit());
        assertThat(route(model, "q")).as("an unbound key reaches nothing").isEmpty();

        List<String> help = painted(Help.view(ProgressDemo.defaultKeys(), true, 200), 8, 200).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(help).hasSize(3);
        assertThat(help.get(0)).contains("install").contains("uninstall").contains("reset");
        assertThat(help.get(1)).contains("motion").contains("fill");
        assertThat(help.get(2)).contains("quit");
    }

    @Test
    void aNarrowSceneClipsInsteadOfWrapping() {
        ProgressDemo.Model model = ease(send(start(), "s"), SETTLED_MILLIS);
        model = apply(model, new ProgressDemo.Msg.Resized(new Size(24, 40)));
        List<String> lines = painted(demo().view(model, CONTEXT), 24, 40);
        // Every status row stays on its own row however narrow the terminal is, so the bars and the
        // help do not move down and a verifier reads the same rows at any width.
        assertThat(lines.get(0).strip()).startsWith("animated target=0.167 displayed=0.167");
        assertThat(lines.get(1).strip()).startsWith("width=24 pkgs=1/6 motion=smooth");
        assertThat(lines.get(3).strip()).isEqualTo("install " + FULL.repeat(4) + EMPTY.repeat(20) + " 17%");
        assertThat(lines.get(5)).isEmpty();
        assertThat(lines.get(8)).as("the help is cut, not wrapped").startsWith("s install");
    }

    @Test
    void escapeQuitsTheDemo() {
        Update<ProgressDemo.Model, ProgressDemo.Msg, Never> update =
                demo().update(start(), new ProgressDemo.Msg.Quit());
        assertThat(update.command()).isInstanceOf(Command.Quit.class);
        assertThat(update.outputs()).isEmpty();
    }

    @Test
    void theDemoRunsUnderTheDeterministicDriver() {
        ComponentDriver<ProgressDemo.Model, ProgressDemo.Msg, Never> driver =
                ComponentDriver.initialize(demo(), new InitContext(new Viewport(24, 80), null));
        driver.send(new ProgressDemo.Msg.Install());
        ProgressDemo.Model armed = driver.model();
        assertThat(driver.screen(CONTEXT).plainLine(0)).isEqualTo("animated target=0.167 displayed=0.000 run=on");
        driver.send(new ProgressDemo.Msg.Bar(
                new AnimatedProgress.Msg.Tick(armed.bar().epoch(), 800 * MILLIS)));
        driver.send(new ProgressDemo.Msg.Bar(
                new AnimatedProgress.Msg.Tick(armed.bar().epoch(), 1_600 * MILLIS)));
        assertThat(driver.screen(CONTEXT).plainLine(0)).isEqualTo("animated target=0.167 displayed=0.167 run=off");
        assertThat(driver.children().nodes()).hasSize(2);
    }

    // ── R-05: lifetimes, proved by the intervals that stop ───────────────────

    @Test
    void theBarAndSpinnerHoldTheirOwnIntervalsAndBothStopWhenTheyShould() {
        ProgressDemo.Scene scene = demo();
        ProgressDemo.Model model = start();
        ManualTimers timers = new ManualTimers();
        Mailbox mailbox = new Mailbox(16);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        SubscriptionManager<ProgressDemo.Msg> manager = new SubscriptionManager<>(mailbox, timers, 8, diagnostics::add);
        try {
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending())
                    .as("only the spinner schedules a tick at rest")
                    .isEqualTo(1);

            model = scene.update(model, new ProgressDemo.Msg.Install()).model();
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).as("the moving bar adds one").isEqualTo(2);

            // A scheduled tick reaches the bar through the runtime and names its segment's origin.
            timers.advance(16 * MILLIS);
            List<ProgressDemo.Msg> ticks = drained(mailbox);
            assertThat(ticks).anySatisfy(message -> assertThat(message).isInstanceOf(ProgressDemo.Msg.Bar.class));
            for (ProgressDemo.Msg message : ticks) {
                model = scene.update(model, message).model();
            }
            assertThat(model.bar().armed()).as("the tick recorded the origin").isFalse();

            // The segment is then driven to rest by explicit readings, which is what time passing
            // looks like to the widget; the interval is what has to disappear when it arrives.
            AnimatedProgress.Model moving = model.bar();
            model = scene.update(
                            model,
                            new ProgressDemo.Msg.Bar(new AnimatedProgress.Msg.Tick(
                                    moving.epoch(), moving.originNanos() + 1_000 * MILLIS)))
                    .model();
            assertThat(model.bar().running()).as("the bar came to rest").isFalse();
            assertThat(displayed(model)).isEqualTo(1.0 / 6.0);
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending())
                    .as("the rested bar released its interval")
                    .isEqualTo(1);
            timers.advance(1_000 * MILLIS);
            assertThat(drained(mailbox))
                    .as("only the spinner is left ticking")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message).isInstanceOf(ProgressDemo.Msg.Spin.class));
            assertThat(diagnostics).isEmpty();

            // Finishing the run stops the spinner too, so nothing is left scheduled at all.
            for (int packageIndex = 2; packageIndex <= ProgressDemo.PACKAGES.size(); packageIndex++) {
                model = scene.update(model, new ProgressDemo.Msg.Install()).model();
                model = rest(scene, model, 1_000 * MILLIS);
                manager.reconcile(scene.subscriptions(model));
            }
            assertThat(model.complete()).isTrue();
            assertThat(model.bar().running()).isFalse();
            assertThat(model.spinner().running()).isFalse();
            assertThat(timers.pending()).as("a finished run holds no timer").isZero();
            timers.advance(10_000 * MILLIS);
            assertThat(drained(mailbox)).as("no source is left to emit").isEmpty();
            assertThat(diagnostics).isEmpty();
        } finally {
            manager.closeAll();
            mailbox.close();
        }
    }

    @Test
    void everySourceIsScopedToItsOwnMount() {
        ProgressDemo.Model model = ease(send(start(), "s"), 32L);
        List<Subscription<ProgressDemo.Msg>> leaves = Subscription.leaves(demo().subscriptions(model));
        assertThat(leaves.stream().map(leaf -> leaf.id().toString()).toList())
                .as("one interval per mounted component plus the scene's resize source")
                .containsExactlyInAnyOrder("/bar:tick", "/spinner:tick", "resize");
        assertThat(leaves)
                .filteredOn(leaf -> !(leaf instanceof Subscription.Resize<ProgressDemo.Msg>))
                .allSatisfy(leaf -> assertThat(leaf.id().value()).isEqualTo("tick"));
    }

    /** Every message the mailbox holds, without waiting for more. */
    private static List<ProgressDemo.Msg> drained(Mailbox mailbox) {
        List<ProgressDemo.Msg> messages = new ArrayList<>();
        while (true) {
            Mailbox.Envelope envelope;
            try {
                envelope = mailbox.poll(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (envelope == null) {
                return messages;
            }
            assertThat(envelope).isInstanceOf(Mailbox.AppMessage.class);
            messages.add((ProgressDemo.Msg) ((Mailbox.AppMessage) envelope).payload());
        }
        return messages;
    }

    @Test
    void theStatusRowReportsTheScaleToThreeDecimals() {
        ProgressDemo.Model model = start();
        assertThat(row(model, 0)).contains("target=0.000").contains("displayed=0.000");
        ProgressDemo.Model one = send(model, "s");
        assertThat(row(one, 0)).as("a sixth of the scale").contains("target=0.167");
        assertThat(displayed(one)).isCloseTo(0.0, within(1e-12));
        ProgressDemo.Model three = ease(send(ease(send(one, "s"), SETTLED_MILLIS), "s"), SETTLED_MILLIS);
        assertThat(row(three, 0)).contains("target=0.500");
        assertThat(painted(demo().view(three, CONTEXT)).get(6).strip()).endsWith("installing harmonica 3/6");
    }

    @Test
    void aResetStartsTheRunOver() {
        ProgressDemo.Model model = start();
        for (int packageIndex = 1; packageIndex <= 3; packageIndex++) {
            model = ease(send(model, "s"), SETTLED_MILLIS);
        }
        ProgressDemo.Model reset = send(model, "r");
        assertThat(row(reset, 1)).contains("pkgs=0/6").contains("last=reset");
        assertThat(row(reset, 0))
                .as("a reset retargets from the half-filled bar it had")
                .isEqualTo("animated target=0.000 displayed=0.500 run=on");
        assertThat(reset.spinner().running()).isTrue();
        ProgressDemo.Model settled = ease(reset, SETTLED_MILLIS);
        assertThat(row(settled, 0)).isEqualTo("animated target=0.000 displayed=0.000 run=off");
        assertThat(row(settled, 6)).endsWith("installing figlet 0/6");
        assertThat(Subscription.leaves(demo().subscriptions(settled))).hasSize(2);
        assertThat(painted(demo().view(settled, CONTEXT)).get(3).strip())
                .isEqualTo("install " + EMPTY.repeat(32) + " 0%");
    }

    @Test
    void anInstantResetDoesNotAnimateAtAll() {
        ProgressDemo.Model model = start();
        for (int packageIndex = 1; packageIndex <= 2; packageIndex++) {
            model = ease(send(model, "s"), SETTLED_MILLIS);
        }
        ProgressDemo.Model instant = send(model, "m");
        assertThat(row(instant, 0)).isEqualTo("animated target=0.333 displayed=0.333 run=off");
        ProgressDemo.Model reset = send(instant, "r");
        assertThat(row(reset, 0))
                .as("an instant reset lands on zero without a segment")
                .isEqualTo("animated target=0.000 displayed=0.000 run=off");
        assertThat(reset.bar().armed()).isFalse();
    }
}
