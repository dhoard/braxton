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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/** Bounded byte acquisition for owned streams; the terminal path polls natively instead. */
final class StreamBytes implements Utf8Reader.Bytes {
    private final InputStream source;
    private final byte[] ring = new byte[8192];
    private int head;
    private int count;
    private boolean eof;
    private boolean closed;
    private Throwable failure;
    private final Thread worker;

    StreamBytes(InputStream source) {
        this.source = java.util.Objects.requireNonNull(source);
        worker = Thread.ofVirtual().name("braxton-stream-bytes").start(this::acquire);
    }

    private void acquire() {
        try {
            byte[] block = new byte[4096];
            while (true) {
                int room;
                synchronized (this) {
                    while (count == ring.length && !closed) wait();
                    if (closed) return;
                    room = ring.length - count;
                }
                // A bulk InputStream.read may wait to fill its request. Request only the bytes
                // advertised as ready; otherwise wait for one byte, publishing it immediately.
                int limit = Math.min(Math.min(room, block.length), Math.max(1, source.available()));
                int size = source.read(block, 0, limit);
                synchronized (this) {
                    if (closed) return;
                    if (size < 0) {
                        eof = true;
                        notifyAll();
                        return;
                    }
                    for (int at = 0; at < size; at++) ring[(head + count + at) % ring.length] = block[at];
                    count += size;
                    notifyAll();
                }
            }
        } catch (Throwable problem) {
            synchronized (this) {
                failure = problem;
                notifyAll();
            }
        }
    }

    public synchronized int read(byte[] bytes, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 86_400_000));
        while (count == 0 && !closed && !eof && failure == null) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return 0;
            try {
                TimeUnit.NANOSECONDS.timedWait(this, left);
            } catch (InterruptedException problem) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("input interrupted");
            }
        }
        if (closed) return -1;
        if (count > 0) {
            int size = Math.min(bytes.length, count);
            for (int at = 0; at < size; at++) bytes[at] = ring[(head + at) % ring.length];
            head = (head + size) % ring.length;
            count -= size;
            notifyAll();
            return size;
        }
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IOException("stream acquisition failed", failure);
        return -1;
    }

    void close() throws IOException {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        worker.interrupt();
        source.close();
    }
}
