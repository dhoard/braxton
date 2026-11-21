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

package io.github.dhoard.braxton.internal.terminal;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.Capabilities;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Internal services needed by the runtime; never part of the public MVU surface.
 */
public interface Terminal extends Closeable {

    /**
     * Where an owned terminal is in the session's handoff lifecycle.
     *
     * <p>The two intermediate states exist so a transition that fails has somewhere to be observed
     * and so a concurrent close can tell "not yet given back" from "gone". A terminal that never
     * hands off is {@link #ACTIVE} from open to {@link #CLOSED}.</p>
     */
    enum State {
        /**
         * Owned by the session and available for reads, writes and mode changes.
         */
        ACTIVE,
        /**
         * Being given back; the session is no longer allowed to use it.
         */
        RELEASING,
        /**
         * Given back to the user or to a child process the session started.
         */
        RELEASED,
        /**
         * Being taken back after a release.
         */
        REACQUIRING,
        /**
         * Closed; every later operation fails and close is a no-op.
         */
        CLOSED
    }

    /**
     * Returns the timed character reader for this terminal.
     *
     * @return the timed character reader for this terminal
     */
    TimedReader reader();

    /**
     * Returns the writer used for terminal output.
     *
     * @return the writer used for terminal output
     */
    PrintWriter writer();

    /**
     * The most recent I/O failure behind {@link #writer()}. A {@link PrintWriter} only reports
     * that an error occurred, so the runtime attaches this as the cause of its output failure;
     * {@code null} when no failure was recorded.
     *
     * @return the output failure, or null when no error is recorded
     */
    default IOException outputError() {
        return null;
    }

    /**
     * Returns the current terminal dimensions in cells.
     *
     * @return the current terminal dimensions in cells
     */
    Size size();

    /**
     * Returns the terminal facts and their per-feature evidence.
     *
     * @return the terminal facts and their per-feature evidence
     */
    Capabilities capabilities();

    /**
     * Enters the owned terminal's raw input configuration.
     *
     * @throws IOException if input or output fails
     */
    void enterRawMode() throws IOException;

    /**
     * The terminal's lifecycle state; {@link State#ACTIVE} when the session never hands it off.
     *
     * @return the current state
     */
    default State state() {
        return State.ACTIVE;
    }

    /**
     * Gives the terminal back: reads stop reporting session input and the input configuration the
     * session changed is restored, so the user's own settings are in force while somebody else owns
     * the terminal.
     *
     * <p>Only the owned POSIX terminal can do this. A supplied terminal (a test fixture, a stream)
     * owns no device configuration, so releasing it is a no-op and the runtime's own state machine is
     * the whole contract there.</p>
     *
     * @throws IOException when the configuration could not be restored
     */
    default void release() throws IOException {}

    /**
     * Takes the terminal back after {@link #release()}, re-entering the input configuration the
     * session runs with.
     *
     * @throws IOException when the terminal could not be taken back, which is fatal for the run
     */
    default void reacquire() throws IOException {}

    /**
     * Whether this session's process group is the terminal's foreground group.
     *
     * <p>Job control belongs to the foreground group only; a supplied terminal has no process group
     * of its own, so the answer is false and a suspend reports a typed refusal instead of stopping a
     * process nobody can continue.</p>
     *
     * @return true when the session owns the terminal's foreground
     */
    default boolean foreground() {
        return false;
    }

    /**
     * Stops this session's process group until the job controller continues it, returning then.
     *
     * <p>Called only while the terminal is released, and only when {@link #foreground()} is true.</p>
     *
     * @throws IOException when job control is unavailable or the stop did not complete
     */
    default void suspend() throws IOException {
        throw new IOException("this session has no job control");
    }

    /**
     * Preregisters the bytes an external stop writes before handing the terminal to the shell and
     * the bytes a continue writes when the session takes it back. The native signal path only ever
     * writes these already-encoded sequences, so no allocation or Java call happens during a stop.
     *
     * @param restore the control bytes to emit when suspending
     * @param resume the control bytes to emit when resuming
     * @throws java.io.IOException if input or output fails
     */
    default void registerSuspendBytes(String restore, String resume) throws IOException {}

    /**
     * Monotonic notification token for a completed external stop/continue cycle.
     *
     * @return monotonic notification token for a completed external stop/continue cycle
     */
    default long resumeEpoch() {
        return 0L;
    }

    /**
     * Called when shutdown begins. From then on, output the terminal does not accept within the
     * short shutdown stall limit fails instead of waiting out the running limit, including a write
     * that is already in progress. Idempotent and callable from any thread without locks.
     */
    default void boundOutput() {}

    /**
     * Registers a callback for terminal-size changes; non-device implementations may ignore it.
     *
     * @param callback the resize notification callback
     */
    default void onResize(Runnable callback) {}

    /**
     * Monotonic notification token; changes can represent a shrink/grow with equal final size.
     *
     * @return monotonic notification token; changes can represent a shrink/grow with equal final size
     */
    default long resizeEpoch() {
        return 0L;
    }

    /**
     * Writes the synchronized-update opening sequence.
     */
    default void beginSynchronizedUpdate() {
        writer().write("\u001b[?2026h");
    }

    /**
     * Writes the synchronized-update closing sequence.
     */
    default void endSynchronizedUpdate() {
        writer().write("\u001b[?2026l");
    }
}
