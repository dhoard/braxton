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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardReadResult;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import io.github.dhoard.braxton.effect.SuspendResult;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.style.ColorProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Interprets inert {@link Command} values: exactly once, on the owner thread for structured
 * effects, and on the effect executor for application tasks.
 *
 * <p>Every one-shot effect is registered under its id. Registering that id again replaces the
 * registration, and cancelling removes it, so a late completion compares itself with the live
 * registration and can never reach the model. Results are enqueued as ordinary messages; the
 * interpreter never calls {@code update} itself.</p>
 * @param <Msg> the component message type effects deliver
 */
public final class EffectRunner<Msg> {

    /** The completion of a command that is not a sequence step. */
    private static final Runnable NOTHING = () -> {};

    private static final class Entry {
        final EffectId id;
        final ScopeManager.Origin origin;
        final boolean task;
        final Duration delay;
        Timers.Handle timer;
        volatile Thread thread;
        /** What a sequence waits for when this entry is one of its steps, or null. */
        private final Runnable finished;

        private final AtomicBoolean claimed = new AtomicBoolean();

        Entry(EffectId id, boolean task, Duration delay, ScopeManager.Origin origin, Runnable finished) {
            this.origin = origin;
            this.id = id;
            this.task = task;
            this.delay = delay;
            this.finished = finished;
        }

        /** Declares the work over; the completion runs once, however the entry resolved. */
        void complete() {
            if (finished == null || !claimed.compareAndSet(false, true)) return;
            finished.run();
        }
    }

    private final Mailbox mailbox;
    private final RuntimeServices environment;
    private final ClipboardService clipboard;
    private final int maxEffects;
    private final Map<EffectId, Entry> entries = new HashMap<>();
    // Completed registrations live only until their envelope is consumed, keeping queued-result
    // cancellation safe without retaining a generation for every ID ever seen.
    private final Map<EffectId, Entry> deliveries = new HashMap<>();
    private final Object lock = new Object();
    private final List<Thread> running = new ArrayList<>();

    private ScopeManager scopes;
    private Consumer<Mailbox.AppMessage> dispatch = mailboxMessage -> {
        throw new IllegalStateException("local dispatch requires an owner queue");
    };

    private TerminalQueries<Msg> queries;
    private Consumer<ColorProfile> presentation = profile -> {};
    private Function<ProcessRequest, ProcessResult> handoff =
            request -> new ProcessResult.Rejected("this session cannot hand off its terminal");
    private Supplier<SuspendResult> suspension = () -> new SuspendResult.Refused("this session cannot suspend");
    /** Whether a graceful quit request is accepted; a session without a filter accepts every one. */
    private IntPredicate quitGate = status -> true;

    /** Where durable output goes; a session that binds no printer reports the loss as a diagnostic. */
    private Consumer<String> printer;

    public void bindScopes(ScopeManager scopes, Consumer<Mailbox.AppMessage> dispatch) {
        this.scopes = scopes;
        this.dispatch = dispatch;
    }

    /** Binds the session's reversible terminal handoff that {@link Command.Exec} is interpreted by. */
    public void bindHandoff(Function<ProcessRequest, ProcessResult> handoff) {
        if (handoff != null) {
            this.handoff = handoff;
        }
    }

    /** Binds the session's suspend/resume support that {@link Command.Suspend} is interpreted by. */
    public void bindSuspension(Supplier<SuspendResult> suspension) {
        if (suspension != null) {
            this.suspension = suspension;
        }
    }

    /** Binds the terminal query engine that {@link Command.Query} is interpreted by. */
    public void bindQueries(TerminalQueries<Msg> queries) {
        this.queries = queries;
    }

    /** Binds the session presentation a {@link Command.ColorProfileCommand} changes. */
    public void bindPresentation(Consumer<ColorProfile> presentation) {
        this.presentation = presentation;
    }

    /**
     * Binds the session's durable output that {@link Command.PrintLine} is interpreted by.
     *
     * <p>Printing happens on the owner thread, inside the command's interpretation, so the text is
     * written with the same lock as the frames it appears above and never between a frame's
     * synchronized-update bracket. The interpreter never touches the terminal itself.</p>
     */
    public void bindPrinter(Consumer<String> printer) {
        if (printer != null) {
            this.printer = printer;
        }
    }

    /** Binds the decision a {@link Command.Quit} is offered to before it ends the run. */
    public void bindQuitGate(IntPredicate gate) {
        if (gate != null) {
            this.quitGate = gate;
        }
    }

    private ScopeManager.Origin origin(ComponentPath path) {
        return scopes == null ? null : scopes.capture(path);
    }

    /**
     * Whether a scoped command still owns a component, discarding it once the commit removed it.
     *
     * <p>Every command variant must validate its origin before doing work, even commands such as
     * quit or cancellation that produce no message and never capture a delivery origin.</p>
     */
    private boolean live(ComponentPath path) {
        if (scopes == null || scopes.isActive(path)) return true;
        if (scopes.wasJustRemoved(path)) {
            environment.diagnostics().accept("discarded command for removed component " + path);
            return false;
        }
        scopes.capture(path); // Reject an undeclared origin before interpreting any command variant.
        return true;
    }

    private Thread owner = Thread.currentThread();
    private boolean quitRequested;
    private int quitStatus;

    public EffectRunner(Mailbox mailbox, RuntimeServices environment, ClipboardService clipboard, int maxEffects) {
        this.mailbox = mailbox;
        this.environment = environment;
        this.clipboard = clipboard;
        this.maxEffects = Math.max(0, maxEffects);
        this.printer = text -> environment.diagnostics().accept("printed text has nowhere to go: " + text);
    }

    /** Marks the thread allowed to enqueue without waiting for itself. */
    public void bindOwner(Thread thread) {
        this.owner = thread;
    }

    public boolean quitRequested() {
        return quitRequested;
    }

    public int quitStatus() {
        return quitStatus;
    }

    /** Interprets one command; batches run in order and stop after a quit. */
    public void interpret(Command<Msg> command) {
        interpret(command, ComponentPath.ROOT, NOTHING);
    }

    /**
     * Interprets one command and reports when its work is over.
     *
     * <p>{@code onComplete} runs exactly once for a step of a {@link Command.Sequence}: on the owner
     * thread when the step finished while it was being submitted (an immediate command), or on
     * whichever thread resolved an asynchronous one - the sequence then resumes on the owner through
     * a {@link Mailbox.Resume} envelope. A step that was cancelled, or whose component is gone, never
     * completes, which is how a sequence stops instead of running work nobody is waiting for.</p>
     *
     * @param command the command to interpret
     * @param path the structural scope the command belongs to
     * @param onComplete what to run when the command has finished
     */
    private void interpret(Command<Msg> command, ComponentPath path, Runnable onComplete) {
        if (quitRequested || command == null) return;
        switch (command) {
            case Command.None<Msg> none -> onComplete.run();
            case Command.Batch<Msg> batch -> batch(batch, path, onComplete);
            case Command.Sequence<Msg> sequence -> sequence(sequence, path, onComplete);
            case Command.Scoped<Msg> scoped -> {
                ComponentPath target = scoped.path().prepend(path);
                // A command returned by the update whose commit tore its own scope down is void:
                // the component no longer exists, so nothing it asked for may run. This is the
                // normal "child emits, parent unmounts it" shape, not a contract violation.
                if (!live(target)) {
                    onComplete.run();
                    return;
                }
                interpret(scoped.command(), target, onComplete);
            }
            case Command.Dispatch<Msg> local -> {
                dispatch.accept(new Mailbox.AppMessage(local.message(), origin(path)));
                onComplete.run();
            }
            case Command.AfterUpdate<Msg> local ->
                dispatch.accept(new Mailbox.AppMessage(local.message(), origin(path), null, onComplete));
            case Command.Delay<Msg> delay ->
                startDelay(
                        new Command.Delay<>(delay.id().scoped(path), delay.delay(), delay.message(), delay.rejected()),
                        onComplete);
            case Command.Task<Msg> task ->
                startTask(
                        new Command.Task<>(task.id().scoped(path), task.work(), task.onSuccess(), task.onFailure()),
                        onComplete);
            case Command.MonotonicTime<Msg> time -> {
                // An immediate read on the owner thread, so the mapper sees no concurrent update and
                // the message is enqueued like any other result. Nothing is registered, so there is
                // nothing to cancel.
                deliver(time.mapper().apply(environment.nanoTime().getAsLong()), origin(path));
                onComplete.run();
            }
            case Command.Exec<Msg> exec -> {
                // The handoff runs here, on the owner thread, and blocks until the child has exited
                // and the terminal has been taken back. Its result is delivered afterwards, which is
                // what "one scoped completion" means: the application's screen is its own again
                // before it learns what happened.
                deliver(exec.mapper().apply(handoff.apply(exec.request())), origin(path));
                onComplete.run();
            }
            case Command.Suspend<Msg> suspend -> {
                deliver(suspend.mapper().apply(suspension.get()), origin(path));
                onComplete.run();
            }
            case Command.Cancel<Msg> cancel -> {
                cancel(cancel.id().scoped(path));
                onComplete.run();
            }
            case Command.CancelAll<Msg> cancel -> {
                cancelScope(path);
                onComplete.run();
            }
            case Command.Query<Msg> query -> {
                TerminalQueries<Msg> engine = queries;
                if (engine == null) {
                    // Without an engine the answer is still exactly one message, and it says what
                    // happened: the session has no terminal to ask.
                    deliver(
                            query.mapper()
                                    .apply(new io.github.dhoard.braxton.app.TerminalQuery.Reply.Malformed(
                                            query.query(), "the session has no terminal query engine")),
                            origin(path));
                    onComplete.run();
                } else {
                    engine.submit(query.id().scoped(path), query.query(), query.mapper(), path, onComplete);
                }
            }
            case Command.ColorProfileCommand<Msg> color -> {
                presentation.accept(color.profile());
                onComplete.run();
            }
            case Command.PrintLine<Msg> print -> {
                printer.accept(print.text());
                onComplete.run();
            }
            case Command.ReadClipboard<Msg> read -> {
                deliver(read.mapper().apply(clipboard.internalText()), origin(path));
                onComplete.run();
            }
            case Command.Copy<Msg> copy -> {
                ClipboardResult result = clipboard.copy(copy.text());
                deliver(copy.mapper().apply(result), origin(path));
                onComplete.run();
            }
            case Command.CopyTo<Msg> copy -> {
                ClipboardResult result = clipboard.copy(copy.selection(), copy.text());
                deliver(copy.mapper().apply(result), origin(path));
                onComplete.run();
            }
            case Command.ReadTerminal<Msg> read -> read(read, path, onComplete);
            case Command.Quit<Msg> quit -> {
                // A graceful quit is offered to the message filter like any other message the session
                // acts on, so an application can guard its exit. A vetoed quit leaves the run going
                // and finishes the enclosing step: the request was answered, not lost.
                if (quitGate.test(quit.status())) {
                    quitRequested = true;
                    quitStatus = quit.status();
                }
                onComplete.run();
            }
        }
    }

    /** One batch: finished when every member has, whatever order they finish in. */
    private void batch(Command.Batch<Msg> batch, ComponentPath path, Runnable onComplete) {
        int size = batch.commands().size();
        if (size == 0) {
            onComplete.run();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(size);
        Runnable member = () -> {
            if (remaining.decrementAndGet() == 0) {
                onComplete.run();
            }
        };
        for (Command<Msg> item : batch.commands()) {
            interpret(item, path, member);
            if (quitRequested) {
                return;
            }
        }
    }

    /** One sequence: each step submitted only after the previous one finished. */
    private void sequence(Command.Sequence<Msg> sequence, ComponentPath path, Runnable onComplete) {
        submit(sequence.commands(), 0, path, origin(path), onComplete);
    }

    /**
     * Submits sequence steps from {@code start} until one of them is still running.
     *
     * <p>The loop is iterative so a long sequence cannot recurse once per step; only a step that is
     * still running ends it, and that step's completion re-enters here on the owner thread. An origin
     * that is no longer current - the component was removed, or a cancel-all tore the scope down -
     * ends the sequence where it stands: the remaining steps belong to work the application has
     * already invalidated.</p>
     */
    private void submit(
            List<Command<Msg>> steps, int start, ComponentPath path, ScopeManager.Origin origin, Runnable onComplete) {
        int at = start;
        while (at < steps.size()) {
            if (!alive(origin)) {
                return;
            }
            Completion completion = new Completion();
            interpret(steps.get(at), path, completion);
            if (!completion.finished()) {
                int next = at + 1;
                completion.whenFinished(() -> resumeOnOwner(() -> submit(steps, next, path, origin, onComplete)));
                return;
            }
            at++;
        }
        onComplete.run();
    }

    /** Whether a sequence may still submit work: not quit, and its scope still current. */
    private boolean alive(ScopeManager.Origin origin) {
        return !quitRequested && (origin == null || scopes == null || scopes.isCurrent(origin));
    }

    /**
     * Runs {@code action} on the owner thread, wherever it is called from.
     *
     * <p>A sequence step that finishes on a timer or task thread resubmits through the mailbox, after
     * the message it produced, so the application sees its messages and its sequence steps in one
     * order rather than two.</p>
     */
    private void resumeOnOwner(Runnable action) {
        if (Thread.currentThread() == owner) {
            action.run();
            return;
        }
        Mailbox.Envelope envelope = new Mailbox.Resume(action);
        while (true) {
            try {
                if (mailbox.enqueue(envelope, 100L)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (mailbox.isClosed()) return;
        }
    }

    /**
     * One step's completion: signalled inline while the interpreter is still in the step, or later
     * from whichever thread finished it.
     */
    private static final class Completion implements Runnable {
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<Runnable> continuation = new AtomicReference<>();

        @Override
        public void run() {
            if (!finished.compareAndSet(false, true)) return;
            Runnable next = continuation.getAndSet(null);
            if (next != null) next.run();
        }

        boolean finished() {
            return finished.get();
        }

        /** Runs {@code action} when the step finishes, or now when it already has. */
        void whenFinished(Runnable action) {
            continuation.set(action);
            if (finished.get() && continuation.compareAndSet(action, null)) {
                action.run();
            }
        }
    }

    /**
     * Asks the terminal for one clipboard, or reports why the session cannot ask.
     *
     * <p>A session that cannot write an escape sequence has nothing to ask with, so the answer is
     * produced immediately and truthfully instead of writing a question that could only time out. The
     * request itself is correlated by the terminal query engine, which already owns the reply
     * deadline, the per-kind serialization and the rule that a reply nobody waits for satisfies
     * nothing.</p>
     */
    private void read(Command.ReadTerminal<Msg> read, ComponentPath path, Runnable onComplete) {
        ClipboardPath transport = clipboard.readPath();
        if (transport == null) {
            deliver(
                    read.mapper()
                            .apply(new ClipboardReadResult.Disabled(
                                    read.selection(), "this session cannot ask the terminal for its clipboard")),
                    origin(path));
            onComplete.run();
            return;
        }
        TerminalQueries<Msg> engine = queries;
        if (engine == null) {
            deliver(
                    read.mapper()
                            .apply(new ClipboardReadResult.Disabled(
                                    read.selection(), "the session has no terminal query engine")),
                    origin(path));
            onComplete.run();
            return;
        }
        engine.submit(
                read.id().scoped(path),
                new TerminalQuery.ClipboardRead(read.selection(), transport),
                reply -> read.mapper().apply(readResult(read.selection(), transport, reply)),
                path,
                onComplete);
    }

    /** One terminal answer as the clipboard result the application sees. */
    private static ClipboardReadResult readResult(
            ClipboardSelection selection, ClipboardPath transport, TerminalQuery.Reply reply) {
        if (reply instanceof TerminalQuery.Reply.Clipboard clipboard) {
            return new ClipboardReadResult.Text(selection, transport, clipboard.text(), clipboard.byteLength());
        }
        if (reply instanceof TerminalQuery.Reply.Oversize oversize) {
            return new ClipboardReadResult.Oversize(selection, transport, oversize.limit());
        }
        if (reply instanceof TerminalQuery.Reply.Unsupported) {
            return new ClipboardReadResult.Refused(selection, transport);
        }
        if (reply instanceof TerminalQuery.Reply.Timeout) {
            return new ClipboardReadResult.Timeout(selection, transport);
        }
        if (reply instanceof TerminalQuery.Reply.Rejected rejected) {
            return new ClipboardReadResult.Rejected(selection, transport, rejected.outstanding());
        }
        // The remaining forms are how a clipboard read reports an unbelievable answer. A reply about
        // another query cannot reach here, and is still not text the application may use.
        String detail = reply instanceof TerminalQuery.Reply.Malformed malformed
                ? malformed.detail()
                : "the terminal answered another query";
        return new ClipboardReadResult.Invalid(selection, transport, detail);
    }

    private void startDelay(Command.Delay<Msg> command, Runnable onComplete) {
        Entry entry;
        synchronized (lock) {
            if (entries.size() >= maxEffects && !entries.containsKey(command.id())) {
                deliver(command.rejected(), origin(command.id().path()));
                onComplete.run();
                return;
            }
            entry = new Entry(
                    command.id(), false, command.delay(), origin(command.id().path()), onComplete);
            replaceLocked(entry);
        }
        Timers.Handle handle =
                environment.timers().schedule(command.delay().toNanos(), () -> fireDelay(command, entry));
        synchronized (lock) {
            entry.timer = handle;
            if (!isCurrent(entry)) {
                handle.cancel();
            }
        }
    }

    private void fireDelay(Command.Delay<Msg> command, Entry entry) {
        synchronized (lock) {
            if (!isCurrent(entry)) {
                return;
            }
            entries.remove(entry.id, entry);
        }
        deliver(command.message(), entry.origin, entry);
        entry.complete();
    }

    private void startTask(Command.Task<Msg> command, Runnable onComplete) {
        Entry entry;
        synchronized (lock) {
            if (entries.size() >= maxEffects && !entries.containsKey(command.id())) {
                deliverFailure(command, new IllegalStateException("effect quota exhausted: " + command.id()));
                onComplete.run();
                return;
            }
            entry = new Entry(
                    command.id(), true, Duration.ZERO, origin(command.id().path()), onComplete);
            replaceLocked(entry);
        }
        Executor executor = environment.effectExecutor();
        try {
            executor.execute(() -> runTask(command, entry));
        } catch (RejectedExecutionException rejected) {
            deliverFailure(command, rejected, entry);
            entry.complete();
        } catch (RuntimeException rejected) {
            deliverFailure(command, rejected, entry);
            entry.complete();
        }
    }

    private void runTask(Command.Task<Msg> command, Entry entry) {
        synchronized (lock) {
            // An executor can start this runnable after cancellation, replacement, or shutdown.
            // Check and publish the running thread atomically with respect to invalidation: either
            // the body is skipped, or cancellation can interrupt it once startup has been claimed.
            if (!isCurrent(entry)) {
                return;
            }
            entry.thread = Thread.currentThread();
            running.add(Thread.currentThread());
        }
        Object value = null;
        Throwable failure = null;
        try {
            value = command.work().call();
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            synchronized (lock) {
                running.remove(Thread.currentThread());
                lock.notifyAll();
            }
        }
        synchronized (lock) {
            if (!isCurrent(entry)) {
                return;
            }
        }
        if (failure != null) {
            deliverFailure(command, failure, entry);
            entry.complete();
            return;
        }
        Msg message;
        try {
            message = command.onSuccess().apply(value);
        } catch (Throwable mapperFailure) {
            // A completion mapper is application code. If it throws, the effect must still resolve:
            // report the mapper's failure through the failure mapper instead of losing the effect
            // on an uncaught exception.
            deliverFailure(command, mapperFailure, entry);
            entry.complete();
            return;
        }
        // Mapping is application code and can outlive cancellation or replacement. Keep the
        // registration until mapping finishes, then claim completion only if it is still current.
        if (finish(entry)) {
            deliver(message, entry.origin, entry);
            entry.complete();
        }
    }

    /** Reports a failed or unmappable completion, containing a throwing failure mapper. */
    private void deliverFailure(Command.Task<Msg> command, Throwable failure) {
        deliverFailure(command, failure, null);
    }

    private void deliverFailure(Command.Task<Msg> command, Throwable failure, Entry entry) {
        try {
            Msg message = command.onFailure().apply(failure);
            if (entry == null) {
                deliver(message, origin(command.id().path()));
            } else if (finish(entry)) {
                deliver(message, entry.origin, entry);
            }
        } catch (Throwable mapperFailure) {
            if (entry != null) {
                finish(entry);
                accept(entry);
            }
            environment
                    .diagnostics()
                    .accept("effect " + command.id() + ": failure mapper threw "
                            + mapperFailure.getClass().getSimpleName());
        }
    }

    /** Removes the entry when it is still current; returns whether it was. */
    private boolean finish(Entry entry) {
        synchronized (lock) {
            if (!isCurrent(entry)) {
                return false;
            }
            entries.remove(entry.id, entry);
            return true;
        }
    }

    private void replaceLocked(Entry entry) {
        deliveries.put(entry.id, entry);
        Entry previous = entries.put(entry.id, entry);
        if (previous != null) {
            cancelEntry(previous);
        }
    }

    private boolean isCurrent(Entry entry) {
        return entries.get(entry.id) == entry;
    }

    private void cancel(EffectId id) {
        Entry entry;
        synchronized (lock) {
            deliveries.remove(id);
            entry = entries.remove(id);
        }
        if (entry != null) {
            cancelEntry(entry);
        }
        if (queries != null) {
            queries.cancel(id);
        }
    }

    public void cancelScope(ComponentPath path) {
        List<Entry> cancelled = new ArrayList<>();
        synchronized (lock) {
            deliveries.keySet().removeIf(id -> id.path().startsWith(path));
            for (EffectId id : new ArrayList<>(entries.keySet())) {
                if (id.path().startsWith(path)) {
                    cancelled.add(entries.remove(id));
                }
            }
        }
        for (Entry entry : cancelled) {
            cancelEntry(entry);
        }
        if (queries != null) {
            queries.cancelScope(path);
        }
    }

    private void cancelEntry(Entry entry) {
        if (entry.timer != null) {
            entry.timer.cancel();
        }
        Thread thread = entry.thread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    /** Invalidates every pending effect and requests interruption of running tasks. */
    public void cancelAll() {
        List<Entry> cancelled;
        synchronized (lock) {
            cancelled = new ArrayList<>(entries.values());
            entries.clear();
            deliveries.clear();
        }
        for (Entry entry : cancelled) {
            cancelEntry(entry);
        }
    }

    /** Waits up to {@code grace} for running task bodies to return; reports how many remain. */
    public int awaitIdle(Duration grace) {
        long deadline = System.nanoTime() + Math.max(0L, grace.toNanos());
        synchronized (lock) {
            while (!running.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    lock.wait(Math.max(1L, remaining / 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return running.size();
        }
    }

    /** Claims one completed delivery unless cancellation or replacement already invalidated it. */
    private boolean accept(Entry entry) {
        synchronized (lock) {
            return deliveries.remove(entry.id, entry);
        }
    }

    private void deliver(Msg message, ScopeManager.Origin origin) {
        deliver(message, origin, null);
    }

    /**
     * Delivers one result to the owner.
     *
     * <p>Owner-side delivery never waits (an overflow there is fatal by design). From an effect
     * thread a full mailbox means the owner is still working, not that the result is disposable, so
     * the wait is retried until the mailbox accepts it or is closed: a fixed timeout would silently
     * lose a task result or a delayed message.</p>
     */
    private void deliver(Msg message, ScopeManager.Origin origin, Entry entry) {
        if (message == null) {
            if (entry != null) accept(entry);
            return;
        }
        Mailbox.Envelope envelope = entry == null
                ? new Mailbox.AppMessage(message, origin)
                : new Mailbox.AppMessage(message, origin, () -> accept(entry));
        if (Thread.currentThread() == owner) {
            // A result the session can no longer hold - one produced after shutdown closed the
            // mailbox, as a handoff completion is when a signal ends the run - is dropped exactly
            // as a producer's would be, instead of turning a clean end into an overflow failure.
            if (mailbox.isClosed()) {
                if (entry != null) accept(entry);
                return;
            }
            mailbox.enqueueFromOwner(envelope);
            return;
        }
        while (true) {
            try {
                if (mailbox.enqueue(envelope, 100L)) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (mailbox.isClosed()) {
                return;
            }
        }
    }
}
