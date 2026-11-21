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

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * Vertical color gradient over styled text.
 *
 * <p>The color position advances one cluster at a time, over the clusters of each span in order.
 * A style boundary that splits one grapheme - a base in one span, its combining mark or variation
 * selector in the next - therefore colors the two pieces from their own positions, and only the
 * painted text stays unchanged: the two spans still paint one cell. Malformed or unusable colors
 * are ignored: the original text is returned unchanged rather than partially repainted.</p>
 */
public final class Gradient {

    /**
     * Gradient direction.
     */
    public enum Direction {
        /**
         * Applies the color ramp down rows.
         */
        VERTICAL,
        /**
         * Applies the color ramp across columns.
         */
        HORIZONTAL,
        /**
         * Applies the color ramp across both rows and columns.
         */
        DIAGONAL
    }

    private Gradient() {}

    /**
     * Applies the color ramp across the text in the requested direction.
     *
     * @param text the text to process
     * @param colors the gradient color stops
     * @param direction the layout or navigation direction
     * @return the text with the gradient applied
     */
    public static StyledText apply(
            StyledText text, List<io.github.dhoard.braxton.style.Color> colors, Direction direction) {
        String plain = text.plainText();
        List<String> clusters = TextMeasure.graphemes(plain);
        if (clusters.isEmpty() || colors == null || colors.isEmpty()) {
            return text;
        }
        List<io.github.dhoard.braxton.style.Color> palette = new ArrayList<>();
        for (io.github.dhoard.braxton.style.Color color : colors) {
            if (color != null) {
                palette.add(color);
            }
        }
        if (palette.isEmpty()) {
            return text;
        }
        Direction mode = direction == null ? Direction.VERTICAL : direction;
        List<String> lines = List.of(plain.split("\n", -1));
        int height = lines.size();
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, TextMeasure.columns(line));
        }
        StyledText out = StyledText.empty();
        int lineIndex = 0;
        int column = 0;
        for (StyledText.Span span : text.spans()) {
            for (String cluster : TextMeasure.graphemes(span.text())) {
                if ("\n".equals(cluster)) {
                    out = out.append("\n");
                    lineIndex++;
                    column = 0;
                    continue;
                }
                double fraction = switch (mode) {
                    case VERTICAL -> height <= 1 ? 0.0 : (double) lineIndex / (height - 1);
                    case HORIZONTAL -> widest <= 1 ? 0.0 : (double) column / (widest - 1);
                    case DIAGONAL -> {
                        double vertical = height <= 1 ? 0.0 : (double) lineIndex / (height - 1);
                        double horizontal = widest <= 1 ? 0.0 : (double) column / (widest - 1);
                        yield (vertical + horizontal) / 2.0;
                    }
                };
                io.github.dhoard.braxton.style.Color color = interpolate(palette, fraction);
                out = out.append(StyledText.of(cluster, span.style().withForeground(color), span.link()));
                column += TextMeasure.clusterColumns(cluster, io.github.dhoard.braxton.style.WidthPolicy.PINNED);
            }
        }
        return out;
    }

    static io.github.dhoard.braxton.style.Color interpolate(
            List<io.github.dhoard.braxton.style.Color> palette, double fraction) {
        double clamped = Math.max(0, Math.min(1, fraction));
        if (palette.size() == 1) {
            return palette.get(0);
        }
        double scaled = clamped * (palette.size() - 1);
        int index = (int) Math.floor(scaled);
        int next = Math.min(palette.size() - 1, index + 1);
        double t = scaled - index;
        io.github.dhoard.braxton.style.Color a = palette.get(index);
        io.github.dhoard.braxton.style.Color b = palette.get(next);
        return new io.github.dhoard.braxton.style.Color(
                (int) Math.round(a.red() + (b.red() - a.red()) * t),
                (int) Math.round(a.green() + (b.green() - a.green()) * t),
                (int) Math.round(a.blue() + (b.blue() - a.blue()) * t));
    }

    /**
     * The gradient as a leaf of an element tree.
     *
     * @param <Msg> the message type of the tree
     * @param text the text to shade; required
     * @param colors the colors to run through; required
     * @param direction the axis the colors run along; required
     * @return the element
     */
    public static <Msg> Element<Msg> element(
            StyledText text, List<io.github.dhoard.braxton.style.Color> colors, Direction direction) {
        return RenderedText.of(cols -> apply(text, colors, direction));
    }
}
