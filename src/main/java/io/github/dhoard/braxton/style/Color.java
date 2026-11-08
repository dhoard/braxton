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

package io.github.dhoard.braxton.style;

import java.util.Locale;
import java.util.Optional;

/**
 * A 24-bit sRGB colour with a validated value range.
 *
 * <p>Parsing accepts the {@code #RRGGBB} form used by Braxton's documented markup and builder
 * configuration. Invalid or missing input yields {@link Optional#empty()} so callers can keep
 * their previous value instead of silently rendering a replacement colour.</p>
 */
public record Color(int red, int green, int blue) {

    public static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    public static final Color BLACK = new Color(0, 0, 0);

    public Color {
        requireChannel(red, "red");
        requireChannel(green, "green");
        requireChannel(blue, "blue");
    }

    private static void requireChannel(int value, String name) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(name + " must be 0..255, was " + value);
        }
    }

    /** Parses {@code #RRGGBB}, returning empty for null, malformed, or out-of-range input. */
    public static Optional<Color> parse(String hex) {
        if (hex == null) {
            return Optional.empty();
        }
        String value = hex.strip();
        if (value.length() != 7 || value.charAt(0) != '#') {
            return Optional.empty();
        }
        int rgb = 0;
        for (int i = 1; i < value.length(); i++) {
            int digit = hexDigit(value.charAt(i));
            if (digit < 0) {
                return Optional.empty();
            }
            rgb = (rgb << 4) | digit;
        }
        return Optional.of(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
    }

    /** ASCII hex digit value, or -1. Rejects the signs and Unicode digits parseInt would accept. */
    private static int hexDigit(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    /** Parses like {@link #parse(String)} but falls back to {@code fallback} on invalid input. */
    public static Color parseOr(String hex, Color fallback) {
        return parse(hex).orElse(fallback);
    }

    /** Lower-case {@code #rrggbb} form; diagnostics and documentation only, never ANSI output. */
    public String hex() {
        return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
    }
}
