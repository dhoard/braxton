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
import io.github.dhoard.braxton.asset.ImageAsset;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.image.ImageOutput;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Animation;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.ProgressBar;
import io.github.dhoard.braxton.widget.Spinner;
import io.github.dhoard.braxton.widget.Transition;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;

/**
 * Phase 30: pure transitions, reduced motion and a composed image fallback.
 */
public final class RichOutputDemo {
    /**
     * The duration of one rich-output demo transition.
     */
    public static final Duration DURATION = Duration.ofMillis(600);

    private static final SubscriptionId TICK = SubscriptionId.of("rich-output-tick");
    private static final Animation.Sequence FRAMES = new Animation.Sequence(
            List.of(StyledText.plain("a"), StyledText.plain("b"), StyledText.plain("c")), Duration.ofMillis(100));

    private RichOutputDemo() {}

    /**
     * The transition timeline, last monotonic reading, and move count.
     *
     * @param transition the current animation transition
     * @param nowNanos the caller-supplied monotonic time in nanoseconds
     * @param moves the number of target changes
     */
    public record Model(Transition transition, long nowNanos, int moves) {
        /**
         * Tests whether the transition uses reduced motion.
         *
         * @return {@code true} if the transition uses reduced motion; otherwise {@code false}
         */
        public boolean reducedMotion() {
            return transition.reducedMotion();
        }

        /**
         * Samples the transition at the model's last monotonic reading.
         *
         * @return samples the transition at the model's last monotonic reading
         */
        public double value() {
            return transition.valueAt(nowNanos);
        }

        /**
         * Formats the model facts as a plain status row for terminal verification.
         *
         * @return formats the model facts as a plain status row for terminal verification
         */
        public String status() {
            return "rich output motion=" + (reducedMotion() ? "reduced" : "normal")
                    + " value=" + Math.round(value() * 100)
                    + " target=" + Math.round(transition.to() * 100)
                    + " state=" + (transition.finishedAt(nowNanos) ? "settled" : "moving") + " moves=" + moves;
        }
    }

    /**
     * Typed animation and reduced-motion messages for the rich-output demo.
     */
    public sealed interface Msg {
        /**
         * Requests a new transition target.
         */
        record Animate() implements Msg {}

        /**
         * Starts the transition at the supplied monotonic instant.
         *
         * @param nowNanos the caller-supplied monotonic time in nanoseconds
         */
        record Start(long nowNanos) implements Msg {}

        /**
         * Reports a monotonic animation reading.
         *
         * @param nowNanos the caller-supplied monotonic time in nanoseconds
         */
        record Tick(long nowNanos) implements Msg {}

        /**
         * Toggles reduced motion.
         */
        record ToggleMotion() implements Msg {}

        /**
         * Requests application exit.
         */
        record Quit() implements Msg {}
    }

    /**
     * Returns the default typed actions for this demo.
     *
     * @return the default typed actions for this demo
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(KeyMap.group(
                "Rich output",
                KeyAction.<Msg>of("animate", new Msg.Animate(), "space", "animate", "space"),
                KeyAction.<Msg>of("motion", new Msg.ToggleMotion(), "m", "reduced motion", "m"),
                KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "escape")));
    }

    /**
     * The rich-output demo component composing a transition with an image fallback.
     */
    public static final class Scene implements Component<Model, Msg, Never> {
        private final ImageOutput image;

        /**
         * Creates the scene with its default configuration.
         */
        public Scene() {
            BufferedImage raster = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 16; x++) {
                    raster.setRGB(x, y, 0xff000000 | ((x * 17) << 16) | ((y * 36) << 8) | 128);
                }
            image = ImageOutput.of(ImageAsset.of(raster));
        }

        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(new Model(Transition.between(0, 0, 0, DURATION), 0, 0));
        }

        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Animate ignored -> Update.next(model, Command.monotonicTime(Msg.Start::new));
                case Msg.Start start -> {
                    double target = model.transition().to() == 0 ? 1 : 0;
                    yield Update.next(new Model(
                            model.transition()
                                    .retarget(target, start.nowNanos())
                                    .withEasing(Transition.Easing.EASE_IN_OUT),
                            start.nowNanos(),
                            model.moves() + 1));
                }
                case Msg.Tick tick ->
                    model.transition().finishedAt(model.nowNanos())
                            ? Update.next(model)
                            : Update.next(new Model(model.transition(), tick.nowNanos(), model.moves()));
                case Msg.ToggleMotion ignored -> {
                    boolean reduced = !model.reducedMotion();
                    // Switching back to normal motion keeps the settled image instead of replaying
                    // a timeline the reduced-motion setting already finished.
                    double from = reduced ? model.value() : model.transition().to();
                    Transition next = Transition.between(
                                    from, model.transition().to(), model.nowNanos(), DURATION)
                            .withEasing(Transition.Easing.EASE_IN_OUT)
                            .withReducedMotion(reduced);
                    yield Update.next(new Model(next, model.nowNanos(), model.moves()));
                }
                case Msg.Quit ignored -> Update.next(model, Command.quit());
            };
        }

        public List<Binding<Msg>> claims(Model model) {
            return defaultKeys().bindings();
        }

        public Subscription<Msg> subscriptions(Model model) {
            return model.transition().finishedAt(model.nowNanos())
                    ? Subscription.none()
                    : Subscription.interval(TICK, Duration.ofMillis(16), Msg.Tick::new);
        }

        public Element<Msg> view(Model model, ViewContext context) {
            int columns = Math.max(1, Math.min(30, context.viewport().cols()));
            long elapsed = Spinner.elapsed(model.transition().startedNanos(), model.nowNanos());
            return Elements.column(
                    Elements.text("Braxton rich output"),
                    Elements.text(model.status()),
                    Elements.text(ProgressBar.of(columns).render(model.value(), 1)),
                    Elements.text("frame="
                            + FRAMES.frameAt(elapsed, model.reducedMotion()).plainText()),
                    Elements.text("image fallback=half-block"),
                    image.fit(Math.max(1, context.viewport().cols()), 8).<Msg>element(),
                    Elements.text("space animate / m reduced motion / q quit"));
        }
    }

    /**
     * Runs the demo and returns its structured exit result.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Launches the interactive rich output demo.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) result.failure().printStackTrace(System.err);
        System.exit(result.status());
    }
}
