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

package io.github.dhoard.braxton.internal.terminal;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Drives one buffer into the terminal through bounded native attempts.
 *
 * <p>Only a terminal that accepts nothing at all for the stall limit fails. A slow terminal that
 * keeps accepting bytes, however few, is waited for, because a frame that has started must finish:
 * stopping partway would leave the screen and the escape-sequence parser in an unknown state. The
 * limit is long while the runtime is running and short once shutdown has begun, so a stalled peer
 * can delay the UI but can never hold terminal close for long. A terminal error (hangup, I/O
 * error, bad descriptor) fails at once.</p>
 */
final class OutputDrain {
    /** How long one native attempt may wait, and so how quickly a shutdown is noticed. */
    static final int ATTEMPT_MILLIS = 100;
    /** A peer that accepts no byte for this long while running is treated as gone. */
    static final long RUNNING_STALL_NANOS = 30_000_000_000L;
    /** Once shutdown has begun, the stall limit restoration output is held to. */
    static final long SHUTDOWN_STALL_NANOS = 1_000_000_000L;

    /** One bounded native attempt over {@code [offset, offset + length)} of the staged chunk. */
    @FunctionalInterface
    interface Attempt {
        /**
         * Returns the positive number of bytes accepted, 0 when nothing was accepted within
         * {@code timeoutMillis}, or {@code -errno} on a terminal failure.
         */
        int write(int offset, int length, int timeoutMillis) throws IOException;
    }

    private OutputDrain() {}

    /**
     * Writes {@code length} staged bytes, retrying partial progress and waiting out slowness.
     *
     * @param runningStallNanos the stall limit before shutdown
     * @param shuttingDown whether shutdown has begun; read before every limit check, so a shutdown
     *     that starts during a stalled write shortens it to {@link #SHUTDOWN_STALL_NANOS}
     * @throws IOException when the terminal fails or accepts nothing for the applicable limit
     */
    static void drain(
            int length, Attempt attempt, LongSupplier nanoTime, long runningStallNanos, BooleanSupplier shuttingDown)
            throws IOException {
        int done = 0;
        long lastProgress = nanoTime.getAsLong();
        while (done < length) {
            int written = attempt.write(done, length - done, ATTEMPT_MILLIS);
            if (written > 0) {
                if (written > length - done) {
                    throw new IOException("terminal reported more bytes than requested (" + written + ")");
                }
                done += written;
                lastProgress = nanoTime.getAsLong();
                continue;
            }
            if (written < 0) throw new IOException("cannot write terminal (errno " + -written + ")");
            long stalled = nanoTime.getAsLong() - lastProgress;
            boolean shutdown = shuttingDown.getAsBoolean();
            long limit = shutdown ? Math.min(runningStallNanos, SHUTDOWN_STALL_NANOS) : runningStallNanos;
            if (stalled >= limit) {
                throw new IOException("terminal accepted no output for " + stalled / 1_000_000L + " ms"
                        + (shutdown ? " during shutdown" : "") + " (" + (length - done) + " of " + length
                        + " bytes unwritten)");
            }
        }
    }
}
