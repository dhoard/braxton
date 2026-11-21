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

package io.github.dhoard.braxton.line;

import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Paints a {@link LineEditor} as one row of a surface, prompt included, and its menu below it.
 *
 * <p>The row is the prompt, then as much of the line as fits, with the caret placed in the cell it
 * belongs to. Nothing here writes to a terminal: the caller decides whether the surface is a
 * full-screen frame, an inline region or a test's own buffer, which is what lets
 * {@link LineReader} paint the same row on the terminal's normal screen and a test assert it without
 * one.</p>
 *
 * <p>A line wider than the row is scrolled, not wrapped, because a line editor is one row by
 * definition. The least amount is scrolled off the left so that the caret stays inside the row, and
 * the window always starts and ends on a cluster boundary - a wide glyph is shown whole or not at
 * all. Nothing marks what was scrolled off: readline shows no marker either, and a marker would be a
 * cell the line itself cannot use. A prompt wider than the row is clipped, and the caret then sits on
 * the row's last cell.</p>
 *
 * <p>Colors come from a {@link Theme}: the prompt is {@link ThemeToken#ACCENT} and the line is
 * {@link ThemeToken#TEXT}, so a live theme switch repaints the same editor without touching its
 * model. A {@link Highlighter} adds one style per cluster on top of that, and the mask a masked
 * editor paints replaces the text with one glyph per cluster - both are presentation only, so neither
 * can move the caret or change a column. The renderer is stateless - every call is a complete answer
 * for the editor it is given - so one instance serves any number of editors and rows.</p>
 *
 * <p>A running search paints its own prompt in place of the caller's, because a line editor has one
 * row and the row has to say what is being searched for: the prompt names the direction, then repeats
 * the query in backticks with a colon after it - {@code (reverse-i-search)} walking to older lines,
 * {@code (forward-i-search)} walking to newer ones - and says {@code failed} first when the last step
 * found nothing. The matched line is the editor's text and the caret is already where the query
 * matched, so the rest of the row needs no special case.</p>
 *
 * <p>A completion menu is a second row: {@link #paintMenu} writes the candidates on the row below the
 * line, with the highlighted one bracketed and filled with the theme's selection, and scrolls the
 * list so that the highlighted candidate is always whole and visible. Brackets are deliberate - a
 * monochrome terminal shows a style as nothing, and a menu that cannot say which candidate is
 * highlighted is not a menu.</p>
 */
public final class LineRenderer {

    /**
     * The columns left empty between two candidates in the menu row.
     */
    private static final int GAP = 2;

    private final Theme theme;
    private final WidthPolicy policy;

    /**
     * A renderer with the built-in dark theme and the pinned column policy.
     */
    public LineRenderer() {
        this(Themes.DARK, WidthPolicy.PINNED);
    }

    /**
     * Creates a renderer from its two inputs.
     *
     * @param theme the theme the row's styles come from; null is the built-in dark theme
     * @param policy how a cluster nobody has assigned a width is measured; null is the pinned policy
     */
    public LineRenderer(Theme theme, WidthPolicy policy) {
        this.theme = theme == null ? Themes.DARK : theme;
        this.policy = policy == null ? WidthPolicy.PINNED : policy;
    }

    /**
     * The same renderer with another theme.
     *
     * @param value the theme; null is the built-in dark theme
     * @return the renderer
     */
    public LineRenderer withTheme(Theme value) {
        return new LineRenderer(value, policy);
    }

    /**
     * The same renderer with another column policy.
     *
     * @param value the policy; null is the pinned policy
     * @return the renderer
     */
    public LineRenderer withPolicy(WidthPolicy value) {
        return new LineRenderer(theme, value);
    }

    /**
     * The theme the row's styles come from.
     *
     * @return the theme the row's styles come from
     */
    public Theme theme() {
        return theme;
    }

    /**
     * The column policy clusters are measured with.
     *
     * @return the column policy clusters are measured with
     */
    public WidthPolicy policy() {
        return policy;
    }

    /**
     * Paints the prompt and the editor's line on one row, and answers where the caret goes.
     *
     * <p>Only the cells the prompt and the line occupy are written; the caller fills or erases the
     * rest of the row. A row of zero or fewer columns paints nothing and asks for no caret, because
     * there is no cell to put one in.</p>
     *
     * @param surface the surface to paint on; required
     * @param row the surface row to paint
     * @param cols the columns this row may use
     * @param editor the editor to paint; required
     * @param prompt the prompt to paint before the line; null is no prompt
     * @return the caret in surface cells, or empty when the row cannot hold one
     */
    public Optional<Cursor> paint(Surface surface, int row, int cols, LineEditor editor, String prompt) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(editor, "editor");
        if (cols <= 0) {
            return Optional.empty();
        }
        String heading = label(editor, prompt);
        Style labelStyle = theme.style(ThemeToken.ACCENT);
        Style lineStyle = theme.style(ThemeToken.TEXT);
        if (!heading.isEmpty()) {
            surface.writeLine(row, 0, cols, StyledText.of(heading, labelStyle), policy);
        }
        int labelColumns = LineText.columns(heading);
        if (labelColumns >= cols) {
            return Optional.of(Cursor.at(row, cols - 1));
        }
        String line = display(editor);
        Window window = window(line, displayCaret(editor), cols - labelColumns);
        if (window.end() > window.start()) {
            surface.writeLine(row, labelColumns, cols - labelColumns, text(editor, line, window, lineStyle), policy);
        }
        return Optional.of(Cursor.at(row, labelColumns + window.caretColumn()));
    }

    /**
     * Paints the completion menu on the row below the line, and nothing when no menu is showing.
     *
     * <p>The candidates are separated by two columns, the highlighted one is bracketed and filled
     * with the theme's selection, and the list scrolls by the least it can so that the highlighted
     * candidate is whole and inside the row. A candidate wider than the row is clipped like any other
     * text, which is the same rule the line follows: no cluster is ever split.</p>
     *
     * @param surface the surface to paint on; required
     * @param row the surface row to paint
     * @param cols the columns this row may use
     * @param editor the editor whose menu to paint; required, and a no-op without one
     */
    public void paintMenu(Surface surface, int row, int cols, LineEditor editor) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(editor, "editor");
        Optional<Completion> shown = editor.completion();
        if (cols <= 0 || shown.isEmpty()) {
            return;
        }
        Completion menu = shown.get();
        List<String> candidates = menu.candidates();
        int[] starts = new int[candidates.size()];
        int column = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) {
                column += GAP;
            }
            starts[index] = column;
            column += LineText.columns(entry(menu, index));
        }
        int selectedStart = starts[menu.selected()];
        int selectedEnd = selectedStart + LineText.columns(entry(menu, menu.selected()));
        int offset = Math.max(0, selectedEnd - cols);
        if (offset > selectedStart) {
            offset = selectedStart;
        }
        Style plain = theme.style(ThemeToken.MUTED);
        Style marked = theme.style(ThemeToken.SELECTED);
        for (int index = 0; index < candidates.size(); index++) {
            candidate(
                    surface,
                    row,
                    cols,
                    entry(menu, index),
                    starts[index] - offset,
                    index == menu.selected() ? marked : plain);
        }
    }

    /**
     * The columns of the line scrolled off the left so that the caret stays inside the row.
     *
     * <p>Zero when the caret is already visible. The answer always stops on a cluster boundary, so
     * the column it names is the first one the painted line occupies rather than the arithmetic
     * minimum a wide glyph would have to be split at.</p>
     *
     * @param editor the editor to measure; null scrolls nothing
     * @param prompt the prompt painted before the line; null is no prompt
     * @param cols the columns the whole row may use
     * @return the scrolled columns
     */
    public int scrollOffset(LineEditor editor, String prompt, int cols) {
        if (editor == null) {
            return 0;
        }
        int available = cols - LineText.columns(label(editor, prompt));
        return available <= 0
                ? 0
                : window(display(editor), displayCaret(editor), available).scrolled();
    }

    /**
     * One menu entry as it is painted: the highlighted candidate carries its brackets.
     */
    private static String entry(Completion menu, int index) {
        String candidate = menu.candidates().get(index);
        return index == menu.selected() ? "[" + candidate + "]" : candidate;
    }

    /**
     * Paints one menu entry at a column, clipped at both ends of the row.
     *
     * <p>An entry that starts left of the row loses the clusters the scroll pushed off, measured the
     * way the row is measured, and one that starts right of it is not painted at all. That is what
     * lets the list scroll to the highlighted candidate without a cluster ever being split.</p>
     */
    private void candidate(Surface surface, int row, int cols, String entry, int column, Style style) {
        int width = LineText.columns(entry);
        if (column + width <= 0) {
            return;
        }
        String visible = entry;
        int start = column;
        if (start < 0) {
            int skipped = skip(entry, -start);
            start = column + LineText.columns(entry.substring(0, skipped));
            visible = entry.substring(skipped);
        }
        if (start >= cols) {
            return;
        }
        surface.writeLine(row, start, cols - start, StyledText.of(visible, style), policy);
    }

    /**
     * The character offset of the first cluster at or after a column.
     */
    private static int skip(String text, int columns) {
        int index = 0;
        int used = 0;
        while (index < text.length() && used < columns) {
            int end = LineText.next(text, index);
            used += LineText.columns(text.substring(index, end));
            index = end;
        }
        return index;
    }

    /**
     * What the row paints for the line: the text itself, or one mask glyph per cluster.
     */
    private static String display(LineEditor editor) {
        return editor.masked() ? LineText.masked(editor.text()) : editor.text();
    }

    /**
     * Where the caret sits in what the row paints.
     */
    private static int displayCaret(LineEditor editor) {
        return editor.masked() ? LineText.maskedCaret(editor.text(), editor.caret()) : editor.caret();
    }

    /**
     * The visible part of the line with the highlighter's styles merged over it.
     *
     * <p>Only the clusters the window shows are restyled, and each one keeps the line's own style
     * underneath, so a highlighter that sets a weight still paints in the theme's color and one that
     * answers nothing for a cluster leaves it alone. A masked row has no real clusters to color, so
     * it ignores the highlighter altogether.</p>
     */
    private StyledText text(LineEditor editor, String line, Window window, Style lineStyle) {
        StyledText painted = StyledText.of(line.substring(window.start(), window.end()), lineStyle);
        if (editor.masked()) {
            return painted;
        }
        Optional<Highlighter> source = editor.highlighter();
        if (source.isEmpty()) {
            return painted;
        }
        List<Style> styles = source.get().styles(editor.text());
        if (styles == null || styles.isEmpty()) {
            return painted;
        }
        int offset = 0;
        int index = 0;
        while (offset < line.length()) {
            int end = LineText.next(line, offset);
            if (index >= window.firstCluster() && index < window.endCluster() && index < styles.size()) {
                Style style = styles.get(index);
                if (style != null) {
                    painted = painted.styled(offset - window.start(), end - window.start(), style);
                }
            }
            offset = end;
            index++;
        }
        return painted;
    }

    /**
     * What is painted before the line: the caller's prompt, or the running search's own prompt.
     *
     * <p>The search prompt names the direction and repeats the query, so a user can see what is being
     * searched for after the query has scrolled off some other way, and it says so when the last step
     * found nothing instead of looking like a search that matched. The query is already one row, so it
     * cannot bring a second line or a control character into the cell grid.</p>
     */
    private static String label(LineEditor editor, String prompt) {
        Optional<LineEditor.Search> running = editor.search();
        if (running.isEmpty()) {
            return LineText.singleRow(prompt);
        }
        LineEditor.Search search = running.get();
        String name = search.direction() == LineEditor.Direction.BACKWARD ? "reverse-i-search" : "forward-i-search";
        return "(" + (search.failed() ? "failed " + name : name) + ")`" + search.query() + "': ";
    }

    /**
     * The window of the line the row shows, and the caret's column inside it.
     *
     * <p>The caret's column is measured from the first visible cluster, never from the start of the
     * line, so a scrolled line's caret answers a column the caller can add the prompt's width to.
     * The cluster range is what a highlighter's per-cluster styles are read against. The caller has
     * already established that the row has at least one column for the line.</p>
     */
    private Window window(String text, int caret, int available) {
        List<String> clusters = TextMeasure.graphemes(text);
        int size = clusters.size();
        int[] offsets = new int[size + 1];
        int[] widths = new int[size];
        for (int index = 0; index < size; index++) {
            String cluster = clusters.get(index);
            offsets[index + 1] = offsets[index] + cluster.length();
            widths[index] = TextMeasure.clusterColumns(cluster, policy);
        }
        int caretCluster = 0;
        while (caretCluster < size && offsets[caretCluster + 1] <= caret) {
            caretCluster++;
        }
        int before = 0;
        for (int index = 0; index < caretCluster; index++) {
            before += widths[index];
        }
        int first = 0;
        int scrolled = 0;
        if (before >= available) {
            int limit = before - available + 1;
            while (first < caretCluster && scrolled < limit) {
                scrolled += widths[first];
                first++;
            }
        }
        int caretColumn = Math.max(0, Math.min(before - scrolled, available - 1));
        int end = first;
        int used = 0;
        while (end < size && used + widths[end] <= available) {
            used += widths[end];
            end++;
        }
        return new Window(offsets[first], offsets[end], first, end, scrolled, caretColumn);
    }

    /**
     * The painted window: which characters of the line, which clusters, how many columns scrolled,
     * and where the caret is.
     */
    private record Window(int start, int end, int firstCluster, int endCluster, int scrolled, int caretColumn) {}
}
