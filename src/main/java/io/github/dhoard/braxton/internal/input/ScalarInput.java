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

package io.github.dhoard.braxton.internal.input;

import io.github.dhoard.braxton.internal.terminal.TimedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/** Parser cursor: scalar assembly and lookahead across timed UTF-16 reads. */
final class ScalarInput {
    private final TimedReader reader;
    private final long intervalMillis;
    private final java.util.function.BooleanSupplier cancelled;
    private int pending = -1;
    private int rawPending = -1;
    private int high = -1;

    ScalarInput(TimedReader reader, long intervalMillis) {
        this(reader, intervalMillis, () -> false);
    }

    ScalarInput(TimedReader reader, long intervalMillis, java.util.function.BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        this.reader = java.util.Objects.requireNonNull(reader);
        this.intervalMillis = Math.max(1, Math.min(100, intervalMillis));
    }

    void unread(int value) {
        pending = value;
    }

    int read() throws IOException {
        while (true) {
            int value = readUntil(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMillis));
            if (value != TimedReader.READ_EXPIRED) return value;
        }
    }

    int readUntil(long deadline) throws IOException {
        if (pending >= 0) {
            int value = pending;
            pending = -1;
            return value;
        }
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("input interrupted");
            long remaining = deadline - System.nanoTime();
            long millis = Math.max(1, Math.min(intervalMillis, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
            int value;
            if (rawPending >= 0) {
                value = rawPending;
                rawPending = -1;
            } else {
                value = reader.poll();
                if (value == TimedReader.READ_EXPIRED) {
                    if (remaining <= 0) return TimedReader.READ_EXPIRED;
                    value = reader.read(millis);
                }
            }
            if (value == TimedReader.READ_EXPIRED) continue;
            if (high >= 0) {
                int first = high;
                if (value >= 0 && Character.isLowSurrogate((char) value)) {
                    high = -1;
                    return Character.toCodePoint((char) first, (char) value);
                }
                high = -1;
                if (value >= 0) rawPending = value;
                return 0xFFFD;
            }
            if (value < 0) return value;
            if (Character.isHighSurrogate((char) value)) {
                high = value;
                continue;
            }
            return Character.isLowSurrogate((char) value) ? 0xFFFD : value;
        }
    }
}
