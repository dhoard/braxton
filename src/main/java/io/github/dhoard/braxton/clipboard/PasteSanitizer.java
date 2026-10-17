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

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized, deterministic normalization for pasted text.
 *
 * <p>Terminal, browser, and documentation copies routinely carry trailing padding, carriage
 * returns, ANSI styling, OSC window-title sequences, nonbreaking spaces, zero-width characters,
 * and stray control bytes. This class removes those artifacts while preserving printable Unicode,
 * combining marks, emoji (including ZWJ sequences), internal blank lines, and—in
 * {@link PasteMode#NORMAL}—leading indentation and tabs used by source code.</p>
 *
 * <p>Both modes are idempotent: {@code sanitize(sanitize(text, mode), mode)} equals
 * {@code sanitize(text, mode)}.</p>
 */
public final class PasteSanitizer {

    private static final char ESC = '\u001b';
    private static final char BEL = '\u0007';
    private static final char C1_CSI = '\u009b';
    private static final char C1_OSC = '\u009d';
    private static final char C1_ST = '\u009c';
    private static final char C1_DCS = '\u0090';
    private static final char C1_SOS = '\u0098';
    private static final char C1_PM = '\u009e';
    private static final char C1_APC = '\u009f';

    private PasteSanitizer() {
    }

    /** Normalizes pasted text with {@link PasteMode#NORMAL}. */
    public static String sanitize(String text) {
        return sanitize(text, PasteMode.NORMAL);
    }

    /** Normalizes pasted text with the requested mode. */
    public static String sanitize(String text, PasteMode mode) {
        return sanitize(text, mode, defaultCollapseBlankLines(mode));
    }

    /**
     * Normalizes pasted text with the requested mode.
     *
     * @param collapseBlankLines whether runs of internal blank lines collapse to one. Defaults to
     *     {@code true} for {@link PasteMode#AGGRESSIVE} and {@code false} for
     *     {@link PasteMode#NORMAL}.
     */
    public static String sanitize(String text, PasteMode mode, boolean collapseBlankLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        PasteMode effective = mode == null ? PasteMode.NORMAL : mode;
        boolean aggressive = effective == PasteMode.AGGRESSIVE;
        String stripped = stripArtifacts(text, aggressive);

        String[] lines = stripped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripTrailing(lines[i]);
            if (aggressive) {
                line = line.stripLeading();
            }
            lines[i] = line;
        }

        int first = 0;
        int last = lines.length - 1;
        while (first <= last && lines[first].isEmpty()) {
            first++;
        }
        while (last >= first && lines[last].isEmpty()) {
            last--;
        }
        if (first > last) {
            return "";
        }

        List<String> kept = new ArrayList<>(last - first + 1);
        boolean previousBlank = false;
        for (int i = first; i <= last; i++) {
            String line = lines[i];
            boolean blank = line.isEmpty();
            if (blank && previousBlank && collapseBlankLines) {
                continue;
            }
            kept.add(line);
            previousBlank = blank;
        }
        return String.join("\n", kept);
    }

    /**
     * Returns plain text suitable for a clipboard payload: line endings are normalized to LF and
     * ANSI/OSC/DCS sequences, control characters, BOMs, zero-width spaces, and nonbreaking spaces
     * are removed or normalized. Unlike the paste modes, this method does not trim lines or remove
     * blank lines, so copying a source selection preserves it exactly.
     */
    public static String plainText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return stripArtifacts(text, false);
    }

    private static boolean defaultCollapseBlankLines(PasteMode mode) {
        return mode == PasteMode.AGGRESSIVE;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && isTrimmedWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * Trailing whitespace as users expect it, including non-breaking spaces (already normalized to
     * ordinary spaces). {@link Character#isWhitespace(char)} intentionally excludes NBSP, hence the
     * explicit cases.
     */
    private static boolean isTrimmedWhitespace(char c) {
        return Character.isWhitespace(c) || c == '\u00a0' || c == '\u2007' || c == '\u202f';
    }

    /**
     * Removes terminal escape sequences, C0/C1 controls, line-ending artifacts, nonbreaking spaces,
     * and the invisible formatting characters that are safe to drop without damaging emoji or
     * combining sequences.
     */
    private static String stripArtifacts(String text, boolean aggressive) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\r') {
                index += index + 1 < text.length() && text.charAt(index + 1) == '\n' ? 2 : 1;
                out.append('\n');
                continue;
            }
            if (c == ESC) {
                index = skipEscape(text, index);
                continue;
            }
            if (c == C1_CSI) {
                index = skipCsi(text, index + 1);
                continue;
            }
            if (c == C1_OSC || c == C1_DCS || c == C1_SOS || c == C1_PM || c == C1_APC) {
                index = skipControlString(text, index + 1);
                continue;
            }
            if (c == '\n' || c == '\t') {
                out.append(c);
                index++;
                continue;
            }
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
                index++;
                continue;
            }
            if (c == '\u00a0' || c == '\u2007' || c == '\u202f') {
                out.append(' ');
                index++;
                continue;
            }
            if (c == '\ufeff' || c == '\u200b' || c == '\u2060' || c == '\u180e'
                    || c == '\u00ad') {
                index++;
                continue;
            }
            if (aggressive && isAggressivelyRemoved(c)) {
                index++;
                continue;
            }
            out.append(c);
            index++;
        }
        return out.toString();
    }

    /**
     * Additional invisible formatting removed only in {@link PasteMode#AGGRESSIVE}. Zero-width
     * joiner (U+200D) is deliberately never removed because it is required by emoji sequences.
     */
    private static boolean isAggressivelyRemoved(char c) {
        return c == '\u200c'                 // zero-width non-joiner
                || c == '\u061c'             // Arabic letter mark
                || c == '\u200e' || c == '\u200f' // LRM / RLM
                || (c >= '\u202a' && c <= '\u202e') // bidi embeddings/overrides
                || (c >= '\u2061' && c <= '\u2064') // invisible mathematical operators
                || (c >= '\u2066' && c <= '\u206f') // bidi isolates and deprecated format chars
                || (c >= '\ufff9' && c <= '\ufffb'); // interlinear annotations
    }

    /** Skips an ESC-introduced sequence and returns the index of the first byte after it. */
    private static int skipEscape(String text, int index) {
        int next = index + 1;
        if (next >= text.length()) {
            return text.length();
        }
        char c = text.charAt(next);
        return switch (c) {
            case '[' -> skipCsi(text, next + 1);
            case ']' -> skipControlString(text, next + 1);
            case 'P', 'X', '^', '_' -> skipControlString(text, next + 1);
            case '(', ')', '*', '+', '-', '.', '/' -> Math.min(text.length(), next + 2);
            default -> {
                // Two-character escapes (ESC 7, ESC M, ESC =, …) are removed whole; every
                // printable byte is a valid escape final in the ANSI grammar. A lone ESC before a
                // non-printable character drops only the ESC.
                if (c >= 0x20 && c <= 0x7e) {
                    yield next + 1;
                }
                yield next;
            }
        };
    }

    /** Skips a CSI sequence (already past {@code ESC [} or the C1 CSI byte). */
    private static int skipCsi(String text, int index) {
        int i = index;
        while (i < text.length() && text.charAt(i) >= 0x30 && text.charAt(i) <= 0x3f) {
            i++;
        }
        while (i < text.length() && text.charAt(i) >= 0x20 && text.charAt(i) <= 0x2f) {
            i++;
        }
        if (i < text.length() && text.charAt(i) >= 0x40 && text.charAt(i) <= 0x7e) {
            return i + 1;
        }
        return i;
    }

    /**
     * Skips an OSC/DCS/SOS/PM/APC string. An unterminated string is dropped through the end of the
     * input: leaving its payload behind could expose terminal-controlled text as visible garbage.
     */
    private static int skipControlString(String text, int index) {
        int i = index;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == BEL || c == C1_ST) {
                return i + 1;
            }
            if (c == ESC && i + 1 < text.length() && text.charAt(i + 1) == '\\') {
                return i + 2;
            }
            i++;
        }
        return text.length();
    }
}
