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

package io.github.dhoard.braxton.effect;

import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.mvu.ComponentPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * An inert description of work to perform once, interpreted by the runtime after a transition
 * commits.
 *
 * <p>Commands are data. Building one performs no I/O; a task body runs only in the runtime's effect
 * interpreter, and its result is delivered back as an ordinary message. Structured built-ins cover
 * the operations the library itself owns; {@link #task} is the documented escape hatch for
 * application effects and is explicitly not a pure or replayable description.</p>
 *
 * @param <Msg> the component message type this command produces
 */
public sealed interface Command<Msg> {

    /** Re-types every message this command can produce. */
    <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper);

    /**
     * Does nothing.
     *
     * @param <Msg> the component message type this command would have produced
     */
    record None<Msg>() implements Command<Msg> {

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new None<>();
        }
    }

    /**
     * Interpreted in order; a {@link Quit} ends the batch.
     *
     * @param commands the commands to interpret, in declaration order
     * @param <Msg> the component message type these commands produce
     */
    record Batch<Msg>(List<Command<Msg>> commands) implements Command<Msg> {

        public Batch {
            commands = List.copyOf(commands);
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            List<Command<Msg2>> mapped = new ArrayList<>(commands.size());
            for (Command<Msg> command : commands) {
                mapped.add(command.map(mapper));
            }
            return new Batch<>(mapped);
        }
    }

    /**
     * Runs {@code work} on the runtime's effect executor.
     *
     * <p>The captured arguments should be immutable values: the task may run after later updates.
     * Its success or failure becomes exactly one message. The body must not touch the active
     * terminal, mutate application state, or call {@code update}.</p>
     *
     * @param id the effect identity; replacing it invalidates an earlier registration
     * @param work the application work to run on the effect executor
     * @param onSuccess maps the work's result to the message delivered on success
     * @param onFailure maps the thrown failure to the message delivered on failure
     * @param <Msg> the component message type this command produces
     */
    record Task<Msg>(EffectId id, Callable<?> work, Function<Object, Msg> onSuccess, Function<Throwable, Msg> onFailure)
            implements Command<Msg> {

        public Task {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(onSuccess, "onSuccess");
            Objects.requireNonNull(onFailure, "onFailure");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Task<>(id, work, onSuccess.andThen(mapper), onFailure.andThen(mapper));
        }
    }

    /**
     * Delivers {@code message} after {@code delay}; {@code rejected} if the quota is exhausted.
     *
     * <p>The delay must be representable in nanoseconds, the unit the scheduler uses; a longer
     * delay is rejected here instead of overflowing when it is scheduled.</p>
     *
     * @param id the effect identity; replacing it invalidates an earlier registration
     * @param delay how long to wait before delivering {@code message}
     * @param message the message delivered after the delay
     * @param rejected the message delivered instead when the effect quota is exhausted
     * @param <Msg> the component message type this command produces
     */
    record Delay<Msg>(EffectId id, Duration delay, Msg message, Msg rejected) implements Command<Msg> {

        public Delay {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
            try {
                delay.toNanos();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("delay must fit in nanoseconds: " + delay, overflow);
            }
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Delay<>(id, delay, mapper.apply(message), mapper.apply(rejected));
        }
    }

    /**
     * Invalidates one effect id; its pending or queued results are ignored.
     *
     * @param id the local effect identity to invalidate
     * @param <Msg> the component message type this command would have produced
     */
    record Cancel<Msg>(EffectId id) implements Command<Msg> {

        public Cancel {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Cancel<>(id);
        }
    }

    /**
     * Invalidates all effects in the current component scope and descendants.
     *
     * @param <Msg> the component message type this command would have produced
     */
    record CancelAll<Msg>() implements Command<Msg> {
        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new CancelAll<>();
        }
    }

    /**
     * Enqueues an owner-local message after commit; never invokes update recursively.
     *
     * @param message the message to deliver to this component's own update
     * @param <Msg> the component message type this command produces
     */
    record Dispatch<Msg>(Msg message) implements Command<Msg> {
        public Dispatch {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Dispatch<>(mapper.apply(message));
        }
    }

    /**
     * Structural origin for a mounted command, including unkeyed built-ins and dispatch.
     *
     * @param path the component path the wrapped command belongs to
     * @param command the command to interpret in that scope
     * @param <Msg> the component message type this command produces
     */
    record Scoped<Msg>(ComponentPath path, Command<Msg> command) implements Command<Msg> {
        public Scoped {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(command, "command");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Scoped<>(path, command.map(mapper));
        }
    }

    default Command<Msg> scoped(ComponentPath path) {
        Objects.requireNonNull(path, "path");
        return this instanceof None<Msg> ? this : new Scoped<>(path, this);
    }

    /**
     * Reads the session's in-process clipboard buffer and reports it as one message.
     *
     * @param mapper maps the stored text to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record ReadClipboard<Msg>(Function<String, Msg> mapper) implements Command<Msg> {

        public ReadClipboard {
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new ReadClipboard<>(this.mapper.andThen(mapper));
        }
    }

    /**
     * Copies text and reports the truthful {@link ClipboardResult} as one message.
     *
     * @param text the text to copy; null is treated as empty
     * @param mapper maps the reported result to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record Copy<Msg>(String text, Function<ClipboardResult, Msg> mapper) implements Command<Msg> {

        public Copy {
            text = text == null ? "" : text;
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Copy<>(text, this.mapper.andThen(mapper));
        }
    }

    /**
     * Ends the program with {@code status}; terminal for the enclosing batch.
     *
     * @param status the process exit status reported by {@code ExitResult}
     * @param <Msg> the component message type this command would have produced
     */
    record Quit<Msg>(int status) implements Command<Msg> {

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Quit<>(status);
        }
    }

    static <Msg> Command<Msg> none() {
        return new None<>();
    }

    /** Ordered batch, flattened and with {@link None} entries removed. */
    @SafeVarargs
    static <Msg> Command<Msg> batch(Command<Msg>... commands) {
        List<Command<Msg>> copy = new ArrayList<>(commands.length);
        for (Command<Msg> command : commands) {
            copy.add(command);
        }
        return batch(copy);
    }

    static <Msg> Command<Msg> batch(List<Command<Msg>> commands) {
        List<Command<Msg>> flat = new ArrayList<>();
        for (Command<Msg> command : commands) {
            if (command instanceof None<Msg>) {
                continue;
            }
            if (command instanceof Batch<Msg> batch) {
                flat.addAll(batch.commands());
            } else {
                flat.add(command);
            }
        }
        if (flat.isEmpty()) {
            return new None<>();
        }
        return flat.size() == 1 ? flat.get(0) : new Batch<>(flat);
    }

    /**
     * Typed one-shot application effect. The unchecked casts are confined to this factory: Java
     * erases {@code T}, and the interpreter invokes the success mapper with the task's own result.
     */
    static <Msg, T> Command<Msg> task(
            EffectId id,
            Callable<T> work,
            Function<? super T, ? extends Msg> onSuccess,
            Function<Throwable, ? extends Msg> onFailure) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        @SuppressWarnings("unchecked")
        Function<Object, Msg> success = value -> onSuccess.apply((T) value);
        return new Task<>(id, work, success, onFailure::apply);
    }

    static <Msg> Command<Msg> delay(EffectId id, Duration delay, Msg message, Msg rejected) {
        return new Delay<>(id, delay, message, rejected);
    }

    static <Msg> Command<Msg> cancel(EffectId id) {
        return new Cancel<>(id);
    }

    static <Msg> Command<Msg> cancelAll() {
        return new CancelAll<>();
    }

    static <Msg> Command<Msg> dispatch(Msg message) {
        return new Dispatch<>(message);
    }

    static <Msg> Command<Msg> copy(String text, Function<ClipboardResult, Msg> mapper) {
        return new Copy<>(text, mapper);
    }

    static <Msg> Command<Msg> readClipboard(Function<String, Msg> mapper) {
        return new ReadClipboard<>(mapper);
    }

    static <Msg> Command<Msg> quit() {
        return new Quit<>(0);
    }

    static <Msg> Command<Msg> quit(int status) {
        return new Quit<>(status);
    }
}
