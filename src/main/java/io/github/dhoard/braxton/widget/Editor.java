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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A reusable text editor: a pure reducer plus a declarative view.
 *
 * <p>Submitting, copying, pasting, and focus changes are <em>intents</em>, not side effects. The
 * owning application decides whether a submit clears the draft, deactivates focus, runs validation,
 * or turns a paste request into a clipboard command.</p>
 */
public final class Editor {

    public static final String CURSOR = "\u2588";
    private static final String MASK = "\u2022";

    private Editor() {
    }

    /** Applies one editing action. Never performs I/O and never mutates the input state. */
    public static EditorResult update(EditorState state, EditorOptions options, EditorAction action) {
        EditorOptions opts = options == null ? EditorOptions.defaults() : options;
        return switch (action) {
            case EditorAction.Insert insert -> EditorResult.unchanged(
                    insert(state, opts, insert.text()));
            case EditorAction.Paste paste -> EditorResult.unchanged(
                    insert(state, opts, sanitize(paste.text(), opts)));
            case EditorAction.Backspace backspace -> EditorResult.unchanged(backspace(state));
            case EditorAction.Delete delete -> EditorResult.unchanged(delete(state));
            case EditorAction.MoveLeft left -> EditorResult.unchanged(
                    moveHorizontal(state, -1));
            case EditorAction.MoveRight right -> EditorResult.unchanged(
                    moveHorizontal(state, 1));
            case EditorAction.MoveUp up -> EditorResult.unchanged(moveVertical(state, -1));
            case EditorAction.MoveDown down -> EditorResult.unchanged(moveVertical(state, 1));
            case EditorAction.MoveHome home -> EditorResult.unchanged(moveLineEdge(state, true));
            case EditorAction.MoveEnd end -> EditorResult.unchanged(moveLineEdge(state, false));
            case EditorAction.SelectAll all -> EditorResult.unchanged(selectAll(state));
            case EditorAction.Newline newline -> EditorResult.unchanged(insert(state, opts, "\n"));
            case EditorAction.Submit submit -> new EditorResult(state,
                    new EditorIntent.Submit(state.text()));
            case EditorAction.Copy copy -> new EditorResult(state,
                    new EditorIntent.Copy(opts.canCopy() ? copyText(state) : ""));
            case EditorAction.PasteRequest request -> new EditorResult(state,
                    new EditorIntent.PasteRequest());
        };
    }

    /**
     * The selection, or the whole draft when nothing is selected.
     *
     * <p>This is the raw text: it does not consider masking, so a password or hidden draft is
     * returned verbatim. Callers that must honour {@code canCopy} use {@link #copyableText}
     * instead, which is what the {@code Copy} action uses.</p>
     */
    public static String copyText(EditorState state) {
        return state.hasSelection() ? state.selection() : state.text();
    }

    private static String sanitize(String text, EditorOptions options) {
        return PasteSanitizer.sanitize(text, options.pasteMode(), options.collapsePasteBlankLines());
    }

    /**
     * Inserts text at the caret or over the selection. Line endings are normalized to the editor's
     * line break (LF), matching the paste sanitizer and the decoder, so a raw carriage return never
     * sits in the draft as an invisible offset that does not break a line.
     */
    private static EditorState insert(EditorState state, EditorOptions options, String raw) {
        if (raw.isEmpty()) {
            return state;
        }
        int start = state.hasSelection() ? state.selectionStart() : state.caret();
        int end = state.hasSelection() ? state.selectionEnd() : state.caret();
        int lengthAfterRemoval = state.text().length() - (end - start);
        String insert = LineBreaks.normalize(raw);
        OptionalInt limit = options.maxLength();
        if (limit.isPresent()) {
            int room = Math.max(0, limit.getAsInt() - lengthAfterRemoval);
            insert = truncateUtf16(insert, room);
        }
        if (insert.isEmpty()) {
            return state;
        }
        String text = state.text().substring(0, start) + insert + state.text().substring(end);
        return edited(text, start + insert.length());
    }

    /**
     * The state after an edit leaves the caret at {@code caret} in {@code text}.
     *
     * <p>An edit can join the surviving text on either side of the removed range into one grapheme,
     * so the deletion point may sit inside a cluster. Keep the caret at the end of that cluster -
     * the nearest representable position - rather than letting {@link EditorState} round the
     * interior offset back before the base the deletion did not remove.</p>
     */
    private static EditorState edited(String text, int caret) {
        if (!TextMeasure.isBoundary(text, caret)) {
            caret = TextMeasure.boundaryAfter(text, caret);
        }
        return new EditorState(text, caret, caret, 0);
    }

    /** Truncates to at most {@code maxUnits} UTF-16 units without splitting a grapheme. */
    private static String truncateUtf16(String text, int maxUnits) {
        if (maxUnits <= 0) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (String cluster : TextMeasure.graphemes(text)) {
            if (used + cluster.length() > maxUnits) {
                break;
            }
            kept.append(cluster);
            used += cluster.length();
        }
        return kept.toString();
    }

    private static EditorState backspace(EditorState state) {
        if (state.hasSelection()) {
            return deleteSelection(state);
        }
        if (state.caret() == 0) {
            return state;
        }
        int previous = TextMeasure.previousBoundary(state.text(), state.caret());
        String text = state.text().substring(0, previous) + state.text().substring(state.caret());
        return edited(text, previous);
    }

    private static EditorState delete(EditorState state) {
        if (state.hasSelection()) {
            return deleteSelection(state);
        }
        if (state.caret() >= state.text().length()) {
            return state;
        }
        int next = TextMeasure.boundaryAfter(state.text(), state.caret());
        String text = state.text().substring(0, state.caret()) + state.text().substring(next);
        return edited(text, state.caret());
    }

    private static EditorState deleteSelection(EditorState state) {
        int start = state.selectionStart();
        int end = state.selectionEnd();
        String text = state.text().substring(0, start) + state.text().substring(end);
        return edited(text, start);
    }

    private static EditorState moveHorizontal(EditorState state, int direction) {
        int caret = direction < 0
                ? TextMeasure.previousBoundary(state.text(), state.caret())
                : TextMeasure.boundaryAfter(state.text(), state.caret());
        return new EditorState(state.text(), caret, caret, 0);
    }

    private static EditorState moveVertical(EditorState state, int direction) {
        int line = state.lineAt(state.caret());
        int lineStart = state.lineStart(line);
        // A vertical move keeps the column the caret was at on the line it came from, even when a
        // shorter line in between clamps it; horizontal moves and edits reset the preference.
        int column = state.preferredColumn() > 0
                ? state.preferredColumn()
                : visualColumn(state.text(), lineStart, state.caret());
        int targetLine = Math.max(0, Math.min(state.lineCount() - 1, line + direction));
        int targetStart = state.lineStart(targetLine);
        int targetEnd = state.lineEnd(targetLine);
        int caret = indexAtColumn(state.text(), targetStart, targetEnd, column);
        return new EditorState(state.text(), caret, caret, column);
    }

    private static EditorState moveLineEdge(EditorState state, boolean toStart) {
        int line = state.lineAt(state.caret());
        int caret = toStart ? state.lineStart(line) : state.lineEnd(line);
        return new EditorState(state.text(), caret, caret, 0);
    }

    private static EditorState selectAll(EditorState state) {
        if (state.text().isEmpty()) {
            return state;
        }
        return new EditorState(state.text(), state.text().length(), 0, 0);
    }

    /**
     * Visual column of {@code index} on its line: wide glyphs count their real width and a tab
     * counts the columns the text view paints for it.
     */
    private static int visualColumn(String text, int lineStart, int index) {
        int end = Math.max(lineStart, Math.min(index, text.length()));
        int used = 0;
        for (String cluster : TextMeasure.graphemes(text.substring(lineStart, end))) {
            used += visualWidth(cluster, used);
        }
        return used;
    }

    /** Caret offset at {@code column} visual columns, stopping before a glyph that would overshoot. */
    private static int indexAtColumn(String text, int lineStart, int lineEnd, int column) {
        int position = lineStart;
        int used = 0;
        for (String cluster : TextMeasure.graphemes(text.substring(lineStart, lineEnd))) {
            int next = position + cluster.length();
            int width = visualWidth(cluster, used);
            if (used + width > column) {
                break;
            }
            used += width;
            position = next;
        }
        return position;
    }

    /**
     * Columns one cluster occupies after {@code used} columns of its line. A tab advances to the
     * next four-column stop, exactly as the text view paints the draft; measuring it as one column
     * would move the caret to a column it is not drawn at.
     */
    private static int visualWidth(String cluster, int used) {
        if ("\t".equals(cluster)) {
            return TabStops.WIDTH - (used % TabStops.WIDTH);
        }
        return TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
    }

    // ── view ─────────────────────────────────────────────────────────────────

    /**
     * Builds the editor view. The returned value declares focus identity and input handling; the
     * application maps each produced {@link EditorAction} into its own message type.
     */
    public static <Msg> View<Msg> view(ElementId id, EditorState state, EditorOptions options,
            boolean focused, Function<EditorAction, Msg> map) {
        EditorOptions opts = options == null ? EditorOptions.defaults() : options;
        View.InputHandler<Msg> handler = event -> actionFor(event, opts).map(map);
        return Views.focus(id, focused, List.of(), handler,
                Views.text(render(state, opts, focused)));
    }

    /**
     * Pure text rendering, shared by the native view and headless tests.
     *
     * <p>The prompt marks the input area once, at the first visible line, and every other line is
     * indented by its painted width. It deliberately does not follow the caret: doing so repeats
     * the prompt whenever a new line is added to a multi-line draft, which reads as a second caret
     * beside the block cursor. The prompt itself is one line: {@link EditorOptions} turns a break
     * in it into a space, so the painted rows stay the window's rows.</p>
     */
    public static StyledText render(EditorState state, EditorOptions options, boolean focused) {
        EditorOptions opts = options == null ? EditorOptions.defaults() : options;
        int lines = state.lineCount();
        int visible = Math.min(opts.maxVisibleLines(), lines);
        int caretLine = state.lineAt(state.caret());
        int first = Math.max(0, Math.min(caretLine - visible + 1, lines - visible));
        // The prompt is painted by the text view, which expands a tab to the next four-column
        // stop, so the blank stand-in on continuation lines uses the painted width.
        int promptWidth = TextMeasure.columns(
                TabStops.expand(StyledText.plain(opts.prompt())).plainText());
        StyledText result = StyledText.empty();
        int start = state.lineStart(first);
        for (int line = first; line < first + visible; line++) {
            if (line > first) {
                result = result.append("\n");
            }
            int newline = state.text().indexOf('\n', start);
            int end = newline < 0 ? state.text().length() : newline;
            result = result.append(renderLine(state, opts, focused, start, end,
                    line == first, promptWidth));
            start = end + 1;
        }
        return result;
    }

    private static StyledText renderLine(EditorState state, EditorOptions opts, boolean focused,
            int start, int end, boolean firstLine, int promptWidth) {
        StyledText out = StyledText.empty();
        if (!opts.prompt().isEmpty()) {
            if (firstLine) {
                out = out.append(StyledText.of(opts.prompt(), opts.promptStyle()));
            } else {
                out = out.append(StyledText.of(" ".repeat(promptWidth), opts.promptStyle()));
            }
        }
        String text = state.text().substring(start, end);
        boolean masking = opts.masking() != EditorOptions.Masking.NONE;
        int selectionStart = state.hasSelection() ? state.selectionStart() : -1;
        int selectionEnd = state.hasSelection() ? state.selectionEnd() : -1;

        StringBuilder pending = new StringBuilder(Math.max(16, end - start));
        Style pendingStyle = opts.textStyle();
        int position = start;
        // Painted columns before this line's draft: the prompt, or the blank stand-in that keeps
        // later lines aligned. A tab advances to the next stop from there, exactly as the text view
        // expands it, so the caret's block can cover the cells the draft really occupies.
        int used = promptWidth;
        // Segment this line once. A per-cluster boundaryAfter() over the whole draft is O(n^2) and
        // cost tens of milliseconds per frame on a draft at the editor's length limit; the line's
        // own grapheme boundaries are the draft's, because a line starts and ends on a newline
        // boundary.
        for (String cluster : TextMeasure.graphemes(text)) {
            int next = position + cluster.length();
            boolean selected = selectionStart >= 0 && position >= selectionStart && position < selectionEnd;
            String display = opts.masking() == EditorOptions.Masking.HIDDEN ? "" : masking ? MASK : cluster;
            boolean cursor = focused && state.caret() == position;
            // The cells this cluster occupies where it is painted. A tab is the gap to its stop, so
            // measuring it as one column (as the masking glyph is) would disagree with the text
            // view, and with {@link #visualWidth}, which already counts it that way for movement.
            int cells = masking ? 1 : visualWidth(cluster, used);
            if (cursor) {
                // The block covers the grapheme under the caret. A two-column grapheme, and a tab
                // reaching its stop, occupy those cells; one block would leave the line short and
                // slide everything after the caret to the left.
                cells = Math.max(1, cells);
                display = CURSOR.repeat(cells);
            }
            Style style = selected ? opts.selectionStyle() : opts.textStyle();
            if (!display.isEmpty()) {
                if (style.equals(pendingStyle)) {
                    pending.append(display);
                } else {
                    out = out.append(StyledText.of(pending.toString(), pendingStyle));
                    pending.setLength(0);
                    pending.append(display);
                    pendingStyle = style;
                }
                used += cells;
            }
            position = next;
        }
        if (focused && state.caret() == end) {
            pending.append(CURSOR);
        }
        out = out.append(StyledText.of(pending.toString(), pendingStyle));
        return out;
    }

    // ── input mapping ────────────────────────────────────────────────────────

    /**
     * Maps one normalized input event to an editor action, or empty when unhandled.
     *
     * <p>A configured copy or paste key wins over printable text, so a shift+letter binding remains
     * reachable.</p>
     */
    public static Optional<EditorAction> actionFor(InputEvent event, EditorOptions options) {
        EditorOptions opts = options == null ? EditorOptions.defaults() : options;
        if (event instanceof InputEvent.PasteInput paste) {
            return Optional.of(new EditorAction.Paste(paste.text()));
        }
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        Key key = keyInput.key();
        String name = key.name().toLowerCase(Locale.ROOT);
        boolean plainModifiers = key.modifiers().none()
                || (key.modifiers().shift() && !key.modifiers().ctrl() && !key.modifiers().alt());

        // The configured copy and paste keys are matched before printable text. Otherwise a
        // binding such as shift+c, which also carries text, could never fire: the gesture would
        // always insert instead.
        if (key.equals(opts.copyKey())) {
            return Optional.of(new EditorAction.Copy());
        }
        if (key.equals(opts.pasteKey())) {
            return Optional.of(new EditorAction.PasteRequest());
        }
        if (!keyInput.text().isEmpty() && plainModifiers) {
            return Optional.of(new EditorAction.Insert(keyInput.text()));
        }
        if (key.modifiers().ctrl() && !key.modifiers().alt()) {
            if ("j".equals(name)) {
                return Optional.of(new EditorAction.Newline());
            }
            if ("a".equals(name)) {
                return Optional.of(new EditorAction.SelectAll());
            }
            return Optional.empty();
        }
        return switch (name) {
            case "enter" -> Optional.of(new EditorAction.Submit());
            case "backspace" -> Optional.of(new EditorAction.Backspace());
            case "delete" -> Optional.of(new EditorAction.Delete());
            case "left" -> Optional.of(new EditorAction.MoveLeft());
            case "right" -> Optional.of(new EditorAction.MoveRight());
            case "up" -> Optional.of(new EditorAction.MoveUp());
            case "down" -> Optional.of(new EditorAction.MoveDown());
            case "home" -> Optional.of(new EditorAction.MoveHome());
            case "end" -> Optional.of(new EditorAction.MoveEnd());
            default -> Optional.empty();
        };
    }

    /** All logical lines of a draft, used by projections that need the line count. */
    public static List<String> lines(EditorState state) {
        List<String> lines = new ArrayList<>(state.lineCount());
        int start = 0;
        int newline;
        while ((newline = state.text().indexOf('\n', start)) >= 0) {
            lines.add(state.text().substring(start, newline));
            start = newline + 1;
        }
        lines.add(state.text().substring(start));
        return lines;
    }

    /** Visible line count for layout projections. */
    public static int visibleLineCount(EditorState state, EditorOptions options) {
        EditorOptions opts = options == null ? EditorOptions.defaults() : options;
        return Math.max(1, Math.min(opts.maxVisibleLines(), state.lineCount()));
    }

    /** Convenience: number of visible lines under the defaults. */
    public static int visibleLineCount(EditorState state) {
        return visibleLineCount(state, EditorOptions.defaults());
    }

    /** Convenience: the selection or the whole draft, honoring masking through {@code canCopy}. */
    public static Optional<String> copyableText(EditorState state, EditorOptions options) {
        EditorOptions opts = options == null ? EditorOptions.defaults() : options;
        return opts.canCopy() ? Optional.of(copyText(state)) : Optional.empty();
    }
}
