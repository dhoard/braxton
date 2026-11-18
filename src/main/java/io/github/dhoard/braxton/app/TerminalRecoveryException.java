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

package io.github.dhoard.braxton.app;

/**
 * The session could not take its terminal back after giving it to a child process or the job
 * controller.
 *
 * <p>This is fatal for the run and is the typed error a handoff reports: the terminal's screen,
 * modes and input configuration are in a state the session cannot describe, so continuing to paint
 * would write a frame into a terminal the session does not own. It is a {@link IllegalStateException}
 * so that code which already treats a broken session as an illegal state keeps working.</p>
 */
public final class TerminalRecoveryException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param message what could not be recovered
     * @param cause why, or null when only the state is known
     */
    public TerminalRecoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
