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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/** A single-consumer UTF-16 input with distinct timeout and end-of-input results. */
public abstract class TimedReader implements Closeable {
    public static final int READ_EXPIRED = -2;

    /** Wait at most timeoutMillis; nonnegative values are UTF-16 units, -1 is EOF. */
    public abstract int read(long timeoutMillis) throws IOException;

    /** Consume already decoded input without waiting; used before applying arrival deadlines. */
    public int poll() throws IOException {
        return READ_EXPIRED;
    }

    public static TimedReader utf8(InputStream input) {
        var bytes = new StreamBytes(input);
        var reader = new Utf8Reader(bytes);
        return new TimedReader() {
            public int read(long timeoutMillis) throws IOException {
                return reader.read(timeoutMillis);
            }

            public int poll() throws IOException {
                return reader.poll();
            }

            public void close() throws IOException {
                reader.close();
                bytes.close();
            }
        };
    }

    /** Adapts an owned blocking reader using bounded buffering and a virtual acquisition worker. */
    public static TimedReader from(Reader source) {
        var pipe = new CharacterInput(8192);
        Thread worker = Thread.ofVirtual().name("braxton-stream-input").start(() -> {
            try {
                char[] block = new char[2048];
                for (int count; (count = source.read(block)) >= 0; ) {
                    pipe.getWriter().write(block, 0, count);
                }
                pipe.getWriter().close();
            } catch (Throwable failure) {
                pipe.fail(failure);
            }
        });
        return new TimedReader() {
            public int read(long timeoutMillis) throws IOException {
                return pipe.read(timeoutMillis);
            }

            public int poll() throws IOException {
                return pipe.poll();
            }

            public void close() throws IOException {
                pipe.close();
                worker.interrupt();
                source.close();
            }
        };
    }
}
