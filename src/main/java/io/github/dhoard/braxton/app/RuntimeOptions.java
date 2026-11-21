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

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.style.ColorProfile;
import java.time.Duration;

/**
 * Immutable limits, control and input/presentation choices for one application run.
 *
 * <p>Defaults are the documented numbers, not hidden constants: a 4,096-entry mailbox, 64
 * concurrent one-shot effects (tasks and delays together), 64 ongoing sources, 4,096 active
 * components including the root, a child depth of 64, 1,024 local dispatches per turn, a
 * {@link ColorProfile#AUTO negotiated} color profile, a 60 frames-per-second presentation ceiling,
 * no message filter, failures reported through {@link ExitResult}, and a two-second shutdown grace
 * period. Producers outside the runtime thread block while the mailbox
 * holds {@code mailboxCapacity} entries. The runtime thread's own deliveries are counted
 * separately against the same capacity, because that thread cannot wait for itself. The component and dispatch limits bound a hostile or
 * accidentally cyclic hierarchy instead of allowing it to allocate or spin without end.</p>
 *
 * @param shutdownGrace how long shutdown waits for running effect tasks
 * @param mailboxCapacity the producer envelope bound and the owner delivery bound
 * @param maxConcurrentEffects the bound on tasks and delays that have not completed
 * @param maxSubscriptions the bound on live ongoing sources
 * @param maxComponents the bound on active components, including the root
 * @param maxComponentDepth the bound on child nesting depth
 * @param maxLocalDispatchesPerTurn the bound on owner-local messages in one turn
 * @param clipboard the clipboard policy for the session
 * @param terminal the presentation modes requested from the terminal
 * @param input the input decoding limits
 * @param colorProfile the color profile to render with, or {@link ColorProfile#AUTO} to negotiate
 *     one from the terminal and the environment
 * @param messageFilter the optional filter every delivered message passes through, or null for none
 * @param fps the frames-per-second ceiling presentations are paced to, between {@value #MIN_FPS} and
 *     {@value #MAX_FPS}
 * @param propagateFailure whether a failed run throws its failure after cleanup instead of returning
 *     it in the {@link ExitResult}
 * @param installShutdownHook whether the run installs the JVM shutdown hook that restores the
 *     terminal; false only for a host that owns restoration itself
 */
public record RuntimeOptions(
        Duration shutdownGrace,
        int mailboxCapacity,
        int maxConcurrentEffects,
        int maxSubscriptions,
        int maxComponents,
        int maxComponentDepth,
        int maxLocalDispatchesPerTurn,
        ClipboardConfig clipboard,
        TerminalOptions terminal,
        InputOptions input,
        ColorProfile colorProfile,
        MessageFilter messageFilter,
        int fps,
        boolean propagateFailure,
        boolean installShutdownHook) {

    /**
     * Creates a {@code RuntimeOptions} value.
     *
     * @param shutdownGrace how long shutdown waits for running effect tasks
     * @param mailboxCapacity the producer envelope bound and the owner delivery bound
     * @param maxConcurrentEffects the bound on tasks and delays that have not completed
     * @param maxSubscriptions the bound on live ongoing sources
     * @param maxComponents the bound on active components, including the root
     * @param maxComponentDepth the bound on child nesting depth
     * @param maxLocalDispatchesPerTurn the bound on owner-local messages in one turn
     * @param clipboard the clipboard policy for the session
     * @param terminal the presentation modes requested from the terminal
     * @param input the input decoding limits
     * @param colorProfile the color profile to render with, or {@link ColorProfile#AUTO} to negotiate one from the terminal and the environment
     * @param messageFilter the optional filter every delivered message passes through, or null for none
     * @param fps the frames-per-second ceiling presentations are paced to, between {@value #MIN_FPS} and {@value #MAX_FPS}
     * @param propagateFailure whether a failed run throws its failure after cleanup instead of returning it in the {@link ExitResult}
     * @param installShutdownHook whether the run installs the JVM shutdown hook that restores the terminal; false only for a host that owns restoration itself
     */
    public RuntimeOptions {
        shutdownGrace = shutdownGrace == null ? DEFAULT_SHUTDOWN_GRACE : shutdownGrace;
        if (shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("shutdown grace must not be negative");
        }
        // The effect runner waits in nanoseconds, the scheduler's unit. A grace beyond that range
        // is rejected here instead of throwing inside shutdown, where the failure would turn a
        // clean quit into a failed run.
        try {
            shutdownGrace.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("shutdown grace must fit in nanoseconds: " + shutdownGrace, overflow);
        }
        if (mailboxCapacity < 1) {
            throw new IllegalArgumentException("mailbox capacity must be positive");
        }
        if (maxConcurrentEffects < 0 || maxSubscriptions < 0) {
            throw new IllegalArgumentException("limits must not be negative");
        }
        if (maxComponents < 1 || maxComponentDepth < 1 || maxLocalDispatchesPerTurn < 1) {
            throw new IllegalArgumentException("component and dispatch limits must be positive");
        }
        if (fps < MIN_FPS || fps > MAX_FPS) {
            // The presentation ceiling is a documented range, not a free number: below one frame a
            // second the session is not interactive, and above the maximum the pacing interval is
            // shorter than a terminal can present.
            throw new IllegalArgumentException("fps must be between " + MIN_FPS + " and " + MAX_FPS + ": " + fps);
        }
        clipboard = clipboard == null ? ClipboardConfig.defaults() : clipboard;
        terminal = terminal == null ? TerminalOptions.DEFAULT : terminal;
        input = input == null ? InputOptions.DEFAULT : input;
        colorProfile = colorProfile == null ? DEFAULT_COLOR_PROFILE : colorProfile;
    }

    /**
     * The default bound on active components, including the root: 4,096.
     */
    public static final int DEFAULT_MAX_COMPONENTS = 4_096;
    /**
     * The default bound on child nesting depth: 64.
     */
    public static final int DEFAULT_MAX_COMPONENT_DEPTH = 64;
    /**
     * The default bound on owner-local messages handled in one turn: 1,024.
     */
    public static final int DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN = 1_024;
    /**
     * The default producer envelope bound and owner delivery bound: 4,096 entries.
     */
    public static final int DEFAULT_MAILBOX_CAPACITY = 4_096;
    /**
     * The default bound on tasks and delays that have not completed: 64.
     */
    public static final int DEFAULT_MAX_EFFECTS = 64;
    /**
     * The default bound on live ongoing sources: 64.
     */
    public static final int DEFAULT_MAX_SUBSCRIPTIONS = 64;
    /**
     * The default time shutdown waits for running effect tasks: two seconds.
     */
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(2);
    /**
     * The color profile a session negotiates when the application does not choose one.
     */
    public static final ColorProfile DEFAULT_COLOR_PROFILE = ColorProfile.AUTO;

    /**
     * The slowest presentation ceiling a session accepts: one frame per second.
     */
    public static final int MIN_FPS = 1;

    /**
     * The fastest presentation ceiling a session accepts.
     */
    public static final int MAX_FPS = 240;

    /**
     * The presentation ceiling a session paces to when the application does not choose one.
     */
    public static final int DEFAULT_FPS = 60;

    /**
     * Returns the default runtime options.
     *
     * @return the default runtime options
     */
    public static RuntimeOptions defaults() {
        return new RuntimeOptions(
                DEFAULT_SHUTDOWN_GRACE,
                DEFAULT_MAILBOX_CAPACITY,
                DEFAULT_MAX_EFFECTS,
                DEFAULT_MAX_SUBSCRIPTIONS,
                DEFAULT_MAX_COMPONENTS,
                DEFAULT_MAX_COMPONENT_DEPTH,
                DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                ClipboardConfig.defaults(),
                TerminalOptions.DEFAULT,
                InputOptions.DEFAULT,
                DEFAULT_COLOR_PROFILE,
                null,
                DEFAULT_FPS,
                false,
                true);
    }

    /**
     * The same options with another color profile.
     *
     * <p>{@link ColorProfile#AUTO} negotiates; any other value is honored as given, including one the
     * terminal may not render. The runtime re-encodes every frame after a profile change, so nothing
     * painted with the previous profile survives.</p>
     *
     * @param value the profile to render with; null negotiates
     * @return the new options
     */
    public RuntimeOptions withColorProfile(ColorProfile value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                input,
                value == null ? DEFAULT_COLOR_PROFILE : value,
                messageFilter,
                fps,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * The same options with another message filter.
     *
     * @param value the filter every delivered message passes through; null for none
     * @return the new options
     * @see MessageFilter
     */
    public RuntimeOptions withMessageFilter(MessageFilter value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                input,
                colorProfile,
                value,
                fps,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * The same options with another presentation ceiling.
     *
     * <p>A session still presents input, resize and orderly-exit frames immediately; the ceiling is
     * the minimum gap between the frames application changes share, and a slower ceiling coalesces
     * more of them into one frame of the latest state.</p>
     *
     * @param value frames per second, between {@value #MIN_FPS} and {@value #MAX_FPS}
     * @return the new options
     */
    public RuntimeOptions withFps(int value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                input,
                colorProfile,
                messageFilter,
                value,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * The same options, rethrowing a failure after cleanup instead of returning it.
     *
     * <p>Cleanup has already run when the failure is thrown, so the terminal, effects and sources
     * are in the state an ordinary failed run leaves them in; only the report changes. A run started
     * through {@link Application#start} cannot throw to its caller, so it reports the same cause in
     * the {@link ProgramResult} instead.</p>
     *
     * @param value whether a failed run throws its failure instead of returning it
     * @return the new options
     */
    public RuntimeOptions withFailurePropagation(boolean value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                input,
                colorProfile,
                messageFilter,
                fps,
                value,
                installShutdownHook);
    }

    /**
     * The same options with or without the run's own shutdown hook.
     *
     * <p>The hook restores the terminal when the JVM is terminated while the session still owns it. A
     * host that owns restoration - an embedder that already restores its own terminal - turns it off
     * with this option; nothing else changes, and the ordinary exit path still restores the
     * terminal.</p>
     *
     * @param value whether the run installs the JVM shutdown hook
     * @return the new options
     */
    public RuntimeOptions withShutdownHook(boolean value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                input,
                colorProfile,
                messageFilter,
                fps,
                propagateFailure,
                value);
    }

    /**
     * Returns the same value with component limits replaced.
     *
     * @param count the maximum component count
     * @param depth the maximum component nesting depth
     * @param dispatches the maximum local messages delivered per turn
     * @return the value with the requested component limits
     */
    public RuntimeOptions withComponentLimits(int count, int depth, int dispatches) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                count,
                depth,
                dispatches,
                clipboard,
                terminal,
                input,
                colorProfile,
                messageFilter,
                fps,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * Returns the same value with input replaced.
     *
     * @param value the requested input
     * @return the value with the requested input
     */
    public RuntimeOptions withInput(InputOptions value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                value,
                colorProfile,
                messageFilter,
                fps,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * Returns the same value with terminal replaced.
     *
     * @param value the requested terminal
     * @return the value with the requested terminal
     */
    public RuntimeOptions withTerminal(TerminalOptions value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                value,
                input,
                colorProfile,
                messageFilter,
                fps,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * Returns the same value with clipboard replaced.
     *
     * @param value the requested clipboard
     * @return the value with the requested clipboard
     */
    public RuntimeOptions withClipboard(ClipboardConfig value) {
        return new RuntimeOptions(
                shutdownGrace,
                mailboxCapacity,
                maxConcurrentEffects,
                maxSubscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                value,
                terminal,
                input,
                colorProfile,
                messageFilter,
                fps,
                propagateFailure,
                installShutdownHook);
    }

    /**
     * Returns the same value with limits replaced.
     *
     * @param mailbox the runtime mailbox
     * @param effects the maximum admitted one-shot effects
     * @param subscriptions the maximum active subscriptions
     * @return the value with the requested limits
     */
    public RuntimeOptions withLimits(int mailbox, int effects, int subscriptions) {
        return new RuntimeOptions(
                shutdownGrace,
                mailbox,
                effects,
                subscriptions,
                maxComponents,
                maxComponentDepth,
                maxLocalDispatchesPerTurn,
                clipboard,
                terminal,
                input,
                colorProfile,
                messageFilter,
                fps,
                propagateFailure,
                installShutdownHook);
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
        return Math.max(1L, 1_000_000_000L / fps);
    }
}
