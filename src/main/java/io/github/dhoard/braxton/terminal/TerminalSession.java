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

package io.github.dhoard.braxton.terminal;

import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import io.github.dhoard.braxton.internal.terminal.StreamTerminal;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.layout.Size;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Optional;

/**
 * A low-level terminal session an application can drive without the MVU runtime.
 *
 * <p>No native type appears in this API. A session owns its reader, writer and raw-mode state, and
 * {@link #close()} always restores them. Input is decoded once, with the same decoder the runtime
 * uses, so a key, paste or pointer report means the same thing in both APIs. A resize is reported as
 * its own event when the observed size changes.</p>
 *
 * <p>{@link #writeText(String)} is the safe output path: control characters other than tab and
 * newline become spaces. {@link #write(String)} is deliberately raw for application-owned escape
 * sequences and must never receive untrusted text.</p>
 */
public interface TerminalSession extends AutoCloseable {

    /**
     * Supplies the current terminal size, which the session samples between input events.
     */
    @FunctionalInterface
    interface SizeSource {
        /**
         * Returns the current size; never null.
         *
         * @return the current size; never null
         */
        Size get();
    }

    /**
     * Opens the controlling terminal with the platform's owned native library.
     *
     * @return opens the controlling terminal with the platform's owned native library
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    static TerminalSession open() throws TerminalException {
        try {
            return new Session(NativeTerminal.open(), InputOptions.DEFAULT);
        } catch (IOException | RuntimeException failure) {
            throw new TerminalException("could not open the terminal", failure);
        }
    }

    /**
     * Opens a stream-backed session with a fixed size; no native access or TTY is required.
     *
     * @param input the input source
     * @param output the output destination
     * @param size the requested cell dimensions
     * @return opens a stream-backed session with a fixed size; no native access or TTY is required
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    static TerminalSession of(Reader input, Writer output, Size size) throws TerminalException {
        Size fixed = size == null ? new Size(24, 80) : size;
        return of(input, output, () -> fixed);
    }

    /**
     * Opens a stream-backed session whose size may change during the run.
     *
     * @param input the input source
     * @param output the output destination
     * @param sizes the supplier of current terminal sizes
     * @return opens a stream-backed session whose size may change during the run
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    static TerminalSession of(Reader input, Writer output, SizeSource sizes) throws TerminalException {
        try {
            Terminal base = StreamTerminal.of(input, output, sizes.get());
            Terminal terminal = new ResizingTerminal(base, sizes);
            return new Session(terminal, InputOptions.DEFAULT);
        } catch (RuntimeException failure) {
            throw new TerminalException("could not create the stream session", failure);
        }
    }

    /**
     * The facts detected when the session opened.
     *
     * @return the facts detected when the session opened
     */
    Capabilities capabilities();

    /**
     * The most recently sampled size.
     *
     * @return the most recently sampled size
     */
    Size size();

    /**
     * Enters raw mode; a stream-backed session is already suitable for byte-oriented input.
     *
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    void enterRawMode() throws TerminalException;

    /**
     * Reads the next event, or empty at end of input. A size change is reported before any input
     * that was already waiting when the change was sampled.
     *
     * @return the next terminal event, or empty at end of input
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    Optional<TerminalEvent> read() throws TerminalException;

    /**
     * Writes application-owned bytes, including escape sequences, to the session's output.
     *
     * @param raw the raw-mode policy
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    void write(String raw) throws TerminalException;

    /**
     * Writes untrusted text with control characters neutralised.
     *
     * @param text the text to process
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    void writeText(String text) throws TerminalException;

    /**
     * Writes one line of untrusted text and flushes it.
     *
     * @param text the text to process
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    default void writeLine(String text) throws TerminalException {
        writeText(text);
        write("\n");
        flush();
    }

    /**
     * Moves the cursor to a zero-based cell.
     *
     * @param row the zero-based row
     * @param column the zero-based column
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    default void moveCursor(int row, int column) throws TerminalException {
        if (row < 0 || column < 0) throw new IllegalArgumentException("cursor cells cannot be negative");
        write("\u001b[" + (row + 1) + ";" + (column + 1) + "H");
    }

    /**
     * Clears the screen and homes the cursor.
     *
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    default void clear() throws TerminalException {
        write("\u001b[2J\u001b[H");
    }

    /**
     * Flushes pending output.
     *
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    void flush() throws TerminalException;

    /**
     * Restores the terminal and releases owned resources; idempotent.
     */
    @Override
    void close() throws TerminalException;
}
