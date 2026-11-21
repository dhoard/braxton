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

import static io.github.dhoard.braxton.widget.Editor.CURSOR;
import static io.github.dhoard.braxton.widget.Editor.MASK;

import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.TextWrap;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.Editor.Caret;
import io.github.dhoard.braxton.widget.Editor.Model;
import io.github.dhoard.braxton.widget.Editor.Msg;
import io.github.dhoard.braxton.widget.Editor.Presentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Editor cell projection, hit testing, and rendering.
 */
final class EditorRendering {

    /**
     * The largest wrap width. The column arithmetic adds a row's first column to it, so it stays far
     * inside the {@code int} range: an unbounded row must never be able to overflow into an early
     * break.
     */
    static final int UNBOUNDED = Integer.MAX_VALUE / 2;

    /**
     * The frame one editor paints in: the columns before every row's draft, the cells one row's
     * draft may occupy, and the cell each kind of row leaves open to its first cluster.
     *
     * @param prefix the columns painted before every row: the gutter and the prompt
     * @param wrap the cells one row's draft may occupy, or {@link #UNBOUNDED}
     * @param promptCell the text of the cell the prompt ends with, or empty when it paints none
     * @param promptTabStop whether that cell is the space a tab stop ended with
     */
    record Frame(int prefix, int wrap, String promptCell, boolean promptTabStop) {}

    /**
     * One painted row, with the logical line it belongs to.
     *
     * @param line the logical line the row shows
     * @param start the first document offset the row shows
     * @param end the offset just past the last one it shows
     * @param columns the cells the row occupies
     */
    record RowAt(int line, int start, int end, int columns) {}

    /**
     * The rows one frame paints, and where the caret sits among them.
     *
     * @param rows the rows, top to bottom
     * @param caretRow the index of the row the caret is drawn on
     * @param firstLine the line the window starts at, which is the one carrying the prompt
     * @param frame the frame the rows were projected in
     * @param focused whether the editor holds focus, which is what a cursor line answers to
     * @param blockPainted whether the caret block is part of the painted rows
     */
    record Painted(List<RowAt> rows, int caretRow, int firstLine, Frame frame, boolean focused, boolean blockPainted) {}

    /**
     * How a painted row charges its clusters.
     */
    static VisualRows.Cells paintCells(EditorConfig options) {
        return switch (options.masking()) {
            case NONE -> VisualRows.Cells.NATURAL;
            case PASSWORD -> VisualRows.Cells.MASKED;
            case HIDDEN -> VisualRows.Cells.HIDDEN;
        };
    }

    /**
     * How a movement charges its clusters.
     *
     * <p>A hidden row paints no glyph before its block - the block sits at the prompt's column
     * wherever the caret is - so it paints no column for a move to agree with; counting the masked
     * cells is what keeps a vertical move returning the caret to the cluster the masked layout
     * names. A movement therefore never counts {@link VisualRows.Cells#HIDDEN}.</p>
     */
    static VisualRows.Cells movementCells(EditorConfig options) {
        return options.masking() == EditorConfig.Masking.NONE ? VisualRows.Cells.NATURAL : VisualRows.Cells.MASKED;
    }

    /**
     * The frame the editor paints in.
     *
     * <p>The declared width is what vertical movement and a dynamic height are computed for; the
     * space the view is actually given wins when it is narrower, so a resized pane still paints rows
     * that fit and puts its caret on the cell it drew. A width of zero means the caller knows of
     * none - the headless entry points - so a declared width is used alone.</p>
     */
    static Frame frame(Model state, EditorConfig options, int allocatedCols) {
        int gutter = gutterColumns(state, options);
        String prompt = paintedPrompt(options);
        int prefix = gutter + TextMeasure.columns(prompt);
        int declared = options.wrapWidth().orElse(0);
        int width = declared > 0 ? (allocatedCols > 0 ? Math.min(declared, allocatedCols) : declared) : allocatedCols;
        int wrap = width > 0 ? Math.max(1, width - prefix) : UNBOUNDED;
        // With nothing painted before a row's draft there is no cell for its first cluster to join,
        // so the blank stand-in only opens one when it actually occupies columns.
        VisualRows.Open open = promptOpen(options, prompt);
        return new Frame(prefix, wrap, open.cell(), open.tabStop());
    }

    /**
     * The columns a line-number gutter occupies, or zero when no gutter is painted.
     *
     * <p>The widest number plus one separator column, so the draft of every row starts at the same
     * column and a wrapped row aligns under the line it continues.</p>
     */
    static int gutterColumns(Model state, EditorConfig options) {
        if (!options.presentation().lineNumbers()) {
            return 0;
        }
        return Integer.toString(Math.max(1, state.lineCount())).length() + 1;
    }

    /**
     * The cell the row carrying the prompt leaves open to the draft's first cluster.
     *
     * <p>A prompt ending in a base character and a prompt ending in a tab's space open different
     * cells: the text view continues a stop's space only with the clusters that claim no column of
     * their own, while an ordinary last cluster takes a bare mark into the cell it is painted in.
     * That is the cell the paint path reads for the row's first cluster, so the caret's arithmetic
     * reads the same one.</p>
     */
    static VisualRows.Open promptOpen(EditorConfig options, String prompt) {
        List<String> clusters = TextMeasure.graphemes(prompt);
        if (clusters.isEmpty()) {
            // Nothing is drawn before the draft, so its first cluster claims a cell of its own.
            return VisualRows.CLOSED;
        }
        return new VisualRows.Open(
                clusters.get(clusters.size() - 1), options.prompt().endsWith("\t"));
    }

    /**
     * The cell the row of {@code line} leaves open to its first cluster: the prompt's on the window's
     * first line, the blank stand-in's on every other.
     */
    static VisualRows.Open openAt(int line, int firstLine, Frame frame) {
        if (line == firstLine) {
            return frame.promptCell().isEmpty()
                    ? VisualRows.CLOSED
                    : new VisualRows.Open(frame.promptCell(), frame.promptTabStop());
        }
        return indentOpen(frame);
    }

    /**
     * The cell a row painted after the blank stand-in leaves open. Nothing is painted before a row's
     * draft when the editor has no gutter and no prompt, so the row opens no cell at all.
     */
    static VisualRows.Open indentOpen(Frame frame) {
        return frame.prefix() == 0 ? VisualRows.CLOSED : new VisualRows.Open(" ", false);
    }

    /**
     * The height budget one frame paints into: the content's rows for a dynamic height, or the
     * configured visible lines, and never more than the view was allocated.
     */
    static int budget(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            EditorConfig options,
            Frame frame,
            VisualRows.Cells cells,
            int allocatedRows) {
        Presentation look = options.presentation();
        // A dynamic height is the content's own height, so it has to be counted before the window is
        // chosen and the count stops at the row the policy will not grow past. A fixed height needs
        // no count at all: the window is as tall as the policy says, and a short draft leaves the
        // rows it did not fill unused.
        int height = look.dynamicHeight()
                ? Math.max(look.minLines(), Math.min(look.maxLines(), contentRows(cache, state, options, frame, cells)))
                : options.maxVisibleLines();
        height = Math.max(1, height);
        return allocatedRows > 0 ? Math.max(1, Math.min(height, allocatedRows)) : height;
    }

    /**
     * How many rows the whole draft needs, counted no further than the policy can paint.
     *
     * <p>The count stops at the largest number of rows that can affect the height: a document a
     * million lines long is never measured whole on every frame, and a draft inside one enormous
     * line costs one projection of that line, which is what painting it costs anyway.</p>
     */
    static int contentRows(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            EditorConfig options,
            Frame frame,
            VisualRows.Cells cells) {
        Presentation look = options.presentation();
        int cap = look.maxContentLines();
        int total = 0;
        for (int line = 0; line < state.lineCount() && total < cap; line++) {
            total += plainRows(cache, state, frame, cells, line).size();
        }
        return Math.min(total, cap);
    }

    /**
     * The rows one line breaks into with the blank stand-in's prefix and no caret block, memoized for
     * one frame: the counting pass, the window search and the painting pass would otherwise project
     * the same line more than once.
     */
    static List<VisualRows.Row> plainRows(
            Map<Integer, List<VisualRows.Row>> cache, Model state, Frame frame, VisualRows.Cells cells, int line) {
        if (frame.wrap() == UNBOUNDED) {
            // One row per line, which needs no projection at all: nothing can break, so the row is
            // the line. The unwrapped path - a policy with no declared width - costs what it did
            // before rows existed.
            return trivialRow(state, line);
        }
        return cache.computeIfAbsent(
                line,
                key -> VisualRows.project(
                        state.text(),
                        state.lineStart(key),
                        state.lineEnd(key),
                        frame.prefix(),
                        frame.wrap(),
                        cells,
                        indentOpen(frame),
                        -1));
    }

    /**
     * The rows one line breaks into, projected with the prefix the line carries in its window.
     *
     * <p>The window's first line paints the prompt and every other line the blank stand-in, and the
     * two open different cells to the draft's first cluster; the caret's own line is charged the
     * block. A line that needs neither is the one the cache holds.</p>
     */
    static List<VisualRows.Row> windowRows(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            EditorConfig options,
            Frame frame,
            VisualRows.Cells cells,
            int line,
            int firstLine,
            int caretOffset) {
        if (frame.wrap() == UNBOUNDED) {
            // A line that cannot wrap is one row whatever prefix it carries and wherever the caret
            // is, so the prefix and the block change no boundary.
            return trivialRow(state, line);
        }
        VisualRows.Open open = openAt(line, firstLine, frame);
        int caret = line == state.lineAt(state.caret()) ? caretOffset : -1;
        if (caret < 0 && open.equals(indentOpen(frame))) {
            return plainRows(cache, state, frame, cells, line);
        }
        return VisualRows.project(
                state.text(),
                state.lineStart(line),
                state.lineEnd(line),
                frame.prefix(),
                frame.wrap(),
                cells,
                open,
                caret);
    }

    /**
     * The one row an unwrapped line paints.
     */
    static List<VisualRows.Row> trivialRow(Model state, int line) {
        int start = state.lineStart(line);
        return List.of(new VisualRows.Row(start, state.lineEnd(line), 0));
    }

    /**
     * The index of the row {@code offset} is drawn on: the last row that starts at or before it.
     */
    static int rowOf(List<VisualRows.Row> rows, int offset) {
        int found = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start() <= offset) {
                found = i;
            }
        }
        return found;
    }

    /**
     * Where a window starts: the line it begins at, and how many of that line's top rows it drops.
     *
     * @param firstLine the first logical line the window paints
     * @param topSkip the rows of that line the window drops, so a line taller than the window is
     *     scrolled through instead of hiding the caret
     */
    record WindowStart(int firstLine, int topSkip) {}

    /**
     * The window that keeps the caret's row visible and ends there, filling the rows still left
     * below it.
     *
     * <p>The window grows upward from the caret's row while the rows still fit. A line above that
     * does not fit whole contributes the rows at its end, which is where a viewport scrolls to; the
     * caret's own line, when it is taller than the whole window, is trimmed from the top so the
     * caret's row is the window's last.</p>
     */
    static WindowStart windowStart(
            Map<Integer, List<VisualRows.Row>> cache,
            Model state,
            Frame frame,
            VisualRows.Cells cells,
            int caretLine,
            int caretRowInLine,
            int budget) {
        int need = caretRowInLine + 1;
        int above = 0;
        int firstLine = caretLine;
        int topSkip = 0;
        for (int line = caretLine - 1; line >= 0; line--) {
            int count = plainRows(cache, state, frame, cells, line).size();
            if (above + count + need <= budget) {
                above += count;
                firstLine = line;
                continue;
            }
            int room = budget - above - need;
            if (room > 0) {
                firstLine = line;
                topSkip = count - room;
                above += room;
            }
            break;
        }
        if (above + need > budget) {
            return new WindowStart(caretLine, need - budget);
        }
        return new WindowStart(firstLine, topSkip);
    }

    /**
     * Projects the frame the editor paints: the rows to draw, and the row and column of the caret.
     *
     * <p>The window ends at the caret's row, so the caret never scrolls out of the editor, and it
     * fills the rows still left below it, which is what makes a short draft sit at the top of a
     * taller view. A caret line too tall for the whole window is trimmed from the top instead, so a
     * draft line longer than the editor still shows the caret.</p>
     *
     * @param state the editor state
     * @param options the editing and rendering policy
     * @param focused whether the editor holds focus, which paints the caret and its row
     * @param allocatedCols the columns the view was given, or zero when it was not told
     * @param allocatedRows the rows the view was given, or zero when it was not told
     * @return the frame
     */
    static Painted painted(Model state, EditorConfig options, boolean focused, int allocatedCols, int allocatedRows) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        // A painted editor puts its caret in the text it renders; a declared one is placed by the
        // layout from the same projection, which is why the block's cell is charged either way.
        boolean blockPainted = focused && opts.cursor().caret() == Caret.PAINTED;
        Frame frame = frame(state, opts, allocatedCols);
        VisualRows.Cells cells = paintCells(opts);
        Map<Integer, List<VisualRows.Row>> cache = new HashMap<>();
        int budget = budget(cache, state, opts, frame, cells, allocatedRows);
        int caret = state.caret();
        int caretOffset = blockPainted ? caret : -1;
        int caretLine = state.lineAt(caret);
        List<VisualRows.Row> caretRows = plainRows(cache, state, frame, cells, caretLine);
        int caretRowInLine = rowOf(caretRows, caret);
        WindowStart window = windowStart(cache, state, frame, cells, caretLine, caretRowInLine, budget);
        int firstLine = window.firstLine();
        int topSkip = window.topSkip();
        List<RowAt> rows = new ArrayList<>(Math.min(budget, 256));
        for (int line = firstLine; line < state.lineCount() && rows.size() < budget; line++) {
            List<VisualRows.Row> projected = windowRows(cache, state, opts, frame, cells, line, firstLine, caretOffset);
            for (int i = line == firstLine ? topSkip : 0; i < projected.size() && rows.size() < budget; i++) {
                VisualRows.Row row = projected.get(i);
                rows.add(new RowAt(line, row.start(), row.end(), row.columns()));
            }
        }
        if (rows.isEmpty()) {
            rows.add(new RowAt(caretLine, state.lineStart(caretLine), state.lineStart(caretLine), 0));
        }
        int caretRow = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start() <= caret) {
                caretRow = i;
            }
        }
        if (opts.presentation().dynamicHeight()) {
            // A dynamic height is a size the editor keeps, not a count of what the draft happens to
            // fill: below its minimum the rows that are left are painted empty, which is what makes
            // an empty textarea the height of the smallest one rather than one row tall. The caret
            // keeps the row it was on, so a blank row below it is never the caret's.
            int pad = state.lineEnd(rows.get(rows.size() - 1).line());
            while (rows.size() < budget) {
                rows.add(new RowAt(-1, pad, pad, 0));
            }
        }
        return new Painted(List.copyOf(rows), caretRow, firstLine, frame, focused, blockPainted);
    }

    /**
     * Painted column of {@code index} on the row that starts at {@code start}: the columns the row
     * draws before it, counted from {@code firstColumn} because that is where the row's own prefix
     * ends. A tab counts the columns the text view paints for it, a wide glyph its real width, and a
     * masked row one cell per cluster.
     */
    static int rowColumn(
            String text, int start, int index, int firstColumn, VisualRows.Cells cells, VisualRows.Open open) {
        int end = Math.max(start, Math.min(index, text.length()));
        int used = firstColumn;
        VisualRows.Open held = open;
        for (String cluster : Graphemes.iterate(text.substring(start, end))) {
            used += VisualRows.cells(cluster, used, cells, held);
            held = VisualRows.openBehind(cluster, cells);
        }
        return used;
    }

    /**
     * Caret offset at {@code column} painted columns in one row, stopping before a glyph that would
     * overshoot. The row is counted from {@code firstColumn} for the same reason {@link #rowColumn}
     * is.
     */
    static int indexAtColumn(
            String text,
            int start,
            int end,
            int column,
            int firstColumn,
            VisualRows.Cells cells,
            VisualRows.Open open) {
        int position = Math.max(0, Math.min(start, text.length()));
        int limit = Math.max(position, Math.min(end, text.length()));
        int used = firstColumn;
        VisualRows.Open held = open;
        for (String cluster : Graphemes.iterate(text.substring(position, limit))) {
            int width = VisualRows.cells(cluster, used, cells, held);
            held = VisualRows.openBehind(cluster, cells);
            if (used + width > column) {
                break;
            }
            used += width;
            position += cluster.length();
        }
        return position;
    }

    /**
     * The first line of the window the editor paints while the caret is on {@code caretLine}.
     *
     * <p>Used where only the prompt's prefix matters - the cell arithmetic of a vertical move - so it
     * stops at the first line, which is all {@link #openAt} needs.</p>
     */
    static int firstVisibleLine(Model state, EditorConfig options, Frame frame, int caretLine) {
        VisualRows.Cells cells = paintCells(options);
        Map<Integer, List<VisualRows.Row>> cache = new HashMap<>();
        int budget = budget(cache, state, options, frame, cells, 0);
        List<VisualRows.Row> caretRows = plainRows(cache, state, frame, cells, caretLine);
        int caretRowInLine = rowOf(caretRows, state.caret());
        return windowStart(cache, state, frame, cells, caretLine, caretRowInLine, budget)
                .firstLine();
    }

    /**
     * The message a pointer report produces, or empty when it is not an editing gesture.
     *
     * <p>A press puts the caret where the pointer was, a second press selects the word there, and
     * motion with the left button held extends the selection - which is the drag. The cell is mapped
     * through the same projection the rows were painted from, so a click lands on the cluster under
     * it across a soft wrap, after a tab, and on a two-column glyph.</p>
     *
     * <p>The geometry comes from the region the report arrived in, never from the size this handler
     * was built with: routing builds a size-aware subtree at zero columns to find the handlers in
     * it, and a caret placed from that geometry would be a caret placed nowhere.</p>
     */
    static Optional<Msg> pointerAction(
            Model state, EditorConfig options, boolean focused, Size region, PointerEvent event) {
        boolean dragging = event.isMotion() && event.button() == PointerButton.LEFT;
        if (!event.isPress() && !dragging) {
            return Optional.empty();
        }
        int offset = offsetAt(state, options, focused, region, event);
        if (dragging) {
            return Optional.of(new Msg.ExtendSelection(offset));
        }
        return Optional.of(event.clickCount() > 1 ? new Msg.SelectWord(offset) : new Msg.PlaceCaret(offset));
    }

    /**
     * The document offset one cell of the painted rows falls on.
     *
     * <p>A row outside the painted window clamps to the nearest one, so a drag that leaves the
     * editor keeps selecting instead of stopping at its edge.</p>
     */
    static int offsetAt(Model state, EditorConfig options, boolean focused, Size region, PointerEvent event) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        Painted frame = painted(state, opts, focused, region.cols(), region.rows());
        List<RowAt> rows = frame.rows();
        if (rows.isEmpty()) {
            return state.caret();
        }
        RowAt row = rows.get(Math.max(0, Math.min(event.row(), rows.size() - 1)));
        return indexAtColumn(
                state.text(),
                row.start(),
                row.end(),
                Math.max(0, event.column()),
                frame.frame().prefix(),
                paintCells(opts),
                openAt(row.line(), frame.firstLine(), frame.frame()));
    }

    /**
     * The caret as cursor intent, at the cell the draft is painted in.
     *
     * <p>The row and column come from the same projection the rows were painted from, so a declared
     * caret lands on the cell the painted block would have covered - across a soft wrap, after a
     * tab, on a two-column glyph, and in a masked or hidden field.</p>
     */
    static Cursor caret(Model state, EditorConfig options, Painted frame) {
        RowAt at = frame.rows().get(frame.caretRow());
        int column = rowColumn(
                state.text(),
                at.start(),
                state.caret(),
                frame.frame().prefix(),
                paintCells(options),
                openAt(at.line(), frame.firstLine(), frame.frame()));
        return new Cursor(
                options.cursor().shape(),
                options.cursor().mode(),
                options.cursor().strategy(),
                null,
                frame.caretRow(),
                column);
    }

    /**
     * Pure text rendering, shared by the native view and headless tests.
     *
     * <p>The prompt marks the input area once, at the first visible row, and every other row is
     * indented by its painted width. It deliberately does not follow the caret: doing so repeats
     * the prompt whenever a new line is added to a multi-line draft, which reads as a second caret
     * beside the block cursor. The prompt itself is one line: {@link EditorConfig} turns a break
     * in it into a space, so the painted rows stay the window's rows.</p>
     *
     * <p>This overload paints for the width the policy declares: a draft with no declared width is
     * one row per logical line.</p>
     *
     * @param state the editor state to paint
     * @param options the editing and rendering policy
     * @param focused whether the caret is painted
     * @return the painted rows
     */
    public static StyledText render(Model state, EditorConfig options, boolean focused) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return render(state, opts, focused, 0);
    }

    /**
     * Pure text rendering for an explicit width.
     *
     * @param state the editor state to paint
     * @param options the editing and rendering policy
     * @param focused whether the caret is painted
     * @param cols the columns the view was given, or zero to use the policy's declared width alone
     * @return the painted rows
     */
    public static StyledText render(Model state, EditorConfig options, boolean focused, int cols) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        return render(painted(state, opts, focused, cols, 0), state, opts);
    }

    /**
     * The prompt as the text view paints it, with its tabs expanded to the next four-column stop.
     * Every visible draft row carries it, continuation rows as the blank stand-in of its width, so
     * the caret's column arithmetic and the painted row start from one prefix.
     */
    static String paintedPrompt(EditorConfig options) {
        return TabStops.expand(StyledText.plain(options.prompt())).plainText();
    }

    /**
     * Paints one projected frame: a row per painted row, with its gutter, prompt and cursor line.
     */
    static StyledText render(Painted frame, Model state, EditorConfig options) {
        EditorConfig opts = options == null ? EditorConfig.defaults() : options;
        Presentation look = opts.presentation();
        String placeholder = state.text().isEmpty() ? look.placeholder() : "";
        Style cursorLine = look.cursorLineStyle();
        StyledText out = StyledText.empty();
        for (int i = 0; i < frame.rows().size(); i++) {
            if (i > 0) {
                out = out.append("\n");
            }
            RowAt at = frame.rows().get(i);
            Style rowStyle = frame.focused() && i == frame.caretRow() ? cursorLine : Style.NONE;
            out = out.append(gutter(at, frame, state, opts, rowStyle));
            out = out.append(renderRow(state, opts, at, i == 0, i == frame.caretRow(), rowStyle, placeholder, frame));
        }
        return out;
    }

    /**
     * The line-number gutter of one row.
     *
     * <p>A logical line is numbered once, on the row it starts at; a row the line was wrapped into
     * continues under a blank gutter, so the numbers name lines rather than rows and the draft stays
     * aligned under the number it belongs to.</p>
     */
    static StyledText gutter(RowAt at, Painted frame, Model state, EditorConfig opts, Style rowStyle) {
        int columns = gutterColumns(state, opts);
        if (columns == 0) {
            return StyledText.empty();
        }
        Style style = opts.presentation().lineNumberStyle().merge(rowStyle);
        if (at.line() < 0 || at.start() != state.lineStart(at.line())) {
            return StyledText.of(" ".repeat(columns), style);
        }
        String number = Integer.toString(at.line() + 1);
        return StyledText.of(" ".repeat(columns - 1 - number.length()) + number + " ", style);
    }

    /**
     * Paints one row's prefix and its draft, with the selection, the block caret and the placeholder.
     */
    static StyledText renderRow(
            Model state,
            EditorConfig opts,
            RowAt at,
            boolean firstRow,
            boolean caretRow,
            Style rowStyle,
            String placeholder,
            Painted frame) {
        StyledText out = StyledText.empty();
        if (!opts.prompt().isEmpty()) {
            out = out.append(
                    firstRow
                            ? StyledText.of(opts.prompt(), opts.promptStyle().merge(rowStyle))
                            : StyledText.of(
                                    " ".repeat(TextMeasure.columns(paintedPrompt(opts))),
                                    opts.promptStyle().merge(rowStyle)));
        }
        int start = at.start();
        int end = at.end();
        String text = state.text().substring(start, end);
        VisualRows.Cells cells = paintCells(opts);
        int selectionStart = state.hasSelection() ? state.selectionStart() : -1;
        int selectionEnd = state.hasSelection() ? state.selectionEnd() : -1;
        boolean masked = opts.masking() == EditorConfig.Masking.PASSWORD;
        boolean bounded = frame.frame().wrap() != UNBOUNDED;

        StringBuilder pending = new StringBuilder(Math.max(16, end - start));
        Style pendingStyle = opts.textStyle().merge(rowStyle);
        int position = start;
        // Painted columns before this row's draft: the gutter, the prompt, or the blank stand-in
        // that keeps later rows aligned. A tab advances to the next stop from there, so the caret's
        // block covers the cells the draft really occupies.
        int used = frame.frame().prefix();
        // The cell the prefix ends with, which the first cluster of the draft is painted inside when
        // it has no base of its own; a tab leaves the same opening at the last space of its stop.
        VisualRows.Open held = openAt(at.line(), frame.firstLine(), frame.frame());
        if (!placeholder.isEmpty()) {
            // The placeholder is painted where an empty draft would be, and is not part of it: it
            // occupies no document offset and is never submitted or copied. The block replaces its
            // first cluster, exactly as it replaces the first character of a draft, and a declared
            // cursor is already placed on that cell.
            Style style = opts.presentation().placeholderStyle().merge(rowStyle);
            int painted;
            if (frame.blockPainted()) {
                int first = Math.max(1, TextMeasure.boundaryAfter(placeholder, 0));
                int block =
                        Math.max(1, TextMeasure.clusterColumns(placeholder.substring(0, first), WidthPolicy.PINNED));
                out = out.append(StyledText.of(CURSOR.repeat(block), style))
                        .append(StyledText.of(placeholder.substring(first), style));
                painted = block + TextMeasure.columns(placeholder.substring(first));
            } else {
                out = out.append(StyledText.of(placeholder, style));
                painted = TextMeasure.columns(placeholder);
            }
            return out.append(filling(painted, rowStyle, frame));
        }
        // Segment this row once. A per-cluster boundaryAfter() over the whole draft is O(n^2) and
        // cost tens of milliseconds per frame on a draft at the editor's length limit; the row's own
        // grapheme boundaries are the draft's, because a row starts and ends on a boundary.
        for (String cluster : Graphemes.iterate(text)) {
            int next = position + cluster.length();
            boolean selected = selectionStart >= 0 && position >= selectionStart && position < selectionEnd;
            String display = opts.masking() == EditorConfig.Masking.HIDDEN ? "" : masked ? MASK : cluster;
            boolean cursor = frame.blockPainted() && state.caret() == position;
            int cost =
                    cursor ? VisualRows.blockCells(cluster, used, cells) : VisualRows.cells(cluster, used, cells, held);
            held = cursor ? VisualRows.CLOSED : VisualRows.openBehind(cluster, cells);
            if (cursor) {
                // The block covers the grapheme under the caret. A two-column grapheme, and a tab
                // reaching its stop, occupy those cells; one block would leave the row short and
                // slide everything after the caret to the left.
                display = CURSOR.repeat(cost);
            } else if (bounded
                    && "\t".equals(cluster)
                    && !masked
                    && opts.masking() != EditorConfig.Masking.HIDDEN
                    && cost > frame.frame().prefix() + frame.frame().wrap() - used) {
                // A stop wider than the whole row is painted as the spaces the row can hold, which
                // is the width the projection charged it; every other tab stays the character the
                // text view expands to the same stop the projection counted.
                cost = Math.max(1, frame.frame().prefix() + frame.frame().wrap() - used);
                display = " ".repeat(cost);
            }
            Style style = (selected ? opts.selectionStyle() : opts.textStyle()).merge(rowStyle);
            if (!display.isEmpty()) {
                if (style.equals(pendingStyle)) {
                    pending.append(display);
                } else {
                    out = out.append(StyledText.of(pending.toString(), pendingStyle));
                    pending.setLength(0);
                    pending.append(display);
                    pendingStyle = style;
                }
                used += cost;
            }
            position = next;
        }
        if (frame.blockPainted() && caretRow && state.caret() == end) {
            // A caret at the end of a row is drawn here unless a later painted row starts at the
            // same offset - a soft break is one offset that ends one row and starts the next, and
            // the block belongs to the row it opens.
            pending.append(CURSOR);
        }
        out = out.append(StyledText.of(pending.toString(), pendingStyle));
        return out.append(filling(used - frame.frame().prefix(), rowStyle, frame));
    }

    /**
     * The blanks that fill the rest of a row a style covers.
     *
     * <p>A cursor line is a row style, so it has to reach the row's last column rather than stopping
     * at the last glyph. An unstyled row, and one that is not wrapped to a width at all, adds
     * nothing.</p>
     *
     * @param cells the content cells the row's draft painted
     * @param rowStyle the style the row carries
     * @param frame the frame the row was projected in
     * @return the blanks, or empty
     */
    static StyledText filling(int cells, Style rowStyle, Painted frame) {
        int padding = frame.frame().wrap() - cells;
        if (rowStyle.isPlain() || padding <= 0 || frame.frame().wrap() == UNBOUNDED) {
            return StyledText.empty();
        }
        return StyledText.of(" ".repeat(padding), rowStyle);
    }

    /**
     * The painted block as a leaf.
     *
     * <p>The rows the draft wraps into depend on the width the ancestors allocate and on the rows
     * they reserve, so the block is projected twice: once while it is measured - which is what tells
     * a parent how many rows it wants - and once while it is painted, at the rectangle it was given.
     * The projection is pure, so the two cannot disagree about the draft, only about the window.</p>
     */
    static final class Block implements Widget<Void, Msg> {

        private final Model state;
        private final EditorConfig options;

        Block(Model state, EditorConfig options) {
            this.state = state;
            this.options = options;
        }

        @Override
        public Size measure(Size max, Void model) {
            StyledText text = EditorRendering.render(
                    EditorRendering.painted(state, options, state.focused(), max.cols(), max.rows()), state, options);
            List<StyledText> lines = TextWrap.wrap(text, WidthPolicy.PINNED, max.cols(), max.rows());
            int cols = 0;
            for (StyledText line : lines) {
                cols = Math.max(cols, TextMeasure.columns(line.plainText()));
            }
            return new Size(Math.min(max.rows(), lines.size()), Math.min(max.cols(), cols));
        }

        @Override
        public void paint(Surface surface, Rect bounds, Void model) {
            if (bounds.rows() <= 0 || bounds.cols() <= 0) {
                return;
            }
            Painted frame = EditorRendering.painted(state, options, state.focused(), bounds.cols(), bounds.rows());
            surface.write(bounds, EditorRendering.render(frame, state, options), WidthPolicy.PINNED);
        }
    }
}
