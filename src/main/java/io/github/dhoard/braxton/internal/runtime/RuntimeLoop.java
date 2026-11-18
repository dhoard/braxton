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
import io.github.dhoard.braxton.app.IoOptions;
import io.github.dhoard.braxton.app.MessageFilter;
import io.github.dhoard.braxton.app.QuitRequest;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalCapabilities;
import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.app.TerminalRecoveryException;
import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import io.github.dhoard.braxton.effect.SuspendResult;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyboardEnhancements;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.input.PointerRouter;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.CursorPresenter;
import io.github.dhoard.braxton.internal.render.DurableText;
import io.github.dhoard.braxton.internal.render.InlineRenderer;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import io.github.dhoard.braxton.internal.terminal.StreamTerminal;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.view.TerminalPresentation;
import io.github.dhoard.braxton.view.View;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single runtime owner.
 *
 * <p>It opens one native terminal, initializes the program, then serializes everything that
 * touches the model: routing, {@code update}, command interpretation, subscription reconciliation,
 * layout, and output. Input, timers, tasks, sources and a program handle only enqueue immutable
 * envelopes. Rendering
 * coalesces application changes to the configured frame ceiling - 60 frames per second, so at most one
 * presentation about every 16 ms, by default. Input, resize,
 * and orderly exit flush dirty state immediately; an idle application performs no view work.
 * Nonempty frames use VT synchronized output under the same lock as control output and
 * restoration; screen state is accepted only after bracket closure and output error checking.</p>
 *
 * <p>The owner is the caller's thread. A virtual input worker enqueues normalized events.
 * The owner samples terminal sizes on a bounded interval; resize notifications from supplied
 * terminals can wake it sooner. Native reads are bounded. The POSIX resize handler only increments a lock-free counter.</p>
 *
 * <p>The session can also give its terminal away. A {@link io.github.dhoard.braxton.effect.Command.Exec}
 * or {@link io.github.dhoard.braxton.effect.Command.Suspend} runs on the owner thread, but only after
 * the input reader has stopped, every mode the session applied has been given back, and the
 * terminal's own configuration has been restored: the child process, or the shell that continued a
 * suspended job, is then the terminal's only owner. Taking it back re-enters the session's modes,
 * measures the size again, coalesces the interval ticks that elapsed meanwhile and repaints in full,
 * because the cells that were on the other screen buffer are gone. Nothing is presented while the
 * terminal is released, so an application cannot paint over the process that owns the screen.</p>
 * @param <M> the root model type
 * @param <Msg> the root message type
 */
public final class RuntimeLoop<M, Msg> {

    private static final long POLL_MILLIS = 50L;
    private static final long RESIZE_POLL_MILLIS = 250L;
    private static final AtomicBoolean OWNED = new AtomicBoolean();

    private final AtomicBoolean started = new AtomicBoolean();
    private final Component<M, Msg, Never> program;
    private final RuntimeOptions options;
    private final RuntimeServices services;
    private final Terminal suppliedTerminal;
    /** Where this session's bytes come from and go to, and whether it paints frames at all. */
    private final IoOptions endpoints;

    private final Object outputLock = new Object();
    private final AtomicBoolean resizePending = new AtomicBoolean();
    /** Whether another handoff is in flight; two processes cannot own one terminal. */
    private final AtomicBoolean handingOff = new AtomicBoolean();
    /** The minimum gap between presentations, from the options' frame ceiling. */
    private final long frameNanos;

    private volatile boolean stopping;
    /**
     * The result a caller-requested stop reports, or null when nobody asked for one.
     *
     * <p>A stop through {@link #requestStop} never returns to the model, so it needs its own answer:
     * the loop reports this instead of the end-of-input it reports when a mailbox closes for another
     * reason.</p>
     */
    private volatile ExitResult stopResult;
    /** Whether the run is in flight; a stop request for a finished run is refused. */
    private volatile boolean running;
    /** Whether the terminal has been given back, so nothing may read or write it. */
    private volatile boolean released;
    /** The child process a handoff is running, so a shutdown can end it. */
    private volatile Process activeChild;

    private volatile Terminal terminal;
    private boolean ownsConsole;
    private boolean modesEntered;
    /** Set when the session pushed enhanced-keyboard flags, so restoration pops exactly one level. */
    private boolean keyboardPushed;

    private boolean terminalClosed;
    private TerminalCapabilities capabilities;
    /** The profile the environment and the terminal negotiate, before any explicit choice. */
    private ColorProfile negotiatedProfile = ColorProfile.NONE;
    /** The profile the application asked for, or {@link ColorProfile#AUTO} to negotiate. */
    private ColorProfile requestedProfile = ColorProfile.AUTO;
    /** The profile frames are encoded with: the explicit one, or the negotiated one. */
    private ColorProfile profile = ColorProfile.NONE;

    private TerminalFacts facts;
    private Thread restoreTask;
    private long nextResizeAt;
    private long resizeEpoch;
    private boolean interrupted;

    private final LayoutEngine engine = new LayoutEngine();
    /** The cursor the session presents; created with the terminal's capabilities. */
    private CursorPresenter cursorPresenter;
    /** The session presentation and its applied-mode ledger; created with the capabilities. */
    private TerminalPresenter presenter;

    /** The managed live region of a non-alternate-screen session; created with the caret presenter. */
    private InlineRenderer inline;

    private final List<Throwable> cleanupFailures = new ArrayList<>();

    private Mailbox mailbox;
    private EffectRunner<Msg> effects;
    private SubscriptionManager<Msg> subscriptions;
    private ClipboardService clipboard;
    private TerminalQueries<Msg> queries;
    private Thread ownerThread;

    private final ScopeManager scopes = new ScopeManager();

    /**
     * Pointer hit testing, and the capture and click bookkeeping that belongs to the runtime rather
     * than to any view tree.
     */
    private final PointerRouter pointerRouter = new PointerRouter();

    private final java.util.ArrayDeque<Mailbox.AppMessage> localMessages = new java.util.ArrayDeque<>();
    private int localDispatches;
    private M model;
    private View<Msg> currentView;
    private Screen currentScreen;
    /** The focus regions of the frame on screen, in paint order: what a pointer report hits. */
    private List<LayoutEngine.Region> currentRegions = List.of();

    private Viewport viewport = Viewport.EMPTY;
    private boolean viewDirty = true;
    private long nextFrameAtNanos;

    private Thread inputThread;

    public RuntimeLoop(Component<M, Msg, Never> program, RuntimeOptions options) {
        this(program, options, null, RuntimeServices.system(), null, IoOptions.defaults());
    }

    /** Internal construction seam: owned internal terminal services. */
    public RuntimeLoop(
            Component<M, Msg, Never> program, RuntimeOptions options, Terminal terminal, RuntimeServices services) {
        this(program, options, terminal, services, null, IoOptions.defaults());
    }

    /**
     * Internal construction seam, with a mailbox the caller already holds.
     *
     * <p>A program handle creates the mailbox before the run starts, so a message sent before the
     * session opened is delivered once it does, rather than being refused for a race the caller cannot
     * see. A null mailbox is created here, which is what every other caller does.</p>
     *
     * @param program the root component; required
     * @param options the immutable limits and choices; null for the defaults
     * @param terminal the terminal to run on, or null to open the owned POSIX terminal
     * @param services the injected runtime services; required
     * @param mailbox the mailbox producers deliver to, or null to create one
     */
    public RuntimeLoop(
            Component<M, Msg, Never> program,
            RuntimeOptions options,
            Terminal terminal,
            RuntimeServices services,
            Mailbox mailbox) {
        this(program, options, terminal, services, mailbox, IoOptions.defaults());
    }

    /**
     * Internal construction seam, with a mailbox the caller already holds and the session's I/O
     * choices.
     *
     * <p>A program handle creates the mailbox before the run starts, so a message sent before the
     * session opened is delivered once it does, rather than being refused for a race the caller cannot
     * see. A null mailbox is created here, which is what every other caller does.</p>
     *
     * @param program the root component; required
     * @param options the immutable limits and choices; null for the defaults
     * @param terminal the terminal to run on, or null to open the owned POSIX terminal
     * @param services the injected runtime services; required
     * @param mailbox the mailbox producers deliver to, or null to create one
     * @param io where the session's bytes come from and go to, and whether it paints frames
     */
    public RuntimeLoop(
            Component<M, Msg, Never> program,
            RuntimeOptions options,
            Terminal terminal,
            RuntimeServices services,
            Mailbox mailbox,
            IoOptions io) {
        this.program = java.util.Objects.requireNonNull(program, "program");
        this.options = options == null ? RuntimeOptions.defaults() : options;
        this.suppliedTerminal = terminal;
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.mailbox = mailbox;
        this.endpoints = io == null ? IoOptions.defaults() : io;
        this.frameNanos = frameIntervalNanos(this.options.fps());
    }

    /**
     * The minimum gap between presentations for a frame ceiling of {@code fps} frames per second.
     *
     * <p>The interval is the ceiling's reciprocal, rounded down to whole nanoseconds and never below
     * one; a ceiling the options reject has no interval, so the range is enforced here too.</p>
     *
     * @param fps the presentation ceiling, between {@link RuntimeOptions#MIN_FPS} and
     *     {@link RuntimeOptions#MAX_FPS}
     * @return the minimum gap between presentations, in nanoseconds
     */
    public static long frameIntervalNanos(int fps) {
        if (fps < RuntimeOptions.MIN_FPS || fps > RuntimeOptions.MAX_FPS) {
            throw new IllegalArgumentException(
                    "fps must be between " + RuntimeOptions.MIN_FPS + " and " + RuntimeOptions.MAX_FPS + ": " + fps);
        }
        return Math.max(1L, TimeUnit.SECONDS.toNanos(1L) / fps);
    }

    /**
     * Ends the run as soon as the owner notices, through the session's ordinary cleanup.
     *
     * <p>This is the forced path a {@link io.github.dhoard.braxton.app.ProgramHandle} uses for an
     * interrupt: nothing else is delivered, no frame is painted, and the terminal, effects and
     * sources are cleaned up exactly as they are after any other end. It therefore cannot hang behind
     * a full mailbox: closing the mailbox releases every blocked producer and wakes the owner.</p>
     *
     * @param result how the run should report the stop; an interrupted run when null
     * @return whether the run was still going, so a caller can tell "stopped it" from "already over"
     */
    public boolean requestStop(ExitResult result) {
        if (!running || stopping) return false;
        stopResult = result == null ? ExitResult.interrupted() : result;
        Mailbox current = mailbox;
        if (current != null) current.close();
        return true;
    }

    /** The result a stop report uses: the requested one, or the ordinary end-of-input answer. */
    private ExitResult stopOutcome() {
        ExitResult requested = stopResult;
        return requested == null ? ExitResult.inputClosed() : requested;
    }

    private void openTerminal() throws IOException {
        if (suppliedTerminal != null) {
            terminal = suppliedTerminal;
        } else if (endpoints.streamed() || !renders()) {
            // A supplied stream pair, or a session that paints nothing, owns no device: no native
            // library is loaded, no device configuration changes, and no shutdown hook has anything
            // to restore when the process is signalled.
            terminal = StreamTerminal.of(endpoints.input(), endpoints.output(), endpoints.size());
        } else {
            if (!OWNED.compareAndSet(false, true))
                throw new IllegalStateException("another Braxton application owns the terminal");
            ownsConsole = true;
            // A host that owns terminal restoration itself turns this off; without a hook a signal
            // leaves the terminal as it is, which is that host's responsibility, not the session's.
            if (options.installShutdownHook()) {
                restoreTask = new Thread(this::shutdownClose, "braxton-terminal-shutdown");
                Runtime.getRuntime().addShutdownHook(restoreTask);
            }
            terminal = NativeTerminal.open();
        }
        capabilities = terminal.capabilities();
        requestedProfile = options.colorProfile();
        negotiatedProfile = ColorProfile.negotiated(capabilities.color(), environment("NO_COLOR"));
        profile = resolveProfile(requestedProfile);
        facts = new TerminalFacts(profile, capabilities, null);
        // Frames start with the cursor hidden (see the entry sequence below), so the presenter starts
        // from an untouched cursor: nothing shown, no shape, no colour.
        cursorPresenter = new CursorPresenter(profile.color());
        inline = new InlineRenderer(cursorPresenter);
        // The session modes the options ask for are the defaults every frame inherits, and the ledger
        // the presenter keeps is what the exit path gives back.
        presenter = new TerminalPresenter(options.terminal(), capabilities, profile.color());
        terminal.enterRawMode();
        resizeEpoch = terminal.resizeEpoch();
        terminal.onResize(() -> {
            resizePending.set(true);
            Mailbox current = mailbox;
            if (current != null) current.wake();
        });
        enterScreen(true);
    }

    /**
     * Writes the session's entry sequence: its presentation modes, a hidden cursor, and one pushed
     * level of enhanced-keyboard flags when the options ask for any.
     *
     * <p>Used both when the session opens the terminal and when it takes it back after a handoff. In
     * the second case the modes have been given back, so {@link TerminalPresenter#reopen()} applies
     * the session's own defaults again rather than assuming they are still in force.</p>
     *
     * @param opening true when the session is opening the terminal for the first time
     */
    private void enterScreen(boolean opening) {
        if (!renders()) {
            // A renderer-disabled session writes no mode sequence at all: its output is the
            // application's own text and nothing else, and there is no screen for a mode to belong
            // to. Leaving the entry unwritten is also what keeps the exit path from writing a
            // restoration for a mode it never entered.
            return;
        }
        synchronized (outputLock) {
            modesEntered = true;
            StringBuilder entry = new StringBuilder(opening ? presenter.open() : presenter.reopen());
            entry.append(AnsiEncoder.HIDE_CURSOR);
            KeyboardEnhancements requested = options.terminal().keyboardEnhancements();
            if (requested.any()) {
                // Push rather than set, and ask once which flags the terminal now has enabled.
                // Pushing is what the protocol asks an application to do at startup: a terminal that
                // does not implement it ignores both sequences and keeps sending legacy input, so
                // this requests the enhancement without ever assuming the terminal supports it. The
                // query is the only authority for whether the request took effect, and it answers as
                // an InputEvent the application can see. Both are written once per push, so no exit
                // can pop a level this session never pushed.
                keyboardPushed = true;
                entry.append(AnsiEncoder.pushKeyboardEnhancements(requested.flags()));
                entry.append(AnsiEncoder.QUERY_KEYBOARD_ENHANCEMENTS);
            }
            writeOutput(entry.toString());
        }
    }

    /**
     * Whether this session paints frames.
     *
     * <p>A renderer-disabled session writes no frame, no mode sequence and no synchronized-update
     * bracket, so a piped or batch run produces only what the application itself prints. Everything
     * else about the run - input decoding, commands, sources, filters, cleanup - is unchanged.</p>
     *
     * @return whether frames are painted
     */
    private boolean renders() {
        return endpoints.renderer();
    }

    /**
     * One environment value, from the session's overrides first and the process second.
     *
     * @param name the variable's name
     * @return the value, or null when neither defines it
     */
    private String environment(String name) {
        return endpoints.environment(name);
    }

    /** The environment this session's clipboard layer inspects, with its overrides applied. */
    private Map<String, String> environmentMap() {
        Map<String, String> overrides = endpoints.environment();
        if (overrides.isEmpty()) return System.getenv();
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        merged.putAll(overrides);
        return merged;
    }

    private Size terminalSize() {
        try {
            var size = terminal.size();
            if (size.rows() > 0 && size.cols() > 0) return new Size(size.rows(), size.cols());
        } catch (RuntimeException unavailable) {
            services.diagnostics().accept("terminal size temporarily unavailable");
        }
        return (viewport.rows() == 0 || viewport.cols() == 0)
                ? new Size(24, 80)
                : new Size(viewport.rows(), viewport.cols());
    }

    private void writeOutput(String data) {
        synchronized (outputLock) {
            if (stopping) throw new IllegalStateException("runtime is stopping");
            // A released terminal belongs to a child process or to the shell: a frame written now
            // would land on top of whatever that process is drawing.
            if (released) throw new IllegalStateException("terminal is released for a handoff");
            flushOutput(data);
        }
    }

    /** Present one frame atomically where supported, with a single flush after closing the update. */
    private void writeFrame(String data) {
        synchronized (outputLock) {
            if (stopping) throw new IllegalStateException("runtime is stopping");
            if (released) throw new IllegalStateException("terminal is released for a handoff");
            writeSynchronized(data);
        }
    }

    private void writeSynchronized(String data) {
        Throwable failure = null;
        try {
            terminal.beginSynchronizedUpdate();
            terminal.writer().write(data);
        } catch (RuntimeException | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            closeSynchronizedOutput(failure);
        }
    }

    /** Always end and flush, preserving the primary failure if either cleanup step fails. */
    private void closeSynchronizedOutput(Throwable primary) {
        Throwable failure = null;
        try {
            terminal.endSynchronizedUpdate();
        } catch (RuntimeException | Error ending) {
            failure = ending;
        }
        try {
            var writer = terminal.writer();
            writer.flush();
            if (writer.checkError()) throw outputFailed();
        } catch (RuntimeException | Error flushing) {
            if (failure == null) failure = flushing;
            else failure.addSuppressed(flushing);
        }
        if (failure == null) return;
        if (primary != null) {
            primary.addSuppressed(failure);
            return;
        }
        throw unchecked(failure);
    }

    /** Called only with outputLock held, including the final restoration operation. */
    private void flushOutput(String data) {
        var writer = terminal.writer();
        writer.write(data);
        writer.flush();
        if (writer.checkError()) throw outputFailed();
    }

    /** The writer hides its I/O exception, so the terminal's recorded one becomes the cause. */
    private IllegalStateException outputFailed() {
        return new IllegalStateException("terminal output failed", terminal.outputError());
    }

    /**
     * Rethrows a cleanup failure without changing its type: an existing runtime exception or error
     * keeps its identity, and any other throwable is wrapped.
     */
    static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }

    /** Shutdown restoration: close admission before restoring application modes. */
    void shutdownRestore() {
        stopping = true;
        // A child the session handed the terminal to must not outlive the session that started it;
        // asked before the terminal is restored, so the child sees the signal while it still has a
        // terminal to react to.
        terminateActiveChild();
        // Outside outputLock: a frame stalled under that lock has to notice and give up first.
        boundTerminalOutput();
        Mailbox current = mailbox;
        if (current != null) current.close();
        restoreModes();
    }

    private void shutdownClose() {
        try {
            shutdownRestore();
        } finally {
            synchronized (outputLock) {
                if (terminal != null && !terminalClosed) {
                    terminalClosed = true;
                    try {
                        terminal.close();
                    } catch (IOException failure) {
                        services.diagnostics().accept("terminal shutdown restore failed: " + failure);
                    }
                }
            }
        }
    }

    private void boundTerminalOutput() {
        Terminal current = terminal;
        if (current == null) return;
        try {
            current.boundOutput();
        } catch (RuntimeException failure) {
            services.diagnostics().accept("terminal output bound failed: " + failure);
        }
    }

    private void restoreModes() {
        synchronized (outputLock) {
            if (!modesEntered || terminal == null) return;
            modesEntered = false;
            StringBuilder exit = new StringBuilder();
            // What the session is actually reporting, not what the options asked for: a frame may have
            // changed a mode, and the ledger is the only honest account of what has to be given back.
            if (presenter != null) {
                exit.append(presenter.restoreModes());
            }
            // Only the cursor properties this session changed are put back, and before the reset and
            // the show: a session that never moved the cursor writes nothing here.
            if (cursorPresenter != null) {
                exit.append(cursorPresenter.reset());
            }
            // An inline session leaves its final frame on screen and puts the cursor on the line below
            // it, where the shell's prompt belongs. A session on the alternate screen has no region on
            // the screen it is giving back, so its tracker is only forgotten.
            if (inline != null) {
                if (presenter == null || presenter.alternateScreenEntered()) {
                    inline.reset();
                } else {
                    exit.append(inline.finish());
                }
            }
            exit.append(AnsiEncoder.RESET).append(AnsiEncoder.SHOW_CURSOR);
            // Popped before leaving the alternate screen: the protocol keeps one keyboard-mode stack
            // per screen, so a pop after the switch would restore the other screen's entry.
            if (keyboardPushed) {
                keyboardPushed = false;
                exit.append(AnsiEncoder.POP_KEYBOARD_ENHANCEMENTS);
            }
            if (presenter != null) {
                // Title, colour defaults, progress and status: independent of the screen buffer, so
                // they are restored before the alternate screen goes away.
                exit.append(presenter.restoreMetadata());
                exit.append(presenter.leaveAlternateScreen());
            }
            Throwable failure = null;
            try {
                terminal.endSynchronizedUpdate();
            } catch (RuntimeException | Error ending) {
                failure = ending;
            }
            try {
                flushOutput(exit.toString());
            } catch (RuntimeException | Error restoring) {
                if (failure != null) restoring.addSuppressed(failure);
                throw restoring;
            }
            if (failure != null) throw unchecked(failure);
        }
    }

    public ExitResult run() {
        if (!started.compareAndSet(false, true)) {
            return ExitResult.failure(new IllegalStateException("runtime can only run once"), List.of());
        }
        ownerThread = Thread.currentThread();
        running = true;
        ExitResult result;
        try {
            openTerminal();
            Size size = terminalSize();
            viewport = new Viewport(size.rows(), size.cols());
            if (mailbox == null) mailbox = new Mailbox(options.mailboxCapacity());
            RuntimeServices environment = services;
            clipboard = new ClipboardService(
                    options.clipboard(), new TerminalEnvironment(environmentMap()), this::writeOutput);
            effects = new EffectRunner<>(mailbox, environment, clipboard, options.maxConcurrentEffects());
            effects.bindOwner(Thread.currentThread());
            effects.bindScopes(scopes, this::enqueueLocal);
            effects.bindQuitGate(this::acceptsQuit);
            subscriptions = new SubscriptionManager<>(
                    mailbox, environment.timers(), options.maxSubscriptions(), environment.diagnostics());
            subscriptions.bindOwner(Thread.currentThread());
            subscriptions.bindScopes(scopes);
            subscriptions.bindFacts(() -> facts);
            // The query engine writes through the same locked output path as every frame, so a
            // request can never interleave with a frame's synchronized update.
            queries = new TerminalQueries<>(
                    this::writeOutput,
                    environment.timers(),
                    this::deliverQueryAnswer,
                    this::presentation,
                    environment.diagnostics(),
                    TerminalQueries.DEFAULT_MAX_OUTSTANDING,
                    TerminalQueries.DEFAULT_TIMEOUT,
                    options.clipboard().maxReadPayloadBytes());
            queries.bindScopes(scopes);
            queries.onReply(this::observeReply);
            effects.bindQueries(queries);
            effects.bindPresentation(this::applyRequestedProfile);
            effects.bindHandoff(this::handOff);
            effects.bindSuspension(this::suspendSession);
            effects.bindPrinter(this::print);

            InitContext context = new InitContext(viewport, capabilities);
            commit(ComponentFailure.call(ComponentPath.ROOT, ComponentFailure.Phase.INIT, () -> program.init(context)));
            drainLocal();
            present();
            inputThread = startInputThread();
            result = loop();
        } catch (InterruptedException cancellation) {
            interrupted = true;
            result = ExitResult.interrupted();
        } catch (Throwable failure) {
            result = ExitResult.failure(failure, List.of());
        }
        ExitResult outcome = cleanup(result);
        running = false;
        if (interrupted) Thread.currentThread().interrupt();
        if (options.propagateFailure() && outcome.failure() != null) {
            // After cleanup, never instead of it: the terminal, effects and sources are in the state
            // an ordinary failed run leaves them in, and the caller decides what to do with the cause.
            throw unchecked(outcome.failure());
        }
        return outcome;
    }

    // ── loop ─────────────────────────────────────────────────────────────────

    private ExitResult loop() throws InterruptedException {
        while (true) {
            if (stopping) return stopOutcome();
            long now = System.nanoTime();
            long observedEpoch = terminal.resizeEpoch();
            boolean resizeNotified = resizePending.getAndSet(false) || observedEpoch != resizeEpoch;
            resizeEpoch = observedEpoch;
            if (resizeNotified || now - nextResizeAt >= 0) {
                nextResizeAt = now + TimeUnit.MILLISECONDS.toNanos(RESIZE_POLL_MILLIS);
                Size size = terminalSize();
                if (size.rows() != viewport.rows() || size.cols() != viewport.cols()) applyResize();
                if (resizeNotified) {
                    // Shrinking can discard terminal cells even when a rapid grow restores the
                    // previous size before the next measurement. The cached frame is then stale.
                    currentScreen = null;
                    viewDirty = true;
                }
            }
            if (effects.quitRequested()) {
                return ExitResult.quit(effects.quitStatus());
            }
            if (viewDirty && frameReady()) {
                present();
            }
            Mailbox.Envelope envelope = mailbox.poll(pollMillis());
            if (envelope == null) {
                if (mailbox.isClosed()) {
                    return stopOutcome();
                }
                // Defence in depth: if the reader ever dies without reporting (an Error, a native
                // fault), shut down instead of hanging with no way to receive Ctrl+C. A handoff
                // replaces the reader, so there is only a failure to report while one is running.
                Thread reader = inputThread;
                if (reader != null && !reader.isAlive() && mailbox.size() == 0) {
                    return ExitResult.failure(
                            new IllegalStateException("input reader stopped unexpectedly"), List.of());
                }
                continue;
            }
            if (envelope instanceof Mailbox.Close close) {
                if (viewDirty && close.result().failure() == null) {
                    present();
                }
                return close.result();
            }
            if (envelope instanceof Mailbox.Quit quit) {
                // A graceful quit from outside the runtime is a request, not a stop: the filter may
                // veto it, and an accepted one still ends through the ordinary exit and cleanup. A
                // vetoed request finishes the turn like any other envelope, so nothing queued behind
                // it is held back.
                if (acceptsQuit(quit.status())) {
                    if (viewDirty) present();
                    return ExitResult.quit(quit.status());
                }
            }
            localDispatches = 0;
            if (envelope instanceof Mailbox.AppMessage message) {
                applyMessage(message);
            } else if (envelope instanceof Mailbox.Resume resume) {
                // Owner-thread work an effect asked for, such as the rest of a sequence; it runs after
                // the message that produced it, in the same order that message was delivered.
                resume.action().run();
            } else if (envelope instanceof Mailbox.Input input) {
                // A pointer report is a statement about the frame the user is looking at: presenting
                // a pending frame first is what keeps the cell the report names and the model it is
                // mapped against the same. Keys do not need this - their routing reads the tree.
                if (viewDirty && input.event() instanceof InputEvent.PointerInput) {
                    present();
                }
                applyInput(input);
            }
            drainLocal();
            if (viewDirty && (envelope instanceof Mailbox.Input || effects.quitRequested() || frameReady())) {
                present();
            }
            if (effects.quitRequested()) {
                return ExitResult.quit(effects.quitStatus());
            }
        }
    }

    private boolean frameReady() {
        return System.nanoTime() - nextFrameAtNanos >= 0;
    }

    private long pollMillis() {
        if (!viewDirty) {
            return POLL_MILLIS;
        }
        long remaining = Math.max(0L, nextFrameAtNanos - System.nanoTime());
        return Math.max(1L, Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999L)));
    }

    @SuppressWarnings("unchecked")
    private void applyMessage(Mailbox.AppMessage message) {
        if (message.origin() != null && !scopes.isCurrent(message.origin())) return;
        if (!message.valid().getAsBoolean()) return;
        Msg value = filter((Msg) message.payload());
        if (value == null) {
            // The filter dropped it: the session delivered the message and the application declined
            // it, which finishes a step that was waiting for the transition rather than suspending a
            // sequence forever.
            message.afterApply().run();
            return;
        }
        Update<M, Msg, Never> transition = ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.UPDATE, () -> program.update(model, value));
        commit(transition);
        message.afterApply().run();
    }

    /**
     * The message the session is about to deliver, after the application's own filter.
     *
     * <p>The filter runs here rather than at each producer, so every application message passes
     * through one place: routed input, a command's result, a source's emission, a query's answer and a
     * host's send. Lifecycle events never reach it - input decoding, resize, terminal replies and the
     * runtime's own cleanup are not messages.</p>
     *
     * @param message the message about to be delivered; never null
     * @return the message to deliver, or null when the filter dropped it
     */
    @SuppressWarnings("unchecked")
    private Msg filter(Msg message) {
        MessageFilter filter = options.messageFilter();
        if (filter == null) return message;
        Object verdict;
        try {
            verdict = filter.apply(message);
        } catch (RuntimeException failure) {
            // A filter is application code. Silently delivering or dropping the message would hide
            // its bug, so the run fails and the cause names the filter.
            throw new ComponentFailure(
                    ComponentPath.ROOT, ComponentFailure.Phase.UPDATE, "message filter threw", failure);
        }
        return (Msg) verdict;
    }

    /**
     * Whether a graceful quit request is accepted, offering it to the message filter first.
     *
     * <p>Quitting is the one lifecycle decision an application can decline: an unsaved-changes guard
     * or a confirmation prompt answers here. Forced termination does not ask.</p>
     *
     * @param status the exit status the run would report
     * @return whether the request is accepted
     */
    private boolean acceptsQuit(int status) {
        MessageFilter filter = options.messageFilter();
        if (filter == null) return true;
        Object verdict;
        try {
            verdict = filter.apply(new QuitRequest(status));
        } catch (RuntimeException failure) {
            throw new ComponentFailure(
                    ComponentPath.ROOT,
                    ComponentFailure.Phase.UPDATE,
                    "message filter threw on a quit request",
                    failure);
        }
        return verdict != null;
    }

    /**
     * The profile frames are encoded with: the caller's explicit choice, or the negotiated one.
     *
     * @param requested what the application asked for
     * @return the profile in force
     */
    private ColorProfile resolveProfile(ColorProfile requested) {
        return requested == null || requested == ColorProfile.AUTO ? negotiatedProfile : requested;
    }

    /** The session's presentation, as a query about it reports. */
    private TerminalQuery.Reply.Profile presentation() {
        return new TerminalQuery.Reply.Profile(profile, requestedProfile != ColorProfile.AUTO);
    }

    /**
     * Changes the profile the application asked for.
     *
     * <p>Interpreting a {@link io.github.dhoard.braxton.effect.Command.ColorProfileCommand} is the only
     * caller, and it runs on the owner thread inside a committed turn. The next frame is a full
     * repaint, because every cell was encoded with the previous profile and a diff would leave those
     * bytes on screen.</p>
     *
     * @param requested the profile to render with; null or {@code AUTO} returns to the negotiated one
     */
    private void applyRequestedProfile(ColorProfile requested) {
        if (stopping) {
            return;
        }
        requestedProfile = requested == null ? ColorProfile.AUTO : requested;
        applyProfile(resolveProfile(requestedProfile));
    }

    /** Puts one profile in force, repainting everything if it differs from the current one. */
    private void applyProfile(ColorProfile next) {
        if (next == profile) {
            return;
        }
        profile = next;
        currentScreen = null;
        viewDirty = true;
        if (cursorPresenter != null) {
            cursorPresenter.colorEnabled(next.color());
        }
        if (presenter != null) {
            presenter.colorEnabled(next.color());
        }
        publishFacts(facts.withProfile(next));
    }

    /**
     * Observes one terminal reply before its answer is delivered.
     *
     * <p>Two replies carry session-wide meaning beyond the query that asked: a termcap capability
     * confirming 24-bit colour widens the profile, and a background colour is the session's theme.
     * Both are recorded here, so a component that did not ask still learns them.</p>
     */
    private void observeReply(TerminalQuery query, TerminalQuery.Reply reply) {
        if (reply instanceof TerminalQuery.Reply.Capability capability) {
            if (capability.name().equals("RGB") || capability.name().equals("Tc")) {
                // The terminal itself says it displays 24-bit colour. Evidence never narrows a
                // profile: a session that chose 256 colours on a cautious assumption is now free to
                // use truecolor, and one that already had it is unchanged.
                applyProfile(ColorProfile.wider(profile, ColorProfile.TRUECOLOR));
            }
        } else if (reply instanceof TerminalQuery.Reply.ColorValue color
                && color.target() == TerminalQuery.Target.BACKGROUND) {
            publishFacts((facts == null ? new TerminalFacts(profile, capabilities, null) : facts)
                    .withBackground(color.color()));
        }
    }

    /** Replaces the session facts and tells the sources that declared them. */
    private void publishFacts(TerminalFacts next) {
        if (next == null || next.equals(facts)) {
            return;
        }
        facts = next;
        if (subscriptions != null) {
            subscriptions.notifyFacts(next);
        }
    }

    /**
     * Delivers one query answer, from whichever thread resolved it.
     *
     * <p>A deadline fires on a timer thread; the owner's own deliveries never wait for the owner.
     * The envelope carries the asking scope, so an answer for a component that has since unmounted is
     * discarded when the owner takes it, exactly like any other message.</p>
     */
    private void deliverQueryAnswer(Msg message, ScopeManager.Origin origin) {
        Mailbox current = mailbox;
        if (current == null) {
            return;
        }
        Mailbox.Envelope envelope = new Mailbox.AppMessage(message, origin);
        if (Thread.currentThread() == ownerThread) {
            current.enqueueFromOwner(envelope);
            return;
        }
        offer(envelope);
    }

    private void applyInput(Mailbox.Input input) {
        if (input.event() instanceof InputEvent.TerminalReplyInput reply) {
            // A reply is an answer to a question, not a gesture: it goes to the query that asked and
            // never reaches routing, so it cannot run a binding or insert text.
            queries.accept(reply.reply());
            return;
        }
        if (viewDirty) {
            // Bindings belong to the latest model even when its paint is deferred.
            currentView = componentView();
        }
        InputEvent event = stamp(input.event());
        // A pointer report is routed against the committed frame - which element was painted where -
        // and a key event against the focus path. Only one of the two ever sees a report, so the
        // wheel compatibility fallback inside the pointer router cannot deliver it twice. A report
        // may produce more than one message: the observers on its way, then the element under it.
        Iterable<Msg> routed = event instanceof InputEvent.PointerInput pointer
                ? pointerRouter.route(currentView, pointer, currentRegions)
                : InputRouter.route(currentView, event).map(List::of).orElseGet(List::of);
        for (Msg message : routed) {
            Msg value = filter(message);
            if (value == null) continue;
            Update<M, Msg, Never> transition = ComponentFailure.call(
                    ComponentPath.ROOT, ComponentFailure.Phase.UPDATE, () -> program.update(model, value));
            commit(transition);
        }
    }

    private InputEvent stamp(InputEvent event) {
        if (event instanceof InputEvent.KeyInput key && key.capturedAtNanos() == 0L) {
            return key.stamped(services.nanoTime().getAsLong());
        }
        if (event instanceof InputEvent.PointerInput pointer
                && pointer.pointer().capturedAtNanos() == 0L) {
            // The click window is measured on the runtime's clock: a decoder cannot know when a
            // report was read, and a view tree must never read the clock itself.
            return new InputEvent.PointerInput(
                    pointer.pointer().stamped(services.nanoTime().getAsLong()));
        }
        return event;
    }

    private void applyResize() {
        Size measured = terminalSize();
        viewport = new Viewport(measured.rows(), measured.cols());
        subscriptions.notifyResize(measured);
        viewDirty = true;
    }

    private void enqueueLocal(Mailbox.AppMessage message) {
        if (++localDispatches > options.maxLocalDispatchesPerTurn()) {
            var origin = message.origin();
            throw new IllegalStateException("local dispatch limit exceeded for component "
                    + (origin == null ? "root" : origin.path().toString()));
        }
        localMessages.addLast(message);
    }

    private void drainLocal() {
        while (!effects.quitRequested() && !localMessages.isEmpty()) applyMessage(localMessages.removeFirst());
        if (effects.quitRequested()) localMessages.clear();
    }

    private View<Msg> componentView() {
        return ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.VIEW, () -> program.view(model, new ViewContext(viewport)));
    }

    private void commit(Update<M, Msg, Never> transition) {
        if (stopping) return;
        if (!transition.outputs().isEmpty()) throw new IllegalArgumentException("root component emitted output");
        M candidate = transition.model();
        var tree = ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.CHILDREN, () -> program.children(candidate));
        var nextScopes = scopes.prepare(tree, options.maxComponents(), options.maxComponentDepth());
        var sources = ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.SUBSCRIPTIONS, () -> program.subscriptions(candidate));
        validateSubscriptions(sources, nextScopes);
        boolean changed = candidate != model;
        model = candidate;
        var removed = scopes.commit(nextScopes);
        for (var path : removed) effects.cancelScope(path);
        for (var path : removed) subscriptions.closeScope(path);
        subscriptions.reconcile(sources);
        effects.interpret(transition.command());
        if (changed || viewDirty) viewDirty = true;
    }

    private void validateSubscriptions(
            io.github.dhoard.braxton.effect.Subscription<Msg> sources, java.util.Map<ComponentPath, Long> nextScopes) {
        ComponentPath path = ComponentPath.ROOT;
        try {
            var leaves = io.github.dhoard.braxton.effect.Subscription.leaves(sources);
            if (leaves.size() > options.maxSubscriptions())
                throw new IllegalArgumentException("too many subscriptions");
            for (var leaf : leaves) {
                path = leaf.id().path();
                if (!nextScopes.containsKey(path))
                    throw new IllegalArgumentException("subscription belongs to undeclared component " + path);
            }
        } catch (IllegalArgumentException invalid) {
            // These are framework declaration errors, not thrown component/payload diagnostics.
            throw new ComponentFailure(path, ComponentFailure.Phase.SUBSCRIPTIONS, invalid.getMessage(), invalid);
        }
    }

    private void present() {
        present("");
    }

    /**
     * Presents one frame, after {@code prelude}, in a single synchronized update.
     *
     * <p>The prelude is how durable output reaches the terminal: the lines an application printed and
     * the frame that follows them are one write, so a terminal that renders an update atomically never
     * shows the gap between them.</p>
     *
     * @param prelude the sequences to write before this frame, or the empty string
     */
    private void present(String prelude) {
        if (stopping || released) {
            // A closing or handed-over session paints nothing: shutdown has already given the modes
            // back, and a released terminal belongs to somebody else.
            return;
        }
        // The frame has to fit the terminal it is written to. A resize that lands after the sample
        // which chose this viewport leaves the layout a column or more wider than the screen, and
        // every full row then wraps its tail into the row below. Re-sample here, the last point
        // before the layout, so the presented frame is always laid out for the width the terminal reports
        // now.
        Size size = terminalSize();
        if (size.rows() != viewport.rows() || size.cols() != viewport.cols()) {
            applyResize();
        }
        View<Msg> view = componentView();
        // Resolved before layout, so a tree that declares two presentations fails on its own terms
        // rather than after a screen has been painted for it.
        TerminalPresentation intent = ComponentFailure.call(
                        ComponentPath.ROOT, ComponentFailure.Phase.VIEW, () -> TerminalPresentation.resolve(view))
                .orElse(null);
        LayoutEngine.Layout layout = engine.layout(view, viewport.bounds());
        Screen screen = layout.screen();
        if (!renders()) {
            // A renderer-disabled session produces no output at all. The view is still built, because
            // input routing reads the tree the model produced, and the screen is not remembered
            // because nothing was painted to compare against.
            currentView = view;
            currentScreen = null;
            currentRegions = layout.regions();
            // A capture only outlives a frame while the element that holds it is still there, and that
            // is true whether or not the frame was painted: routing reads the same regions either way.
            pointerRouter.observeFrame(currentRegions);
            viewDirty = false;
            nextFrameAtNanos = System.nanoTime() + frameNanos;
            return;
        }
        TerminalPresenter.Transition transition = presenter.plan(intent);
        if (transition.bufferChanged()) {
            // The other buffer holds different cells, so there is nothing to diff against: the frame
            // is painted in full, and the terminal's cursor is somewhere else on that screen.
            currentScreen = null;
            cursorPresenter.contentPainted();
        }
        String output;
        String cursor = "";
        if (transition.alternateScreen()) {
            output = AnsiEncoder.diff(currentScreen, screen, profile);
            // The cursor comes last and inside the same synchronized update: it must land on cells the
            // frame has already painted, and a terminal that renders the update atomically never shows
            // the caret flying to its new cell. Repainting moved the terminal's cursor, so the presenter
            // re-states the caret's cell; a frame that changed nothing writes nothing at all.
            if (!output.isEmpty()) {
                cursorPresenter.contentPainted();
            }
            cursor = cursorPresenter.frame(layout.cursor());
        } else {
            // An inline frame addresses nothing absolutely: it repaints the live region from the row
            // the session started it on and places the caret by moving inside that region.
            output = inline.frame(currentScreen, screen, profile, layout.cursor());
        }
        String metadata = transition.sequences();
        if (!prelude.isEmpty() || !metadata.isEmpty() || !output.isEmpty() || !cursor.isEmpty()) {
            // One write, inside one synchronized update: the durable lines, the screen switch, the
            // modes, the metadata and the cells a terminal renders atomically cannot land in two
            // different frames.
            writeFrame(prelude + metadata + output + cursor);
        }
        // Recorded only now: a frame whose write failed failed the run, and the session must not
        // believe it entered a mode it never announced.
        presenter.commit(transition);
        currentView = view;
        currentScreen = screen;
        currentRegions = layout.regions();
        // A capture only outlives a frame while the element that holds it is still on screen: an
        // unmount, or a modal opening over it, ends the gesture instead of retargeting it.
        pointerRouter.observeFrame(currentRegions);
        viewDirty = false;
        // Match the mailbox's real wait clock, independent of application timestamps.
        nextFrameAtNanos = System.nanoTime() + frameNanos;
    }

    /**
     * Writes durable output where the session's mode defines it.
     *
     * <p>This is the command interpreter's entry point for {@link io.github.dhoard.braxton.effect.Command.PrintLine},
     * and it runs on the owner thread inside the command's interpretation, so no frame can be written
     * between the lines and the repaint that follows them.</p>
     *
     * <p>Three modes, three defined outcomes. An inline session erases its live region, writes the
     * lines where the region began and repaints the region below them in the same write. An
     * alternate-screen session has no scrollback to write into, so it does nothing: the screen it is
     * drawing on is unchanged, which is what the caller of a print command on that screen asked for.
     * A renderer-disabled session writes the text to its output as plain text, because a piped or
     * batch run has no screen to protect and its output is the only result.</p>
     *
     * @param text the text to print; control characters other than tab and newline are dropped
     */
    void print(String text) {
        if (stopping || released) return;
        if (!renders()) {
            String plain = DurableText.normalize(text);
            if (plain.isEmpty()) return;
            writeOutput(plain.endsWith("\n") ? plain : plain + "\n");
            return;
        }
        if (presenter.alternateScreenEntered()) return;
        synchronized (outputLock) {
            String prelude = inline.print(text);
            if (prelude.isEmpty()) return;
            present(prelude);
        }
    }

    // ── handoff ──────────────────────────────────────────────────────────────

    /**
     * Runs one child process in the session's terminal, on the owner thread.
     *
     * <p>The terminal is given back before the child starts and taken again before this returns, so
     * the completion the caller maps is delivered into a session that owns its screen. A second
     * handoff is rejected rather than queued: one terminal has one owner at a time.</p>
     *
     * <p>Package-private because it is the command interpreter's entry point; the package's own tests
     * call it directly to assert the second-owner rejection, which no application can reach.</p>
     */
    ProcessResult handOff(ProcessRequest request) {
        if (stopping || terminalClosed) {
            return new ProcessResult.Rejected("the session is closing");
        }
        if (!handingOff.compareAndSet(false, true)) {
            return new ProcessResult.Rejected("another handoff is already running");
        }
        boolean interruptedHere = false;
        try {
            releaseForHandoff();
            ProcessResult result;
            try {
                result = runChild(request);
            } catch (HandoffInterrupted cancellation) {
                // The caller's own interruption ends the run. The child has already been terminated
                // and the terminal is taken back below, so the interruption is re-asserted only once
                // nothing else here needs to run.
                interruptedHere = true;
                result = new ProcessResult.Rejected("the session was interrupted");
            } finally {
                reacquireAfterHandoff();
            }
            if (stopping || terminalClosed) {
                return new ProcessResult.Rejected("the session is closing");
            }
            return result;
        } finally {
            handingOff.set(false);
            if (interruptedHere) Thread.currentThread().interrupt();
        }
    }

    /** Starts the child, waits for it, and reports how it ended. */
    private ProcessResult runChild(ProcessRequest request) throws HandoffInterrupted {
        if (stopping || terminalClosed) {
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
            if (stopping || terminalClosed) {
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
     * Suspends the session, on the owner thread.
     *
     * <p>Only the process group that owns the terminal's foreground can be stopped: anything else has
     * nobody to continue it. The terminal is given back before the stop and taken again after the
     * continue, so the shell that stopped the job finds its own configuration.</p>
     *
     * <p>Package-private for the same reason as {@link #handOff}: the interpreter calls it, and the
     * package's own tests drive the suspend state machine directly.</p>
     */
    SuspendResult suspendSession() {
        if (stopping || terminalClosed) {
            return new SuspendResult.Refused("the session is closing");
        }
        if (!terminal.foreground()) {
            return new SuspendResult.Refused("this session does not own the terminal's foreground");
        }
        if (!handingOff.compareAndSet(false, true)) {
            return new SuspendResult.Refused("another handoff is already running");
        }
        try {
            releaseForHandoff();
            SuspendResult result;
            try {
                terminal.suspend();
                result = new SuspendResult.Resumed();
            } catch (IOException failure) {
                result = new SuspendResult.Failed(failure);
            } finally {
                reacquireAfterHandoff();
            }
            if (stopping || terminalClosed) {
                return new SuspendResult.Refused("the session is closing");
            }
            return result;
        } finally {
            handingOff.set(false);
        }
    }

    /**
     * Gives the terminal back: admission closes, the reader stops, the modes are given back and the
     * terminal returns to the user's own configuration.
     *
     * <p>The order is the contract. Output admission closes first because a frame written after the
     * child starts would corrupt its screen; the reader stops next because a read would take the
     * child's input; the modes are given back before the device configuration, because a terminal
     * whose settings say cooked while its screen still shows the alternate buffer is a terminal no
     * shell can use.</p>
     */
    private void releaseForHandoff() {
        released = true;
        stopInputReader();
        if (subscriptions != null) {
            // Interval periods keep running - they are monotonic durations, not wall-clock
            // deadlines - but the ticks that elapse while somebody else owns the terminal are
            // coalesced, so the application sees one tick on resume instead of a burst.
            subscriptions.pause();
        }
        restoreModes();
        try {
            terminal.release();
        } catch (IOException failure) {
            throw new TerminalRecoveryException("cannot give the terminal back for a handoff", failure);
        }
    }

    /**
     * Takes the terminal back and repaints, or does nothing when the session is already closing.
     *
     * <p>A terminal that cannot be taken back is fatal: the session no longer knows what is on the
     * screen, in what mode, or at what configuration, and every later frame would be a guess.</p>
     */
    private void reacquireAfterHandoff() {
        if (stopping || terminalClosed) {
            // Shutdown owns the terminal now; it closes it from whatever state the handoff left.
            return;
        }
        try {
            terminal.reacquire();
        } catch (IOException | RuntimeException failure) {
            if (stopping || terminalClosed) {
                return;
            }
            throw new TerminalRecoveryException("cannot take the terminal back after a handoff", failure);
        }
        released = false;
        try {
            enterScreen(false);
        } catch (RuntimeException failure) {
            throw new TerminalRecoveryException("cannot restore the session's terminal modes", failure);
        }
        // The frame on the other buffer is gone and the size may have changed while somebody else
        // owned the terminal, so the next frame measures again and paints everything.
        currentScreen = null;
        if (cursorPresenter != null) {
            cursorPresenter.contentPainted();
        }
        applyResize();
        if (subscriptions != null) {
            subscriptions.resume();
        }
        viewDirty = true;
        inputThread = startInputThread();
    }

    /** Stops the input reader before the terminal stops belonging to the session. */
    private void stopInputReader() {
        Thread reader = inputThread;
        inputThread = null;
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

    /**
     * Ends the child a handoff is running, gracefully and then forcibly.
     *
     * <p>Called from shutdown, including the JVM shutdown hook's thread, so it never waits on a lock
     * the owner holds and never blocks behind the child: it asks, gives it the session's own shutdown
     * grace to leave, and then kills it.</p>
     */
    private void terminateActiveChild() {
        Process child = activeChild;
        if (child == null) {
            return;
        }
        child.destroy();
        long deadline = System.nanoTime() + Math.max(0L, options.shutdownGrace().toNanos());
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

    /** Carries a caller's interruption out of a blocked wait without losing the flag. */
    private static final class HandoffInterrupted extends Exception {
        private static final long serialVersionUID = 1L;

        HandoffInterrupted() {
            super("the session was interrupted while a handoff was running", null, false, false);
        }
    }

    // ── threads ──────────────────────────────────────────────────────────────

    private Thread startInputThread() {
        return Thread.ofVirtual().name("braxton-input").start(() -> {
            InputDecoder decoder = new InputDecoder(
                    terminal.reader(),
                    options.input(),
                    () -> stopping || released,
                    options.clipboard().maxReadEncodedCharacters());
            while (!stopping && !released && !Thread.currentThread().isInterrupted()) {
                try {
                    Optional<InputEvent> event = decoder.read();
                    if (event.isEmpty()) {
                        if (stopping || released) return;
                        offerControl(new Mailbox.Close(ExitResult.inputClosed()));
                        return;
                    }
                    if (!offer(new Mailbox.Input(event.get()))) {
                        return;
                    }
                } catch (IOException closed) {
                    if (stopping || released) return;
                    offerControl(new Mailbox.Close(ExitResult.failure(closed, List.of())));
                    return;
                } catch (Throwable failure) {
                    if (stopping || released) return;
                    // Never let the reader die silently: an uncaught exception would leave the
                    // owner polling an empty mailbox forever (a hung application that cannot even
                    // process Ctrl+C) and print a stack trace over the alternate screen.
                    offerControl(new Mailbox.Close(ExitResult.failure(failure, List.of())));
                    return;
                }
            }
        });
    }

    private void offerControl(Mailbox.Envelope envelope) {
        if (mailbox == null) {
            return;
        }
        offer(envelope);
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
    private boolean offer(Mailbox.Envelope envelope) {
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

    // ── cleanup ──────────────────────────────────────────────────────────────

    private ExitResult cleanup(ExitResult result) {
        // Every step is contained: a failing terminal restore is a cleanup failure like a failing
        // source or effect cancellation, not the end of cleanup. Stopping here would leave the
        // mailbox open (its producers, including the input reader, keep retrying an owner that has
        // already gone away) and skip the thread joins.
        stopping = true;
        terminateActiveChild();
        boundTerminalOutput();
        if (mailbox != null) mailbox.close();
        // Let the finite input read expire normally. Stop the consumer before closing/releasing the native terminal.
        if (inputThread != null) {
            try {
                inputThread.join(500);
            } catch (InterruptedException cancellation) {
                interrupted = true;
            }
            if (inputThread.isAlive()) {
                inputThread.interrupt();
                cleanupFailures.add(new IllegalStateException("input worker did not stop"));
            }
        }
        closeTerminal();
        if (subscriptions != null) {
            try {
                subscriptions.closeAll();
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
        }
        if (effects != null) {
            try {
                effects.cancelAll();
                int remaining = effects.awaitIdle(options.shutdownGrace());
                if (remaining > 0) {
                    services.diagnostics().accept("shutdown: " + remaining + " effect task(s) still running");
                }
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
        }
        if (queries != null) {
            try {
                // No deadline may outlive the session, and no answer may be delivered into a mailbox
                // that is already closed.
                queries.close();
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
        }
        join(inputThread);
        if (result.failure() == null && !cleanupFailures.isEmpty()) {
            return ExitResult.failure(cleanupFailures.get(0), cleanupFailures);
        }
        if (!result.cleanupFailures().isEmpty()) {
            List<Throwable> all = new ArrayList<>(result.cleanupFailures());
            all.addAll(cleanupFailures);
            return new ExitResult(result.kind(), result.status(), result.failure(), all);
        }
        return new ExitResult(result.kind(), result.status(), result.failure(), cleanupFailures);
    }

    private void closeTerminal() {
        try {
            restoreModes();
        } catch (Throwable failure) {
            cleanupFailures.add(failure);
        }
        if (restoreTask != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(restoreTask);
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
            restoreTask = null;
        }
        synchronized (outputLock) {
            if (terminal != null && !terminalClosed) {
                terminalClosed = true;
                try {
                    terminal.close();
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
        }
        if (ownsConsole) {
            ownsConsole = false;
            OWNED.set(false);
        }
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Math.max(1L, TimeUnit.MILLISECONDS.toMillis(500)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exposed for tests: the committed model, never {@code null} after successful init. */
    public M model() {
        return model;
    }

    /** Exposed for tests: the last built view; a pending model may await presentation. */
    public View<Msg> view() {
        return currentView;
    }

    /** Exposed for tests: the last presented screen, if any. */
    public Screen screen() {
        return currentScreen;
    }
}
