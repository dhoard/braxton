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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.Widget;
import java.util.ArrayList;
import java.util.List;

/**
 * A framed block with an optional title in the top edge.
 *
 * <p>All five legacy border styles are represented by {@link Border}; a responsive box simply uses
 * the available width, while a fixed-width box truncates content to its inner width. Tabs are
 * expanded to the same four-column stops the text view uses, so a measured line and a painted line
 * always agree.</p>
 *
 * <p>The box is configuration; the content it frames is captured when the box is built, so an
 * application places it with {@link #element()} and the frame renders at whatever width the layout
 * gives it. {@link #render(int)} stays the pure renderer the element delegates to, which is what
 * makes a box assertable without a screen.</p>
 *
 * <p>A frame reserves one cell per edge character, so every rendered row occupies exactly the box
 * width. The content and the title are therefore measured inside the frame: a row is painted as one
 * grapheme-cluster sequence, and a cluster with no base character of its own - a combining mark, a
 * variation selector, a joiner - joins the frame cell beside it instead of occupying a cell, which
 * is a different number of columns from measuring that content on its own. That joined sequence is
 * also what a clip is measured against: a style boundary inside one cluster does not change the
 * columns the row occupies, so splitting a run never buys the content an extra column.</p>
 */
public final class Box {

    private Border border = Border.SINGLE;
    private Style style = Style.NONE;
    private StyledText title = StyledText.empty();
    private StyledText content;
    private int fixedWidth = -1;

    private Box(StyledText content) {
        this.content = content == null ? StyledText.empty() : content;
    }

    /**
     * A box around already-styled content.
     *
     * @param content the content to frame
     * @return the box builder
     */
    public static Box of(StyledText content) {
        return new Box(content);
    }

    /**
     * A box around plain text.
     *
     * @param content the content to frame
     * @return the box builder
     */
    public static Box of(String content) {
        return new Box(StyledText.plain(content));
    }

    /**
     * The same box with a different frame.
     *
     * @param value the frame characters, or null for none
     * @return this builder
     */
    public Box border(Border value) {
        border = value == null ? Border.NONE : value;
        return this;
    }

    /**
     * The same box with a different frame style.
     *
     * @param value the frame style, or null for plain
     * @return this builder
     */
    public Box style(Style value) {
        style = value == null ? Style.NONE : value;
        return this;
    }

    /**
     * The same box with a plain-text title.
     *
     * @param value the title; line breaks become spaces
     * @return this builder
     */
    public Box title(String value) {
        // Prepare the raw title before the span boundary: Span deletes a bare CR as a control, so
        // flattening afterward painted "one\rtwo" as "onetwo" while LF and CRLF kept a space.
        title = value == null || value.isEmpty()
                ? StyledText.empty()
                : StyledText.plain(LineBreaks.singleRowAndTabs(value));
        return this;
    }

    /**
     * The same box with a styled title.
     *
     * @param value the title
     * @return this builder
     */
    public Box title(StyledText value) {
        title = SingleRow.of(value);
        return this;
    }

    /**
     * Uses the width passed to {@link #render}, discarding any fixed width set earlier.
     *
     * @return this builder
     */
    public Box responsive() {
        fixedWidth = -1;
        return this;
    }

    /**
     * The same box with a fixed width.
     *
     * @param value the fixed width in columns
     * @return this builder
     */
    public Box width(int value) {
        fixedWidth = value;
        return this;
    }

    /**
     * Renders the box at {@code width} columns (or its fixed width when configured).
     *
     * @param width the available width when the box is responsive
     * @return the rendered box
     */
    public StyledText render(int width) {
        // The two frame columns are the floor: a narrower box would have a negative inner width.
        int limit = fixedWidth > 0 ? Math.max(2, fixedWidth) : Math.max(2, width);
        // The text view expands a tab to the next four-column stop, so the frame must measure the
        // expanded line; otherwise a padded or truncated line paints past the frame's width.
        List<StyledText> lines = new ArrayList<>();
        for (StyledText line : content.lines()) {
            lines.add(TabStops.expand(line));
        }
        String vertical = Border.glyph(border.vertical());
        List<StyledText> clipped = new ArrayList<>();
        for (StyledText line : lines) {
            // One column of the row belongs to the right frame cell, and the content shares the rest
            // with the left one: the row is painted as a single cluster sequence, so a cluster with
            // no base of its own at the head of the content joins the left cell and can widen it.
            // Charging the joined sequence leaves the row no wider than the frame, which is what
            // keeps the padding below non-negative.
            clipped.add(truncate(vertical, line, Math.max(0, limit - 1)));
        }
        if (!border.isVisible()) {
            // Border is a value type, so ask the border itself whether it draws a frame instead of
            // comparing against the NONE constant: a value-equal border must not add an inset.
            if (fixedWidth <= 0) {
                return content;
            }
            // No frame to draw, but a fixed width still bounds the content it would have framed.
            StyledText bounded = StyledText.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    bounded = bounded.append("\n");
                }
                bounded = bounded.append(truncate(lines.get(i), limit));
            }
            return bounded;
        }
        StyledText out = StyledText.empty();
        out = out.append(top(limit));
        for (StyledText line : clipped) {
            // The row is one cluster sequence, so the content is counted with the frame cell beside
            // it: a leading cluster with no base of its own occupies that cell rather than starting
            // one of its own, and padding from the content measured alone leaves the row short by a
            // column - every cell after it lands left of the frame above and below it.
            int painted = TextMeasure.columns(vertical + line.plainText());
            out = out.append("\n")
                    .append(StyledText.of(vertical, style))
                    .append(line)
                    // The clip above charges this same joined sequence, so the pad cannot go
                    // negative; the guard only keeps a row that somehow overran from asking for a
                    // negative count of spaces.
                    .append(StyledText.of(" ".repeat(Math.max(0, limit - 1 - painted)) + vertical, style));
        }
        out = out.append("\n")
                .append(StyledText.of(
                        Border.glyph(border.bottomLeft())
                                + Border.glyph(border.horizontal()).repeat(limit - 2)
                                + Border.glyph(border.bottomRight()),
                        style));
        return out;
    }

    private StyledText top(int width) {
        int inner = Math.max(0, width - 2);
        String label = title.plainText();
        String topLeft = Border.glyph(border.topLeft());
        String topRight = Border.glyph(border.topRight());
        String horizontal = Border.glyph(border.horizontal());
        StyledText out = StyledText.of(topLeft, style);
        // A label needs a dash, a space either side of it, so an inner width below three has no
        // room; drawing the decorations anyway would push the top edge past the box width.
        if (label.isEmpty() || inner < 3) {
            out = out.append(StyledText.of(horizontal.repeat(inner), style));
        } else {
            StyledText fitted = truncate(title, Math.max(0, inner - 3));
            if (fitted.plainText().isEmpty()) {
                // No cluster fits the label budget (a wide glyph needs two columns). Keeping the
                // decorations would leave a hole in the edge where the title is not drawn, so the
                // narrow box gets a plain edge instead.
                out = out.append(StyledText.of(horizontal.repeat(inner), style));
                return out.append(StyledText.of(topRight, style));
            }
            // The label sits between the dash-space before it and the space after it, and the three
            // are painted as one sequence: a cluster with no base of its own at either end joins the
            // decoration cell beside it, so the label's columns are counted inside that sequence.
            int painted = TextMeasure.columns(horizontal + " " + fitted.plainText() + " ");
            if (painted > inner) {
                // Even the label's own columns no longer fit the space a labeled edge reserves, so
                // the edge is drawn plain rather than leaving a hole where the label was dropped.
                out = out.append(StyledText.of(horizontal.repeat(inner), style));
                return out.append(StyledText.of(topRight, style));
            }
            out = out.append(StyledText.of(horizontal + " ", style))
                    .append(restyle(fitted, style))
                    .append(StyledText.of(" " + horizontal.repeat(inner - painted), style));
        }
        return out.append(StyledText.of(topRight, style));
    }

    /**
     * Applies the frame style to every span, keeping each span's own styling and link.
     */
    private static StyledText restyle(StyledText text, Style style) {
        StyledText result = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            result = result.append(StyledText.of(span.text(), span.style().merge(style), span.link()));
        }
        return result;
    }

    /**
     * Truncates to {@code maxColumns} without splitting a cluster, preserving span styling.
     */
    private static StyledText truncate(StyledText text, int maxColumns) {
        return truncate("", text, maxColumns);
    }

    /**
     * Truncates to {@code maxColumns} without splitting a cluster, preserving span styling.
     * {@code edge} is the text painted immediately before {@code text} - a frame cell or an edge
     * decoration - and shares the budget.
     *
     * <p>The columns are charged to the clusters of the joined plain text rather than to each span
     * measured on its own. A style boundary can split one cluster, and the joined cluster is what
     * the frame measures and the paint path draws: a variation selector that opens a span claims no
     * column by itself, yet forces the base before it to a two-column cell once joined. A per-span
     * budget under-counts the row by exactly the columns the join gains, and the row paints past
     * the frame's right edge.</p>
     */
    private static StyledText truncate(String edge, StyledText text, int maxColumns) {
        int chars = fittedChars(edge, text.plainText(), maxColumns);
        return chars <= 0 ? StyledText.empty() : prefix(text, chars);
    }

    /**
     * UTF-16 units of {@code plain} that fit {@code maxColumns} columns once appended to
     * {@code edge}, cut at a cluster boundary of the joined sequence. As in
     * {@link TextMeasure#truncate}, a cluster that does not fit is dropped along with everything
     * after it; only the part of it belonging to the content is dropped, never the edge beside it.
     */
    private static int fittedChars(String edge, String plain, int maxColumns) {
        if (plain.isEmpty() || maxColumns <= 0) {
            return 0;
        }
        int used = 0;
        int offset = 0;
        int taken = 0;
        for (String cluster : TextMeasure.graphemes(edge + plain)) {
            int width = TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
            if (used + width > maxColumns) {
                break;
            }
            used += width;
            offset += cluster.length();
            taken = Math.max(taken, offset - edge.length());
        }
        return taken;
    }

    /**
     * The leading {@code chars} UTF-16 units of {@code text}, keeping each span's own style and
     * link. {@code chars} is a boundary of the line's joined cluster sequence, so a cluster the
     * styling splits survives as the pieces the paint path rejoins.
     */
    private static StyledText prefix(StyledText text, int chars) {
        StyledText result = StyledText.empty();
        int remaining = chars;
        for (StyledText.Span span : text.spans()) {
            if (remaining <= 0) {
                break;
            }
            int taken = Math.min(remaining, span.text().length());
            result = result.append(StyledText.of(span.text().substring(0, taken), span.style(), span.link()));
            remaining -= taken;
        }
        return result;
    }

    /**
     * The box as a leaf of an element tree.
     *
     * <p>The box is measured at the width it renders to, so it occupies exactly the cells it
     * paints: as many rows as the frame and the content need, and as many columns as the frame is
     * wide. A responsive box takes the width it is offered; a fixed-width one keeps its own and is
     * clipped when the region is narrower.</p>
     *
     * @param <Msg> the message type of the tree the box is placed in
     * @return the element
     */
    public <Msg> Element<Msg> element() {
        return new Element.Leaf<Void, Msg>(new Renderer<Msg>(), null, Sizing.measured());
    }

    /**
     * The leaf widget behind {@link #element()}.
     *
     * <p>It holds configuration rather than a model, because a box has no state of its own: what it
     * frames was decided when it was built. The configuration is read at paint time and never
     * mutated, so one built box can be placed in a tree that is rebuilt every frame.</p>
     *
     * @param <Msg> the message type of the tree
     */
    private final class Renderer<Msg> implements Widget<Void, Msg> {

        @Override
        public Size measure(Size max, Void model) {
            if (max.rows() <= 0 || max.cols() <= 0) {
                return new Size(0, 0);
            }
            List<StyledText> lines = render(max.cols()).lines();
            int cols = fixedWidth > 0 ? Math.min(max.cols(), Math.max(2, fixedWidth)) : max.cols();
            return new Size(Math.min(max.rows(), lines.size()), cols);
        }

        @Override
        public void paint(Surface surface, Rect bounds, Void model) {
            if (bounds.rows() <= 0 || bounds.cols() <= 0) {
                return;
            }
            List<StyledText> lines = render(bounds.cols()).lines();
            int limit = Math.min(bounds.rows(), lines.size());
            for (int index = 0; index < limit; index++) {
                // The renderer already clipped each row to the box width, so the writer only has to
                // place them: a row is painted whole, never wrapped again.
                surface.writeLine(
                        bounds.row() + index, bounds.col(), bounds.cols(), lines.get(index), WidthPolicy.PINNED);
            }
        }
    }

    /**
     * Whether {@link #render} uses its argument instead of a fixed width.
     *
     * @return whether {@link #render(int)} uses its argument
     */
    public boolean isResponsive() {
        return fixedWidth <= 0;
    }
}
