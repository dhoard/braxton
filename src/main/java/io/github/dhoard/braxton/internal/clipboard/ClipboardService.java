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

package io.github.dhoard.braxton.internal.clipboard;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.clipboard.TmuxStrategy;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-session clipboard behaviour: protocol encoding, transport selection, deduplication, and the
 * in-process fallback buffer.
 *
 * <p>The service never writes to a stream itself. The runtime hands it the session's output owner,
 * which keeps a clipboard sequence from splitting a rendered frame. Nothing here claims that the
 * operating-system clipboard accepted a payload.</p>
 */
public final class ClipboardService {

    private final ClipboardConfig config;
    private final TerminalEnvironment environment;
    private final Consumer<String> writer;
    private final ClipboardBuffer buffer;
    /**
     * The last sequence written for each selection.
     *
     * <p>One slot per selection, because the two are independent destinations: copying the same text to
     * the primary selection after copying it to the system one is a new write, and saying otherwise
     * would leave the primary selection stale.</p>
     */
    private final Map<ClipboardSelection, String> lastSequences = new EnumMap<>(ClipboardSelection.class);

    public ClipboardService(ClipboardConfig config, TerminalEnvironment environment, Consumer<String> writer) {
        this.config = config == null ? ClipboardConfig.defaults() : config;
        this.environment = environment == null ? TerminalEnvironment.system() : environment;
        this.writer = writer == null ? sequence -> {} : writer;
        this.buffer = new ClipboardBuffer(this.config.maxInternalChars());
    }

    /** Copies text to the system clipboard, always storing it internally. */
    public synchronized ClipboardResult copy(String text) {
        return copy(ClipboardSelection.SYSTEM, text);
    }

    /**
     * Copies text to one selection, always storing it internally, and writes at most one OSC 52
     * sequence.
     *
     * <p>The in-process buffer holds the text whatever the transport does, so a terminal that ignores
     * the sequence loses nothing the application can still paste. Which selection the escape sequence
     * names is the only difference between a system copy and a primary copy: the encoding, the
     * payload limit, the tmux strategy and the deduplication are shared.</p>
     *
     * @param selection the selection to write; a missing selection copies to the system clipboard
     * @param text the text to copy; null is treated as empty
     */
    public synchronized ClipboardResult copy(ClipboardSelection selection, String text) {
        ClipboardSelection target = selection == null ? ClipboardSelection.SYSTEM : selection;
        String plain = PasteSanitizer.plainText(text);
        String stored = buffer.set(plain);
        boolean internalTruncated = stored.length() < plain.length();
        int storedBytes = stored.getBytes(StandardCharsets.UTF_8).length;

        if (plain.isEmpty()) {
            lastSequences.remove(target);
            return new ClipboardResult("", 0, false, true, false, false, ClipboardPath.NONE);
        }

        ClipboardPath path = resolvePath();
        if (path == ClipboardPath.INTERNAL_ONLY) {
            lastSequences.remove(target);
            return new ClipboardResult(stored, storedBytes, internalTruncated, true, false, false, path);
        }

        Osc52.Encoded encoded = Osc52.encode(target, plain, config.maxPayloadBytes());
        if (encoded.text().isEmpty()) {
            // The payload limit left nothing to write, and an empty OSC 52 payload clears the
            // clipboard. A copy that can carry no text must not clear what the user already had.
            lastSequences.remove(target);
            return new ClipboardResult(stored, storedBytes, true, true, false, false, ClipboardPath.INTERNAL_ONLY);
        }
        String sequence = path == ClipboardPath.TMUX_PASSTHROUGH
                ? TmuxClipboard.passthrough(encoded.sequence())
                : encoded.sequence();
        boolean truncated = internalTruncated || encoded.truncated();

        if (sequence.equals(lastSequences.get(target))) {
            return new ClipboardResult(stored, storedBytes, truncated, true, false, true, path);
        }

        try {
            writer.accept(sequence);
        } catch (RuntimeException failure) {
            lastSequences.remove(target);
            return new ClipboardResult(stored, storedBytes, truncated, true, false, false, ClipboardPath.INTERNAL_ONLY);
        }
        lastSequences.put(target, sequence);
        return new ClipboardResult(stored, storedBytes, truncated, true, true, false, path);
    }

    /** The text currently held by the in-process buffer. */
    public String internalText() {
        return buffer.get();
    }

    /**
     * The transport a terminal read would use, or null when the session cannot ask.
     *
     * <p>Reading is the same question as writing, asked backwards, so it is available exactly when an
     * escape sequence may be written at all: a session with OSC 52 emission off, a strategy that never
     * writes, or a terminal that cannot interpret escapes has nothing to ask with. Reads also have
     * their own switch, because a deployment may allow writing the clipboard without allowing an
     * application to ask what is in it.</p>
     *
     * @return the transport a read would be written on, or null when reads are unavailable
     */
    public ClipboardPath readPath() {
        if (!config.terminalReadsEnabled()) {
            return null;
        }
        ClipboardPath path = resolvePath();
        return path == ClipboardPath.INTERNAL_ONLY ? null : path;
    }

    private ClipboardPath resolvePath() {
        if (!config.osc52Enabled() || !environment.supportsOsc52()) {
            return ClipboardPath.INTERNAL_ONLY;
        }
        boolean tmux = environment.inTmux();
        return switch (config.tmuxStrategy()) {
            case OFF -> ClipboardPath.INTERNAL_ONLY;
            case DIRECT -> ClipboardPath.OSC52_DIRECT;
            case TMUX_NATIVE -> tmux ? ClipboardPath.TMUX_NATIVE : ClipboardPath.OSC52_DIRECT;
            case TMUX_PASSTHROUGH -> tmux ? ClipboardPath.TMUX_PASSTHROUGH : ClipboardPath.OSC52_DIRECT;
            case AUTO -> tmux ? ClipboardPath.TMUX_NATIVE : ClipboardPath.OSC52_DIRECT;
        };
    }

    /** Strategy helper retained for callers that need to name the selected tmux mode. */
    public TmuxStrategy tmuxStrategy() {
        return config.tmuxStrategy();
    }
}
