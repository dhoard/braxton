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

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.StyledText;

/**
 * One-row preparation for widgets that paint a value whose height is fixed: a badge pill, a
 * progress bar's labels, and a box title.
 *
 * <p>Surviving line breaks and tabs become spaces while each span keeps its style and link, so the
 * flattened value can still be restyled and still carries its links. The conversion is applied
 * span by span because that is what rebuilds the value with its styles; converting
 * {@link StyledText#plainText()} afterwards would return a bare string and lose both.</p>
 *
 * <p>A bare CR is not recoverable here: {@code Span} deletes it as an unrepresentable control
 * character when the value is constructed, so no later step can tell it apart from text that never
 * had one. A caller that still holds the raw string prepares it before constructing the span, with
 * {@link LineBreaks#singleRowAndTabs(String)} or {@link LineBreaks#normalize(String)}. CRLF does
 * survive into a span as LF, so this method does flatten it.</p>
 */
final class SingleRow {

    private SingleRow() {}

    /** {@code text} flat enough to paint on one row; a null value is empty. */
    static StyledText of(StyledText text) {
        if (text == null) {
            return StyledText.empty();
        }
        StyledText result = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            result = result.append(StyledText.of(LineBreaks.singleRowAndTabs(span.text()), span.style(), span.link()));
        }
        return result;
    }
}
