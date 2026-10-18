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

import java.time.Duration;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.terminal.TerminalOptions;

/**
 * Runtime limits and services for one application run.
 *
 * <p>Defaults are the documented numbers: a 4,096-entry mailbox, 64 concurrent one-shot effects
 * (tasks and delays together), 64 ongoing sources, and a two-second shutdown grace period. They are
 * configuration, not hidden constants.</p>
 */
public record RuntimeOptions(
        Duration shutdownGrace,
        int mailboxCapacity,
        int maxConcurrentEffects,
        int maxSubscriptions,
        ClipboardConfig clipboard,
        TerminalOptions terminal,
        RuntimeEnvironment environment) {

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
        clipboard = clipboard == null ? ClipboardConfig.defaults() : clipboard;
        terminal = terminal == null ? TerminalOptions.DEFAULT : terminal;
        environment = environment == null ? RuntimeEnvironment.system() : environment;
    }

    public static RuntimeOptions defaults() {
        return new RuntimeOptions(DEFAULT_SHUTDOWN_GRACE, DEFAULT_MAILBOX_CAPACITY,
                DEFAULT_MAX_EFFECTS, DEFAULT_MAX_SUBSCRIPTIONS, ClipboardConfig.defaults(),
                TerminalOptions.DEFAULT, RuntimeEnvironment.system());
    }

    public RuntimeOptions withEnvironment(RuntimeEnvironment value) {
        return new RuntimeOptions(shutdownGrace, mailboxCapacity, maxConcurrentEffects,
                maxSubscriptions, clipboard, terminal, value);
    }

    public RuntimeOptions withTerminal(TerminalOptions value) {
        return new RuntimeOptions(shutdownGrace, mailboxCapacity, maxConcurrentEffects,
                maxSubscriptions, clipboard, value, environment);
    }

    public RuntimeOptions withClipboard(ClipboardConfig value) {
        return new RuntimeOptions(shutdownGrace, mailboxCapacity, maxConcurrentEffects,
                maxSubscriptions, value, terminal, environment);
    }

    public RuntimeOptions withLimits(int mailbox, int effects, int subscriptions) {
        return new RuntimeOptions(shutdownGrace, mailbox, effects, subscriptions, clipboard,
                terminal, environment);
    }
}
