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
import java.lang.invoke.VarHandle;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.terminal.TerminalOptions;

/**
 * Windows console session.
 *
 * <p>Uses kernel32 directly: {@code CONIN$} is opened for input, the console input mode is switched
 * to virtual-terminal input, and output is written as UTF-8 through {@code CONOUT$} after switching
 * the code page. Reads wait on the console handle with a timeout so a closed session is observed
 * without depending on Java thread interruption.</p>
 *
 * <p>This backend has not been exercised on real Windows hardware in this environment; its
 * headless contract is covered by injected tests only.</p>
 */
@SuppressWarnings("restricted")
final class WindowsTerminalSession extends BaseTerminalSession {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIBRARY_ARENA = Arena.ofShared();
    private static final SymbolLookup KERNEL32 =
            SymbolLookup.libraryLookup("kernel32", LIBRARY_ARENA);

    private static final MethodHandle CREATE_FILE_W = down("CreateFileW",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle CLOSE_HANDLE = down("CloseHandle",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle GET_CONSOLE_MODE = down("GetConsoleMode",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SET_CONSOLE_MODE = down("SetConsoleMode",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle READ_FILE = down("ReadFile",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle WRITE_FILE = down("WriteFile",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle WAIT_FOR_SINGLE_OBJECT = down("WaitForSingleObject",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_CONSOLE_SCREEN_BUFFER_INFO = down(
            "GetConsoleScreenBufferInfo",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_CONSOLE_OUTPUT_CP = down("GetConsoleOutputCP",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final MethodHandle SET_CONSOLE_OUTPUT_CP = down("SetConsoleOutputCP",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private static MethodHandle down(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(KERNEL32.find(name).orElseThrow(
                () -> new IllegalStateException("kernel32 symbol not found: " + name)), descriptor);
    }

    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_READ = 1;
    private static final int FILE_SHARE_WRITE = 2;
    private static final int OPEN_EXISTING = 3;
    private static final int ENABLE_PROCESSED_INPUT = 0x0001;
    private static final int ENABLE_LINE_INPUT = 0x0002;
    private static final int ENABLE_ECHO_INPUT = 0x0004;
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;
    private static final int UTF8_CODE_PAGE = 65001;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;

    private static final MemoryLayout CSBI = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("sizeX"),
            ValueLayout.JAVA_SHORT.withName("sizeY"),
            ValueLayout.JAVA_SHORT.withName("cursorX"),
            ValueLayout.JAVA_SHORT.withName("cursorY"),
            ValueLayout.JAVA_SHORT.withName("attributes"),
            ValueLayout.JAVA_SHORT.withName("left"),
            ValueLayout.JAVA_SHORT.withName("top"),
            ValueLayout.JAVA_SHORT.withName("right"),
            ValueLayout.JAVA_SHORT.withName("bottom"),
            ValueLayout.JAVA_SHORT.withName("maxX"),
            ValueLayout.JAVA_SHORT.withName("maxY"));
    private static final VarHandle LEFT = CSBI.varHandle(MemoryLayout.PathElement.groupElement("left"));
    private static final VarHandle TOP = CSBI.varHandle(MemoryLayout.PathElement.groupElement("top"));
    private static final VarHandle RIGHT = CSBI.varHandle(MemoryLayout.PathElement.groupElement("right"));
    private static final VarHandle BOTTOM = CSBI.varHandle(MemoryLayout.PathElement.groupElement("bottom"));

    private final Runnable onClosed;
    private final Arena arena = Arena.ofShared();
    private MemorySegment inputHandle = MemorySegment.NULL;
    private MemorySegment outputHandle = MemorySegment.NULL;
    private int savedInputMode;
    private int savedOutputMode;
    private boolean inputModeSaved;
    private boolean outputModeSaved;
    private int savedOutputCodePage;
    private boolean outputCodePageSaved;
    private volatile boolean released;
    private Size fallbackSize;

    WindowsTerminalSession(TerminalOptions options, Size fallbackSize, Runnable onClosed) {
        super(options, new TerminalCapabilities(true, true, true, true, "windows"));
        this.onClosed = onClosed;
        this.fallbackSize = fallbackSize == null || fallbackSize.isEmpty()
                ? new Size(24, 80) : fallbackSize;
        try {
            inputHandle = openConsole("CONIN$", GENERIC_READ | GENERIC_WRITE);
            outputHandle = openConsole("CONOUT$", GENERIC_READ | GENERIC_WRITE);
            configureModes();
            startSession();
        } catch (Throwable failure) {
            releaseNative();
            throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("windows terminal setup failed", failure);
        }
    }

    private MemorySegment openConsole(String name, int access) throws Throwable {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment wide = local.allocateFrom(name, java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment handle = (MemorySegment) CREATE_FILE_W.invokeExact(wide, access,
                    FILE_SHARE_READ | FILE_SHARE_WRITE, MemorySegment.NULL, OPEN_EXISTING, 0,
                    MemorySegment.NULL);
            if (handle.address() == -1L) {
                throw new IllegalStateException("CreateFileW(" + name + ") failed");
            }
            return handle;
        }
    }

    private void configureModes() throws Throwable {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment mode = local.allocate(ValueLayout.JAVA_INT);
            if ((int) GET_CONSOLE_MODE.invokeExact(inputHandle, mode) == 0) {
                throw new IllegalStateException("GetConsoleMode(CONIN$) failed");
            }
            savedInputMode = mode.get(ValueLayout.JAVA_INT, 0);
            inputModeSaved = true;
            int raw = (savedInputMode
                    & ~(ENABLE_ECHO_INPUT | ENABLE_LINE_INPUT | ENABLE_PROCESSED_INPUT))
                    | ENABLE_VIRTUAL_TERMINAL_INPUT;
            if ((int) SET_CONSOLE_MODE.invokeExact(inputHandle, raw) == 0) {
                throw new IllegalStateException("SetConsoleMode(CONIN$) failed");
            }

            if ((int) GET_CONSOLE_MODE.invokeExact(outputHandle, mode) == 0) {
                throw new IllegalStateException("GetConsoleMode(CONOUT$) failed");
            }
            savedOutputMode = mode.get(ValueLayout.JAVA_INT, 0);
            outputModeSaved = true;
            if ((int) SET_CONSOLE_MODE.invokeExact(outputHandle,
                    savedOutputMode | ENABLE_VIRTUAL_TERMINAL_PROCESSING) == 0) {
                throw new IllegalStateException("SetConsoleMode(CONOUT$) failed");
            }
            // The console keeps the code page after exit, so the shell that regains it would write
            // UTF-8 into a legacy page. Remember it and put it back when the session closes.
            int previousCodePage = (int) GET_CONSOLE_OUTPUT_CP.invokeExact();
            if (previousCodePage != 0) {
                savedOutputCodePage = previousCodePage;
                outputCodePageSaved = true;
            }
            int ignored = (int) SET_CONSOLE_OUTPUT_CP.invokeExact(UTF8_CODE_PAGE);
        }
    }

    @Override
    protected void writeNative(String data) {
        if (released || outputHandle.address() == 0) {
            return;
        }
        byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (Arena local = Arena.ofConfined()) {
            MemorySegment buffer = local.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, buffer, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment written = local.allocate(ValueLayout.JAVA_INT);
            int ok = (int) WRITE_FILE.invokeExact(outputHandle, buffer, bytes.length, written,
                    MemorySegment.NULL);
            if (ok == 0) {
                return;
            }
        } catch (Throwable ignored) {
            // Output failures during shutdown are not recoverable here.
        }
    }

    @Override
    protected boolean waitReadable(int timeoutMillis) {
        if (released || inputHandle.address() == 0) {
            return false;
        }
        try {
            int result = (int) WAIT_FOR_SINGLE_OBJECT.invokeExact(inputHandle,
                    Math.max(0, timeoutMillis));
            if (result == WAIT_OBJECT_0) {
                return true;
            }
            if (result == WAIT_TIMEOUT) {
                return false;
            }
            return false;
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    protected int readNative(byte[] buffer, int offset, int length) {
        if (released || inputHandle.address() == 0) {
            return -1;
        }
        try (Arena local = Arena.ofConfined()) {
            MemorySegment segment = local.allocate(Math.max(1, length));
            MemorySegment read = local.allocate(ValueLayout.JAVA_INT);
            int ok = (int) READ_FILE.invokeExact(inputHandle, segment, length, read,
                    MemorySegment.NULL);
            if (ok == 0) {
                return -1;
            }
            int count = read.get(ValueLayout.JAVA_INT, 0);
            if (count <= 0) {
                return 0;
            }
            MemorySegment.copy(segment, 0, MemorySegment.ofArray(buffer), offset, count);
            return count;
        } catch (Throwable failure) {
            return -1;
        }
    }

    @Override
    protected Size sizeNative() {
        if (released || outputHandle.address() == 0) {
            return fallbackSize;
        }
        try (Arena local = Arena.ofConfined()) {
            MemorySegment info = local.allocate(CSBI);
            if ((int) GET_CONSOLE_SCREEN_BUFFER_INFO.invokeExact(outputHandle, info) == 0) {
                return fallbackSize;
            }
            int left = (short) LEFT.get(info, 0L);
            int top = (short) TOP.get(info, 0L);
            int right = (short) RIGHT.get(info, 0L);
            int bottom = (short) BOTTOM.get(info, 0L);
            int cols = right - left + 1;
            int rows = bottom - top + 1;
            return rows <= 0 || cols <= 0 ? fallbackSize : new Size(rows, cols);
        } catch (Throwable failure) {
            return fallbackSize;
        }
    }

    @Override
    protected void releaseNative() {
        if (released) {
            return;
        }
        released = true;
        Arena local = Arena.ofConfined();
        try {
            if (inputModeSaved) {
                int ignored = (int) SET_CONSOLE_MODE.invokeExact(inputHandle, savedInputMode);
            }
            if (outputModeSaved) {
                int ignored = (int) SET_CONSOLE_MODE.invokeExact(outputHandle, savedOutputMode);
            }
            if (outputCodePageSaved) {
                int ignored = (int) SET_CONSOLE_OUTPUT_CP.invokeExact(savedOutputCodePage);
            }
        } catch (Throwable ignored) {
            // Restoration is best effort once the handle is unusable.
        } finally {
            local.close();
        }
        closeHandle(inputHandle);
        closeHandle(outputHandle);
        inputHandle = MemorySegment.NULL;
        outputHandle = MemorySegment.NULL;
        try {
            arena.close();
        } catch (RuntimeException ignored) {
            // Shared arena may still be referenced; the JVM reclaims it at exit.
        }
    }

    private void closeHandle(MemorySegment handle) {
        if (handle == null || handle.address() == 0) {
            return;
        }
        try {
            int ignored = (int) CLOSE_HANDLE.invokeExact(handle);
        } catch (Throwable ignored) {
            // Handle is already unusable.
        }
    }

    @Override
    protected void onClosed() {
        if (onClosed != null) {
            onClosed.run();
        }
    }
}
