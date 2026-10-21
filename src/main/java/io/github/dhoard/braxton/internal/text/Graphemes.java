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

package io.github.dhoard.braxton.internal.text;

import java.text.BreakIterator;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Streaming segmentation with the same boundaries as the public text measurement API. */
public final class Graphemes {
    private static final String[] ASCII = new String[128];
    static {
        for (int i = 0; i < ASCII.length; i++) {
            ASCII[i] = String.valueOf((char) i);
        }
    }
    private Graphemes() {
    }

    public static Iterable<String> iterate(String text) {
        String value = text == null ? "" : text;
        return () -> new Cursor(value);
    }

    private static final class Cursor implements Iterator<String> {
        private final String text;
        private final BreakIterator iterator;
        private int start;

        Cursor(String text) {
            this.text = text;
            boolean simple = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // Tabs and LF have a break on both sides in this alphabet. CR is deliberately
                // excluded: CR/LF is one cluster, and other Unicode uses the pinned JDK iterator.
                if ((c < 0x20 || c > 0x7e) && c != '\n' && c != '\t') {
                    simple = false;
                    break;
                }
            }
            iterator = simple ? null : BreakIterator.getCharacterInstance(Locale.ROOT);
            if (iterator != null) {
                iterator.setText(text);
                iterator.first();
            }
        }

        @Override
        public boolean hasNext() {
            return start < text.length();
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (iterator == null) {
                return ASCII[text.charAt(start++)];
            }
            int end = iterator.next();
            String cluster = text.substring(start, end);
            start = end;
            return cluster;
        }
    }
}
