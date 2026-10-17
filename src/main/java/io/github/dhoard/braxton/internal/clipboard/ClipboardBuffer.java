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
 * Bounded, thread-safe in-process clipboard storage.
 *
 * <p>This buffer is the reliable fallback when a terminal does not implement OSC 52, when tmux is
 * not configured to forward clipboard writes, or when the application runs without a terminal.
 * It is also the only source for application-level paste because reading the operating-system
 * clipboard is not possible with the OSC 52 protocol alone.</p>
 */
public final class ClipboardBuffer {

    private final int maxChars;
    private String text = "";

    /** Creates a buffer bounded to {@code maxChars} UTF-16 units (at least one). */
    public ClipboardBuffer(int maxChars) {
        this.maxChars = Math.max(1, maxChars);
    }

    /**
     * Replaces the stored text, truncating at a code-point boundary when it exceeds the bound.
     *
     * @return the text actually stored
     */
    public synchronized String set(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > maxChars) {
            int end = maxChars;
            if (Character.isHighSurrogate(safe.charAt(end - 1))) {
                end--;
            }
            safe = safe.substring(0, end);
        }
        text = safe;
        return text;
    }

    /** Returns the stored text, or an empty string. */
    public synchronized String get() {
        return text;
    }

    /** Removes the stored text. */
    public synchronized void clear() {
        text = "";
    }

    /** Number of stored UTF-16 units. */
    public synchronized int length() {
        return text.length();
    }

    /** Whether no text is stored. */
    public synchronized boolean isEmpty() {
        return text.isEmpty();
    }

    /** Maximum number of stored UTF-16 units. */
    public int maxChars() {
        return maxChars;
    }
}
