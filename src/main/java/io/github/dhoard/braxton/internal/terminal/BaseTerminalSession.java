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
import java.io.InputStream;

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.terminal.TerminalOptions;
import io.github.dhoard.braxton.terminal.TerminalSession;

/**
 * Shared session behaviour: application modes, cursor state, input stream, and idempotent cleanup.
 *
 * <p>Subclasses own the platform handles and raw-mode save/restore. Everything they acquire is
 * released through {@link #releaseNative()}, which runs even when an earlier restore step failed.</p>
 */
abstract class BaseTerminalSession implements TerminalSession {

    private final TerminalOptions options;
    private final TerminalCapabilities capabilities;
    private final InputStream input;
    private final Thread shutdownHook;
    private volatile boolean closed;
    private volatile boolean modesEntered;
    private boolean hookRegistered;

    BaseTerminalSession(TerminalOptions options, TerminalCapabilities capabilities) {
        this.options = options == null ? TerminalOptions.DEFAULT : options;
        this.capabilities = capabilities;
        this.input = new NativeInputStream();
        this.shutdownHook = Thread.ofVirtual().name("braxton-terminal-restore").unstarted(this::close);
    }

    /**
     * Enters application modes and installs the restore hook. Called by the backend after the
     * subclass has acquired its handles, so mode output can never use an uninitialized descriptor.
     */
    final void startSession() {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        hookRegistered = true;
        enterApplicationModes();
    }

    protected final TerminalOptions options() {
        return options;
    }

    @Override
    public final TerminalCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public final InputStream input() {
        return input;
    }

    @Override
    public final boolean isOpen() {
        return !closed;
    }

    @Override
    public final void write(String data) {
        if (closed || data == null || data.isEmpty()) {
            return;
        }
        writeNative(data);
    }

    /** Writes encoded output through the platform handle. Called on the runtime owner thread. */
    protected abstract void writeNative(String data);

    /** Blocks up to {@code timeoutMillis} for input; returns true when bytes are ready. */
    protected abstract boolean waitReadable(int timeoutMillis);

    /** Reads bytes; returns -1 when the session closed or the descriptor failed. */
    protected abstract int readNative(byte[] buffer, int offset, int length);

    /** Current size; may return the fallback when probing fails. */
    protected abstract Size sizeNative();

    /** Restores console state. Must be idempotent and must not throw. */
    protected abstract void releaseNative();

    @Override
    public final Size size() {
        if (closed) {
            return new Size(24, 80);
        }
        Size size = sizeNative();
        return size == null || size.isEmpty() ? new Size(24, 80) : size;
    }

    private void enterApplicationModes() {
        StringBuilder out = new StringBuilder();
        if (options.alternateScreen()) {
            out.append(AnsiEncoder.ALT_SCREEN_ON);
        }
        out.append(AnsiEncoder.HIDE_CURSOR);
        if (options.bracketedPaste()) {
            out.append(AnsiEncoder.ENABLE_BRACKETED_PASTE);
        }
        if (options.mouse()) {
            out.append(AnsiEncoder.ENABLE_MOUSE);
        }
        // Marked before the write: a descriptor can fail after applying part of the entry (a short
        // write), and close() must still emit the inverse modes rather than leaving the alternate
        // screen and the hidden cursor behind.
        modesEntered = true;
        writeNative(out.toString());
    }

    private void leaveApplicationModes() {
        if (!modesEntered) {
            return;
        }
        StringBuilder out = new StringBuilder();
        if (options.mouse()) {
            out.append(AnsiEncoder.DISABLE_MOUSE);
        }
        if (options.bracketedPaste()) {
            out.append(AnsiEncoder.DISABLE_BRACKETED_PASTE);
        }
        out.append(AnsiEncoder.SHOW_CURSOR);
        if (options.alternateScreen()) {
            out.append(AnsiEncoder.ALT_SCREEN_OFF);
        }
        writeNative(out.toString());
        modesEntered = false;
    }

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            leaveApplicationModes();
        } catch (RuntimeException ignored) {
            // A broken output handle must not prevent raw-mode restoration.
        }
        try {
            releaseNative();
        } finally {
            if (hookRegistered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException alreadyShuttingDown) {
                    // The JVM is already running hooks; nothing more to remove.
                }
            }
            onClosed();
        }
    }

    /** Releases the backend ownership flag after native state is restored. */
    protected void onClosed() {
    }

    private final class NativeInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count <= 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) {
                throw new NullPointerException("buffer");
            }
            if (offset < 0 || length < 0 || offset + length > buffer.length) {
                throw new IndexOutOfBoundsException("offset/length outside buffer");
            }
            if (length == 0) {
                return 0;
            }
            while (!closed) {
                if (waitReadable(100)) {
                    int count = readNative(buffer, offset, length);
                    if (count > 0) {
                        return count;
                    }
                    if (count < 0) {
                        return -1;
                    }
                }
            }
            return -1;
        }

        @Override
        public int available() throws IOException {
            return !closed && waitReadable(0) ? 1 : 0;
        }

        @Override
        public void close() {
            // The session owns the terminal descriptor; closing the view of it is a no-op.
        }
    }
}
