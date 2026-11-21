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

package io.github.dhoard.braxton.asset;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;

/**
 * A FIGlet-style logo rendered as styled text.
 */
public final class Logo {

    private final String text;
    private final FigletFont font;
    private Style style = Style.NONE;
    private Color shadowColor;

    private Logo(String text, FigletFont font) {
        this.text = text == null ? "" : text;
        this.font = font;
    }

    /**
     * Creates a {@code Logo} value.
     *
     * @param text the text to process
     * @param font the FIGlet font
     * @return a logo using the supplied text and font
     */
    public static Logo of(String text, FigletFont font) {
        if (font == null) {
            throw new IllegalArgumentException("font is required");
        }
        return new Logo(text, font);
    }

    /**
     * Returns the text style.
     *
     * @param value the text style to paint with
     * @return the text style
     */
    public Logo style(Style value) {
        style = value == null ? Style.NONE : value;
        return this;
    }

    /**
     * Returns the foreground color.
     *
     * @param value the color to paint the text with
     * @return the foreground color
     */
    public Logo color(Color value) {
        style = value == null ? style : style.withForeground(value);
        return this;
    }

    /**
     * Applies a shadow color. The bundled shadow fonts encode their shadow in the glyph shapes, so
     * this value is applied to the whole art rather than guessed per glyph.
     *
     * @param value the shadow color applied to the whole art
     * @return this logo for chaining
     */
    public Logo shadowColor(Color value) {
        shadowColor = value;
        return this;
    }

    /**
     * Renders the configured FIGlet text, styling, and optional shadow.
     *
     * @return the styled FIGlet banner and optional shadow
     */
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

    /**
     * The art as a one-element tree, for a scene that paints it.
     *
     * @return the art as text
     */
    public Element<Object> element() {
        return Elements.text(render());
    }
}
