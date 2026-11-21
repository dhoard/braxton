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

package io.github.dhoard.braxton.style;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable set of styles for every semantic token.
 */
public final class Theme {

    private final String name;
    private final Map<ThemeToken, Style> styles;

    /**
     * Builds a theme; any missing token is plain.
     *
     * @param name the name to resolve
     * @param styles the styles to apply
     */
    public Theme(String name, Map<ThemeToken, Style> styles) {
        this.name = Objects.requireNonNull(name, "name");
        Map<ThemeToken, Style> copy = new EnumMap<>(ThemeToken.class);
        for (ThemeToken token : ThemeToken.values()) copy.put(token, Style.NONE);
        copy.putAll(Objects.requireNonNull(styles, "styles"));
        this.styles = Map.copyOf(copy);
    }

    /**
     * Returns the display name.
     *
     * @return the display name
     */
    public String name() {
        return name;
    }

    /**
     * The style for one semantic token.
     *
     * @param token the semantic theme token
     * @return the style for one semantic token
     */
    public Style style(ThemeToken token) {
        return styles.get(token);
    }

    /**
     * A copy of this theme with one token replaced.
     *
     * @param token the semantic theme token
     * @param style the style to apply
     * @return a copy of this theme with that token replaced
     */
    public Theme with(ThemeToken token, Style style) {
        Map<ThemeToken, Style> copy = new EnumMap<>(styles);
        copy.put(token, style);
        return new Theme(name, copy);
    }
}
