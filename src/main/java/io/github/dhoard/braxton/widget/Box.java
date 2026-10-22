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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A framed block with an optional title in the top edge.
 *
 * <p>All five legacy border styles are represented by {@link Border}; a responsive box simply uses
 * the available width, while a fixed-width box truncates content to its inner width. Tabs are
 * expanded to the same four-column stops the text view uses, so a measured line and a painted line
 * always agree.</p>
 *
 * <p>A frame reserves one cell per edge character, so every rendered row occupies exactly the box
 * width. The content and the title are therefore measured inside the frame: a row is painted as one
 * grapheme-cluster sequence, and a cluster with no base character of its own - a combining mark, a
 * variation selector, a joiner - joins the frame cell beside it instead of occupying a cell, which
 * is a different number of columns from measuring that content on its own.</p>
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

    public static Box of(StyledText content) {
        return new Box(content);
    }

    public static Box of(String content) {
        return new Box(StyledText.plain(content));
    }

    public Box border(Border value) {
        border = value == null ? Border.NONE : value;
        return this;
    }

    public Box style(Style value) {
        style = value == null ? Style.NONE : value;
        return this;
    }

    public Box title(String value) {
        // Prepare the raw title before the span boundary: Span deletes a bare CR as a control, so
        // flattening afterwards painted "one\rtwo" as "onetwo" while LF and CRLF kept a space.
        title = value == null || value.isEmpty()
                ? StyledText.empty()
                : StyledText.plain(LineBreaks.singleRowAndTabs(value));
        return this;
    }

    public Box title(StyledText value) {
        title = value == null ? StyledText.empty() : singleLine(value);
        return this;
    }

    /**
     * The title sits in a one-line top edge, so surviving line breaks and tabs become spaces. A
     * span cannot hold a bare CR, so only the raw String overload can preserve that separator.
     */
    private static StyledText singleLine(StyledText value) {
        StyledText result = StyledText.empty();
        for (StyledText.Span span : value.spans()) {
            result = result.append(StyledText.of(
                    LineBreaks.singleRowAndTabs(span.text()), span.style(), span.link()));
        }
        return result;
    }

    /** Uses the width passed to {@link #render}, discarding any fixed width set earlier. */
    public Box responsive() {
        fixedWidth = -1;
        return this;
    }

    public Box width(int value) {
        fixedWidth = value;
        return this;
    }

    /** Renders the box at {@code width} columns (or its fixed width when configured). */
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
            StyledText fitted = truncate(line, Math.max(0, limit - 2));
            if (TextMeasure.columns(vertical + fitted.plainText()) > limit - 2) {
                // The left frame cell took a column the content claimed for itself, so one fewer
                // content column keeps the row inside the frame instead of past its right edge.
                fitted = truncate(line, Math.max(0, limit - 3));
            }
            clipped.add(fitted);
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
                    .append(StyledText.of(" ".repeat(Math.max(0, limit - 1 - painted))
                            + vertical, style));
        }
        out = out.append("\n").append(StyledText.of(
                Border.glyph(border.bottomLeft())
                        + Border.glyph(border.horizontal()).repeat(limit - 2)
                        + Border.glyph(border.bottomRight()), style));
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
                // Even the label's own columns no longer fit the space a labelled edge reserves, so
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

    /** Applies the frame style to every span, keeping each span's own styling and link. */
    private static StyledText restyle(StyledText text, Style style) {
        StyledText result = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            result = result.append(StyledText.of(span.text(), span.style().merge(style), span.link()));
        }
        return result;
    }

    /** Truncates to {@code maxColumns} without splitting a cluster, preserving span styling. */
    private static StyledText truncate(StyledText text, int maxColumns) {
        StyledText result = StyledText.empty();
        int remaining = Math.max(0, maxColumns);
        for (StyledText.Span span : text.spans()) {
            if (remaining <= 0) {
                break;
            }
            if (span.text().isEmpty()) {
                continue;
            }
            String fitted = TextMeasure.truncate(span.text(), remaining);
            if (fitted.isEmpty()) {
                break;
            }
            result = result.append(StyledText.of(fitted, span.style(), span.link()));
            remaining -= TextMeasure.columns(fitted);
        }
        return result;
    }

    public View<Object> view(int width) {
        return Views.text(render(width));
    }

    /** Whether {@link #render} uses its argument instead of a fixed width. */
    public boolean isResponsive() {
        return fixedWidth <= 0;
    }
}
