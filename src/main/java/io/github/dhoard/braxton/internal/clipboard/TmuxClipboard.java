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

/**
 * tmux DCS passthrough encoding for terminal sequences.
 *
 * <p>The format is {@code ESC P tmux ; <sequence> ESC \} with every ESC byte inside the sequence
 * doubled. tmux unescapes one layer before sending the inner sequence to the outer terminal. The
 * wrapper is applied at most once, so nested tmux sessions cannot turn a clipboard write into an
 * unbounded or duplicated escape stream.</p>
 *
 * <p>Passthrough requires {@code allow-passthrough on} in tmux 3.3 and later (older releases used
 * {@code allow-passthrough} with different defaults). Braxton never changes tmux configuration.</p>
 */
public final class TmuxClipboard {

    private static final String PREFIX = "\u001bPtmux;";
    private static final String SUFFIX = "\u001b\\";
    private static final char ESC = '\u001b';

    private TmuxClipboard() {
    }

    /**
     * Wraps {@code sequence} for tmux passthrough.
     *
     * @return the wrapped sequence, an empty string for {@code null}/empty input, or the input
     *     unchanged when it is already a tmux passthrough sequence
     */
    public static String passthrough(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return "";
        }
        if (sequence.startsWith(PREFIX)) {
            return sequence;
        }
        StringBuilder escaped = new StringBuilder(sequence.length() + 16);
        for (int i = 0; i < sequence.length(); i++) {
            char c = sequence.charAt(i);
            if (c == ESC) {
                escaped.append(ESC);
            }
            escaped.append(c);
        }
        return PREFIX + escaped + SUFFIX;
    }

    /** Whether {@code sequence} is already a tmux passthrough payload. */
    public static boolean isPassthrough(String sequence) {
        return sequence != null && sequence.startsWith(PREFIX);
    }
}
