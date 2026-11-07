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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One transcript message: a {@code TYPE} header on the header band, one blank row, then the body.
 *
 * <p>The header names what the block is, in the kind's accent, and carries the time the message
 * entered the session in the metrics dim, so a reader can tell a prompt, a reasoning step, a tool
 * call, a notice, and a failure apart without reading the body. The body keeps the text colour, so
 * a message is never one coloured block.</p>
 */
final class HarnessMessage {

    private static final String TIMESTAMP_PATTERN = "uuuu-MM-dd h:mm a";

    /** The message kinds the transcript renders and the accent each one's label wears. */
    enum Kind {
        USER("USER", HarnessTheme.USER_GUTTER_COLOR),
        THINKING("THINKING", HarnessTheme.THINKING_GUTTER_COLOR),
        ASSISTANT("ASSISTANT", HarnessTheme.ASSISTANT_GUTTER_COLOR),
        TOOL("TOOL", HarnessTheme.TOOL_GUTTER_COLOR),
        /** A failed tool call keeps the tool label and wears the failure accent. */
        TOOL_ERROR("TOOL", HarnessTheme.ERROR_GUTTER_COLOR),
        SYSTEM("SYSTEM", HarnessTheme.SYSTEM_GUTTER_COLOR),
        ERROR("ERROR", HarnessTheme.ERROR_GUTTER_COLOR);

        private final String label;
        private final Color accent;

        Kind(String label, Color accent) {
            this.label = label;
            this.accent = accent;
        }

        String label() {
            return label;
        }

        Color accent() {
            return accent;
        }
    }

    private HarnessMessage() {}

    /** The kind one entry renders as. */
    static Kind kind(HarnessEntry entry) {
        return switch (entry) {
            case HarnessEntry.User user -> Kind.USER;
            case HarnessEntry.Assistant assistant -> Kind.ASSISTANT;
            case HarnessEntry.Reasoning reasoning -> Kind.THINKING;
            case HarnessEntry.Notice notice -> Kind.SYSTEM;
            case HarnessEntry.Failure failure -> Kind.ERROR;
            case HarnessEntry.Tool tool -> tool.status() == HarnessEntry.ToolStatus.ERROR ? Kind.TOOL_ERROR : Kind.TOOL;
        };
    }

    /** The rows one entry occupies: the header, one blank row, and the body. */
    static List<StyledText> rows(int width, HarnessEntry entry) {
        int columns = Math.max(1, width);
        List<StyledText> body = body(columns, entry);
        int end = body.size();
        // A body that ends in blank rows would multiply the separator the next message adds.
        while (end > 0 && body.get(end - 1).plainText().isBlank()) {
            end--;
        }
        List<StyledText> rows = new ArrayList<>(end + 2);
        rows.add(header(kind(entry), entry.epochMillis(), columns));
        rows.add(HarnessProjection.backgroundRow(columns));
        rows.addAll(body.subList(0, end));
        return rows;
    }

    /**
     * The header row: the kind label in bold on the header band, then the time the message entered
     * the session, one space after the label and in the metrics dim. A message with no recorded
     * time carries its label alone; a header wider than the frame is cut at its right edge.
     */
    static StyledText header(Kind kind, long epochMillis, int width) {
        StyledText header = StyledText.of(kind.label(), Style.fg(kind.accent()).withBold());
        if (epochMillis > 0) {
            header = header.append(StyledText.of(" " + timestamp(epochMillis), HarnessTheme.DIM));
        }
        return HarnessRender.fill(header, Math.max(1, width), HarnessTheme.HEADER_BACKGROUND);
    }

    /**
     * The header's timestamp: an unpadded 12-hour clock with minutes, on the host's own zone. The
     * time travels with the message, so a re-render, a resize, or a scroll never changes it.
     */
    static String timestamp(long epochMillis) {
        return DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.ROOT)
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private static List<StyledText> body(int width, HarnessEntry entry) {
        return switch (entry) {
            case HarnessEntry.User user -> text(width, user.text(), HarnessTheme.TEXT);
            case HarnessEntry.Assistant assistant -> text(width, assistant.text(), HarnessTheme.TEXT);
            case HarnessEntry.Reasoning reasoning -> text(width, reasoning.text(), HarnessTheme.THINKING);
            case HarnessEntry.Notice notice -> text(width, notice.text(), HarnessTheme.TEXT);
            case HarnessEntry.Failure failure -> text(width, failure.text(), HarnessTheme.TEXT);
            case HarnessEntry.Tool tool -> tool(width, tool);
        };
    }

    private static List<StyledText> text(int width, String text, Style style) {
        List<StyledText> rows = new ArrayList<>();
        for (String line : HarnessRender.wrap(HarnessRender.sanitize(text), width)) {
            rows.add(HarnessRender.fill(StyledText.of(line, style), width, HarnessTheme.BACKGROUND));
        }
        return rows;
    }

    /**
     * A tool message: the invocation, then, when there is output, one blank row and the bounded
     * preview. Omissions and engine truncation are named in the dim notice voice rather than in
     * the content colour, so generated text is never mistaken for tool output.
     */
    private static List<StyledText> tool(int width, HarnessEntry.Tool tool) {
        List<StyledText> rows = new ArrayList<>();
        Style content = tool.status() == HarnessEntry.ToolStatus.ERROR ? HarnessTheme.ERROR : HarnessTheme.TEXT;
        rows.addAll(text(width, tool.call(), content));
        HarnessRender.Preview preview = HarnessRender.toolPreview(tool.result(), HarnessProjection.TOOL_PREVIEW_LINES);
        if (preview.lines().isEmpty()) {
            return rows;
        }
        rows.add(HarnessProjection.backgroundRow(width));
        for (String line : preview.lines()) {
            rows.addAll(text(width, line, content));
        }
        if (preview.truncated()) {
            rows.addAll(text(
                    width,
                    "(" + preview.hidden() + " more " + (preview.hidden() == 1 ? "line)" : "lines)"),
                    HarnessTheme.DIM));
        }
        if (tool.truncated()) {
            rows.addAll(text(width, "(truncated)", HarnessTheme.DIM));
        }
        return rows;
    }
}
