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

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Pure frame projection for the harness: geometry, region rows, and scroll clamping.
 *
 * <p>The frame is a full agent-terminal layout: a pinned identity block, the scrollable
 * transcript, a queued-prompt band, one gap row, the status rule, the composer, a second rule, and
 * a four-row footer. Update and view share this code, so scrolling bounds, page size, and the
 * visible copy range cannot drift from what is actually painted. Nothing here reads a clock, a
 * terminal, or a mutable cache.</p>
 */
final class HarnessProjection {

    static final int SCROLL_STEP = 3;
    static final int TOOL_PREVIEW_LINES = 6;

    /** The pinned identity block: the identity line and the rule beneath it. */
    static final int HEADER_HEIGHT = 2;

    static final int FOOTER_HEIGHT = 4;
    static final int INPUT_GAP_ROWS = 1;
    /** Transcript rows the full layout keeps whenever the queue and the draft leave room. */
    private static final int MIN_BODY_ROWS = 2;
    /** The most composer rows the frame shows, however tall the terminal or long the draft. */
    static final int MAX_EDITOR_ROWS = 10;
    /**
     * The shortest frame the full layout can carry: the header block, the input gap, two rules,
     * one composer row, and the footer. Anything shorter uses the compact layout.
     */
    static final int MIN_FULLSCREEN_ROWS = HEADER_HEIGHT + INPUT_GAP_ROWS + 2 + 1 + FOOTER_HEIGHT;

    /**
     * The classpath resource the transcript is read from, shown in the footer's source row. It is
     * the loader's own path rather than a second copy, so the row cannot name a resource the
     * example does not read.
     */
    static final String TRANSCRIPT_SOURCE = TranscriptLoader.RESOURCE;

    private static final String SEPARATOR = " | ";
    private static final String EXIT_HINT = "ctrl+c again to exit";

    private HarnessProjection() {}

    /**
     * How the terminal height is divided, in paint order. Invisible regions receive zero rows; the
     * composer is the one region painted by a component view rather than by this class.
     */
    record Layout(
            int header,
            int body,
            int queued,
            int gap,
            int statusRule,
            int editorPad,
            int editor,
            int bottomRule,
            int footer,
            int stats) {

        int rows() {
            return header + body + queued + gap + statusRule + editorPad + editor + bottomRule + footer + stats;
        }
    }

    /**
     * The prompt the composer mirrors while it waits in the queue, or an empty text when the
     * composer is editable. A submitting prompt keeps the composer until the turn it is queued
     * behind ends, so the editor cannot be typed into while a prompt of the user's is waiting.
     */
    static String mirroredPrompt(Harness.Model state) {
        return state.working() && !state.pending().isEmpty() ? state.pending().get(0) : "";
    }

    /** The composer rows the frame needs: the mirrored prompt's, or the draft's. */
    static int composerRows(Harness.Model state) {
        String mirrored = mirroredPrompt(state);
        int rows = mirrored.isEmpty() ? state.editorRows() : lineCount(mirrored);
        return Math.max(1, Math.min(MAX_EDITOR_ROWS, rows));
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    static Layout layout(Harness.Model state) {
        int height = Math.max(0, state.viewport().rows());
        int width = Math.max(1, state.viewport().cols());
        int draftRows = composerRows(state);
        if (height < MIN_FULLSCREEN_ROWS) {
            // The compact frame keeps the identity, then as much of the composer as fits, and the
            // metrics lane last. A composer shorter than its region is pushed to the bottom, so
            // the draft stays adjacent to the metrics row.
            int header = Math.min(HEADER_HEIGHT, height);
            int budget = height - header;
            int region = budget >= 2 ? budget - 1 : budget;
            int editor = Math.min(draftRows, region);
            int pad = Math.max(0, region - editor);
            int stats = budget >= 2 ? 1 : 0;
            return new Layout(header, 0, 0, 0, 0, pad, editor, 0, 0, stats);
        }
        int editor = Math.min(draftRows, maxEditorRows(height));
        int fixed = HEADER_HEIGHT + INPUT_GAP_ROWS + 2 + editor + FOOTER_HEIGHT;
        int queued = queuedBand(state, width, Math.max(0, height - fixed - MIN_BODY_ROWS))
                .size();
        int body = Math.max(0, height - fixed - queued);
        return new Layout(HEADER_HEIGHT, body, queued, INPUT_GAP_ROWS, 1, 0, editor, 1, FOOTER_HEIGHT, 0);
    }

    /**
     * The composer rows the frame allows at this height: a third of the frame or the rows left
     * once the pinned chrome and one composer row are set aside, whichever is smaller, and never
     * more than {@link #MAX_EDITOR_ROWS}.
     */
    private static int maxEditorRows(int height) {
        int rowsBesideTheBody = HEADER_HEIGHT + INPUT_GAP_ROWS + 2 + FOOTER_HEIGHT;
        return Math.min(MAX_EDITOR_ROWS, Math.min(Math.max(1, height / 3), Math.max(1, height - rowsBesideTheBody)));
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    static int maxScroll(Harness.Model state) {
        int rows = layout(state).body();
        if (rows <= 0) {
            return 0;
        }
        int total = projectedRows(state.session(), state.viewport().cols()).stream()
                .mapToInt(ProjectedRows::size)
                .sum();
        return Math.max(0, total - rows);
    }

    /** The visible transcript rows and the scroll position they were read at. */
    private record Slice(List<StyledText> rows, int start, int total) {}

    private static Slice slice(Session.Model state, int width, int rows) {
        if (rows <= 0 || width <= 0) {
            return new Slice(List.of(), 0, 0);
        }
        List<ProjectedRows> groups = projectedRows(state, width);
        int total = groups.stream().mapToInt(ProjectedRows::size).sum();
        int maxScroll = Math.max(0, total - rows);
        int scroll = Math.min(Math.max(0, state.scrollBack()), maxScroll);
        int end = total - scroll;
        int start = Math.max(0, end - rows);
        List<StyledText> window = new ArrayList<>(rows);
        int offset = 0;
        for (ProjectedRows group : groups) {
            for (int i = Math.max(0, start - offset); i < Math.min(group.size(), end - offset); i++) {
                window.add(group.row().apply(i));
            }
            offset += group.size();
            if (offset >= end) {
                break;
            }
        }
        return new Slice(List.copyOf(window), start, total);
    }

    /** The visible transcript window, padded with background rows to the allocated height. */
    static List<StyledText> window(Session.Model state, int width, int allocatedRows) {
        int rows = Math.max(0, allocatedRows);
        Slice slice = slice(state, width, rows);
        List<StyledText> window = new ArrayList<>(slice.rows());
        while (window.size() < rows) {
            window.add(backgroundRow(width));
        }
        return List.copyOf(window);
    }

    /**
     * Plain text of the currently visible transcript, used by Alt+T: no right padding, so pasted
     * text carries the transcript only.
     */
    static String visiblePlainText(Harness.Model state) {
        List<StyledText> rows = slice(
                        state.session(), state.viewport().cols(), layout(state).body())
                .rows();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(rows.get(i).plainText().stripTrailing());
        }
        return text.toString().stripTrailing();
    }

    // ── content ──────────────────────────────────────────────────────────────

    /** Wrapped content and a pure row factory, deferring styled output until a row is requested. */
    private record ProjectedRows(int size, IntFunction<StyledText> row) {}

    static List<StyledText> allRows(Harness.Model state, int width) {
        List<StyledText> result = new ArrayList<>();
        for (ProjectedRows group : projectedRows(state.session(), width)) {
            for (int i = 0; i < group.size(); i++) {
                result.add(group.row().apply(i));
            }
        }
        return result;
    }

    /** One blank-separated group per entry, in transcript order. */
    private static List<ProjectedRows> projectedRows(Session.Model state, int width) {
        List<ProjectedRows> rows = new ArrayList<>();
        List<HarnessEntry> entries = state.entries();
        if (entries.isEmpty()) {
            entries = List.of(
                    new HarnessEntry.Notice("No example transcript is loaded. Fallback mock responses are active."));
        }
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                rows.add(new ProjectedRows(1, ignored -> backgroundRow(width)));
            }
            List<StyledText> message = HarnessMessage.rows(width, entries.get(i));
            rows.add(new ProjectedRows(message.size(), message::get));
        }
        return rows;
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    /** The pinned identity block: the identity line, then a full-width rule. */
    static List<StyledText> header(Harness.Model state, int width) {
        int columns = Math.max(1, width);
        List<StyledText> rows = new ArrayList<>(HEADER_HEIGHT);
        rows.add(identityRow(columns));
        rows.add(rule(columns));
        return rows;
    }

    /**
     * The identity line: the harness name in the accent with the example's version beside it in the
     * metrics dim, and the recorded session it replays ending the row at the right edge. The
     * session field is all or nothing, so a narrow frame shows the identity alone rather than a
     * truncated identifier that could name a different session.
     */
    private static StyledText identityRow(int width) {
        StyledText identity = StyledText.of(
                        "AI Harness", Style.fg(HarnessTheme.ACCENT_COLOR).withBold())
                .append(StyledText.of(" v" + HarnessTranscript.EXAMPLE_VERSION, HarnessTheme.DIM));
        StyledText field = StyledText.of(HarnessTranscript.EXAMPLE_SESSION_ID, HarnessTheme.DIM);
        int identityWidth = HarnessRender.visibleLength(identity);
        int fieldWidth = HarnessRender.visibleLength(field);
        if (fieldWidth == 0 || identityWidth + 1 + fieldWidth > width) {
            return HarnessRender.fill(identity, width, HarnessTheme.BACKGROUND);
        }
        int gap = width - identityWidth - fieldWidth;
        return HarnessRender.fill(
                identity.append(StyledText.plain(" ".repeat(gap))).append(field), width, HarnessTheme.BACKGROUND);
    }

    /** A full-width horizontal rule in the metrics dim, the style every frame rule wears. */
    static StyledText rule(int width) {
        int columns = Math.max(1, width);
        return HarnessRender.fill(
                StyledText.of("\u2500".repeat(columns), HarnessTheme.DIM), columns, HarnessTheme.BACKGROUND);
    }

    /**
     * The rule above the composer, carrying the turn's status label in the text colour. An idle
     * frame, or a label that does not fit, leaves the plain rule.
     */
    static StyledText statusRule(Harness.Model state) {
        int width = Math.max(1, state.viewport().cols());
        String label = statusLabel(state);
        if (label.isBlank() || HarnessRender.visibleLength(label) + 5 > width) {
            return rule(width);
        }
        int trailing = Math.max(1, width - HarnessRender.visibleLength(label) - 3);
        StyledText row = StyledText.of("\u2500 ", HarnessTheme.DIM)
                .append(StyledText.of(label, HarnessTheme.TEXT))
                .append(StyledText.of(" " + "\u2500".repeat(trailing), HarnessTheme.DIM));
        return HarnessRender.fill(row, width, HarnessTheme.BACKGROUND);
    }

    /** The status label of a working turn, or the empty label of an idle one. */
    static String statusLabel(Harness.Model state) {
        if (!state.working()) {
            return "";
        }
        long started = state.workStartedNanos();
        long elapsedMillis = started < 0L ? 0L : Math.max(0, (state.nowNanos() - started) / 1_000_000L);
        String hint = state.escArmedUntilNanos() > state.nowNanos() ? " - esc again to stop" : " - esc esc to stop";
        return HarnessRender.spinner(elapsedMillis)
                + " Working ("
                + HarnessRender.formatElapsed(elapsedMillis / 1000L)
                + hint
                + ")";
    }

    /**
     * The queued prompts, wrapped edge to edge at the viewport width, below one blank separator
     * row. A band longer than {@code maxRows} keeps its leading rows and ends with a count of the
     * prompts its rows do not name, so the prompts stay queued and are still sent in order.
     */
    static List<StyledText> queuedBand(Harness.Model state, int width, int maxRows) {
        int limit = Math.max(0, maxRows);
        List<StyledText> rows = new ArrayList<>();
        if (limit == 0) {
            return rows;
        }
        // The mirrored prompt is painted by the composer, not by the band, so it is not shown
        // twice. A queue of one therefore leaves the band empty.
        List<String> prompts = state.pending();
        int first = mirroredPrompt(state).isEmpty() ? 0 : 1;
        List<Integer> promptStarts = new ArrayList<>();
        for (int index = first; index < prompts.size(); index++) {
            String prompt = prompts.get(index);
            List<StyledText> rendered = new ArrayList<>();
            for (String line : HarnessRender.sanitize(prompt).split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                for (String wrapped : HarnessRender.wrap(line, width)) {
                    rendered.add(HarnessRender.fill(
                            StyledText.of(wrapped, HarnessTheme.QUEUED), width, HarnessTheme.BACKGROUND));
                }
            }
            if (rendered.isEmpty()) {
                continue;
            }
            if (rows.isEmpty()) {
                rows.add(backgroundRow(width));
            }
            promptStarts.add(rows.size());
            rows.addAll(rendered);
        }
        if (rows.isEmpty() || rows.size() <= limit) {
            return rows;
        }
        int kept = Math.max(0, limit - 1);
        int unnamed = 0;
        for (int start : promptStarts) {
            if (start >= kept) {
                unnamed++;
            }
        }
        List<StyledText> shown = new ArrayList<>(rows.subList(0, Math.min(kept, rows.size())));
        shown.add(queuedCountRow(width, "[" + unnamed + " more queued]"));
        return shown;
    }

    /**
     * The composer rows for a prompt waiting in the queue: the prompt's own lines in the queued
     * colour, with no caret. A line too wide for the frame is cut at its right edge, exactly as
     * the editor cuts a draft line it cannot draw.
     */
    static List<StyledText> queuedComposerRows(Harness.Model state, int width, int rows) {
        int columns = Math.max(1, width);
        int limit = Math.max(0, rows);
        String[] lines = HarnessRender.sanitize(mirroredPrompt(state)).split("\n", -1);
        int from = Math.max(0, lines.length - limit);
        List<StyledText> out = new ArrayList<>(limit);
        for (int i = from; i < lines.length; i++) {
            out.add(HarnessRender.fill(StyledText.of(lines[i], HarnessTheme.QUEUED), columns, HarnessTheme.BACKGROUND));
        }
        return out;
    }

    /** The footer: project path, example source, provider/model identity, and the metrics lane. */
    static List<StyledText> footer(Harness.Model state, int width) {
        int columns = Math.max(0, width);
        List<StyledText> rows = new ArrayList<>(FOOTER_HEIGHT);
        String path = state.projectPath();
        rows.add(HarnessRender.fill(
                StyledText.of(
                        HarnessRender.truncateLeft(path == null || path.isBlank() ? "braxton" : path, columns),
                        HarnessTheme.DIM),
                columns,
                HarnessTheme.BACKGROUND));
        // A path from the root is read from its left edge, so it keeps the leading slash and is
        // cut at its right edge instead of from the front.
        rows.add(HarnessRender.fill(
                StyledText.of(HarnessRender.truncate(TRANSCRIPT_SOURCE, columns), HarnessTheme.DIM),
                columns,
                HarnessTheme.BACKGROUND));
        rows.add(HarnessRender.fill(
                StyledText.of(HarnessRender.truncateLeft(state.profile().label(), columns), HarnessTheme.DIM),
                columns,
                HarnessTheme.BACKGROUND));
        rows.add(statsRow(state, columns));
        return rows;
    }

    /** The metrics lane: real totals, cache hit rate, context use, and generation rate. */
    static StyledText statsRow(Harness.Model state, int width) {
        int columns = Math.max(0, width);
        if (columns == 0) {
            return StyledText.empty();
        }
        HarnessTranscript.Usage usage = state.usage();
        long prompt = usage.uncachedInputTokens() > 0 ? usage.uncachedInputTokens() : usage.inputTokens();
        long cacheTotal = usage.cachedInputTokens() + usage.uncachedInputTokens();
        List<StyledText> parts = new ArrayList<>();
        parts.add(metric("\u2191 " + count(prompt)));
        parts.add(metric("\u2193 " + count(usage.outputTokens())));
        parts.add(metric("R " + count(usage.cachedInputTokens())));
        parts.add(metric("CH "
                + (cacheTotal > 0
                        ? String.format(Locale.ROOT, "%.1f%%", usage.cachedInputTokens() * 100.0 / cacheTotal)
                        : "-")));
        if (state.contextWindow() > 0) {
            parts.add(contextPart(usage, state.contextWindow()));
        }
        parts.add(metric(HarnessRender.formatRate(usage.generationRate())));

        String note = state.quitArmedUntilNanos() > state.nowNanos() ? EXIT_HINT : "";
        int noteWidth = note.isEmpty() ? 0 : HarnessRender.visibleLength(note);
        int available = Math.max(1, columns - (note.isEmpty() ? 0 : noteWidth + 2));
        StyledText stats = HarnessRender.truncate(joined(parts, available), available);
        if (note.isEmpty()) {
            return HarnessRender.fill(stats, columns, HarnessTheme.BACKGROUND);
        }
        int gap = Math.max(2, columns - HarnessRender.visibleLength(stats) - noteWidth);
        return HarnessRender.fill(
                stats.append(StyledText.plain(" ".repeat(gap))).append(StyledText.of(note, HarnessTheme.ACCENT)),
                columns,
                HarnessTheme.BACKGROUND);
    }

    private static StyledText contextPart(HarnessTranscript.Usage usage, int contextWindow) {
        long tokens = usage.contextTokens();
        double percent = tokens * 100.0 / contextWindow;
        String label = HarnessRender.formatTokens(contextWindow) + String.format(Locale.ROOT, " (%.1f%%)", percent);
        if (tokens > 0 && percent > 90) {
            return StyledText.of(label, HarnessTheme.ERROR);
        }
        if (tokens > 0 && percent > 70) {
            return StyledText.of(label, HarnessTheme.WARNING);
        }
        return metric(label);
    }

    /**
     * Joins the metric parts that fit {@code available} columns with the lane separator. The first
     * part always stays: a lane too narrow for it is truncated by the row fill rather than emptied.
     */
    private static StyledText joined(List<StyledText> parts, int available) {
        StyledText row = StyledText.empty();
        int used = 0;
        for (StyledText part : parts) {
            int size = HarnessRender.visibleLength(part) + (used == 0 ? 0 : SEPARATOR.length());
            if (used > 0 && used + size > available) {
                break;
            }
            if (used > 0) {
                row = row.append(StyledText.of(SEPARATOR, HarnessTheme.DIM));
            }
            row = row.append(part);
            used += size;
        }
        return row;
    }

    private static String count(long value) {
        return value > 0 ? HarnessRender.formatTokens(value) : "-";
    }

    private static StyledText metric(String text) {
        return StyledText.of(text, HarnessTheme.DIM);
    }

    private static StyledText queuedCountRow(int width, String text) {
        return HarnessRender.fill(
                StyledText.of(HarnessRender.truncate(text, Math.max(1, width)), HarnessTheme.NOTICE),
                Math.max(1, width),
                HarnessTheme.BACKGROUND);
    }

    /** One full-width row painted with the frame background. */
    static StyledText backgroundRow(int width) {
        return HarnessRender.fill(StyledText.empty(), Math.max(0, width), HarnessTheme.BACKGROUND);
    }
}
