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

package io.github.dhoard.braxton.internal.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Text the session writes into the terminal's scrollback, normalized to lines of plain text.
 *
 * <p>Durable output is the one path where an application's own bytes reach the terminal beside a
 * frame the session is managing. Two properties make that safe. The text carries no control
 * characters, so it cannot move the cursor out of the line it is on, and it carries no escape
 * sequence, so it cannot enter a mode the session's own ledger knows nothing about and would never
 * give back. What survives normalization is the printable text the caller meant, with every line
 * ending - {@code LF}, {@code CRLF} or a bare {@code CR} - resolved to one {@code LF}, and tabs kept
 * because a tab is a column step within the line, not a command to the terminal.</p>
 *
 * <p>Normalization happens here, not at the command factory, because this is the point every durable
 * write passes through: a caller cannot reach the terminal by building a command, and a future
 * producer of durable lines cannot bypass the rule by forgetting to normalize first.</p>
 */
public final class DurableText {

    private DurableText() {}

    /**
     * The lines {@code text} holds once every control character except tab and newline is removed.
     *
     * @param text the text to normalize; null is no text
     * @return the lines, in order, each without its terminator; empty text yields no lines
     */
    public static List<String> lines(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < normalized.length(); index++) {
            if (normalized.charAt(index) == '\n') {
                lines.add(normalized.substring(start, index));
                start = index + 1;
            }
        }
        lines.add(normalized.substring(start));
        return lines;
    }

    /**
     * The printable form of {@code text}: every line ending as {@code LF}, no other control character.
     *
     * @param text the text to normalize; null is the empty string
     * @return the normalized text
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n') {
                out.append(value);
                continue;
            }
            if (value == '\r') {
                // CRLF and a bare CR both end a line; the LF a CRLF already carries is skipped so
                // one ending never becomes two.
                out.append('\n');
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                continue;
            }
            if (value == '\t' || !Character.isISOControl(value)) {
                out.append(value);
            }
        }
        return out.toString();
    }
}
