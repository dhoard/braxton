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

package io.github.dhoard.braxton.terminal;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the attached terminal was found to support, with one result per feature.
 *
 * @param color whether truecolor SGR output is enabled
 * @param alternateScreen whether the alternate screen is available
 * @param bracketedPaste whether bracketed paste is enabled
 * @param mouse whether mouse reporting is enabled
 * @param term the terminal identifier the facts came from
 * @param report the per-feature decisions and the evidence behind them
 */
public record Capabilities(
        boolean color,
        boolean alternateScreen,
        boolean bracketedPaste,
        boolean mouse,
        String term,
        CapabilityReport report) {

    /**
     * The maximum time one active probe may take; an unanswered probe falls back safely.
     */
    public static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofMillis(500);

    /**
     * Conservative facts for a terminal whose features have not been probed.
     */
    public static final Capabilities PLAIN =
            new Capabilities(false, false, false, false, "dumb", CapabilityReport.plain());

    /**
     * Creates a {@code Capabilities} value.
     *
     * @param color whether truecolor SGR output is enabled
     * @param alternateScreen whether the alternate screen is available
     * @param bracketedPaste whether bracketed paste is enabled
     * @param mouse whether mouse reporting is enabled
     * @param term the terminal identifier the facts came from
     * @param report the per-feature decisions and the evidence behind them
     */
    public Capabilities {
        term = term == null ? "" : term;
        if (report == null) throw new IllegalArgumentException("report is required");
    }

    /**
     * Explicit terminal facts, with matching evidence for each feature.
     * Native and stream implementations and MVU callers share this one capability value.
     *
     * @param alternateScreen whether the alternate screen is available
     * @param bracketedPaste whether bracketed paste is enabled
     * @param color whether truecolor SGR output is enabled
     * @param mouse whether mouse reporting is enabled
     * @param term the terminal identifier the facts came from
     * @return explicit terminal facts, with matching evidence for each feature
     */
    public static Capabilities of(
            boolean color, boolean alternateScreen, boolean bracketedPaste, boolean mouse, String term) {
        Map<Capability, CapabilityResult> results = new EnumMap<>(Capability.class);
        results.put(Capability.COLOR, decision(Capability.COLOR, color, "explicit capability value"));
        results.put(
                Capability.ALTERNATE_SCREEN,
                decision(Capability.ALTERNATE_SCREEN, alternateScreen, "explicit capability value"));
        results.put(
                Capability.BRACKETED_PASTE,
                decision(Capability.BRACKETED_PASTE, bracketedPaste, "explicit capability value"));
        results.put(Capability.MOUSE, decision(Capability.MOUSE, mouse, "explicit capability value"));
        return new Capabilities(
                color, alternateScreen, bracketedPaste, mouse, term, new CapabilityReport(term, results));
    }

    /**
     * Infers capabilities from a terminal identity without asking the terminal any questions.
     *
     * @param term the {@code TERM} value, or null for an unset terminal
     * @return the inferred capabilities
     * @throws UnsupportedTerminalException when the terminal is unset or {@code dumb}
     */
    public static Capabilities inspect(String term) throws UnsupportedTerminalException {
        return inspect(term, CapabilityProbe.NONE, DEFAULT_PROBE_TIMEOUT);
    }

    /**
     * Infers capabilities, then asks a bounded probe about every feature that cannot be inferred
     * safely. A silent, slow or malformed probe leaves that feature disabled.
     *
     * @param term the terminal identifier the facts came from
     * @param probe the terminal capability probe
     * @param timeout the maximum wait
     * @return infers capabilities, then asks a bounded probe about every feature that cannot be inferred
     * safely
     * @throws io.github.dhoard.braxton.terminal.UnsupportedTerminalException if the operation cannot complete
     */
    public static Capabilities inspect(String term, CapabilityProbe probe, Duration timeout)
            throws UnsupportedTerminalException {
        String name = term == null ? "" : term.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("dumb")) {
            throw new UnsupportedTerminalException("a cursor-addressable VT terminal is required (TERM=" + term + ")");
        }
        Duration bound = timeout == null ? DEFAULT_PROBE_TIMEOUT : timeout;
        if (bound.isNegative() || bound.isZero() || bound.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException("probe timeout must be positive and at most two seconds");
        }
        CapabilityProbe questions = probe == null ? CapabilityProbe.NONE : probe;
        boolean modern = java.util.List.of(
                        "xterm",
                        "screen",
                        "tmux",
                        "rxvt",
                        "foot",
                        "alacritty",
                        "wezterm",
                        "kitty",
                        "ghostty",
                        "st-",
                        "st.",
                        "konsole",
                        "contour")
                .stream()
                .anyMatch(name::startsWith);
        boolean truecolor = name.contains("truecolor") || name.contains("direct");
        Map<Capability, CapabilityResult> results = new EnumMap<>(Capability.class);

        results.put(Capability.COLOR, decide(Capability.COLOR, name, questions, bound, truecolor || modern, "Tc"));
        results.put(
                Capability.ALTERNATE_SCREEN,
                decide(Capability.ALTERNATE_SCREEN, name, questions, bound, modern, "?1049"));
        results.put(
                Capability.BRACKETED_PASTE,
                decide(Capability.BRACKETED_PASTE, name, questions, bound, modern, "?2004"));
        results.put(Capability.MOUSE, decide(Capability.MOUSE, name, questions, bound, modern, "?1000"));

        CapabilityReport report = new CapabilityReport(name, results);
        return new Capabilities(
                report.enabled(Capability.COLOR),
                report.enabled(Capability.ALTERNATE_SCREEN),
                report.enabled(Capability.BRACKETED_PASTE),
                report.enabled(Capability.MOUSE),
                term,
                report);
    }

    private static CapabilityResult decide(
            Capability capability,
            String term,
            CapabilityProbe probe,
            Duration timeout,
            boolean hint,
            String question) {
        if (hint) {
            return new CapabilityResult(capability, true, CapabilitySource.HINT, "TERM=" + term);
        }
        Optional<String> reply = bounded(probe, question, timeout);
        if (reply.filter(value -> !value.isBlank() && !value.equals("0") && !value.startsWith("no"))
                .isPresent()) {
            return new CapabilityResult(capability, true, CapabilitySource.PROBE, question + "=" + reply.get());
        }
        return CapabilityResult.fallback(capability, "no positive reply to " + question);
    }

    /**
     * A probe is never allowed to block startup beyond the bound, even if it ignores its timeout.
     */
    private static Optional<String> bounded(CapabilityProbe probe, String question, Duration timeout) {
        AtomicReference<Optional<String>> answer = new AtomicReference<>(Optional.empty());
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread worker = Thread.ofVirtual().name("braxton-capability-probe").unstarted(() -> {
            try {
                Optional<String> value = probe.query(question, timeout);
                if (!expired.get()) answer.set(value == null ? Optional.empty() : value);
            } catch (RuntimeException ignored) {
                answer.set(Optional.empty());
            }
        });
        worker.start();
        try {
            worker.join(timeout.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (worker.isAlive()) {
                expired.set(true);
                worker.interrupt();
            }
        }
        return expired.get() ? Optional.empty() : answer.get();
    }

    private static CapabilityResult decision(Capability capability, boolean enabled, String detail) {
        return enabled
                ? new CapabilityResult(capability, true, CapabilitySource.HINT, detail)
                : CapabilityResult.fallback(capability, detail);
    }
}
