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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * Pure screen projection for the harness: geometry, content rows, and scroll clamping.
 *
 * <p>Update and view share this code, so scrolling bounds, page size, and the visible copy range
 * cannot drift from what is actually painted. Nothing here reads a clock, a terminal, or a mutable
 * cache.</p>
 */
final class HarnessProjection {

    static final int SCROLL_STEP = 3;
    static final int TOOL_PREVIEW_LINES = 6;
    private static final long FAKE_CACHED_TOKENS = 2_100_000L;

    private HarnessProjection() {
    }

    /** How the terminal height is divided. Invisible regions receive zero rows. */
    record Allocation(int transcript, int gap, int topRule, int composer, int bottomRule, int footer) {
    }

    /**
     * Allocates rows in the documented priority order: composer first, then footer, then the two
     * rules and the gap, then the transcript with whatever remains.
     */
    static Allocation allocate(int height, int editorRows) {
        int budget = Math.max(0, height);
        int composer = Math.min(Math.max(1, editorRows), budget);
        budget -= composer;
        int footer = Math.min(3, budget);
        budget -= footer;
        int bottomRule = budget > 0 ? 1 : 0;
        budget -= bottomRule;
        int topRule = budget > 0 ? 1 : 0;
        budget -= topRule;
        int gap = budget > 0 ? 1 : 0;
        budget -= gap;
        return new Allocation(budget, gap, topRule, composer, bottomRule, footer);
    }

    record Segment(String text, Style style) {
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    static int maxScroll(HarnessState state) {
        Allocation allocation = allocate(state.viewport().rows(), state.editorRows());
        if (allocation.transcript() <= 0) {
            return 0;
        }
        int rows = allRows(state, state.viewport().cols()).size();
        return Math.max(0, rows - allocation.transcript());
    }

    /** The visible transcript rows, padded to the allocation, before any scrollbar chrome. */
    private record Slice(List<StyledText> rows, int start, int total) {
    }

    private static Slice slice(HarnessState state, int width, int height, int editorRows) {
        Allocation allocation = allocate(height, editorRows);
        int rows = allocation.transcript();
        if (rows <= 0 || width <= 0) {
            return new Slice(List.of(), 0, 0);
        }
        List<StyledText> all = allRows(state, width);
        int maxScroll = Math.max(0, all.size() - rows);
        int scroll = Math.min(Math.max(0, state.scrollBack()), maxScroll);
        int end = all.size() - scroll;
        int start = Math.max(0, end - rows);
        List<StyledText> window = new ArrayList<>(all.subList(start, end));
        while (window.size() < rows) {
            window.add(row(width, HarnessTheme.BACKGROUND_COLOR));
        }
        return new Slice(List.copyOf(window), start, all.size());
    }

    /** The visible transcript window, padded and with a scrollbar when content overflows. */
    static List<StyledText> window(HarnessState state, int width, int height, int editorRows) {
        Slice slice = slice(state, width, height, editorRows);
        List<StyledText> window = new ArrayList<>(slice.rows());
        int rows = window.size();
        if (rows > 0 && slice.total() > rows) {
            int maxScroll = Math.max(0, slice.total() - rows);
            int thumbHeight = Math.min(rows, Math.max(2,
                    (int) Math.round((double) rows * rows / slice.total())));
            int maxThumbTop = rows - thumbHeight;
            int thumbTop = maxScroll == 0 ? 0
                    : (int) Math.round((double) slice.start() / maxScroll * maxThumbTop);
            for (int i = 0; i < rows; i++) {
                boolean thumb = i >= thumbTop && i < thumbTop + thumbHeight;
                window.set(i, withScrollbar(window.get(i), width,
                        thumb ? "\u2503" : "\u2502",
                        thumb ? HarnessTheme.SCROLLBAR_THUMB : HarnessTheme.SCROLLBAR_TRACK));
            }
        }
        return List.copyOf(window);
    }

    /** Plain text of the currently visible transcript, used by Alt+T; never the scrollbar. */
    static String visiblePlainText(HarnessState state) {
        List<StyledText> rows = slice(state, state.viewport().cols(), state.viewport().rows(),
                state.editorRows()).rows();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(rows.get(i).plainText());
        }
        return text.toString().stripTrailing();
    }

    // ── content ──────────────────────────────────────────────────────────────

    static List<StyledText> allRows(HarnessState state, int width) {
        List<StyledText> rows = new ArrayList<>();
        rows.add(row(width, HarnessTheme.BACKGROUND_COLOR,
                new Segment(" AI Harness", HarnessTheme.ACCENT.withBold()),
                new Segment("  \u00b7  mock transcript", HarnessTheme.DIM)));
        rows.add(row(width, HarnessTheme.BACKGROUND_COLOR));

        List<HarnessEntry> entries = state.entries();
        if (entries.isEmpty()) {
            entries = List.of(new HarnessEntry.Notice(
                    "No example transcript is loaded. Fallback mock responses are active."));
        }
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                rows.add(row(width, HarnessTheme.BACKGROUND_COLOR));
            }
            rows.addAll(entryRows(width, entries.get(i)));
        }
        return rows;
    }

    private static List<StyledText> entryRows(int width, HarnessEntry entry) {
        return switch (entry) {
            case HarnessEntry.User user -> userRows(width, user.text());
            case HarnessEntry.Assistant assistant -> plainRows(width, assistant.text(),
                    HarnessTheme.TEXT);
            case HarnessEntry.Reasoning reasoning -> plainRows(width, reasoning.text(),
                    HarnessTheme.THINKING);
            case HarnessEntry.Notice notice -> plainRows(width, notice.text(),
                    HarnessTheme.NOTICE);
            case HarnessEntry.Failure failure -> plainRows(width, failure.text(),
                    HarnessTheme.ERROR);
            case HarnessEntry.Tool tool -> toolRows(width, tool);
        };
    }

    private static List<StyledText> userRows(int width, String text) {
        int inner = Math.max(1, width - 2);
        List<StyledText> rows = new ArrayList<>();
        rows.add(row(width, HarnessTheme.USER_MESSAGE_BG_COLOR));
        for (String line : HarnessRender.wrap(text, inner)) {
            rows.add(row(width, HarnessTheme.USER_MESSAGE_BG_COLOR,
                    new Segment(" " + HarnessRender.pad(line, inner) + " ", HarnessTheme.TEXT)));
        }
        rows.add(row(width, HarnessTheme.USER_MESSAGE_BG_COLOR));
        return rows;
    }

    private static List<StyledText> plainRows(int width, String text, Style style) {
        List<StyledText> rows = new ArrayList<>();
        for (String line : HarnessRender.wrap(text, Math.max(1, width - 1))) {
            rows.add(row(width, HarnessTheme.BACKGROUND_COLOR,
                    new Segment(" " + line, style)));
        }
        return rows;
    }

    private static List<StyledText> toolRows(int width, HarnessEntry.Tool tool) {
        Color background = switch (tool.status()) {
            case PENDING -> HarnessTheme.TOOL_PENDING_BG_COLOR;
            case SUCCESS -> HarnessTheme.TOOL_SUCCESS_BG_COLOR;
            case ERROR -> HarnessTheme.TOOL_ERROR_BG_COLOR;
        };
        int inner = Math.max(1, width - 2);
        List<StyledText> rows = new ArrayList<>();
        rows.add(row(width, background));
        rows.add(row(width, background,
                new Segment(" " + HarnessRender.truncate(tool.call(), inner) + " ",
                        HarnessTheme.TEXT.withBold())));

        HarnessRender.Preview preview = HarnessRender.toolPreview(
                tool.result(), TOOL_PREVIEW_LINES);
        if (!preview.lines().isEmpty()) {
            rows.add(row(width, background));
            for (String line : preview.lines()) {
                for (String wrapped : HarnessRender.wrap(line, inner)) {
                    rows.add(row(width, background,
                            new Segment(" " + HarnessRender.pad(wrapped, inner) + " ",
                                    HarnessTheme.DIM)));
                }
            }
            if (preview.truncated()) {
                String note = "... (" + preview.hidden() + " more lines, "
                        + preview.totalLines() + " total)";
                rows.add(row(width, background,
                        new Segment(" " + HarnessRender.truncate(note, inner) + " ",
                                HarnessTheme.DIM)));
            }
        } else if (tool.status() == HarnessEntry.ToolStatus.PENDING) {
            rows.add(row(width, background,
                    new Segment(" waiting for output...", HarnessTheme.DIM)));
        }
        rows.add(row(width, background));
        return rows;
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    static StyledText topRule(HarnessState state) {
        int width = Math.max(1, state.viewport().cols());
        boolean quitArmed = state.quitArmedUntilNanos() > state.nowNanos();
        if (!state.working()) {
            return rule(width, quitArmed ? "ctrl+c again to exit" : "");
        }
        long elapsedMillis = Math.max(0,
                (state.nowNanos() - state.workStartedNanos()) / 1_000_000L);
        String queued = state.pending().size() > 0
                ? " \u00b7 " + state.pending().size() + " queued" : "";
        String hint = state.escArmedUntilNanos() > state.nowNanos()
                ? " - esc again to stop" : "";
        String quitHint = quitArmed ? " \u00b7 ctrl+c again to exit" : "";
        String label = HarnessRender.spinner(elapsedMillis) + " Working ("
                + HarnessRender.formatElapsed(elapsedMillis / 1000L) + queued + hint + quitHint + ")";
        return rule(width, label);
    }

    static StyledText bottomRule(HarnessState state) {
        return rule(Math.max(1, state.viewport().cols()), "");
    }

    static List<StyledText> footer(HarnessState state) {
        int limit = Math.max(0, state.viewport().cols());
        String path = state.projectPath();
        if (path == null || path.isBlank()) {
            path = "braxton";
        }
        List<StyledText> rows = new ArrayList<>(3);
        rows.add(row(limit, HarnessTheme.BACKGROUND_COLOR,
                new Segment(HarnessRender.truncateLeft(path, limit), HarnessTheme.DIM)));
        rows.add(row(limit, HarnessTheme.BACKGROUND_COLOR,
                new Segment(HarnessRender.truncate(state.profile().label(), limit),
                        HarnessTheme.DIM)));
        rows.add(statsRow(limit, state));
        return rows;
    }

    private static StyledText statsRow(int width, HarnessState state) {
        if (width <= 0) {
            return StyledText.empty();
        }
        HarnessTranscript.Usage usage = state.usage();
        long promptInput = usage.uncachedInputTokens() > 0
                ? usage.uncachedInputTokens() : usage.inputTokens();
        long promptTotal = usage.cachedInputTokens() + usage.uncachedInputTokens();
        List<Segment> parts = new ArrayList<>();
        if (promptInput > 0) {
            parts.add(new Segment("\u2191 " + HarnessRender.formatTokens(promptInput),
                    HarnessTheme.DIM));
        }
        if (usage.outputTokens() > 0) {
            parts.add(new Segment("\u2193 " + HarnessRender.formatTokens(usage.outputTokens()),
                    HarnessTheme.DIM));
        }
        parts.add(new Segment("R " + HarnessRender.formatTokens(FAKE_CACHED_TOKENS),
                HarnessTheme.DIM));
        if (promptTotal > 0) {
            parts.add(new Segment("CH " + String.format(java.util.Locale.ROOT, "%.1f%%",
                    usage.cachedInputTokens() * 100.0 / promptTotal), HarnessTheme.DIM));
        }
        if (state.contextWindow() > 0) {
            double percent = usage.contextTokens() * 100.0 / state.contextWindow();
            Style color = usage.contextTokens() > 0 && percent > 90
                    ? HarnessTheme.ERROR
                    : usage.contextTokens() > 0 && percent > 70
                            ? HarnessTheme.WARNING : HarnessTheme.DIM;
            parts.add(new Segment(HarnessRender.formatTokens(state.contextWindow())
                    + String.format(java.util.Locale.ROOT, " (%.1f%%)", percent), color));
        }
        if (!parts.isEmpty() && usage.generationRate() != null) {
            parts.add(new Segment(HarnessRender.formatRate(usage.generationRate()),
                    HarnessTheme.DIM));
        }

        for (int count = parts.size(); count > 0; count--) {
            String plain = joinText(parts, count);
            if (HarnessRender.visibleLength(plain) <= width) {
                return segmentsRow(width, parts.subList(0, count));
            }
        }
        if (parts.isEmpty()) {
            return row(width, HarnessTheme.BACKGROUND_COLOR,
                    new Segment("ready", HarnessTheme.DIM));
        }
        return row(width, HarnessTheme.BACKGROUND_COLOR,
                new Segment(HarnessRender.truncate(parts.get(0).text(), width), HarnessTheme.DIM));
    }

    private static StyledText segmentsRow(int width, List<Segment> parts) {
        List<Segment> withSeparators = new ArrayList<>(parts.size() * 2 - 1);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                withSeparators.add(new Segment(" | ", HarnessTheme.DIM));
            }
            withSeparators.add(parts.get(i));
        }
        return row(width, HarnessTheme.BACKGROUND_COLOR,
                withSeparators.toArray(Segment[]::new));
    }

    private static String joinText(List<Segment> parts, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(" | ");
            }
            text.append(parts.get(i).text());
        }
        return text.toString();
    }

    static StyledText rule(int width, String label) {
        int limit = Math.max(1, width);
        if (label == null || label.isBlank()) {
            return row(limit, HarnessTheme.BACKGROUND_COLOR,
                    new Segment("\u2500".repeat(limit), HarnessTheme.TEXT));
        }
        int available = Math.max(1, limit - 4);
        String fitted = HarnessRender.truncate(label, available);
        int trailing = Math.max(1, limit - 3 - HarnessRender.visibleLength(fitted));
        return row(limit, HarnessTheme.BACKGROUND_COLOR,
                new Segment("\u2500 ", HarnessTheme.TEXT),
                new Segment(fitted, HarnessTheme.TEXT),
                new Segment(" " + "\u2500".repeat(trailing), HarnessTheme.TEXT));
    }

    /** One full-width, background-filled row. */
    static StyledText row(int width, Color background, Segment... segments) {
        int limit = Math.max(0, width);
        Style pad = background == null ? Style.NONE : Style.bg(background);
        StyledText row = StyledText.empty();
        int used = 0;
        for (Segment segment : segments) {
            int remaining = limit - used;
            if (remaining <= 0) {
                break;
            }
            String text = HarnessRender.truncate(segment.text(), remaining);
            if (text.isEmpty()) {
                continue;
            }
            Style style = segment.style() == null ? Style.NONE : segment.style();
            if (background != null) {
                style = style.withBackground(background);
            }
            row = row.append(StyledText.of(text, style));
            used += HarnessRender.visibleLength(text);
        }
        if (used < limit) {
            row = row.append(StyledText.of(" ".repeat(limit - used), pad));
        }
        return row;
    }

    private static StyledText withScrollbar(StyledText content, int width, String glyph,
            Style style) {
        if (width <= 0) {
            return StyledText.empty();
        }
        StyledText body = truncateStyled(content, width - 1);
        return body.append(StyledText.of(glyph, style.withBackground(HarnessTheme.BACKGROUND_COLOR)));
    }

    private static StyledText truncateStyled(StyledText content, int columns) {
        if (columns <= 0) {
            return StyledText.empty();
        }
        int used = 0;
        StyledText out = StyledText.empty();
        for (StyledText.Span span : content.spans()) {
            for (String grapheme : TextMeasure.graphemes(span.text())) {
                int width = TextMeasure.clusterColumns(grapheme, WidthPolicy.PINNED);
                if (used + width > columns) {
                    return out;
                }
                out = out.append(StyledText.of(grapheme, span.style(), span.link()));
                used += width;
            }
        }
        return out;
    }
}
