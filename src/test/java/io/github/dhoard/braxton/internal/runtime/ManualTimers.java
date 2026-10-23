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
import java.util.List;
import java.util.ArrayList;
public final class ManualTimers implements Timers {

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
