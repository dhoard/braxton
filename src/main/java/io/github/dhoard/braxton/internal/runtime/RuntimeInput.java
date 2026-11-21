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

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Runs the bounded decoder worker and producer admission to the runtime mailbox.
 */
final class RuntimeInput {
    /**
     * Stops the input reader before the terminal stops belonging to the session.
     */
    static void stopInputReader(Thread reader, RuntimeServices services) {
        if (reader == null) {
            return;
        }
        reader.interrupt();
        try {
            reader.join(500);
        } catch (InterruptedException cancellation) {
            Thread.currentThread().interrupt();
        }
        if (reader.isAlive()) {
            services.diagnostics().accept("input worker did not stop for the handoff");
        }
    }

    static Thread startInputThread(RuntimeTerminal terminalIo, RuntimeOptions options, Mailbox mailbox) {
        return Thread.ofVirtual().name("braxton-input").start(() -> {
            InputDecoder decoder = new InputDecoder(
                    terminalIo.terminal.reader(),
                    options.input(),
                    () -> terminalIo.stopping || terminalIo.released,
                    options.clipboard().maxReadEncodedCharacters());
            while (!terminalIo.stopping
                    && !terminalIo.released
                    && !Thread.currentThread().isInterrupted()) {
                try {
                    Optional<InputEvent> event = decoder.read();
                    if (event.isEmpty()) {
                        if (terminalIo.stopping || terminalIo.released) return;
                        offerControl(mailbox, new Mailbox.Close(ExitResult.inputClosed()));
                        return;
                    }
                    if (!offer(mailbox, new Mailbox.Input(event.get()))) {
                        return;
                    }
                } catch (IOException closed) {
                    if (terminalIo.stopping || terminalIo.released) return;
                    offerControl(mailbox, new Mailbox.Close(ExitResult.failure(closed, List.of())));
                    return;
                } catch (Throwable failure) {
                    if (terminalIo.stopping || terminalIo.released) return;
                    // Never let the reader die silently: an uncaught exception would leave the
                    // owner polling an empty mailbox forever (a hung application that cannot even
                    // process Ctrl+C) and print a stack trace over the alternate screen.
                    offerControl(mailbox, new Mailbox.Close(ExitResult.failure(failure, List.of())));
                    return;
                }
            }
        });
    }

    static void offerControl(Mailbox mailbox, Mailbox.Envelope envelope) {
        if (mailbox == null) {
            return;
        }
        offer(mailbox, envelope);
    }

    /**
     * Enqueues from a reader thread, waiting while the owner is busy.
     *
     * <p>An envelope must not be dropped after a fixed wait: a full mailbox means the owner is
     * still working, not that the event is disposable. Giving up would stop the input reader (and
     * the runtime would report a clean end-of-input as a failure), lose the key that could not be
     * queued, or silently end resize observation. Returns false only when the mailbox is closed,
     * which is how shutdown releases the wait; the owner's final join also interrupts it.</p>
     */
    static boolean offer(Mailbox mailbox, Mailbox.Envelope envelope) {
        while (true) {
            try {
                if (mailbox.enqueue(envelope, 100L)) {
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (mailbox.isClosed()) {
                return false;
            }
        }
    }

    static InputEvent stamp(InputEvent event, java.util.function.LongSupplier nanoTime) {
        if (event instanceof InputEvent.KeyInput key && key.capturedAtNanos() == 0L) {
            return key.stamped(nanoTime.getAsLong());
        }
        if (event instanceof InputEvent.PointerInput pointer
                && pointer.pointer().capturedAtNanos() == 0L) {
            // The click window is measured on the runtime's clock: a decoder cannot know when a
            // report was read, and a view tree must never read the clock itself.
            return new InputEvent.PointerInput(pointer.pointer().stamped(nanoTime.getAsLong()));
        }
        return event;
    }

    static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Math.max(1L, java.util.concurrent.TimeUnit.MILLISECONDS.toMillis(500)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
