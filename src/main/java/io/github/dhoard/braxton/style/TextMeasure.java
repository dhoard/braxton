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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

import io.github.dhoard.braxton.internal.text.Unicode16Width;

/**
 * Text measurement in grapheme clusters and terminal columns.
 *
 * <p>Editing and slicing work in grapheme clusters so a combining mark, ZWJ sequence, or surrogate
 * pair is never split. Layout works in columns, using {@link WidthPolicy}. Every method accepts
 * plain text; styled values measure their own plain text.</p>
 */
public final class TextMeasure {

    /** Rendered in front of a cluster that has no base character to occupy a cell. */
    public static final String DOTTED_CIRCLE = "\u25CC";

    private TextMeasure() {
    }

    /** Grapheme clusters of {@code text}, in order. */
    public static List<String> graphemes(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> clusters = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(text);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            clusters.add(text.substring(start, end));
        }
        return clusters;
    }

    /** Number of grapheme clusters in {@code text}. Newlines count as one cluster. */
    public static int graphemeCount(String text) {
        return graphemes(text).size();
    }

    /** Terminal columns used by a single line of {@code text} under {@link WidthPolicy#PINNED}. */
    public static int columns(String text) {
        return columns(text, WidthPolicy.PINNED);
    }

    /** Terminal columns used by a single line of {@code text}. */
    public static int columns(String text, WidthPolicy policy) {
        int total = 0;
        for (String cluster : graphemes(text)) {
            total += clusterColumns(cluster, policy);
        }
        return total;
    }

    /** Columns occupied by one grapheme cluster. */
    public static int clusterColumns(String cluster, WidthPolicy policy) {
        if (cluster.isEmpty()) {
            return 0;
        }
        int[] codePoints = cluster.codePoints().toArray();
        boolean base = false;
        boolean mark = false;
        boolean wide = false;
        for (int codePoint : codePoints) {
            if (codePoint == 0xFE0E) {
                return 1;
            }
            if (codePoint == 0xFE0F) {
                wide = policy.wideEmoji();
                continue;
            }
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                mark = true;
                continue;
            }
            if (type == Character.FORMAT) {
                // Zero-width joiners, word joiners, bidi marks, and a byte-order mark add no column;
                // painting the cluster must not advance either.
                continue;
            }
            base = true;
            if (policy.wideEmoji() && Unicode16Width.isWide(codePoint)) {
                wide = true;
            }
        }
        if (!base && !mark) {
            return 0;
        }
        if (!base) {
            return 1;
        }
        return wide ? 2 : 1;
    }

    /**
     * Truncates {@code text} to at most {@code maxColumns} columns without splitting a cluster.
     * A wide cluster that does not fit is dropped entirely rather than half-painted.
     */
    public static String truncate(String text, int maxColumns) {
        return truncate(text, maxColumns, WidthPolicy.PINNED);
    }

    /** Truncates {@code text} to at most {@code maxColumns} columns. */
    public static String truncate(String text, int maxColumns, WidthPolicy policy) {
        if (text == null || text.isEmpty() || maxColumns <= 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (String cluster : graphemes(text)) {
            int width = clusterColumns(cluster, policy);
            if (used + width > maxColumns) {
                break;
            }
            result.append(cluster);
            used += width;
        }
        return result.toString();
    }

    /** Pads {@code text} with spaces to {@code columns}, truncating when it is too wide. */
    public static String fit(String text, int columns) {
        String clipped = truncate(text, columns);
        int used = TextMeasure.columns(clipped);
        return used >= columns ? clipped : clipped + " ".repeat(columns - used);
    }

    /** UTF-16 index of the cluster boundary at or before {@code index}. */
    public static int boundaryBefore(String text, int index) {
        if (text == null || text.isEmpty() || index <= 0) {
            return 0;
        }
        int limit = Math.min(index, text.length());
        BreakIterator iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(text);
        if (iterator.isBoundary(limit)) {
            return limit;
        }
        int previous = iterator.preceding(limit);
        return previous == BreakIterator.DONE ? 0 : previous;
    }

    /** UTF-16 index of the next cluster boundary strictly after {@code index}. */
    public static int boundaryAfter(String text, int index) {
        if (text == null || text.isEmpty() || index < 0) {
            // The first boundary after an index before the text is the start, not the end of the
            // first cluster.
            return 0;
        }
        int limit = Math.min(index, text.length() - 1);
        BreakIterator iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(text);
        int next = iterator.following(limit);
        return next == BreakIterator.DONE ? text.length() : next;
    }

    /** UTF-16 index of the previous cluster boundary before {@code index}. */
    public static int previousBoundary(String text, int index) {
        if (text == null || text.isEmpty() || index <= 0) {
            return 0;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(text);
        int previous = iterator.preceding(Math.min(index, text.length()));
        return previous == BreakIterator.DONE ? 0 : previous;
    }

    /** True when {@code index} is a UTF-16 offset on a grapheme boundary. */
    public static boolean isBoundary(String text, int index) {
        if (text == null) {
            return index == 0;
        }
        if (index < 0 || index > text.length()) {
            return false;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(text);
        return iterator.isBoundary(index);
    }

    /** Columns of the widest line of a {@link StyledText} value; newlines are not columns. */
    public static int columns(StyledText text) {
        return text.columns();
    }
}
