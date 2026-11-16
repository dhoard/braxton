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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;

/** A row of pill-shaped labels rendered on their segment background. */
public final class Badge {

    private final List<Segment> segments = new ArrayList<>();
    private boolean pathway;

    /**
     * One badge label and its styling.
     *
     * @param text the label painted in the segment
     * @param style the style applied to that label
     */
    public record Segment(StyledText text, Style style) {}

    /** Creates a badge with no segments. */
    public Badge() {}

    /**
     * A badge with no segments.
     *
     * @return an empty badge builder
     */
    public static Badge of() {
        return new Badge();
    }

    /**
     * Appends a label with the default segment styling.
     *
     * @param text the label to paint; required
     * @return this builder
     */
    public Badge append(StyledText text) {
        return append(text, Style.NONE);
    }

    /**
     * Appends a label on a background colour.
     *
     * @param text the label to paint; required
     * @param background the segment background, or null for none
     * @return this builder
     */
    public Badge append(StyledText text, Color background) {
        return append(text, background == null ? Style.NONE : Style.bg(background));
    }

    /**
     * Appends a label with an explicit segment style.
     *
     * @param text the label to paint; required
     * @param style the segment style, or null for none
     * @return this builder
     */
    public Badge append(StyledText text, Style style) {
        segments.add(new Segment(SingleRow.of(text), style == null ? Style.NONE : style));
        return this;
    }

    /**
     * Joins segments with a pathway arrow. Without it, adjacent pills are separated only by their own
     * padding, so the labels sit one space apart from each other on either side.
     */
    public Badge pathway() {
        pathway = true;
        return this;
    }

    /**
     * Renders the segments as one styled value.
     *
     * @return the painted badge
     */
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

    /**
     * A one-element view of the rendered badge.
     *
     * @return a text view holding {@link #render()}
     */
    public View<Object> view() {
        return Views.text(render());
    }

    /**
     * Width of the rendered badge, used by layout tests.
     *
     * @return the columns the rendered badge occupies
     */
    public int columns() {
        return TextMeasure.columns(render().plainText());
    }

    /**
     * The segments added so far, in order.
     *
     * @return an immutable copy of the segments
     */
    public List<Segment> segments() {
        return List.copyOf(segments);
    }
}
