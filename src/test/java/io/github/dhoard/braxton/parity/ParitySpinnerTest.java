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

import examples.parity.SpinnerDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputRouter;
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
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.Animation;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Spinner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 07 acceptance: configurable spinner definitions, runtime-time state and the two-spinner demo.
 *
 * <p>The pure cases drive {@code update} with explicit monotonic readings, so the asserted timing is
 * exact and involves no sleeps. The lifecycle cases reconcile the demo's own subscription
 * declarations against {@link ManualTimers}, so "stopped" and "unmounted" are proven by the ticks
 * that stop being scheduled rather than by a frame that happened to stay still. The terminal
 * behaviour of the packaged demo is verified separately by
 * {@code python3 tools/terminal/verify_terminal.py --parity-phase 07}.</p>
 */
@Timeout(20)
class ParitySpinnerTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    private static final long MILLIS = 1_000_000L;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Spinner spinner(Spinner.Config config) {
        return new Spinner(config);
    }

    private static Spinner spinner(Spinner.Preset preset) {
        return new Spinner(Spinner.Config.preset(preset));
    }

    private static Spinner.Model after(Spinner spinner, Spinner.Model model, Spinner.Msg message) {
        return spinner.update(model, message).model();
    }

    /** One tick of the model's current cycle, at an absolute monotonic time in milliseconds. */
    private static Spinner.Model at(Spinner spinner, Spinner.Model model, long millis) {
        return after(spinner, model, new Spinner.Msg.Tick(model.epoch(), millis * MILLIS));
    }

    /** A running spinner whose origin has been recorded at {@code originMillis}. */
    private static Spinner.Model origin(Spinner spinner, long originMillis) {
        return at(spinner, Spinner.Model.starting(), originMillis);
    }

    private static String frame(Spinner spinner, Spinner.Model model) {
        return Spinner.render(model, spinner.config()).plainText();
    }

    private static InputEvent.KeyInput key(String spec) {
        Key gesture = Key.parse(spec);
        return InputEvent.KeyInput.of(gesture, gesture.name());
    }

    private static List<String> painted(View<?> view) {
        return new ScreenRenderer().plainLines(view, new Viewport(SCREEN_ROWS, SCREEN_COLS));
    }

    private static List<String> painted(View<?> view, int cols) {
        return new ScreenRenderer().plainLines(view, new Viewport(SCREEN_ROWS, cols));
    }

    private static List<String> painted(View<?> view, int rows, int cols) {
        return new ScreenRenderer().plainLines(view, new Viewport(rows, cols));
    }

    /** The demo the packaged launch runs, driven as its own component. */
    private static SpinnerDemo.Scene demo() {
        return new SpinnerDemo.Scene();
    }

    private static SpinnerDemo.Model start() {
        return demo().init(new InitContext(null, null)).model();
    }

    private static SpinnerDemo.Model apply(SpinnerDemo.Model model, SpinnerDemo.Msg message) {
        return demo().update(model, message).model();
    }

    /** What one key does to the demo's screen, as the scene's own message. */
    private static Optional<SpinnerDemo.Msg> route(SpinnerDemo.Model model, String keyName) {
        return InputRouter.route(demo().view(model, CONTEXT), key(keyName));
    }

    private static SpinnerDemo.Model send(SpinnerDemo.Model model, String keyName) {
        return apply(model, route(model, keyName).orElseThrow(() -> new AssertionError("no message for " + keyName)));
    }

    /** The demo's status rows, so a test can assert the scene without decoding styles. */
    private static String row(SpinnerDemo.Model model, int index) {
        return painted(demo().view(model, CONTEXT)).get(index).strip();
    }

    /** Feeds one tick to one mount of the demo, as the runtime's mapper would. */
    private static SpinnerDemo.Model tick(SpinnerDemo.Model model, boolean leftPane, long millis) {
        Spinner.Model state = model.slot(leftPane).model();
        Spinner.Msg message = new Spinner.Msg.Tick(state.epoch(), millis * MILLIS);
        return apply(model, leftPane ? new SpinnerDemo.Msg.Left(message) : new SpinnerDemo.Msg.Right(message));
    }

    // ── R-01: the definition ─────────────────────────────────────────────────

    @Test
    void aDefinitionNeedsAtLeastOneFrame() {
        assertThatThrownBy(() -> new Spinner.Config(List.of(), Duration.ofMillis(100), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one frame");
        assertThatThrownBy(() -> new Spinner.Config(null, Duration.ofMillis(100), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Spinner.Config.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFrameMustBeNonEmptyPaintableText() {
        assertThatThrownBy(() -> Spinner.Config.of(List.of("ok", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> Spinner.Config.of(listWithNullFrame()))
                .as("a null frame is a missing definition")
                .isInstanceOf(NullPointerException.class);
        // Control text is a definition error rather than something stripped at paint time: an escape
        // sequence in a frame would otherwise be a terminal command, and a tab would make the painted
        // width depend on where the row starts.
        for (String hostile : List.of("\u001B[31m", "\t", "\r", "\u0007", "a\u007Fb", "\u009Bx")) {
            assertThatThrownBy(() -> Spinner.Config.of(List.of(hostile)))
                    .as("frame %s", hostile.replace("\u001B", "ESC"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control characters");
        }
        assertThatThrownBy(() -> Spinner.Config.of(List.of("\uD83C")))
                .as("half a surrogate pair cannot be painted")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");

        Spinner.Config multiline = Spinner.Config.of(List.of("a\nb"));
        assertThat(multiline.rows())
                .as("a line break is how a frame becomes multi-row")
                .isEqualTo(2);
        assertThat(Spinner.Config.of(List.of(" ")).frameCount())
                .as("a blank frame is odd but paintable")
                .isEqualTo(1);
    }

    private static List<String> listWithNullFrame() {
        List<String> frames = new ArrayList<>();
        frames.add("x");
        frames.add(null);
        return frames;
    }

    @Test
    void aFrameDurationMustBePositiveAndFitInNanoseconds() {
        for (Duration refused : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(-1))) {
            assertThatThrownBy(() -> new Spinner.Config(List.of("-"), refused, null))
                    .as("duration %s", refused)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
        assertThatThrownBy(() -> new Spinner.Config(List.of("-"), null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Spinner.Config(List.of("-"), Duration.ofSeconds(Long.MAX_VALUE), null))
                .as("a cadence no clock can express is not a definition")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fit in nanoseconds");
        assertThat(Spinner.Config.of(List.of("-"), Duration.ofNanos(1)).frameStepNanos())
                .as("one nanosecond is the smallest usable step")
                .isEqualTo(1L);
    }

    @Test
    void everyPresetIsAValidDefinitionWithItsOwnWidth() {
        record Snapshot(Spinner.Preset preset, String label, int frames, int rows, int columns) {}
        List<Snapshot> expected = List.of(
                new Snapshot(Spinner.Preset.LINE, "line", 4, 1, 1),
                new Snapshot(Spinner.Preset.DOT, "dot", 8, 1, 1),
                new Snapshot(Spinner.Preset.PULSE, "pulse", 6, 1, 1),
                new Snapshot(Spinner.Preset.GLOBE, "globe", 3, 1, 2),
                new Snapshot(Spinner.Preset.MOON, "moon", 8, 1, 2));
        assertThat(Spinner.Preset.values()).hasSize(expected.size());
        for (Snapshot snapshot : expected) {
            Spinner.Config config = Spinner.Config.preset(snapshot.preset());
            assertThat(snapshot.preset().label()).isEqualTo(snapshot.label());
            assertThat(config.frames()).isEqualTo(snapshot.preset().frames());
            assertThat(config.frameCount()).isEqualTo(snapshot.frames());
            assertThat(config.rows()).isEqualTo(snapshot.rows());
            assertThat(config.columns())
                    .as("%s painted width", snapshot.label())
                    .isEqualTo(snapshot.columns());
            assertThat(config.style()).isEqualTo(Style.NONE);
            assertThat(config.frameDuration()).isEqualTo(snapshot.preset().frameDuration());
            assertThat(config.frames()).allSatisfy(value -> assertThat(value).isNotEmpty());
        }
        assertThat(Spinner.Preset.LINE.frames()).doesNotHaveDuplicates();
        assertThat(Spinner.Preset.MOON.frames())
                .as("eight distinct phases")
                .hasSize(8)
                .doesNotHaveDuplicates();
    }

    @Test
    void aDefinitionIsImmutableAndItsWithersPreserveTheRest() {
        List<String> frames = new ArrayList<>(List.of("a", "b"));
        Spinner.Config config = Spinner.Config.of(frames, Duration.ofMillis(40));
        frames.add("c");
        assertThat(config.frames()).as("the definition copied its frames").containsExactly("a", "b");
        assertThatThrownBy(() -> config.frames().add("d")).isInstanceOf(UnsupportedOperationException.class);

        Spinner.Config styled = config.withStyle(Style.NONE.withBold());
        assertThat(styled.frames()).isEqualTo(config.frames());
        assertThat(styled.frameDuration()).isEqualTo(config.frameDuration());
        assertThat(config.style()).as("the original is unchanged").isEqualTo(Style.NONE);
        assertThat(styled.withFrames(List.of("z")).frameDuration()).isEqualTo(Duration.ofMillis(40));
        assertThat(styled.withFrameDuration(Duration.ofSeconds(1)).style()).isEqualTo(styled.style());
        assertThat(config.withStyle(null).style())
                .as("a null style is the plain style")
                .isEqualTo(Style.NONE);
    }

    @Test
    void sizingFactsReportTheLargestFrame() {
        Spinner.Config multiline =
                Spinner.Config.of(List.of("\uD83C\uDF11\uD83C\uDF12\nx", "\u00E9"), Duration.ofMillis(100));
        assertThat(multiline.rows()).isEqualTo(2);
        assertThat(multiline.columns()).as("two wide emoji on the longest row").isEqualTo(4);
        assertThat(Spinner.Config.defaults().rows()).isEqualTo(1);
        assertThat(Spinner.Config.defaults().columns()).isEqualTo(1);
        assertThat(Spinner.Config.preset(null).frames())
                .as("a missing preset is the documented default")
                .isEqualTo(Spinner.DEFAULT_PRESET.frames());
    }

    @Test
    void presetsCycleInBothDirections() {
        Spinner.Preset preset = Spinner.Preset.LINE;
        List<Spinner.Preset> seen = new ArrayList<>();
        for (int step = 0; step < Spinner.Preset.values().length; step++) {
            seen.add(preset);
            preset = preset.shift(1);
        }
        assertThat(seen).containsExactly(Spinner.Preset.values());
        assertThat(preset).as("a full lap returns to the first preset").isEqualTo(Spinner.Preset.LINE);
        assertThat(Spinner.Preset.LINE.shift(-1)).isEqualTo(Spinner.Preset.MOON);
        assertThat(Spinner.Preset.LINE.shift(0)).isEqualTo(Spinner.Preset.LINE);
        Object[] presets = Spinner.Preset.values();
        assertThat(Spinner.Preset.LINE.shift(Integer.MIN_VALUE))
                .as("no step size can escape the cycle")
                .isIn(presets);
        assertThat(Spinner.Preset.LINE.shift(Integer.MAX_VALUE)).isIn(presets);
    }

    // ── R-02: runtime time drives the frame ───────────────────────────────────

    @Test
    void theFirstTickRecordsTheOriginSoTheCycleStartsAtFrameZero() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model starting = Spinner.Model.starting();
        assertThat(starting.running()).isTrue();
        assertThat(starting.armed()).isTrue();
        assertThat(starting.elapsedNanos()).as("no clock has been read yet").isZero();

        Spinner.Model armed = at(spinner, starting, 5_000L);
        assertThat(armed.armed()).isFalse();
        assertThat(armed.originNanos()).isEqualTo(5_000L * MILLIS);
        assertThat(armed.nowNanos()).isEqualTo(armed.originNanos());
        assertThat(armed.elapsedNanos()).isZero();
        assertThat(frame(spinner, armed)).isEqualTo("\u007C");
    }

    @Test
    void equalModelAndTimePaintEqualFrames() {
        Spinner spinner = spinner(Spinner.Preset.DOT);
        Spinner.Model model = origin(spinner, 10_000L);
        Spinner.Model later = at(spinner, model, 10_245L);
        assertThat(Spinner.render(later, spinner.config())).isEqualTo(Spinner.render(later, spinner.config()));
        assertThat(later.frameIndex(spinner.config()))
                .as("245 ms of 80 ms frames is frame three")
                .isEqualTo(3);
        assertThat(at(spinner, model, 10_245L)).isEqualTo(later);
    }

    @Test
    void aLongGapLandsOnTheFrameTheElapsedTimeSelects() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model model = origin(spinner, 0L);
        for (int step = 1; step <= 3; step++) {
            assertThat(at(spinner, model, step * 100L).frameIndex(spinner.config()))
                    .as("step %d", step)
                    .isEqualTo(step);
        }
        // One tick that arrives after a hundred frames must land where the clock says, not replay.
        assertThat(at(spinner, model, 10_000L).frameIndex(spinner.config())).isEqualTo(10_000 / 100 % 4);
        assertThat(at(spinner, model, 100_000_000L).frameIndex(spinner.config()))
                .isEqualTo(100_000_000 / 100 % 4);
    }

    @Test
    void stoppingKeepsTheFrameItWasShowing() {
        Spinner spinner = spinner(Spinner.Preset.MOON);
        Spinner.Model running = at(spinner, origin(spinner, 1_000L), 1_650L);
        String shown = frame(spinner, running);
        Spinner.Model stopped = after(spinner, running, new Spinner.Msg.Stop());
        assertThat(stopped.running()).isFalse();
        assertThat(frame(spinner, stopped))
                .as("a stopped spinner does not move")
                .isEqualTo(shown);
        assertThat(stopped.elapsedNanos()).isEqualTo(running.elapsedNanos());
        assertThat(stopped.originNanos()).isEqualTo(running.originNanos());
        assertThat(stopped.epoch()).as("the cycle was invalidated").isNotEqualTo(running.epoch());
    }

    @Test
    void restartingBeginsANewCycleAtFrameZero() {
        Spinner spinner = spinner(Spinner.Preset.PULSE);
        Spinner.Model running = at(spinner, origin(spinner, 0L), 480L);
        assertThat(running.frameIndex(spinner.config())).isEqualTo(4);
        Spinner.Model restarted =
                after(spinner, after(spinner, running, new Spinner.Msg.Stop()), new Spinner.Msg.Start());
        assertThat(restarted.running()).isTrue();
        assertThat(restarted.armed()).as("the next tick names the new origin").isTrue();
        assertThat(restarted.elapsedNanos()).isZero();
        assertThat(frame(spinner, restarted))
                .as("a restart begins at frame one")
                .isEqualTo("\u2591");
        assertThat(frame(spinner, at(spinner, restarted, 9_000L)))
                .as("a late origin still paints the first frame")
                .isEqualTo("\u2591");
    }

    @Test
    void aStaleTickNeverAdvancesANewerCycle() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model stopped = after(spinner, at(spinner, origin(spinner, 0L), 250L), new Spinner.Msg.Stop());
        Spinner.Msg stale = new Spinner.Msg.Tick(stopped.epoch() - 1, 900L * MILLIS);
        assertThat(spinner.update(stopped, stale).model())
                .as("a tick from the stopped cycle is ignored")
                .isSameAs(stopped);

        Spinner.Model restarted = after(spinner, stopped, new Spinner.Msg.Start());
        assertThat(spinner.update(restarted, stale).model())
                .as("a tick from the previous cycle cannot advance the new one")
                .isSameAs(restarted);
        assertThat(spinner.update(restarted, new Spinner.Msg.Tick(restarted.epoch() + 1, 900L * MILLIS))
                        .model())
                .as("a tick from a cycle that has not started yet is ignored too")
                .isSameAs(restarted);

        Spinner.Model reset = after(spinner, at(spinner, origin(spinner, 0L), 700L), new Spinner.Msg.Reset());
        assertThat(spinner.update(reset, new Spinner.Msg.Tick(reset.epoch() - 1, 800L * MILLIS))
                        .model())
                .as("a reset invalidates the ticks of the cycle it replaced")
                .isSameAs(reset);
    }

    @Test
    void aResetRestartsTheCurrentCycleAndKeepsTheRunningState() {
        Spinner spinner = spinner(Spinner.Preset.GLOBE);
        Spinner.Model running = at(spinner, origin(spinner, 0L), 400L);
        assertThat(running.frameIndex(spinner.config())).isEqualTo(2);
        Spinner.Model reset = after(spinner, running, new Spinner.Msg.Reset());
        assertThat(reset.running()).isTrue();
        assertThat(reset.armed()).isTrue();
        assertThat(frame(spinner, reset)).isEqualTo("\uD83C\uDF0D");
        assertThat(after(spinner, reset, new Spinner.Msg.Tick(reset.epoch(), 250L * MILLIS))
                        .originNanos())
                .as("the first tick of the new cycle is its origin")
                .isEqualTo(250L * MILLIS);

        Spinner.Model stopped =
                after(spinner, after(spinner, running, new Spinner.Msg.Stop()), new Spinner.Msg.Reset());
        assertThat(stopped.running())
                .as("a reset never starts a stopped spinner")
                .isFalse();
        assertThat(stopped.frameIndex(spinner.config())).isZero();
    }

    @Test
    void aTimeBeforeTheOriginIsFrameZero() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model model = origin(spinner, 5_000L);
        Spinner.Model backwards = after(spinner, model, new Spinner.Msg.Tick(model.epoch(), 4_900L * MILLIS));
        assertThat(backwards.frameIndex(spinner.config())).isZero();
        assertThat(backwards.elapsedNanos()).isZero();
        assertThat(Spinner.elapsed(5_000L * MILLIS, 4_900L * MILLIS)).isZero();
    }

    @Test
    void hugeElapsedTimesStayInsideTheCycle() {
        assertThat(Spinner.elapsed(Long.MIN_VALUE, Long.MAX_VALUE))
                .as("an overflowing difference saturates")
                .isEqualTo(Long.MAX_VALUE);
        assertThat(Spinner.elapsed(Long.MAX_VALUE, Long.MIN_VALUE)).isZero();
        assertThat(Spinner.elapsed(42L, 42L)).isZero();

        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model model = new Spinner.Model(true, 0L, Long.MIN_VALUE + 1, Long.MAX_VALUE);
        assertThat(model.elapsedNanos()).isEqualTo(Long.MAX_VALUE);
        assertThat(frame(spinner, model)).isIn(Spinner.Preset.LINE.frames());
        assertThat(model.frameIndex(spinner.config())).isBetween(0, 3);

        // The same for every preset and for a definition with one frame, which can never move.
        for (Spinner.Preset preset : Spinner.Preset.values()) {
            Spinner spinnerPreset = spinner(preset);
            Spinner.Model extreme = new Spinner.Model(true, 0L, Long.MIN_VALUE + 1, Long.MAX_VALUE);
            assertThat(frame(spinnerPreset, extreme)).isIn(preset.frames());
            assertThat(Spinner.render(extreme, spinnerPreset.config(), 4).columns())
                    .as("%s clipped to four columns", preset.label())
                    .isLessThanOrEqualTo(4);
        }
        Spinner single = spinner(Spinner.Config.of(List.of("*"), Duration.ofMillis(1)));
        assertThat(frame(single, new Spinner.Model(true, 0L, 0L, Long.MAX_VALUE)))
                .isEqualTo("*");
    }

    @Test
    void frameIndexSurvivesAnEmptyCycleAndAnUnusualStep() {
        assertThatThrownBy(() -> Spinner.frameIndex(0, 1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one frame");
        assertThat(Spinner.frameIndex(4, 0L, 7L))
                .as("a zero step is one nanosecond per frame rather than a division by zero")
                .isEqualTo(7 % 4);
        assertThat(Spinner.frameIndex(4, 100L, -150L))
                .as("a negative reading still names a real frame rather than a negative index")
                .isEqualTo(3);
        assertThat(Spinner.frameIndex(Integer.MAX_VALUE, 1L, Long.MAX_VALUE)).isBetween(0, Integer.MAX_VALUE - 1);
    }

    @Test
    void aMessageThatChangesNothingKeepsTheIncomingInstance() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model running = at(spinner, origin(spinner, 0L), 300L);
        assertThat(spinner.update(running, new Spinner.Msg.Start()).model())
                .as("starting a running spinner changes nothing")
                .isSameAs(running);
        assertThat(spinner.update(running, new Spinner.Msg.Tick(running.epoch(), running.nowNanos()))
                        .model())
                .as("a repeated time reading changes nothing")
                .isSameAs(running);
        Spinner.Model stopped = after(spinner, running, new Spinner.Msg.Stop());
        assertThat(spinner.update(stopped, new Spinner.Msg.Stop()).model())
                .as("stopping a stopped spinner changes nothing")
                .isSameAs(stopped);
    }

    // ── R-01/R-03: rendering, and the legacy spinner ──────────────────────────

    @Test
    void aFrameIsPaintedWithTheDefinitionStyle() {
        Style style = Style.NONE.withBold();
        Spinner spinner = spinner(Spinner.Config.preset(Spinner.Preset.DOT).withStyle(style));
        StyledText rendered = Spinner.render(origin(spinner, 0L), spinner.config());
        assertThat(rendered.spans()).hasSize(1);
        assertThat(rendered.style()).isEqualTo(style);
        assertThat(rendered.plainText()).isEqualTo(Spinner.Preset.DOT.frames().get(0));
        assertThat(Spinner.render(origin(spinner, 0L), null))
                .as("a missing definition paints the default one")
                .isEqualTo(Spinner.render(origin(spinner(Spinner.Config.defaults()), 0L), Spinner.Config.defaults()));
    }

    @Test
    void aMultilineFramePaintsEveryRow() {
        Spinner spinner = spinner(Spinner.Config.of(List.of("[ ]\n | ", "[-]\n/|\\"), Duration.ofMillis(100)));
        StyledText first = Spinner.render(origin(spinner, 0L), spinner.config());
        assertThat(first.plainText()).isEqualTo("[ ]\n | ");
        assertThat(first.lines()).as("two painted rows").hasSize(2);
        StyledText second = Spinner.render(at(spinner, origin(spinner, 0L), 100L), spinner.config());
        assertThat(second.plainText()).isEqualTo("[-]\n/|\\");

        // The mounted view paints the same rows.
        Spinner.Model model = at(spinner, origin(spinner, 0L), 100L);
        List<String> rows = painted(spinner.view(model, CONTEXT), 10);
        assertThat(rows.get(0).strip()).isEqualTo("[-]");
        assertThat(rows.get(1).strip()).isEqualTo("/|\\");
    }

    @Test
    void aNarrowWidthCutsWholeClustersInsteadOfSplittingThem() {
        Spinner spinner = spinner(Spinner.Preset.MOON);
        Spinner.Model model = origin(spinner, 0L);
        assertThat(Spinner.render(model, spinner.config(), 2).plainText())
                .as("two columns hold the wide glyph")
                .isEqualTo("\uD83C\uDF11");
        assertThat(Spinner.render(model, spinner.config(), 1).plainText())
                .as("one column cannot hold half a glyph")
                .isEmpty();
        assertThat(Spinner.render(model, spinner.config(), 0).isEmpty()).isTrue();
        assertThat(Spinner.render(model, spinner.config(), -5).isEmpty()).isTrue();
        StyledText clipped = Spinner.render(model, spinner.config(), 1);
        assertThat(clipped.columns()).isLessThanOrEqualTo(1);

        Spinner wide = spinner(Spinner.Config.of(List.of("wide text"), Duration.ofMillis(100)));
        assertThat(Spinner.render(origin(wide, 0L), wide.config(), 6).plainText())
                .as("an ASCII frame is cut by columns")
                .isEqualTo("wide t");
    }

    @Test
    void theLegacySpinnerIsUnchanged() {
        assertThat(Animation.spinnerFrames()).isEqualTo(4);
        assertThat(Animation.spinner(0L, 0L, Style.NONE).plainText()).isEqualTo("\u25C7");
        assertThat(Animation.spinner(249 * MILLIS, 0L, Style.NONE).plainText()).isEqualTo("\u25C7");
        assertThat(Animation.spinner(250 * MILLIS, 0L, Style.NONE).plainText()).isEqualTo("\u25C8");
        assertThat(Animation.spinner(500 * MILLIS, 0L, Style.NONE).plainText()).isEqualTo("\u25C6");
        assertThat(Animation.spinner(1_000 * MILLIS, 0L, Style.NONE).plainText())
                .isEqualTo("\u25C7");
        assertThat(Animation.spinner(0L, 5_000 * MILLIS, Style.NONE).plainText())
                .isEqualTo("\u25C7");
        assertThat(Animation.spinner(0L, 0L, null).style()).isEqualTo(Style.NONE);
    }

    @Test
    void theLegacySpinnerSelectsTheFrameTheSharedArithmeticSelects() {
        List<String> diamonds = List.of("\u25C7", "\u25C8", "\u25C6", "\u25C8");
        Spinner spinner = spinner(Spinner.Config.of(diamonds, Duration.ofMillis(250)));
        for (long now : List.of(0L, 1L, 249 * MILLIS, 250 * MILLIS, 999 * MILLIS, 4_000L * MILLIS)) {
            assertThat(Animation.spinner(now, 0L, Style.NONE).plainText())
                    .as("now %d", now)
                    .isEqualTo(Spinner.render(new Spinner.Model(true, 0L, 0L, now), spinner.config())
                            .plainText());
        }
        // Every clock pair still lands on one of the legacy frames, whatever the arithmetic does.
        List<Long> times = List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE);
        for (long now : times) {
            for (long started : times) {
                assertThat(Animation.spinner(now, started, Style.NONE).plainText())
                        .as("now %d started %d", now, started)
                        .isIn((Object[]) diamonds.toArray(String[]::new));
            }
        }
    }

    @Test
    void theMountedViewPaintsTheSameFrameAsTheDirectRender() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        Spinner.Model model = at(spinner, origin(spinner, 0L), 250L);
        assertThat(painted(spinner.view(model, CONTEXT), 10).get(0).strip())
                .isEqualTo(Spinner.render(model, spinner.config()).plainText());
        assertThat(painted(spinner.view(model, CONTEXT), 10).get(0).strip()).isEqualTo("-");
    }

    // ── R-04: the two-spinner demo ───────────────────────────────────────────

    @Test
    void theDemoPaintsTwoSpinnersAndOneHelpLine() {
        SpinnerDemo.Model model = start();
        assertThat(row(model, 0)).isEqualTo("left=line frame=1/4 run=on t=0ms");
        assertThat(row(model, 1)).isEqualTo("right=globe frame=1/3 run=on t=0ms");
        assertThat(row(model, 2)).isEqualTo("focus=left cadence=100ms mounted=on last=none");
        assertThat(row(model, 3)).isEmpty();
        assertThat(row(model, 4)).isEqualTo("left  |    right  \uD83C\uDF0D");
        List<String> lines = painted(demo().view(model, CONTEXT));
        assertThat(lines.get(6))
                .as("one help line rendered from the routed key map")
                .isEqualTo(
                        "\u2190 previous \u2022 \u2192 next \u2022 space run \u2022 c cadence \u2022 tab focus \u2022 x mount \u2022 esc quit");
    }

    @Test
    void theDemoAdvancesFromRuntimeTime() {
        SpinnerDemo.Model model = tick(tick(start(), true, 0L), false, 0L);
        assertThat(row(model, 0)).isEqualTo("left=line frame=1/4 run=on t=0ms");
        model = tick(tick(model, true, 300L), false, 450L);
        assertThat(row(model, 0)).isEqualTo("left=line frame=4/4 run=on t=300ms");
        assertThat(row(model, 1))
                .as("globe frames run at the scene's cadence")
                .isEqualTo("right=globe frame=2/3 run=on t=450ms");
        assertThat(row(model, 4)).isEqualTo("left  \\    right  \uD83C\uDF0E");
    }

    @Test
    void cyclingTheFocusedPresetRestartsOnlyThatSpinner() {
        SpinnerDemo.Model model = tick(tick(start(), true, 0L), false, 0L);
        model = tick(tick(model, true, 250L), false, 250L);
        String right = row(model, 1);
        SpinnerDemo.Model cycled = send(model, "right");
        assertThat(row(cycled, 0)).isEqualTo("left=dot frame=1/8 run=on t=0ms");
        assertThat(row(cycled, 1)).as("the other spinner kept its preset").isEqualTo(right);
        assertThat(row(cycled, 2)).contains("last=preset-dot");
        assertThat(row(cycled, 4)).isEqualTo("left  \u2801    right  \uD83C\uDF0F");

        assertThat(send(model, "left").left().preset())
                .as("cycling backwards wraps to the last preset")
                .isEqualTo(Spinner.Preset.MOON);
        assertThat(send(model, "h").left().preset())
                .as("the alternative alias routes the same action")
                .isEqualTo(Spinner.Preset.MOON);
        assertThat(send(model, "l").left().preset()).isEqualTo(Spinner.Preset.DOT);
    }

    @Test
    void spaceStopsAndStartsOnlyTheFocusedSpinner() {
        SpinnerDemo.Model model = tick(tick(start(), true, 0L), false, 0L);
        model = tick(tick(model, true, 250L), false, 250L);
        SpinnerDemo.Model stopped = send(model, "space");
        assertThat(row(stopped, 0)).isEqualTo("left=line frame=3/4 run=off t=250ms");
        assertThat(row(stopped, 2)).contains("last=stopped");
        assertThat(Subscription.leaves(demo().subscriptions(stopped)))
                .as("only the running spinner is subscribed")
                .hasSize(1);

        // The stopped spinner ignores the ticks that reach it and the other one keeps turning.
        SpinnerDemo.Model later = tick(tick(stopped, true, 900L), false, 900L);
        assertThat(row(later, 0)).as("a stopped spinner does not move").isEqualTo(row(stopped, 0));
        assertThat(row(later, 1)).isEqualTo("right=globe frame=1/3 run=on t=900ms");

        SpinnerDemo.Model restarted = send(later, "space");
        assertThat(row(restarted, 0)).isEqualTo("left=line frame=1/4 run=on t=0ms");
        assertThat(row(restarted, 2)).contains("last=started");
        SpinnerDemo.Model ticked = tick(restarted, true, 1_000L);
        assertThat(row(ticked, 0))
                .as("the first tick of the new cycle is its origin")
                .isEqualTo("left=line frame=1/4 run=on t=0ms");
        assertThat(row(tick(ticked, true, 1_100L), 0)).isEqualTo("left=line frame=2/4 run=on t=100ms");
    }

    @Test
    void tabMovesTheGesturesToTheOtherSpinner() {
        SpinnerDemo.Model model = send(start(), "tab");
        assertThat(row(model, 2)).startsWith("focus=right").contains("last=focus-right");
        SpinnerDemo.Model stopped = send(model, "space");
        assertThat(row(stopped, 1)).as("the second spinner stopped").contains("run=off");
        assertThat(row(stopped, 0)).as("the first spinner is untouched").contains("run=on");
        assertThat(row(send(model, "right"), 1))
                .as("the second spinner took the preset")
                .startsWith("right=moon");
        assertThat(row(send(model, "right"), 0)).startsWith("left=line");
    }

    @Test
    void theCadenceKeyRetimesBothSpinners() {
        SpinnerDemo.Model model = tick(tick(start(), true, 0L), false, 0L);
        model = tick(tick(model, true, 300L), false, 300L);
        assertThat(row(model, 0)).isEqualTo("left=line frame=4/4 run=on t=300ms");
        SpinnerDemo.Model faster = send(model, "c");
        assertThat(row(faster, 2)).contains("cadence=50ms").contains("last=cadence-50ms");
        assertThat(row(faster, 0))
                .as("the same elapsed time is a different frame at a faster cadence")
                .isEqualTo("left=line frame=3/4 run=on t=300ms");
        assertThat(faster.left().config(faster.cadence()).frameStepNanos()).isEqualTo(50 * MILLIS);
        assertThat(send(send(faster, "c"), "c").cadence())
                .as("the cadence cycles")
                .isEqualTo(SpinnerDemo.Cadence.NORMAL);
    }

    @Test
    void removingTheSecondSpinnerDropsItsMountAndRestoringStartsItStopped() {
        SpinnerDemo.Model model = tick(tick(start(), true, 0L), false, 0L);
        SpinnerDemo.Model removed = send(model, "x");
        assertThat(row(removed, 1)).isEqualTo("right=absent");
        assertThat(row(removed, 2)).contains("mounted=off").contains("last=removed");
        assertThat(row(removed, 4)).as("the removed spinner paints nothing").isEqualTo("left  |    right");
        assertThat(demo().children(removed).nodes())
                .as("one declared child left")
                .hasSize(1);
        assertThat(Subscription.leaves(demo().subscriptions(removed))).hasSize(1);
        assertThat(apply(removed, new SpinnerDemo.Msg.Right(new Spinner.Msg.Tick(0L, 1_000 * MILLIS)))
                        .right()
                        .model())
                .as("a message for an unmounted spinner changes nothing")
                .isSameAs(removed.right().model());

        SpinnerDemo.Model restored = send(removed, "x");
        assertThat(row(restored, 2)).contains("mounted=on").contains("last=restored");
        assertThat(row(restored, 1))
                .as("a restored spinner starts over, stopped, on the preset it had")
                .isEqualTo("right=globe frame=1/3 run=off t=0ms");
        assertThat(demo().children(restored).nodes()).hasSize(2);
    }

    @Test
    void everySceneGestureIsReachableFromTheRoutedKeyMap() {
        SpinnerDemo.Model model = start();
        assertThat(route(model, "right")).contains(new SpinnerDemo.Msg.CyclePreset(1));
        assertThat(route(model, "l")).contains(new SpinnerDemo.Msg.CyclePreset(1));
        assertThat(route(model, "left")).contains(new SpinnerDemo.Msg.CyclePreset(-1));
        assertThat(route(model, "space")).contains(new SpinnerDemo.Msg.ToggleRun());
        assertThat(route(model, "c")).contains(new SpinnerDemo.Msg.CycleCadence());
        assertThat(route(model, "tab")).contains(new SpinnerDemo.Msg.ToggleFocus());
        assertThat(route(model, "x")).contains(new SpinnerDemo.Msg.ToggleSecond());
        assertThat(route(model, "escape")).contains(new SpinnerDemo.Msg.Quit());
        assertThat(route(model, "q")).as("an unbound key reaches nothing").isEmpty();

        List<String> help = painted(Help.view(SpinnerDemo.defaultKeys(), true, 200), 8, 200).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(help).hasSize(2);
        assertThat(help.get(0))
                .contains("previous")
                .contains("next")
                .contains("run")
                .contains("cadence");
        assertThat(help.get(1)).contains("focus").contains("mount").contains("quit");
    }

    @Test
    void aNarrowSceneClipsInsteadOfWrapping() {
        List<String> lines = painted(demo().view(start(), CONTEXT), 40);
        // Every status row stays on its own row however narrow the terminal is, so the glyphs and the
        // help do not move down and a verifier reads the same rows at any width.
        assertThat(lines.get(0).strip()).isEqualTo("left=line frame=1/4 run=on t=0ms");
        assertThat(lines.get(1).strip()).isEqualTo("right=globe frame=1/3 run=on t=0ms");
        assertThat(lines.get(2)).startsWith("focus=left cadence=100ms mounted=on");
        assertThat(lines.get(3)).isEmpty();
        assertThat(lines.get(4)).startsWith("left  |");
        assertThat(lines.get(6)).as("the help is cut, not wrapped").startsWith("\u2190 previous");
    }

    @Test
    void escapeQuitsTheDemo() {
        Update<SpinnerDemo.Model, SpinnerDemo.Msg, Never> update = demo().update(start(), new SpinnerDemo.Msg.Quit());
        assertThat(update.command()).isInstanceOf(Command.Quit.class);
        assertThat(update.outputs()).isEmpty();
    }

    @Test
    void theDemoRunsUnderTheDeterministicDriver() {
        ComponentDriver<SpinnerDemo.Model, SpinnerDemo.Msg, Never> driver =
                ComponentDriver.initialize(demo(), new InitContext(null, null));
        driver.send(new SpinnerDemo.Msg.Left(new Spinner.Msg.Tick(0L, 0L)));
        driver.send(new SpinnerDemo.Msg.Right(new Spinner.Msg.Tick(0L, 0L)));
        driver.send(new SpinnerDemo.Msg.Left(new Spinner.Msg.Tick(0L, 350 * MILLIS)));
        assertThat(driver.screen(CONTEXT).plainLine(0)).isEqualTo("left=line frame=4/4 run=on t=350ms");
        assertThat(Subscription.leaves(driver.subscriptions()))
                .as("both mounted spinners are subscribed")
                .hasSize(2);
        assertThat(driver.children().nodes()).hasSize(2);
    }

    // ── R-02/R-04: mounted lifetimes, proved by the ticks that stop ───────────

    @Test
    void twoMountedSpinnersTickIndependentlyAndUnmountingClosesTheSource() {
        SpinnerDemo.Scene scene = demo();
        SpinnerDemo.Model model = scene.init(new InitContext(null, null)).model();
        ManualTimers timers = new ManualTimers();
        Mailbox mailbox = new Mailbox(16);
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        SubscriptionManager<SpinnerDemo.Msg> manager = new SubscriptionManager<>(mailbox, timers, 8, diagnostics::add);
        try {
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).as("one interval per running spinner").isEqualTo(2);

            timers.advance(100 * MILLIS);
            List<SpinnerDemo.Msg> ticks = drain(mailbox, 2);
            assertThat(ticks).hasSize(2);
            assertThat(ticks).anySatisfy(value -> assertThat(value).isInstanceOf(SpinnerDemo.Msg.Left.class));
            assertThat(ticks).anySatisfy(value -> assertThat(value).isInstanceOf(SpinnerDemo.Msg.Right.class));
            for (SpinnerDemo.Msg message : ticks) {
                model = scene.update(model, message).model();
            }
            assertThat(model.left().model().armed())
                    .as("the tick recorded the origin")
                    .isFalse();
            assertThat(model.right().model().armed()).isFalse();
            assertThat(model.left().model().originNanos()).isNotEqualTo(Spinner.UNSET_ORIGIN);

            // Stopping the first spinner removes exactly its source.
            model = scene.update(model, new SpinnerDemo.Msg.ToggleRun()).model();
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).isEqualTo(1);
            timers.advance(100 * MILLIS);
            List<SpinnerDemo.Msg> afterStop = drain(mailbox, 1);
            assertThat(afterStop).hasSize(1);
            assertThat(afterStop.get(0)).isInstanceOf(SpinnerDemo.Msg.Right.class);

            // Unmounting the second one closes the last source: no spinner keeps ticking.
            model = scene.update(model, new SpinnerDemo.Msg.ToggleSecond()).model();
            manager.reconcile(scene.subscriptions(model));
            assertThat(timers.pending()).as("a removed mount holds no timer").isZero();
            timers.advance(1_000 * MILLIS);
            assertThat(drain(mailbox, 1)).as("no source is left to emit").isEmpty();
            assertThat(diagnostics).isEmpty();
        } finally {
            manager.closeAll();
            mailbox.close();
        }
    }

    @Test
    void everySpinnerSourceIsScopedToItsOwnMount() {
        SpinnerDemo.Model model = start();
        List<Subscription<SpinnerDemo.Msg>> leaves = Subscription.leaves(demo().subscriptions(model));
        assertThat(leaves).hasSize(2);
        assertThat(leaves.stream().map(leaf -> leaf.id().path().toString()).toList())
                .as("two intervals under two mount paths")
                .containsExactly("/left", "/right");
        assertThat(leaves.stream().map(leaf -> leaf.id().value()).toList()).containsOnly("tick");
        assertThat(leaves)
                .allSatisfy(leaf -> assertThat(((Subscription.Interval<?>) leaf).period())
                        .as("the period is the definition's cadence")
                        .isEqualTo(Duration.ofMillis(100)));

        SpinnerDemo.Model stopped =
                apply(apply(model, new SpinnerDemo.Msg.ToggleRun()), new SpinnerDemo.Msg.ToggleFocus());
        stopped = apply(stopped, new SpinnerDemo.Msg.ToggleRun());
        assertThat(Subscription.leaves(demo().subscriptions(stopped)))
                .as("two stopped spinners declare no source at all")
                .isEmpty();
    }

    @Test
    void aSpinnerDeclaresATickOnlyWhileItRuns() {
        Spinner spinner = spinner(Spinner.Preset.LINE);
        assertThat(Subscription.leaves(spinner.subscriptions(Spinner.Model.stopped())))
                .as("a stopped spinner is not a source")
                .isEmpty();
        List<Subscription<Spinner.Msg>> running = Subscription.leaves(spinner.subscriptions(Spinner.Model.starting()));
        assertThat(running).hasSize(1);
        Subscription.Interval<Spinner.Msg> interval = (Subscription.Interval<Spinner.Msg>) running.get(0);
        assertThat(interval.period()).isEqualTo(Spinner.Config.defaults().frameDuration());

        // The mapper carries the epoch of the model it was declared from, which is what makes a stale
        // tick recognisable after the cycle it belonged to ended.
        Spinner.Model cycle = new Spinner.Model(true, 7L, 0L, 0L);
        Subscription.Interval<Spinner.Msg> mapped = (Subscription.Interval<Spinner.Msg>)
                Subscription.leaves(spinner.subscriptions(cycle)).get(0);
        assertThat(mapped.onTick().apply(5_000L)).isEqualTo(new Spinner.Msg.Tick(7L, 5_000L));
    }

    /** Every message one advance left in the mailbox, up to a count. */
    private static List<SpinnerDemo.Msg> drain(Mailbox mailbox, int expected) {
        List<SpinnerDemo.Msg> messages = new ArrayList<>();
        for (int index = 0; index < expected; index++) {
            try {
                Mailbox.Envelope envelope = mailbox.poll(100L);
                if (envelope == null) {
                    break;
                }
                assertThat(envelope).isInstanceOf(Mailbox.AppMessage.class);
                messages.add((SpinnerDemo.Msg) ((Mailbox.AppMessage) envelope).payload());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return messages;
    }
}
