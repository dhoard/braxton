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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A configurable spinner: a named or custom frame definition, and the model time that selects one.
 *
 * <p>A {@link Config} is the whole definition - an immutable frame list, the time one frame lasts,
 * and the style the frame is painted with - so two spinners on one screen can run different
 * frames at different cadences without any shared state. {@link Preset} names the bundled
 * definitions; {@link Config#of} takes frames of the application's own, including multi-row ones.</p>
 *
 * <h2>Frames and validation</h2>
 *
 * <p>A definition is validated where it is made, not when it is painted: an empty frame list, an
 * empty or null frame, a frame carrying a control character other than the line break that makes
 * it multi-row, and a frame duration that is zero, negative or too large to express in
 * nanoseconds are all rejected with an {@link IllegalArgumentException}. Rejecting control text
 * keeps a frame from injecting a terminal escape sequence, and rejecting a tab keeps a frame's
 * painted width independent of where the row happens to start.</p>
 *
 * <p>{@link Config#frameCount()}, {@link Config#rows()} and {@link Config#columns()} are the
 * definition's sizing facts: the number of frames, and the largest painted box any frame needs.
 * A caller that has to reserve space before it paints reads those facts instead of measuring a
 * rendered value. Frames of one definition should have the same width for a still layout, but the
 * widget does not require it - {@link #render} paints the frame as it is defined.</p>
 *
 * <h2>Time and state</h2>
 *
 * <p>A {@link Model} is a value: whether the spinner is running, the epoch of the current cycle,
 * the origin that cycle is measured from, and the last monotonic time the runtime reported. The
 * frame is never stored; {@link Model#frameIndex} derives it from the elapsed time, so equal
 * model and time always paint equal frames, and a stalled runtime that delivers one tick after a
 * long gap lands on the right frame instead of replaying the ticks it missed.</p>
 *
 * <p>The widget reads no clock of its own. {@link Msg.Start} subscribes and arms the cycle; the
 * first {@link Msg.Tick} then records that runtime-supplied time as the origin, which is what
 * makes a restart begin at frame zero without the application having to know the time.
 * {@link Msg.Stop} keeps the frame it was showing and drops the subscription, so a stopped
 * spinner neither paints a different frame nor keeps a timer alive. {@link Msg.Reset} starts the
 * cycle over from its current time, which is what a caller sends after changing the definition so
 * that a shorter or longer frame list does not continue from an unrelated phase.</p>
 *
 * <p>Every reduction that changes the cycle increments {@link Model#epoch()}, and a tick carries
 * the epoch it was mapped with. A tick that arrives after a stop, restart or reset therefore
 * matches no epoch and is ignored, returning the incoming instance: an old tick can never
 * advance a newer animation. Elapsed arithmetic saturates instead of overflowing, and a runtime
 * time before the origin clamps to frame zero rather than producing a negative index.</p>
 *
 * <h2>Mounting</h2>
 *
 * <p>As a component, a spinner declares exactly one scoped {@link Subscription.Interval} while it
 * is running, in whatever {@code SubscriptionId} namespace its mount scopes it to, and none while
 * it is stopped. There is no per-widget timer, thread or executor, and unmounting closes the
 * source with the component's scope, so a removed spinner cannot keep ticking. The interval's
 * period is the definition's frame duration; a changed cadence is a changed configuration, which
 * the runtime reconciles as a new source.</p>
 *
 * <p>The widget owns no keys and no focus: it is presentation and timing. Which preset is shown,
 * when a spinner starts or stops, and every terminal gesture that does so belong to the owning
 * application, which is what lets one screen drive two spinners independently. {@link
 * #init} mounts a spinner running, because a spinner that is mounted is normally a spinner the
 * application wants to show.</p>
 */
public final class Spinner implements Component<Spinner.Model, Spinner.Msg, Never> {

    /** The cadence of a definition that does not name its own. */
    public static final Duration DEFAULT_FRAME_DURATION = Duration.ofMillis(100);

    /** The preset a definition without one uses. */
    public static final Preset DEFAULT_PRESET = Preset.LINE;

    /**
     * The origin of a cycle whose first tick has not arrived yet, so no elapsed time is known.
     *
     * <p>A monotonic reading of exactly this value is not a usable origin; every real origin is
     * greater, because the runtime's clock counts upwards from zero.</p>
     */
    public static final long UNSET_ORIGIN = Long.MIN_VALUE;

    /** The scoped identity of a mounted spinner's tick source. */
    private static final SubscriptionId TICK = SubscriptionId.of("tick");

    /**
     * The bundled frame definitions.
     *
     * <p>Each preset is one cycle of frames and the cadence it is designed for: a spinning line,
     * a dot travelling around a braille cell, a pulsing block, a rotating globe and the moon's
     * phases. The frames are Braxton's own; the two emoji presets are two columns wide, which is
     * what the sizing facts report.</p>
     */
    public enum Preset {
        /** Four ASCII strokes: a line that rotates. */
        LINE("line", Duration.ofMillis(100), "|", "/", "-", "\\"),

        /** A single braille dot travelling around one cell. */
        DOT(
                "dot",
                Duration.ofMillis(80),
                "\u2801",
                "\u2802",
                "\u2804",
                "\u2840",
                "\u2880",
                "\u2820",
                "\u2810",
                "\u2808"),

        /** A block that brightens and fades. */
        PULSE("pulse", Duration.ofMillis(120), "\u2591", "\u2592", "\u2593", "\u2588", "\u2593", "\u2592"),

        /** The globe's three longitude views. */
        GLOBE("globe", Duration.ofMillis(200), "\uD83C\uDF0D", "\uD83C\uDF0E", "\uD83C\uDF0F"),

        /** The moon's eight phases. */
        MOON(
                "moon",
                Duration.ofMillis(120),
                "\uD83C\uDF11",
                "\uD83C\uDF12",
                "\uD83C\uDF13",
                "\uD83C\uDF14",
                "\uD83C\uDF15",
                "\uD83C\uDF16",
                "\uD83C\uDF17",
                "\uD83C\uDF18");

        private final String label;
        private final Duration frameDuration;
        private final List<String> frames;

        Preset(String label, Duration frameDuration, String... frames) {
            this.label = label;
            this.frameDuration = frameDuration;
            this.frames = List.of(frames);
        }

        /**
         * The preset's lower-case name, for a status row or a configuration file.
         *
         * @return the label
         */
        public String label() {
            return label;
        }

        /**
         * The cadence this preset is designed for.
         *
         * @return the time one frame lasts
         */
        public Duration frameDuration() {
            return frameDuration;
        }

        /**
         * The preset's frames, in cycle order.
         *
         * @return the immutable frame list
         */
        public List<String> frames() {
            return frames;
        }

        /**
         * This preset's definition.
         *
         * @return the configuration
         */
        public Config config() {
            return new Config(frames, frameDuration, Style.NONE);
        }

        /**
         * Another preset of the cycle, {@code steps} presets away and wrapped into it.
         *
         * @param steps how far to move; negative moves towards the first preset
         * @return the selected preset
         */
        public Preset shift(int steps) {
            Preset[] presets = values();
            return presets[Math.floorMod(ordinal() + steps, presets.length)];
        }
    }

    /**
     * An immutable spinner definition.
     *
     * <p>The frames, the time one frame lasts and the style are the whole definition. Validation
     * happens here, so a definition that exists is paint-able: the frame list is not empty, every
     * frame is non-empty text without control characters other than its own line breaks, and the
     * frame duration is strictly positive and expressible in nanoseconds.</p>
     *
     * @param frames the frames in cycle order; copied, non-empty, each one non-empty text
     * @param frameDuration the time one frame lasts; strictly positive
     * @param style the style every frame is painted with; null is {@link Style#NONE}
     */
    public record Config(List<String> frames, Duration frameDuration, Style style) {

        /** Copies and validates the frames and the cadence and substitutes the plain style. */
        public Config {
            frames = checkedFrames(frames);
            frameDuration = checkedDuration(frameDuration);
            style = style == null ? Style.NONE : style;
        }

        /**
         * The default definition: the {@link Preset#LINE} frames at their own cadence.
         *
         * @return the default configuration
         */
        public static Config defaults() {
            return DEFAULT_PRESET.config();
        }

        /**
         * A preset's own definition.
         *
         * @param preset the preset; null is {@link #DEFAULT_PRESET}
         * @return the configuration
         */
        public static Config preset(Preset preset) {
            return (preset == null ? DEFAULT_PRESET : preset).config();
        }

        /**
         * A custom definition with the default cadence and no styling.
         *
         * @param frames the frames in cycle order; validated as the canonical constructor does
         * @return the configuration
         */
        public static Config of(List<String> frames) {
            return new Config(frames, DEFAULT_FRAME_DURATION, Style.NONE);
        }

        /**
         * A custom definition with its own cadence and no styling.
         *
         * @param frames the frames in cycle order
         * @param frameDuration the time one frame lasts
         * @return the configuration
         */
        public static Config of(List<String> frames, Duration frameDuration) {
            return new Config(frames, frameDuration, Style.NONE);
        }

        /**
         * The same definition with different frames.
         *
         * @param value the frames in cycle order; validated
         * @return the new definition
         */
        public Config withFrames(List<String> value) {
            return new Config(value, frameDuration, style);
        }

        /**
         * The same definition with a different cadence.
         *
         * @param value the time one frame lasts; strictly positive
         * @return the new definition
         */
        public Config withFrameDuration(Duration value) {
            return new Config(frames, value, style);
        }

        /**
         * The same definition painted with a different style.
         *
         * @param value the style of every frame; null is {@link Style#NONE}
         * @return the new definition
         */
        public Config withStyle(Style value) {
            return new Config(frames, frameDuration, value);
        }

        /**
         * How many frames one cycle has.
         *
         * @return the frame count, always positive
         */
        public int frameCount() {
            return frames.size();
        }

        /**
         * The rows the largest frame needs.
         *
         * @return the row count of the tallest frame, at least one
         */
        public int rows() {
            int rows = 1;
            for (String frame : frames) {
                int lines = 1;
                for (int index = 0; index < frame.length(); index++) {
                    if (frame.charAt(index) == '\n') {
                        lines++;
                    }
                }
                rows = Math.max(rows, lines);
            }
            return rows;
        }

        /**
         * The display columns the widest frame needs.
         *
         * @return the column count of the widest row of any frame
         */
        public int columns() {
            int columns = 0;
            for (String frame : frames) {
                for (String row : frame.split("\n", -1)) {
                    columns = Math.max(columns, TextMeasure.columns(row));
                }
            }
            return columns;
        }

        /**
         * How long one frame lasts, in nanoseconds.
         *
         * @return the positive step every frame is selected with
         */
        public long frameStepNanos() {
            return frameDuration.toNanos();
        }
    }

    /**
     * Immutable spinner state.
     *
     * <p>The frame is derived from {@code (originNanos, nowNanos)} rather than stored, so a model
     * and a time always paint the same frame and nothing has to be advanced in step with a
     * timer. {@code originNanos} is {@link #UNSET_ORIGIN} until the first tick of the current
     * cycle records the runtime's own time as the origin.</p>
     *
     * @param running whether the spinner is subscribed to runtime time
     * @param epoch the identity of the current cycle; a tick carrying another one is stale
     * @param originNanos the time the current cycle is measured from, or {@link #UNSET_ORIGIN}
     * @param nowNanos the last monotonic time the runtime reported for this spinner
     */
    public record Model(boolean running, long epoch, long originNanos, long nowNanos) {

        /**
         * A spinner that has never run: stopped, with no cycle and frame zero.
         *
         * @return the starting state of a spinner the application starts itself
         */
        public static Model stopped() {
            return new Model(false, 0L, UNSET_ORIGIN, 0L);
        }

        /**
         * A running spinner whose first tick records the origin: mounted, showing frame zero.
         *
         * @return the state {@link Spinner#init} starts from
         */
        public static Model starting() {
            return new Model(true, 0L, UNSET_ORIGIN, 0L);
        }

        /**
         * Whether a running spinner is waiting for its first tick to name an origin.
         *
         * <p>A stopped spinner has no cycle to arm, however it was stopped.</p>
         *
         * @return whether the spinner is running with no origin recorded yet
         */
        public boolean armed() {
            return running && originNanos == UNSET_ORIGIN;
        }

        /**
         * The time the current cycle has run for, saturated and never negative.
         *
         * @return the elapsed nanoseconds, zero before the first tick of a cycle
         */
        public long elapsedNanos() {
            return originNanos == UNSET_ORIGIN ? 0L : elapsed(originNanos, nowNanos);
        }

        /**
         * The zero-based frame this state paints under a definition.
         *
         * @param config the definition; required
         * @return the frame index inside the definition's frame list
         */
        public int frameIndex(Config config) {
            Objects.requireNonNull(config, "config");
            return Spinner.frameIndex(config.frameCount(), config.frameStepNanos(), elapsedNanos());
        }
    }

    /** One lifecycle gesture or one runtime time reading. */
    public sealed interface Msg {

        /**
         * Starts or restarts the spinner, subscribing to runtime time.
         *
         * <p>The next tick records the new origin, so the first frame painted by the new cycle is
         * frame zero. Starting a spinner that is already running changes nothing.</p>
         */
        record Start() implements Msg {}

        /**
         * Stops the spinner, keeping the frame it is showing and dropping the subscription.
         *
         * <p>Stopping a spinner that is already stopped changes nothing.</p>
         */
        record Stop() implements Msg {}

        /**
         * Starts the current cycle over from the next tick, keeping the running state.
         *
         * <p>This is what a caller sends after changing the definition, so the new frame list
         * begins at frame zero instead of continuing the phase of the old one.</p>
         */
        record Reset() implements Msg {}

        /**
         * One runtime time reading.
         *
         * @param epoch the cycle the reading belongs to; a tick from another cycle is ignored
         * @param nowNanos the runtime's monotonic time, in nanoseconds
         */
        record Tick(long epoch, long nowNanos) implements Msg {}
    }

    /** A reduction result. The spinner has no semantic outputs, so the intent is always absent. */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    private final Config config;

    /**
     * A spinner with a definition.
     *
     * @param config the immutable definition; required
     */
    public Spinner(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * This spinner's definition.
     *
     * @return the configuration
     */
    public Config config() {
        return config;
    }

    /**
     * The time between two monotonic readings, saturated and never negative.
     *
     * @param startedNanos the time the interval started, in nanoseconds
     * @param nowNanos the current time, in nanoseconds
     * @return the elapsed nanoseconds; zero when the clock has not advanced
     */
    public static long elapsed(long startedNanos, long nowNanos) {
        long delta;
        try {
            delta = Math.subtractExact(nowNanos, startedNanos);
        } catch (ArithmeticException overflow) {
            // The difference of two clock readings can overflow even though both readings are
            // valid; saturating keeps the frame index meaningful instead of wrapping it.
            delta = nowNanos > startedNanos ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return Math.max(0L, delta);
    }

    /**
     * The frame an elapsed time lands on, wrapped into the cycle.
     *
     * @param frameCount the number of frames in the cycle; must be positive
     * @param frameStepNanos the time one frame lasts; zero or less is treated as one nanosecond
     * @param elapsedNanos the elapsed time, in nanoseconds; may be negative
     * @return the frame index in {@code [0, frameCount)}
     */
    public static int frameIndex(int frameCount, long frameStepNanos, long elapsedNanos) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("a spinner needs at least one frame");
        }
        // A definition cannot ask for a zero step (the duration is validated), but a caller of this
        // helper can, and dividing by zero would be the only way to make it fail.
        long step = Math.max(1L, frameStepNanos);
        return (int) Math.floorMod(elapsedNanos / step, (long) frameCount);
    }

    /**
     * The frame an elapsed time paints.
     *
     * @param frames the frames in cycle order; must not be empty
     * @param frameStepNanos the time one frame lasts; zero or less is treated as one nanosecond
     * @param elapsedNanos the elapsed time, in nanoseconds; may be negative
     * @return the frame text
     */
    public static String frameAt(List<String> frames, long frameStepNanos, long elapsedNanos) {
        Objects.requireNonNull(frames, "frames");
        return frames.get(frameIndex(frames.size(), frameStepNanos, elapsedNanos));
    }

    /**
     * Paints the frame this state selects, at the definition's full size.
     *
     * @param model the spinner state; required
     * @param config the definition; null is {@link Config#defaults()}
     * @return the styled frame
     */
    public static StyledText render(Model model, Config config) {
        return render(model, config, Integer.MAX_VALUE);
    }

    /**
     * Paints the frame this state selects, cut to an available width.
     *
     * <p>Every row of a multi-row frame is cut by display columns and never through a grapheme
     * cluster, so a wide glyph is dropped whole rather than half painted. A width of zero or less
     * paints nothing.</p>
     *
     * @param model the spinner state; required
     * @param config the definition; null is {@link Config#defaults()}
     * @param width the available display columns
     * @return the styled frame, never wider than {@code width}
     */
    public static StyledText render(Model model, Config config, int width) {
        Objects.requireNonNull(model, "model");
        Config look = config == null ? Config.defaults() : config;
        if (width <= 0) {
            return StyledText.empty();
        }
        String frame = frameAt(look.frames(), look.frameStepNanos(), model.elapsedNanos());
        String[] rows = frame.split("\n", -1);
        StringBuilder painted = new StringBuilder(frame.length());
        for (int row = 0; row < rows.length; row++) {
            if (row > 0) {
                painted.append('\n');
            }
            painted.append(cut(rows[row], width));
        }
        return StyledText.of(painted.toString(), look.style());
    }

    /** One row of a frame, cut to the column budget without splitting a cluster. */
    private static String cut(String row, int width) {
        return TextMeasure.columns(row) <= width ? row : TextMeasure.truncate(row, width);
    }

    /** Copies a frame list and rejects every definition a spinner cannot paint. */
    private static List<String> checkedFrames(List<String> frames) {
        Objects.requireNonNull(frames, "frames");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("a spinner needs at least one frame");
        }
        List<String> checked = new ArrayList<>(frames.size());
        for (String frame : frames) {
            checked.add(checkedFrame(frame));
        }
        return List.copyOf(checked);
    }

    /** One frame: non-empty text whose only control character is the break between its rows. */
    private static String checkedFrame(String frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.isEmpty()) {
            throw new IllegalArgumentException("a spinner frame must not be empty");
        }
        for (int index = 0; index < frame.length(); index++) {
            char value = frame.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 < frame.length() && Character.isLowSurrogate(frame.charAt(index + 1))) {
                    index++;
                    continue;
                }
                throw new IllegalArgumentException("a spinner frame must not contain an unpaired surrogate");
            }
            if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("a spinner frame must not contain an unpaired surrogate");
            }
            // A line break is how a frame becomes multi-row. Everything else a terminal would treat
            // as a command - or a tab, whose painted width depends on where the row starts - is a
            // definition error rather than something to strip silently.
            if (value != '\n' && (value < 0x20 || value == 0x7F || (value >= 0x80 && value <= 0x9F))) {
                throw new IllegalArgumentException("a spinner frame must not contain control characters");
            }
        }
        return frame;
    }

    /** A cadence a frame can be selected with. */
    private static Duration checkedDuration(Duration duration) {
        Objects.requireNonNull(duration, "frameDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("frame duration must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("frame duration must fit in nanoseconds: " + duration, overflow);
        }
        return duration;
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        // A mounted spinner is a spinner the application wants to show, and the first tick names the
        // origin, so starting needs no clock reading here.
        return Update.next(Model.starting());
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        // The spinner has no focus of its own, so the reduced state is the whole answer.
        return WidgetUpdate.settled(
                model, message, action -> new Result(reduce(model, action), null), UnaryOperator.identity());
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        if (!model.running()) {
            return Subscription.none();
        }
        // The interval is the only timer: one tick per frame step, scoped to this mount, and closed
        // when the spinner stops or is unmounted. The epoch is captured so a tick mapped before a
        // stop or restart is recognisable afterwards.
        long epoch = model.epoch();
        return Subscription.interval(TICK, config.frameDuration(), time -> new Msg.Tick(epoch, time));
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return Views.sized(size -> Views.text(render(model, config, size.cols())));
    }

    /** Applies one lifecycle gesture or one time reading. Never performs I/O. */
    private static Model reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Start start ->
                state.running() ? state : new Model(true, state.epoch() + 1, UNSET_ORIGIN, state.nowNanos());
            // Stopping keeps the origin and the last time, which is exactly the frame being shown.
            case Msg.Stop stop ->
                state.running() ? new Model(false, state.epoch() + 1, state.originNanos(), state.nowNanos()) : state;
            case Msg.Reset reset -> new Model(state.running(), state.epoch() + 1, UNSET_ORIGIN, state.nowNanos());
            case Msg.Tick tick -> ticked(state, tick);
        };
    }

    /** One time reading: stale cycles are ignored, and the first reading of a cycle is its origin. */
    private static Model ticked(Model state, Msg.Tick tick) {
        if (!state.running() || tick.epoch() != state.epoch()) {
            return state;
        }
        if (state.originNanos() == UNSET_ORIGIN) {
            return new Model(true, state.epoch(), tick.nowNanos(), tick.nowNanos());
        }
        return tick.nowNanos() == state.nowNanos()
                ? state
                : new Model(true, state.epoch(), state.originNanos(), tick.nowNanos());
    }
}
