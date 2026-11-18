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

import io.github.dhoard.braxton.internal.runtime.Mailbox;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Control of one program a host started with {@link Application#start}.
 *
 * <p>A run is the same runtime as {@link Application#run}, moved onto its own thread so the caller
 * keeps control: it can deliver a message, ask for a graceful quit, end the run, and read the final
 * model. It never exposes the terminal, the effect executor or any other owned resource, so nothing
 * here can put the session in a state the runtime does not own. The handle does not own the terminal
 * either: the runtime closes it on the way out, and the handle only asks.</p>
 *
 * <p>Sending is back pressure, not a queue of its own. {@link #send} waits while the mailbox is full
 * and the runtime is still going, exactly as any other producer does; {@link #trySend} never waits and
 * says whether the message was accepted, refused for want of room, or refused because the run is
 * over. Messages sent before the session opened are held in the same mailbox and delivered once it
 * does, because the mailbox exists before the run starts.</p>
 *
 * <p>{@link #quit()} is a request the session may decline - a filter can veto it - and it ends through
 * the ordinary exit path: pending frame, update in flight, cleanup. {@link #interrupt()} is the forced
 * path: nothing further is delivered, no frame is painted, and the terminal, effects and sources are
 * still cleaned up. It cannot be vetoed and it cannot wait behind a full mailbox.</p>
 *
 * <p>{@link #await()} blocks the calling thread and returns {@link ProgramResult}, the final model and
 * the exit result. Awaiting from the runtime's own thread - from inside {@code update}, a command's
 * mapper or a source - would wait for the thread that is running it, so it fails instead of
 * deadlocking.</p>
 *
 * @param <M> the root model type
 * @param <Msg> the root message type
 */
public final class ProgramHandle<M, Msg> {

    /** What happened to one message a host tried to send. */
    public enum Send {
        /** The runtime accepted the message. */
        ACCEPTED,
        /** The mailbox is full; the runtime is still working through what it already holds. */
        FULL,
        /** The run is over, or never started; nothing was delivered. */
        CLOSED
    }

    /** How long one producer wait is, before the mailbox state is checked again. */
    private static final long WAIT_MILLIS = 100L;

    private final RuntimeLoop<M, Msg> loop;
    private final Mailbox mailbox;
    private final Thread thread;
    /** The final state, published before the runtime thread ends. */
    private volatile ProgramResult<M> outcome;

    /**
     * Internal seam: a handle over a runtime that has not started yet.
     *
     * <p>Created by {@link Application#start}; a caller never builds one directly, which is what keeps
     * the terminal, the executor and the mailbox out of the public surface.</p>
     *
     * @param loop the runtime to drive; required
     * @param mailbox the mailbox the runtime will use; required
     */
    ProgramHandle(RuntimeLoop<M, Msg> loop, Mailbox mailbox) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.thread = Thread.ofPlatform().name("braxton-runtime").unstarted(this::runProgram);
    }

    /** Starts the runtime thread. Called once, by the factory that built this handle. */
    void start() {
        thread.start();
    }

    /** Runs the program on its own thread and publishes the final state. */
    private void runProgram() {
        ProgramResult<M> result;
        try {
            ExitResult exit = loop.run();
            result = new ProgramResult<>(loop.model(), exit);
        } catch (Throwable failure) {
            // A run that propagates its failure throws after cleanup instead of returning it. The
            // cause is still reported here rather than lost on a thread nobody is watching, together
            // with whatever model the run had committed.
            result = new ProgramResult<>(loop.model(), ExitResult.failure(failure, List.of()));
        }
        outcome = result;
    }

    /**
     * Delivers one message, waiting while the mailbox is full.
     *
     * @param message the message to deliver; required
     * @return true when the runtime accepted it, false when the run was already over
     */
    public boolean send(Msg message) {
        Objects.requireNonNull(message, "message");
        Mailbox.Envelope envelope = new Mailbox.AppMessage(message);
        while (true) {
            try {
                if (mailbox.enqueue(envelope, WAIT_MILLIS)) return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!isRunning()) return false;
        }
    }

    /**
     * Delivers one message without waiting.
     *
     * @param message the message to deliver; required
     * @return whether the message was accepted, refused for want of room, or refused because the run
     *     is over
     */
    public Send trySend(Msg message) {
        Objects.requireNonNull(message, "message");
        if (!isRunning()) return Send.CLOSED;
        try {
            if (mailbox.enqueue(new Mailbox.AppMessage(message), 0L)) return Send.ACCEPTED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Send.CLOSED;
        }
        return isRunning() ? Send.FULL : Send.CLOSED;
    }

    /**
     * Asks the program to quit with status 0, giving it a final update, frame and cleanup.
     *
     * @return whether the request was delivered; false when the run was already over
     */
    public boolean quit() {
        return quit(0);
    }

    /**
     * Asks the program to quit with {@code status}.
     *
     * <p>The request goes through the session like any other: a {@link MessageFilter} may veto it, and
     * an accepted request still ends through the ordinary exit path.</p>
     *
     * @param status the exit status to report
     * @return whether the request was delivered; false when the run was already over
     */
    public boolean quit(int status) {
        Mailbox.Envelope envelope = new Mailbox.Quit(status);
        while (true) {
            try {
                if (mailbox.enqueue(envelope, WAIT_MILLIS)) return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!isRunning()) return false;
        }
    }

    /**
     * Ends the run now, with bounded cleanup and no further delivery or painting.
     *
     * <p>This is the forced path: it is not a request, so no filter can veto it, and it never waits
     * behind a full mailbox. The run reports {@link ExitResult#interrupted()}, which is the same answer
     * a caller's own interruption produces.</p>
     *
     * @return whether this call stopped the run, false when it had already ended
     */
    public boolean interrupt() {
        return loop.requestStop(ExitResult.interrupted());
    }

    /**
     * Whether the program still accepts messages.
     *
     * <p>The answer is the mailbox's, not the thread's: a handle accepts messages from the moment it
     * exists - including before its runtime thread has opened the terminal - and stops the moment the
     * runtime closes its mailbox on the way out. A message sent in between is delivered once the
     * session is ready, which is why the state is not "is the thread alive".</p>
     *
     * @return true while the run can still receive a message
     */
    public boolean isRunning() {
        return !mailbox.isClosed();
    }

    /**
     * Waits for the run to end and returns its final model and exit result.
     *
     * <p>An interruption of the calling thread ends the run (as {@link #interrupt()} does), the flag is
     * restored, and the outcome is still returned: a host that is shutting down learns how the program
     * ended instead of losing it.</p>
     *
     * @return the final model and how the run ended; never null
     * @throws IllegalStateException when called from the program's own runtime thread, where it could
     *     only deadlock
     */
    public ProgramResult<M> await() {
        requireCallerThread();
        boolean interruptedHere = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException interrupted) {
                interruptedHere = true;
                loop.requestStop(ExitResult.interrupted());
            }
        }
        if (interruptedHere) Thread.currentThread().interrupt();
        ProgramResult<M> result = outcome;
        return result == null ? new ProgramResult<>(null, ExitResult.interrupted()) : result;
    }

    /**
     * Waits at most {@code timeout} for the run to end.
     *
     * @param timeout how long to wait; not negative
     * @return the final state, or empty when the run is still going
     * @throws IllegalStateException when called from the program's own runtime thread
     */
    public Optional<ProgramResult<M>> await(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        requireCallerThread();
        try {
            thread.join(Math.max(1L, timeout.toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Optional.ofNullable(outcome);
    }

    /** Refuses an await that could only wait for the current thread. */
    private void requireCallerThread() {
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("await() on the program's own runtime thread would deadlock");
        }
    }

    /** Diagnostic summary; never includes model values or terminal escape output. */
    @Override
    public String toString() {
        return "ProgramHandle[" + (isRunning() ? "running" : "finished") + "]";
    }
}
