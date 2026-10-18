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

import java.util.HashMap;
import java.util.Map;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * Pixel art rendered from an {@link ImageAsset}: one symbol per pixel, coloured per pixel, with
 * transparent pixels left blank. A transparent pixel spans as many columns as the symbol does, so
 * a wide symbol keeps the pixel grid aligned.
 *
 * <p>A pixel is either fully transparent (alpha zero) and left blank, or painted with its own RGB
 * value: a terminal cell has no alpha channel, so intermediate alpha is not blended.</p>
 */
public final class ImageView {

    private final ImageAsset image;
    private String symbol = "\u2588";
    private int scale = 1;
    private final Map<Integer, Color> replacements = new HashMap<>();

    private ImageView(ImageAsset image) {
        this.image = image;
    }

    public static ImageView of(ImageAsset image) {
        if (image == null) {
            throw new IllegalArgumentException("image is required");
        }
        return new ImageView(image);
    }

    public ImageView symbol(String value) {
        symbol = value == null || value.isEmpty() ? "\u2588" : value;
        return this;
    }

    /** Horizontal repetition of each pixel; values below one are treated as one. */
    public ImageView scale(int value) {
        scale = Math.max(1, value);
        return this;
    }

    /** Replaces one exact RGB value with a colour; invalid hex input is ignored. */
    public ImageView replace(String sourceHex, String targetHex) {
        Color source = Color.parse(sourceHex).orElse(null);
        Color target = Color.parse(targetHex).orElse(null);
        if (source != null && target != null) {
            replacements.put(rgb(source), target);
        }
        return this;
    }

    private static int rgb(Color color) {
        return (color.red() << 16) | (color.green() << 8) | color.blue();
    }

    public StyledText render() {
        int cellColumns = Math.max(1, TextMeasure.columns(symbol));
        StyledText out = StyledText.empty();
        for (int y = 0; y < image.height(); y++) {
            if (y > 0) {
                out = out.append("\n");
            }
            for (int x = 0; x < image.width(); x++) {
                int argb = image.argb(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    out = out.append(" ".repeat(cellColumns * scale));
                    continue;
                }
                int rgb = argb & 0xFFFFFF;
                Color color = replacements.getOrDefault(rgb,
                        new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
                out = out.append(StyledText.of(symbol.repeat(scale), Style.fg(color)));
            }
        }
        return out;
    }

    public View<Object> view() {
        return Views.text(render());
    }
}
