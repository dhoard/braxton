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

import examples.parity.CountdownDemo;
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
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.Help;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 09 acceptance: the countdown widget, its expiry output and the countdown demo.
 *
 * <p>The timing cases drive {@code update} with explicit monotonic readings, so the asserted timing
 * is exact and involves no sleeps; a "late reading" is a number, not a wall clock. The lifecycle
 * cases reconcile the demo's own subscription declarations against {@link ManualTimers}, so "no
 * timer while paused" is proven by the interval that stops being scheduled rather than by a value
 * that happened to hold still. The terminal behavior of the packaged demo is verified separately
 * by {@code python3 tools/terminal/verify_terminal.py --parity-phase 09}.</p>
 */
@Timeout(20)
class ParityCountdownTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final long MILLIS = 1_000_000L;

    /**
     * The duration the widget tests are configured with: a minute, so mm:ss has two digits.
     */
    private static final Duration CONFIGURED = Duration.ofSeconds(60);

    private static final Countdown.Config CONFIG = Countdown.Config.of(CONFIGURED);

    // ── the widget's own helpers ─────────────────────────────────────────────

    private static Countdown countdown() {
        return new Countdown(CONFIG);
    }

    private static Countdown countdown(Countdown.Config config) {
        return new Countdown(config);
    }

    private static Countdown.Model initial(Countdown widget) {
        return widget.init(new InitContext(null, null)).model();
    }

    private static Update<Countdown.Model, Countdown.Msg, Countdown.Output> send(
            Countdown widget, Countdown.Model model, Countdown.Msg message) {
        return widget.update(model, message);
    }

    private static Countdown.Model apply(Countdown widget, Countdown.Model model, Countdown.Msg message) {
        return send(widget, model, message).model();
    }

    /**
     * The reading a timestamp request produces: how a start or resume is told what time it is.
     */
    private static Countdown.Msg reading(Command<Countdown.Msg> command, long atMillis) {
        return readingAt(command, atMillis * MILLIS);
    }

    /**
     * The reading a timestamp request produces at an absolute monotonic time in nanoseconds.
     */
    private static Countdown.Msg readingAt(Command<Countdown.Msg> command, long nowNanos) {
        assertThat(command)
                .as("a run waiting for its deadline asks the runtime for one reading")
                .isInstanceOf(Command.MonotonicTime.class);
        return ((Command.MonotonicTime<Countdown.Msg>) command).mapper().apply(nowNanos);
    }

    /**
     * One reading of the model's current run, at an absolute monotonic time in milliseconds.
     */
    private static Countdown.Model at(Countdown widget, Countdown.Model model, long atMillis) {
        return apply(widget, model, new Countdown.Msg.Reading(model.epoch(), atMillis * MILLIS));
    }

    /**
     * A running countdown whose deadline the runtime named at {@code atMillis}.
     */
    private static Countdown.Model running(Countdown widget, long atMillis) {
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> start =
                send(widget, initial(widget), new Countdown.Msg.Start());
        return apply(widget, start.model(), reading(start.command(), atMillis));
    }

    /**
     * A running countdown that a reading at {@code atMillis} expires.
     */
    private static Countdown.Model expiredRun(Countdown widget, long atMillis) {
        Countdown.Model model = running(widget, 0L);
        return apply(widget, model, new Countdown.Msg.Reading(model.epoch(), atMillis * MILLIS));
    }

    // ── the demo's own helpers ───────────────────────────────────────────────

    /**
     * The demo the packaged launch runs, driven as its own component.
     */
    private static CountdownDemo.Scene demo() {
        return new CountdownDemo.Scene(CountdownDemo.TEST_DURATION);
    }

    private static CountdownDemo.Model initial(CountdownDemo.Scene scene) {
        return scene.init(new InitContext(new Viewport(24, 80), null)).model();
    }

    /**
     * The demo's rows, so a test can assert the scene without decoding styling.
     */
    private static List<String> painted(CountdownDemo.Scene scene, CountdownDemo.Model model) {
        return painted(scene.view(model, CONTEXT), 24, 80);
    }

    private static List<String> painted(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols).lines();
    }

    private static String row(CountdownDemo.Model model, int index) {
        return painted(demo(), model).get(index).strip();
    }

    private static String row(CountdownDemo.Scene scene, CountdownDemo.Model model, int index) {
        return painted(scene, model).get(index).strip();
    }

    /**
     * What one key does to the demo's screen, as the scene's own message.
     */
    private static Optional<CountdownDemo.Msg> route(CountdownDemo.Model model, String keyName) {
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
     * <p>The countdown's timestamp request crosses the mount as a scoped command, so applying its
     * mapper here is the same path the runtime takes: the deadline comes from the command, not from
     * the test's copy of the state.</p>
     */
    private static CountdownDemo.Model deliver(
            CountdownDemo.Scene scene, CountdownDemo.Model model, CountdownDemo.Msg message, long nowNanos) {
        ArrayDeque<CountdownDemo.Msg> pending = new ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            Update<CountdownDemo.Model, CountdownDemo.Msg, Never> update = scene.update(model, pending.removeFirst());
            model = update.model();
            inspect(update.command(), pending, nowNanos);
        }
        return model;
    }

    private static void inspect(
            Command<CountdownDemo.Msg> command, ArrayDeque<CountdownDemo.Msg> pending, long nowNanos) {
        switch (command) {
            case Command.None<CountdownDemo.Msg> none -> {}
            case Command.Batch<CountdownDemo.Msg> batch ->
                batch.commands().forEach(item -> inspect(item, pending, nowNanos));
            case Command.Scoped<CountdownDemo.Msg> scoped -> inspect(scoped.command(), pending, nowNanos);
            case Command.MonotonicTime<CountdownDemo.Msg> time ->
                pending.add(time.mapper().apply(nowNanos));
            case Command.Dispatch<CountdownDemo.Msg> dispatch -> pending.add(dispatch.message());
            default -> {}
        }
    }

    /**
     * One reading of the demo's countdown, as the mounted component would receive it.
     */
    private static CountdownDemo.Msg tick(CountdownDemo.Model model, long atMillis) {
        return new CountdownDemo.Msg.Count(
                new Countdown.Msg.Reading(model.countdown().epoch(), atMillis * MILLIS));
    }

    /**
     * The demo started by one gesture, with the runtime's timestamp fact delivered after it.
     */
    private static CountdownDemo.Model started(CountdownDemo.Scene scene, long atMillis) {
        return deliver(scene, initial(scene), new CountdownDemo.Msg.Toggle(), atMillis * MILLIS);
    }

    /**
     * Every message the mailbox holds, without waiting for more.
     */
    private static List<Countdown.Msg> drained(Mailbox mailbox) {
        List<Countdown.Msg> messages = new ArrayList<>();
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
            messages.add((Countdown.Msg) ((Mailbox.AppMessage) envelope).payload());
        }
    }

    /**
     * Every demo message the mailbox holds, without waiting for more.
     */
    private static List<CountdownDemo.Msg> drainedDemo(Mailbox mailbox) {
        List<CountdownDemo.Msg> messages = new ArrayList<>();
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
            messages.add((CountdownDemo.Msg) ((Mailbox.AppMessage) envelope).payload());
        }
    }

    // ── R-01: defined state, defined gestures ────────────────────────────────

    @Test
    void aCountdownMountsIdleAtItsConfiguredDuration() {
        Countdown widget = countdown();
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> init = widget.init(new InitContext(null, null));
        Countdown.Model model = init.model();
        assertThat(model.running()).isFalse();
        assertThat(model.armed()).isFalse();
        assertThat(model.expired()).isFalse();
        assertThat(model.epoch()).isZero();
        assertThat(model.remaining()).isEqualTo(CONFIGURED);
        assertThat(init.command()).isInstanceOf(Command.None.class);
        assertThat(init.outputs()).as("mounting a timer starts nothing").isEmpty();
        assertThat(Subscription.leaves(widget.subscriptions(model)))
                .as("an idle countdown schedules nothing")
                .isEmpty();
        assertThat(Countdown.render(model, CONFIG).plainText()).isEqualTo("01:00");
    }

    @Test
    void startArmsARunAndAsksTheRuntimeForItsDeadline() {
        Countdown widget = countdown();
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> start =
                send(widget, initial(widget), new Countdown.Msg.Start());
        Countdown.Model model = start.model();
        assertThat(model.running()).isTrue();
        assertThat(model.armed()).as("a run with no deadline yet is armed").isTrue();
        assertThat(model.epoch()).isEqualTo(1L);
        assertThat(model.remaining())
                .as("an armed run reports the duration it was armed with")
                .isEqualTo(CONFIGURED);
        assertThat(model.deadlineNanos()).isEqualTo(Countdown.UNSET_DEADLINE);
        assertThat(start.outputs()).isEmpty();
        assertThat(start.command()).isInstanceOf(Command.MonotonicTime.class);
        assertThat(Subscription.leaves(widget.subscriptions(model))).hasSize(1);
        assertThat(send(widget, model, new Countdown.Msg.Start()).command())
                .as("a start while the run is still waiting for its deadline asks for nothing more")
                .isInstanceOf(Command.None.class);
    }

    @Test
    void theTimestampFactNamesTheDeadlineAtTheInstantTheRunStarted() {
        Countdown widget = countdown();
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> start =
                send(widget, initial(widget), new Countdown.Msg.Start());
        Countdown.Msg fact = reading(start.command(), 1_000L);
        assertThat(fact).isEqualTo(new Countdown.Msg.Reading(start.model().epoch(), 1_000L * MILLIS));
        Countdown.Model model = apply(widget, start.model(), fact);
        assertThat(model.armed()).isFalse();
        assertThat(model.deadlineNanos()).isEqualTo(61_000L * MILLIS);
        assertThat(model.remaining())
                .as("the deadline is the duration after the reading")
                .isEqualTo(CONFIGURED);
        assertThat(model.nowNanos()).isEqualTo(1_000L * MILLIS);
    }

    @Test
    void aControlMessageThatChangesNothingKeepsTheIncomingInstance() {
        Countdown widget = countdown();
        Countdown.Model running = running(widget, 0L);
        Countdown.Model paused = apply(widget, running, new Countdown.Msg.Stop());
        Countdown.Model expired = expiredRun(widget, 60_000L);

        assertThat(send(widget, running, new Countdown.Msg.Start()).model())
                .as("starting a running countdown")
                .isSameAs(running);
        assertThat(send(widget, running, new Countdown.Msg.Resume()).model())
                .as("resuming a running countdown")
                .isSameAs(running);
        assertThat(send(widget, paused, new Countdown.Msg.Stop()).model())
                .as("stopping a paused countdown")
                .isSameAs(paused);
        assertThat(send(widget, expired, new Countdown.Msg.Resume()).model())
                .as("resuming a countdown with nothing left")
                .isSameAs(expired);
        assertThat(send(widget, running, new Countdown.Msg.Start()).command())
                .as("a gesture that changed nothing asks for nothing")
                .isInstanceOf(Command.None.class);
        assertThat(send(widget, running, new Countdown.Msg.Start()).outputs()).isEmpty();
    }

    @Test
    void startBeginsTheConfiguredDurationAgainAndResumeContinues() {
        Countdown widget = countdown();
        Countdown.Model model = at(widget, running(widget, 0L), 20_000L);
        assertThat(model.remaining()).isEqualTo(Duration.ofSeconds(40));

        Countdown.Model paused = apply(widget, model, new Countdown.Msg.Stop());
        assertThat(paused.running()).isFalse();
        assertThat(paused.remaining()).as("a stop freezes what was left").isEqualTo(Duration.ofSeconds(40));
        assertThat(paused.deadlineNanos()).isEqualTo(Countdown.UNSET_DEADLINE);

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> resume =
                send(widget, paused, new Countdown.Msg.Resume());
        assertThat(resume.model().running()).isTrue();
        assertThat(resume.model().remaining())
                .as("resuming keeps the remainder")
                .isEqualTo(Duration.ofSeconds(40));
        assertThat(resume.command()).isInstanceOf(Command.MonotonicTime.class);
        Countdown.Model resumed = apply(widget, resume.model(), reading(resume.command(), 100_000L));
        assertThat(resumed.deadlineNanos())
                .as("the new deadline is the remainder after the new reading")
                .isEqualTo(140_000L * MILLIS);
        assertThat(resumed.remaining()).isEqualTo(Duration.ofSeconds(40));

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> restart =
                send(widget, paused, new Countdown.Msg.Start());
        assertThat(restart.model().remaining())
                .as("a start discards the remainder")
                .isEqualTo(CONFIGURED);
        assertThat(restart.model().epoch()).isEqualTo(paused.epoch() + 1L);
    }

    @Test
    void aToggleStopsResumesAndStartsAgain() {
        Countdown widget = countdown();
        Countdown.Model running = running(widget, 0L);

        Countdown.Model paused = apply(widget, running, new Countdown.Msg.Toggle());
        assertThat(paused.running()).isFalse();
        assertThat(paused.epoch()).isEqualTo(running.epoch() + 1L);

        Countdown.Model resumed = apply(widget, paused, new Countdown.Msg.Toggle());
        assertThat(resumed.running()).isTrue();
        assertThat(resumed.armed()).isTrue();
        assertThat(resumed.remaining()).isEqualTo(paused.remaining());

        Countdown.Model expired = expiredRun(widget, 60_000L);
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> restart =
                send(widget, expired, new Countdown.Msg.Toggle());
        assertThat(restart.model().running()).isTrue();
        assertThat(restart.model().remaining())
                .as("a finished countdown starts over")
                .isEqualTo(CONFIGURED);
        assertThat(restart.command()).isInstanceOf(Command.MonotonicTime.class);
    }

    @Test
    void aResetPutsTheConfiguredDurationBackUnderANewEpoch() {
        Countdown widget = countdown();
        Countdown.Model model = at(widget, running(widget, 0L), 30_000L);
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> reset = send(widget, model, new Countdown.Msg.Reset());
        assertThat(reset.model().running()).isFalse();
        assertThat(reset.model().remaining()).isEqualTo(CONFIGURED);
        assertThat(reset.model().deadlineNanos()).isEqualTo(Countdown.UNSET_DEADLINE);
        assertThat(reset.model().epoch()).isEqualTo(model.epoch() + 1L);
        assertThat(reset.command()).isInstanceOf(Command.None.class);
        assertThat(reset.outputs()).isEmpty();

        // The new epoch is what makes a reset cancel the readings the replaced run still had queued.
        assertThat(apply(widget, reset.model(), new Countdown.Msg.Reading(model.epoch(), 900_000L * MILLIS)))
                .isSameAs(reset.model());
    }

    @Test
    void aZeroLengthCountdownExpiresOnceOnEveryStart() {
        Countdown widget = countdown(Countdown.Config.of(Duration.ZERO));
        Countdown.Model idle = initial(widget);
        assertThat(idle.expired()).isTrue();
        assertThat(idle.epoch()).isZero();

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> first = send(widget, idle, new Countdown.Msg.Start());
        assertThat(first.outputs()).containsExactly(new Countdown.Output.Timeout());
        assertThat(first.model().running()).isFalse();
        assertThat(first.model().expired()).isTrue();
        assertThat(first.model().epoch()).isEqualTo(1L);
        assertThat(first.command()).as("there is nothing to wait for").isInstanceOf(Command.None.class);
        assertThat(Subscription.leaves(widget.subscriptions(first.model()))).isEmpty();

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> second =
                send(widget, first.model(), new Countdown.Msg.Start());
        assertThat(second.outputs()).as("each start is its own run").containsExactly(new Countdown.Output.Timeout());
        assertThat(second.model().epoch()).isEqualTo(2L);

        assertThat(send(widget, second.model(), new Countdown.Msg.Reading(2L, 5_000L * MILLIS))
                        .outputs())
                .as("nothing expires a run that is not running")
                .isEmpty();
        assertThat(send(widget, first.model(), new Countdown.Msg.Toggle()).outputs())
                .as("the toggle gesture starts a new run, which reports its own timeout")
                .containsExactly(new Countdown.Output.Timeout());
    }

    @Test
    void aDefinitionRejectsWhatACountdownCannotHonour() {
        assertThatThrownBy(() -> Countdown.Config.of(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> Countdown.Model.stopped(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> Countdown.Config.of(null))
                .as("a countdown cannot default its duration")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Countdown.Config.of(Duration.ofDays(1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
        assertThatThrownBy(() -> Countdown.Config.of(CONFIGURED).withTickPeriod(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> Countdown.Config.of(CONFIGURED).withTickPeriod(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> Countdown.Config.of(CONFIGURED).withTickPeriod(Duration.ofDays(1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
        assertThatThrownBy(() -> new Countdown(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aDefinitionSubstitutesItsDefaults() {
        Countdown.Config defaults = new Countdown.Config(CONFIGURED, null, null, null);
        assertThat(defaults.tickPeriod()).isEqualTo(Countdown.DEFAULT_TICK_PERIOD);
        assertThat(defaults.formatter()).isSameAs(Countdown.CLOCK);
        assertThat(defaults.style()).isEqualTo(Style.NONE);
        assertThat(defaults.durationNanos()).isEqualTo(60_000L * MILLIS);
        assertThat(Countdown.Config.of(CONFIGURED)
                        .withDuration(Duration.ofSeconds(90))
                        .duration())
                .isEqualTo(Duration.ofSeconds(90));
        assertThat(defaults.withStyle(Style.NONE.withBold()).style()).isEqualTo(Style.NONE.withBold());
        assertThat(countdown(defaults).config()).isSameAs(defaults);
    }

    @Test
    void theDeadlineAndRemainderArithmeticSaturatesInsteadOfWrapping() {
        assertThat(Countdown.deadlineFrom(Long.MAX_VALUE - 1L, 10L)).isEqualTo(Long.MAX_VALUE);
        assertThat(Countdown.deadlineFrom(5L, -10L))
                .as("a negative remainder is no time at all")
                .isEqualTo(5L);
        assertThat(Countdown.remainingFrom(Countdown.UNSET_DEADLINE, 5L)).isZero();
        assertThat(Countdown.remainingFrom(10L, 20L))
                .as("a passed deadline leaves nothing")
                .isZero();
        assertThat(Countdown.remainingFrom(0L, Long.MIN_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(Countdown.remainingFrom(Long.MIN_VALUE + 1L, Long.MAX_VALUE)).isZero();
        assertThat(new Countdown.Model(false, 0L, -5L, Countdown.UNSET_DEADLINE, 0L).remainingNanos())
                .as("a negative remainder is clamped where the state is built")
                .isZero();
        assertThat(Countdown.Model.stopped(Duration.ofDays(1_000_000)).remainingNanos())
                .as("an unrepresentable remainder saturates")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aStaleReadingNeverMovesANewerRun() {
        Countdown widget = countdown();
        Countdown.Model running = running(widget, 0L);
        Countdown.Model paused = apply(widget, running, new Countdown.Msg.Stop());

        assertThat(apply(widget, paused, new Countdown.Msg.Reading(running.epoch(), 90_000L * MILLIS)))
                .as("a reading of the run that was stopped")
                .isSameAs(paused);

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> resume =
                send(widget, paused, new Countdown.Msg.Resume());
        Countdown.Model resumed = apply(widget, resume.model(), reading(resume.command(), 200_000L));
        assertThat(apply(widget, resumed, new Countdown.Msg.Reading(running.epoch(), 260_000L * MILLIS)))
                .as("a reading of a run two epochs old")
                .isSameAs(resumed);
        assertThat(apply(widget, resumed, new Countdown.Msg.Reading(resumed.epoch() + 1L, 300_000L * MILLIS)))
                .as("a reading that belongs to no run at all")
                .isSameAs(resumed);
    }

    // ── R-02: exact expiry from runtime time ─────────────────────────────────

    @Test
    void aLateReadingLandsOnTheTimeThatIsReallyLeft() {
        Countdown widget = countdown();
        Countdown.Model model = running(widget, 0L);
        // One reading, ten seconds before the deadline: the countdown never replays the ticks it
        // missed, because it measures the distance to the deadline instead of subtracting steps.
        Countdown.Model late = at(widget, model, 50_000L);
        assertThat(late.remaining()).isEqualTo(Duration.ofSeconds(10));
        assertThat(late.nowNanos()).isEqualTo(50_000L * MILLIS);
    }

    @Test
    void aReadingThatReachesTheDeadlineReportsExactlyOneTimeout() {
        Countdown widget = countdown();
        Countdown.Model model = running(widget, 0L);

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> justBefore =
                send(widget, model, new Countdown.Msg.Reading(model.epoch(), 60_000L * MILLIS - 1L));
        assertThat(justBefore.outputs()).isEmpty();
        assertThat(justBefore.model().remaining()).isEqualTo(Duration.ofNanos(1L));

        Update<Countdown.Model, Countdown.Msg, Countdown.Output> hit =
                send(widget, model, new Countdown.Msg.Reading(model.epoch(), 60_000L * MILLIS));
        assertThat(hit.outputs()).containsExactly(new Countdown.Output.Timeout());
        assertThat(hit.model().running()).isFalse();
        assertThat(hit.model().expired()).isTrue();
        assertThat(hit.model().remaining()).isZero();
        assertThat(hit.command()).isInstanceOf(Command.None.class);
        assertThat(Subscription.leaves(widget.subscriptions(hit.model())))
                .as("an expired countdown holds no timer")
                .isEmpty();

        // A duplicate reading, and a much later one from the same run, report nothing more.
        assertThat(send(widget, hit.model(), new Countdown.Msg.Reading(model.epoch(), 60_000L * MILLIS))
                        .outputs())
                .isEmpty();
        assertThat(send(widget, hit.model(), new Countdown.Msg.Reading(model.epoch(), 900_000L * MILLIS))
                        .outputs())
                .isEmpty();

        // Starting over arms a new run, whose expiry is its own single timeout.
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> restart =
                send(widget, hit.model(), new Countdown.Msg.Start());
        Countdown.Model second = apply(widget, restart.model(), reading(restart.command(), 1_000_000L));
        assertThat(send(widget, second, new Countdown.Msg.Reading(second.epoch(), 1_060_000L * MILLIS))
                        .outputs())
                .containsExactly(new Countdown.Output.Timeout());
    }

    @Test
    void aLateOrReorderedReadingCannotIncreaseTheRemainingTime() {
        Countdown widget = countdown();
        Countdown.Model model = at(widget, running(widget, 0L), 30_000L);
        assertThat(model.remaining()).isEqualTo(Duration.ofSeconds(30));
        Countdown.Model earlier = at(widget, model, 10_000L);
        assertThat(earlier.remaining())
                .as("a reordered reading can only shorten what is left")
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(earlier.nowNanos()).isEqualTo(model.nowNanos());
        assertThat(earlier).isSameAs(model);
    }

    @Test
    void pausedTimeDoesNotElapse() {
        Countdown widget = countdown();
        Countdown.Model model = at(widget, running(widget, 0L), 20_000L);
        Countdown.Model paused = apply(widget, model, new Countdown.Msg.Stop());
        assertThat(paused.remaining()).isEqualTo(Duration.ofSeconds(40));
        // Readings of a paused countdown are not measurements: only resuming starts the clock again.
        assertThat(at(widget, paused, 900_000L)).isSameAs(paused);
    }

    @Test
    void aRunningCountdownDeclaresOneScopedInterval() {
        Countdown widget = countdown();
        Countdown.Model model = running(widget, 0L);
        List<Subscription<Countdown.Msg>> leaves = Subscription.leaves(widget.subscriptions(model));
        assertThat(leaves).hasSize(1);
        Subscription.Interval<Countdown.Msg> interval = (Subscription.Interval<Countdown.Msg>) leaves.get(0);
        assertThat(interval.id().value()).isEqualTo("tick");
        assertThat(interval.period()).isEqualTo(Countdown.DEFAULT_TICK_PERIOD);
        assertThat(interval.onTick().apply(74_000L * MILLIS))
                .as("the interval maps the runtime's reading with the run's epoch")
                .isEqualTo(new Countdown.Msg.Reading(model.epoch(), 74_000L * MILLIS));
    }

    @Test
    void anIntervalThatDeliversLateStillLandsOnTheRightTime() {
        Countdown widget = countdown();
        Countdown.Model model = running(widget, 0L);
        Subscription.Interval<Countdown.Msg> interval = (Subscription.Interval<Countdown.Msg>)
                Subscription.leaves(widget.subscriptions(model)).get(0);
        // Three periods' worth of scheduling happened before one tick arrived; a single reading is
        // still exactly right, because nothing was accumulated in between.
        Countdown.Model late = apply(widget, model, interval.onTick().apply(55_000L * MILLIS));
        assertThat(late.remaining()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aMountedCountdownHoldsItsIntervalOnlyWhileItRuns() {
        Countdown widget = countdown(Countdown.Config.of(Duration.ofHours(1)));
        Update<Countdown.Model, Countdown.Msg, Countdown.Output> start =
                send(widget, initial(widget), new Countdown.Msg.Start());
        Countdown.Model model = start.model();
        // The runtime's own clock, which is the timeline the interval reports on.
        model = apply(widget, model, new Countdown.Msg.Reading(model.epoch(), System.nanoTime()));

        ManualTimers timers = new ManualTimers();
        Mailbox mailbox = new Mailbox(16);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        SubscriptionManager<Countdown.Msg> manager = new SubscriptionManager<>(mailbox, timers, 8, diagnostics::add);
        try {
            manager.reconcile(widget.subscriptions(model));
            assertThat(timers.pending())
                    .as("a running countdown schedules one tick")
                    .isEqualTo(1);

            timers.advance(Countdown.DEFAULT_TICK_PERIOD.toNanos());
            List<Countdown.Msg> ticks = drained(mailbox);
            assertThat(ticks).hasSize(1);
            assertThat(ticks.get(0)).isInstanceOf(Countdown.Msg.Reading.class);
            model = apply(widget, model, ticks.get(0));
            assertThat(model.remaining()).isLessThan(Duration.ofHours(1));
            assertThat(model.remaining()).as("one period of real time passed").isGreaterThan(Duration.ofMinutes(59));
            assertThat(timers.pending())
                    .as("the interval keeps its one schedule")
                    .isEqualTo(1);

            // Stopping the countdown releases the interval, and nothing is left to deliver.
            model = apply(widget, model, new Countdown.Msg.Stop());
            manager.reconcile(widget.subscriptions(model));
            assertThat(timers.pending()).as("a paused countdown holds no timer").isZero();
            timers.advance(10_000L * MILLIS);
            assertThat(drained(mailbox)).isEmpty();
            assertThat(diagnostics).isEmpty();

            // Resuming schedules it again, and the expiry releases it for good. Both readings stay on
            // the runtime's own clock, which is the timeline the interval reports on.
            long base = System.nanoTime();
            Update<Countdown.Model, Countdown.Msg, Countdown.Output> resume =
                    send(widget, model, new Countdown.Msg.Resume());
            model = apply(
                    widget,
                    resume.model(),
                    new Countdown.Msg.Reading(resume.model().epoch(), base));
            manager.reconcile(widget.subscriptions(model));
            assertThat(timers.pending()).isEqualTo(1);
            Update<Countdown.Model, Countdown.Msg, Countdown.Output> expiry = send(
                    widget,
                    model,
                    new Countdown.Msg.Reading(
                            model.epoch(), base + Duration.ofHours(2).toNanos()));
            assertThat(expiry.outputs()).containsExactly(new Countdown.Output.Timeout());
            model = expiry.model();
            manager.reconcile(widget.subscriptions(model));
            assertThat(timers.pending())
                    .as("an expired countdown releases its interval")
                    .isZero();
        } finally {
            manager.closeAll();
            mailbox.close();
        }
    }

    @Test
    void aMountedCountdownScopesItsIntervalToItsOwnMount() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = started(scene, 1_000L);
        List<Subscription<CountdownDemo.Msg>> leaves = Subscription.leaves(scene.subscriptions(model));
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).id().toString()).isEqualTo("/countdown:tick");
        assertThat(Subscription.leaves(scene.subscriptions(initial(scene))))
                .as("an idle scene declares no source at all")
                .isEmpty();
    }

    // ── R-03: the exact duration and its rendering ───────────────────────────

    @Test
    void theModelExposesTheExactRemainingDuration() {
        Countdown widget = countdown();
        Countdown.Model model = at(widget, running(widget, 0L), 1L);
        assertThat(model.remainingNanos()).isEqualTo(60_000L * MILLIS - MILLIS);
        assertThat(model.remaining()).isEqualTo(Duration.ofNanos(59_999_000_000L));
        assertThat(model.remaining().toMillis()).isEqualTo(59_999L);
    }

    @Test
    void theDefaultFormatterWritesWholeSeconds() {
        assertThat(Countdown.clock(Duration.ZERO)).isEqualTo("00:00");
        assertThat(Countdown.clock(Duration.ofSeconds(1))).isEqualTo("00:01");
        assertThat(Countdown.clock(Duration.ofSeconds(59))).isEqualTo("00:59");
        assertThat(Countdown.clock(Duration.ofSeconds(60))).isEqualTo("01:00");
        assertThat(Countdown.clock(Duration.ofSeconds(61))).isEqualTo("01:01");
        assertThat(Countdown.clock(Duration.ofSeconds(3599))).isEqualTo("59:59");
        assertThat(Countdown.clock(Duration.ofSeconds(3600)))
                .as("an hour grows the third field rather than a ninety-minute count")
                .isEqualTo("1:00:00");
        assertThat(Countdown.clock(Duration.ofSeconds(3661))).isEqualTo("1:01:01");
        assertThat(Countdown.clock(Duration.ofSeconds(36_000))).isEqualTo("10:00:00");
        assertThat(Countdown.clock(Duration.ofSeconds(90))).isEqualTo("01:30");
    }

    @Test
    void theDefaultFormatterRoundsAPositiveFractionUpAndNeverPaintsEarlyZero() {
        assertThat(Countdown.clock(Duration.ofNanos(1L)))
                .as("a countdown with time left never paints zero")
                .isEqualTo("00:01");
        assertThat(Countdown.clock(Duration.ofMillis(999L))).isEqualTo("00:01");
        assertThat(Countdown.clock(Duration.ofSeconds(59, 1L)))
                .as("rounding up crosses the minute")
                .isEqualTo("01:00");
        assertThat(Countdown.clock(Duration.ofNanos(-1L)))
                .as("a negative duration is no time at all")
                .isEqualTo("00:00");
        assertThat(Countdown.clock(null)).isEqualTo("00:00");
        assertThat(Countdown.clock(Duration.ofNanos(Long.MAX_VALUE)))
                .as("the largest representable remainder still renders")
                .matches("\\d+:\\d{2}:\\d{2}");
    }

    @Test
    void aCustomFormatterReplacesThePolicyAndRenderingStaysPure() {
        Countdown widget = countdown();
        Countdown.Model model = running(widget, 0L);
        Countdown.Config hundredths =
                CONFIG.withFormatter(remaining -> String.format(java.util.Locale.ROOT, "%d ms", remaining.toMillis()));
        assertThat(Countdown.render(model, hundredths).plainText()).isEqualTo("60000 ms");
        assertThat(Countdown.render(model, CONFIG).plainText())
                .as("the definition, not the state, owns the formatting")
                .isEqualTo("01:00");
        assertThat(Countdown.render(model, hundredths)).isEqualTo(Countdown.render(model, hundredths));
        assertThat(Countdown.render(model, CONFIG.withFormatter(remaining -> null)))
                .as("a declining formatter paints nothing")
                .isEqualTo(StyledText.empty());
        assertThat(model).isEqualTo(running(widget, 0L));
    }

    @Test
    void thePaintedTimeIsStyledCutByColumnsAndNeverSplit() {
        Countdown.Model model = running(countdown(), 0L);
        StyledText styled = Countdown.render(model, CONFIG.withStyle(Style.NONE.withBold()));
        assertThat(styled.plainText()).isEqualTo("01:00");
        assertThat(styled.spans()).allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE.withBold()));

        assertThat(Countdown.render(model, CONFIG, 0)).isEqualTo(StyledText.empty());
        assertThat(Countdown.render(model, CONFIG, -1)).isEqualTo(StyledText.empty());
        assertThat(Countdown.render(model, CONFIG, 2).plainText()).isEqualTo("01");
        assertThat(Countdown.render(model, CONFIG, 5).plainText()).isEqualTo("01:00");

        Countdown.Config wide = CONFIG.withFormatter(remaining -> "\uD83C\uDF0D\uD83C\uDF0D");
        StyledText cut = Countdown.render(model, wide, 3);
        assertThat(cut.plainText()).as("a wide glyph is dropped whole").isEqualTo("\uD83C\uDF0D");
        assertThat(cut.columns()).isEqualTo(2);
    }

    @Test
    void aMountedCountdownPaintsItsOwnTime() {
        Countdown widget = countdown();
        Countdown.Model model = running(widget, 0L);
        Element<Countdown.Msg> view = widget.view(model, new ViewContext(new Viewport(24, 4)));
        assertThat(painted(view, 1, 80).get(0)).isEqualTo("01:00");
        assertThat(painted(widget.view(model, new ViewContext(new Viewport(24, 4))), 1, 3)
                        .get(0))
                .isEqualTo("01:");
    }

    // ── R-04: the demo ───────────────────────────────────────────────────────

    @Test
    void theDemoMountsAnIdleCountdownAndShowsIt() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = initial(scene);
        List<String> rows = painted(scene, model);
        assertThat(rows.get(0)).isEqualTo("countdown state=idle epoch=0 nanos=5000000000 timeouts=0");
        assertThat(rows.get(1)).isEqualTo("duration=00:05 tick=100ms last=none");
        assertThat(rows.get(2)).isEmpty();
        assertThat(rows.get(3)).as("the mounted countdown's own view").isEqualTo("remaining  00:05");
        assertThat(rows.get(4)).isEmpty();
        assertThat(rows.get(5))
                .isEqualTo("s start over \u2022 space start/pause \u2022 r reset"
                        + " \u2022 \u2190 shorter \u2022 \u2192 longer \u2022 esc quit");
        assertThat(scene.children(model).nodes()).hasSize(1);
        assertThat(Subscription.leaves(scene.subscriptions(model))).isEmpty();
    }

    @Test
    void theSpaceKeyStartsPausesAndResumesTheCountdown() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = started(scene, 1_000L);
        assertThat(model.countdown().running()).isTrue();
        assertThat(model.countdown().armed())
                .as("the timestamp command named the deadline")
                .isFalse();
        assertThat(model.countdown().deadlineNanos()).isEqualTo(6_000L * MILLIS);
        assertThat(row(model, 1)).isEqualTo("duration=00:05 tick=100ms last=start");
        assertThat(Subscription.leaves(scene.subscriptions(model))).hasSize(1);

        // One reading three seconds in: the painted time follows the runtime's clock.
        model = deliver(scene, model, tick(model, 3_000L), 3_000L * MILLIS);
        assertThat(row(model, 0)).isEqualTo("countdown state=running epoch=1 nanos=3000000000 timeouts=0");
        assertThat(row(model, 3)).isEqualTo("remaining  00:03");

        CountdownDemo.Model paused = deliver(scene, model, new CountdownDemo.Msg.Toggle(), 3_500L * MILLIS);
        assertThat(row(paused, 1)).isEqualTo("duration=00:05 tick=100ms last=pause");
        assertThat(row(paused, 0)).isEqualTo("countdown state=paused epoch=2 nanos=3000000000 timeouts=0");
        assertThat(deliver(scene, paused, tick(paused, 4_500L), 4_500L * MILLIS))
                .as("a paused countdown ignores the readings of the run it froze")
                .isSameAs(paused);

        CountdownDemo.Model resumed = deliver(scene, paused, new CountdownDemo.Msg.Toggle(), 20_000L * MILLIS);
        assertThat(row(resumed, 1)).isEqualTo("duration=00:05 tick=100ms last=resume");
        assertThat(resumed.countdown().remaining())
                .as("resuming keeps the remainder")
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(resumed.countdown().deadlineNanos()).isEqualTo(23_000L * MILLIS);
        assertThat(row(resumed, 3)).isEqualTo("remaining  00:03");
    }

    @Test
    void theStartKeyDiscardsThePausedRemainder() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model running = started(scene, 0L);
        CountdownDemo.Model model = deliver(scene, running, tick(running, 3_000L), 3_000L * MILLIS);
        CountdownDemo.Model paused = deliver(scene, model, new CountdownDemo.Msg.Toggle(), 3_000L * MILLIS);
        assertThat(paused.countdown().remaining()).isEqualTo(Duration.ofSeconds(2));

        CountdownDemo.Model restarted = deliver(scene, paused, new CountdownDemo.Msg.Start(), 10_000L * MILLIS);
        assertThat(row(restarted, 1)).isEqualTo("duration=00:05 tick=100ms last=start");
        assertThat(restarted.countdown().remaining())
                .as("a start is a fresh run, not a resume")
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(row(restarted, 3)).isEqualTo("remaining  00:05");
    }

    @Test
    void theTimeoutIsReportedOnceAndStartsANewRun() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = started(scene, 0L);
        CountdownDemo.Model expired = deliver(scene, model, tick(model, 5_000L), 5_000L * MILLIS);
        assertThat(expired.timeouts()).isEqualTo(1);
        assertThat(row(expired, 0)).isEqualTo("countdown state=expired epoch=1 nanos=0 timeouts=1");
        assertThat(row(expired, 1)).isEqualTo("duration=00:05 tick=100ms last=timeout");
        assertThat(row(expired, 3)).as("the expiry reaches the screen once").isEqualTo("remaining  00:00   time is up");
        assertThat(Subscription.leaves(scene.subscriptions(expired))).isEmpty();

        // A duplicate reading of the run that expired dispatches nothing at all.
        Update<CountdownDemo.Model, CountdownDemo.Msg, Never> duplicate = scene.update(expired, tick(expired, 6_000L));
        assertThat(duplicate.command()).isInstanceOf(Command.None.class);
        assertThat(duplicate.model()).isSameAs(expired);

        // The same key starts a new run, and the scene keeps the one timeout it was told about.
        CountdownDemo.Model again = deliver(scene, expired, new CountdownDemo.Msg.Toggle(), 10_000L * MILLIS);
        assertThat(again.timeouts()).isEqualTo(1);
        assertThat(row(again, 0)).isEqualTo("countdown state=running epoch=2 nanos=5000000000 timeouts=1");
        assertThat(row(again, 1)).isEqualTo("duration=00:05 tick=100ms last=start");
    }

    @Test
    void theDurationGesturesConfigureTheCountdownAndStopAtTheSceneBounds() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = initial(scene);

        CountdownDemo.Model longer = deliver(scene, model, new CountdownDemo.Msg.Adjust(15L), 0L);
        assertThat(longer.duration()).isEqualTo(Duration.ofSeconds(20));
        assertThat(row(longer, 1)).isEqualTo("duration=00:20 tick=100ms last=duration-20");
        assertThat(row(longer, 3))
                .as("a new duration resets the countdown to it")
                .isEqualTo("remaining  00:20");

        CountdownDemo.Model shorter = deliver(scene, longer, new CountdownDemo.Msg.Adjust(-15L), 0L);
        assertThat(row(shorter, 1)).isEqualTo("duration=00:05 tick=100ms last=duration-5");

        CountdownDemo.Model limited = deliver(scene, shorter, new CountdownDemo.Msg.Adjust(-15L), 0L);
        assertThat(limited.duration()).as("the scene's minimum holds").isEqualTo(CountdownDemo.MIN_DURATION);
        assertThat(row(limited, 1)).isEqualTo("duration=00:05 tick=100ms last=limit");

        assertThat(CountdownDemo.clamp(Duration.ofHours(6))).isEqualTo(CountdownDemo.MAX_DURATION);
        assertThat(CountdownDemo.clamp(null)).isEqualTo(CountdownDemo.DEFAULT_DURATION);

        // A duration gesture during a run stops it: a run of the old length would mean nothing.
        CountdownDemo.Model running = started(scene, 0L);
        CountdownDemo.Model adjusted = deliver(scene, running, new CountdownDemo.Msg.Adjust(15L), 1_000L * MILLIS);
        assertThat(adjusted.countdown().running()).isFalse();
        assertThat(adjusted.countdown().remaining()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void theResetKeyPutsTheConfiguredDurationBack() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = started(scene, 0L);
        model = deliver(scene, model, tick(model, 2_000L), 2_000L * MILLIS);
        CountdownDemo.Model reset = deliver(scene, model, new CountdownDemo.Msg.Reset(), 2_000L * MILLIS);
        assertThat(reset.countdown().running()).isFalse();
        assertThat(reset.countdown().remaining()).isEqualTo(CountdownDemo.TEST_DURATION);
        assertThat(row(reset, 0)).isEqualTo("countdown state=paused epoch=2 nanos=5000000000 timeouts=0");
        assertThat(row(reset, 1)).isEqualTo("duration=00:05 tick=100ms last=reset");
        assertThat(row(reset, 3)).isEqualTo("remaining  00:05");
    }

    @Test
    void everySceneGestureIsReachableFromTheRoutedKeyMap() {
        CountdownDemo.Model model = initial(demo());
        assertThat(route(model, "s")).contains(new CountdownDemo.Msg.Start());
        assertThat(route(model, "space")).contains(new CountdownDemo.Msg.Toggle());
        assertThat(route(model, "r")).contains(new CountdownDemo.Msg.Reset());
        assertThat(route(model, "left")).contains(new CountdownDemo.Msg.Adjust(-15L));
        assertThat(route(model, "h")).contains(new CountdownDemo.Msg.Adjust(-15L));
        assertThat(route(model, "right")).contains(new CountdownDemo.Msg.Adjust(15L));
        assertThat(route(model, "l")).contains(new CountdownDemo.Msg.Adjust(15L));
        assertThat(route(model, "escape")).contains(new CountdownDemo.Msg.Quit());
        assertThat(route(model, "q")).as("an unbound key reaches nothing").isEmpty();

        List<String> help =
                painted(Help.element(CountdownDemo.defaultKeys(), Help.Config.defaults(), true, 200), 8, 200).stream()
                        .filter(line -> !line.isBlank())
                        .toList();
        assertThat(help).hasSize(3);
        assertThat(help.get(0)).contains("start over").contains("start/pause").contains("reset");
        assertThat(help.get(1)).contains("shorter").contains("longer");
        assertThat(help.get(2)).contains("quit");
    }

    @Test
    void escapeQuitsTheDemo() {
        Update<CountdownDemo.Model, CountdownDemo.Msg, Never> update =
                demo().update(initial(demo()), new CountdownDemo.Msg.Quit());
        assertThat(update.command()).isInstanceOf(Command.Quit.class);
        assertThat(update.outputs()).isEmpty();
    }

    @Test
    void theDemoRunsUnderTheDeterministicDriver() {
        ComponentDriver<CountdownDemo.Model, CountdownDemo.Msg, Never> driver =
                ComponentDriver.initialize(demo(), new InitContext(new Viewport(24, 80), null));
        assertThat(driver.screen(CONTEXT).line(0))
                .isEqualTo("countdown state=idle epoch=0 nanos=5000000000 timeouts=0");
        driver.send(new CountdownDemo.Msg.Toggle());
        CountdownDemo.Model armed = driver.model();
        assertThat(armed.countdown().running()).isTrue();
        assertThat(driver.subscriptions()).isInstanceOf(Subscription.Interval.class);
        // The driver leaves commands inert, so the first reading it feeds is the timestamp fact that
        // names the deadline and the second one measures the distance to it.
        driver.send(tick(armed, 1_000L));
        assertThat(driver.model().countdown().armed()).isFalse();
        driver.send(tick(armed, 2_000L));
        assertThat(driver.screen(CONTEXT).line(3)).isEqualTo("remaining  00:04");
        driver.send(new CountdownDemo.Msg.Toggle());
        assertThat(driver.model().countdown().running()).isFalse();
        assertThat(driver.subscriptions()).isInstanceOf(Subscription.None.class);
        assertThat(driver.children().nodes()).hasSize(1);
    }

    @Test
    void aNarrowSceneClipsTheHelpInsteadOfWrapping() {
        CountdownDemo.Scene scene = demo();
        CountdownDemo.Model model = initial(scene);
        List<String> lines = painted(scene.view(model, new ViewContext(new Viewport(24, 40))), 24, 40);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(40));
        assertThat(lines.get(3)).isEqualTo("remaining  00:05");
        assertThat(lines.get(5))
                .as("the help is cut, not wrapped")
                .startsWith("s start over")
                .endsWith("\u2026");
        assertThat(lines.get(0)).as("the status row stays its own row").startsWith("countdown state=idle");
    }

    @Test
    void theSceneHoldsTheIntervalOnlyWhileTheCountdownRuns() {
        CountdownDemo.Scene scene = demo();
        ManualTimers timers = new ManualTimers();
        Mailbox mailbox = new Mailbox(16);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        SubscriptionManager<CountdownDemo.Msg> manager =
                new SubscriptionManager<>(mailbox, timers, 8, diagnostics::add);
        try {
            CountdownDemo.Model model = initial(scene);
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).as("an idle scene schedules nothing").isZero();

            model = deliver(scene, model, new CountdownDemo.Msg.Toggle(), System.nanoTime());
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).isEqualTo(1);

            timers.advance(Countdown.DEFAULT_TICK_PERIOD.toNanos());
            List<CountdownDemo.Msg> ticks = drainedDemo(mailbox);
            assertThat(ticks).hasSize(1);
            assertThat(ticks.get(0)).isInstanceOf(CountdownDemo.Msg.Count.class);
            CountdownDemo.Model ticked = deliver(scene, model, ticks.get(0), System.nanoTime());
            assertThat(ticked.countdown().remaining()).isLessThan(CountdownDemo.TEST_DURATION);

            CountdownDemo.Model paused = deliver(scene, ticked, new CountdownDemo.Msg.Toggle(), System.nanoTime());
            manager.reconcile(scene.subscriptions(paused));
            assertThat(timers.pending())
                    .as("a paused countdown leaves nothing scheduled")
                    .isZero();
            timers.advance(10_000L * MILLIS);
            assertThat(diagnostics).isEmpty();
        } finally {
            manager.closeAll();
            mailbox.close();
        }
    }
}
