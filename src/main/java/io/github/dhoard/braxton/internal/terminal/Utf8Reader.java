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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Incremental JDK decoding; incomplete byte sequences remain buffered across timed reads. */
public final class Utf8Reader extends TimedReader {
    @FunctionalInterface
    public interface Bytes {
        /** Returns a byte count, zero for timeout, or -1 for EOF. */
        int read(byte[] buffer, long timeoutMillis) throws IOException;
    }

    private final Bytes input;
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer bytes = ByteBuffer.allocate(8192);
    private final CharBuffer chars = CharBuffer.allocate(8192);
    private final byte[] incoming = new byte[4096];
    private boolean eof;
    private volatile boolean closed;

    public Utf8Reader(Bytes input) {
        this.input = java.util.Objects.requireNonNull(input);
        bytes.flip();
        chars.flip();
    }

    public int read(long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 86_400_000));
        while (!closed) {
            if (chars.hasRemaining()) return chars.get();
            chars.clear();
            var result = decoder.decode(bytes, chars, eof);
            if (result.isError()) result.throwException();
            chars.flip();
            if (chars.hasRemaining()) return chars.get();
            if (eof) return -1;
            long left = deadline - System.nanoTime();
            if (left <= 0) return READ_EXPIRED;
            int count = input.read(incoming, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            if (count == 0) return READ_EXPIRED;
            if (count < 0) {
                eof = true;
                continue;
            }
            bytes.compact();
            bytes.put(incoming, 0, count);
            bytes.flip();
        }
        return -1;
    }

    public int poll() {
        if (closed) return -1;
        if (chars.hasRemaining()) return chars.get();
        return eof ? -1 : READ_EXPIRED;
    }

    public void close() {
        closed = true;
    }
}
