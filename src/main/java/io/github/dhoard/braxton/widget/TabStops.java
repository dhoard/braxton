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

package io.github.dhoard.braxton.widget;

import java.util.List;

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * Tab expansion shared by the widgets that lay out text themselves.
 *
 * <p>The layout engine expands a tab to the next four-column stop, so a widget that measures or pads
 * a cell must expand it the same way; otherwise a measured column and a displayed column disagree.
 * Styles and links are preserved.</p>
 *
 * <p>The column a tab reaches is counted over the clusters of the value's <em>plain text</em>, the
 * sequence the layout engine wraps, measures and paints. A span boundary is decoration: a base whose
 * combining mark or variation selector follows it in the next span is one cluster of the plain text,
 * so it is charged the columns that cluster measures, never the sum of its span-local pieces.
 * Counting the pieces separately placed the stop one column either way for plain text that is the
 * same, which made a Table, Quote, Box or markup cell align on where its spans happened to split.</p>
 */
final class TabStops {

    /** Columns between stops, matching the layout engine's text wrapping. */
    static final int WIDTH = 4;

    private TabStops() {
    }

    /** {@code text} with every tab replaced by spaces up to the next stop on its own line. */
    static StyledText expand(StyledText text) {
        String plain = text.plainText();
        if (plain.indexOf('\t') < 0) {
            return text;
        }
        List<StyledText.Span> spans = text.spans();
        StyledText out = StyledText.empty();
        // The run of text written for the span under the cursor, and how much of that span's own
        // text is already in it. Reading the plain text as one cluster sequence steps over span
        // boundaries, so a cluster is written into the run of the span each of its characters came
        // from and the run is closed when the next character belongs to a later span.
        StringBuilder run = new StringBuilder();
        int index = 0;
        int offset = 0;
        int used = 0;
        for (String cluster : TextMeasure.graphemes(plain)) {
            boolean tab = "\t".equals(cluster);
            String written;
            if ("\n".equals(cluster)) {
                used = 0;
                written = cluster;
            } else if (tab) {
                int stop = WIDTH - (used % WIDTH);
                used += stop;
                written = " ".repeat(stop);
            } else {
                used += TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
                written = cluster;
            }
            // A tab and a newline are clusters of their own, with a break on each side of them, so
            // only a cluster that measures columns can be split by a span boundary. That is the
            // cluster whose columns were just counted once, as the joined cluster the text view
            // paints, rather than once per span-local piece.
            int remaining = cluster.length();
            int from = 0;
            while (remaining > 0) {
                int room = spans.get(index).text().length() - offset;
                if (room == 0) {
                    out = out.append(StyledText.of(run.toString(),
                            spans.get(index).style(), spans.get(index).link()));
                    run.setLength(0);
                    index++;
                    offset = 0;
                    continue;
                }
                int take = Math.min(room, remaining);
                if (tab) {
                    // The whole stop replaces the one character of the tab it was counted for.
                    run.append(written);
                } else {
                    run.append(written, from, from + take);
                }
                offset += take;
                remaining -= take;
                from += take;
            }
        }
        return out.append(StyledText.of(run.toString(),
                spans.get(index).style(), spans.get(index).link()));
    }
}
