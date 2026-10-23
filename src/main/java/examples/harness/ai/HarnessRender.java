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

package examples.harness.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.style.TextMeasure;

/**
 * Text and metric helpers for the showcase.
 *
 * <p>Everything here is pure: sanitizing, wrapping, truncating, and formatting never touch terminal
 * state. Styled composition lives in {@link HarnessProjection}.</p>
 */
final class HarnessRender {

    private static final String[] SPINNER = {"\u25c7", "\u25c8", "\u25c6", "\u25c8"};

    private HarnessRender() {
    }

    /**
     * Makes untrusted transcript text inert: strips escapes and control characters, normalizes line
     * endings, and expands tabs. Line-break conversion belongs to {@link PasteSanitizer}, which
     * prepares the complete input before filtering; repeating it here would be a second policy to
     * keep in sync.
     */
    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return PasteSanitizer.plainText(text).replace("\t", "    ");
    }

    /**
     * Wraps plain text to {@code width} visible cells, preserving words and leading indentation.
     *
     * <p>No row costs more than {@code width} cells unless one grapheme cluster of the text is
     * itself wider than that: a cluster cannot be split, so it starts a row of its own. Callers
     * such as the user-message pill in {@link HarnessProjection} pad every row to this width, so an
     * over-wide row costs real text.</p>
     */
    static List<String> wrap(String text, int width) {
        int limit = Math.max(1, width);
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                out.add("");
            } else {
                wrapLine(line, limit, out);
            }
        }
        return out;
    }

    private static void wrapLine(String line, int limit, List<String> out) {
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        // Indentation alone is not content: a word too long for the remaining cells continues the
        // same line instead of flushing the indent as a blank line.
        boolean hasContent = false;
        int index = 0;
        while (index < line.length()) {
            int start = index;
            boolean spaces = line.charAt(index) == ' ';
            while (index < line.length() && (line.charAt(index) == ' ') == spaces) {
                index++;
            }
            int runWidth = runColumns(line, start, index);
            // What the row would cost with the whole run on it, which is not always currentWidth
            // plus the run's own columns: the run can continue the cell the row already ends with.
            int rowWidth = appendedColumns(current, currentWidth, line, start, index);
            if (!spaces && rowWidth > limit) {
                // A cluster that claims no cell of its own - a variation selector whose base glyph
                // was lost when the text was copied, a joiner - belongs to the cell in front of it.
                // It cannot wrap away from that cell, so it stays on this row and the rest of the
                // run is wrapped below.
                int cellEnd = joinedCellEnd(current, line, start);
                if (cellEnd > start) {
                    int cellWidth = appendedColumns(current, currentWidth, line, start, cellEnd);
                    if (cellWidth <= limit) {
                        current.append(line, start, cellEnd);
                        currentWidth = cellWidth;
                        hasContent = true;
                        index = cellEnd;
                        continue;
                    }
                }
            }
            if (spaces) {
                if (currentWidth == 0) {
                    // The run is whitespace, so clip it instead of truncating it: truncate marks a
                    // cut with an ellipsis, which would draw a content marker on a blank line.
                    String leading = " ".repeat(Math.min(runWidth, limit));
                    current.append(leading);
                    currentWidth = visibleLength(leading);
                } else if (rowWidth <= limit) {
                    current.append(line, start, index);
                    currentWidth = rowWidth;
                } else {
                    flush(current, out);
                    currentWidth = 0;
                }
                continue;
            }
            if (runWidth > limit) {
                if (hasContent) {
                    flush(current, out);
                    currentWidth = 0;
                }
                for (String cluster : TextMeasure.graphemes(line.substring(start, index))) {
                    int joinedWidth = appendedColumns(current, currentWidth, cluster, 0,
                            cluster.length());
                    if (currentWidth > 0 && joinedWidth > limit) {
                        if (hasContent) {
                            flush(current, out);
                        }
                        current.setLength(0);
                        currentWidth = 0;
                        // The row is empty, so the cluster joins nothing of its own.
                        joinedWidth = runColumns(cluster, 0, cluster.length());
                    }
                    current.append(cluster);
                    currentWidth = joinedWidth;
                    hasContent = true;
                }
            } else if (currentWidth == 0) {
                current.append(line, start, index);
                currentWidth = rowWidth;
                hasContent = true;
            } else if (rowWidth <= limit) {
                current.append(line, start, index);
                currentWidth = rowWidth;
                hasContent = true;
            } else {
                // Indentation alone is not content: a word that does not fit after it starts the
                // next line instead of flushing the indent as a blank one.
                if (hasContent) {
                    flush(current, out);
                } else {
                    current.setLength(0);
                }
                current.append(line, start, index);
                currentWidth = runWidth;
                hasContent = true;
            }
        }
        flush(current, out);
    }

    /**
     * The index just past the clusters at the head of the run that continue the cell the row being
     * built ends with, or {@code start} when the run opens a cell of its own.
     *
     * <p>A cluster with no base of its own claims no cell: a variation selector whose base glyph
     * was lost when the text was copied or scraped continues the space that stands in for that
     * base, and widens it. Wrapping that selector to the next row with the run behind it charges
     * this row for a column it never pays and leaves the selector to widen the first cell it finds
     * on the next one.</p>
     */
    private static int joinedCellEnd(StringBuilder current, String line, int start) {
        char first = line.charAt(start);
        if (current.length() == 0 || first >= 0x20 && first <= 0x7E) {
            // A printable ASCII character is never an extend, format, or spacing mark, so it always
            // opens a cell of its own and continues nothing.
            return start;
        }
        int cellEnd = TextMeasure.boundaryAfter(line, start);
        int cellStart = TextMeasure.previousBoundary(current.toString(), current.length());
        if (TextMeasure.graphemeCount(
                current.substring(cellStart) + line.substring(start, cellEnd)) != 1) {
            return start;
        }
        return cellEnd;
    }

    /**
     * The columns the row built so far would occupy once {@code text[start,end)} follows it. A run
     * is never measured on its own: a variation selector with no base of its own continues the cell
     * in front of it and makes that cell two columns wide, and those are the columns the row costs
     * once painted. Charging the run for the columns it holds alone left rows a cell wider than the
     * width they were wrapped to, and {@link #pad} cut the real text they overflowed with an
     * ellipsis.
     */
    private static int appendedColumns(StringBuilder current, int currentWidth, String text,
            int start, int end) {
        char first = text.charAt(start);
        if (first >= 0x20 && first <= 0x7E) {
            // A printable ASCII character is never an extend, format, or spacing mark, so it always
            // starts a cell of its own: nothing across the gap joins and the two widths add up.
            return currentWidth + runColumns(text, start, end);
        }
        if (current.length() == 0) {
            return runColumns(text, start, end);
        }
        StringBuilder joined = new StringBuilder(current.length() + end - start);
        joined.append(current).append(text, start, end);
        return visibleLength(joined.toString());
    }

    /** Measure ordinary word/space runs without allocating one substring for every word. */
    private static int runColumns(String line, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return visibleLength(line.substring(start, end));
            }
        }
        return end - start;
    }

    private static void flush(StringBuilder current, List<String> out) {
        if (current.length() == 0) {
            return;
        }
        out.add(current.toString().stripTrailing());
        current.setLength(0);
    }

    /** Truncates plain text to {@code width} cells, appending an ellipsis when cut. */
    static String truncate(String text, int width) {
        String safe = text == null ? "" : text;
        int limit = Math.max(0, width);
        if (visibleLength(safe) <= limit) {
            return safe;
        }
        if (limit == 0) {
            return "";
        }
        if (limit == 1) {
            return "\u2026";
        }
        return TextMeasure.truncate(safe, limit - 1) + "\u2026";
    }

    /** Truncates from the left, keeping the informative tail of a path. */
    static String truncateLeft(String text, int width) {
        String safe = text == null ? "" : text;
        int limit = Math.max(0, width);
        if (visibleLength(safe) <= limit) {
            return safe;
        }
        if (limit == 0) {
            return "";
        }
        if (limit == 1) {
            return "\u2026";
        }
        List<String> clusters = TextMeasure.graphemes(safe);
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = clusters.size() - 1; i >= 0; i--) {
            String cluster = clusters.get(i);
            int clusterWidth = TextMeasure.columns(cluster);
            if (used + clusterWidth > limit - 1) {
                break;
            }
            kept.insert(0, cluster);
            used += clusterWidth;
        }
        return "\u2026" + kept;
    }

    /** Pads or truncates plain text to exactly {@code width} cells. */
    static String pad(String text, int width) {
        int limit = Math.max(0, width);
        String fitted = truncate(text == null ? "" : text, limit);
        int missing = limit - visibleLength(fitted);
        return missing <= 0 ? fitted : fitted + " ".repeat(missing);
    }

    static int visibleLength(String text) {
        return text == null ? 0 : TextMeasure.columns(text);
    }

    /** Amanda-compatible compact token counts. */
    static String formatTokens(long count) {
        if (count < 1000) {
            return Long.toString(count);
        }
        if (count < 10000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1000.0);
        }
        if (count < 999_500) {
            return Math.round(count / 1000.0) + "k";
        }
        if (count < 10_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        return Math.round(count / 1_000_000.0) + "M";
    }

    /** Formats elapsed seconds as {@code 1h 0m 5s}, {@code 1m 5s}, or {@code 5s}. */
    static String formatElapsed(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + rest + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + rest + "s";
        }
        return rest + "s";
    }

    static String formatRate(Double rate) {
        if (rate == null || rate < 0 || !Double.isFinite(rate)) {
            return "- t/s";
        }
        return Math.round(rate) + " t/s";
    }

    static String spinner(long elapsedMillis) {
        long frame = Math.max(0, elapsedMillis) / 250;
        return SPINNER[Math.floorMod(frame, SPINNER.length)];
    }

    /** A bounded preview of tool output: the first {@code maxLines} lines plus a total count. */
    static Preview toolPreview(String result, int maxLines) {
        String safe = sanitize(result);
        if (safe.isBlank()) {
            return new Preview(List.of(), 0);
        }
        String[] lines = safe.split("\n", -1);
        int total = lines.length;
        while (total > 0 && lines[total - 1].isEmpty()) {
            total--;
        }
        if (total == 0) {
            return new Preview(List.of(), 0);
        }
        int shown = Math.min(Math.max(1, maxLines), total);
        List<String> preview = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            preview.add(lines[i]);
        }
        return new Preview(preview, total);
    }

    record Preview(List<String> lines, int totalLines) {
        Preview {
            lines = List.copyOf(lines);
        }

        boolean truncated() {
            return totalLines > lines.size();
        }

        int hidden() {
            return Math.max(0, totalLines - lines.size());
        }
    }
}
