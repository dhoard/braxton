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
import io.github.dhoard.braxton.terminal.NoControllingTerminalException;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Owns the POSIX context and every FFM allocation; callers serialize its operations.
 */
final class FfmTerminalTransport implements TerminalTransport {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment input = arena.allocate(8192);
    private final MemorySegment output = arena.allocate(8192);
    private final MemorySegment dimensions = arena.allocate(8, 4);
    private final MemorySegment context;

    FfmTerminalTransport() throws IOException {
        try {
            var slot = arena.allocate(ValueLayout.ADDRESS);
            int error = call(Library.OPEN, 0, 1, slot);
            if (error == 6 || error == 25) throw new NoControllingTerminalException();
            check(error, "open attached process terminal");
            context = slot.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable failure) {
            arena.close();
            throw failure;
        }
    }

    public int read(byte[] bytes, int timeout) throws IOException {
        int count = call(Library.READ, context, input, Math.min(bytes.length, 8192), timeout);
        if (count > 0) MemorySegment.copy(input, ValueLayout.JAVA_BYTE, 0, bytes, 0, count);
        return count;
    }

    public int write(byte[] bytes, int offset, int length, int timeout) throws IOException {
        MemorySegment.copy(bytes, offset, output, ValueLayout.JAVA_BYTE, 0, length);
        return call(Library.WRITE_SOME, context, output, length, timeout);
    }

    public int raw() throws IOException {
        return call(Library.RAW, context);
    }

    public int release() throws IOException {
        return call(Library.RELEASE, context);
    }

    public int reacquire() throws IOException {
        return call(Library.REACQUIRE, context);
    }

    public int foreground() throws IOException {
        return call(Library.FOREGROUND, context);
    }

    public int suspend() throws IOException {
        return call(Library.SUSPEND, context);
    }

    public int resizeEpoch() throws IOException {
        return call(Library.RESIZE_EPOCH);
    }

    public int resumeEpoch() throws IOException {
        return call(Library.RESUME_EPOCH);
    }

    public int registerSuspendBytes(String restore, String resume) throws IOException {
        // bt_set_suspend_bytes copies into the context before returning. Temporary buffers must
        // not accumulate in the session arena when presentation modes change repeatedly.
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment before = temporary.allocateFrom(restore, StandardCharsets.UTF_8);
            MemorySegment after = temporary.allocateFrom(resume, StandardCharsets.UTF_8);
            return call(
                    Library.SET_SUSPEND_BYTES, context, before, (int) before.byteSize(), after, (int) after.byteSize());
        }
    }

    public Size size() throws IOException {
        check(call(Library.SIZE, context, dimensions, dimensions.asSlice(4)), "measure terminal");
        return new Size(dimensions.get(ValueLayout.JAVA_INT, 0), dimensions.get(ValueLayout.JAVA_INT, 4));
    }

    public void close() throws IOException {
        try {
            check(call(Library.CLOSE, context), "restore/close terminal");
        } finally {
            arena.close();
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
        static final MethodHandle WRITE_SOME = bind(
                "bt_write_some", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        static final MethodHandle SIZE = bind("bt_size", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        static final MethodHandle CLOSE = bind("bt_close", ValueLayout.ADDRESS);
        static final MethodHandle RESIZE_EPOCH = bind("bt_resize_epoch");
        static final MethodHandle RELEASE = bind("bt_release", ValueLayout.ADDRESS);
        static final MethodHandle REACQUIRE = bind("bt_reacquire", ValueLayout.ADDRESS);
        static final MethodHandle FOREGROUND = bind("bt_foreground", ValueLayout.ADDRESS);
        static final MethodHandle SUSPEND = bind("bt_suspend", ValueLayout.ADDRESS);
        static final MethodHandle SET_SUSPEND_BYTES = bind(
                "bt_set_suspend_bytes",
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT);
        static final MethodHandle RESUME_EPOCH = bind("bt_resume_epoch");

        private static SymbolLookup load() {
            try {
                var image = NativeLibraryLoader.packaged(NativeTerminal.platform());
                return NativeLibraryLoader.load(image, file -> {
                    SymbolLookup symbols = SymbolLookup.libraryLookup(file, Arena.ofAuto());
                    MethodHandle abi = Linker.nativeLinker()
                            .downcallHandle(
                                    symbols.find("bt_abi").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
                    if (call(abi) != NativeTerminal.REQUIRED_ABI)
                        throw new IOException("unsupported Braxton native ABI");
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
