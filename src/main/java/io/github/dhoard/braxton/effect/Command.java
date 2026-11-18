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

import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.clipboard.ClipboardReadResult;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.style.ColorProfile;
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

    /**
     * Re-types every message this command can produce.
     *
     * @param <Msg2> the message type the mapped command produces
     * @param mapper maps every message this command produces
     * @return the re-typed command
     */
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

        /**
         * Rejects a null list of commands.
         */
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
     * Runs each command in turn, submitting the next one only after the previous one has finished.
     *
     * <p>A step finishes when its work is done and its message has been handed to the runtime: a task
     * when its success or failure message is enqueued, a delay when it delivers, a query when its
     * answer arrives, and a command that produces no message - cancellation, or a presentation change -
     * once it has taken effect. Submission is not the same as installation, which is what makes a
     * sequence different from a {@link Batch}: a batch starts everything at once, and a sequence
     * starts step N+1 only once step N is over. Messages are delivered in sequence order.
     *
     * <p>The next step is submitted at that completion point, not after the reducer has seen the
     * message: a message is one value among many, and the runtime never waits for a component's own
     * {@code update} before continuing. A sequence that must observe the model its own step produced
     * uses {@link AfterUpdate} for that step, which finishes only once the transition has
     * committed.</p>
     *
     * <p>A sequence stops early - and runs nothing further - when a step never finishes because it
     * was cancelled, when the component its steps belong to is removed, or when a step ends the
     * program with {@link Quit}. Ending the program discards whatever is left, exactly as it does for
     * a batch. A sequence is not a lease on the runtime: it holds no thread and no registration of its
     * own, so an idle step (a long delay) costs nothing beyond that step's own effect.</p>
     *
     * @param commands the commands to run in order
     * @param <Msg> the component message type these commands produce
     */
    record Sequence<Msg>(List<Command<Msg>> commands) implements Command<Msg> {

        /**
         * Rejects a null list of commands.
         */
        public Sequence {
            commands = List.copyOf(commands);
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            List<Command<Msg2>> mapped = new ArrayList<>(commands.size());
            for (Command<Msg> command : commands) {
                mapped.add(command.map(mapper));
            }
            return new Sequence<>(mapped);
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

        /**
         * Rejects a null work, success mapper or failure mapper.
         */
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

        /**
         * Rejects a negative delay and a delay that does not fit in nanoseconds.
         */
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
     * Reads the runtime's monotonic clock once and delivers the reading as one message.
     *
     * <p>This is the supported way for a component to learn what time it is. Components hold no
     * clock, so a widget that needs a timestamp to start from - a countdown naming a deadline, a
     * stopwatch naming an origin - asks for one and waits for the message it produces. The reading
     * comes from the same clock the runtime's intervals report, so a timestamp fact and a tick are
     * on one timeline.</p>
     *
     * <p>Like the clipboard built-ins this is an immediate owner-side read rather than a scheduled
     * effect: it has no identity, it can be neither cancelled nor replaced, it is not counted
     * against the effect quota, and its mapper runs on the owner thread while the command is
     * interpreted. A mapper that returns {@code null} declines the reading, so nothing is
     * delivered.</p>
     *
     * @param mapper maps the monotonic reading in nanoseconds to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record MonotonicTime<Msg>(Function<Long, Msg> mapper) implements Command<Msg> {

        /**
         * Rejects a null mapper.
         */
        public MonotonicTime {
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new MonotonicTime<>(this.mapper.andThen(mapper));
        }
    }

    /**
     * Runs a child process in the session's terminal while the session is released.
     *
     * <p>A handoff is the opposite of an effect: the session stops being the terminal's owner. The
     * input reader is stopped, the screen and every mode the session applied are given back, the
     * terminal returns to the user's own configuration, and the child inherits it. The child runs to
     * completion before the transition that returned this command finishes, which is why an
     * interactive program - an editor, a pager, a shell - works at all: there is no second consumer
     * competing for the keyboard.</p>
     *
     * <p>Exactly one result follows, and it is delivered after the session has taken the terminal
     * back and repainted, so the application never has to repaint by hand. A handoff requested while
     * another one runs, or while the session is closing, is reported as
     * {@link ProcessResult.Rejected} rather than queued: two processes cannot own one terminal. A
     * session that cannot take its terminal back fails the run instead of reporting a result, because
     * there is nowhere left to report it.</p>
     *
     * <p>Like the other owner-side built-ins this produces no message of its own: it has no identity,
     * it can be neither cancelled nor replaced, and it is not counted against the effect quota. A
     * mapper that returns {@code null} delivers nothing.</p>
     *
     * @param request the child to run; required
     * @param mapper maps the child's result to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record Exec<Msg>(ProcessRequest request, Function<ProcessResult, Msg> mapper) implements Command<Msg> {

        /**
         * Rejects a null request or mapper.
         */
        public Exec {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Exec<>(request, this.mapper.andThen(mapper));
        }
    }

    /**
     * Suspends the session, giving its terminal to the job controller until it is continued.
     *
     * <p>The process group is stopped only when this session owns the terminal's foreground; a
     * background group is refused, because a stopped background job has nobody to continue it. The
     * terminal is given back before the stop and taken again after the continue, so the shell that
     * stopped the job finds its own configuration and its own screen.</p>
     *
     * <p>Exactly one result follows, delivered after the session has taken the terminal back and
     * measured its size again. {@link SuspendResult.Resumed} is the resume notification: the frame the
     * application paints next is the resumed screen.</p>
     *
     * <p>Like {@link Exec} this is an owner-side built-in with no identity, no cancellation and no
     * quota cost.</p>
     *
     * @param mapper maps the outcome to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record Suspend<Msg>(Function<SuspendResult, Msg> mapper) implements Command<Msg> {

        /**
         * Rejects a null mapper.
         */
        public Suspend {
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Suspend<>(this.mapper.andThen(mapper));
        }
    }

    /**
     * Invalidates one effect id; its pending or queued results are ignored.
     *
     * @param id the local effect identity to invalidate
     * @param <Msg> the component message type this command would have produced
     */
    record Cancel<Msg>(EffectId id) implements Command<Msg> {

        /**
         * Rejects a null effect id.
         */
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
        /**
         * Rejects a null message.
         */
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
     * Enqueues an owner-local message and finishes only once the transition it caused has committed.
     *
     * <p>This is the continuation a sequence uses when the next step has to see the model its
     * predecessor's message produced. A {@link Sequence} submits step N+1 as soon as step N's message
     * is handed over; this command makes "handed over" mean "folded in": the message is delivered as
     * an owner-local message exactly like {@link Dispatch}, and the step finishes after the
     * {@code update} it caused has committed, so the rest of the sequence is interpreted against the
     * model that includes it.</p>
     *
     * <p>Outside a sequence it behaves like {@link Dispatch}: the completion is what nobody observes.
     * A message the runtime drops because its component is gone finishes nothing, which ends the
     * sequence, and a message an application filter drops still finishes the step: the runtime
     * delivered it and the filter declined it, which is not a reason to leave a sequence suspended.</p>
     *
     * @param message the message the next sequence step waits for
     * @param <Msg> the component message type this command produces
     */
    record AfterUpdate<Msg>(Msg message) implements Command<Msg> {

        /**
         * Rejects a null message.
         */
        public AfterUpdate {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new AfterUpdate<>(mapper.apply(message));
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
        /**
         * Rejects a null path or command.
         */
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

    /**
     * The same command in a structural scope.
     *
     * @param path the structural scope to prepend
     * @return this command in that scope
     */
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

        /**
         * Rejects a null mapper.
         */
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
     * <p>The system clipboard is the default destination; {@link CopyTo} names the other selection. A
     * copy never waits for the terminal and never claims the terminal accepted anything: the
     * in-process buffer holds the text either way.</p>
     *
     * @param text the text to copy; null is treated as empty
     * @param mapper maps the reported result to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record Copy<Msg>(String text, Function<ClipboardResult, Msg> mapper) implements Command<Msg> {

        /**
         * Treats a null text as empty.
         */
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
     * Copies text to one selection and reports the truthful {@link ClipboardResult} as one message.
     *
     * <p>The primary selection exists on X11 and Wayland; a terminal that has none ignores the write,
     * which is why the result reports the transport and the in-process buffer rather than claiming
     * that the operating system accepted anything. The two selections are independent: copying to one
     * leaves the other as it was.</p>
     *
     * @param selection the selection to write; a missing selection copies to the system clipboard
     * @param text the text to copy; null is treated as empty
     * @param mapper maps the reported result to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record CopyTo<Msg>(ClipboardSelection selection, String text, Function<ClipboardResult, Msg> mapper)
            implements Command<Msg> {

        /**
         * Treats a null text and a null selection as the system clipboard with no text.
         */
        public CopyTo {
            selection = selection == null ? ClipboardSelection.SYSTEM : selection;
            text = text == null ? "" : text;
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new CopyTo<>(selection, text, this.mapper.andThen(mapper));
        }
    }

    /**
     * Asks the terminal what one of its clipboards holds, and reports one typed answer.
     *
     * <p>This is a question, not a read of session state: the request is written when the command is
     * interpreted and the answer arrives later as an ordinary message, exactly like
     * {@link #query}. The reply is the user's own text, so it is decoded strictly and bounded before
     * it becomes a message - a payload that is not base64, is not UTF-8 or is larger than the
     * configured read limit is reported as such and never delivered as text.</p>
     *
     * <p>The result is always exactly one message: the text, or the reason there is none. A late reply
     * after a timeout, a cancellation or an unmount satisfies nothing, and two reads of the same
     * selection are serialized because a reply carries no request identity.</p>
     *
     * <p>A read never writes the in-process buffer. An application that wants what it read to become
     * the session's paste value issues its own copy.</p>
     *
     * @param id the effect identity; replacing it invalidates an earlier read
     * @param selection the selection to read; required
     * @param mapper maps the reported result to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record ReadTerminal<Msg>(EffectId id, ClipboardSelection selection, Function<ClipboardReadResult, Msg> mapper)
            implements Command<Msg> {

        /**
         * Rejects a missing identity, selection or mapper.
         */
        public ReadTerminal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new ReadTerminal<>(id, selection, this.mapper.andThen(mapper));
        }
    }

    /**
     * Asks the terminal one bounded question and reports its typed answer as one message.
     *
     * <p>The query is written once when the command is interpreted, and its answer arrives later as
     * an ordinary message, so nothing blocks the update thread and no terminal read happens inside a
     * transition. Exactly one message follows a query: the answer, or the reason there is none
     * ({@link TerminalQuery.Reply.Unsupported}, {@link TerminalQuery.Reply.Timeout},
     * {@link TerminalQuery.Reply.Malformed}) - including when the effect is cancelled, where the
     * answer is simply never delivered.</p>
     *
     * <p>A query carries no identity, so the runtime serializes requests of the same kind and
     * discards a reply nobody is waiting for. Cancelling the effect, cancelling its scope, or
     * unmounting the component it belongs to withdraws the query: a late reply then satisfies
     * nothing.</p>
     *
     * @param query the question to ask; required
     * @param id the effect identity; replacing it invalidates an earlier query
     * @param mapper maps the terminal's answer to the delivered message
     * @param <Msg> the component message type this command produces
     */
    record Query<Msg>(EffectId id, TerminalQuery query, Function<TerminalQuery.Reply, Msg> mapper)
            implements Command<Msg> {

        /**
         * Rejects a null id, query or mapper.
         */
        public Query {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(mapper, "mapper");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Query<>(id, query, this.mapper.andThen(mapper));
        }
    }

    /**
     * Changes the colour profile the session renders with.
     *
     * <p>The runtime owns the profile because it owns the output: a command sets it, every frame is
     * re-encoded with it, and the next frame repaints the whole screen so no cell keeps a colour from
     * the previous profile. {@link io.github.dhoard.braxton.style.ColorProfile#AUTO} returns the
     * session to the profile it negotiated.</p>
     *
     * <p>Like the other owner-side built-ins this produces no message: it changes presentation, not
     * the model.</p>
     *
     * @param profile the profile to render with; null returns to the negotiated one
     * @param <Msg> the component message type this command would have produced
     */
    record ColorProfileCommand<Msg>(ColorProfile profile) implements Command<Msg> {

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new ColorProfileCommand<>(profile);
        }
    }

    /**
     * Writes one or more lines into the terminal's own content, above the live view.
     *
     * <p>This is how progress that has already happened stays readable: the line scrolls away with
     * everything else the terminal has shown and the session never paints over it again. It is a
     * presentation command, so it produces no message, and it is written where the frame is written, so
     * it cannot interleave with one.</p>
     *
     * <p>Where the line goes depends on the session, and every case is defined rather than left to the
     * terminal. On an inline session the managed live region is erased, the text is written where the
     * region began, and the region is repainted below it in the same write. On the alternate screen
     * there is no scrollback to write into, so the command is an explicit no-op: the session's screen
     * is unchanged. In a renderer-disabled session the text is written to the session's output as plain
     * text, which is what a piped or batch run wants. Text is normalized first: {@code CRLF} and a bare
     * {@code CR} become {@code LF}, control characters other than tab and newline are dropped, and an
     * empty text prints nothing.</p>
     *
     * @param text the text to print, one line per newline; required
     * @param <Msg> the component message type this command would have produced
     * @see #println(String)
     */
    record PrintLine<Msg>(String text) implements Command<Msg> {

        /**
         * Rejects a null text.
         */
        public PrintLine {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public <Msg2> Command<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new PrintLine<>(text);
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

    /**
     * Does nothing.
     *
     * @param <Msg> the component message type
     * @return the inert command
     */
    static <Msg> Command<Msg> none() {
        return new None<>();
    }

    /**
     * Ordered batch, flattened and with {@link None} entries removed.
     *
     * @param <Msg> the component message type these commands produce
     * @param commands the commands to interpret in order
     * @return one command that interprets them in order
     */
    @SafeVarargs
    static <Msg> Command<Msg> batch(Command<Msg>... commands) {
        List<Command<Msg>> copy = new ArrayList<>(commands.length);
        for (Command<Msg> command : commands) {
            copy.add(command);
        }
        return batch(copy);
    }

    /**
     * One ordered batch with empty and nested batches flattened.
     *
     * @param <Msg> the component message type these commands produce
     * @param commands the commands to interpret in order
     * @return the flattened batch, or the single command it holds
     */
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
     *
     * @param <Msg> the component message type the task produces
     * @param <T> the work's result type
     * @param id the effect identity; replacing it invalidates an earlier registration
     * @param work the application work to run on the effect executor
     * @param onSuccess maps the result to the message delivered on success
     * @param onFailure maps the failure to the message delivered on failure
     * @return the task command
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

    /**
     * Delivers a message after a delay.
     *
     * @param <Msg> the component message type
     * @param id the effect identity
     * @param delay how long to wait
     * @param message the message delivered after the delay
     * @param rejected the message delivered when the effect quota is exhausted
     * @return the delay command
     */
    static <Msg> Command<Msg> delay(EffectId id, Duration delay, Msg message, Msg rejected) {
        return new Delay<>(id, delay, message, rejected);
    }

    /**
     * Reads the runtime's monotonic clock and delivers the reading as one message.
     *
     * @param <Msg> the component message type
     * @param mapper maps the reading in nanoseconds to the delivered message
     * @return the timestamp command
     */
    static <Msg> Command<Msg> monotonicTime(Function<Long, Msg> mapper) {
        return new MonotonicTime<>(mapper);
    }

    /**
     * Runs a child process in the session's terminal while the session is released.
     *
     * @param <Msg> the component message type
     * @param request the child to run
     * @param mapper maps the child's result to a message
     * @return the handoff command
     */
    static <Msg> Command<Msg> exec(ProcessRequest request, Function<ProcessResult, Msg> mapper) {
        return new Exec<>(request, mapper);
    }

    /**
     * Suspends the session until the job controller continues it.
     *
     * @param <Msg> the component message type
     * @param mapper maps the outcome to a message
     * @return the suspend command
     */
    static <Msg> Command<Msg> suspend(Function<SuspendResult, Msg> mapper) {
        return new Suspend<>(mapper);
    }

    /**
     * Invalidates one effect.
     *
     * @param <Msg> the component message type
     * @param id the effect identity to invalidate
     * @return the cancel command
     */
    static <Msg> Command<Msg> cancel(EffectId id) {
        return new Cancel<>(id);
    }

    /**
     * Invalidates every effect in the current scope and its descendants.
     *
     * @param <Msg> the component message type
     * @return the cancel-all command
     */
    static <Msg> Command<Msg> cancelAll() {
        return new CancelAll<>();
    }

    /**
     * Enqueues an owner-local message after commit.
     *
     * @param <Msg> the component message type
     * @param message the message to deliver
     * @return the dispatch command
     */
    static <Msg> Command<Msg> dispatch(Msg message) {
        return new Dispatch<>(message);
    }

    /**
     * Continues a sequence once {@code message} has been folded into the model.
     *
     * @param <Msg> the component message type
     * @param message the message the next sequence step waits for
     * @return the update-ordered continuation
     */
    static <Msg> Command<Msg> afterUpdate(Msg message) {
        return new AfterUpdate<>(message);
    }

    /**
     * Runs commands one after another, each starting when the previous one finishes.
     *
     * @param <Msg> the component message type these commands produce
     * @param commands the commands to run in order
     * @return one command that runs them in sequence
     */
    @SafeVarargs
    static <Msg> Command<Msg> sequence(Command<Msg>... commands) {
        List<Command<Msg>> copy = new ArrayList<>(commands.length);
        for (Command<Msg> command : commands) {
            copy.add(command);
        }
        return sequence(copy);
    }

    /**
     * One sequence, with empty steps and nested sequences flattened into it.
     *
     * <p>Flattening changes nothing a program can observe and keeps a sequence from nesting a
     * submission inside a submission: {@code sequence(a, sequence(b, c), d)} runs {@code a} to
     * {@code d} in order, with each step starting when the previous one finishes.</p>
     *
     * @param <Msg> the component message type these commands produce
     * @param commands the commands to run in order
     * @return the flattened sequence, or the single command it holds
     */
    static <Msg> Command<Msg> sequence(List<Command<Msg>> commands) {
        List<Command<Msg>> flat = new ArrayList<>();
        for (Command<Msg> command : commands) {
            if (command instanceof None<Msg>) {
                continue;
            }
            if (command instanceof Sequence<Msg> sequence) {
                flat.addAll(sequence.commands());
            } else {
                flat.add(command);
            }
        }
        if (flat.isEmpty()) {
            return new None<>();
        }
        return flat.size() == 1 ? flat.get(0) : new Sequence<>(flat);
    }

    /**
     * Copies text and reports the clipboard result.
     *
     * @param <Msg> the component message type
     * @param text the text to copy
     * @param mapper maps the result to a message
     * @return the copy command
     */
    static <Msg> Command<Msg> copy(String text, Function<ClipboardResult, Msg> mapper) {
        return new Copy<>(text, mapper);
    }

    /**
     * Reads the in-process clipboard buffer.
     *
     * @param <Msg> the component message type
     * @param mapper maps the stored text to a message
     * @return the read command
     */
    static <Msg> Command<Msg> readClipboard(Function<String, Msg> mapper) {
        return new ReadClipboard<>(mapper);
    }

    /**
     * Copies text to one selection and reports the clipboard result.
     *
     * @param <Msg> the component message type
     * @param selection the selection to write
     * @param text the text to copy
     * @param mapper maps the result to a message
     * @return the copy command
     */
    static <Msg> Command<Msg> copy(ClipboardSelection selection, String text, Function<ClipboardResult, Msg> mapper) {
        return new CopyTo<>(selection, text, mapper);
    }

    /**
     * Asks the terminal what one of its clipboards holds.
     *
     * @param <Msg> the component message type
     * @param id the effect identity; replacing it invalidates an earlier read
     * @param selection the selection to read
     * @param mapper maps the result to a message
     * @return the read command
     */
    static <Msg> Command<Msg> readTerminalClipboard(
            EffectId id, ClipboardSelection selection, Function<ClipboardReadResult, Msg> mapper) {
        return new ReadTerminal<>(id, selection, mapper);
    }

    /**
     * Asks the terminal one bounded question.
     *
     * @param <Msg> the component message type
     * @param id the effect identity; replacing it invalidates an earlier query
     * @param query the question to ask
     * @param mapper maps the terminal's answer to a message
     * @return the query command
     */
    static <Msg> Command<Msg> query(EffectId id, TerminalQuery query, Function<TerminalQuery.Reply, Msg> mapper) {
        return new Query<>(id, query, mapper);
    }

    /**
     * Prints a line (or lines) above the live view.
     *
     * <p>The text is written into the terminal's own content where the session manages its live
     * region, so it survives later frames; see {@link PrintLine} for what each session mode does with
     * it. The command completes as soon as the text has been handed to the session's output, which
     * makes it a safe step in a {@link Sequence} between two effects.</p>
     *
     * @param <Msg> the component message type
     * @param text the text to print; required
     * @return the command
     */
    static <Msg> Command<Msg> println(String text) {
        return new PrintLine<>(text);
    }

    /**
     * Renders the session with another colour profile.
     *
     * @param <Msg> the component message type
     * @param profile the profile to render with; null returns to the negotiated one
     * @return the command
     */
    static <Msg> Command<Msg> colorProfile(ColorProfile profile) {
        return new ColorProfileCommand<>(profile);
    }

    /**
     * Ends the program with status 0.
     *
     * @param <Msg> the component message type
     * @return the quit command
     */
    static <Msg> Command<Msg> quit() {
        return new Quit<>(0);
    }

    /**
     * Ends the program with a status.
     *
     * @param <Msg> the component message type
     * @param status the exit status
     * @return the quit command
     */
    static <Msg> Command<Msg> quit(int status) {
        return new Quit<>(status);
    }
}
