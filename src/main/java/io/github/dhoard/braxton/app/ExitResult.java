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

package io.github.dhoard.braxton.app;

import java.util.List;

/** How an application run ended. */
public record ExitResult(
        Kind kind, int status, Throwable failure, List<Throwable> cleanupFailures) {

    public enum Kind {
        /** The program asked to quit. */
        QUIT,
        /** The input stream reached end-of-file. */
        INPUT_CLOSED,
        /** The runtime or application failed. */
        FAILURE,
        /** The caller interrupted the run. */
        INTERRUPTED
    }

    public ExitResult {
        cleanupFailures = cleanupFailures == null ? List.of() : List.copyOf(cleanupFailures);
    }

    public static ExitResult quit(int status) {
        return new ExitResult(Kind.QUIT, status, null, List.of());
    }

    public static ExitResult inputClosed() {
        return new ExitResult(Kind.INPUT_CLOSED, 0, null, List.of());
    }

    public static ExitResult failure(Throwable cause, List<Throwable> cleanupFailures) {
        return new ExitResult(Kind.FAILURE, 1, cause, cleanupFailures);
    }

    public static ExitResult interrupted() {
        return new ExitResult(Kind.INTERRUPTED, 130, null, List.of());
    }

    public boolean ok() {
        return kind != Kind.FAILURE && status == 0;
    }

    /** Diagnostic summary; never includes terminal escape output. */
    @Override
    public String toString() {
        return "ExitResult[" + kind + ", status=" + status
                + (failure == null ? "" : ", failure=" + failure.getClass().getSimpleName())
                + (cleanupFailures.isEmpty() ? "" : ", cleanupFailures=" + cleanupFailures.size())
                + "]";
    }
}
