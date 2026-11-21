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

import examples.parity.StopwatchDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.runtime.Mailbox;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.SubscriptionManager;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Stopwatch;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 10 acceptance: the stopwatch widget and the two-watch stopwatch demo.
 *
 * <p>The timing cases drive {@code update} with explicit monotonic readings, so the asserted timing
 * is exact and involves no sleeps; a "late reading" is a number, not a wall clock. The lifecycle
 * cases reconcile the demo's own subscription declarations against {@link ManualTimers}, so "no
 * timer while stopped" and "two watches tick independently" are proven by the intervals that are
 * scheduled rather than by values that happened to hold still. The terminal behavior of the
 * packaged demo is verified separately by {@code python3 tools/terminal/verify_terminal.py
 * --parity-phase 10}.</p>
 */
@Timeout(20)
class ParityStopwatchTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final long MILLIS = 1_000_000L;

    private static final Stopwatch.Config CONFIG = Stopwatch.Config.defaults();

    // ── the widget's own helpers ─────────────────────────────────────────────

    private static Stopwatch stopwatch() {
        return new Stopwatch(CONFIG);
    }

    private static Stopwatch.Model initial(Stopwatch widget) {
        return widget.init(new InitContext(null, null)).model();
    }

    private static Update<Stopwatch.Model, Stopwatch.Msg, Never> send(
            Stopwatch widget, Stopwatch.Model model, Stopwatch.Msg message) {
        return widget.update(model, message);
    }

    private static Stopwatch.Model apply(Stopwatch widget, Stopwatch.Model model, Stopwatch.Msg message) {
        return send(widget, model, message).model();
    }

    /**
     * The reading a timestamp request produces: how a start or a reset is told what time it is.
     */
    private static Stopwatch.Msg reading(Command<Stopwatch.Msg> command, long atMillis) {
        return readingAt(command, atMillis * MILLIS);
    }

    /**
     * The reading a timestamp request produces at an absolute monotonic time in nanoseconds.
     */
    private static Stopwatch.Msg readingAt(Command<Stopwatch.Msg> command, long nowNanos) {
        assertThat(command)
                .as("a run waiting for its origin asks the runtime for one reading")
                .isInstanceOf(Command.MonotonicTime.class);
        return ((Command.MonotonicTime<Stopwatch.Msg>) command).mapper().apply(nowNanos);
    }

    /**
     * One reading of the model's current run, at an absolute monotonic time in milliseconds.
     */
    private static Stopwatch.Model at(Stopwatch widget, Stopwatch.Model model, long atMillis) {
        return apply(widget, model, new Stopwatch.Msg.Reading(model.epoch(), atMillis * MILLIS));
    }

    /**
     * A running stopwatch whose origin the runtime named at {@code atMillis}.
     */
    private static Stopwatch.Model running(Stopwatch widget, long atMillis) {
        Update<Stopwatch.Model, Stopwatch.Msg, Never> start = send(widget, initial(widget), new Stopwatch.Msg.Start());
        return apply(widget, start.model(), reading(start.command(), atMillis));
    }

    // ── the demo's own helpers ───────────────────────────────────────────────

    /**
     * The demo the packaged launch runs, driven as its own component.
     */
    private static StopwatchDemo.Scene demo() {
        return new StopwatchDemo.Scene();
    }

    private static StopwatchDemo.Model initial(StopwatchDemo.Scene scene) {
        return scene.init(new InitContext(new Viewport(24, 80), null)).model();
    }

    /**
     * The demo's rows, so a test can assert the scene without decoding styling.
     */
    private static List<String> painted(StopwatchDemo.Scene scene, StopwatchDemo.Model model) {
        return painted(scene.view(model, CONTEXT), 24, 80);
    }

    private static List<String> painted(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols).lines();
    }

    private static String row(StopwatchDemo.Scene scene, StopwatchDemo.Model model, int index) {
        return painted(scene, model).get(index).strip();
    }

    /**
     * What one key does to the demo's screen, as the scene's own message.
     */
    private static Optional<StopwatchDemo.Msg> route(StopwatchDemo.Model model, String keyName) {
        return io.github.dhoard.braxton.testing.Routing.route(demo(), model, CONTEXT, key(keyName)).stream()
                .findFirst();
    }

    private static InputEvent.KeyInput key(String spec) {
        Key gesture = Key.parse(spec);
        return InputEvent.KeyInput.of(gesture, gesture.name());
    }

    /**
     * Drives one scene message and everything the runtime would deliver after it, at a chosen time.
     *
     * <p>A stopwatch's timestamp request crosses the mount as a scoped command, so applying its
     * mapper here is the same path the runtime takes: the origin comes from the command, not from
     * the test's copy of the state.</p>
     */
    private static StopwatchDemo.Model deliver(
            StopwatchDemo.Scene scene, StopwatchDemo.Model model, StopwatchDemo.Msg message, long nowNanos) {
        ArrayDeque<StopwatchDemo.Msg> pending = new ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            Update<StopwatchDemo.Model, StopwatchDemo.Msg, Never> update = scene.update(model, pending.removeFirst());
            model = update.model();
            inspect(update.command(), pending, nowNanos);
        }
        return model;
    }

    private static void inspect(
            Command<StopwatchDemo.Msg> command, ArrayDeque<StopwatchDemo.Msg> pending, long nowNanos) {
        switch (command) {
            case Command.None<StopwatchDemo.Msg> none -> {}
            case Command.Batch<StopwatchDemo.Msg> batch ->
                batch.commands().forEach(item -> inspect(item, pending, nowNanos));
            case Command.Scoped<StopwatchDemo.Msg> scoped -> inspect(scoped.command(), pending, nowNanos);
            case Command.MonotonicTime<StopwatchDemo.Msg> time ->
                pending.add(time.mapper().apply(nowNanos));
            case Command.Dispatch<StopwatchDemo.Msg> dispatch -> pending.add(dispatch.message());
            default -> {}
        }
    }

    /**
     * One watch of the demo's model.
     */
    private static Stopwatch.Model watch(StopwatchDemo.Model model, boolean leftPane) {
        return leftPane ? model.left() : model.right();
    }

    /**
     * One reading of the demo's left or right watch, as the mounted component would receive it.
     */
    private static StopwatchDemo.Msg tick(StopwatchDemo.Model model, boolean leftPane, long atMillis) {
        Stopwatch.Msg reading = new Stopwatch.Msg.Reading(watch(model, leftPane).epoch(), atMillis * MILLIS);
        return leftPane ? new StopwatchDemo.Msg.Left(reading) : new StopwatchDemo.Msg.Right(reading);
    }

    /**
     * The demo's left watch started by one gesture, with the runtime's timestamp fact delivered.
     */
    private static StopwatchDemo.Model startedLeft(StopwatchDemo.Scene scene, long atMillis) {
        return deliver(scene, initial(scene), new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Start()), atMillis * MILLIS);
    }

    /**
     * Every message the mailbox holds, without waiting for more.
     */
    private static List<StopwatchDemo.Msg> drained(Mailbox mailbox) {
        List<StopwatchDemo.Msg> messages = new ArrayList<>();
        while (true) {
            Mailbox.Envelope envelope;
            try {
                envelope = mailbox.poll(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return messages;
            }
            if (envelope == null) {
                return messages;
            }
            assertThat(envelope).isInstanceOf(Mailbox.AppMessage.class);
            messages.add((StopwatchDemo.Msg) ((Mailbox.AppMessage) envelope).payload());
        }
    }

    // ── R-01: defined state, defined gestures ────────────────────────────────

    @Test
    void aStopwatchMountsStoppedAtZero() {
        Stopwatch widget = stopwatch();
        Update<Stopwatch.Model, Stopwatch.Msg, Never> init = widget.init(new InitContext(null, null));
        Stopwatch.Model model = init.model();
        assertThat(model.running()).isFalse();
        assertThat(model.armed()).isFalse();
        assertThat(model.epoch()).isZero();
        assertThat(model.accumulatedNanos()).isZero();
        assertThat(model.originNanos()).isEqualTo(Stopwatch.UNSET_ORIGIN);
        assertThat(model.elapsedNanos()).isZero();
        assertThat(model.elapsed()).isEqualTo(Duration.ZERO);
        assertThat(init.command()).isInstanceOf(Command.None.class);
        assertThat(init.outputs()).as("mounting a watch starts nothing").isEmpty();
        assertThat(Subscription.leaves(widget.subscriptions(model)))
                .as("a stopped stopwatch schedules nothing")
                .isEmpty();
        assertThat(Stopwatch.render(model, CONFIG).plainText()).isEqualTo("00:00:00.000");
    }

    @Test
    void startArmsARunAndAsksTheRuntimeForItsOrigin() {
        Stopwatch widget = stopwatch();
        Update<Stopwatch.Model, Stopwatch.Msg, Never> start = send(widget, initial(widget), new Stopwatch.Msg.Start());
        Stopwatch.Model model = start.model();
        assertThat(model.running()).isTrue();
        assertThat(model.armed()).as("a run with no origin yet is armed").isTrue();
        assertThat(model.epoch()).isEqualTo(1L);
        assertThat(model.elapsed()).as("an armed run has measured nothing yet").isEqualTo(Duration.ZERO);
        assertThat(model.originNanos()).isEqualTo(Stopwatch.UNSET_ORIGIN);
        assertThat(start.outputs()).isEmpty();
        assertThat(start.command()).isInstanceOf(Command.MonotonicTime.class);
        assertThat(Subscription.leaves(widget.subscriptions(model))).hasSize(1);
        assertThat(send(widget, model, new Stopwatch.Msg.Start()).command())
                .as("a start while the run is still waiting for its origin asks for nothing more")
                .isInstanceOf(Command.None.class);
    }

    @Test
    void theTimestampFactNamesTheOriginAtTheInstantTheRunStarted() {
        Stopwatch widget = stopwatch();
        Update<Stopwatch.Model, Stopwatch.Msg, Never> start = send(widget, initial(widget), new Stopwatch.Msg.Start());
        Stopwatch.Msg fact = reading(start.command(), 1_000L);
        assertThat(fact).isEqualTo(new Stopwatch.Msg.Reading(start.model().epoch(), 1_000L * MILLIS));
        Stopwatch.Model model = apply(widget, start.model(), fact);
        assertThat(model.armed()).isFalse();
        assertThat(model.originNanos()).isEqualTo(1_000L * MILLIS);
        assertThat(model.nowNanos()).isEqualTo(1_000L * MILLIS);
        assertThat(model.elapsed()).isEqualTo(Duration.ZERO);

        // The next reading measures the distance from that origin, not from itself.
        assertThat(at(widget, model, 3_000L).elapsed()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aControlMessageThatChangesNothingKeepsTheIncomingInstance() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model running = running(widget, 0L);
        Stopwatch.Model stopped = initial(widget);

        assertThat(send(widget, running, new Stopwatch.Msg.Start()).model())
                .as("starting a running stopwatch")
                .isSameAs(running);
        assertThat(send(widget, stopped, new Stopwatch.Msg.Stop()).model())
                .as("stopping a stopped stopwatch")
                .isSameAs(stopped);
        assertThat(send(widget, stopped, new Stopwatch.Msg.Reading(stopped.epoch(), 5_000L * MILLIS))
                        .model())
                .as("a reading of a run that is not running")
                .isSameAs(stopped);
        assertThat(send(widget, running, new Stopwatch.Msg.Start()).command())
                .as("a gesture that changed nothing asks for nothing")
                .isInstanceOf(Command.None.class);
        assertThat(send(widget, stopped, new Stopwatch.Msg.Stop()).outputs()).isEmpty();
    }

    @Test
    void stopFoldsTheRunAndStartContinuesFromTheTotal() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 20_000L);
        assertThat(model.elapsed()).isEqualTo(Duration.ofSeconds(20));

        Stopwatch.Model stopped = apply(widget, model, new Stopwatch.Msg.Stop());
        assertThat(stopped.running()).isFalse();
        assertThat(stopped.elapsed()).as("a stop keeps what the run measured").isEqualTo(Duration.ofSeconds(20));
        assertThat(stopped.accumulatedNanos()).isEqualTo(20_000L * MILLIS);
        assertThat(stopped.originNanos()).isEqualTo(Stopwatch.UNSET_ORIGIN);
        assertThat(stopped.epoch()).isEqualTo(model.epoch() + 1L);

        // Starting again keeps the total and measures the new run from its own reading, so the time
        // the watch spent stopped is not counted.
        Update<Stopwatch.Model, Stopwatch.Msg, Never> restart = send(widget, stopped, new Stopwatch.Msg.Start());
        Stopwatch.Model armed = restart.model();
        assertThat(armed.accumulatedNanos()).isEqualTo(20_000L * MILLIS);
        assertThat(armed.elapsed()).as("an armed run still reports its total").isEqualTo(Duration.ofSeconds(20));
        Stopwatch.Model second = apply(widget, armed, reading(restart.command(), 100_000L));
        assertThat(second.elapsed()).isEqualTo(Duration.ofSeconds(20));
        assertThat(at(widget, second, 130_000L).elapsed())
                .as("the pause between the two runs is excluded")
                .isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void aToggleStopsAndStartsAgain() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model running = at(widget, running(widget, 0L), 4_000L);

        Stopwatch.Model stopped = apply(widget, running, new Stopwatch.Msg.Toggle());
        assertThat(stopped.running()).isFalse();
        assertThat(stopped.elapsed()).isEqualTo(Duration.ofSeconds(4));
        assertThat(stopped.epoch()).isEqualTo(running.epoch() + 1L);

        Stopwatch.Model started = apply(widget, stopped, new Stopwatch.Msg.Toggle());
        assertThat(started.running()).isTrue();
        assertThat(started.armed()).isTrue();
        assertThat(started.elapsed()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void aResetZeroesTheElapsedTimeAndKeepsWhetherTheWatchRuns() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 30_000L);

        // A reset of a running watch keeps it running, and re-arms it so the new run is measured
        // from the instant the reset was applied rather than from the last repaint before it.
        Update<Stopwatch.Model, Stopwatch.Msg, Never> reset = send(widget, model, new Stopwatch.Msg.Reset());
        Stopwatch.Model armed = reset.model();
        assertThat(armed.running()).isTrue();
        assertThat(armed.armed()).isTrue();
        assertThat(armed.elapsed()).isZero();
        assertThat(armed.epoch()).isEqualTo(model.epoch() + 1L);
        assertThat(reset.command()).isInstanceOf(Command.MonotonicTime.class);
        assertThat(Subscription.leaves(widget.subscriptions(armed))).hasSize(1);

        Stopwatch.Model restarted = apply(widget, armed, reading(reset.command(), 500_000L));
        assertThat(restarted.elapsed()).isZero();
        assertThat(at(widget, restarted, 520_000L).elapsed()).isEqualTo(Duration.ofSeconds(20));

        // The new epoch is what makes a reset cancel the readings the replaced run still had queued.
        assertThat(apply(widget, restarted, new Stopwatch.Msg.Reading(model.epoch(), 900_000L * MILLIS)))
                .isSameAs(restarted);

        // A reset of a stopped watch stays stopped, at zero, under a new epoch.
        Stopwatch.Model stopped = apply(widget, at(widget, restarted, 540_000L), new Stopwatch.Msg.Stop());
        assertThat(stopped.elapsed()).isEqualTo(Duration.ofSeconds(40));
        Update<Stopwatch.Model, Stopwatch.Msg, Never> cleared = send(widget, stopped, new Stopwatch.Msg.Reset());
        assertThat(cleared.model().running())
                .as("a reset keeps the stopped watch stopped")
                .isFalse();
        assertThat(cleared.model().elapsed()).isZero();
        assertThat(cleared.model().epoch()).isEqualTo(stopped.epoch() + 1L);
        assertThat(cleared.command()).as("a stopped watch has no run to re-arm").isInstanceOf(Command.None.class);
        assertThat(Subscription.leaves(widget.subscriptions(cleared.model()))).isEmpty();
    }

    @Test
    void aDefinitionRejectsWhatAStopwatchCannotHonour() {
        assertThatThrownBy(() -> Stopwatch.Config.of(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> Stopwatch.Config.of(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> Stopwatch.Config.of(Duration.ofDays(1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
        assertThatThrownBy(() -> new Stopwatch(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Stopwatch.Model.stopped(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void aDefinitionSubstitutesItsDefaults() {
        Stopwatch.Config defaults = new Stopwatch.Config(null, null, null);
        assertThat(defaults.tickPeriod()).isEqualTo(Stopwatch.DEFAULT_TICK_PERIOD);
        assertThat(defaults.formatter()).isSameAs(Stopwatch.CLOCK);
        assertThat(defaults.style()).isEqualTo(Style.NONE);
        assertThat(defaults.tickPeriodNanos()).isEqualTo(50L * MILLIS);
        assertThat(defaults.withTickPeriod(Duration.ofMillis(10)).tickPeriod()).isEqualTo(Duration.ofMillis(10));
        assertThat(defaults.withStyle(Style.NONE.withBold()).style()).isEqualTo(Style.NONE.withBold());
        assertThat(new Stopwatch(defaults).config()).isSameAs(defaults);
        assertThat(Stopwatch.Config.of(Duration.ofMillis(5)).formatter()).isSameAs(Stopwatch.CLOCK);
        assertThat(Stopwatch.Model.stopped(Duration.ofSeconds(3)).elapsed()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theElapsedArithmeticSaturatesInsteadOfWrapping() {
        assertThat(Stopwatch.distance(10L, 20L)).isEqualTo(10L);
        assertThat(Stopwatch.distance(20L, 10L))
                .as("a clock that has not advanced leaves nothing")
                .isZero();
        assertThat(Stopwatch.distance(Long.MIN_VALUE + 1L, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(Stopwatch.distance(Long.MAX_VALUE, Long.MIN_VALUE + 1L)).isZero();
        assertThat(new Stopwatch.Model(false, 0L, -5L, Stopwatch.UNSET_ORIGIN, 0L).accumulatedNanos())
                .as("a negative total is clamped where the state is built")
                .isZero();
        assertThat(new Stopwatch.Model(false, 0L, Long.MAX_VALUE - 5L, 0L, 20L).elapsedNanos())
                .as("a total that would overflow saturates")
                .isEqualTo(Long.MAX_VALUE);
        assertThat(Stopwatch.Model.stopped(Duration.ofDays(1_000_000)).elapsedNanos())
                .as("an unrepresentable total saturates")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aStaleReadingNeverMovesANewerRun() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model running = at(widget, running(widget, 0L), 10_000L);
        Stopwatch.Model stopped = apply(widget, running, new Stopwatch.Msg.Stop());

        assertThat(apply(widget, stopped, new Stopwatch.Msg.Reading(running.epoch(), 90_000L * MILLIS)))
                .as("a reading of the run that was stopped")
                .isSameAs(stopped);

        Stopwatch.Model restarted = at(widget, apply(widget, stopped, new Stopwatch.Msg.Start()), 100_000L);
        assertThat(apply(widget, restarted, new Stopwatch.Msg.Reading(running.epoch(), 160_000L * MILLIS)))
                .as("a reading of a run two epochs old")
                .isSameAs(restarted);
        assertThat(apply(widget, restarted, new Stopwatch.Msg.Reading(restarted.epoch() + 1L, 200_000L * MILLIS)))
                .as("a reading that belongs to no run at all")
                .isSameAs(restarted);
    }

    // ── R-02: exact elapsed time from runtime readings ───────────────────────

    @Test
    void aLateReadingLandsOnTheTimeThatHasReallyElapsed() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = running(widget, 0L);
        // One reading, fifty seconds after the origin: the watch never replays the ticks it missed,
        // because it measures the distance from the origin instead of adding up steps.
        Stopwatch.Model late = at(widget, model, 50_000L);
        assertThat(late.elapsed()).isEqualTo(Duration.ofSeconds(50));
        assertThat(late.nowNanos()).isEqualTo(50_000L * MILLIS);
    }

    @Test
    void aLateOrReorderedReadingCannotMoveTheDisplayBackward() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 30_000L);
        assertThat(model.elapsed()).isEqualTo(Duration.ofSeconds(30));
        Stopwatch.Model earlier = at(widget, model, 10_000L);
        assertThat(earlier.elapsed())
                .as("a reordered reading cannot make the watch run backwards")
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(earlier.nowNanos()).isEqualTo(model.nowNanos());
        assertThat(earlier).isSameAs(model);
    }

    @Test
    void pausedTimeDoesNotElapse() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 20_000L);
        Stopwatch.Model stopped = apply(widget, model, new Stopwatch.Msg.Stop());
        // Readings of a stopped watch are not measurements: only starting it again resumes it.
        assertThat(at(widget, stopped, 900_000L)).isSameAs(stopped);

        Stopwatch.Model restarted = at(widget, apply(widget, stopped, new Stopwatch.Msg.Start()), 1_000_000L);
        assertThat(at(widget, restarted, 1_010_000L).elapsed())
                .as("the ninety seconds the watch was stopped are not part of the total")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aRunningStopwatchDeclaresOneScopedInterval() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = running(widget, 0L);
        List<Subscription<Stopwatch.Msg>> leaves = Subscription.leaves(widget.subscriptions(model));
        assertThat(leaves).hasSize(1);
        Subscription.Interval<Stopwatch.Msg> interval = (Subscription.Interval<Stopwatch.Msg>) leaves.get(0);
        assertThat(interval.id().value()).isEqualTo("tick");
        assertThat(interval.period()).isEqualTo(Stopwatch.DEFAULT_TICK_PERIOD);
        assertThat(interval.onTick().apply(74_000L * MILLIS))
                .as("the interval maps the runtime's reading with the run's epoch")
                .isEqualTo(new Stopwatch.Msg.Reading(model.epoch(), 74_000L * MILLIS));

        // Three periods' worth of scheduling happened before one tick arrived; a single reading is
        // still exactly right, because nothing was accumulated in between.
        assertThat(apply(widget, model, interval.onTick().apply(55_000L * MILLIS))
                        .elapsed())
                .isEqualTo(Duration.ofSeconds(55));
    }

    @Test
    void aMountedStopwatchHoldsItsIntervalOnlyWhileItRuns() {
        Stopwatch widget = stopwatch();
        Update<Stopwatch.Model, Stopwatch.Msg, Never> start = send(widget, initial(widget), new Stopwatch.Msg.Start());
        Stopwatch.Model model = start.model();
        // The runtime's own clock, which is the timeline the interval reports on.
        model = apply(widget, model, new Stopwatch.Msg.Reading(model.epoch(), System.nanoTime()));

        ManualTimers timers = new ManualTimers();
        Mailbox mailbox = new Mailbox(16);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        SubscriptionManager<Stopwatch.Msg> manager = new SubscriptionManager<>(mailbox, timers, 8, diagnostics::add);
        try {
            manager.reconcile(widget.subscriptions(model));
            assertThat(timers.pending())
                    .as("a running stopwatch schedules one tick")
                    .isEqualTo(1);

            timers.advance(Stopwatch.DEFAULT_TICK_PERIOD.toNanos());
            List<Stopwatch.Msg> ticks = drainedFrom(mailbox, Stopwatch.Msg.class);
            assertThat(ticks).hasSize(1);
            assertThat(ticks.get(0)).isInstanceOf(Stopwatch.Msg.Reading.class);
            Stopwatch.Model ticked = apply(widget, model, ticks.get(0));
            assertThat(ticked.elapsedNanos())
                    .as("one period of real time passed")
                    .isPositive();
            assertThat(timers.pending())
                    .as("the interval keeps its one schedule")
                    .isEqualTo(1);

            // Stopping the watch folds the run in and releases the interval, so nothing is left.
            Stopwatch.Model stopped = apply(widget, ticked, new Stopwatch.Msg.Stop());
            manager.reconcile(widget.subscriptions(stopped));
            assertThat(timers.pending())
                    .as("a stopped stopwatch holds no timer")
                    .isZero();
            long measured = stopped.elapsedNanos();
            timers.advance(10_000L * MILLIS);
            assertThat(drainedFrom(mailbox, Stopwatch.Msg.class)).isEmpty();
            assertThat(stopped.elapsedNanos())
                    .as("no time accrues while stopped")
                    .isEqualTo(measured);
            assertThat(diagnostics).isEmpty();

            // Starting again schedules it again, and a reset of the running watch keeps one schedule.
            Stopwatch.Model restarted = apply(widget, stopped, new Stopwatch.Msg.Start());
            manager.reconcile(widget.subscriptions(restarted));
            assertThat(timers.pending()).isEqualTo(1);
            Stopwatch.Model cleared = apply(widget, restarted, new Stopwatch.Msg.Reset());
            manager.reconcile(widget.subscriptions(cleared));
            assertThat(timers.pending())
                    .as("a reset of a running watch keeps its repaint cadence")
                    .isEqualTo(1);
            assertThat(cleared.elapsedNanos()).isZero();
            assertThat(diagnostics).isEmpty();
        } finally {
            manager.closeAll();
            mailbox.close();
        }
    }

    /**
     * Every message of one widget the mailbox holds, without waiting for more.
     */
    private static <T> List<T> drainedFrom(Mailbox mailbox, Class<T> type) {
        List<T> messages = new ArrayList<>();
        while (true) {
            Mailbox.Envelope envelope;
            try {
                envelope = mailbox.poll(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return messages;
            }
            if (envelope == null) {
                return messages;
            }
            assertThat(envelope).isInstanceOf(Mailbox.AppMessage.class);
            messages.add(type.cast(((Mailbox.AppMessage) envelope).payload()));
        }
    }

    @Test
    void aMountedStopwatchScopesItsIntervalToItsOwnMount() {
        StopwatchDemo.Scene scene = demo();
        StopwatchDemo.Model model = startedLeft(scene, 1_000L);
        List<Subscription<StopwatchDemo.Msg>> leaves = Subscription.leaves(scene.subscriptions(model));
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).id().toString()).isEqualTo("/left:tick");
        assertThat(Subscription.leaves(scene.subscriptions(initial(scene))))
                .as("a scene with two stopped watches declares no source at all")
                .isEmpty();

        StopwatchDemo.Model both =
                deliver(scene, model, new StopwatchDemo.Msg.Right(new Stopwatch.Msg.Start()), 1_000L * MILLIS);
        assertThat(Subscription.leaves(scene.subscriptions(both)))
                .extracting(leaf -> leaf.id().toString())
                .containsExactly("/left:tick", "/right:tick");
    }

    // ── R-03: the exact duration and its rendering ───────────────────────────

    @Test
    void theModelExposesTheExactElapsedDuration() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 1L);
        assertThat(model.elapsedNanos()).isEqualTo(MILLIS);
        assertThat(model.elapsed()).isEqualTo(Duration.ofMillis(1L));
        assertThat(model.elapsed().toNanos()).isEqualTo(1_000_000L);
    }

    @Test
    void theDefaultFormatterWritesHoursMinutesSecondsAndMilliseconds() {
        assertThat(Stopwatch.clock(Duration.ZERO)).isEqualTo("00:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofMillis(1))).isEqualTo("00:00:00.001");
        assertThat(Stopwatch.clock(Duration.ofSeconds(1))).isEqualTo("00:00:01.000");
        assertThat(Stopwatch.clock(Duration.ofMillis(59_999))).isEqualTo("00:00:59.999");
        assertThat(Stopwatch.clock(Duration.ofSeconds(60))).isEqualTo("00:01:00.000");
        assertThat(Stopwatch.clock(Duration.ofSeconds(3_599))).isEqualTo("00:59:59.000");
        assertThat(Stopwatch.clock(Duration.ofSeconds(3_600)))
                .as("an hour fills the hours field")
                .isEqualTo("01:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofSeconds(36_000))).isEqualTo("10:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofSeconds(360_000)))
                .as("the hours field widens past two digits")
                .isEqualTo("100:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofNanos(-1L)))
                .as("a negative duration is no time at all")
                .isEqualTo("00:00:00.000");
        assertThat(Stopwatch.clock(null)).isEqualTo("00:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofNanos(Long.MAX_VALUE)))
                .as("the largest representable total still renders")
                .matches("\\d+:\\d{2}:\\d{2}\\.\\d{3}");
        assertThat(Stopwatch.clock(Duration.ofSeconds(Long.MAX_VALUE)))
                .as("a total that cannot be measured in milliseconds saturates")
                .matches("\\d+:\\d{2}:\\d{2}\\.\\d{3}");
    }

    @Test
    void theDefaultFormatterTruncatesRatherThanRounds() {
        assertThat(Stopwatch.clock(Duration.ofNanos(1L)))
                .as("a watch never paints a millisecond that has not elapsed")
                .isEqualTo("00:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofNanos(999_999L)))
                .as("a fraction of a millisecond is truncated away")
                .isEqualTo("00:00:00.000");
        assertThat(Stopwatch.clock(Duration.ofNanos(999_999_999L))).isEqualTo("00:00:00.999");
        assertThat(Stopwatch.clock(Duration.ofNanos(1_000_001L))).isEqualTo("00:00:00.001");
        assertThat(Stopwatch.clock(Duration.ofNanos(1_000_001_000L))).isEqualTo("00:00:01.000");
        assertThat(Stopwatch.clock(Duration.ofMillis(59_999).plusNanos(999_999L)))
                .as("truncation does not carry into the next second")
                .isEqualTo("00:00:59.999");
    }

    @Test
    void aCustomFormatterReplacesThePolicyAndRenderingStaysPure() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 61_500L);
        Stopwatch.Config hundredths = CONFIG.withFormatter(elapsed ->
                String.format(java.util.Locale.ROOT, "%d.%02d s", elapsed.toSeconds(), elapsed.toMillisPart() / 10L));
        assertThat(Stopwatch.render(model, hundredths).plainText()).isEqualTo("61.50 s");
        assertThat(Stopwatch.render(model, CONFIG).plainText())
                .as("the definition, not the state, owns the formatting")
                .isEqualTo("00:01:01.500");
        assertThat(Stopwatch.render(model, hundredths)).isEqualTo(Stopwatch.render(model, hundredths));
        assertThat(Stopwatch.render(model, CONFIG.withFormatter(elapsed -> null)))
                .as("a declining formatter paints nothing")
                .isEqualTo(StyledText.empty());
        assertThat(model.elapsed()).isEqualTo(Duration.ofMillis(61_500L));
    }

    @Test
    void thePaintedTimeIsStyledCutByColumnsAndNeverSplit() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 1_234L);
        StyledText styled = Stopwatch.render(model, CONFIG.withStyle(Style.NONE.withBold()));
        assertThat(styled.plainText()).isEqualTo("00:00:01.234");
        assertThat(styled.spans()).allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE.withBold()));

        assertThat(Stopwatch.render(model, CONFIG, 0)).isEqualTo(StyledText.empty());
        assertThat(Stopwatch.render(model, CONFIG, -1)).isEqualTo(StyledText.empty());
        assertThat(Stopwatch.render(model, CONFIG, 5).plainText()).isEqualTo("00:00");
        assertThat(Stopwatch.render(model, CONFIG, 12).plainText()).isEqualTo("00:00:01.234");

        Stopwatch.Config wide = CONFIG.withFormatter(elapsed -> "\uD83C\uDF0D\uD83C\uDF0D");
        StyledText cut = Stopwatch.render(model, wide, 3);
        assertThat(cut.plainText()).as("a wide glyph is dropped whole").isEqualTo("\uD83C\uDF0D");
        assertThat(cut.columns()).isEqualTo(2);
    }

    @Test
    void aMountedStopwatchPaintsItsOwnTime() {
        Stopwatch widget = stopwatch();
        Stopwatch.Model model = at(widget, running(widget, 0L), 2_000L);
        Element<Stopwatch.Msg> view = widget.view(model, new ViewContext(new Viewport(24, 4)));
        assertThat(painted(view, 1, 80).get(0)).isEqualTo("00:00:02.000");
        assertThat(painted(widget.view(model, new ViewContext(new Viewport(24, 4))), 1, 4)
                        .get(0))
                .isEqualTo("00:0");
    }

    // ── R-04: the demo ──────────────────────────────────────────────────────

    @Test
    void theDemoMountsTwoStoppedWatchesAndShowsThem() {
        StopwatchDemo.Scene scene = demo();
        StopwatchDemo.Model model = initial(scene);
        List<String> rows = painted(scene, model);
        assertThat(rows.get(0)).isEqualTo("left state=stopped epoch=0 nanos=0");
        assertThat(rows.get(1)).isEqualTo("right state=stopped epoch=0 nanos=0");
        assertThat(rows.get(2)).isEqualTo("tick=50ms last=none");
        assertThat(rows.get(3)).isEmpty();
        assertThat(rows.get(4)).as("the left mount's own view").isEqualTo("left   00:00:00.000");
        assertThat(rows.get(5)).as("the right mount's own view").isEqualTo("right  00:00:00.000");
        assertThat(rows.get(6)).isEmpty();
        assertThat(rows.get(7))
                .isEqualTo("s start left \u2022 r reset left \u2022 k start right \u2022 l reset right"
                        + " \u2022 esc quit");
        assertThat(scene.children(model).nodes()).hasSize(2);
        assertThat(Subscription.leaves(scene.subscriptions(model))).isEmpty();
    }

    @Test
    void theKeyThatStartsAWatchStopsItAndTheHelpSaysWhichOne() {
        StopwatchDemo.Scene scene = demo();
        StopwatchDemo.Model model = startedLeft(scene, 1_000L);

        assertThat(row(scene, model, 0)).isEqualTo("left state=running epoch=1 nanos=0");
        assertThat(row(scene, model, 2)).isEqualTo("tick=50ms last=start-left");
        assertThat(row(scene, model, 1))
                .as("starting the left watch leaves the right one alone")
                .isEqualTo("right state=stopped epoch=0 nanos=0");
        // The one key still offers one word, and it now says the other one.
        assertThat(row(scene, model, 7))
                .isEqualTo("s stop left \u2022 r reset left \u2022 k start right \u2022 l reset right"
                        + " \u2022 esc quit");

        // The run began at the instant the start gesture was answered, so a reading at three
        // seconds measures two seconds.
        StopwatchDemo.Model ticked = deliver(scene, model, tick(model, true, 3_000L), 3_000L * MILLIS);
        assertThat(row(scene, ticked, 0)).isEqualTo("left state=running epoch=1 nanos=2000000000");
        assertThat(row(scene, ticked, 1)).isEqualTo("right state=stopped epoch=0 nanos=0");
        assertThat(row(scene, ticked, 4)).isEqualTo("left   00:00:02.000");
        assertThat(row(scene, ticked, 5)).as("the right watch never moved").isEqualTo("right  00:00:00.000");
        assertThat(row(scene, ticked, 2))
                .as("a repaint is not a gesture the scene logs")
                .isEqualTo("tick=50ms last=start-left");

        // The same key stops the watch, and the total it measured stays on the display.
        StopwatchDemo.Model stopped =
                deliver(scene, ticked, new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Stop()), 4_000L * MILLIS);
        assertThat(row(scene, stopped, 0)).isEqualTo("left state=stopped epoch=2 nanos=2000000000");
        assertThat(row(scene, stopped, 2)).isEqualTo("tick=50ms last=stop-left");
        assertThat(row(scene, stopped, 7)).startsWith("s start left").contains("l reset right");
        assertThat(deliver(scene, stopped, tick(stopped, true, 9_000L), 9_000L * MILLIS))
                .as("a stopped watch ignores the readings of the run it folded in")
                .isSameAs(stopped);
    }

    @Test
    void theTwoWatchesRunIndependently() {
        StopwatchDemo.Scene scene = demo();
        StopwatchDemo.Model model = startedLeft(scene, 0L);
        model = deliver(scene, model, tick(model, true, 2_000L), 2_000L * MILLIS);
        assertThat(row(scene, model, 0)).isEqualTo("left state=running epoch=1 nanos=2000000000");

        // The right watch starts on its own key, at its own instant, and keeps its own total.
        model = deliver(scene, model, new StopwatchDemo.Msg.Right(new Stopwatch.Msg.Start()), 5_000L * MILLIS);
        assertThat(row(scene, model, 1)).isEqualTo("right state=running epoch=1 nanos=0");
        assertThat(row(scene, model, 2)).isEqualTo("tick=50ms last=start-right");
        assertThat(row(scene, model, 0))
                .as("starting the right watch does not touch the left one")
                .isEqualTo("left state=running epoch=1 nanos=2000000000");

        model = deliver(scene, model, tick(model, false, 7_000L), 7_000L * MILLIS);
        assertThat(row(scene, model, 1)).isEqualTo("right state=running epoch=1 nanos=2000000000");
        assertThat(row(scene, model, 0)).isEqualTo("left state=running epoch=1 nanos=2000000000");
        assertThat(row(scene, model, 4)).isEqualTo("left   00:00:02.000");
        assertThat(row(scene, model, 5)).isEqualTo("right  00:00:02.000");

        // Stopping one leaves the other running, and only its own interval is left.
        model = deliver(scene, model, new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Stop()), 8_000L * MILLIS);
        assertThat(row(scene, model, 0)).isEqualTo("left state=stopped epoch=2 nanos=2000000000");
        assertThat(row(scene, model, 1)).isEqualTo("right state=running epoch=1 nanos=2000000000");
        assertThat(Subscription.leaves(scene.subscriptions(model)))
                .extracting(leaf -> leaf.id().toString())
                .containsExactly("/right:tick");
    }

    @Test
    void theResetKeyZeroesTheTotalAndKeepsTheWatchRunning() {
        StopwatchDemo.Scene scene = demo();
        StopwatchDemo.Model model = startedLeft(scene, 0L);
        model = deliver(scene, model, tick(model, true, 3_000L), 3_000L * MILLIS);
        assertThat(row(scene, model, 4)).isEqualTo("left   00:00:03.000");

        StopwatchDemo.Model reset =
                deliver(scene, model, new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Reset()), 4_000L * MILLIS);
        assertThat(row(scene, reset, 0)).isEqualTo("left state=running epoch=2 nanos=0");
        assertThat(row(scene, reset, 2)).isEqualTo("tick=50ms last=reset-left");
        assertThat(row(scene, reset, 4)).isEqualTo("left   00:00:00.000");
        assertThat(row(scene, reset, 7))
                .as("the watch is still running, so the key still says stop")
                .startsWith("s stop left");

        // The new run is measured from the reset, so the total grows from zero again.
        StopwatchDemo.Model again = deliver(scene, reset, tick(reset, true, 6_000L), 6_000L * MILLIS);
        assertThat(row(scene, again, 0)).isEqualTo("left state=running epoch=2 nanos=2000000000");
        assertThat(row(scene, again, 4)).isEqualTo("left   00:00:02.000");
    }

    @Test
    void everySceneGestureIsReachableFromTheRoutedKeyMap() {
        StopwatchDemo.Model stopped = initial(demo());
        assertThat(route(stopped, "s")).contains(new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Start()));
        assertThat(route(stopped, "r")).contains(new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Reset()));
        assertThat(route(stopped, "k")).contains(new StopwatchDemo.Msg.Right(new Stopwatch.Msg.Start()));
        assertThat(route(stopped, "l")).contains(new StopwatchDemo.Msg.Right(new Stopwatch.Msg.Reset()));
        assertThat(route(stopped, "escape")).contains(new StopwatchDemo.Msg.Quit());
        assertThat(route(stopped, "q")).as("an unbound key reaches nothing").isEmpty();

        StopwatchDemo.Model running = startedLeft(demo(), 0L);
        assertThat(route(running, "s"))
                .as("the same key stops a running watch")
                .contains(new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Stop()));
        assertThat(route(running, "k"))
                .as("the right watch is untouched by the left one's state")
                .contains(new StopwatchDemo.Msg.Right(new Stopwatch.Msg.Start()));

        List<String> help =
                painted(Help.element(StopwatchDemo.keys(running), Help.Config.defaults(), true, 200), 8, 200).stream()
                        .filter(line -> !line.isBlank())
                        .toList();
        assertThat(help).hasSize(3);
        assertThat(help.get(0)).contains("stop left").contains("reset left").doesNotContain("start left");
        assertThat(help.get(1)).contains("start right").contains("reset right").doesNotContain("stop right");
        assertThat(help.get(2)).contains("quit");
    }

    @Test
    void escapeQuitsTheDemo() {
        Update<StopwatchDemo.Model, StopwatchDemo.Msg, Never> update =
                demo().update(initial(demo()), new StopwatchDemo.Msg.Quit());
        assertThat(update.command()).isInstanceOf(Command.Quit.class);
        assertThat(update.outputs()).isEmpty();
    }

    @Test
    void theDemoRunsUnderTheDeterministicDriver() {
        ComponentDriver<StopwatchDemo.Model, StopwatchDemo.Msg, Never> driver =
                ComponentDriver.initialize(demo(), new InitContext(new Viewport(24, 80), null));
        assertThat(driver.screen(CONTEXT).line(0)).isEqualTo("left state=stopped epoch=0 nanos=0");
        assertThat(driver.screen(CONTEXT).line(4)).isEqualTo("left   00:00:00.000");

        driver.send(new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Start()));
        StopwatchDemo.Model armed = driver.model();
        assertThat(armed.left().running()).isTrue();
        assertThat(driver.subscriptions()).isInstanceOf(Subscription.Interval.class);
        // The driver leaves commands inert, so the first reading it feeds names the origin and the
        // second one measures the distance from it.
        driver.send(tick(armed, true, 1_000L));
        assertThat(driver.model().left().armed()).isFalse();
        driver.send(tick(armed, true, 2_500L));
        assertThat(driver.screen(CONTEXT).line(4)).isEqualTo("left   00:00:01.500");
        assertThat(driver.screen(CONTEXT).line(5)).isEqualTo("right  00:00:00.000");

        driver.send(new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Stop()));
        assertThat(driver.model().left().running()).isFalse();
        assertThat(driver.subscriptions()).isInstanceOf(Subscription.None.class);
        assertThat(driver.children().nodes()).hasSize(2);
    }

    @Test
    void aNarrowSceneClipsTheHelpInsteadOfWrapping() {
        StopwatchDemo.Scene scene = demo();
        StopwatchDemo.Model model = initial(scene);
        List<String> lines = painted(scene.view(model, new ViewContext(new Viewport(24, 40))), 24, 40);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(40));
        assertThat(lines.get(4)).isEqualTo("left   00:00:00.000");
        assertThat(lines.get(5)).isEqualTo("right  00:00:00.000");
        assertThat(lines.get(7))
                .as("the help is cut, not wrapped")
                .startsWith("s start left")
                .endsWith("\u2026");
        assertThat(lines.get(0)).as("the status row stays its own row").startsWith("left state=stopped");
    }

    @Test
    void theSceneHoldsEachIntervalOnlyWhileItsWatchRuns() {
        StopwatchDemo.Scene scene = demo();
        ManualTimers timers = new ManualTimers();
        Mailbox mailbox = new Mailbox(16);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        SubscriptionManager<StopwatchDemo.Msg> manager =
                new SubscriptionManager<>(mailbox, timers, 8, diagnostics::add);
        try {
            StopwatchDemo.Model model = initial(scene);
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending())
                    .as("two stopped watches schedule nothing")
                    .isZero();

            model = deliver(scene, model, new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Start()), System.nanoTime());
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending())
                    .as("the left watch holds its own interval")
                    .isEqualTo(1);

            timers.advance(Stopwatch.DEFAULT_TICK_PERIOD.toNanos());
            List<StopwatchDemo.Msg> ticks = drained(mailbox);
            assertThat(ticks).hasSize(1);
            assertThat(ticks.get(0)).isInstanceOf(StopwatchDemo.Msg.Left.class);
            StopwatchDemo.Model ticked = deliver(scene, model, ticks.get(0), System.nanoTime());
            assertThat(ticked.left().elapsedNanos()).as("real time passed").isPositive();
            assertThat(ticked.right().elapsedNanos())
                    .as("the right watch measured nothing")
                    .isZero();

            model = deliver(scene, ticked, new StopwatchDemo.Msg.Right(new Stopwatch.Msg.Start()), System.nanoTime());
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).as("both watches now hold an interval").isEqualTo(2);

            model = deliver(scene, model, new StopwatchDemo.Msg.Left(new Stopwatch.Msg.Stop()), System.nanoTime());
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending())
                    .as("stopping one watch releases one interval")
                    .isEqualTo(1);
            timers.advance(10_000L * MILLIS);
            assertThat(diagnostics).isEmpty();
        } finally {
            manager.closeAll();
            mailbox.close();
        }
    }
}
