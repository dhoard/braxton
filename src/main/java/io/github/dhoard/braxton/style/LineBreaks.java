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

package io.github.dhoard.braxton.style;

/**
 * One policy for preparing raw text that may contain LF, CRLF, or a lone CR.
 *
 * <p>Text arriving from a clipboard, a file, or another platform may use any of the three common
 * break forms. These methods convert a <em>complete</em> raw string to the form a destination
 * needs: {@link #normalize} produces the LF that layout and the editor use, {@link #singleRow}
 * produces the one space a one-row decoration needs, and {@link #singleRowAndTabs} also expands
 * tabs to one space. CRLF is one logical break, not two characters.</p>
 *
 * <p>The methods are pure, stateless, and linear: a single forward scan converts the input and
 * allocates a replacement only when a conversion is needed. They accept a complete logical value;
 * they do not buffer or recognize a CR/LF pair that is split across independent calls. Assemble
 * the raw text first: {@code normalize("a\r") + normalize("\nb")} is {@code "a\n\nb"}, while
 * {@code normalize("a\r\nb")} is {@code "a\nb"}.</p>
 *
 * <p>Conversion is not sanitization. Other control characters and unpaired surrogates are
 * preserved, and tab stops are not expanded, so the result still belongs at a
 * {@link StyledText#plain(String)} or {@link StyledText#of(String, Style)} boundary. Prepare raw
 * text <em>before</em> constructing a span: {@code Span} deletes a bare CR as an unrepresentable
 * control character, and no widget can recover a separator that is already gone. Consequently,
 * {@code StyledText.plain("a\rb").plainText()} remains {@code "ab"}, while
 * {@code StyledText.plain(LineBreaks.normalize("a\rb"))} is {@code "a\nb"}.</p>
 */
public final class LineBreaks {

    private LineBreaks() {
    }

    /**
     * Converts LF, CRLF, and lone CR to one LF each. Tabs and every other character are preserved.
     * A null or empty value returns an empty string.
     */
    public static String normalize(String text) {
        return convert(text, false, false);
    }

    /**
     * Converts LF, CRLF, and lone CR to one space each, for a value painted on a single row. Tabs
     * are preserved because the destination may expand them to its own tab stops. A null or empty
     * value returns an empty string.
     */
    public static String singleRow(String text) {
        return convert(text, true, false);
    }

    /**
     * Converts LF, CRLF, and lone CR to one space each and tabs to one space, for a value whose
     * painted width must not depend on where it sits. A null or empty value returns an empty
     * string.
     */
    public static String singleRowAndTabs(String text) {
        return convert(text, true, true);
    }

    /**
     * Converts in one forward pass. Text with nothing to convert is returned unchanged, including
     * the null-to-empty normalization handled by the public methods.
     */
    private static String convert(String text, boolean flat, boolean flatTabs) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\r' || c == '\n' || (flatTabs && c == '\t')) {
                break;
            }
            index++;
        }
        if (index == text.length()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, index);
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\r') {
                out.append(flat ? ' ' : '\n');
                // CRLF is one logical break; treating the two characters separately would double
                // the separator on every Windows-sourced line.
                index += index + 1 < text.length() && text.charAt(index + 1) == '\n' ? 2 : 1;
            } else if (c == '\n') {
                out.append(flat ? ' ' : '\n');
                index++;
            } else if (c == '\t' && flatTabs) {
                out.append(' ');
                index++;
            } else {
                out.append(c);
                index++;
            }
        }
        return out.toString();
    }
}
