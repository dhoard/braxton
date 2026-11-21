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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import java.io.IOException;

/**
 * Owns the inherited-terminal child process and its bounded shutdown.
 */
final class ProcessHandoff {

    private final java.time.Duration shutdownGrace;
    private final java.util.function.BooleanSupplier closing;

    ProcessHandoff(java.time.Duration shutdownGrace, java.util.function.BooleanSupplier closing) {
        this.shutdownGrace = shutdownGrace;
        this.closing = closing;
    }

    /**
     * The child process a handoff is running, so a shutdown can end it.
     */
    volatile Process activeChild;

    /**
     * Starts the child, waits for it, and reports how it ended.
     */
    ProcessResult runChild(ProcessRequest request) throws HandoffInterrupted {
        if (closing.getAsBoolean()) {
            return new ProcessResult.Rejected("the session is closing");
        }
        ProcessBuilder builder = new ProcessBuilder(request.command());
        if (request.directory() != null) {
            builder.directory(request.directory().toFile());
        }
        if (!request.environment().isEmpty()) {
            builder.environment().putAll(request.environment());
        }
        // The child inherits the terminal the session just gave back: its own stdin, stdout and
        // stderr are the device, not a pipe the session would have to copy.
        builder.inheritIO();
        Process child;
        try {
            child = builder.start();
        } catch (IOException failure) {
            return new ProcessResult.Failed(failure);
        }
        activeChild = child;
        try {
            // Shutdown may have asked for the child between the check above and this assignment,
            // in which case it found nothing to end: the child is ended here instead of left
            // running without a session.
            if (closing.getAsBoolean()) {
                terminateActiveChild();
            }
            return new ProcessResult.Exited(child.waitFor());
        } catch (InterruptedException cancellation) {
            terminateActiveChild();
            throw new HandoffInterrupted();
        } catch (RuntimeException failure) {
            return new ProcessResult.Failed(new IOException("cannot read the child's status", failure));
        } finally {
            activeChild = null;
        }
    }

    /**
     * Ends the child a handoff is running, gracefully and then forcibly.
     *
     * <p>Called from shutdown, including the JVM shutdown hook's thread, so it never waits on a lock
     * the owner holds and never blocks behind the child: it asks, gives it the session's own shutdown
     * grace to leave, and then kills it.</p>
     */
    void terminateActiveChild() {
        Process child = activeChild;
        if (child == null) {
            return;
        }
        child.destroy();
        long deadline = System.nanoTime() + Math.max(0L, shutdownGrace.toNanos());
        while (child.isAlive() && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException cancellation) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (child.isAlive()) {
            child.destroyForcibly();
        }
        activeChild = null;
    }

    /**
     * Carries a caller's interruption out of a blocked wait without losing the flag.
     */
    static final class HandoffInterrupted extends Exception {
        private static final long serialVersionUID = 1L;

        HandoffInterrupted() {
            super("the session was interrupted while a handoff was running", null, false, false);
        }
    }
}
