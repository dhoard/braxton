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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.layout.Size;

/**
 * Bounded hand-off between producers (input, effects, timers, sources) and the runtime owner.
 *
 * <p>Producers block while the queue is full and are released when the runtime closes. Messages
 * generated on the owner thread never wait for the owner: an owner-side overflow is fatal rather
 * than recursive or silent. A close sets the flag and signals both waiters, so a full queue can
 * never delay shutdown and a blocked consumer returns immediately.</p>
 */
public final class Mailbox {

    /** One item in the hand-off queue. */
    public sealed interface Envelope permits AppMessage, Input, Resize, Close {
    }

    /** An application message produced by routing, a command, or a source. */
    public record AppMessage(Object payload) implements Envelope {
    }

    /** Raw normalized input, routed later against the latest committed view. */
    public record Input(InputEvent event) implements Envelope {
    }

    /** A measured terminal resize. */
    public record Resize(Size size) implements Envelope {
    }

    /** A request to stop the runtime; generated inside the runtime or by a backend. */
    public record Close(ExitResult result) implements Envelope {
    }

    /** Largest millisecond timeout that still converts to nanoseconds without overflowing. */
    private static final long MAX_WAIT_MILLIS = Long.MAX_VALUE / 1_000_000L;

    private final Deque<Envelope> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final int capacity;
    private volatile boolean closed;

    public Mailbox(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Enqueues from a non-owner thread, blocking at most {@code timeoutMillis} while the queue is
     * full. Returns false when the runtime closed or the wait was interrupted.
     */
    public boolean enqueue(Envelope envelope, long timeoutMillis) throws InterruptedException {
        long remaining = waitNanos(timeoutMillis);
        lock.lockInterruptibly();
        try {
            while (queue.size() >= capacity) {
                if (closed || remaining <= 0L) {
                    return false;
                }
                remaining = notFull.awaitNanos(remaining);
            }
            if (closed) {
                return false;
            }
            queue.addLast(envelope);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Enqueues from the owner thread without waiting.
     *
     * @throws IllegalStateException when the queue is full or closed; the runtime treats this as a
     *     fatal overflow instead of dropping the message or recursing into {@code update}
     */
    public void enqueueFromOwner(Envelope envelope) {
        lock.lock();
        try {
            if (closed || queue.size() >= capacity) {
                throw new IllegalStateException("runtime mailbox overflow (capacity " + capacity + ")");
            }
            queue.addLast(envelope);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to {@code timeoutMillis} for the next envelope.
     *
     * @return the envelope, or null on timeout or when the mailbox is closed
     */
    public Envelope poll(long timeoutMillis) throws InterruptedException {
        long remaining = waitNanos(timeoutMillis);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (closed || remaining <= 0L) {
                    return null;
                }
                remaining = notEmpty.awaitNanos(remaining);
            }
            if (closed) {
                return null;
            }
            Envelope envelope = queue.pollFirst();
            notFull.signal();
            return envelope;
        } finally {
            lock.unlock();
        }
    }

    /** Wakes every blocked producer and consumer. Idempotent. */
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Nanoseconds to wait, clamped so a huge millisecond value cannot overflow the conversion. */
    private static long waitNanos(long timeoutMillis) {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(0L, Math.min(timeoutMillis, MAX_WAIT_MILLIS)));
    }
}
