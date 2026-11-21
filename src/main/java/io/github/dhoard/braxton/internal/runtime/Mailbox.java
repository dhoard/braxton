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

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.input.InputEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded hand-off between producers (input, effects, timers, sources) and the runtime owner.
 *
 * <p>Producers block while the queue is full and are released when the runtime closes. Messages
 * generated on the owner thread never wait for the owner: an owner-side overflow is fatal rather
 * than recursive or silent. A close sets the flag and signals both waiters, so a full queue can
 * never delay shutdown and a blocked consumer returns immediately.</p>
 */
public final class Mailbox {

    /**
     * One item in the hand-off queue.
     */
    public sealed interface Envelope permits AppMessage, Input, Close, Resume, Quit {}

    /**
     * An application message produced by routing, a command, or a source.
     *
     * @param payload the application message value
     * @param origin the scope that produced it, or null for the root
     * @param valid whether it may still be delivered when the owner takes it
     * @param afterApply what to run once the transition it causes has committed, or a no-op
     */
    public record AppMessage(
            Object payload, ScopeManager.Origin origin, java.util.function.BooleanSupplier valid, Runnable afterApply)
            implements Envelope {
        private static final java.util.function.BooleanSupplier ALWAYS = () -> true;
        private static final Runnable NOTHING = () -> {};

        /**
         * Creates a {@code AppMessage} value.
         *
         * @param payload the application message value
         * @param origin the scope that produced it, or null for the root
         * @param valid whether it may still be delivered when the owner takes it
         * @param afterApply what to run once the transition it causes has committed, or a no-op
         */
        public AppMessage {
            valid = valid == null ? ALWAYS : valid;
            afterApply = afterApply == null ? NOTHING : afterApply;
        }

        /**
         * Creates a {@code AppMessage} value.
         *
         * @param payload the application message value
         */
        public AppMessage(Object payload) {
            this(payload, null, ALWAYS, NOTHING);
        }

        /**
         * Creates a {@code AppMessage} value.
         *
         * @param payload the application message value
         * @param origin the scope that produced it, or null for the root
         */
        public AppMessage(Object payload, ScopeManager.Origin origin) {
            this(payload, origin, ALWAYS, NOTHING);
        }

        /**
         * Creates a {@code AppMessage} value.
         *
         * @param payload the application message value
         * @param origin the scope that produced it, or null for the root
         * @param valid whether it may still be delivered when the owner takes it
         */
        public AppMessage(Object payload, ScopeManager.Origin origin, java.util.function.BooleanSupplier valid) {
            this(payload, origin, valid, NOTHING);
        }
    }

    /**
     * Raw normalized input, routed later against the latest committed view.
     *
     * @param event the normalized input event
     */
    public record Input(InputEvent event) implements Envelope {}

    /**
     * A request to stop the runtime; generated inside the runtime or by a backend.
     *
     * @param result how the run ended
     */
    public record Close(ExitResult result) implements Envelope {}

    /**
     * A request to end the run gracefully, from outside the runtime.
     *
     * <p>It is a request rather than a stop so the session can still refuse it: the owner presents a
     * pending frame and returns through the ordinary exit and cleanup path. Forced termination does
     * not use this envelope, because it may not wait for the owner at all.</p>
     *
     * @param status the exit status the run reports when the request is accepted
     */
    public record Quit(int status) implements Envelope {}

    /**
     * Owner-thread work a producer asked for, such as submitting the next step of a sequence.
     *
     * <p>An effect that finishes on a timer or task thread cannot interpret the rest of a sequence
     * itself: submission touches state only the owner may change. It enqueues this envelope instead,
     * after the message it produced, so the owner submits the next step in the same order the
     * message was delivered.</p>
     *
     * @param action the work to run on the owner thread
     */
    public record Resume(Runnable action) implements Envelope {

        /**
         * Rejects a null action.
         */
        public Resume {
            if (action == null) throw new IllegalArgumentException("action is required");
        }
    }

    /**
     * Largest millisecond timeout that still converts to nanoseconds without overflowing.
     */
    private static final long MAX_WAIT_MILLIS = Long.MAX_VALUE / 1_000_000L;

    private final Deque<Envelope> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final int capacity;
    /**
     * Whether each queued envelope, in queue order, was enqueued by the owner.
     */
    private final Deque<Boolean> ownerEnqueued = new ArrayDeque<>();

    private int ownerQueued;
    private volatile boolean closed;
    private boolean woken;

    /**
     * Creates a mailbox holding up to {@code capacity} producer envelopes, plus up to
     * {@code capacity} envelopes the owner enqueued itself.
     *
     * <p>Producers block while the queue holds {@code capacity} envelopes of any origin. The owner
     * cannot wait on a queue only it drains, so it fails instead, but only when its <em>own</em>
     * queued envelopes reach {@code capacity}. Counting all envelopes there would make overflow a
     * race: the owner takes one envelope from a full mailbox, a blocked producer refills the slot
     * at once, and a delivery from the owner in the same turn would overflow. Producer back pressure
     * is unchanged, and an owner burst larger than {@code capacity} is still the fatal overflow.</p>
     *
     * @param capacity the maximum buffered item count
     */
    public Mailbox(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Returns the configured mailbox capacity.
     *
     * @return the configured mailbox capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the current number of queued envelopes.
     *
     * @return the current number of queued envelopes
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tests whether mailbox admission has closed.
     *
     * @return {@code true} if mailbox admission has closed; otherwise {@code false}
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Enqueues from a non-owner thread, blocking at most {@code timeoutMillis} while the queue is
     * full. Returns false when the runtime closed or the wait was interrupted.
     *
     * @param envelope the mailbox envelope to admit
     * @param timeoutMillis the maximum wait in milliseconds
     * @return {@code true} if enqueues from a non-owner thread, blocking at most {@code timeoutMillis} while the queue is
     * full; otherwise {@code false}
     * @throws java.lang.InterruptedException if the waiting thread is interrupted
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
            ownerEnqueued.addLast(Boolean.FALSE);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Enqueues from the owner thread without waiting.
     *
     * @param envelope the mailbox envelope to admit
     * @throws IllegalStateException when the queue is closed or already holds {@code capacity}
     *     owner-enqueued envelopes; the runtime treats this as a fatal overflow instead of dropping the message or recursing into {@code update}
     */
    public void enqueueFromOwner(Envelope envelope) {
        lock.lock();
        try {
            if (closed || ownerQueued >= capacity) {
                throw new IllegalStateException("runtime mailbox overflow (capacity " + capacity + ")");
            }
            queue.addLast(envelope);
            ownerEnqueued.addLast(Boolean.TRUE);
            ownerQueued++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Notifies the owner without blocking a signal thread on the mailbox lock.
     */
    public void wake() {
        if (lock.tryLock()) {
            try {
                woken = true;
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Waits up to {@code timeoutMillis} for the next envelope.
     *
     * @param timeoutMillis the maximum wait in milliseconds
     * @return the envelope, or null on timeout or when the mailbox is closed
     * @throws java.lang.InterruptedException if the waiting thread is interrupted
     */
    public Envelope poll(long timeoutMillis) throws InterruptedException {
        long remaining = waitNanos(timeoutMillis);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (closed || woken || remaining <= 0L) {
                    woken = false;
                    return null;
                }
                remaining = notEmpty.awaitNanos(remaining);
            }
            if (closed) {
                return null;
            }
            Envelope envelope = queue.pollFirst();
            if (ownerEnqueued.pollFirst()) ownerQueued--;
            notFull.signal();
            return envelope;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Discards undeliverable envelopes and wakes every blocked producer and consumer. Idempotent.
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            queue.clear();
            ownerEnqueued.clear();
            ownerQueued = 0;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Nanoseconds to wait, clamped so a huge millisecond value cannot overflow the conversion.
     */
    private static long waitNanos(long timeoutMillis) {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(0L, Math.min(timeoutMillis, MAX_WAIT_MILLIS)));
    }
}
