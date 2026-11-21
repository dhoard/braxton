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

package io.github.dhoard.braxton.testing;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.terminal.Capabilities;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * A headless, deterministic pilot for a typed MVU component.
 *
 * <p>The pilot feeds normalized {@link InputEvent}s to an application-supplied translator, runs the
 * resulting updates, executes the inert command variants that need no real terminal, and renders a
 * real view through the production renderer. It is deliberate that a pilot is <em>not</em> a runtime
 * session: there is no native access, executor, I/O or wall-clock wait, so a widget test can assert
 * an exact screen without a TTY. Effects that need an external system (processes, clipboard,
 * terminal queries and suspend) are rejected with {@link UnsupportedCommandException} instead of
 * being pretended.</p>
 *
 * <p>Time is manual. {@link #advance(Duration)} moves the clock and delivers only the delayed
 * messages that are then due. Task work is run by a synchronous {@link TaskRunner} by default, so
 * the whole script has one deterministic order.</p>
 *
 * @param <M> the root model type
 * @param <Msg> the root message type
 */
public final class Pilot<M, Msg> {

    /**
     * The bound on one drain, so a self-dispatching command fails instead of hanging a test.
     */
    private static final int MAX_DISPATCHES = 4_096;

    /**
     * Runs effect task bodies. The default runs them on the calling thread.
     */
    @FunctionalInterface
    public interface TaskRunner {
        /**
         * Runs one task and returns its value.
         *
         * @param work the task computation to execute
         * @return the task's computed value
         * @throws java.lang.Exception if the operation fails
         */
        Object run(Callable<?> work) throws Exception;

        /**
         * The synchronous default, which keeps a script's order reproducible.
         */
        TaskRunner DIRECT = Callable::call;
    }

    /**
     * A scripted sequence of interactions.
     *
     * @param <M> the model type
     * @param <Msg> the message type
     */
    @FunctionalInterface
    public interface Script<M, Msg> {
        /**
         * Applies interactions to the pilot.
         *
         * @param pilot the pilot receiving scripted input
         */
        void run(Pilot<M, Msg> pilot);
    }

    /**
     * One recorded interaction and the exact screen it produced.
     *
     * @param <M> the model type
     * @param label the display label
     * @param model the immutable model to inspect
     * @param screen the headless element screen
     */
    public record Step<M>(String label, M model, Snapshot screen) {}

    /**
     * Text, per-cell styles and focusable rectangles of one painted frame.
     *
     * @param rows the number of rows
     * @param cols the number of columns
     * @param lines the rows in display order
     * @param styles the styles to apply
     * @param focusables the focusable cell rectangles in traversal order
     */
    public record Snapshot(int rows, int cols, List<String> lines, List<List<String>> styles, List<Rect> focusables) {

        /**
         * Creates a {@code Snapshot} value.
         *
         * @param rows the number of rows
         * @param cols the number of columns
         * @param lines the rows in display order
         * @param styles the styles to apply
         * @param focusables the focusable cell rectangles in traversal order
         */
        public Snapshot {
            lines = List.copyOf(lines);
            styles = styles.stream().map(List::copyOf).toList();
            focusables = List.copyOf(focusables);
            if (rows < 0 || cols < 0 || lines.size() != rows || styles.size() != rows) {
                throw new IllegalArgumentException("snapshot dimensions do not match rows");
            }
        }

        /**
         * The painted text as one newline-separated string.
         *
         * @return the painted text as one newline-separated string
         */
        public String text() {
            return String.join("\n", lines);
        }
    }

    /**
     * One delayed message and the effect identity that can cancel it.
     */
    private record Scheduled<Msg>(EffectId id, Msg message) {}

    /**
     * A command that cannot be executed without a real session.
     */
    public static final class UnsupportedCommandException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        UnsupportedCommandException(String message) {
            super(message);
        }

        /**
         * The rejected command.
         *
         * @return the rejected command
         */
        public String commandType() {
            return getMessage();
        }
    }

    private final Component<M, Msg, Never> component;
    private final Function<InputEvent, Msg> translate;
    private final TaskRunner tasks;
    private final Deque<Msg> pending = new ArrayDeque<>();
    private final NavigableMap<Long, List<Scheduled<Msg>>> due = new TreeMap<>();
    private final Map<EffectId, Long> scheduledIds = new HashMap<>();
    private final List<String> printed = new ArrayList<>();
    private final List<Step<M>> trace = new ArrayList<>();
    private M model;
    private Viewport viewport;
    private long nowNanos;
    private OptionalInt quitStatus = OptionalInt.empty();

    private Pilot(
            Component<M, Msg, Never> component,
            Function<InputEvent, Msg> translate,
            Viewport viewport,
            TaskRunner tasks) {
        this.component = Objects.requireNonNull(component, "component");
        this.translate = Objects.requireNonNull(translate, "translate");
        this.viewport = Objects.requireNonNull(viewport, "viewport");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        Update<M, Msg, Never> initial = component.init(new InitContext(viewport, Capabilities.PLAIN));
        commit("init", initial);
    }

    /**
     * Creates a pilot with a 24x80 viewport and synchronous tasks.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param component the root component; required
     * @param translate maps normalized input to a root message; required
     * @return the initialized pilot
     */
    public static <M, Msg> Pilot<M, Msg> of(Component<M, Msg, Never> component, Function<InputEvent, Msg> translate) {
        return new Pilot<>(component, translate, new Viewport(24, 80), TaskRunner.DIRECT);
    }

    /**
     * Creates a pilot with a chosen viewport.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param component the typed component to drive
     * @param translate the input-to-message translator
     * @param viewport the available terminal viewport
     * @return a pilot with a chosen viewport
     */
    public static <M, Msg> Pilot<M, Msg> of(
            Component<M, Msg, Never> component, Function<InputEvent, Msg> translate, Viewport viewport) {
        return new Pilot<>(component, translate, viewport, TaskRunner.DIRECT);
    }

    /**
     * Creates a pilot with a chosen viewport and task runner.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param component the typed component to drive
     * @param tasks the task execution policy
     * @param translate the input-to-message translator
     * @param viewport the available terminal viewport
     * @return a pilot with a chosen viewport and task runner
     */
    public static <M, Msg> Pilot<M, Msg> of(
            Component<M, Msg, Never> component,
            Function<InputEvent, Msg> translate,
            Viewport viewport,
            TaskRunner tasks) {
        return new Pilot<>(component, translate, viewport, tasks);
    }

    /**
     * The current model.
     *
     * @return the current model
     */
    public M model() {
        return model;
    }

    /**
     * The current viewport.
     *
     * @return the current viewport
     */
    public Viewport viewport() {
        return viewport;
    }

    /**
     * The manual clock, in nanoseconds since the pilot started.
     *
     * @return the manual clock, in nanoseconds since the pilot started
     */
    public long nowNanos() {
        return nowNanos;
    }

    /**
     * The status of a quit command, or empty while the pilot is still running.
     *
     * @return the status of a quit command, or empty while the pilot is still running
     */
    public OptionalInt quitStatus() {
        return quitStatus;
    }

    /**
     * Lines passed to {@link Command.PrintLine}, in order.
     *
     * @return the durable lines emitted by interpreted print commands
     */
    public List<String> printedLines() {
        return List.copyOf(printed);
    }

    /**
     * Every interaction and the screen it produced, including initialization.
     *
     * @return every interaction and the screen it produced, including initialization
     */
    public List<Step<M>> trace() {
        return List.copyOf(trace);
    }

    /**
     * Renders the current frame exactly as the runtime would paint it.
     *
     * @return the current headless frame
     */
    public Snapshot snapshot() {
        ElementScreen screen = ElementScreen.render(
                component.view(model, new ViewContext(viewport)), viewport.rows(), viewport.cols());
        List<List<String>> styles = new ArrayList<>(viewport.rows());
        for (int row = 0; row < viewport.rows(); row++) {
            List<String> cells = new ArrayList<>(viewport.cols());
            for (io.github.dhoard.braxton.style.Style style : screen.styles(row)) {
                cells.add(style.toString());
            }
            styles.add(List.copyOf(cells));
        }
        return new Snapshot(viewport.rows(), viewport.cols(), screen.lines(), styles, screen.focusables());
    }

    /**
     * Applies a scripted sequence, returning this pilot for chaining.
     *
     * @param script the ordered pilot script
     * @return this pilot after executing the script
     */
    public Pilot<M, Msg> run(Script<M, Msg> script) {
        Objects.requireNonNull(script, "script").run(this);
        return this;
    }

    /**
     * Delivers one normalized event through the application's translator.
     *
     * @param event the normalized input event
     * @return this pilot after delivering the event
     */
    public Pilot<M, Msg> input(InputEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof InputEvent.ResizeInput resize) {
            viewport = new Viewport(resize.size().rows(), resize.size().cols());
        }
        Msg message = Objects.requireNonNull(translate.apply(event), "translated message");
        return dispatch(message, describe(event));
    }

    /**
     * Presses one key by its parsed specification, such as {@code ctrl+a} or {@code enter}.
     *
     * @param spec the key or style specification
     * @return this pilot after delivering the key
     */
    public Pilot<M, Msg> press(String spec) {
        Key key = Key.parse(spec);
        String text = key.name().length() == 1 ? key.name() : key.equals(Key.SPACE) ? " " : "";
        return input(InputEvent.KeyInput.of(key, text, nowNanos));
    }

    /**
     * Inserts printable text as one atomic paste, never as key bindings.
     *
     * @param text the text to process
     * @return inserts printable text as one atomic paste, never as key bindings
     */
    public Pilot<M, Msg> paste(String text) {
        return input(new InputEvent.PasteInput(Objects.requireNonNull(text, "text")));
    }

    /**
     * Reports a pointer press and release at one cell.
     *
     * @param row the zero-based row
     * @param column the zero-based column
     * @return this pilot after the click
     */
    public Pilot<M, Msg> click(int row, int column) {
        return pointer(PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, column))
                .pointer(PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, row, column));
    }

    /**
     * Reports one normalized pointer event.
     *
     * @param event the normalized input event
     * @return this pilot after delivering the pointer event
     */
    public Pilot<M, Msg> pointer(PointerEvent event) {
        return input(new InputEvent.PointerInput(Objects.requireNonNull(event, "event")));
    }

    /**
     * Resizes the viewport and delivers the resize event.
     *
     * @param rows the number of rows
     * @param columns the number of columns
     * @return resizes the viewport and delivers the resize event
     */
    public Pilot<M, Msg> resize(int rows, int columns) {
        return input(new InputEvent.ResizeInput(new io.github.dhoard.braxton.layout.Size(rows, columns)));
    }

    /**
     * Delivers one application message directly, executing the resulting commands.
     *
     * @param message the typed message to deliver
     * @return this pilot after applying the message and commands
     */
    public Pilot<M, Msg> dispatch(Msg message) {
        return dispatch(message, "dispatch");
    }

    private Pilot<M, Msg> dispatch(Msg message, String label) {
        pending.addLast(Objects.requireNonNull(message, "message"));
        drain(label);
        return this;
    }

    /**
     * Advances the manual clock and delivers every delayed message that is then due.
     *
     * @param duration the nonnegative duration
     * @return this pilot after advancing time and delivering due messages
     */
    public Pilot<M, Msg> advance(Duration duration) {
        Duration step = Objects.requireNonNull(duration, "duration");
        if (step.isNegative()) {
            throw new IllegalArgumentException("a pilot clock cannot move backwards");
        }
        nowNanos += step.toNanos();
        while (!due.isEmpty() && due.firstKey() <= nowNanos) {
            long firedAt = due.firstKey();
            List<Scheduled<Msg>> ready = due.pollFirstEntry().getValue();
            scheduledIds.entrySet().removeIf(entry -> entry.getValue().longValue() == firedAt);
            for (Scheduled<Msg> scheduled : ready) {
                pending.addLast(scheduled.message());
            }
            drain("advance");
        }
        return this;
    }

    private void drain(String label) {
        int count = 0;
        while (!pending.isEmpty()) {
            if (++count > MAX_DISPATCHES) {
                throw new IllegalStateException("a command dispatched more than " + MAX_DISPATCHES + " messages");
            }
            Msg message = pending.removeFirst();
            commit(label, component.update(model, message));
        }
    }

    private void commit(String label, Update<M, Msg, Never> update) {
        model = Objects.requireNonNull(update, "update").model();
        execute(update.command());
        trace.add(new Step<>(label, model, snapshot()));
    }

    private void execute(Command<Msg> command) {
        switch (Objects.requireNonNull(command, "command")) {
            case Command.None<Msg> ignored -> {}
            case Command.Batch<Msg> batch -> batch.commands().forEach(this::execute);
            case Command.Sequence<Msg> sequence -> sequence.commands().forEach(this::execute);
            case Command.Scoped<Msg> scoped -> execute(scoped.command());
            case Command.Dispatch<Msg> message -> pending.addLast(message.message());
            case Command.AfterUpdate<Msg> message -> pending.addLast(message.message());
            case Command.MonotonicTime<Msg> time ->
                pending.addLast(time.mapper().apply(nowNanos));
            case Command.Delay<Msg> delay -> schedule(delay);
            case Command.Task<Msg> task -> runTask(task);
            case Command.Cancel<Msg> cancel -> cancel(cancel.id());
            case Command.CancelAll<Msg> ignored -> cancelAll();
            case Command.PrintLine<Msg> line -> printed.add(line.text());
            case Command.Quit<Msg> quit -> quitStatus = OptionalInt.of(quit.status());
            default -> throw new UnsupportedCommandException(command.getClass().getSimpleName());
        }
    }

    private void schedule(Command.Delay<Msg> delay) {
        long at = nowNanos + delay.delay().toNanos();
        scheduledIds.put(delay.id(), at);
        due.computeIfAbsent(at, ignored -> new ArrayList<>()).add(new Scheduled<>(delay.id(), delay.message()));
    }

    private void cancel(EffectId id) {
        Long at = scheduledIds.remove(id);
        if (at != null) {
            List<Scheduled<Msg>> messages = due.get(at);
            if (messages != null) {
                messages.removeIf(scheduled -> scheduled.id().equals(id));
                if (messages.isEmpty()) {
                    due.remove(at);
                }
            }
        }
    }

    private void cancelAll() {
        scheduledIds.clear();
        due.clear();
    }

    private void runTask(Command.Task<Msg> task) {
        try {
            Object value = tasks.run(task.work());
            pending.addLast(task.onSuccess().apply(value));
        } catch (Throwable failure) {
            pending.addLast(task.onFailure().apply(failure));
        }
    }

    private static String describe(InputEvent event) {
        return switch (event) {
            case InputEvent.KeyInput key -> "key:" + key.key().name();
            case InputEvent.PasteInput ignored -> "paste";
            case InputEvent.PointerInput pointer ->
                "pointer:" + pointer.pointer().action();
            case InputEvent.ResizeInput resize -> "resize:" + resize.size();
            default -> event.getClass().getSimpleName();
        };
    }
}
