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

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

/**
 * The text preparation a one-line row widget shares: one row, and the theme's base style underneath
 * the styles the application already gave the value.
 */
final class RowText {

    private RowText() {}

    /**
     * A value flattened to the single row a list, a tree or a menu paints it on.
     *
     * <p>A break in a label would push every row below it down, so a row's text never carries one:
     * each break becomes one space, and a span's own style and link travel with the text it covers.</p>
     *
     * @param value the value; null is empty
     * @return the flattened value
     */
    static StyledText singleRow(StyledText value) {
        StyledText label = value == null ? StyledText.empty() : value;
        return LineBreaks.singleRow(label.plainText()).equals(label.plainText()) ? label : flatten(label);
    }

    /**
     * A value with a base style underneath the styles it already carries.
     *
     * @param value the value; null is empty
     * @param base the style the theme asks for; null for none
     * @return the styled value
     */
    static StyledText styled(StyledText value, Style base) {
        StyledText label = value == null ? StyledText.empty() : value;
        Style underneath = base == null ? Style.NONE : base;
        StyledText styled = StyledText.empty();
        for (StyledText.Span span : label.spans()) {
            styled = styled.append(StyledText.of(span.text(), underneath.merge(span.style()), span.link()));
        }
        return styled;
    }

    /**
     * The value with every break replaced by a space, keeping each span's own style and link.
     */
    private static StyledText flatten(StyledText value) {
        StyledText flat = StyledText.empty();
        for (StyledText.Span span : value.spans()) {
            flat = flat.append(StyledText.of(LineBreaks.singleRow(span.text()), span.style(), span.link()));
        }
        return flat;
    }
}
