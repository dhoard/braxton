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

import io.github.dhoard.braxton.app.IoOptions;
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.input.KeyboardEnhancements;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.CursorPresenter;
import io.github.dhoard.braxton.internal.render.InlineRenderer;
import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import io.github.dhoard.braxton.internal.terminal.StreamTerminal;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.Capabilities;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns terminal acquisition, presentation modes, output admission, and restoration.
 */
final class RuntimeTerminal {

    private final RuntimeOptions options;
    private final RuntimeServices services;
    private final IoOptions endpoints;
    private final Terminal suppliedTerminal;
    private final Runnable shutdownRestore;
    private final Runnable onResize;

    RuntimeTerminal(
            RuntimeOptions options,
            RuntimeServices services,
            IoOptions endpoints,
            Terminal suppliedTerminal,
            Runnable shutdownRestore,
            Runnable onResize) {
        this.options = options;
        this.services = services;
        this.endpoints = endpoints;
        this.suppliedTerminal = suppliedTerminal;
        this.shutdownRestore = shutdownRestore;
        this.onResize = onResize;
    }

    static final AtomicBoolean OWNED = new AtomicBoolean();

    final Object outputLock = new Object();

    volatile boolean stopping;

    /**
     * Whether the terminal has been given back, so nothing may read or write it.
     */
    volatile boolean released;

    volatile Terminal terminal;

    boolean ownsConsole;

    boolean modesEntered;

    /**
     * Set when the session pushed enhanced-keyboard flags, so restoration pops exactly one level.
     */
    boolean keyboardPushed;

    boolean terminalClosed;

    Capabilities capabilities;

    /**
     * The profile the environment and the terminal negotiate, before any explicit choice.
     */
    ColorProfile negotiatedProfile = ColorProfile.NONE;

    /**
     * The profile the application asked for, or {@link ColorProfile#AUTO} to negotiate.
     */
    ColorProfile requestedProfile = ColorProfile.AUTO;

    /**
     * The profile frames are encoded with: the explicit one, or the negotiated one.
     */
    ColorProfile profile = ColorProfile.NONE;

    TerminalFacts facts;

    Thread restoreTask;

    long resizeEpoch;

    long resumeEpoch;

    /**
     * The exact entry sequence the native stop path must replay after SIGCONT.
     */
    String screenEntry = "";

    /**
     * The cursor the session presents; created with the terminal's capabilities.
     */
    CursorPresenter cursorPresenter;

    /**
     * The session presentation and its applied-mode ledger; created with the capabilities.
     */
    TerminalPresenter presenter;

    /**
     * The managed live region of a non-alternate-screen session; created with the caret presenter.
     */
    InlineRenderer inline;

    void openTerminal() throws IOException {
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
        // from an untouched cursor: nothing shown, no shape, no color.
        cursorPresenter = new CursorPresenter(profile.color());
        inline = new InlineRenderer(cursorPresenter);
        // The session modes the options ask for are the defaults every frame inherits, and the ledger
        // the presenter keeps is what the exit path gives back.
        presenter = new TerminalPresenter(options.terminal(), capabilities, profile.color());
        terminal.enterRawMode();
        resizeEpoch = terminal.resizeEpoch();
        terminal.onResize(onResize);
        enterScreen(true);
        registerSuspendSequences();
    }

    /**
     * Registers the bytes an external job-control stop writes while Java cannot run safely.
     */
    void registerSuspendSequences() throws IOException {
        if (!renders() || terminal == null) return;
        StringBuilder restore = new StringBuilder();
        if (options.terminal().mouseMode() != MouseMode.NONE) {
            restore.append(AnsiEncoder.disableMouse(options.terminal().mouseMode()));
        }
        if (options.terminal().bracketedPaste()) restore.append(AnsiEncoder.DISABLE_BRACKETED_PASTE);
        if (options.terminal().focusReporting()) restore.append(AnsiEncoder.DISABLE_FOCUS_REPORTING);
        restore.append(AnsiEncoder.RESET).append(AnsiEncoder.SHOW_CURSOR);
        if (presenter != null && presenter.alternateScreenEntered()) {
            restore.append(AnsiEncoder.ALT_SCREEN_OFF);
        }
        terminal.registerSuspendBytes(restore.toString(), screenEntry);
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
    void enterScreen(boolean opening) {
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
            screenEntry = entry.toString();
            writeOutput(screenEntry);
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
    boolean renders() {
        return endpoints.renderer();
    }

    /**
     * One environment value, from the session's overrides first and the process second.
     *
     * @param name the variable's name
     * @return the value, or null when neither defines it
     */
    String environment(String name) {
        return endpoints.environment(name);
    }

    /**
     * The environment this session's clipboard layer inspects, with its overrides applied.
     */
    Map<String, String> environmentMap() {
        Map<String, String> overrides = endpoints.environment();
        if (overrides.isEmpty()) return System.getenv();
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        merged.putAll(overrides);
        return merged;
    }

    Size terminalSize(Viewport viewport) {
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

    void writeOutput(String data) {
        synchronized (outputLock) {
            if (stopping) throw new IllegalStateException("runtime is stopping");
            // A released terminal belongs to a child process or to the shell: a frame written now
            // would land on top of whatever that process is drawing.
            if (released) throw new IllegalStateException("terminal is released for a handoff");
            flushOutput(data);
        }
    }

    /**
     * Present one frame atomically where supported, with a single flush after closing the update.
     */
    void writeFrame(String data) {
        synchronized (outputLock) {
            if (stopping) throw new IllegalStateException("runtime is stopping");
            if (released) throw new IllegalStateException("terminal is released for a handoff");
            writeSynchronized(data);
        }
    }

    void writeSynchronized(String data) {
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

    /**
     * Always end and flush, preserving the primary failure if either cleanup step fails.
     */
    void closeSynchronizedOutput(Throwable primary) {
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

    /**
     * Called only with outputLock held, including the final restoration operation.
     */
    void flushOutput(String data) {
        var writer = terminal.writer();
        writer.write(data);
        writer.flush();
        if (writer.checkError()) throw outputFailed();
    }

    /**
     * The writer hides its I/O exception, so the terminal's recorded one becomes the cause.
     */
    IllegalStateException outputFailed() {
        return new IllegalStateException("terminal output failed", terminal.outputError());
    }

    void shutdownClose() {
        try {
            shutdownRestore.run();
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

    void boundTerminalOutput() {
        Terminal current = terminal;
        if (current == null) return;
        try {
            current.boundOutput();
        } catch (RuntimeException failure) {
            services.diagnostics().accept("terminal output bound failed: " + failure);
        }
    }

    void restoreModes() {
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
                // Title, color defaults, progress and status: independent of the screen buffer, so
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

    /**
     * The profile frames are encoded with: the caller's explicit choice, or the negotiated one.
     *
     * @param requested what the application asked for
     * @return the profile in force
     */
    ColorProfile resolveProfile(ColorProfile requested) {
        return requested == null || requested == ColorProfile.AUTO ? negotiatedProfile : requested;
    }

    void closeTerminal(List<Throwable> cleanupFailures) {
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

    /**
     * Rethrows a cleanup failure without changing its type: an existing runtime exception or error
     * keeps its identity, and any other throwable is wrapped.
     */
    static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }
}
