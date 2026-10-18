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
import java.util.Base64;

import io.github.dhoard.braxton.style.TextMeasure;

/**
 * OSC 52 clipboard encoding.
 *
 * <p>An OSC 52 write is {@code ESC ] 52 ; <selection> ; <base64 UTF-8> BEL}. The BEL terminator is
 * used because every mainstream OSC 52 implementation accepts it and it avoids embedding another
 * ESC byte in a tmux passthrough payload.</p>
 *
 * <p>There is no acknowledgement in the protocol. A caller can know that a sequence was written,
 * never that a terminal or tmux actually placed the text on the system clipboard.</p>
 */
public final class Osc52 {

    /** The default clipboard selection: the system clipboard. */
    public static final String CLIPBOARD_SELECTION = "c";

    private static final char ESC = '\u001b';
    private static final char BEL = '\u0007';
    private static final String PREFIX = ESC + "]52;";

    private Osc52() {
    }

    /**
     * Encodes {@code text} for the system clipboard without a size limit.
     *
     * @return the complete OSC 52 sequence, or an empty string when there is no text (including
     *     {@code null}), because an empty payload would clear the user's clipboard
     */
    public static String encode(String text) {
        // Nothing to encode means nothing to write. An empty payload would otherwise reach the
        // terminal as a sequence that clears the clipboard.
        return text == null || text.isEmpty()
                ? "" : encode(text, CLIPBOARD_SELECTION, Integer.MAX_VALUE).sequence();
    }

    /**
     * Encodes {@code text} for the system clipboard, truncating it on a grapheme-cluster boundary
     * when it exceeds {@code maxBytes}.
     */
    public static Encoded encode(String text, int maxBytes) {
        return encode(text, CLIPBOARD_SELECTION, maxBytes);
    }

    /**
     * Encodes {@code text} for the requested selection.
     *
     * @param text clipboard text; {@code null} is treated as empty
     * @param selection OSC 52 selection ({@code c}, {@code p}, {@code s}, {@code 0}–{@code 7}); an
     *     unknown value falls back to {@code c} so the sequence cannot be injected through it
     * @param maxBytes maximum UTF-8 payload size; a value below zero is treated as zero
     */
    public static Encoded encode(String text, String selection, int maxBytes) {
        String safeText = text == null ? "" : text;
        String safeSelection = validSelection(selection);
        int limit = Math.max(0, maxBytes);
        String limited = limitUtf8(safeText, limit);
        boolean truncated = limited.length() < safeText.length();
        byte[] bytes = limited.getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String sequence = PREFIX + safeSelection + ";" + base64 + BEL;
        return new Encoded(sequence, limited, bytes.length, truncated);
    }

    /**
     * The encoded payload. {@code byteLength} is the UTF-8 size of {@code text} after any
     * truncation, and {@code truncated} reports whether the configured limit removed text.
     */
    public record Encoded(String sequence, String text, int byteLength, boolean truncated) {
        public Encoded {
            sequence = sequence == null ? "" : sequence;
            text = text == null ? "" : text;
            byteLength = Math.max(0, byteLength);
        }
    }

    private static String validSelection(String selection) {
        if (selection == null || selection.isEmpty()) {
            return CLIPBOARD_SELECTION;
        }
        if (selection.length() == 1) {
            char c = selection.charAt(0);
            if (c == 'c' || c == 'p' || c == 's' || (c >= '0' && c <= '7')) {
                return selection;
            }
        }
        return CLIPBOARD_SELECTION;
    }

    /**
     * Truncates to at most {@code maxBytes} UTF-8 bytes without splitting a grapheme cluster, so a
     * combining accent, a ZWJ emoji sequence, or a flag never reaches the clipboard half-formed.
     */
    static String limitUtf8(String text, int maxBytes) {
        if (text.isEmpty() || maxBytes <= 0) {
            return "";
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        StringBuilder limited = new StringBuilder();
        int used = 0;
        for (String cluster : TextMeasure.graphemes(text)) {
            int size = cluster.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) {
                break;
            }
            limited.append(cluster);
            used += size;
        }
        return limited.toString();
    }
}
