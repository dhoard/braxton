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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.terminal.TerminalOptions;

/**
 * Linux/glibc x86_64 terminal session using direct FFM calls.
 *
 * <p>The session owns an {@code /dev/tty} descriptor, its saved termios, and a wakeup-safe poll
 * loop. Closing the descriptor makes a blocked poll/read return, so shutdown never depends on
 * interrupting a thread stuck in a console read. Input is byte-oriented and decoded above this
 * layer.</p>
 *
 * <p>This layout matches glibc on Linux x86_64 only. Other Unix ABIs are rejected by
 * {@link SystemTerminalBackend} rather than guessed.</p>
 */
@SuppressWarnings("restricted")
final class LinuxTerminalSession extends BaseTerminalSession {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static final MethodHandle OPEN;
    private static final MethodHandle CLOSE;
    private static final MethodHandle READ;
    private static final MethodHandle WRITE;
    private static final MethodHandle POLL;
    private static final MethodHandle IOCTL;
    private static final MethodHandle TCGETATTR;
    private static final MethodHandle TCSETATTR;

    static {
        OPEN = down("open", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        CLOSE = down("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        READ = down("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        WRITE = down("write", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        POLL = down("poll", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        IOCTL = down("ioctl", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        TCGETATTR = down("tcgetattr", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        TCSETATTR = down("tcsetattr", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    private static MethodHandle down(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIBC.find(name).orElseThrow(
                () -> new IllegalStateException("libc symbol not found: " + name)), descriptor);
    }

    private static final int NCCS = 32;
    private static final MemoryLayout TERMIOS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("c_iflag"),
            ValueLayout.JAVA_INT.withName("c_oflag"),
            ValueLayout.JAVA_INT.withName("c_cflag"),
            ValueLayout.JAVA_INT.withName("c_lflag"),
            ValueLayout.JAVA_BYTE.withName("c_line"),
            MemoryLayout.sequenceLayout(NCCS, ValueLayout.JAVA_BYTE).withName("c_cc"),
            MemoryLayout.paddingLayout(3),
            ValueLayout.JAVA_INT.withName("c_ispeed"),
            ValueLayout.JAVA_INT.withName("c_ospeed"));
    private static final long OFF_IFLAG =
            TERMIOS.byteOffset(MemoryLayout.PathElement.groupElement("c_iflag"));
    private static final long OFF_LFLAG =
            TERMIOS.byteOffset(MemoryLayout.PathElement.groupElement("c_lflag"));
    private static final long OFF_CC =
            TERMIOS.byteOffset(MemoryLayout.PathElement.groupElement("c_cc"));

    private static final int ECHO = 0000010;
    private static final int ICANON = 0000002;
    private static final int ISIG = 0000001;
    private static final int IEXTEN = 0100000;
    private static final int IXON = 0002000;
    private static final int ICRNL = 0000400;
    private static final int BRKINT = 0000002;
    private static final int INPCK = 0000020;
    private static final int ISTRIP = 0000040;
    private static final int VTIME = 5;
    private static final int VMIN = 6;
    private static final int TCSAFLUSH = 2;
    private static final int O_RDWR = 2;
    private static final long TIOCGWINSZ = 0x5413L;
    private static final short POLLIN = 0x0001;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment termios;
    private final MemorySegment saved;
    private final MemorySegment winsize;
    private final Runnable onClosed;
    private int fd = -1;
    private volatile boolean released;

    LinuxTerminalSession(TerminalOptions options, Runnable onClosed) {
        super(options, new TerminalCapabilities(true, true, true, true, "linux"));
        this.onClosed = onClosed;
        this.termios = arena.allocate(TERMIOS);
        this.saved = arena.allocate(TERMIOS);
        this.winsize = arena.allocate(MemoryLayout.structLayout(
                ValueLayout.JAVA_SHORT.withName("ws_row"),
                ValueLayout.JAVA_SHORT.withName("ws_col"),
                ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
                ValueLayout.JAVA_SHORT.withName("ws_ypixel")));
        try {
            MemorySegment path = arena.allocateFrom("/dev/tty");
            this.fd = (int) OPEN.invokeExact(path, O_RDWR);
            if (fd < 0) {
                throw new IllegalStateException("cannot open /dev/tty");
            }
            if ((int) TCGETATTR.invokeExact(fd, termios) != 0) {
                throw new IllegalStateException("tcgetattr failed");
            }
            MemorySegment.copy(termios, 0, saved, 0, TERMIOS.byteSize());

            int iflag = termios.get(ValueLayout.JAVA_INT, OFF_IFLAG);
            int lflag = termios.get(ValueLayout.JAVA_INT, OFF_LFLAG);
            termios.set(ValueLayout.JAVA_INT, OFF_LFLAG, lflag & ~(ECHO | ICANON | ISIG | IEXTEN));
            termios.set(ValueLayout.JAVA_INT, OFF_IFLAG,
                    iflag & ~(IXON | ICRNL | BRKINT | INPCK | ISTRIP));
            termios.set(ValueLayout.JAVA_BYTE, OFF_CC + VMIN, (byte) 0);
            termios.set(ValueLayout.JAVA_BYTE, OFF_CC + VTIME, (byte) 0);
            if ((int) TCSETATTR.invokeExact(fd, TCSAFLUSH, termios) != 0) {
                throw new IllegalStateException("tcsetattr failed");
            }
            startSession();
        } catch (Throwable failure) {
            releaseNative();
            throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("terminal setup failed", failure);
        }
    }

    @Override
    protected void writeNative(String data) {
        byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (Arena local = Arena.ofConfined()) {
            MemorySegment segment = local.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
            long written = 0;
            while (written < bytes.length && !released) {
                long remaining = bytes.length - written;
                long count = (long) WRITE.invokeExact(fd, segment.asSlice(written), remaining);
                if (count <= 0) {
                    return;
                }
                written += count;
            }
        } catch (Throwable failure) {
            // A closed descriptor during shutdown is expected; nothing to recover here.
        }
    }

    @Override
    protected boolean waitReadable(int timeoutMillis) {
        if (released || fd < 0) {
            return false;
        }
        try (Arena local = Arena.ofConfined()) {
            MemorySegment pollfd = local.allocate(8);
            pollfd.set(ValueLayout.JAVA_INT, 0, fd);
            pollfd.set(ValueLayout.JAVA_SHORT, 4, POLLIN);
            int ready = (int) POLL.invokeExact(pollfd, 1L, Math.max(0, timeoutMillis));
            return ready > 0 && (pollfd.get(ValueLayout.JAVA_SHORT, 6) & POLLIN) != 0;
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    protected int readNative(byte[] buffer, int offset, int length) {
        if (released || fd < 0) {
            return -1;
        }
        try (Arena local = Arena.ofConfined()) {
            MemorySegment segment = local.allocate(length);
            long requested = length;
            long count = (long) READ.invokeExact(fd, segment, requested);
            if (count < 0) {
                return -1;
            }
            if (count == 0) {
                return 0;
            }
            MemorySegment.copy(segment, 0, MemorySegment.ofArray(buffer), offset, (int) count);
            return (int) count;
        } catch (Throwable failure) {
            return -1;
        }
    }

    @Override
    protected Size sizeNative() {
        if (released || fd < 0) {
            return new Size(24, 80);
        }
        try {
            if ((int) IOCTL.invokeExact(fd, TIOCGWINSZ, winsize) != 0) {
                return new Size(24, 80);
            }
            int rows = winsize.get(ValueLayout.JAVA_SHORT, 0);
            int cols = winsize.get(ValueLayout.JAVA_SHORT, 2);
            return rows <= 0 || cols <= 0 ? new Size(24, 80) : new Size(rows, cols);
        } catch (Throwable failure) {
            return new Size(24, 80);
        }
    }

    @Override
    protected void releaseNative() {
        if (released) {
            return;
        }
        released = true;
        int descriptor = fd;
        fd = -1;
        if (descriptor >= 0) {
            try {
                int ignored = (int) CLOSE.invokeExact(descriptor);
            } catch (Throwable ignored) {
                // Best-effort close; the descriptor is already unusable to this session.
            }
        }
        try (Arena restore = Arena.ofConfined()) {
            MemorySegment path = restore.allocateFrom("/dev/tty");
            int restoreFd = (int) OPEN.invokeExact(path, O_RDWR);
            if (restoreFd >= 0) {
                try {
                    int ignored = (int) TCSETATTR.invokeExact(restoreFd, TCSAFLUSH, saved);
                } finally {
                    int ignored = (int) CLOSE.invokeExact(restoreFd);
                }
            }
        } catch (Throwable ignored) {
            // Restoration already failed; surface nothing further from close().
        }
        try {
            arena.close();
        } catch (RuntimeException ignored) {
            // Another thread may still hold a segment; the JVM will reclaim it.
        }
    }

    @Override
    protected void onClosed() {
        if (onClosed != null) {
            onClosed.run();
        }
    }
}
