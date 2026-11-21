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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.RichList.FilterMode;
import io.github.dhoard.braxton.widget.RichList.Found;
import io.github.dhoard.braxton.widget.RichList.Highlight;
import io.github.dhoard.braxton.widget.RichList.Item;
import java.util.ArrayList;
import java.util.List;

/**
 * Unicode filtering, match ranking, and highlight projection.
 */
final class RichListMatching {

    /**
     * One item's match: its title's highlights and the rank keys of the match in its filter text.
     */
    static Found found(List<int[]> terms, FilterMode mode, Item item) {
        List<int[]> ranges = codePointRanges(terms, folded(item.filterText()), mode);
        if (ranges == null) {
            return null;
        }
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (int[] range : ranges) {
            first = Math.min(first, range[0]);
            last = Math.max(last, range[1] - 1);
        }
        String title = item.title();
        List<int[]> titleRanges =
                title.equals(item.filterText()) ? ranges : codePointRanges(terms, folded(title), mode);
        return new Found(titleRanges == null ? List.of() : highlights(titleRanges, title), last - first + 1, first);
    }

    /**
     * The ranges a query selects in one folded text, or null when a term does not match. Ranges are
     * code point indices, sorted and merged so two terms that overlap highlight once.
     */
    static List<int[]> codePointRanges(List<int[]> terms, int[] text, FilterMode mode) {
        List<int[]> found = new ArrayList<>(terms.size());
        for (int[] term : terms) {
            if (mode == FilterMode.PREFIX) {
                int at = indexOf(text, term, 0);
                if (at < 0) {
                    return null;
                }
                found.add(new int[] {at, at + term.length});
                continue;
            }
            List<Integer> positions = fuzzyPositions(text, term);
            if (positions.isEmpty()) {
                return null;
            }
            int start = positions.get(0);
            int previous = start;
            for (int index = 1; index < positions.size(); index++) {
                int position = positions.get(index);
                if (position == previous + 1) {
                    previous = position;
                    continue;
                }
                found.add(new int[] {start, previous + 1});
                start = position;
                previous = position;
            }
            found.add(new int[] {start, previous + 1});
        }
        found.sort((left, right) ->
                left[0] != right[0] ? Integer.compare(left[0], right[0]) : Integer.compare(left[1], right[1]));
        List<int[]> merged = new ArrayList<>(found.size());
        for (int[] range : found) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
                continue;
            }
            merged.add(new int[] {range[0], range[1]});
        }
        return merged;
    }

    /**
     * The first code point index of {@code needle} in {@code haystack} at or after {@code from}, or -1.
     */
    static int indexOf(int[] haystack, int[] needle, int from) {
        if (needle.length == 0) {
            return Math.max(0, from);
        }
        for (int start = Math.max(0, from); start + needle.length <= haystack.length; start++) {
            int matched = 0;
            while (matched < needle.length && haystack[start + matched] == needle[matched]) {
                matched++;
            }
            if (matched == needle.length) {
                return start;
            }
        }
        return -1;
    }

    /**
     * The code point indices of the shortest window of {@code text} that spells {@code term} as a
     * subsequence, or an empty list when it does not.
     *
     * <p>The window is contracted from both ends: the forward scan finds the first complete match,
     * and the backward scan moves the start as late as it can go while still matching, which is what
     * makes the span the shortest one for that match. The next search starts one code point later,
     * so every possible window is considered once and the total work stays linear in the text for a
     * fixed term.</p>
     */
    static List<Integer> fuzzyPositions(int[] text, int[] term) {
        if (term.length == 0) {
            return List.of();
        }
        int bestStart = -1;
        int bestEnd = -1;
        int start = 0;
        while (start + term.length <= text.length) {
            int index = start;
            int matched = 0;
            while (index < text.length && matched < term.length) {
                if (text[index] == term[matched]) {
                    matched++;
                }
                index++;
            }
            if (matched < term.length) {
                break;
            }
            int end = index;
            int back = end - 1;
            matched = term.length - 1;
            while (back >= start) {
                if (text[back] == term[matched]) {
                    matched--;
                    if (matched < 0) {
                        break;
                    }
                }
                back--;
            }
            if (bestStart < 0 || end - back < bestEnd - bestStart) {
                bestStart = back;
                bestEnd = end;
            }
            start = back + 1;
        }
        if (bestStart < 0) {
            return List.of();
        }
        List<Integer> positions = new ArrayList<>(term.length);
        int matched = 0;
        for (int index = bestStart; index < bestEnd && matched < term.length; index++) {
            if (text[index] == term[matched]) {
                positions.add(index);
                matched++;
            }
        }
        return positions;
    }

    /**
     * The query's terms, folded to code points.
     */
    static List<int[]> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<int[]> terms = new ArrayList<>(4);
        for (String term : query.strip().split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(folded(term));
            }
        }
        return terms;
    }

    /**
     * One value as lowercased code points, with the simple mapping that never changes a length.
     */
    static int[] folded(String text) {
        String value = text == null ? "" : text;
        int[] points = new int[value.codePointCount(0, value.length())];
        int index = 0;
        for (int offset = 0; offset < value.length(); ) {
            int point = value.codePointAt(offset);
            points[index++] = Character.toLowerCase(point);
            offset += Character.charCount(point);
        }
        return points;
    }

    /**
     * The utf-16 offset of every code point of one value, plus the value's length.
     */
    static int[] offsetsOf(String text) {
        int[] offsets = new int[text.codePointCount(0, text.length()) + 1];
        int index = 0;
        for (int offset = 0; offset < text.length(); ) {
            offsets[index++] = offset;
            offset += Character.charCount(text.codePointAt(offset));
        }
        offsets[index] = text.length();
        return offsets;
    }

    /**
     * The highlighted ranges of one value: offsets widened to whole clusters, then merged.
     */
    static List<Highlight> highlights(List<int[]> ranges, String text) {
        if (ranges.isEmpty() || text.isEmpty()) {
            return List.of();
        }
        int[] offsets = offsetsOf(text);
        List<Highlight> highlights = new ArrayList<>(ranges.size());
        for (int[] range : ranges) {
            int start = offsets[Math.min(range[0], offsets.length - 1)];
            int end = offsets[Math.min(range[1], offsets.length - 1)];
            if (end <= start) {
                continue;
            }
            // A style boundary inside a cluster would split it, so the range is widened to the
            // boundaries on either side of what matched.
            int from = TextMeasure.boundaryBefore(text, start);
            int to = TextMeasure.boundaryAfter(text, Math.max(from, end - 1));
            if (to <= from) {
                continue;
            }
            Highlight last = highlights.isEmpty() ? null : highlights.get(highlights.size() - 1);
            if (last != null && from <= last.end()) {
                highlights.set(highlights.size() - 1, new Highlight(last.start(), Math.max(last.end(), to)));
                continue;
            }
            highlights.add(new Highlight(from, to));
        }
        return List.copyOf(highlights);
    }

    /**
     * One painted row: the value's line breaks become spaces and it is cut to the width.
     */
    static StyledText oneRow(StyledText value, int width) {
        StyledText row = StyledText.empty();
        int used = 0;
        for (StyledText.Span span : value.spans()) {
            String text = LineBreaks.singleRow(span.text());
            int columns = TextMeasure.columns(text);
            if (used + columns <= width) {
                row = row.append(StyledText.of(text, span.style(), span.link()));
                used += columns;
                continue;
            }
            row = row.append(
                    StyledText.of(TextMeasure.truncate(text, Math.max(0, width - used)), span.style(), span.link()));
            break;
        }
        return row;
    }
}
