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

package io.github.dhoard.braxton.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import io.github.dhoard.braxton.app.Timers;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalBackend;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.terminal.TerminalOptions;
import io.github.dhoard.braxton.terminal.TerminalSession;

/**
 * An in-memory terminal: scripted input, recorded output, adjustable size, and no native access.
 *
 * <p>Reads block until bytes are available or end-of-file is signalled, and a closed session drops
 * writes, exactly like a real session, so tests exercise the runtime's real input and output path
 * without a TTY.</p>
 */
public final class FakeTerminal implements TerminalBackend, TerminalSession {

    private final ScriptedInput input = new ScriptedInput();
    private final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    private volatile Size size;
    private volatile boolean open;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean virtualRead =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean virtualSize =
            new java.util.concurrent.atomic.AtomicBoolean();
    private TerminalOptions options = TerminalOptions.DEFAULT;

    public FakeTerminal(Size size) {
        this.size = size == null ? new Size(24, 80) : size;
    }

    public FakeTerminal() {
        this(new Size(24, 80));
    }

    @Override
    public TerminalSession open(Size fallback, TerminalOptions requested) {
        this.options = requested == null ? TerminalOptions.DEFAULT : requested;
        this.open = true;
        return this;
    }

    /** Appends bytes exactly as the terminal would deliver them. */
    public void feed(String text) {
        if (text != null) {
            input.feed(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Signals end-of-input for the decoder. */
    public void endInput() {
        input.end();
    }

    /** Makes the next reads throw, simulating a broken decoder or native reader. */
    public void failInput(RuntimeException failure) {
        input.fail(failure);
    }

    public void resize(Size next) {
        size = next;
    }

    public TerminalOptions options() {
        return options;
    }

    /** Everything written so far, concatenated. */
    public String output() {
        synchronized (writes) {
            return String.join("", writes);
        }
    }

    public List<String> writes() {
        synchronized (writes) {
            return List.copyOf(writes);
        }
    }

    @Override
    public TerminalCapabilities capabilities() {
        return new TerminalCapabilities(true, true, true, true, "fake");
    }

    @Override
    public Size size() {
        virtualSize.compareAndSet(false, Thread.currentThread().isVirtual());
        Size measured = size;
        return closed || measured == null || measured.isEmpty() ? new Size(24, 80) : measured;
    }

    /** Whether a read was first observed on a virtual thread. */
    public boolean readOnVirtualThread() {
        return virtualRead.get();
    }

    /** Whether a size probe was first observed on a virtual thread. */
    public boolean measuredOnVirtualThread() {
        return virtualSize.get();
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public void write(String data) {
        if (closed) {
            // BaseTerminalSession.write drops output once the session is closed; a fake that
            // recorded it would let the runtime emit a frame no real terminal ever shows.
            return;
        }
        if (data != null && !data.isEmpty()) {
            writes.add(data);
        }
    }

    @Override
    public boolean isOpen() {
        return open && !closed;
    }

    @Override
    public void close() {
        closed = true;
        open = false;
        input.end();
    }

    private final class ScriptedInput extends InputStream {

        private final Deque<Integer> bytes = new ArrayDeque<>();
        private boolean ended;
        private boolean closed;
        private RuntimeException failure;

        synchronized void feed(byte[] data) {
            for (byte value : data) {
                bytes.add(value & 0xFF);
            }
            notifyAll();
        }

        synchronized void end() {
            ended = true;
            notifyAll();
        }

        synchronized void fail(RuntimeException problem) {
            failure = problem;
            notifyAll();
        }

        @Override
        public synchronized int read() {
            recordThread();
            while (bytes.isEmpty() && !ended && !closed && failure == null) {
                try {
                    wait(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (failure != null) {
                throw failure;
            }
            if (bytes.isEmpty()) {
                return -1;
            }
            return bytes.poll();
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            recordThread();
            int first = read();
            if (first < 0) {
                return -1;
            }
            buffer[offset] = (byte) first;
            int count = 1;
            while (count < length && !bytes.isEmpty()) {
                buffer[offset + count] = (byte) (int) bytes.poll();
                count++;
            }
            return count;
        }

        @Override
        public synchronized int available() {
            return bytes.size();
        }

        private void recordThread() {
            virtualRead.compareAndSet(false, Thread.currentThread().isVirtual());
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            notifyAll();
        }
    }

    /** Deterministic timers: nothing runs until the test advances the clock. */
    public static final class ManualTimers implements Timers {

        private final List<Task> tasks = new ArrayList<>();
        private long nowNanos;

        private static final class Task {
            final long dueNanos;
            final Runnable runnable;
            final long sequence;
            boolean cancelled;

            Task(long dueNanos, Runnable runnable, long sequence) {
                this.dueNanos = dueNanos;
                this.runnable = runnable;
                this.sequence = sequence;
            }
        }

        private long sequence;

        @Override
        public synchronized Handle schedule(long delayNanos, Runnable task) {
            Task scheduled = new Task(nowNanos + Math.max(0L, delayNanos), task, sequence++);
            tasks.add(scheduled);
            return () -> {
                synchronized (ManualTimers.this) {
                    scheduled.cancelled = true;
                }
            };
        }

        /** Runs every task due at or before {@code now + nanos}, in due order. */
        public synchronized void advance(long nanos) {
            nowNanos += Math.max(0L, nanos);
            while (true) {
                Task next = null;
                for (Task task : tasks) {
                    if (task.cancelled || task.dueNanos > nowNanos) {
                        continue;
                    }
                    if (next == null || task.dueNanos < next.dueNanos
                            || (task.dueNanos == next.dueNanos && task.sequence < next.sequence)) {
                        next = task;
                    }
                }
                if (next == null) {
                    return;
                }
                tasks.remove(next);
                next.runnable.run();
            }
        }

        public synchronized int pending() {
            int count = 0;
            for (Task task : tasks) {
                if (!task.cancelled) {
                    count++;
                }
            }
            return count;
        }

        public synchronized long nowNanos() {
            return nowNanos;
        }
    }
}
