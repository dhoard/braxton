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
import io.github.dhoard.braxton.app.MessageFilter;
import io.github.dhoard.braxton.app.ProgramHandle;
import io.github.dhoard.braxton.app.ProgramResult;
import io.github.dhoard.braxton.app.QuitRequest;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Phase 22 parity demo: command ordering and application control.
 *
 * <p>The scene is driven from outside as well as from the keyboard, which is what a program handle is
 * for:</p>
 *
 * <ul>
 *   <li>an owned producer thread sends {@code Msg.Progress} through {@link ProgramHandle#trySend} every
 *       {@value #PRODUCER_INTERVAL_MILLIS} ms, so the row counts messages no key produced;</li>
 *   <li>{@code s} returns one {@link Command.Sequence}, and the row records the order its messages
 *       arrive in - {@code a}, the delay's own {@code tick-a}, {@code b}, the update-ordered
 *       continuation's {@code b-done}, and {@code c} - so the sequence is visible as executed rather
 *       than merely submitted;</li>
 *   <li>{@code q} asks to quit and is <em>declined</em>: the session's {@link MessageFilter} guards the
 *       exit, so the prompt appears and the program keeps running until {@code y} confirms.</li>
 * </ul>
 *
 * <p>After the session has restored the terminal, the program prints the final model it read from
 * {@link ProgramResult} - the documented way to see what a run ended with - and reports the exit
 * status.</p>
 */
public final class ApplicationControlDemo {

    /** The row carrying progress, the sequence state and the quit guard. */
    public static final int STATUS_ROW = 0;

    /** The row carrying the confirmation prompt. */
    public static final int PROMPT_ROW = 1;

    /** The body row, which names the producer. */
    public static final int BODY_ROW = 3;

    /** How often the owned producer sends one progress message. */
    public static final long PRODUCER_INTERVAL_MILLIS = 100L;

    /** How many progress messages the producer sends before it stops on its own. */
    public static final int PRODUCER_TICKS = 200;

    /** How long one sequence step takes, so the order is an order of execution and not of submission. */
    public static final Duration STEP_DELAY = Duration.ofMillis(80);

    private static final Style STATUS = Style.NONE.withBold();

    private ApplicationControlDemo() {}

    /** Every message the scene handles. */
    public sealed interface Msg {

        /** One progress value, sent by the owned producer through the program handle. */
        record Progress(int value) implements Msg {}

        /** Starts the demonstration sequence. */
        record StartSequence() implements Msg {}

        /** One sequence step finished, or one continuation committed. */
        record Mark(String name) implements Msg {}

        /** Asks to quit; the guard decides whether the request is accepted. */
        record RequestQuit() implements Msg {}

        /** Confirms the quit the prompt asked about. */
        record ConfirmQuit() implements Msg {}

        /** Keeps the session running. */
        record CancelQuit() implements Msg {}
    }

    /**
     * The scene state.
     *
     * @param progress the last progress value the producer sent, or -1 when none arrived
     * @param ticks how many messages the producer delivered
     * @param sequence how the demonstration sequence stands, as one token
     * @param order the sequence's messages, in the order the model received them
     * @param confirming whether the quit prompt is up
     * @param gestures how many messages the scene has handled
     */
    public record Model(
            int progress, int ticks, String sequence, List<String> order, boolean confirming, int gestures) {

        /** Substitutes the starting values for anything missing. */
        public Model {
            sequence = sequence == null ? "idle" : sequence;
            order = order == null ? List.of() : List.copyOf(order);
            gestures = Math.max(0, gestures);
        }

        /** The scene's starting state: nothing sent, nothing run, nothing asked. */
        public static Model start() {
            return new Model(-1, 0, "idle", List.of(), false, 0);
        }

        Model advanced() {
            return new Model(progress, ticks, sequence, order, confirming, gestures + 1);
        }

        Model withProgress(int value) {
            return new Model(value, ticks + 1, sequence, order, confirming, gestures + 1);
        }

        Model running() {
            return new Model(progress, ticks, "running", List.of(), confirming, gestures + 1);
        }

        Model withMark(String name) {
            List<String> next = new ArrayList<>(order);
            next.add(name);
            return new Model(progress, ticks, next.size() >= 5 ? "done" : "running", next, confirming, gestures + 1);
        }

        Model withPrompt(boolean value) {
            return new Model(progress, ticks, sequence, order, value, gestures + 1);
        }

        /** The order row's value, or {@code none} before the sequence ran. */
        public String orderText() {
            return order.isEmpty() ? "none" : String.join(",", order);
        }
    }

    /** The scene's own actions. */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Sequence", KeyAction.<Msg>of("sequence", new Msg.StartSequence(), "s", "run sequence", "s")),
                KeyMap.group(
                        "Session",
                        KeyAction.<Msg>of("quit", new Msg.RequestQuit(), "q", "quit", "q", "ctrl+c"),
                        KeyAction.<Msg>of("confirm", new Msg.ConfirmQuit(), "y", "confirm quit", "y"),
                        KeyAction.<Msg>of("cancel", new Msg.CancelQuit(), "esc", "keep running", "esc")));
    }

    /**
     * The quit guard.
     *
     * <p>A filter is a plain function over the messages a session is about to deliver, so an
     * application can keep the one piece of state this decision needs: whether the user has confirmed
     * the quit. It sees {@link Msg.ConfirmQuit} before the quit it causes - the confirmation is an
     * ordinary message - and it sees the {@link QuitRequest} that follows.</p>
     */
    public static final class Guard implements MessageFilter {

        private volatile boolean confirmed;

        @Override
        public Object apply(Object message) {
            if (message instanceof Msg.ConfirmQuit) {
                confirmed = true;
                return message;
            }
            if (message instanceof QuitRequest) {
                return confirmed ? message : null;
            }
            return message;
        }

        /** Whether the quit has been confirmed. */
        public boolean confirmed() {
            return confirmed;
        }
    }

    /**
     * Runs the demo: starts the scene on its own runtime thread, runs the owned producer, waits for
     * the program to end, and reports the final model.
     *
     * @return how the run ended; never null
     */
    public static ExitResult run() {
        Guard guard = new Guard();
        ProgramHandle<Model, Msg> handle =
                Application.start(new Scene(), RuntimeOptions.defaults().withMessageFilter(guard));
        Thread producer = producer(handle);
        try {
            ProgramResult<Model> result = handle.await();
            System.out.println("final " + summary(result.model()) + " exit=" + describe(result.exit()));
            return result.exit();
        } finally {
            producer.interrupt();
        }
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The owned local producer: it sends progress through the handle, never through the scene, which is
     * what an outside event source - a queue, a socket, a file watcher - looks like.
     *
     * @param handle the running program's control handle
     * @return the producer thread, already started
     */
    static Thread producer(ProgramHandle<Model, Msg> handle) {
        return Thread.ofVirtual().name("braxton-demo-producer").start(() -> {
            for (int tick = 1; tick <= PRODUCER_TICKS; tick++) {
                try {
                    TimeUnit.MILLISECONDS.sleep(PRODUCER_INTERVAL_MILLIS);
                } catch (InterruptedException cancelled) {
                    return;
                }
                if (handle.trySend(new Msg.Progress(tick)) == ProgramHandle.Send.CLOSED) {
                    return;
                }
            }
        });
    }

    /** The status row's text, which is what a terminal verifier asserts. */
    public static String status(Model model) {
        return "progress=" + (model.progress() < 0 ? "-" : Integer.toString(model.progress()))
                + " ticks=" + model.ticks()
                + " sequence=" + model.sequence()
                + " order=" + model.orderText()
                + " quit=" + (model.confirming() ? "confirm" : "guarded");
    }

    /** The prompt row's text. */
    public static String prompt(Model model) {
        return model.confirming() ? "prompt=confirm press y to quit" : "prompt=none s sequence • q quit";
    }

    /** The final model, as the line the demo prints once the terminal is its own again. */
    public static String summary(Model model) {
        if (model == null) {
            return "progress=- ticks=0 sequence=idle order=none";
        }
        return "progress=" + (model.progress() < 0 ? "-" : Integer.toString(model.progress()))
                + " ticks=" + model.ticks() + " sequence=" + model.sequence()
                + " order=" + model.orderText();
    }

    /** One exit result as a token, without any terminal output. */
    public static String describe(ExitResult result) {
        return result.kind() + "/" + result.status();
    }

    /** The demo scene: the producer's progress, the sequence and the guarded quit. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(Model.start());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Progress progress -> Update.next(model.withProgress(progress.value()));
                case Msg.StartSequence start -> Update.next(model.running(), sequence());
                case Msg.Mark mark -> Update.next(model.withMark(mark.name()));
                case Msg.RequestQuit request -> Update.next(model.withPrompt(true), Command.quit());
                case Msg.ConfirmQuit confirm -> Update.next(model.withPrompt(false), Command.quit());
                case Msg.CancelQuit cancel -> Update.next(model.withPrompt(false));
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            return Views.onKeys(
                    keys.bindings(),
                    Views.focus(
                            ElementId.of("control"),
                            true,
                            keys.bindings(),
                            Scene::type,
                            Views.column(
                                    Views.text(status(model), STATUS),
                                    Views.text(prompt(model), STATUS),
                                    Views.gap(1),
                                    Views.text("braxton application control producer=on", STATUS),
                                    Views.gap(1),
                                    Help.view(keys, false, width))));
        }

        /** Types nothing: the scene's screen is a status board, not a field. */
        private static Optional<Msg> type(InputEvent event) {
            return Optional.empty();
        }

        /**
         * The demonstration sequence.
         *
         * <p>Each step starts only when the previous one has finished: the first task appends
         * {@code a}, the delay delivers {@code tick-a} after {@value #STEP_DELAY}, and only then is the
         * second task submitted. The {@link Command.AfterUpdate} step is the continuation that waits for
         * the reducer, so {@code b-done} commits before {@code c} is submitted.</p>
         *
         * @return the sequence command
         */
        static Command<Msg> sequence() {
            return Command.sequence(
                    task("a", 0L),
                    Command.delay(
                            EffectId.of("sequence-delay"), STEP_DELAY, new Msg.Mark("tick-a"), new Msg.Mark("tick-a")),
                    task("b", 0L),
                    Command.afterUpdate(new Msg.Mark("b-done")),
                    task("c", 0L));
        }

        /**
         * One sequence step: application work that takes a little time, so the order the rows record is
         * an order of execution.
         *
         * @param name the step's name, which its message carries
         * @param extraMillis extra time the step body spends before it returns
         * @return the task command
         */
        static Command<Msg> task(String name, long extraMillis) {
            return Command.task(
                    EffectId.of("sequence-" + name),
                    () -> {
                        if (extraMillis > 0) {
                            TimeUnit.MILLISECONDS.sleep(extraMillis);
                        }
                        return name;
                    },
                    value -> new Msg.Mark(value),
                    failure -> new Msg.Mark("failed"));
        }
    }
}
