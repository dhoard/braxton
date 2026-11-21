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
import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.app.TerminalRecoveryException;
import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import io.github.dhoard.braxton.effect.SuspendResult;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.DurableText;
import io.github.dhoard.braxton.internal.render.Screen;
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
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes model updates, effects, subscriptions, layout, and presentation on the caller's thread.
 *
 * <p>Producers enqueue immutable envelopes. Rendering coalesces changes to the configured ceiling
 * (default 60 FPS); input, resize, and orderly exit flush dirty state immediately. Idle sessions do
 * no view work. Synchronized frame output shares the control/restoration lock; the screen is accepted
 * only after bracket closure and output error checking.</p>
 *
 * <p>A virtual worker reads bounded native input. The owner polls size on a bounded interval; resize
 * notifications can wake it sooner. The POSIX handler increments only a lock-free counter.</p>
 *
 * <p>Exec and suspend stop input and restore terminal configuration before releasing ownership.
 * Reacquisition restores modes, measures size, coalesces missed ticks, and repaints fully. No frame
 * is presented while another process owns the terminal.</p>
 *
 * @param <M> the root model type
 * @param <Msg> the root message type
 */
public final class RuntimeLoop<M, Msg> {

    private static final long POLL_MILLIS = 50L;
    private static final long RESIZE_POLL_MILLIS = 250L;

    private final AtomicBoolean started = new AtomicBoolean();
    private final RuntimeTerminal terminalIo;
    private final ProcessHandoff childProcesses;
    private final Component<M, Msg, Never> program;
    private final RuntimeOptions options;
    private final RuntimeServices services;
    /**
     * Where this session's bytes come from and go to, and whether it paints frames at all.
     */
    private final IoOptions endpoints;

    private final AtomicBoolean resizePending = new AtomicBoolean();
    /**
     * Whether another handoff is in flight; two processes cannot own one terminal.
     */
    private final AtomicBoolean handingOff = new AtomicBoolean();
    /**
     * The minimum gap between presentations, from the options' frame ceiling.
     */
    private final long frameNanos;
    /**
     * Optional owner-thread state; disabled sessions retain no diagnostic messages.
     */
    private RuntimeDiagnostics debugOverlay;

    /**
     * The result a caller-requested stop reports, or null when nobody asked for one.
     *
     * <p>A stop through {@link #requestStop} never returns to the model, so it needs its own answer:
     * the loop reports this instead of the end-of-input it reports when a mailbox closes for another
     * reason.</p>
     */
    private volatile ExitResult stopResult;
    /**
     * Whether the run is in flight; a stop request for a finished run is refused.
     */
    private volatile boolean running;

    private long nextResizeAt;

    private boolean interrupted;

    private final List<Throwable> cleanupFailures = new ArrayList<>();

    private Mailbox mailbox;
    private EffectRunner<Msg> effects;
    private SubscriptionManager<Msg> subscriptions;
    private ClipboardService clipboard;
    private TerminalQueries<Msg> queries;
    private Thread ownerThread;

    private final ScopeManager scopes = new ScopeManager();

    /**
     * Pointer state: what the pointer is over, which widget owns the gesture, and the press the
     * next one is compared against.
     */
    private InteractionRouter.Interaction pointerState = InteractionRouter.Interaction.NONE;

    private final java.util.ArrayDeque<Mailbox.AppMessage> localMessages = new java.util.ArrayDeque<>();
    private int localDispatches;
    private M model;
    private Element<Msg> currentTree;
    private Screen currentScreen;
    /**
     * The resolved frame on screen, in paint order: what a pointer report is hit-tested against.
     */
    private ElementTree.Placement<Msg> currentPlacement;

    private Viewport viewport = Viewport.EMPTY;
    private boolean viewDirty = true;
    private long nextFrameAtNanos;

    private Thread inputThread;

    /**
     * Creates a {@code RuntimeLoop} value.
     *
     * @param program the root component
     * @param options the immutable configuration
     */
    public RuntimeLoop(Component<M, Msg, Never> program, RuntimeOptions options) {
        this(program, options, null, RuntimeServices.system(), null, IoOptions.defaults());
    }

    /**
     * Internal construction seam: owned internal terminal services.
     *
     * @param options the immutable configuration
     * @param program the root component
     * @param services the injected runtime services
     * @param terminal the terminal implementation, or null to open the owned POSIX terminal
     */
    public RuntimeLoop(
            Component<M, Msg, Never> program, RuntimeOptions options, Terminal terminal, RuntimeServices services) {
        this(program, options, terminal, services, null, IoOptions.defaults());
    }

    /**
     * Internal construction seam, with a mailbox the caller already holds.
     *
     * <p>A supplied mailbox preserves messages queued before startup; null creates a mailbox.</p>
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
     * <p>A supplied mailbox preserves messages queued before startup; null creates a mailbox.</p>
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
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.mailbox = mailbox;
        this.endpoints = io == null ? IoOptions.defaults() : io;
        this.terminalIo = new RuntimeTerminal(
                this.options, this.services, this.endpoints, terminal, this::shutdownRestore, () -> {
                    resizePending.set(true);
                    Mailbox current = this.mailbox;
                    if (current != null) current.wake();
                });

        this.childProcesses = new ProcessHandoff(
                this.options.shutdownGrace(), () -> terminalIo.stopping || terminalIo.terminalClosed);
        this.frameNanos = RuntimeOptions.frameIntervalNanos(this.options.fps());
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
        if (!running || terminalIo.stopping) return false;
        stopResult = result == null ? ExitResult.interrupted() : result;
        Mailbox current = mailbox;
        if (current != null) current.close();
        return true;
    }

    /**
     * The result a stop report uses: the requested one, or the ordinary end-of-input answer.
     */
    private ExitResult stopOutcome() {
        ExitResult requested = stopResult;
        return requested == null ? ExitResult.inputClosed() : requested;
    }

    /**
     * Shutdown restoration: close admission before restoring application modes.
     */
    void shutdownRestore() {
        terminalIo.stopping = true;
        // A child the session handed the terminal to must not outlive the session that started it;
        // asked before the terminal is restored, so the child sees the signal while it still has a
        // terminal to react to.
        childProcesses.terminateActiveChild();
        // Outside outputLock: a frame stalled under that lock has to notice and give up first.
        terminalIo.boundTerminalOutput();
        Mailbox current = mailbox;
        if (current != null) current.close();
        terminalIo.restoreModes();
    }

    /**
     * Runs the owner loop once and restores all owned resources before returning.
     *
     * @return the structured session outcome, including cleanup failures
     */
    public ExitResult run() {
        if (!started.compareAndSet(false, true)) {
            return ExitResult.failure(new IllegalStateException("runtime can only run once"), List.of());
        }
        ownerThread = Thread.currentThread();
        running = true;
        ExitResult result;
        try {
            terminalIo.openTerminal();
            Size size = terminalIo.terminalSize(viewport);
            viewport = new Viewport(size.rows(), size.cols());
            if (mailbox == null) mailbox = new Mailbox(options.mailboxCapacity());
            RuntimeServices environment = services;
            clipboard = new ClipboardService(
                    options.clipboard(), new TerminalEnvironment(terminalIo.environmentMap()), terminalIo::writeOutput);
            effects = new EffectRunner<>(mailbox, environment, clipboard, options.maxConcurrentEffects());
            effects.bindOwner(Thread.currentThread());
            effects.bindScopes(scopes, this::enqueueLocal);
            effects.bindQuitGate(this::acceptsQuit);
            subscriptions = new SubscriptionManager<>(
                    mailbox, environment.timers(), options.maxSubscriptions(), environment.diagnostics());
            subscriptions.bindOwner(Thread.currentThread());
            subscriptions.bindScopes(scopes);
            subscriptions.bindFacts(() -> terminalIo.facts);
            // The query engine writes through the same locked output path as every frame, so a
            // request can never interleave with a frame's synchronized update.
            queries = new TerminalQueries<>(
                    terminalIo::writeOutput,
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
            effects.bindDebugOverlay(this::applyDebugOverlay);
            effects.bindHandoff(this::handOff);
            effects.bindSuspension(this::suspendSession);
            effects.bindPrinter(this::print);

            InitContext context = new InitContext(viewport, terminalIo.capabilities);
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
            throw RuntimeTerminal.unchecked(outcome.failure());
        }
        return outcome;
    }

    // ── loop ─────────────────────────────────────────────────────────────────

    private ExitResult loop() throws InterruptedException {
        while (true) {
            if (terminalIo.stopping) return stopOutcome();
            long now = System.nanoTime();
            long observedEpoch = terminalIo.terminal.resizeEpoch();
            boolean resizeNotified = resizePending.getAndSet(false) || observedEpoch != terminalIo.resizeEpoch;
            terminalIo.resizeEpoch = observedEpoch;
            long observedResume = terminalIo.terminal.resumeEpoch();
            if (observedResume != terminalIo.resumeEpoch) {
                terminalIo.resumeEpoch = observedResume;
                // The native path has just replayed the entry sequence. Repaint from an empty frame.
                currentScreen = null;
                viewDirty = true;
            }
            if (resizeNotified || now - nextResizeAt >= 0) {
                nextResizeAt = now + TimeUnit.MILLISECONDS.toNanos(RESIZE_POLL_MILLIS);
                Size size = terminalIo.terminalSize(viewport);
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
                // Defense in depth: if the reader ever dies without reporting (an Error, a native
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
        if (debugOverlay != null) {
            debugOverlay.record(value);
            viewDirty = true;
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
     * The session's presentation, as a query about it reports.
     */
    private TerminalQuery.Reply.Profile presentation() {
        return new TerminalQuery.Reply.Profile(terminalIo.profile, terminalIo.requestedProfile != ColorProfile.AUTO);
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
        if (terminalIo.stopping) {
            return;
        }
        terminalIo.requestedProfile = requested == null ? ColorProfile.AUTO : requested;
        applyProfile(terminalIo.resolveProfile(terminalIo.requestedProfile));
    }

    private void applyDebugOverlay(boolean enabled) {
        if (terminalIo.stopping || !terminalIo.renders() || enabled == (debugOverlay != null)) return;
        debugOverlay = enabled ? new RuntimeDiagnostics() : null;
        viewDirty = true;
    }

    /**
     * Puts one profile in force, repainting everything if it differs from the current one.
     */
    private void applyProfile(ColorProfile next) {
        if (next == terminalIo.profile) {
            return;
        }
        terminalIo.profile = next;
        currentScreen = null;
        viewDirty = true;
        if (terminalIo.cursorPresenter != null) {
            terminalIo.cursorPresenter.colorEnabled(next.color());
        }
        if (terminalIo.presenter != null) {
            terminalIo.presenter.colorEnabled(next.color());
        }
        publishFacts(terminalIo.facts.withProfile(next));
    }

    /**
     * Observes one terminal reply before its answer is delivered.
     *
     * <p>Two replies carry session-wide meaning beyond the query that asked: a termcap capability
     * confirming 24-bit color widens the profile, and a background color is the session's theme.
     * Both are recorded here, so a component that did not ask still learns them.</p>
     */
    private void observeReply(TerminalQuery query, TerminalQuery.Reply reply) {
        if (reply instanceof TerminalQuery.Reply.Capability capability) {
            if (capability.name().equals("RGB") || capability.name().equals("Tc")) {
                // The terminal itself says it displays 24-bit color. Evidence never narrows a
                // profile: a session that chose 256 colors on a cautious assumption is now free to
                // use truecolor, and one that already had it is unchanged.
                applyProfile(ColorProfile.wider(terminalIo.profile, ColorProfile.TRUECOLOR));
            }
        } else if (reply instanceof TerminalQuery.Reply.ColorValue color
                && color.target() == TerminalQuery.Target.BACKGROUND) {
            publishFacts((terminalIo.facts == null
                            ? new TerminalFacts(terminalIo.profile, terminalIo.capabilities, null)
                            : terminalIo.facts)
                    .withBackground(color.color()));
        }
    }

    /**
     * Replaces the session facts and tells the sources that declared them.
     */
    private void publishFacts(TerminalFacts next) {
        if (next == null || next.equals(terminalIo.facts)) {
            return;
        }
        terminalIo.facts = next;
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
            currentTree = componentTree();
            currentPlacement = null;
        }
        InputEvent event = RuntimeInput.stamp(input.event(), services.nanoTime());
        // A pointer report is routed against the committed frame - which widget was painted where -
        // and a key event against the tree the model produced. Only one of the two ever sees a
        // report, so the wheel compatibility fallback cannot deliver it twice.
        Iterable<Msg> routed;
        if (event instanceof InputEvent.PointerInput pointer) {
            java.util.List<Msg> produced = pointerGesture(pointer);
            if (produced.isEmpty()) {
                // Nothing took the wheel, so it becomes the gesture it used to arrive as, and it is
                // routed through the same tiers a key is. Delivering it here rather than beside the
                // hit test is what keeps one path: the wheel reaches an application's claims
                // exactly once, and never twice.
                produced = wheelGesture(pointer);
            }
            routed = produced;
        } else {
            routed = InteractionRouter.route(currentTree, event, claimBindings(), applicationBindings());
        }
        for (Msg message : routed) {
            dispatch(message);
        }
        // What the component made of the event is dispatched after routing, so an observation never
        // reorders a widget's own reaction to the gesture it observed.
        for (Msg message : ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.UPDATE, () -> program.observed(event))) {
            dispatch(message);
        }
    }

    /**
     * Routes one pointer report against the frame on screen.
     *
     * <p>The report is hit-tested against the placement that was painted, not against the tree the
     * newest model would build, because the user pointed at a cell of the frame they can see. The
     * pointer state the answer carries - the capture and the press a double click is counted from -
     * outlives that frame, so a gesture keeps going while the model it changes repaints.</p>
     */
    private java.util.List<Msg> pointerGesture(InputEvent.PointerInput input) {
        if (currentPlacement == null) {
            return java.util.List.of();
        }
        java.util.List<Msg> produced = new java.util.ArrayList<>(1);
        pointerState = InteractionRouter.pointer(currentPlacement, pointerState, input.pointer(), produced::add);
        return java.util.List.copyOf(produced);
    }

    /**
     * The key a wheel report becomes when nothing took it, routed as a key.
     */
    private java.util.List<Msg> wheelGesture(InputEvent.PointerInput pointer) {
        if (!pointer.pointer().action().name().startsWith("WHEEL")) {
            return java.util.List.of();
        }
        return switch (pointer.pointer().button()) {
            case WHEEL_UP ->
                InteractionRouter.route(
                        currentTree,
                        InputEvent.KeyInput.of(io.github.dhoard.braxton.input.Key.WHEEL_UP),
                        claimBindings(),
                        applicationBindings());
            case WHEEL_DOWN ->
                InteractionRouter.route(
                        currentTree,
                        InputEvent.KeyInput.of(io.github.dhoard.braxton.input.Key.WHEEL_DOWN),
                        claimBindings(),
                        applicationBindings());
            default -> java.util.List.of();
        };
    }

    /**
     * Sends one message through the filter and the root update, committing whatever it changes.
     */
    private void dispatch(Msg message) {
        Msg value = filter(message);
        if (value == null) return;
        if (debugOverlay != null) {
            debugOverlay.record(value);
            viewDirty = true;
        }
        Update<M, Msg, Never> transition = ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.UPDATE, () -> program.update(model, value));
        commit(transition);
    }

    private void applyResize() {
        Size measured = terminalIo.terminalSize(viewport);
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

    /**
     * What the root component's model asks the frame to present, or null when it asks for nothing.
     *
     * <p>Null is an answer here, so the hook is not called through {@link ComponentFailure#call},
     * which rejects one: a component that declares no presentation keeps the session's own options.</p>
     */
    private TerminalPresentation declaredPresentation() {
        try {
            return program.presentation(model);
        } catch (RuntimeException | Error failure) {
            throw failure instanceof ComponentFailure componentFailure
                    ? componentFailure
                    : new ComponentFailure(ComponentPath.ROOT, ComponentFailure.Phase.VIEW, failure);
        }
    }

    /**
     * The root component's own bindings, read from the model the frame was painted from.
     *
     * <p>They are matched only after the tree has declined the gesture, so an application shortcut
     * never takes a key away from the field being edited.</p>
     */
    private java.util.List<Binding<Msg>> applicationBindings() {
        return ComponentFailure.call(ComponentPath.ROOT, ComponentFailure.Phase.VIEW, () -> program.bindings(model));
    }

    /**
     * The root component's claimed gestures, matched before the tree answers.
     */
    private java.util.List<Binding<Msg>> claimBindings() {
        return ComponentFailure.call(ComponentPath.ROOT, ComponentFailure.Phase.VIEW, () -> program.claims(model));
    }

    /**
     * The root component's element tree for the current model.
     *
     * <p>The tree is built from the model and the viewport every frame, which is what makes focus,
     * focus order and every painted cell a function of the application's own state.</p>
     */
    private Element<Msg> componentTree() {
        return ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.VIEW, () -> program.view(model, new ViewContext(viewport)));
    }

    private void commit(Update<M, Msg, Never> transition) {
        if (terminalIo.stopping) return;
        if (!transition.outputs().isEmpty()) throw new IllegalArgumentException("root component emitted output");
        M candidate = transition.model();
        var tree = ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.CHILDREN, () -> program.children(candidate));
        var nextScopes = scopes.prepare(tree, options.maxComponents(), options.maxComponentDepth());
        var sources = ComponentFailure.call(
                ComponentPath.ROOT, ComponentFailure.Phase.SUBSCRIPTIONS, () -> program.subscriptions(candidate));
        ScopeManager.validateSubscriptions(sources, nextScopes, options.maxSubscriptions());
        boolean changed = candidate != model;
        model = candidate;
        var removed = scopes.commit(nextScopes);
        for (var path : removed) effects.cancelScope(path);
        for (var path : removed) subscriptions.closeScope(path);
        subscriptions.reconcile(sources);
        effects.interpret(transition.command());
        if (changed || viewDirty) viewDirty = true;
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
        if (terminalIo.stopping || terminalIo.released) {
            // A closing or handed-over session paints nothing: shutdown has already given the modes
            // back, and a released terminal belongs to somebody else.
            return;
        }
        // The frame has to fit the terminal it is written to. A resize that lands after the sample
        // which chose this viewport leaves the layout a column or more wider than the screen, and
        // every full row then wraps its tail into the row below. Re-sample here, the last point
        // before the layout, so the presented frame is always laid out for the width the terminal reports
        // now.
        Size size = terminalIo.terminalSize(viewport);
        if (size.rows() != viewport.rows() || size.cols() != viewport.cols()) {
            applyResize();
        }
        long debugStarted = debugOverlay == null ? 0 : services.nanoTime().getAsLong();
        Element<Msg> tree = componentTree();
        // Resolved before layout, so a frame is never painted for an intent the session cannot
        // present.
        TerminalPresentation intent = declaredPresentation();
        // The frame is resolved and painted in cells the terminal has: sizes go down from the
        // viewport, every widget arranges itself inside the rectangle it was given, and nothing can
        // paint outside the screen however large it claims to be.
        var painted = RuntimeFrame.paint(
                tree, viewport, debugOverlay, terminalIo.renders(), debugStarted, services.nanoTime());
        var placement = painted.placement();
        Screen screen = painted.screen();
        Cursor caret = painted.caret();
        if (!terminalIo.renders()) {
            // A renderer-disabled session produces no output at all. The frame is still resolved,
            // because input routing reads the tree the model produced and the placement is what a
            // pointer report is hit-tested against, and the screen is not remembered because
            // nothing was painted to compare against.
            currentTree = tree;
            currentPlacement = placement;
            currentScreen = null;
            viewDirty = false;
            nextFrameAtNanos = System.nanoTime() + frameNanos;
            return;
        }
        TerminalPresenter.Transition transition = terminalIo.presenter.plan(intent);
        if (transition.bufferChanged()) {
            // The other buffer holds different cells, so there is nothing to diff against: the frame
            // is painted in full, and the terminal's cursor is somewhere else on that screen.
            currentScreen = null;
            terminalIo.cursorPresenter.contentPainted();
        }
        String output;
        String cursor = "";
        if (transition.alternateScreen()) {
            output = AnsiEncoder.diff(currentScreen, screen, terminalIo.profile);
            // The cursor comes last and inside the same synchronized update: it must land on cells the
            // frame has already painted, and a terminal that renders the update atomically never shows
            // the caret flying to its new cell. Repainting moved the terminal's cursor, so the presenter
            // re-states the caret's cell; a frame that changed nothing writes nothing at all.
            if (!output.isEmpty()) {
                terminalIo.cursorPresenter.contentPainted();
            }
            cursor = terminalIo.cursorPresenter.frame(java.util.Optional.ofNullable(caret));
        } else {
            // An inline frame addresses nothing absolutely: it repaints the live region from the row
            // the session started it on and places the caret by moving inside that region.
            output = terminalIo.inline.frame(
                    currentScreen, screen, terminalIo.profile, java.util.Optional.ofNullable(caret));
        }
        String metadata = transition.sequences();
        if (!prelude.isEmpty() || !metadata.isEmpty() || !output.isEmpty() || !cursor.isEmpty()) {
            // One write, inside one synchronized update: the durable lines, the screen switch, the
            // modes, the metadata and the cells a terminal renders atomically cannot land in two
            // different frames.
            terminalIo.writeFrame(prelude + metadata + output + cursor);
        }
        // Recorded only now: a frame whose write failed failed the run, and the session must not
        // believe it entered a mode it never announced.
        terminalIo.presenter.commit(transition);
        currentTree = tree;
        currentPlacement = placement;
        currentScreen = screen;
        viewDirty = false;
        // Match the mailbox's real wait clock, independent of application timestamps.
        nextFrameAtNanos = System.nanoTime() + frameNanos;
    }

    /**
     * Writes {@link io.github.dhoard.braxton.effect.Command.PrintLine} output on the owner thread.
     *
     * <p>Inline mode erases the live region, prints, and repaints atomically. Alternate-screen
     * mode ignores durable output. Renderer-disabled mode emits normalized plain text.</p>
     *
     * @param text the text; controls other than tabs and newlines are dropped
     */
    void print(String text) {
        if (terminalIo.stopping || terminalIo.released) return;
        if (!terminalIo.renders()) {
            String plain = DurableText.normalize(text);
            if (plain.isEmpty()) return;
            terminalIo.writeOutput(plain.endsWith("\n") ? plain : plain + "\n");
            return;
        }
        if (terminalIo.presenter.alternateScreenEntered()) return;
        synchronized (terminalIo.outputLock) {
            String prelude = terminalIo.inline.print(text);
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
        if (terminalIo.stopping || terminalIo.terminalClosed) {
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
                result = childProcesses.runChild(request);
            } catch (ProcessHandoff.HandoffInterrupted cancellation) {
                // The caller's own interruption ends the run. The child has already been terminated
                // and the terminal is taken back below, so the interruption is re-asserted only once
                // nothing else here needs to run.
                interruptedHere = true;
                result = new ProcessResult.Rejected("the session was interrupted");
            } finally {
                reacquireAfterHandoff();
            }
            if (terminalIo.stopping || terminalIo.terminalClosed) {
                return new ProcessResult.Rejected("the session is closing");
            }
            return result;
        } finally {
            handingOff.set(false);
            if (interruptedHere) Thread.currentThread().interrupt();
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
        if (terminalIo.stopping || terminalIo.terminalClosed) {
            return new SuspendResult.Refused("the session is closing");
        }
        if (!terminalIo.terminal.foreground()) {
            return new SuspendResult.Refused("this session does not own the terminal's foreground");
        }
        if (!handingOff.compareAndSet(false, true)) {
            return new SuspendResult.Refused("another handoff is already running");
        }
        try {
            releaseForHandoff();
            SuspendResult result;
            try {
                terminalIo.terminal.suspend();
                result = new SuspendResult.Resumed();
            } catch (IOException failure) {
                result = new SuspendResult.Failed(failure);
            } finally {
                reacquireAfterHandoff();
            }
            if (terminalIo.stopping || terminalIo.terminalClosed) {
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
        terminalIo.released = true;
        stopInputReader();
        if (subscriptions != null) {
            // Interval periods keep running - they are monotonic durations, not wall-clock
            // deadlines - but the ticks that elapse while somebody else owns the terminal are
            // coalesced, so the application sees one tick on resume instead of a burst.
            subscriptions.pause();
        }
        terminalIo.restoreModes();
        try {
            terminalIo.terminal.release();
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
        if (terminalIo.stopping || terminalIo.terminalClosed) {
            // Shutdown owns the terminal now; it closes it from whatever state the handoff left.
            return;
        }
        try {
            terminalIo.terminal.reacquire();
        } catch (IOException | RuntimeException failure) {
            if (terminalIo.stopping || terminalIo.terminalClosed) {
                return;
            }
            throw new TerminalRecoveryException("cannot take the terminal back after a handoff", failure);
        }
        terminalIo.released = false;
        try {
            terminalIo.enterScreen(false);
            terminalIo.registerSuspendSequences();
        } catch (IOException | RuntimeException failure) {
            throw new TerminalRecoveryException("cannot restore the session's terminal modes", failure);
        }
        // The frame on the other buffer is gone and the size may have changed while somebody else
        // owned the terminal, so the next frame measures again and paints everything.
        currentScreen = null;
        if (terminalIo.cursorPresenter != null) {
            terminalIo.cursorPresenter.contentPainted();
        }
        applyResize();
        if (subscriptions != null) {
            subscriptions.resume();
        }
        viewDirty = true;
        inputThread = startInputThread();
    }

    private void stopInputReader() {
        Thread reader = inputThread;
        inputThread = null;
        RuntimeInput.stopInputReader(reader, services);
    }

    // ── threads ──────────────────────────────────────────────────────────────

    private Thread startInputThread() {
        return RuntimeInput.startInputThread(terminalIo, options, mailbox);
    }

    private boolean offer(Mailbox.Envelope envelope) {
        return RuntimeInput.offer(mailbox, envelope);
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    private ExitResult cleanup(ExitResult result) {
        // Every step is contained: a failing terminal restore is a cleanup failure like a failing
        // source or effect cancellation, not the end of cleanup. Stopping here would leave the
        // mailbox open (its producers, including the input reader, keep retrying an owner that has
        // already gone away) and skip the thread joins.
        terminalIo.stopping = true;
        childProcesses.terminateActiveChild();
        terminalIo.boundTerminalOutput();
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
        terminalIo.closeTerminal(cleanupFailures);
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
        RuntimeInput.join(inputThread);
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

    /**
     * Exposed for tests: the committed model, never {@code null} after successful init.
     *
     * @return the current immutable model
     */
    public M model() {
        return model;
    }

    /**
     * Exposed for tests: the last built tree; a pending model may await presentation.
     *
     * @return exposed for tests: the last built tree; a pending model may await presentation
     */
    public Element<Msg> view() {
        return currentTree;
    }

    /**
     * Exposed for tests: the last presented screen, if any.
     *
     * @return exposed for tests: the last presented screen, if any
     */
    public Screen screen() {
        return currentScreen;
    }
}
