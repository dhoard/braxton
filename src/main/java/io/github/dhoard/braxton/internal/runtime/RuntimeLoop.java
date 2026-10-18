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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.InitContext;
import io.github.dhoard.braxton.app.Program;
import io.github.dhoard.braxton.app.RuntimeEnvironment;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.Transition;
import io.github.dhoard.braxton.effect.Command;
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
import io.github.dhoard.braxton.terminal.TerminalBackend;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.view.View;

/**
 * The single runtime owner.
 *
 * <p>It opens one terminal session, initializes the program, then serializes everything that
 * touches the model: routing, {@code update}, command interpretation, subscription reconciliation,
 * layout, and output. Input, timers, tasks, and sources only enqueue immutable envelopes. Rendering
 * happens after a committed change or a resize, so an idle application performs no work.</p>
 *
 * <p>The owner is the caller's thread; the two reader threads (input and resize) are virtual
 * threads. Both block on streams or sleeps, which is exactly what a virtual thread handles without
 * holding a carrier thread.</p>
 */
public final class RuntimeLoop<M, Msg> {

    private static final long POLL_MILLIS = 50L;
    private static final long RESIZE_POLL_MILLIS = 100L;

    private final Program<M, Msg> program;
    private final RuntimeOptions options;
    private final TerminalBackend backend;

    private final LayoutEngine engine = new LayoutEngine();
    private final List<Throwable> cleanupFailures = new ArrayList<>();

    private TerminalSession session;
    private Mailbox mailbox;
    private EffectRunner<Msg> effects;
    private SubscriptionManager<Msg> subscriptions;
    private ClipboardService clipboard;

    private M model;
    private View<Msg> currentView;
    private Screen currentScreen;
    private Viewport viewport = Viewport.EMPTY;
    private boolean viewDirty = true;
    private boolean failed;

    private Thread inputThread;
    private Thread resizeThread;

    public RuntimeLoop(Program<M, Msg> program, RuntimeOptions options, TerminalBackend backend) {
        this.program = program;
        this.options = options == null ? RuntimeOptions.defaults() : options;
        this.backend = backend;
    }

    public ExitResult run() {
        ExitResult result;
        try {
            session = backend.open(new Size(24, 80), options.terminal());
            Size size = session.size();
            viewport = new Viewport(size.rows(), size.cols());
            mailbox = new Mailbox(options.mailboxCapacity());
            RuntimeEnvironment environment = options.environment();
            clipboard = new ClipboardService(options.clipboard(), TerminalEnvironment.system(),
                    session::write);
            effects = new EffectRunner<>(mailbox, environment, clipboard,
                    options.maxConcurrentEffects());
            effects.bindOwner(Thread.currentThread());
            subscriptions = new SubscriptionManager<>(mailbox, environment.timers(),
                    options.maxSubscriptions(), environment.diagnostics());

            inputThread = startInputThread();
            resizeThread = startResizeThread();

            InitContext context = new InitContext(viewport, session.capabilities(),
                    environment.nanoTime().getAsLong(), systemProperties());
            commit(program.init(context));
            present();
            result = loop();
        } catch (Throwable failure) {
            failed = true;
            result = ExitResult.failure(failure, List.of());
        }
        return cleanup(result);
    }

    // ── loop ─────────────────────────────────────────────────────────────────

    private ExitResult loop() throws InterruptedException {
        while (true) {
            if (effects.quitRequested()) {
                return ExitResult.quit(effects.quitStatus());
            }
            Mailbox.Envelope envelope = mailbox.poll(POLL_MILLIS);
            if (envelope == null) {
                if (mailbox.isClosed()) {
                    return ExitResult.inputClosed();
                }
                // Defence in depth: if the reader ever dies without reporting (an Error, a native
                // fault), shut down instead of hanging with no way to receive Ctrl+C.
                if (!inputThread.isAlive() && mailbox.size() == 0) {
                    return ExitResult.failure(
                            new IllegalStateException("input reader stopped unexpectedly"), List.of());
                }
                continue;
            }
            if (envelope instanceof Mailbox.Close close) {
                return close.result();
            }
            if (envelope instanceof Mailbox.AppMessage message) {
                applyMessage(message);
            } else if (envelope instanceof Mailbox.Input input) {
                applyInput(input);
            } else if (envelope instanceof Mailbox.Resize resize) {
                applyResize(resize.size());
            }
            if (viewDirty) {
                present();
            }
            if (effects.quitRequested()) {
                return ExitResult.quit(effects.quitStatus());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyMessage(Mailbox.AppMessage message) {
        Msg value = (Msg) message.payload();
        Transition<M, Msg> transition = program.update(model, value);
        commit(transition);
    }

    private void applyInput(Mailbox.Input input) {
        InputEvent event = stamp(input.event());
        Optional<Msg> routed = InputRouter.route(currentView, event);
        routed.ifPresent(message -> {
            Transition<M, Msg> transition = program.update(model, message);
            commit(transition);
        });
    }

    private InputEvent stamp(InputEvent event) {
        if (event instanceof InputEvent.KeyInput key && key.capturedAtNanos() == 0L) {
            return key.stamped(options.environment().nanoTime().getAsLong());
        }
        return event;
    }

    private void applyResize(Size size) {
        Size current = session.size();
        Size effective = current.rows() > 0 && current.cols() > 0 ? current : size;
        viewport = new Viewport(effective.rows(), effective.cols());
        subscriptions.notifyResize(effective);
        viewDirty = true;
    }

    private void commit(Transition<M, Msg> transition) {
        boolean changed = transition.model() != model;
        model = transition.model();
        subscriptions.reconcile(program.subscriptions(model));
        effects.interpret(transition.command());
        if (changed || viewDirty) {
            viewDirty = true;
        }
    }

    private void present() {
        View<Msg> view = program.view(model, viewport);
        LayoutEngine.Layout layout = engine.layout(view, viewport.bounds());
        Screen screen = layout.screen();
        String output = AnsiEncoder.diff(currentScreen, screen);
        if (!output.isEmpty()) {
            session.write(output);
        }
        currentView = view;
        currentScreen = screen;
        viewDirty = false;
    }

    // ── threads ──────────────────────────────────────────────────────────────

    private Thread startInputThread() {
        return Thread.ofVirtual().name("braxton-input").start(() -> {
            InputDecoder decoder = new InputDecoder(session.input());
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Optional<InputEvent> event = decoder.read();
                    if (event.isEmpty()) {
                        offerControl(new Mailbox.Close(ExitResult.inputClosed()));
                        return;
                    }
                    if (!mailbox.enqueue(new Mailbox.Input(event.get()), 100L)) {
                        return;
                    }
                } catch (IOException closed) {
                    offerControl(new Mailbox.Close(ExitResult.failure(closed, List.of())));
                    return;
                } catch (RuntimeException failure) {
                    // Never let the reader die silently: an uncaught exception would leave the
                    // owner polling an empty mailbox forever (a hung application that cannot even
                    // process Ctrl+C) and print a stack trace over the alternate screen.
                    offerControl(new Mailbox.Close(ExitResult.failure(failure, List.of())));
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private Thread startResizeThread() {
        return Thread.ofVirtual().name("braxton-resize").start(() -> {
            Size last = session.size();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(RESIZE_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Size current = session.size();
                    if (!current.equals(last)) {
                        last = current;
                        if (!mailbox.enqueue(new Mailbox.Resize(current), 100L)) {
                            return;
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException failure) {
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
        try {
            mailbox.enqueue(envelope, 100L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    private ExitResult cleanup(ExitResult result) {
        if (session != null) {
            session.close();
        }
        if (mailbox != null) {
            mailbox.close();
        }
        if (subscriptions != null) {
            try {
                subscriptions.closeAll();
            } catch (RuntimeException failure) {
                cleanupFailures.add(failure);
            }
        }
        if (effects != null) {
            try {
                effects.cancelAll();
                int remaining = effects.awaitIdle(options.shutdownGrace());
                if (remaining > 0) {
                    options.environment().diagnostics().accept(
                            "shutdown: " + remaining + " effect task(s) still running");
                }
            } catch (RuntimeException failure) {
                cleanupFailures.add(failure);
            }
        }
        if (session != null) {
            session.close();
        }
        join(inputThread);
        join(resizeThread);
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

    private static Map<String, String> systemProperties() {
        Properties properties = System.getProperties();
        Map<String, String> copy = new java.util.LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name);
            if (value != null) {
                copy.put(name, value);
            }
        }
        return copy;
    }

    /** Exposed for tests: the command type currently held, never {@code null} after init. */
    public M model() {
        return model;
    }

    /** Exposed for tests: the last committed view, if any. */
    public View<Msg> view() {
        return currentView;
    }

    /** Exposed for tests: the last presented screen, if any. */
    public Screen screen() {
        return currentScreen;
    }
}
