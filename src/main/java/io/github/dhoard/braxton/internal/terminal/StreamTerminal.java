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

package io.github.dhoard.braxton.internal.terminal;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.Capabilities;
import java.io.FilterReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A terminal over a caller-supplied stream pair, owning no device.
 *
 * <p>This is the terminal a session runs on when its bytes come from somewhere other than the
 * process's own device: an embedder's pipe, socket or byte array, or the discarding endpoints a
 * renderer-disabled session gets. Nothing here loads a native library, changes device configuration
 * or measures anything with an ioctl, so a session on this terminal can run in a test, a build step
 * or a process with no terminal at all.</p>
 *
 * <p>The streams belong to the caller and are never closed: {@link #close()} flushes the output
 * writer and stops the read, and that is all. Text crosses both ends as it arrives, so a caller that
 * supplied an {@code InputStreamReader} chose its own charset; the options' own convenience factories
 * state UTF-8.</p>
 *
 * <p>The size is stated rather than measured, because a stream has no geometry. {@link #resize} is how
 * a host reports a new one, and it is the only resize notification a stream session ever receives: it
 * records the size, bumps {@link #resizeEpoch()} and calls the registered callback, which is exactly
 * what the native terminal's own resize signal does.</p>
 *
 * <p>Capabilities are the honest ones for a stream: none. A pipe cannot enter an alternate screen or
 * report a pointer, and claiming otherwise would make the session write sequences nothing reads. The
 * session therefore renders inline, which needs no capability at all.</p>
 */
public final class StreamTerminal implements Terminal {

    /**
     * A reader that reports end of input immediately: no input was supplied.
     */
    private static final TimedReader NO_INPUT = new TimedReader() {
        @Override
        public int read(long timeoutMillis) {
            return -1;
        }

        @Override
        public void close() {}
    };

    private final TimedReader reader;
    private final PrintWriter writer;
    private final AtomicReference<IOException> outputError;
    private final AtomicLong resizeEpoch = new AtomicLong();
    private final Capabilities capabilities = Capabilities.PLAIN;
    private volatile Size size;
    private volatile Runnable resizeCallback = () -> {};
    private volatile State state = State.ACTIVE;
    private volatile boolean closed;

    private StreamTerminal(
            TimedReader reader, PrintWriter writer, AtomicReference<IOException> outputError, Size size) {
        this.reader = reader;
        this.writer = writer;
        this.outputError = outputError;
        this.size = size;
    }

    /**
     * A terminal over {@code input} and {@code output}, laying out for {@code size}.
     *
     * <p>Either end may be null: no input means the session sees its input close immediately, and no
     * output means its frames are discarded. Neither case touches the process's own terminal.</p>
     *
     * @param input the input to decode, or null for none
     * @param output the output to write, or null to discard it
     * @param size the size to lay out for; null for 24 rows by 80 columns
     * @return the terminal
     */
    public static StreamTerminal of(Reader input, Writer output, Size size) {
        AtomicReference<IOException> outputError = new AtomicReference<>();
        return new StreamTerminal(
                input == null ? NO_INPUT : TimedReader.from(nonClosing(input)),
                writerOver(output, outputError),
                outputError,
                size == null ? new Size(24, 80) : size);
    }

    /**
     * A terminal that reads and writes nothing, which is what a renderer-disabled session needs.
     *
     * @param size the size to lay out for; null for the default
     * @return the terminal
     */
    public static StreamTerminal headless(Size size) {
        return of(null, null, size);
    }

    /**
     * A writer that records the I/O failure the {@link PrintWriter} contract would hide.
     *
     * <p>The runtime writes through a {@link PrintWriter} because that is the terminal contract; this
     * adapter turns the failure it would otherwise swallow into {@link #outputError()}, the cause the
     * runtime reports when output fails. The caller's writer is never closed.</p>
     */
    private static PrintWriter writerOver(Writer output, AtomicReference<IOException> outputError) {
        Writer target = output == null ? new Discard() : nonClosing(output);
        return new PrintWriter(new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                try {
                    target.write(buffer, offset, length);
                } catch (IOException failure) {
                    outputError.set(failure);
                    throw failure;
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    target.flush();
                } catch (IOException failure) {
                    outputError.set(failure);
                    throw failure;
                }
            }

            @Override
            public void close() throws IOException {
                // The caller owns the destination; a session only ever flushes it.
                flush();
            }
        });
    }

    /**
     * The destination of a session whose frames go nowhere.
     */
    private static final class Discard extends Writer {
        @Override
        public void write(char[] buffer, int offset, int length) {}

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    /**
     * A view of a caller-owned reader whose close does not reach the caller's stream.
     */
    private static Reader nonClosing(Reader source) {
        return new FilterReader(source) {
            @Override
            public void close() {}
        };
    }

    /**
     * A view of a caller-owned writer whose close does not reach the caller's stream.
     */
    private static Writer nonClosing(Writer source) {
        return new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                source.write(buffer, offset, length);
            }

            @Override
            public void flush() throws IOException {
                source.flush();
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    @Override
    public TimedReader reader() {
        return reader;
    }

    @Override
    public PrintWriter writer() {
        return writer;
    }

    @Override
    public IOException outputError() {
        return outputError.get();
    }

    @Override
    public Size size() {
        return size;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public void enterRawMode() {
        // A stream has no input configuration to enter: the caller's own process owns its terminal,
        // and this session must not change it.
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public long resizeEpoch() {
        return resizeEpoch.get();
    }

    @Override
    public void onResize(Runnable callback) {
        resizeCallback = callback == null ? () -> {} : callback;
    }

    /**
     * Reports a new size, which is the only resize a stream session receives.
     *
     * <p>Called by the host that owns the stream and never inferred from the bytes. The epoch always
     * changes, so a shrink that restores the previous dimensions between two looks is still a resize
     * the session repaints for.</p>
     *
     * @param value the size to lay out for now; required
     */
    public void resize(Size value) {
        if (value == null) {
            throw new IllegalArgumentException("size is required");
        }
        if (value.isEmpty() || value.equals(size)) {
            return;
        }
        size = value;
        resizeEpoch.incrementAndGet();
        resizeCallback.run();
    }

    @Override
    public void boundOutput() {
        // A stream's writes do not stall on device flow control, so there is nothing to bound.
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        state = State.CLOSED;
        try {
            reader.close();
        } catch (IOException failure) {
            // The input belongs to the caller and its close is a no-op here, so there is nothing this
            // session can do about a failure reading it produced.
            outputError.compareAndSet(null, failure);
        }
        // Flushed, never closed: the destination outlives this session.
        writer.flush();
    }
}
