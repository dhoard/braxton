/*
 * Copyright (c) 2026-present Doug Hoard
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

import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.mvu.ComponentPath;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Interprets inert {@link Command} values: exactly once, on the owner thread for structured
 * effects, and on the effect executor for application tasks.
 *
 * <p>Every one-shot effect is registered under its id and an epoch. Replacing an id invalidates the
 * old epoch, so a late completion can never reach the model. Results are enqueued as ordinary
 * messages; the interpreter never calls {@code update} itself.</p>
 */
public final class EffectRunner<Msg> {

    private static final class Entry {
        final EffectId id;
        final ScopeManager.Origin origin;
        final long epoch;
        final boolean task;
        final Duration delay;
        Timers.Handle timer;
        volatile Thread thread;

        Entry(EffectId id, long epoch, boolean task, Duration delay, ScopeManager.Origin origin) {
            this.origin = origin;
            this.id = id;
            this.epoch = epoch;
            this.task = task;
            this.delay = delay;
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
    private Consumer<Mailbox.AppMessage> dispatch =
            mailboxMessage -> {
                throw new IllegalStateException("local dispatch requires an owner queue");
            };

    public void bindScopes(ScopeManager scopes, Consumer<Mailbox.AppMessage> dispatch) {
        this.scopes = scopes;
        this.dispatch = dispatch;
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
    private long nextEpoch = 1L;
    private boolean quitRequested;
    private int quitStatus;

    public EffectRunner(Mailbox mailbox, RuntimeServices environment,
            ClipboardService clipboard, int maxEffects) {
        this.mailbox = mailbox;
        this.environment = environment;
        this.clipboard = clipboard;
        this.maxEffects = Math.max(0, maxEffects);
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
        interpret(command, ComponentPath.ROOT);
    }

    private void interpret(Command<Msg> command, ComponentPath path) {
        if (quitRequested || command == null) return;
        switch (command) {
            case Command.None<Msg> none -> { }
            case Command.Batch<Msg> batch -> {
                for (Command<Msg> item : batch.commands()) {
                    interpret(item, path);
                    if (quitRequested) {
                        return;
                    }
                }
            }
            case Command.Scoped<Msg> scoped -> {
                ComponentPath target = scoped.path().prepend(path);
                // A command returned by the update whose commit tore its own scope down is void:
                // the component no longer exists, so nothing it asked for may run. This is the
                // normal "child emits, parent unmounts it" shape, not a contract violation.
                if (!live(target)) return;
                interpret(scoped.command(), target);
            }
            case Command.Dispatch<Msg> local ->
                    dispatch.accept(new Mailbox.AppMessage(local.message(), origin(path)));
            case Command.Delay<Msg> delay ->
                    startDelay(
                            new Command.Delay<>(
                                    delay.id().scoped(path),
                                    delay.delay(),
                                    delay.message(),
                                    delay.rejected()));
            case Command.Task<Msg> task ->
                    startTask(
                            new Command.Task<>(
                                    task.id().scoped(path),
                                    task.work(),
                                    task.onSuccess(),
                                    task.onFailure()));
            case Command.Cancel<Msg> cancel -> cancel(cancel.id().scoped(path));
            case Command.CancelAll<Msg> cancel -> cancelScope(path);
            case Command.ReadClipboard<Msg> read ->
                    deliver(read.mapper().apply(clipboard.internalText()), origin(path));
            case Command.Copy<Msg> copy -> {
                ClipboardResult result = clipboard.copy(copy.text());
                deliver(copy.mapper().apply(result), origin(path));
            }
            case Command.Quit<Msg> quit -> {
                quitRequested = true;
                quitStatus = quit.status();
            }
        }
    }

    private void startDelay(Command.Delay<Msg> command) {
        Entry entry;
        synchronized (lock) {
            if (entries.size() >= maxEffects && !entries.containsKey(command.id())) {
                deliver(command.rejected(), origin(command.id().path()));
                return;
            }
            entry =
                    new Entry(
                            command.id(),
                            nextEpoch++,
                            false,
                            command.delay(),
                            origin(command.id().path()));
            replaceLocked(entry);
        }
        Timers.Handle handle = environment.timers().schedule(
                command.delay().toNanos(), () -> fireDelay(command, entry));
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
    }

    private void startTask(Command.Task<Msg> command) {
        Entry entry;
        synchronized (lock) {
            if (entries.size() >= maxEffects && !entries.containsKey(command.id())) {
                deliverFailure(command,
                        new IllegalStateException("effect quota exhausted: " + command.id()));
                return;
            }
            entry =
                    new Entry(
                            command.id(),
                            nextEpoch++,
                            true,
                            Duration.ZERO,
                            origin(command.id().path()));
            replaceLocked(entry);
        }
        Executor executor = environment.effectExecutor();
        try {
            executor.execute(() -> runTask(command, entry));
        } catch (RejectedExecutionException rejected) {
            deliverFailure(command, rejected, entry);
        } catch (RuntimeException rejected) {
            deliverFailure(command, rejected, entry);
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
            return;
        }
        // Mapping is application code and can outlive cancellation or replacement. Keep the
        // registration until mapping finishes, then claim completion only if it is still current.
        if (finish(entry)) {
            deliver(message, entry.origin, entry);
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
            environment.diagnostics().accept("effect " + command.id()
                    + ": failure mapper threw " + mapperFailure.getClass().getSimpleName());
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
        Mailbox.Envelope envelope =
                entry == null
                        ? new Mailbox.AppMessage(message, origin)
                        : new Mailbox.AppMessage(message, origin, () -> accept(entry));
        if (Thread.currentThread() == owner) {
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
