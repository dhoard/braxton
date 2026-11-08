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

import io.github.dhoard.braxton.internal.text.Graphemes;

/**
 * Bounded, thread-safe in-process clipboard storage.
 *
 * <p>This buffer is the reliable fallback when a terminal does not implement OSC 52, when tmux is
 * not configured to forward clipboard writes, or when the application runs without a terminal.
 * It is also the only source for application-level paste because reading the operating-system
 * clipboard is not possible with the OSC 52 protocol alone.</p>
 */
public final class ClipboardBuffer {

    /**
     * How far past the bound the truncating path segments the source: enough to reach the end of
     * the cluster that straddles the bound in text a terminal holds.
     */
    private static final int CLUSTER_LOOKAHEAD = 64;

    private final int maxChars;
    private String text = "";

    /** Creates a buffer bounded to {@code maxChars} UTF-16 units (at least one). */
    public ClipboardBuffer(int maxChars) {
        this.maxChars = Math.max(1, maxChars);
    }

    /**
     * Replaces the stored text, truncating on a grapheme-cluster boundary when it exceeds the
     * bound.
     *
     * <p>The stored value is text an application pastes back, so it has to be a sequence of
     * complete clusters of the copy: the OSC 52 payload is truncated on the same boundary. A cut
     * inside a cluster - a joiner split from the pictographs it joins, a mark split from its base -
     * would leave the value ending in a character whose meaning depends on the text after the
     * insertion point, changing it when the value is pasted.</p>
     *
     * <p>A bound that cannot hold the first cluster stores nothing. Malformed input is otherwise
     * kept verbatim, including a value that fits the bound; a truncated value never ends with a
     * lone high surrogate, because a high surrogate with nothing after it is not a character.</p>
     *
     * @return the text actually stored
     */
    public synchronized String set(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > maxChars) {
            safe = prefixOfCompleteClusters(safe, maxChars);
        }
        text = safe;
        return text;
    }

    /**
     * The longest prefix of {@code text} that fits {@code maxChars} and ends after a cluster.
     *
     * <p>Only a window of the source is segmented. Segmenting is the costly half of truncation - the
     * pinned break iterator copies and indexes everything it is given - while the answer depends
     * only on the text up to the cluster that straddles the bound, so a copy far larger than the
     * bound costs no more than the bound does.</p>
     *
     * <p>A window that ends inside that straddling cluster cannot show where the cluster ends, and a
     * cluster has no length limit (a base character plus any number of joiners, marks, or modifiers
     * is one cluster). The whole source is segmented then, which is what this method did before the
     * window existed: an unsettled window is never guessed at, so both paths store the same value.
     * </p>
     */
    private static String prefixOfCompleteClusters(String text, int maxChars) {
        int length = text.length();
        int window = (int) Math.min(length, (long) maxChars + CLUSTER_LOOKAHEAD);
        while (true) {
            int end = settledClusterBoundary(text, window, maxChars);
            if (end >= 0) {
                return text.substring(0, endWithoutTrailingLoneHighSurrogates(text, end));
            }
            // The end of the source always settles it, so this runs at most twice.
            window = length;
        }
    }

    /**
     * The largest cluster boundary at or below {@code maxChars} among the first {@code window} units
     * of {@code text}, or {@code -1} when the window stops short of the source with every one of its
     * clusters starting at or below the bound.
     *
     * <p>A window settles every boundary before its last cluster: a break, once decided, does not
     * change because more text follows. Only the start of that last cluster is in doubt, because the
     * characters after the window could extend it. A window whose last cluster starts after the
     * bound leaves that doubt below the answer, and a window that reaches the end of the source has
     * no doubt at all.</p>
     */
    private static int settledClusterBoundary(String text, int window, int maxChars) {
        int end = 0;
        int position = 0;
        for (String cluster : Graphemes.iterate(window == text.length() ? text : text.substring(0, window))) {
            if (position > maxChars) {
                return end;
            }
            end = position;
            position += cluster.length();
        }
        return window == text.length() ? end : -1;
    }

    /**
     * {@code end}, backed off the lone high surrogates a cut there can leave at the end of the
     * value. A complete cluster ends with a low surrogate or a BMP character, so a high surrogate at
     * the cut is unpaired and is not a character: the stored value must not end with something no
     * cell and no UTF-8 encoder can render.
     */
    private static int endWithoutTrailingLoneHighSurrogates(String text, int end) {
        while (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end;
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
