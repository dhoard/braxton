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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** A row of pill-shaped labels rendered on their segment background. */
public final class Badge {

    private final List<Segment> segments = new ArrayList<>();
    private boolean pathway;

    /** One badge label and its styling. */
    public record Segment(StyledText text, Style style) {
    }

    public static Badge of() {
        return new Badge();
    }

    public Badge append(StyledText text) {
        return append(text, Style.NONE);
    }

    public Badge append(StyledText text, Color background) {
        return append(text, background == null ? Style.NONE : Style.bg(background));
    }

    public Badge append(StyledText text, Style style) {
        segments.add(new Segment(singleRow(text), style == null ? Style.NONE : style));
        return this;
    }

    /** A pill occupies one row, so line breaks and tabs become spaces. */
    private static StyledText singleRow(StyledText text) {
        if (text == null) {
            return StyledText.empty();
        }
        StyledText result = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            result = result.append(StyledText.of(
                    span.text().replace('\n', ' ').replace('\t', ' '), span.style(), span.link()));
        }
        return result;
    }

    /** Joins segments with a pathway arrow; the default is a single space. */
    public Badge pathway() {
        pathway = true;
        return this;
    }

    public StyledText render() {
        StyledText out = StyledText.empty();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0 && pathway) {
                out = out.append(StyledText.plain(" \u2192 "));
            }
            Segment segment = segments.get(i);
            out = out.append(StyledText.of(" ", segment.style()))
                    .append(restyle(segment.text(), segment.style()))
                    .append(StyledText.of(" ", segment.style()));
        }
        return out;
    }

    /** Applies the segment style to every label span, keeping each span's own styling. */
    private static StyledText restyle(StyledText text, Style style) {
        StyledText out = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            out = out.append(StyledText.of(span.text(), span.style().merge(style), span.link()));
        }
        return out;
    }

    public View<Object> view() {
        return Views.text(render());
    }

    /** Width of the rendered badge, used by layout tests. */
    public int columns() {
        return TextMeasure.columns(render().plainText());
    }

    public List<Segment> segments() {
        return List.copyOf(segments);
    }
}
