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
 * How one handed-over child process ended.
 *
 * <p>A handoff always resolves to exactly one of these: the child ran and exited, the session could
 * not start it or could not read its status, or the session declined to hand the terminal over at
 * all. Nothing here reports a process that is still running: the session waits for the child before
 * it takes the terminal back, so the answer is final by the time it is delivered.</p>
 */
public sealed interface ProcessResult {

    /**
     * The child ran and exited.
     *
     * @param status the exit status; a child a signal ended reports {@code 128 + signal}, which is
     *     the value the operating system's wait status converts to
     */
    record Exited(int status) implements ProcessResult {}

    /**
     * The child could not be run, or its status could not be read.
     *
     * <p>The session took the terminal back before this was delivered, so an application can report
     * the failure from its own screen.</p>
     *
     * @param cause the launch or wait failure
     */
    record Failed(IOException cause) implements ProcessResult {

        /**
         * Rejects a failure with no cause.
         */
        public Failed {
            if (cause == null) {
                throw new IllegalArgumentException("cause is required");
            }
        }
    }

    /**
     * The session did not hand the terminal over.
     *
     * @param reason why, in one phrase meant for the application's own display
     */
    record Rejected(String reason) implements ProcessResult {

        /**
         * Substitutes a generic reason for a missing one.
         */
        public Rejected {
            reason = reason == null || reason.isBlank() ? "the session cannot hand off its terminal" : reason;
        }
    }

    /**
     * The exit status, or {@code -1} when the child never ran.
     *
     * @return the child's exit status, or -1
     */
    default int exitStatus() {
        return this instanceof Exited exited ? exited.status() : -1;
    }
}
