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
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.Never;
import java.util.function.Consumer;

/**
 * Entry point for running one MVU application.
 *
 * <p>The runtime owns the session, the mailbox, effects, subscriptions, layout, and output. The
 * program supplies only immutable values and pure functions. The returned result distinguishes a
 * requested quit, clean end-of-input, and failure, and carries any cleanup failures.</p>
 *
 * <p>Two shapes are available. {@link #run} blocks the calling thread, which is the ordinary case: the
 * caller is the runtime's owner and reads the {@link ExitResult} when the program ends. {@link #start}
 * moves the same run onto its own thread and returns a {@link ProgramHandle}, for a program whose model
 * is driven from outside - a local producer, a message queue, a socket - and whose final model the
 * caller still wants to read.</p>
 *
 * <p>Each shape has two forms: the default, which runs on the process's own terminal, and one that
 * takes {@link IoOptions}. Supplying a stream pair there replaces the terminal with endpoints the
 * caller owns, and turning the renderer off runs the program with no terminal at all - which is what a
 * piped, batch or embedded run needs. The default form is unchanged, so an application that was
 * written before either option existed behaves exactly as it did.</p>
 */
public final class Application {

    private Application() {}

    /**
     * Runs with the owned POSIX system terminal and default options.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @return how the run ended; never null
     */
    public static <M, Msg> ExitResult run(Component<M, Msg, Never> program) {
        return run(program, RuntimeOptions.defaults());
    }

    /**
     * Runs with the owned POSIX system terminal.
     *
     * <p>When the options ask for failure propagation, a failed run throws its failure after cleanup
     * instead of returning it; the terminal, effects and sources are already in the state a failed run
     * leaves them in.</p>
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @param options the immutable limits and presentation choices; required
     * @return how the run ended; never null
     */
    public static <M, Msg> ExitResult run(Component<M, Msg, Never> program, RuntimeOptions options) {
        return run(program, options, IoOptions.defaults());
    }

    /**
     * Runs with the supplied I/O endpoints and environment.
     *
     * <p>Supplying a stream pair means no terminal device is opened, no native library is loaded and no
     * device configuration changes; a renderer-disabled session paints nothing at all. Either way the
     * program runs in full - input decoding, commands, sources, the message filter and cleanup - and the
     * returned result is the same one the owned terminal reports.</p>
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @param io where the session's bytes come from and go to; null for the owned terminal
     * @return how the run ended; never null
     */
    public static <M, Msg> ExitResult run(Component<M, Msg, Never> program, IoOptions io) {
        return run(program, RuntimeOptions.defaults(), io);
    }

    /**
     * Runs with the supplied options and I/O endpoints.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @param options the immutable limits and presentation choices; null for the defaults
     * @param io where the session's bytes come from and go to; null for the owned terminal
     * @return how the run ended; never null
     */
    public static <M, Msg> ExitResult run(Component<M, Msg, Never> program, RuntimeOptions options, IoOptions io) {
        if (program == null) throw new IllegalArgumentException("program is required");
        RuntimeOptions effective = options == null ? RuntimeOptions.defaults() : options;
        IoOptions endpoints = io == null ? IoOptions.defaults() : io;
        return new RuntimeLoop<>(program, effective, null, services(endpoints), null, endpoints).run();
    }

    /**
     * Starts the run on its own thread and returns the handle that controls it.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @return a handle to the running program; never null
     */
    public static <M, Msg> ProgramHandle<M, Msg> start(Component<M, Msg, Never> program) {
        return start(program, RuntimeOptions.defaults());
    }

    /**
     * Starts the run on its own thread with the supplied I/O endpoints and environment.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @param io where the session's bytes come from and go to; null for the owned terminal
     * @return a handle to the running program; never null
     */
    public static <M, Msg> ProgramHandle<M, Msg> start(Component<M, Msg, Never> program, IoOptions io) {
        return start(program, RuntimeOptions.defaults(), io);
    }

    /**
     * Starts the run on its own thread with {@code options}.
     *
     * <p>The runtime thread owns the terminal from the moment it opens it until it has restored and
     * closed it, so exactly one session owns the process terminal at a time. The mailbox is created
     * here, before the thread starts, so a message the caller sends immediately is delivered rather
     * than refused for a race it cannot observe.</p>
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @param options the immutable limits and presentation choices; null for the defaults
     * @return a handle to the running program; never null
     */
    public static <M, Msg> ProgramHandle<M, Msg> start(Component<M, Msg, Never> program, RuntimeOptions options) {
        return start(program, options, IoOptions.defaults());
    }

    /**
     * Starts the run on its own thread with the supplied options and I/O endpoints.
     *
     * @param <M> the root model type
     * @param <Msg> the root message type
     * @param program the root component; required
     * @param options the immutable limits and presentation choices; null for the defaults
     * @param io where the session's bytes come from and go to; null for the owned terminal
     * @return a handle to the running program; never null
     */
    public static <M, Msg> ProgramHandle<M, Msg> start(
            Component<M, Msg, Never> program, RuntimeOptions options, IoOptions io) {
        if (program == null) throw new IllegalArgumentException("program is required");
        RuntimeOptions effective = options == null ? RuntimeOptions.defaults() : options;
        IoOptions endpoints = io == null ? IoOptions.defaults() : io;
        Mailbox mailbox = new Mailbox(effective.mailboxCapacity());
        ProgramHandle<M, Msg> handle = new ProgramHandle<>(
                new RuntimeLoop<>(program, effective, null, services(endpoints), mailbox, endpoints), mailbox);
        handle.start();
        return handle;
    }

    /**
     * The runtime services a session reports through: the system's own, with the caller's sink.
     *
     * <p>A diagnostic sink is application code, so it cannot end the run: a sink that throws is
     * swallowed here rather than failing a session whose only problem is its log. The runtime creates
     * no log file of its own, and nothing here writes to the terminal it is drawing on.</p>
     */
    private static RuntimeServices services(IoOptions io) {
        Consumer<String> sink = io.diagnostics();
        if (sink == null) {
            return RuntimeServices.system();
        }
        return RuntimeServices.system().withDiagnostics(message -> {
            try {
                sink.accept(message);
            } catch (RuntimeException ignored) {
                // A report about the session is not part of it.
            }
        });
    }
}
