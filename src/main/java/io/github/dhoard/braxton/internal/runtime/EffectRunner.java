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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import io.github.dhoard.braxton.app.RuntimeEnvironment;
import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;

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
        final long epoch;
        final boolean task;
        final Duration delay;
        Timers.Handle timer;
        volatile Thread thread;

        Entry(EffectId id, long epoch, boolean task, Duration delay) {
            this.id = id;
            this.epoch = epoch;
            this.task = task;
            this.delay = delay;
        }
    }

    private final Mailbox mailbox;
    private final RuntimeEnvironment environment;
    private final ClipboardService clipboard;
    private final int maxEffects;
    private final Map<EffectId, Entry> entries = new HashMap<>();
    private final Object lock = new Object();
    private final List<Thread> running = new ArrayList<>();

    private Thread owner = Thread.currentThread();
    private long nextEpoch = 1L;
    private boolean quitRequested;
    private int quitStatus;

    public EffectRunner(Mailbox mailbox, RuntimeEnvironment environment,
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
        if (command == null) {
            return;
        }
        switch (command) {
            case Command.None<Msg> none -> { }
            case Command.Batch<Msg> batch -> {
                for (Command<Msg> item : batch.commands()) {
                    interpret(item);
                    if (quitRequested) {
                        return;
                    }
                }
            }
            case Command.Delay<Msg> delay -> startDelay(delay);
            case Command.Task<Msg> task -> startTask(task);
            case Command.Cancel<Msg> cancel -> cancel(cancel.id());
            case Command.CancelNamespace<Msg> cancel -> cancelNamespace(cancel.namespace());
            case Command.ReadClipboard<Msg> read -> deliver(read.mapper().apply(clipboard.internalText()));
            case Command.Copy<Msg> copy -> {
                ClipboardResult result = clipboard.copy(copy.text());
                deliver(copy.mapper().apply(result));
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
                deliver(command.rejected());
                return;
            }
            entry = new Entry(command.id(), nextEpoch++, false, command.delay());
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
        deliver(command.message());
    }

    private void startTask(Command.Task<Msg> command) {
        Entry entry;
        synchronized (lock) {
            if (entries.size() >= maxEffects && !entries.containsKey(command.id())) {
                deliverFailure(command,
                        new IllegalStateException("effect quota exhausted: " + command.id()));
                return;
            }
            entry = new Entry(command.id(), nextEpoch++, true, Duration.ZERO);
            replaceLocked(entry);
        }
        Executor executor = environment.effectExecutor();
        try {
            executor.execute(() -> runTask(command, entry));
        } catch (RejectedExecutionException rejected) {
            finish(entry);
            deliverFailure(command, rejected);
        } catch (RuntimeException rejected) {
            finish(entry);
            deliverFailure(command, rejected);
        }
    }

    private void runTask(Command.Task<Msg> command, Entry entry) {
        entry.thread = Thread.currentThread();
        synchronized (lock) {
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
        boolean current = finish(entry);
        if (!current) {
            return;
        }
        if (failure != null) {
            deliverFailure(command, failure);
            return;
        }
        Msg message;
        try {
            message = command.onSuccess().apply(value);
        } catch (Throwable mapperFailure) {
            // A completion mapper is application code. If it throws, the effect must still resolve:
            // report the mapper's failure through the failure mapper instead of losing the effect
            // on an uncaught exception.
            deliverFailure(command, mapperFailure);
            return;
        }
        deliver(message);
    }

    /** Reports a failed or unmappable completion, containing a throwing failure mapper. */
    private void deliverFailure(Command.Task<Msg> command, Throwable failure) {
        try {
            deliver(command.onFailure().apply(failure));
        } catch (Throwable mapperFailure) {
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
            entry = entries.remove(id);
        }
        if (entry != null) {
            cancelEntry(entry);
        }
    }

    private void cancelNamespace(String namespace) {
        List<Entry> cancelled = new ArrayList<>();
        synchronized (lock) {
            String prefix = namespace + "/";
            for (EffectId id : new ArrayList<>(entries.keySet())) {
                if (id.value().equals(namespace) || id.value().startsWith(prefix)) {
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

    private void deliver(Msg message) {
        if (message == null) {
            return;
        }
        Mailbox.Envelope envelope = new Mailbox.AppMessage(message);
        if (Thread.currentThread() == owner) {
            mailbox.enqueueFromOwner(envelope);
            return;
        }
        try {
            mailbox.enqueue(envelope, 1_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
