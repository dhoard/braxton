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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalCapabilities;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.ffm.FfmTerminalProvider;
import org.jline.terminal.spi.TerminalExt;
import org.jline.utils.InfoCmp;
import org.jline.utils.ShutdownHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single runtime owner.
 *
 * <p>It opens one JLine terminal, initializes the program, then serializes everything that
 * touches the model: routing, {@code update}, command interpretation, subscription reconciliation,
 * layout, and output. Input, timers, tasks, and sources only enqueue immutable envelopes. Rendering
 * coalesces application changes with a minimum 16 ms gap between presentations. Input, resize,
 * and orderly exit flush dirty state immediately; an idle application performs no view work.
 * Nonempty frames use JLine synchronized output under the same lock as control output and
 * restoration; screen state is accepted only after bracket closure and output error checking.</p>
 *
 * <p>The owner is the caller's thread. A virtual input worker enqueues normalized events.
 * The owner samples JLine sizes after nonblocking signal notifications and on a bounded fallback
 * interval. JLine manages its own native input/signal threads.</p>
 */
public final class RuntimeLoop<M, Msg> {

    private static final long POLL_MILLIS = 50L;
    private static final long RESIZE_POLL_MILLIS = 250L;
    private static final AtomicBoolean OWNED = new AtomicBoolean();
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(16L);

    private final AtomicBoolean started = new AtomicBoolean();
    private final Component<M, Msg, Never> program;
    private final RuntimeOptions options;
    private final RuntimeServices services;
    private final Terminal suppliedTerminal;
    private final Object outputLock = new Object();
    private final AtomicBoolean resizePending = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile Terminal terminal;
    private boolean ownsConsole;
    private boolean modesEntered;
    private boolean terminalClosed;
    private TerminalCapabilities capabilities;
    private ShutdownHooks.Task restoreTask;
    private long nextResizeAt;
    private boolean interrupted;

    private final LayoutEngine engine = new LayoutEngine();
    private final List<Throwable> cleanupFailures = new ArrayList<>();

    private Mailbox mailbox;
    private EffectRunner<Msg> effects;
    private SubscriptionManager<Msg> subscriptions;
    private ClipboardService clipboard;

    private final ScopeManager scopes = new ScopeManager();
    private final java.util.ArrayDeque<Mailbox.AppMessage> localMessages =
            new java.util.ArrayDeque<>();
    private int localDispatches;
    private M model;
    private View<Msg> currentView;
    private Screen currentScreen;
    private Viewport viewport = Viewport.EMPTY;
    private boolean viewDirty = true;
    private boolean failed;
    private long nextFrameAtNanos;

    private Thread inputThread;

    public RuntimeLoop(Component<M, Msg, Never> program, RuntimeOptions options) {
        this(program, options, null, RuntimeServices.system());
    }

    /** Internal construction seam: an actual JLine terminal, never a Braxton backend abstraction. */
    public RuntimeLoop(
            Component<M, Msg, Never> program,
            RuntimeOptions options,
            Terminal terminal,
            RuntimeServices services) {
        this.program = java.util.Objects.requireNonNull(program, "program");
        this.options = options == null ? RuntimeOptions.defaults() : options;
        this.suppliedTerminal = terminal;
        this.services = java.util.Objects.requireNonNull(services, "services");
    }

    private void openTerminal() throws IOException {
        if (suppliedTerminal == null) {
            if (!OWNED.compareAndSet(false, true))
                throw new IllegalStateException("another Braxton application owns the terminal");
            ownsConsole = true;
            restoreTask = ShutdownHooks.add(this::shutdownRestore);
            if (!FfmTerminalProvider.class.getModule().isNativeAccessEnabled()) {
                throw new IllegalStateException(
                        "JLine FFM requires --enable-native-access=ALL-UNNAMED on the classpath,"
                                + " or --enable-native-access=org.jline.terminal.ffm on the module"
                                + " path");
            }
            try {
                terminal = TerminalBuilder.builder()
                        .name("braxton")
                        .system(true)
                        .devTty(false)
                        .provider("ffm")
                        .dumb(false)
                        .encoding(StandardCharsets.UTF_8)
                        .graphemeCluster(false)
                        .build();
            } catch (IOException | RuntimeException failure) {
                throw new IOException(
                        "Cannot open JLine FFM: an attached process terminal is required; verify"
                                + " the FFM dependency and native-access permission",
                        failure);
            }
            if (!(terminal instanceof TerminalExt ext)
                    || ext.getProvider() == null
                    || !"ffm".equals(ext.getProvider().name()))
                throw new IllegalStateException("JLine FFM provider required");
        } else terminal = suppliedTerminal;
        String type = terminal.getType();
        if (type == null
                || type.startsWith("dumb")
                || terminal.getStringCapability(InfoCmp.Capability.cursor_address) == null) {
            throw new IllegalStateException("a cursor-addressable VT terminal is required");
        }
        String name = type.toLowerCase(java.util.Locale.ROOT);
        boolean vt = name.contains("xterm")
                || name.contains("screen")
                || name.contains("tmux")
                || name.contains("rxvt")
                || name.contains("windows");
        Integer colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        capabilities = new TerminalCapabilities(
                colors != null && colors > 0,
                terminal.getStringCapability(InfoCmp.Capability.enter_ca_mode) != null,
                vt,
                terminal.hasMouseSupport(),
                type);
        terminal.enterRawMode();
        terminal.handle(Terminal.Signal.WINCH, ignored -> {
            resizePending.set(true);
            Mailbox current = mailbox;
            if (current != null) current.wake();
        });
        synchronized (outputLock) {
            modesEntered = true;
            StringBuilder entry = new StringBuilder();
            if (options.terminal().alternateScreen() && capabilities.alternateScreen())
                entry.append(AnsiEncoder.ALT_SCREEN_ON);
            entry.append(AnsiEncoder.HIDE_CURSOR);
            if (options.terminal().bracketedPaste() && capabilities.bracketedPaste())
                entry.append(AnsiEncoder.ENABLE_BRACKETED_PASTE);
            if (options.terminal().mouse() && capabilities.mouse())
                entry.append(AnsiEncoder.ENABLE_MOUSE);
            writeOutput(entry.toString());
        }
    }

    private Size terminalSize() {
        try {
            var size = terminal.getSize();
            if (size.getRows() > 0 && size.getColumns() > 0)
                return new Size(size.getRows(), size.getColumns());
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
            flushOutput(data);
        }
    }

    /** Present one frame atomically where supported, with a single flush after closing the update. */
    private void writeFrame(String data) {
        synchronized (outputLock) {
            if (stopping) throw new IllegalStateException("runtime is stopping");
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
            if (writer.checkError()) throw new IllegalStateException("terminal output failed");
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
        if (writer.checkError()) throw new IllegalStateException("terminal output failed");
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

    /** JLine's ordered shutdown task: close admission before restoring application modes. */
    void shutdownRestore() {
        stopping = true;
        Mailbox current = mailbox;
        if (current != null) current.close();
        restoreModes();
    }

    private void restoreModes() {
        synchronized (outputLock) {
            if (!modesEntered || terminal == null) return;
            modesEntered = false;
            StringBuilder exit = new StringBuilder();
            if (options.terminal().mouse() && capabilities.mouse())
                exit.append(AnsiEncoder.DISABLE_MOUSE);
            if (options.terminal().bracketedPaste() && capabilities.bracketedPaste())
                exit.append(AnsiEncoder.DISABLE_BRACKETED_PASTE);
            exit.append(AnsiEncoder.RESET).append(AnsiEncoder.SHOW_CURSOR);
            if (options.terminal().alternateScreen() && capabilities.alternateScreen())
                exit.append(AnsiEncoder.ALT_SCREEN_OFF);
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
            return ExitResult.failure(
                    new IllegalStateException("runtime can only run once"), List.of());
        }
        ExitResult result;
        try {
            openTerminal();
            Size size = terminalSize();
            viewport = new Viewport(size.rows(), size.cols());
            mailbox = new Mailbox(options.mailboxCapacity());
            RuntimeServices environment = services;
            clipboard = new ClipboardService(
                    options.clipboard(), TerminalEnvironment.system(), this::writeOutput);
            effects = new EffectRunner<>(
                    mailbox, environment, clipboard, options.maxConcurrentEffects());
            effects.bindOwner(Thread.currentThread());
            effects.bindScopes(scopes, this::enqueueLocal);
            subscriptions = new SubscriptionManager<>(
                    mailbox,
                    environment.timers(),
                    options.maxSubscriptions(),
                    environment.diagnostics());
            subscriptions.bindOwner(Thread.currentThread());
            subscriptions.bindScopes(scopes);

            InitContext context = new InitContext(viewport, capabilities);
            commit(
                    ComponentFailure.call(
                            ComponentPath.ROOT,
                            ComponentFailure.Phase.INIT,
                            () -> program.init(context)));
            drainLocal();
            present();
            terminal.resume();
            inputThread = startInputThread();
            result = loop();
        } catch (InterruptedException cancellation) {
            interrupted = true;
            result = ExitResult.interrupted();
        } catch (Throwable failure) {
            failed = true;
            result = ExitResult.failure(failure, List.of());
        }
        ExitResult outcome = cleanup(result);
        if (interrupted) Thread.currentThread().interrupt();
        return outcome;
    }

    // ── loop ─────────────────────────────────────────────────────────────────

    private ExitResult loop() throws InterruptedException {
        while (true) {
            if (stopping) return ExitResult.inputClosed();
            long now = System.nanoTime();
            if (resizePending.getAndSet(false) || now - nextResizeAt >= 0) {
                nextResizeAt = now + TimeUnit.MILLISECONDS.toNanos(RESIZE_POLL_MILLIS);
                Size size = terminalSize();
                if (size.rows() != viewport.rows() || size.cols() != viewport.cols())
                    applyResize();
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
                    return ExitResult.inputClosed();
                }
                // Defence in depth: if the reader ever dies without reporting (an Error, a native
                // fault), shut down instead of hanging with no way to receive Ctrl+C.
                if (!inputThread.isAlive() && mailbox.size() == 0) {
                    return ExitResult.failure(
                            new IllegalStateException("input reader stopped unexpectedly"),
                            List.of());
                }
                continue;
            }
            if (envelope instanceof Mailbox.Close close) {
                if (viewDirty && close.result().failure() == null) {
                    present();
                }
                return close.result();
            }
            localDispatches = 0;
            if (envelope instanceof Mailbox.AppMessage message) {
                applyMessage(message);
            } else if (envelope instanceof Mailbox.Input input) {
                applyInput(input);
            }
            drainLocal();
            if (viewDirty
                    && (envelope instanceof Mailbox.Input
                            || effects.quitRequested()
                            || frameReady())) {
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
        return Math.max(
                1L, Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999L)));
    }

    @SuppressWarnings("unchecked")
    private void applyMessage(Mailbox.AppMessage message) {
        if (message.origin() != null && !scopes.isCurrent(message.origin())) return;
        if (!message.valid().getAsBoolean()) return;
        Msg value = (Msg) message.payload();
        Update<M, Msg, Never> transition =
                ComponentFailure.call(
                        ComponentPath.ROOT,
                        ComponentFailure.Phase.UPDATE,
                        () -> program.update(model, value));
        commit(transition);
    }

    private void applyInput(Mailbox.Input input) {
        if (viewDirty) {
            // Bindings belong to the latest model even when its paint is deferred.
            currentView = componentView();
        }
        InputEvent event = stamp(input.event());
        Optional<Msg> routed = InputRouter.route(currentView, event);
        routed.ifPresent(
                message -> {
                    Update<M, Msg, Never> transition =
                            ComponentFailure.call(
                                    ComponentPath.ROOT,
                                    ComponentFailure.Phase.UPDATE,
                                    () -> program.update(model, message));
                    commit(transition);
                });
    }

    private InputEvent stamp(InputEvent event) {
        if (event instanceof InputEvent.KeyInput key && key.capturedAtNanos() == 0L) {
            return key.stamped(services.nanoTime().getAsLong());
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
            throw new IllegalStateException(
                    "local dispatch limit exceeded for component "
                            + (origin == null ? "root" : origin.path().toString()));
        }
        localMessages.addLast(message);
    }

    private void drainLocal() {
        while (!effects.quitRequested() && !localMessages.isEmpty())
            applyMessage(localMessages.removeFirst());
        if (effects.quitRequested()) localMessages.clear();
    }

    private View<Msg> componentView() {
        return ComponentFailure.call(
                ComponentPath.ROOT,
                ComponentFailure.Phase.VIEW,
                () -> program.view(model, new ViewContext(viewport)));
    }

    private void commit(Update<M, Msg, Never> transition) {
        if (stopping) return;
        if (!transition.outputs().isEmpty())
            throw new IllegalArgumentException("root component emitted output");
        M candidate = transition.model();
        var tree =
                ComponentFailure.call(
                        ComponentPath.ROOT,
                        ComponentFailure.Phase.CHILDREN,
                        () -> program.children(candidate));
        var nextScopes = scopes.prepare(tree, options.maxComponents(), options.maxComponentDepth());
        var sources =
                ComponentFailure.call(
                        ComponentPath.ROOT,
                        ComponentFailure.Phase.SUBSCRIPTIONS,
                        () -> program.subscriptions(candidate));
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
            io.github.dhoard.braxton.effect.Subscription<Msg> sources,
            java.util.Map<ComponentPath, Long> nextScopes) {
        ComponentPath path = ComponentPath.ROOT;
        try {
            var leaves = io.github.dhoard.braxton.effect.Subscription.leaves(sources);
            if (leaves.size() > options.maxSubscriptions())
                throw new IllegalArgumentException("too many subscriptions");
            for (var leaf : leaves) {
                path = leaf.id().path();
                if (!nextScopes.containsKey(path))
                    throw new IllegalArgumentException(
                            "subscription belongs to undeclared component " + path);
            }
        } catch (IllegalArgumentException invalid) {
            // These are framework declaration errors, not thrown component/payload diagnostics.
            throw new ComponentFailure(
                    path, ComponentFailure.Phase.SUBSCRIPTIONS, invalid.getMessage(), invalid);
        }
    }

    private void present() {
        // The frame has to fit the terminal it is written to. A resize that lands after the sample
        // which chose this viewport leaves the layout a column or more wider than the screen, and
        // every full row then wraps its tail into the row below. Re-sample here, the last point
        // before the layout, so the presented frame is always laid out for the width JLine reports
        // now.
        Size size = terminalSize();
        if (size.rows() != viewport.rows() || size.cols() != viewport.cols()) {
            applyResize();
        }
        View<Msg> view = componentView();
        LayoutEngine.Layout layout = engine.layout(view, viewport.bounds());
        Screen screen = layout.screen();
        String output = AnsiEncoder.diff(currentScreen, screen, capabilities.color());
        if (!output.isEmpty()) {
            writeFrame(output);
        }
        currentView = view;
        currentScreen = screen;
        viewDirty = false;
        // Match the mailbox's real wait clock, independent of application timestamps.
        nextFrameAtNanos = System.nanoTime() + FRAME_NANOS;
    }

    // ── threads ──────────────────────────────────────────────────────────────

    private Thread startInputThread() {
        return Thread.ofVirtual().name("braxton-input").start(() -> {
            InputDecoder decoder =
                    new InputDecoder(terminal.reader(), options.input(), () -> stopping);
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                try {
                    Optional<InputEvent> event = decoder.read();
                    if (event.isEmpty()) {
                        offerControl(new Mailbox.Close(ExitResult.inputClosed()));
                        return;
                    }
                    if (!offer(new Mailbox.Input(event.get()))) {
                        return;
                    }
                } catch (IOException closed) {
                    if (stopping) return;
                    offerControl(new Mailbox.Close(ExitResult.failure(closed, List.of())));
                    return;
                } catch (Throwable failure) {
                    if (stopping) return;
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
        if (mailbox != null) mailbox.close();
        // Let JLine's finite read expire normally: interruption skips its outstanding-poll
        // cancellation path. Stop the consumer before closing/releasing the native terminal.
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
                    services.diagnostics()
                            .accept("shutdown: " + remaining + " effect task(s) still running");
                }
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
        }
        join(inputThread);
        if (failed && result.failure() == null) {
            return ExitResult.failure(new IllegalStateException("runtime failed"), cleanupFailures);
        }
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
                ShutdownHooks.remove(restoreTask);
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
