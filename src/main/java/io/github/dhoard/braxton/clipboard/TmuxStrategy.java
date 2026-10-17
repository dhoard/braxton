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

package io.github.dhoard.braxton.clipboard;

/**
 * How OSC 52 clipboard writes are forwarded when the application may run inside tmux.
 *
 * <p>The strategy controls transport only; OSC 52 encoding and the internal clipboard fallback are
 * identical for every value.</p>
 */
public enum TmuxStrategy {
    /**
     * Use tmux's native OSC 52 forwarding when {@code TMUX} is present, otherwise write OSC 52
     * directly. This is the default and sends exactly one clipboard write.
     */
    AUTO,

    /** Always write OSC 52 directly to the terminal, even inside tmux. */
    DIRECT,

    /**
     * Write tmux-native OSC 52 when inside tmux. tmux forwards it when {@code set-clipboard} is
     * {@code on}; the sequence is not wrapped in DCS passthrough.
     */
    TMUX_NATIVE,

    /**
     * Wrap OSC 52 in a tmux DCS passthrough sequence. Requires {@code allow-passthrough on} in
     * recent tmux releases. Falls back to a direct OSC 52 write outside tmux.
     */
    TMUX_PASSTHROUGH,

    /** Never emit an escape sequence; use only the in-process clipboard buffer. */
    OFF;

    /**
     * Parses a configuration value, accepting the enum name case-insensitively.
     *
     * @return the matching strategy, or {@code fallback} when the value is unknown
     */
    public static TmuxStrategy parse(String value, TmuxStrategy fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        for (TmuxStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value.strip())) {
                return strategy;
            }
        }
        return fallback;
    }
}
