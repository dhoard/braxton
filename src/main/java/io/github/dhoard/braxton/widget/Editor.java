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
            case EditorAction.MoveUp up -> EditorResult.unchanged(moveVertical(state, opts, -1));
            case EditorAction.MoveDown down -> EditorResult.unchanged(moveVertical(state, opts, 1));
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
     * line break (LF), matching the paste sanitizer, the decoder, and the draft {@link EditorState}
     * is given, so a raw carriage return never sits in the draft as an invisible offset that does
     * not break a line.
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

    private static EditorState moveVertical(EditorState state, EditorOptions options, int direction) {
        int line = state.lineAt(state.caret());
        int lineStart = state.lineStart(line);
        // Every visible draft line is painted after the prompt, or after the blank stand-in of its
        // width, so the caret's column is measured from there: a tab in the draft reaches the same
        // stop the text view paints for it. Measuring from column 0 moves the caret to a column it
        // is not drawn at, by the prompt's width modulo the tab stop.
        String prompt = paintedPrompt(options);
        // The column is counted the way the row is painted, so a masked draft is measured one cell
        // per cluster: its rows show mask glyphs, never the cleartext widths, and a move aimed at a
        // cleartext column draws the block columns right of where it was last drawn. A hidden row
        // draws no glyph before its block - the block sits at the prompt's column wherever the caret
        // is - so it paints no column for a move to agree with; counting the masked cells is what
        // keeps a vertical move returning the caret to the cluster the masked layout names.
        boolean masking = options.masking() != EditorOptions.Masking.NONE;
        // A vertical move keeps the column the caret was at on the line it came from, even when a
        // shorter line in between clamps it; horizontal moves and edits reset the preference.
        int column = state.preferredColumn() > 0
                ? state.preferredColumn()
                : visualColumn(state.text(), lineStart, state.caret(),
                        prefixOf(state, options, prompt, line), masking);
        int targetLine = Math.max(0, Math.min(state.lineCount() - 1, line + direction));
        int targetStart = state.lineStart(targetLine);
        int targetEnd = state.lineEnd(targetLine);
        // The row the block lands on carries its own prefix: only the first line of the window
        // paints the prompt, and the rest the blank stand-in. The cell a draft cluster joins can
        // therefore differ between the row the caret left and the row it arrives on.
        int caret = indexAtColumn(state.text(), targetStart, targetEnd, column,
                prefixOf(state, options, prompt, targetLine), masking);
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
     * Painted column of {@code index} on its line: the columns the row draws before it, so the count
     * starts at {@code prefix} - the prompt the first visible line carries, or the blank stand-in of
     * its width - and a tab counts the columns the text view paints for it. Wide glyphs count their
     * real width, and a masked line counts one cell per cluster because the mask glyph is all that
     * row draws.
     */
    private static int visualColumn(String text, int lineStart, int index, PaintedPrefix prefix,
            boolean masking) {
        int end = Math.max(lineStart, Math.min(index, text.length()));
        int used = prefix.columns();
        PaintedPrefix joinable = prefix;
        for (String cluster : TextMeasure.graphemes(text.substring(lineStart, end))) {
            used += paintedCells(cluster, used, masking, joinable);
            joinable = cellBehind(cluster, masking);
        }
        return used;
    }

    /**
     * Caret offset at {@code column} painted columns, stopping before a glyph that would overshoot.
     * The row is counted from {@code prefix} for the same reason {@link #visualColumn} is.
     */
    private static int indexAtColumn(String text, int lineStart, int lineEnd, int column,
            PaintedPrefix prefix, boolean masking) {
        int position = lineStart;
        int used = prefix.columns();
        PaintedPrefix joinable = prefix;
        for (String cluster : TextMeasure.graphemes(text.substring(lineStart, lineEnd))) {
            int width = paintedCells(cluster, used, masking, joinable);
            joinable = cellBehind(cluster, masking);
            if (used + width > column) {
                break;
            }
            used += width;
            position += cluster.length();
        }
        return position;
    }

    /**
     * Cells one cluster occupies on a painted row, after {@code used} cells of that row. The renderer
     * and the caret's column arithmetic share this rule so a move can never aim at a column its row
     * does not draw: a masked row draws one glyph per cluster, however wide the cleartext underneath
     * it is, and an unmasked row occupies what {@link #visualWidth} measures - except for a cluster
     * the row paints inside the cell before it: the one that opens the draft, which joins the cell
     * its prefix ends with whenever it has no base character of its own, and the clusters behind a
     * tab, which join the space that stop ends with ({@link #cellBehind}).
     */
    private static int paintedCells(String cluster, int used, boolean masking, PaintedPrefix prefix) {
        if (masking) {
            return 1;
        }
        String cell = prefix.cell();
        if (cell != null && TextMeasure.graphemeCount(cell + cluster) == 1
                && (!prefix.tabStop() || TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED) == 0)) {
            // The cluster continues the cell the row already painted, so it costs the columns that
            // cell gains or loses - the join LayoutEngine makes when a span boundary splits one
            // cluster of the row's plain text. A bare combining mark adds no column this way, and a
            // lone variation selector adds the column it gives the base it joins. Where the cell is
            // the space a tab reached, only the clusters that claim no column join it: a bare mark
            // there keeps the column it measures, the dotted circle standing in for its base.
            return TextMeasure.clusterColumns(cell + cluster, WidthPolicy.PINNED)
                    - TextMeasure.clusterColumns(cell, WidthPolicy.PINNED);
        }
        return visualWidth(cluster, used);
    }

    /**
     * The cell the row leaves open to the cluster behind {@code cluster}, or none. A tab reaches its
     * stop as spaces, and those spaces are the row's own plain text, so it re-segments the stop's
     * last space with the clusters behind the tab: they continue that space exactly as they would a
     * typed one, and only the clusters claiming no column of their own join it - the column a
     * selector adds there is reserved with the stop, as {@code LayoutEngine} does. A masked row
     * draws one mask glyph for the tab instead of its spaces, and that glyph is a base of its own.
     *
     * <p>Every other cluster is a whole cluster of the draft's own text, and the segmentation the
     * paint path reads for the row is that same one, so nothing follows it into its cell.</p>
     */
    private static PaintedPrefix cellBehind(String cluster, boolean masking) {
        if (!masking && "\t".equals(cluster)) {
            // No columns of its own: the stop's columns were already charged to the tab.
            return new PaintedPrefix(0, " ", true);
        }
        return NO_PREFIX;
    }

    /**
     * The prefix one painted row carries: {@code columns} drawn before its draft and the {@code cell}
     * that run ends with, or {@code null} when the row draws no prefix at all. {@link #cellBehind}
     * builds the same shape for the last space of a stop inside the draft; it carries no columns of
     * its own, because those were charged to the tab.
     *
     * <p>{@code tabStop} marks the cell a tab reached, in the prompt or in the draft. The text view
     * continues that space only with clusters that claim no column of their own, so a cluster with
     * columns of its own keeps its own cell after a stop, while a prompt's ordinary last cluster takes
     * the mark into the cell it is painted in.</p>
     *
     * <p>Only the cluster that opens a draft can join the prefix's cell, and the clusters behind a tab
     * can join the cell that stop ends with. The rest are whole clusters of the draft's own text, and
     * the segmentation the paint path reads for the row is that same one, so none of them continues
     * the cell before it.</p>
     */
    private record PaintedPrefix(int columns, String cell, boolean tabStop) {
    }

    private static final PaintedPrefix NO_PREFIX = new PaintedPrefix(0, null, false);

    /** The prefix of {@code line} in the window the editor paints while the caret is on that line. */
    private static PaintedPrefix prefixOf(EditorState state, EditorOptions options, String prompt,
            int line) {
        return line == firstVisibleLine(state, options, line)
                ? promptPrefix(options, prompt)
                : indentPrefix(prompt);
    }

    /** The prefix the first visible line paints: the prompt as the text view draws it. */
    private static PaintedPrefix promptPrefix(EditorOptions options, String prompt) {
        int columns = TextMeasure.columns(prompt);
        if (columns == 0) {
            // Nothing is drawn before the draft, so its first cluster claims a cell of its own.
            return NO_PREFIX;
        }
        List<String> clusters = TextMeasure.graphemes(prompt);
        String cell = clusters.get(clusters.size() - 1);
        return new PaintedPrefix(columns, cell, options.prompt().endsWith("\t"));
    }

    /** The blank stand-in every other visible line paints in place of the prompt. */
    private static PaintedPrefix indentPrefix(String prompt) {
        int columns = TextMeasure.columns(prompt);
        // A run of typed blanks, so its last cell is one space and a mark joins it as any other.
        return columns == 0 ? NO_PREFIX : new PaintedPrefix(columns, " ", false);
    }

    /** The first line of the window {@link #render} paints for a caret on {@code caretLine}. */
    private static int firstVisibleLine(EditorState state, EditorOptions options, int caretLine) {
        int lines = state.lineCount();
        int visible = Math.min(options.maxVisibleLines(), lines);
        return Math.max(0, Math.min(caretLine - visible + 1, lines - visible));
    }

    /**
     * Columns one cluster occupies after {@code used} columns of its line. A tab advances to the
     * next four-column stop, exactly as the text view paints the draft; measuring it as one column
     * would move the caret to a column it is not drawn at. A masked row never reaches this rule: it
     * draws one glyph per cluster, {@link #paintedCells}.
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
        int first = firstVisibleLine(state, opts, caretLine);
        // The prompt is painted by the text view, which expands a tab to the next four-column
        // stop, so the blank stand-in on continuation lines uses the painted width.
        String prompt = paintedPrompt(opts);
        PaintedPrefix promptPrefix = promptPrefix(opts, prompt);
        PaintedPrefix indentPrefix = indentPrefix(prompt);
        StyledText result = StyledText.empty();
        int start = state.lineStart(first);
        for (int line = first; line < first + visible; line++) {
            if (line > first) {
                result = result.append("\n");
            }
            int newline = state.text().indexOf('\n', start);
            int end = newline < 0 ? state.text().length() : newline;
            result = result.append(renderLine(state, opts, focused, start, end, line == first,
                    line == first ? promptPrefix : indentPrefix));
            start = end + 1;
        }
        return result;
    }

    /**
     * The prompt as the text view paints it, with its tabs expanded to the next four-column stop.
     * Every visible draft line carries it, continuation lines as the blank stand-in of its width,
     * so the caret's column arithmetic and the painted row start from one prefix.
     */
    private static String paintedPrompt(EditorOptions options) {
        return TabStops.expand(StyledText.plain(options.prompt())).plainText();
    }

    private static StyledText renderLine(EditorState state, EditorOptions opts, boolean focused,
            int start, int end, boolean firstLine, PaintedPrefix prefix) {
        StyledText out = StyledText.empty();
        if (!opts.prompt().isEmpty()) {
            if (firstLine) {
                out = out.append(StyledText.of(opts.prompt(), opts.promptStyle()));
            } else {
                out = out.append(StyledText.of(" ".repeat(prefix.columns()), opts.promptStyle()));
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
        int used = prefix.columns();
        // The cell that prefix ends with, which the first cluster of the draft is painted inside
        // when it has no base of its own; a tab leaves the same opening at the last space of its
        // stop, which cellBehind carries to the cluster behind it. Where the row draws its own glyph
        // in place of the cluster - the block cursor, or a mask glyph - that glyph, not the cell
        // before it, is the base the cluster sits on.
        PaintedPrefix joinable = prefix;
        // Segment this line once. A per-cluster boundaryAfter() over the whole draft is O(n^2) and
        // cost tens of milliseconds per frame on a draft at the editor's length limit; the line's
        // own grapheme boundaries are the draft's, because a line starts and ends on a newline
        // boundary.
        for (String cluster : TextMeasure.graphemes(text)) {
            int next = position + cluster.length();
            boolean selected = selectionStart >= 0 && position >= selectionStart && position < selectionEnd;
            String display = opts.masking() == EditorOptions.Masking.HIDDEN ? "" : masking ? MASK : cluster;
            boolean cursor = focused && state.caret() == position;
            // The cells this cluster occupies where it is painted: one mask glyph, or the gap a tab
            // advances to its stop. The caret's column arithmetic uses the same rule, so a vertical
            // move returns the block to the cell this line draws it in.
            int cells = paintedCells(cluster, used, masking, cursor ? NO_PREFIX : joinable);
            joinable = cellBehind(cluster, masking);
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
