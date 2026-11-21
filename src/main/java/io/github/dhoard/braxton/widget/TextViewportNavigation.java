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

import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.TextViewport.Anchor;
import io.github.dhoard.braxton.widget.TextViewport.Config;
import io.github.dhoard.braxton.widget.TextViewport.Content;
import io.github.dhoard.braxton.widget.TextViewport.Gutter;
import io.github.dhoard.braxton.widget.TextViewport.Highlight;
import io.github.dhoard.braxton.widget.TextViewport.Mode;
import io.github.dhoard.braxton.widget.TextViewport.Model;
import io.github.dhoard.braxton.widget.TextViewport.Msg;
import io.github.dhoard.braxton.widget.TextViewport.Row;
import io.github.dhoard.braxton.widget.TextViewport.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Viewport scrolling, anchors, projection, and highlighting.
 */
final class TextViewportNavigation {

    /**
     * Applies one message. Never performs I/O, never mutates the input state.
     */
    static Model reduce(Model state, Config config, Msg message) {
        return switch (message) {
            case Msg.Up up -> movedUp(state, config, up.rows());
            case Msg.Down down -> movedDown(state, config, down.rows());
            case Msg.PageUp pageUp -> movedUp(state, config, state.rows());
            case Msg.PageDown pageDown -> movedDown(state, config, state.rows());
            case Msg.HalfPageUp half -> movedUp(state, config, state.rows() / 2);
            case Msg.HalfPageDown half -> movedDown(state, config, state.rows() / 2);
            case Msg.Top top -> toTop(state, config);
            case Msg.Bottom bottom -> toBottom(state, config);
            case Msg.Left left -> panned(state, config, -left.columns());
            case Msg.Right right -> panned(state, config, right.columns());
            case Msg.ContentChanged changed -> changed(state, config, changed.content());
            case Msg.Resized resized -> resized(state, config, resized.rows(), resized.columns());
            case Msg.Highlights highlights -> highlightsChanged(state, config, highlights.highlights());
            case Msg.NextMatch next -> matched(state, config, 1);
            case Msg.PreviousMatch previous -> matched(state, config, -1);
        };
    }

    /**
     * One deliberate move upwards, releasing follow-end only when the window really moved.
     */
    static Model movedUp(Model state, Config config, int rows) {
        Anchor from = anchor(state, config);
        Anchor target = retreat(state.content(), config.mode(), TextViewport.contentColumns(state, config), from, rows);
        if (target.equals(from)) {
            return state;
        }
        return at(state, config, target, false);
    }

    /**
     * One move downwards, clamped to the last window the content fills.
     */
    static Model movedDown(Model state, Config config, int rows) {
        Anchor from = anchor(state, config);
        Anchor target = clampedDown(state, config, from, rows);
        return target.equals(from) ? state : at(state, config, target, state.followEnd());
    }

    /**
     * The first window. Follow-end is released only when the window can move: content that fits the
     * pane is already at its first row, and losing following there would surprise the next append.
     */
    static Model toTop(Model state, Config config) {
        Anchor from = anchor(state, config);
        Anchor target = clampedDown(state, config, new Anchor(0, 0), 0);
        if (target.equals(from)) {
            return state;
        }
        return at(state, config, target, false);
    }

    /**
     * The last window, with follow-end armed as far as the configuration allows.
     */
    static Model toBottom(Model state, Config config) {
        Anchor target =
                bottom(state.content(), config.mode(), TextViewport.contentColumns(state, config), state.rows());
        return at(state, config, target, config.followEnd());
    }

    /**
     * One horizontal move. Wrapping viewports have no horizontal offset to move.
     */
    static Model panned(Model state, Config config, int columns) {
        if (config.mode() != Mode.PAN || columns == 0) {
            return state;
        }
        int width = TextViewport.contentColumns(state, config);
        int max = Math.max(0, widestColumns(state.content()) - width);
        int current = effectiveFirstColumn(state, config);
        // Widened before the addition: a pager can be asked to move by any count an int holds.
        int target = (int) Math.max(0L, Math.min((long) max, (long) current + columns));
        if (target == current && state.firstColumn() == current) {
            return state;
        }
        return new Model(
                state.topLine(),
                state.topRow(),
                target,
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                state.highlights(),
                state.active());
    }

    /**
     * New content: a following window re-anchors, a moved one keeps its logical anchor.
     */
    static Model changed(Model state, Config config, Content content) {
        int width = Math.max(0, state.columns() - config.gutterColumns());
        Anchor anchor = state.followEnd()
                ? bottom(content, config.mode(), width, state.rows())
                : clamped(content, config.mode(), width, anchor(state, config));
        // The highlights described the content version that is being replaced, so they are not
        // carried over: their offsets would mark whatever text now happens to sit at them.
        return new Model(
                anchor.line(),
                anchor.row(),
                clampFirstColumn(state.firstColumn(), content, width, config.mode()),
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                content,
                List.of(),
                -1);
    }

    /**
     * New geometry: a following window re-anchors, a moved one keeps its logical line.
     */
    static Model resized(Model state, Config config, int rows, int columns) {
        int width = Math.max(0, columns - config.gutterColumns());
        Anchor anchor = state.followEnd()
                ? bottom(state.content(), config.mode(), width, rows)
                : clamped(state.content(), config.mode(), width, anchor(state, config));
        return new Model(
                anchor.line(),
                anchor.row(),
                clampFirstColumn(state.firstColumn(), state.content(), width, config.mode()),
                state.followEnd(),
                state.focused(),
                rows,
                columns,
                state.content(),
                state.highlights(),
                state.active());
    }

    /**
     * Published ranges, validated against the content they describe.
     */
    static Model highlightsChanged(Model state, Config config, List<Highlight> highlights) {
        requireValid(state.content(), highlights);
        return new Model(
                state.topLine(),
                state.topRow(),
                state.firstColumn(),
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                highlights,
                -1);
    }

    /**
     * One step through the published ranges, wrapping at both ends, then scrolled into view.
     */
    static Model matched(Model state, Config config, int direction) {
        List<Highlight> highlights = state.highlights();
        if (highlights.isEmpty()) {
            return state;
        }
        int size = highlights.size();
        int active = state.active();
        int next = direction > 0
                ? (active < 0 || active + 1 >= size ? 0 : active + 1)
                : (active <= 0 ? size - 1 : active - 1);
        Highlight target = highlights.get(next);
        Model selected = new Model(
                state.topLine(),
                state.topRow(),
                state.firstColumn(),
                state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                highlights,
                next);
        return scrolledIntoView(selected, config, target);
    }

    /**
     * The state that shows one range: vertically, and in a panned viewport horizontally.
     *
     * <p>The window the state already covers is the range's own coordinates - the rows it paints,
     * each naming its line and row - so "is the match visible" is one comparison against the first
     * and last of them, and neither answer needs the whole content. A range above the window becomes
     * the anchor; one below it is placed on the last row by walking back from it, which is as far as
     * the pane needs to look.</p>
     */
    static Model scrolledIntoView(Model state, Config config, Highlight highlight) {
        Content content = state.content();
        Mode mode = config.mode();
        int width = TextViewport.contentColumns(state, config);
        Anchor from = anchor(state, config);
        Anchor target = new Anchor(highlight.line(), rowAt(content, highlight.line(), highlight.start(), width, mode));
        Anchor anchor = from;
        List<Slot> window =
                slots(content, width, mode, effectiveFirstColumn(state, config), from.line(), from.row(), state.rows());
        if (window.isEmpty()) {
            anchor = target;
        } else {
            Slot first = window.get(0);
            Slot last = window.get(window.size() - 1);
            if (compare(target, new Anchor(first.line(), first.row())) < 0) {
                anchor = target;
            } else if (compare(target, new Anchor(last.line(), last.row())) > 0) {
                anchor = retreat(content, mode, width, target, Math.max(0, state.rows() - 1));
            }
        }
        int firstColumn = mode == Mode.PAN ? pannedTo(state, config, highlight) : effectiveFirstColumn(state, config);
        boolean moved = !anchor.equals(from) || firstColumn != effectiveFirstColumn(state, config);
        return new Model(
                anchor.line(),
                anchor.row(),
                firstColumn,
                moved ? false : state.followEnd(),
                state.focused(),
                state.rows(),
                state.columns(),
                content,
                state.highlights(),
                state.active());
    }

    /**
     * The horizontal offset that shows one range, as little moved as the content allows.
     */
    static int pannedTo(Model state, Config config, Highlight highlight) {
        Content content = state.content();
        String text = content.line(highlight.line()).plainText();
        int start = Math.min(highlight.start(), text.length());
        int end = Math.min(highlight.end(), text.length());
        int column = TextMeasure.columns(text.substring(0, start));
        // An empty range is a position, so it needs the cell it sits on rather than its own width.
        int need = Math.max(1, TextMeasure.columns(text.substring(start, end)));
        int width = TextViewport.contentColumns(state, config);
        int offset = effectiveFirstColumn(state, config);
        if (column < offset) {
            offset = column;
        } else if (column + need > offset + width) {
            offset = Math.max(0, column + need - width);
        }
        int max = Math.max(0, widestColumns(content) - width);
        return Math.max(0, Math.min(offset, max));
    }

    /**
     * The window's anchor, clamped to the content this state holds.
     *
     * <p>Following is a property of the window, not a position in it: while the flag is set the
     * window covers the end of the content, wherever the stored anchor happens to point. Every
     * projection and every message therefore starts here, so a following window cannot disagree
     * with itself about where it is, and a gesture that leaves the end really moves.</p>
     */
    static Anchor anchor(Model state, Config config) {
        int width = TextViewport.contentColumns(state, config);
        if (state.followEnd()) {
            return bottom(state.content(), config.mode(), width, state.rows());
        }
        return clamped(state.content(), config.mode(), width, new Anchor(state.topLine(), state.topRow()));
    }

    /**
     * The same anchor, clamped so it names a row the content really has.
     */
    static Anchor clamped(Content content, Mode mode, int width, Anchor anchor) {
        int lines = content.lineCount();
        if (lines == 0) {
            return new Anchor(0, 0);
        }
        int line = Math.min(anchor.line(), lines - 1);
        int rows = rowsInLine(content, line, width, mode);
        return new Anchor(line, Math.min(anchor.row(), rows - 1));
    }

    /**
     * The state anchored at {@code target}, with the geometry it already has.
     */
    static Model at(Model state, Config config, Anchor target, boolean followEnd) {
        Anchor clamped = clamped(state.content(), config.mode(), TextViewport.contentColumns(state, config), target);
        return new Model(
                clamped.line(),
                clamped.row(),
                state.firstColumn(),
                followEnd,
                state.focused(),
                state.rows(),
                state.columns(),
                state.content(),
                state.highlights(),
                state.active());
    }

    /**
     * One downward move, clamped to the last window the content fills.
     */
    static Anchor clampedDown(Model state, Config config, Anchor from, int rows) {
        int width = TextViewport.contentColumns(state, config);
        Anchor target = advance(state.content(), config.mode(), width, from, rows);
        Anchor last = bottom(state.content(), config.mode(), width, state.rows());
        return compare(target, last) > 0 ? last : target;
    }

    /**
     * The anchor {@code count} visual rows below {@code from}, stopping at the content's last row.
     */
    static Anchor advance(Content content, Mode mode, int width, Anchor from, int count) {
        int remaining = Math.max(0, count);
        int lines = content.lineCount();
        if (lines == 0 || remaining == 0) {
            return clamped(content, mode, width, from);
        }
        int line = Math.min(from.line(), lines - 1);
        int rowsIn = rowsInLine(content, line, width, mode);
        int row = Math.min(from.row(), rowsIn - 1);
        while (remaining > 0) {
            int below = rowsIn - 1 - row;
            if (remaining <= below) {
                row += remaining;
                remaining = 0;
            } else {
                remaining -= below;
                if (line + 1 >= lines) {
                    row = rowsIn - 1;
                    remaining = 0;
                } else {
                    line++;
                    row = 0;
                    remaining--;
                    rowsIn = rowsInLine(content, line, width, mode);
                }
            }
        }
        return new Anchor(line, row);
    }

    /**
     * The anchor {@code count} visual rows above {@code from}, stopping at the content's first row.
     */
    static Anchor retreat(Content content, Mode mode, int width, Anchor from, int count) {
        int remaining = Math.max(0, count);
        int lines = content.lineCount();
        if (lines == 0 || remaining == 0) {
            return clamped(content, mode, width, from);
        }
        int line = Math.min(from.line(), lines - 1);
        int rowsIn = rowsInLine(content, line, width, mode);
        int row = Math.min(from.row(), rowsIn - 1);
        while (remaining > 0) {
            if (row > 0) {
                int up = Math.min(row, remaining);
                row -= up;
                remaining -= up;
            } else if (line > 0) {
                line--;
                rowsIn = rowsInLine(content, line, width, mode);
                row = rowsIn - 1;
                remaining--;
            } else {
                remaining = 0;
            }
        }
        return new Anchor(line, row);
    }

    /**
     * The topmost anchor whose window still reaches the content's end.
     *
     * <p>The window is one pane tall, so this walks back from the last row for at most one window and
     * never measures the whole content. A zero-height pane still has a valid anchor - the last row -
     * and paints nothing from it.</p>
     */
    static Anchor bottom(Content content, Mode mode, int width, int rows) {
        int lines = content.lineCount();
        if (lines == 0) {
            return new Anchor(0, 0);
        }
        int remaining = Math.max(1, rows);
        for (int line = lines - 1; line >= 0; line--) {
            int rowsIn = rowsInLine(content, line, width, mode);
            if (rowsIn >= remaining) {
                return new Anchor(line, rowsIn - remaining);
            }
            remaining -= rowsIn;
        }
        return new Anchor(0, 0);
    }

    /**
     * How many visual rows one logical line occupies.
     */
    static int rowsInLine(Content content, int line, int width, Mode mode) {
        return projection(content.line(line), width, mode, 0).size();
    }

    /**
     * The rows one logical line occupies, in the pane's mode.
     *
     * <p>Both modes answer with {@link VisualRows}, so a tab, a wide glyph and a combining mark cost
     * the same cells whether the line wraps or is panned, and the row a match lands on is the row the
     * cell mapping put it on.</p>
     */
    static List<VisualRows.Row> projection(StyledText line, int width, Mode mode, int firstColumn) {
        String text = line.plainText();
        if (mode == Mode.PAN) {
            VisualRows.Window window = VisualRows.pane(text, 0, width, firstColumn);
            return List.of(new VisualRows.Row(window.start(), window.end(), window.columns()));
        }
        VisualRows.Wrap wrap = mode == Mode.WORD_WRAP ? VisualRows.Wrap.WORD : VisualRows.Wrap.CELL;
        return VisualRows.project(
                text, 0, text.length(), 0, width, VisualRows.Cells.NATURAL, VisualRows.CLOSED, -1, wrap);
    }

    /**
     * The row of one line an offset lands on: the last row that starts at or before it.
     */
    static int rowAt(Content content, int line, int offset, int width, Mode mode) {
        List<VisualRows.Row> rows = projection(content.line(line), width, mode, 0);
        int found = 0;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).start() <= offset) {
                found = index;
            }
        }
        return found;
    }

    /**
     * The painted rows of the window, before their text is cut and styled.
     */
    static List<Slot> slots(Model state, Config config) {
        Anchor anchor = anchor(state, config);
        return slots(
                state.content(),
                TextViewport.contentColumns(state, config),
                config.mode(),
                effectiveFirstColumn(state, config),
                anchor.line(),
                anchor.row(),
                state.rows());
    }

    /**
     * The painted rows from one anchor, never reading a line the window does not reach.
     */
    static List<Slot> slots(Content content, int width, Mode mode, int firstColumn, int line, int row, int rows) {
        if (rows <= 0) {
            return List.of();
        }
        int lines = content.lineCount();
        if (lines == 0) {
            return List.of();
        }
        List<Slot> slots = new ArrayList<>(Math.min(rows, 256));
        int at = Math.min(Math.max(0, line), lines - 1);
        int from = Math.max(0, row);
        while (slots.size() < rows && at < lines) {
            StyledText text = content.line(at);
            List<VisualRows.Row> projected = projection(text, width, mode, firstColumn);
            int total = projected.size();
            for (int index = Math.min(from, total - 1); index < total && slots.size() < rows; index++) {
                VisualRows.Row projectedRow = projected.get(index);
                slots.add(new Slot(at, index, total, projectedRow.start(), projectedRow.end(), text));
            }
            at++;
            from = 0;
        }
        return slots;
    }

    /**
     * The horizontal offset the pane really paints at.
     */
    static int effectiveFirstColumn(Model state, Config config) {
        if (config.mode() != Mode.PAN || state.firstColumn() == 0) {
            return 0;
        }
        int max = Math.max(0, widestColumns(state.content()) - TextViewport.contentColumns(state, config));
        return Math.min(state.firstColumn(), max);
    }

    /**
     * The same offset, clamped to the content its new geometry allows.
     */
    static int clampFirstColumn(int firstColumn, Content content, int width, Mode mode) {
        if (mode != Mode.PAN || firstColumn <= 0) {
            return Math.max(0, firstColumn);
        }
        // Only a pane that is really panned pays for measuring the widest line, so the common case -
        // a viewport that never pans - stays as cheap as publishing content.
        return Math.min(firstColumn, Math.max(0, widestColumns(content) - width));
    }

    static int widestColumns(Content content) {
        int widest = 0;
        int lines = content.lineCount();
        for (int line = 0; line < lines; line++) {
            widest = Math.max(widest, TextMeasure.columns(content.line(line).plainText()));
        }
        return widest;
    }

    /**
     * A fraction of one range, clamped to it; zero when there is nothing to travel.
     */
    static double fraction(int position, int travel) {
        if (travel <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) position / travel));
    }

    static int compare(Anchor left, Anchor right) {
        return left.line() != right.line()
                ? Integer.compare(left.line(), right.line())
                : Integer.compare(left.row(), right.row());
    }

    /**
     * Rejects ranges that do not describe the content they claim to.
     */
    static void requireValid(Content content, List<Highlight> highlights) {
        int lines = content.lineCount();
        for (Highlight highlight : highlights) {
            if (highlight.line() >= lines) {
                throw new IllegalArgumentException(
                        "highlight line " + highlight.line() + " is outside the content's " + lines + " lines");
            }
            String text = content.line(highlight.line()).plainText();
            if (highlight.end() > text.length()) {
                throw new IllegalArgumentException("highlight range [" + highlight.start() + ", "
                        + highlight.end() + ") is outside line " + highlight.line() + "'s " + text.length()
                        + " units");
            }
            if (!TextMeasure.isBoundary(text, highlight.start()) || !TextMeasure.isBoundary(text, highlight.end())) {
                throw new IllegalArgumentException("highlight range [" + highlight.start() + ", " + highlight.end()
                        + ") of line " + highlight.line() + " does not fall on grapheme boundaries");
            }
        }
    }

    /**
     * One line with every published range of it restyled, the active one last.
     */
    static StyledText highlighted(StyledText text, int line, Model state, Config.Styles styles) {
        List<Highlight> highlights = state.highlights();
        if (highlights.isEmpty()) {
            return text;
        }
        StyledText styled = text;
        // The active range is applied last so that an overlapping range cannot paint over it.
        Highlight active = state.activeHighlight().orElse(null);
        for (Highlight highlight : highlights) {
            if (highlight.line() != line || highlight.equals(active)) {
                continue;
            }
            styled = styled.styled(highlight.start(), highlight.end(), styles.highlight());
        }
        if (active != null && active.line() == line) {
            styled = styled.styled(
                    active.start(), active.end(), styles.highlight().merge(styles.selectedHighlight()));
        }
        return styled;
    }

    /**
     * One row as the pane paints it: the gutter, fitted to its width, then the row's own text.
     */
    static StyledText painted(Row row, int totalLines, Config config) {
        Gutter gutter = config.gutter();
        if (gutter == null || gutter.width() == 0) {
            return row.text();
        }
        StyledText rendered = gutter.renderer().render(row.facts(totalLines));
        return fit(rendered, gutter.width()).append(row.text());
    }

    /**
     * A value padded or truncated to exactly {@code columns} cells.
     *
     * <p>Truncation stops on a cluster boundary, so a gutter can never paint half a wide glyph, and
     * the padding is what guarantees the content column starts at one cell on every row.</p>
     */
    static StyledText fit(StyledText text, int columns) {
        if (columns <= 0) {
            return StyledText.empty();
        }
        if (text == null || text.isEmpty()) {
            return StyledText.plain(" ".repeat(columns));
        }
        String plain = text.plainText();
        int used = 0;
        int end = 0;
        for (String cluster : Graphemes.iterate(plain)) {
            int cells = VisualRows.visualWidth(cluster, used);
            if (used + cells > columns) {
                break;
            }
            used += cells;
            end += cluster.length();
        }
        StyledText fitted = text.slice(0, end);
        return used >= columns ? fitted : fitted.append(StyledText.plain(" ".repeat(columns - used)));
    }

    /**
     * Rejects a negative geometry component.
     */
    static void requireNotNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    /**
     * The ranges of {@code content} that hold {@code needle}: the pane's own literal search.
     *
     * <p>This is the one matcher an application needs for the ordinary case - a find bar over a
     * document - so the widget can offer search without deciding what a match means. It is literal
     * (no pattern syntax), it never spans a line break, and it does not overlap itself: after a match
     * the scan resumes at the match's end, so {@code "aa"} in {@code "aaaa"} finds two ranges rather
     * than three. An empty needle finds nothing. Publish the result with {@link Msg.Highlights} and
     * step through it with {@link Msg.NextMatch}.</p>
     *
     * <p>The application can always supply its own ranges instead: a regular expression, a fuzzy
     * match or a search over styled spans is its policy, and the pane paints whatever it is given.</p>
     *
     * @param content the lines to search; required
     * @param needle the text to find; null or empty finds nothing
     * @param ignoreCase whether case alone cannot prevent a match
     * @return the ranges found, in line order and then in column order
     */
    public static List<Highlight> find(Content content, String needle, boolean ignoreCase) {
        Objects.requireNonNull(content, "content");
        if (needle == null || needle.isEmpty()) {
            return List.of();
        }
        List<Highlight> found = new ArrayList<>();
        int lines = content.lineCount();
        int step = Math.max(1, needle.length());
        for (int line = 0; line < lines; line++) {
            String text = content.line(line).plainText();
            int at = 0;
            while (at + needle.length() <= text.length()) {
                if (text.regionMatches(ignoreCase, at, needle, 0, needle.length())) {
                    found.add(new Highlight(line, at, at + needle.length()));
                    at += step;
                } else {
                    at++;
                }
            }
        }
        return List.copyOf(found);
    }

    static int totalRows(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        Content content = state.content();
        int width = TextViewport.contentColumns(state, opts);
        int lines = content.lineCount();
        int total = 0;
        for (int line = 0; line < lines; line++) {
            total += TextViewportNavigation.rowsInLine(content, line, width, opts.mode());
        }
        return total;
    }

    static int firstVisibleRow(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        Content content = state.content();
        int width = TextViewport.contentColumns(state, opts);
        Anchor anchor = TextViewportNavigation.anchor(state, opts);
        int first = 0;
        for (int line = 0; line < anchor.line(); line++) {
            first += TextViewportNavigation.rowsInLine(content, line, width, opts.mode());
        }
        return first + anchor.row();
    }
}
