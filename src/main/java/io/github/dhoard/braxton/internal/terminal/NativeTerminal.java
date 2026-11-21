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
import io.github.dhoard.braxton.terminal.UnsupportedTerminalException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Java lifecycle policy for the owned POSIX transport. Close waits for active native calls.
 */
public final class NativeTerminal implements Terminal {

    /**
     * The native ABI this Java side speaks; {@code src/main/native/terminal.c} answers it from
     * {@code bt_abi}. An image that answers anything else is refused before a symbol is bound, so a
     * stale extracted binary can never be called with the wrong signatures.
     */
    static final int REQUIRED_ABI = 5;

    // Close and handoffs wait for bounded transport calls to finish.
    private final java.util.concurrent.locks.ReentrantReadWriteLock lifetime =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final TerminalTransport transport;
    private final Capabilities capabilities;
    private final Utf8Reader reader;
    private final PrintWriter writer;
    private volatile IOException outputError;
    private final long outputStallNanos;
    // Set by boundOutput() or close() without any lock, so a write stalled under the lifetime
    // read lock notices within one attempt and close is never held behind it for long.
    private volatile boolean outputBounded;
    private boolean closed;
    private volatile State state = State.ACTIVE;

    /**
     * Returns the supported operating-system and CPU resource key.
     *
     * @return the supported operating-system and CPU resource key
     */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String cpu = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = switch (cpu) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException("unsupported Braxton CPU: " + cpu);
        };
        if (os.equals("linux")) return "linux-" + arch;
        if (os.equals("mac os x")) return "macos-" + arch;
        throw new IllegalStateException(
                "Braxton requires Linux (x86_64/ARM64) or macOS (ARM64/Intel): " + os + "/" + cpu);
    }

    /**
     * Opens the attached terminal with the default output-stall limit.
     *
     * @return opens the attached terminal with the default output-stall limit
     * @throws IOException if input or output fails
     */
    public static NativeTerminal open() throws IOException {
        return open(java.time.Duration.ofNanos(OutputDrain.RUNNING_STALL_NANOS));
    }

    /**
     * Opens the attached terminal with an explicit running stall limit: the time the terminal may
     * accept no output at all before a write fails. Shutdown always uses the shorter of this and
     * one second. Internal; the runtime uses {@link #open()}.
     *
     * @param outputStallLimit the maximum output stall duration
     * @return opens the attached terminal with an explicit running stall limit: the time the terminal may
     * accept no output at all before a write fails
     * @throws java.io.IOException if input or output fails
     */
    public static NativeTerminal open(java.time.Duration outputStallLimit) throws IOException {
        if (!NativeTerminal.class.getModule().isNativeAccessEnabled())
            throw new IllegalStateException("Braxton requires --enable-native-access=ALL-UNNAMED on the classpath,"
                    + " or --enable-native-access=io.github.dhoard.braxton on the module path");
        long stallNanos = stallNanos(outputStallLimit);
        String type = System.getenv("TERM");
        return new NativeTerminal(capabilities(type), stallNanos, new FfmTerminalTransport());
    }

    static long stallNanos(java.time.Duration outputStallLimit) {
        if (outputStallLimit == null || outputStallLimit.isNegative() || outputStallLimit.isZero())
            throw new IllegalArgumentException("output stall limit must be positive");

        try {
            return outputStallLimit.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static Capabilities capabilities(String type) throws UnsupportedTerminalException {
        return Capabilities.inspect(type);
    }

    NativeTerminal(Capabilities capabilities, long outputStallNanos, TerminalTransport transport) {
        this.outputStallNanos = outputStallNanos;
        this.capabilities = java.util.Objects.requireNonNull(capabilities);
        this.transport = java.util.Objects.requireNonNull(transport);
        reader = new Utf8Reader(this::readBytes);
        writer = new PrintWriter(new OutputStreamWriter(
                new OutputStream() {
                    public void write(int value) throws IOException {
                        write(new byte[] {(byte) value}, 0, 1);
                    }

                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        try {
                            NativeTerminal.this.write(bytes, offset, length);
                        } catch (IOException failure) {
                            outputError = failure;
                            throw failure;
                        }
                    }
                },
                StandardCharsets.UTF_8));
    }

    private int readBytes(byte[] bytes, long timeoutMillis) throws IOException {
        lifetime.readLock().lock();
        try {
            if (closed) return -1;
            // A released terminal belongs to a child process or to the shell: reading it here would
            // take that process's input, so the read reports nothing to read rather than waiting.
            if (state != State.ACTIVE) return 0;
            int count = transport.read(bytes, (int) Math.min(100, timeoutMillis));
            if (count < -1) throw new IOException("terminal input failed (errno " + (-count - 2) + ")");
            return count;
        } finally {
            lifetime.readLock().unlock();
        }
    }

    private synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        lifetime.readLock().lock();
        try {
            if (closed) throw new IOException("terminal closed");
            if (state != State.ACTIVE) throw new IOException("terminal is released");
            while (length > 0) {
                int count = Math.min(length, 8192);
                int chunkOffset = offset;
                OutputDrain.drain(
                        count,
                        (from, remaining, timeout) -> transport.write(bytes, chunkOffset + from, remaining, timeout),
                        System::nanoTime,
                        outputStallNanos,
                        () -> outputBounded);
                offset += count;
                length -= count;
            }
        } finally {
            lifetime.readLock().unlock();
        }
    }

    public TimedReader reader() {
        return reader;
    }

    public PrintWriter writer() {
        return writer;
    }

    public IOException outputError() {
        return outputError;
    }

    public void boundOutput() {
        outputBounded = true;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    public long resizeEpoch() {
        try {
            return Integer.toUnsignedLong(transport.resizeEpoch());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public void enterRawMode() throws IOException {
        lifetime.writeLock().lock();
        try {
            if (closed) throw new IOException("terminal closed");
            if (state != State.ACTIVE) throw new IOException("terminal is released");
            check(transport.raw(), "enter raw mode");
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    public State state() {
        return state;
    }

    /**
     * Gives the terminal back to the user's own configuration.
     *
     * <p>The transition is serialized against every read, write and mode change, so nothing can be
     * in flight when the device stops belonging to the session. Releasing a released terminal is a
     * no-op: a retried handoff does not disturb the child that already owns it.</p>
     */
    public void release() throws IOException {
        lifetime.writeLock().lock();
        try {
            switch (state) {
                case CLOSED -> throw new IOException("terminal closed");
                case RELEASED -> {
                    return;
                }
                case ACTIVE -> {
                    state = State.RELEASING;
                    try {
                        check(transport.release(), "release terminal");
                    } catch (IOException | RuntimeException failure) {
                        // The device configuration may not have been restored, but the session still
                        // owns it: report the failure from the state it was actually left in.
                        state = State.ACTIVE;
                        throw failure;
                    }
                    state = State.RELEASED;
                }
                case RELEASING, REACQUIRING -> throw new IllegalStateException("terminal is " + state);
            }
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    /**
     * Takes the terminal back, re-entering raw mode.
     *
     * <p>A failure leaves the terminal released: the session cannot use a device whose configuration
     * it could not restore, and the caller reports that as a fatal recovery error.</p>
     */
    public void reacquire() throws IOException {
        lifetime.writeLock().lock();
        try {
            switch (state) {
                case CLOSED -> throw new IOException("terminal closed");
                case ACTIVE -> {
                    return;
                }
                case RELEASED -> {
                    state = State.REACQUIRING;
                    try {
                        check(transport.reacquire(), "reacquire terminal");
                    } catch (IOException | RuntimeException failure) {
                        state = State.RELEASED;
                        throw failure;
                    }
                    state = State.ACTIVE;
                }
                case RELEASING, REACQUIRING -> throw new IllegalStateException("terminal is " + state);
            }
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    public boolean foreground() {
        lifetime.readLock().lock();
        try {
            if (state != State.ACTIVE && state != State.RELEASED) return false;
            int answer = transport.foreground();
            if (answer < 0)
                throw new IllegalStateException("cannot inspect the terminal foreground (errno " + (-answer) + ")");
            return answer == 1;
        } catch (IOException problem) {
            throw new IllegalStateException(problem);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    @Override
    public void registerSuspendBytes(String restore, String resume) throws IOException {
        lifetime.readLock().lock();
        try {
            if (closed) throw new IOException("terminal closed");
            check(
                    transport.registerSuspendBytes(restore == null ? "" : restore, resume == null ? "" : resume),
                    "register suspend sequences");
        } finally {
            lifetime.readLock().unlock();
        }
    }

    @Override
    public long resumeEpoch() {
        try {
            lifetime.readLock().lock();
            try {
                return closed ? 0L : Integer.toUnsignedLong(transport.resumeEpoch());
            } finally {
                lifetime.readLock().unlock();
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public void suspend() throws IOException {
        lifetime.readLock().lock();
        try {
            if (closed) throw new IOException("terminal closed");
            // The native layer refuses a terminal that is still the session's: a session stopped in
            // raw mode would hand the shell a terminal whose settings contradict its screen.
            check(transport.suspend(), "suspend the session");
        } finally {
            lifetime.readLock().unlock();
        }
    }

    public synchronized Size size() {
        lifetime.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("terminal closed");
            return transport.size();
        } catch (IOException problem) {
            throw new IllegalStateException(problem);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    public void close() throws IOException {
        // Before the lock: an in-flight write holds the read lock and must see this to give up.
        outputBounded = true;
        lifetime.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            state = State.CLOSED;
            reader.close();
            transport.close();
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    private static void check(int error, String operation) throws IOException {
        if (error != 0) throw new IOException("cannot " + operation + " (errno " + error + ")");
    }
}
