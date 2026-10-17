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

package io.github.dhoard.braxton.terminal;

import io.github.dhoard.braxton.layout.Size;

/**
 * One attached terminal.
 *
 * <p>The session owns every piece of terminal state it changes: raw mode, cursor visibility,
 * bracketed paste, mouse reporting, and the alternate screen. It also owns the input stream and a
 * <em>cancellable</em> read path: closing the session must wake a blocked reader without closing a
 * caller-owned stream such as {@code System.in}.</p>
 *
 * <p>All writes go through the owning runtime thread; implementations must not buffer frames behind
 * the caller's back.</p>
 */
public interface TerminalSession extends AutoCloseable {

    TerminalCapabilities capabilities();

    /** Current size; cheap enough to call on resize notifications. */
    Size size();

    /** Raw byte stream. The session, not the caller, owns the descriptor behind it. */
    java.io.InputStream input();

    /** Writes already-encoded output. Called only by the runtime's output owner. */
    void write(String data);

    /** True while the session is usable. */
    boolean isOpen();

    /** Restores terminal state and wakes any blocked reader. Idempotent. */
    @Override
    void close();
}
