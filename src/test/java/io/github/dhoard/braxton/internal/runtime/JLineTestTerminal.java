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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.jline.utils.NonBlockingReader;

import io.github.dhoard.braxton.app.*;
import io.github.dhoard.braxton.layout.Size;

/** Owned streams feeding an actual JLine terminal; no Braxton backend abstraction. */
public final class JLineTestTerminal implements AutoCloseable {
    private final ScriptedInput input = new ScriptedInput();
    private final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final AtomicBoolean virtualRead = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean measured;
    private volatile RuntimeException readFailure;
    private volatile IOException readIoFailure;
    private volatile RuntimeException sizeFailure;
    private volatile RuntimeException closeFailure;
    private volatile boolean outputFailure;
    private volatile java.util.function.Consumer<String> outputObserver = text -> {};
    private volatile java.util.function.Consumer<String> beforeWrite = text -> {};
    private volatile Runnable beforeFlush = () -> {};
    private final Terminal terminal;

    public JLineTestTerminal() {
        this(new Size(24, 80));
    }

    public JLineTestTerminal(Size size) {
        try {
            OutputStream capture = new OutputStream() {
                public synchronized void write(int b) throws IOException {
                    if (outputFailure) throw new IOException("broken output");
                    output.write(b);
                }

                public synchronized void write(byte[] b, int off, int len) throws IOException {
                    if (outputFailure) throw new IOException("broken output");
                    output.write(b, off, len);
                }

                public void flush() {
                    synchronized (output) {
                        if (output.size() > 0) {
                            String text = output.toString(StandardCharsets.UTF_8);
                            writes.add(text);
                            outputObserver.accept(text);
                            output.reset();
                        }
                    }
                }
            };
            terminal =
                    new ExternalTerminal(
                            null,
                            "test",
                            "xterm-256color",
                            input,
                            capture,
                            StandardCharsets.UTF_8,
                            Terminal.SignalHandler.SIG_DFL,
                            true,
                            null,
                            org.jline.terminal.Size.of(size.cols(), size.rows())) {
                        private final PrintWriter checkedWriter = new PrintWriter(super.writer()) {
                            @Override
                            public void write(String text) {
                                beforeWrite.accept(text);
                                super.write(text);
                            }

                            @Override
                            public void flush() {
                                beforeFlush.run();
                                super.flush();
                            }
                        };

                        @Override
                        public PrintWriter writer() {
                            // JLine may call writer() during its own construction.
                            return checkedWriter == null ? super.writer() : checkedWriter;
                        }

                        private final NonBlockingReader actualReader = super.reader();
                        private final NonBlockingReader checkedReader = new NonBlockingReader() {
                            protected int read(long timeout, boolean peek) throws IOException {
                                virtualRead.compareAndSet(
                                        false, Thread.currentThread().isVirtual());
                                if (readFailure != null) throw readFailure;
                                if (readIoFailure != null) throw readIoFailure;
                                return peek
                                        ? actualReader.peek(timeout)
                                        : actualReader.read(timeout);
                            }

                            public int readBuffered(char[] b, int off, int len, long timeout)
                                    throws IOException {
                                return actualReader.readBuffered(b, off, len, timeout);
                            }

                            public void close() throws IOException {
                                actualReader.close();
                            }
                        };

                        @Override
                        public NonBlockingReader reader() {
                            return checkedReader;
                        }

                        @Override
                        public org.jline.terminal.Size getSize() {
                            measured = true;
                            if (sizeFailure != null) {
                                var failure = sizeFailure;
                                sizeFailure = null;
                                throw failure;
                            }
                            return super.getSize();
                        }

                        @Override
                        protected void doClose() throws IOException {
                            input.end();
                            super.doClose();
                            JLineTestTerminal.this.closed = true;
                            if (closeFailure != null) throw closeFailure;
                        }
                    };
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public Terminal terminal() {
        return terminal;
    }

    public void feed(String text) {
        input.feed(text.getBytes(StandardCharsets.UTF_8));
    }

    public void endInput() {
        input.end();
    }

    public void failInput(RuntimeException failure) {
        readFailure = failure;
    }

    public void failInputWith(IOException failure) {
        readIoFailure = failure;
    }

    public void failSize(RuntimeException failure) {
        sizeFailure = failure;
    }

    public void onOutput(java.util.function.Consumer<String> observer) {
        outputObserver = observer;
    }

    void beforeWrite(java.util.function.Consumer<String> observer) {
        beforeWrite = observer;
    }

    void beforeFlush(Runnable observer) {
        beforeFlush = observer;
    }

    public void failOutput() {
        outputFailure = true;
    }

    public void failClose(RuntimeException failure) {
        closeFailure = failure;
    }

    public void resize(Size size) {
        terminal.setSize(org.jline.terminal.Size.of(size.cols(), size.rows()));
    }

    public String outputText() {
        synchronized (writes) {
            return String.join("", writes);
        }
    }

    public List<String> writes() {
        synchronized (writes) {
            return List.copyOf(writes);
        }
    }

    public boolean isOpen() {
        return !closed;
    }

    public boolean measured() {
        return measured;
    }

    public boolean readOnVirtualThread() {
        return virtualRead.get();
    }

    @Override
    public void close() {
        input.end();
        try {
            terminal.close();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private final class ScriptedInput extends InputStream {

        private final Deque<Integer> bytes = new ArrayDeque<>();
        private boolean ended;
        private boolean closed;
        private RuntimeException failure;

        synchronized void feed(byte[] data) {
            for (byte value : data) {
                bytes.add(value & 0xFF);
            }
            notifyAll();
        }

        synchronized void end() {
            ended = true;
            notifyAll();
        }

        synchronized void fail(RuntimeException problem) {
            failure = problem;
            notifyAll();
        }

        @Override
        public synchronized int read() {
            recordThread();
            while (bytes.isEmpty() && !ended && !closed && failure == null) {
                try {
                    wait(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (failure != null) {
                throw failure;
            }
            if (bytes.isEmpty()) {
                return -1;
            }
            return bytes.poll();
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            recordThread();
            int first = read();
            if (first < 0) {
                return -1;
            }
            buffer[offset] = (byte) first;
            int count = 1;
            while (count < length && !bytes.isEmpty()) {
                buffer[offset + count] = (byte) (int) bytes.poll();
                count++;
            }
            return count;
        }

        @Override
        public synchronized int available() {
            return bytes.size();
        }

        private void recordThread() {
            virtualRead.compareAndSet(false, Thread.currentThread().isVirtual());
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            notifyAll();
        }
    }

    public static <M, Msg> ExitResult run(
            Program<M, Msg> program, RuntimeOptions options, JLineTestTerminal terminal) {
        return run(
                program,
                options,
                terminal,
                RuntimeServices.system()
                        .withTimers(new ManualTimers())
                        .withEffectExecutor(Runnable::run)
                        .withNanoTime(() -> 1000L));
    }

    public static <M, Msg> ExitResult run(
            Program<M, Msg> program,
            RuntimeOptions options,
            JLineTestTerminal terminal,
            RuntimeServices services) {
        return new RuntimeLoop<>(program, options, terminal.terminal(), services).run();
    }
}
