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

package io.github.dhoard.braxton.internal.clipboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The terminal/tmux facts the clipboard subsystem is allowed to rely on.
 *
 * <p>Only environment information is inspected: no terminal query, process, or tmux configuration
 * is read. In particular, the presence of {@code TMUX} proves that the process runs under tmux,
 * not that tmux will forward OSC 52. Callers who need passthrough must opt in explicitly.</p>
 */
public final class TerminalEnvironment {

    private final Map<String, String> variables;

    /** Creates an environment view, defensively copying and sanitizing the supplied variables. */
    public TerminalEnvironment(Map<String, String> variables) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.variables = Collections.unmodifiableMap(copy);
    }

    /** Reads the current process environment. */
    public static TerminalEnvironment system() {
        return new TerminalEnvironment(System.getenv());
    }

    /** Returns an immutable view of the inspected variables. */
    public Map<String, String> variables() {
        return variables;
    }

    /** The {@code TERM} value, or an empty string. */
    public String term() {
        return value("TERM");
    }

    /** The {@code TERM_PROGRAM} value, or an empty string. */
    public String termProgram() {
        return value("TERM_PROGRAM");
    }

    /**
     * Whether the process runs under tmux according to {@code TMUX}.
     *
     * <p>Nested tmux sessions overwrite {@code TMUX} with the innermost server, so depth cannot be
     * derived reliably from the environment. The clipboard layer therefore never double-wraps a
     * passthrough sequence and {@link TmuxStrategy#AUTO} never uses passthrough.</p>
     */
    public boolean inTmux() {
        return !value("TMUX").isBlank();
    }

    /** Whether {@code TERM} identifies a terminal that cannot display or interpret escapes. */
    public boolean isDumbTerminal() {
        return "dumb".equalsIgnoreCase(term().strip());
    }

    /**
     * Whether writing OSC 52 is worthwhile. A dumb terminal is the only conservative
     * auto-detected unsupported case; terminals that silently ignore OSC 52 still retain the
     * in-process clipboard fallback.
     */
    public boolean supportsOsc52() {
        return !isDumbTerminal();
    }

    private String value(String name) {
        String value = variables.get(name);
        return value == null ? "" : value;
    }
}
