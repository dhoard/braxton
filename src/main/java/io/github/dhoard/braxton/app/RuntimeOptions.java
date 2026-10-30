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

package io.github.dhoard.braxton.app;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.input.InputOptions;

import java.time.Duration;

/**
 * Immutable limits and input/presentation choices for one application run.
 *
 * <p>Defaults are the documented numbers, not hidden constants: a 4,096-entry mailbox, 64
 * concurrent one-shot effects (tasks and delays together), 64 ongoing sources, 4,096 active
 * components including the root, a child depth of 64, 1,024 local dispatches per turn, and a
 * two-second shutdown grace period. The component and dispatch limits bound a hostile or
 * accidentally cyclic hierarchy instead of allowing it to allocate or spin without end.</p>
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
        InputOptions input) {

    public static final int DEFAULT_MAX_COMPONENTS = 4_096;
    public static final int DEFAULT_MAX_COMPONENT_DEPTH = 64;
    public static final int DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN = 1_024;
    public static final int DEFAULT_MAILBOX_CAPACITY = 4_096;
    public static final int DEFAULT_MAX_EFFECTS = 64;
    public static final int DEFAULT_MAX_SUBSCRIPTIONS = 64;
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(2);

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
            throw new IllegalArgumentException(
                    "shutdown grace must fit in nanoseconds: " + shutdownGrace, overflow);
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
        clipboard = clipboard == null ? ClipboardConfig.defaults() : clipboard;
        terminal = terminal == null ? TerminalOptions.DEFAULT : terminal;
        input = input == null ? InputOptions.DEFAULT : input;
    }

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
                InputOptions.DEFAULT);
    }

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
                input);
    }

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
                value);
    }

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
                input);
    }

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
                input);
    }

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
                input);
    }
}
