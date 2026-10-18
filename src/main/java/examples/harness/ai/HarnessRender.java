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
     * endings, and expands tabs.
     */
    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return PasteSanitizer.plainText(normalized).replace("\t", "    ");
    }

    /** Wraps plain text to {@code width} visible cells, preserving words and leading indentation. */
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
            String run = line.substring(start, index);
            int runWidth = visibleLength(run);
            if (spaces) {
                if (currentWidth == 0) {
                    String leading = truncate(run, limit);
                    current.append(leading);
                    currentWidth = visibleLength(leading);
                } else if (currentWidth + runWidth <= limit) {
                    current.append(run);
                    currentWidth += runWidth;
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
                for (String cluster : TextMeasure.graphemes(run)) {
                    int clusterWidth = TextMeasure.columns(cluster);
                    if (currentWidth > 0 && currentWidth + clusterWidth > limit) {
                        if (hasContent) {
                            flush(current, out);
                        }
                        current.setLength(0);
                        currentWidth = 0;
                    }
                    current.append(cluster);
                    currentWidth += clusterWidth;
                    hasContent = true;
                }
            } else if (currentWidth == 0) {
                current.append(run);
                currentWidth = runWidth;
                hasContent = true;
            } else if (currentWidth + runWidth <= limit) {
                current.append(run);
                currentWidth += runWidth;
                hasContent = true;
            } else {
                // Indentation alone is not content: a word that does not fit after it starts the
                // next line instead of flushing the indent as a blank one.
                if (hasContent) {
                    flush(current, out);
                } else {
                    current.setLength(0);
                }
                current.append(run);
                currentWidth = runWidth;
                hasContent = true;
            }
        }
        flush(current, out);
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
