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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A block quote: a coloured bar, the quoted content, and an optional background.
 *
 * <p>The bar is a one-line prefix on every quoted line, so a line break in it becomes a single
 * space instead of splitting the line into extra rows.</p>
 *
 * <p>Tabs are expanded to the same four-column stops the text view uses, so the block keeps one
 * width instead of padding to a tab that paint expands wider.</p>
 *
 * <p>Each quoted line is padded inside the bar and the space after it, which are painted in the
 * same row: a cluster with no base character of its own at the head of a line joins that space
 * instead of occupying one of its own, and a variation selector there widens it. Padding a line
 * from its own measurement leaves such a row a column short of the block or a column past it.</p>
 */
public final class Quote {

    private final List<StyledText> lines = new ArrayList<>();
    private Style borderStyle = Style.NONE;
    private Style background = Style.NONE;
    private String bar = "\u2502";

    public static Quote of() {
        return new Quote();
    }

    public Quote append(StyledText text) {
        if (text != null) {
            lines.addAll(text.lines());
        }
        return this;
    }

    public Quote append(String text) {
        return append(StyledText.plain(text));
    }

    public Quote border(Color color) {
        borderStyle = color == null ? Style.NONE : Style.fg(color);
        return this;
    }

    public Quote background(Color color) {
        background = color == null ? Style.NONE : Style.bg(color);
        return this;
    }

    /**
     * Sets the bar painted at the start of every quoted line. The bar is a one-line prefix, so
     * CRLF, CR, and LF each become one space; a null or empty value falls back to a space.
     */
    public Quote bar(String value) {
        bar = value == null || value.isEmpty() ? " " : singleLine(value);
        return this;
    }

    private static String singleLine(String value) {
        return LineBreaks.singleRow(value);
    }

    public StyledText render() {
        List<StyledText> body = new ArrayList<>(lines.size());
        for (StyledText line : lines) {
            body.add(TabStops.expand(line));
        }
        StyledText out = StyledText.empty();
        // The bar and the space after it are painted in the row before the quoted line, so the line
        // is charged inside them rather than measured on its own: a cluster with no base of its own
        // at the head of the line joins that space and claims no column, while a variation selector
        // there widens it by a column the line measured on its own never paid for.
        String prefix = bar + " ";
        int prefixColumns = TextMeasure.columns(prefix);
        int width = 0;
        for (StyledText line : body) {
            width = Math.max(width, TextMeasure.columns(prefix + line.plainText()) - prefixColumns);
        }
        for (int i = 0; i < body.size(); i++) {
            StyledText line = body.get(i);
            int pad = GridRows.pad(prefix, line.plainText(), width);
            if (i > 0) {
                out = out.append("\n");
            }
            out = out.append(StyledText.of(bar, borderStyle))
                    .append(StyledText.of(" ", background))
                    .append(restyle(line, background))
                    .append(StyledText.of(" ".repeat(pad), background));
        }
        return out;
    }

    /** Applies the quote background to every span, keeping each span's own styling and link. */
    private static StyledText restyle(StyledText line, Style background) {
        StyledText body = StyledText.empty();
        for (StyledText.Span span : line.spans()) {
            body = body.append(StyledText.of(span.text(), span.style().merge(background), span.link()));
        }
        return body;
    }

    public View<Object> view() {
        return Views.text(render());
    }
}
