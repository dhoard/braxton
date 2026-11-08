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
import java.io.InterruptedIOException;
import java.io.Writer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Bounded character pipe for owned streams and deterministic terminal fixtures. */
public final class CharacterInput extends TimedReader {
    private final char[] buffer;
    private int head;
    private int count;
    private boolean ended;
    private boolean closed;
    private Throwable failure;
    private final Writer writer = new Writer() {
        public void write(char[] text, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, text.length);
            synchronized (CharacterInput.this) {
                while (length > 0) {
                    while (count == buffer.length && !closed && !ended) await(0);
                    if (closed || ended) throw new IOException("input closed");
                    int tail = (head + count) % buffer.length;
                    int size = Math.min(length, Math.min(buffer.length - count, buffer.length - tail));
                    System.arraycopy(text, offset, buffer, tail, size);
                    count += size;
                    offset += size;
                    length -= size;
                    CharacterInput.this.notifyAll();
                }
            }
        }

        public void flush() {}

        public void close() {
            synchronized (CharacterInput.this) {
                ended = true;
                CharacterInput.this.notifyAll();
            }
        }
    };

    public CharacterInput() {
        this(8192);
    }

    public CharacterInput(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        buffer = new char[capacity];
    }

    public Writer getWriter() {
        return writer;
    }

    public synchronized int read(long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 86_400_000));
        while (count == 0 && !ended && !closed && failure == null) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return READ_EXPIRED;
            await(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
        }
        return poll();
    }

    public synchronized int poll() throws IOException {
        if (closed) return -1;
        if (count > 0) {
            int value = buffer[head];
            head = (head + 1) % buffer.length;
            count--;
            notifyAll();
            return value;
        }
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IOException("stream acquisition failed", failure);
        return ended ? -1 : READ_EXPIRED;
    }

    private void await(long millis) throws InterruptedIOException {
        try {
            wait(millis);
        } catch (InterruptedException problem) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("input interrupted");
        }
    }

    synchronized void fail(Throwable problem) {
        failure = problem;
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
