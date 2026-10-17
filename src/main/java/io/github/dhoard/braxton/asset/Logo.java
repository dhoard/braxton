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

package io.github.dhoard.braxton.asset;

import java.util.List;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** A FIGlet-style logo rendered as styled text. */
public final class Logo {

    private final String text;
    private final FigletFont font;
    private Style style = Style.NONE;
    private Color shadowColor;

    private Logo(String text, FigletFont font) {
        this.text = text == null ? "" : text;
        this.font = font;
    }

    public static Logo of(String text, FigletFont font) {
        if (font == null) {
            throw new IllegalArgumentException("font is required");
        }
        return new Logo(text, font);
    }

    public Logo style(Style value) {
        style = value == null ? Style.NONE : value;
        return this;
    }

    public Logo color(Color value) {
        style = value == null ? style : style.withForeground(value);
        return this;
    }

    /**
     * Applies a shadow colour. The bundled shadow fonts encode their shadow in the glyph shapes, so
     * this value is applied to the whole art rather than guessed per glyph.
     */
    public Logo shadowColor(Color value) {
        shadowColor = value;
        return this;
    }

    public StyledText render() {
        List<String> rows = font.render(text);
        StyledText out = StyledText.empty();
        Style effective = shadowColor == null ? style : style.withForeground(shadowColor);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out = out.append("\n");
            }
            out = out.append(StyledText.of(rows.get(i), effective));
        }
        return out;
    }

    public View<Object> view() {
        return Views.text(render());
    }
}
