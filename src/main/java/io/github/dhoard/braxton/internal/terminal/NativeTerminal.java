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

import io.github.dhoard.braxton.app.TerminalCapabilities;
import io.github.dhoard.braxton.layout.Size;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** FFM adapter for Braxton's own POSIX ABI. Context close waits for active native calls. */
public final class NativeTerminal implements Terminal {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment inputTransfer = arena.allocate(8192);
    private final MemorySegment outputTransfer = arena.allocate(8192);
    // A read and output/size query may run concurrently. Close waits for bounded calls to finish.
    private final java.util.concurrent.locks.ReentrantReadWriteLock lifetime =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final MemorySegment dimensions = arena.allocate(8, 4);
    private final MemorySegment context;
    private final TerminalCapabilities capabilities;
    private final Utf8Reader reader;
    private final PrintWriter writer;
    private boolean closed;

    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String cpu = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = switch (cpu) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException("unsupported Braxton CPU: " + cpu);
        };
        if (os.equals("linux")) return "linux-" + arch;
        if (os.equals("mac os x") && arch.equals("aarch64")) return "macos-aarch64";
        throw new IllegalStateException("Braxton requires Linux or macOS ARM64: " + os + "/" + cpu);
    }

    public static NativeTerminal open() throws IOException {
        if (!NativeTerminal.class.getModule().isNativeAccessEnabled())
            throw new IllegalStateException("Braxton requires --enable-native-access=ALL-UNNAMED on the classpath,"
                    + " or --enable-native-access=io.github.dhoard.braxton on the module path");
        String type = System.getenv("TERM");
        return new NativeTerminal(type);
    }

    static TerminalCapabilities capabilities(String type) {
        String name = type == null ? "" : type.toLowerCase(Locale.ROOT);
        boolean vt = name.startsWith("xterm")
                || name.startsWith("screen")
                || name.startsWith("tmux")
                || name.startsWith("rxvt")
                || name.equals("linux")
                || name.startsWith("vt")
                || name.startsWith("foot")
                || name.startsWith("alacritty")
                || name.startsWith("wezterm")
                || name.startsWith("kitty")
                || name.startsWith("ghostty");
        if (!vt) throw new IllegalStateException("a cursor-addressable VT terminal is required (TERM=" + type + ")");
        boolean modern = !name.equals("linux") && !name.startsWith("vt");
        return new TerminalCapabilities(!name.startsWith("vt"), modern, modern, modern, type);
    }

    private NativeTerminal(String type) throws IOException {
        MemorySegment acquired;
        try {
            var slot = arena.allocate(ValueLayout.ADDRESS);
            check(call(Library.OPEN, 0, 1, slot), "open attached process terminal");
            acquired = slot.get(ValueLayout.ADDRESS, 0);
            try {
                capabilities = capabilities(type);
            } catch (RuntimeException failure) {
                check(call(Library.CLOSE, acquired), "close rejected terminal");
                throw failure;
            }
        } catch (Throwable failure) {
            arena.close();
            throw failure;
        }
        context = acquired;
        reader = new Utf8Reader(this::readBytes);
        writer = new PrintWriter(new OutputStreamWriter(
                new OutputStream() {
                    public void write(int value) throws IOException {
                        write(new byte[] {(byte) value}, 0, 1);
                    }

                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        NativeTerminal.this.write(bytes, offset, length);
                    }
                },
                StandardCharsets.UTF_8));
    }

    private int readBytes(byte[] bytes, long timeoutMillis) throws IOException {
        lifetime.readLock().lock();
        try {
            if (closed) return -1;
            int count = call(Library.READ, context, inputTransfer, bytes.length, (int) Math.min(100, timeoutMillis));
            if (count < -1) throw new IOException("terminal input failed (errno " + (-count - 2) + ")");
            if (count > 0) MemorySegment.copy(inputTransfer, ValueLayout.JAVA_BYTE, 0, bytes, 0, count);
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
            while (length > 0) {
                int count = Math.min(length, (int) outputTransfer.byteSize());
                MemorySegment.copy(bytes, offset, outputTransfer, ValueLayout.JAVA_BYTE, 0, count);
                check(call(Library.WRITE, context, outputTransfer, count), "write terminal");
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

    public TerminalCapabilities capabilities() {
        return capabilities;
    }

    public long resizeEpoch() {
        try {
            return Integer.toUnsignedLong(call(Library.RESIZE_EPOCH));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public void enterRawMode() throws IOException {
        lifetime.writeLock().lock();
        try {
            if (closed) throw new IOException("terminal closed");
            check(call(Library.RAW, context), "enter raw mode");
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    public synchronized Size size() {
        lifetime.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("terminal closed");
            check(call(Library.SIZE, context, dimensions, dimensions.asSlice(4)), "measure terminal");
            return new Size(dimensions.get(ValueLayout.JAVA_INT, 0), dimensions.get(ValueLayout.JAVA_INT, 4));
        } catch (IOException problem) {
            throw new IllegalStateException(problem);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    public void close() throws IOException {
        lifetime.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            reader.close();
            try {
                check(call(Library.CLOSE, context), "restore/close terminal");
            } finally {
                arena.close();
            }
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    private static void check(int error, String operation) throws IOException {
        if (error != 0) throw new IOException("cannot " + operation + " (errno " + error + ")");
    }

    private static int call(MethodHandle function, Object... arguments) throws IOException {
        try {
            return (int) function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("native terminal call failed", failure);
        }
    }

    // Native access is checked explicitly before this lazy holder can be initialized.
    @SuppressWarnings("restricted")
    private static final class Library {
        static final SymbolLookup SYMBOLS = load();
        static final MethodHandle OPEN =
                bind("bt_open", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        static final MethodHandle RAW = bind("bt_raw", ValueLayout.ADDRESS);
        static final MethodHandle READ =
                bind("bt_read", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        static final MethodHandle WRITE =
                bind("bt_write", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        static final MethodHandle SIZE = bind("bt_size", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        static final MethodHandle CLOSE = bind("bt_close", ValueLayout.ADDRESS);
        static final MethodHandle RESIZE_EPOCH = bind("bt_resize_epoch");

        private static SymbolLookup load() {
            try {
                var image = NativeLibraryLoader.packaged(platform());
                return NativeLibraryLoader.load(image, file -> {
                    SymbolLookup symbols = SymbolLookup.libraryLookup(file, Arena.ofAuto());
                    MethodHandle abi = Linker.nativeLinker()
                            .downcallHandle(
                                    symbols.find("bt_abi").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
                    if (call(abi) != 1) throw new IOException("unsupported Braxton native ABI");
                    return symbols;
                });
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        private static MethodHandle bind(String name, java.lang.foreign.MemoryLayout... arguments) {
            return Linker.nativeLinker()
                    .downcallHandle(
                            SYMBOLS.find(name).orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments));
        }
    }
}
