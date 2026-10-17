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

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.clipboard.TmuxStrategy;

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
    private String lastSequence = "";

    public ClipboardService(ClipboardConfig config, TerminalEnvironment environment,
            Consumer<String> writer) {
        this.config = config == null ? ClipboardConfig.defaults() : config;
        this.environment = environment == null ? TerminalEnvironment.system() : environment;
        this.writer = writer == null ? sequence -> { } : writer;
        this.buffer = new ClipboardBuffer(this.config.maxInternalChars());
    }

    /** Copies text, always storing it internally, and writes at most one OSC 52 sequence. */
    public synchronized ClipboardResult copy(String text) {
        String plain = PasteSanitizer.plainText(text);
        String stored = buffer.set(plain);
        boolean internalTruncated = stored.length() < plain.length();
        int storedBytes = stored.getBytes(StandardCharsets.UTF_8).length;

        if (plain.isEmpty()) {
            lastSequence = "";
            return new ClipboardResult("", 0, false, true, false, false, ClipboardPath.NONE);
        }

        ClipboardPath path = resolvePath();
        if (path == ClipboardPath.INTERNAL_ONLY) {
            lastSequence = "";
            return new ClipboardResult(stored, storedBytes, internalTruncated, true, false, false, path);
        }

        Osc52.Encoded encoded = Osc52.encode(plain, config.maxPayloadBytes());
        String sequence = path == ClipboardPath.TMUX_PASSTHROUGH
                ? TmuxClipboard.passthrough(encoded.sequence())
                : encoded.sequence();
        boolean truncated = internalTruncated || encoded.truncated();

        if (sequence.equals(lastSequence)) {
            return new ClipboardResult(stored, storedBytes, truncated, true, false, true, path);
        }

        try {
            writer.accept(sequence);
        } catch (RuntimeException failure) {
            lastSequence = "";
            return new ClipboardResult(stored, storedBytes, truncated, true, false, false,
                    ClipboardPath.INTERNAL_ONLY);
        }
        lastSequence = sequence;
        return new ClipboardResult(stored, storedBytes, truncated, true, true, false, path);
    }

    /** The text currently held by the in-process buffer. */
    public String internalText() {
        return buffer.get();
    }

    public ClipboardConfig config() {
        return config;
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
            case TMUX_PASSTHROUGH ->
                    tmux ? ClipboardPath.TMUX_PASSTHROUGH : ClipboardPath.OSC52_DIRECT;
            case AUTO -> tmux ? ClipboardPath.TMUX_NATIVE : ClipboardPath.OSC52_DIRECT;
        };
    }

    /** The default strategy/limits from {@link ClipboardConfig#fromSystemProperties()}. */
    public static ClipboardService fromSystem(Consumer<String> writer) {
        return new ClipboardService(ClipboardConfig.fromSystemProperties(),
                TerminalEnvironment.system(), writer);
    }

    /** Strategy helper retained for callers that need to name the selected tmux mode. */
    public TmuxStrategy tmuxStrategy() {
        return config.tmuxStrategy();
    }
}
