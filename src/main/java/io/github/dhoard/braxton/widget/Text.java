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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.TextWrap;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Widget;
import java.util.List;
import java.util.Objects;

/**
 * Styled text as a widget: it wraps to the columns it is given and paints every cell whole.
 *
 * <p>The model is the value itself, so an update that changes a word repaints the widget without
 * any other state. Wrapping goes through {@link TextWrap}, the same wrap the renderer measures
 * with, so the widget occupies the columns it paints: a style boundary never moves a cell and a
 * cluster that continues the one before it - a mark, a selector - travels with the cell it
 * belongs to instead of taking a column of its own.</p>
 *
 * <p>The widget is generic in the message type it never emits, because an element tree carries one
 * message type and a leaf that paints text belongs in every one of them.</p>
 *
 * @param <Msg> the message type of the tree this widget is placed in
 */
public final class Text<Msg> implements Widget<StyledText, Msg> {

    private static final Text<?> PLAIN = new Text<>(WidthPolicy.PINNED);

    private final WidthPolicy policy;

    private Text(WidthPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * A text widget that measures the way the default width policy measures.
     *
     * @param <Msg> the message type of the tree this widget is placed in
     * @return the widget
     */
    @SuppressWarnings("unchecked")
    public static <Msg> Text<Msg> of() {
        return (Text<Msg>) PLAIN;
    }

    /**
     * A text widget that measures with the given width policy.
     *
     * @param <Msg> the message type of the tree this widget is placed in
     * @param policy how a cluster nobody has assigned a width is measured; required
     * @return the widget
     */
    public static <Msg> Text<Msg> of(WidthPolicy policy) {
        return new Text<>(policy);
    }

    /**
     * This widget measuring with another width policy.
     *
     * @param policy the policy to measure with; required
     * @return a widget sharing nothing mutable with this one
     */
    public Text<Msg> withPolicy(WidthPolicy policy) {
        return new Text<>(policy);
    }

    /**
     * The size the wrapped value needs: as many rows as it wraps to and as many columns as its
     * widest line, both inside {@code max}.
     */
    @Override
    public Size measure(Size max, StyledText model) {
        if (model == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        List<StyledText> lines = TextWrap.wrap(model, policy, max.cols(), max.rows());
        int cols = 0;
        for (StyledText line : lines) {
            cols = Math.max(cols, TextMeasure.columns(line.plainText(), policy));
        }
        return new Size(lines.size(), cols);
    }

    @Override
    public void paint(Surface surface, Rect bounds, StyledText model) {
        surface.write(bounds, model, policy);
    }
}
