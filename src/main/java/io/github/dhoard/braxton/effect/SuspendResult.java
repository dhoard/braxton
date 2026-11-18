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

package io.github.dhoard.braxton.effect;

import java.io.IOException;

/**
 * How one suspend request ended.
 *
 * <p>A suspend is a request, never a promise: the session can only give its terminal to the job
 * controller when it owns the terminal's foreground, and a process group that is not in the
 * foreground has nobody to continue it. The three outcomes say which of those happened, and the
 * {@link Resumed} one doubles as the resume notification the application needs, because it is
 * delivered after the terminal has been taken back.</p>
 */
public sealed interface SuspendResult {

    /**
     * The session stopped, was continued, and has taken its terminal back.
     *
     * <p>The frame painted after this message is the resumed screen: the terminal was re-entered,
     * its size was measured again, and the scene is repainted in full because the shell and the
     * stopped program are what the terminal was showing meanwhile.</p>
     */
    record Resumed() implements SuspendResult {}

    /**
     * Nothing was stopped, and the session still owns its terminal.
     *
     * @param reason why, in one phrase meant for the application's own display
     */
    record Refused(String reason) implements SuspendResult {

        /**
         * Substitutes a generic reason for a missing one.
         */
        public Refused {
            reason = reason == null || reason.isBlank() ? "this session cannot suspend" : reason;
        }
    }

    /**
     * The session gave the terminal back and job control then failed; it has taken the terminal
     * again, so the application can report the failure on its own screen.
     *
     * @param cause the failure the operating system reported
     */
    record Failed(IOException cause) implements SuspendResult {

        /**
         * Rejects a failure with no cause.
         */
        public Failed {
            if (cause == null) {
                throw new IllegalArgumentException("cause is required");
            }
        }
    }
}
