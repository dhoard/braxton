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

/**
 * The three built-in themes.
 */
public final class Themes {

    /**
     * The built-in light theme.
     */
    public static final Theme LIGHT = build("light", false);
    /**
     * The built-in dark theme.
     */
    public static final Theme DARK = build("dark", true);
    /**
     * The built-in high-contrast theme.
     */
    public static final Theme HIGH_CONTRAST = highContrast();

    private Themes() {}

    private static Theme build(String name, boolean dark) {
        Color text = dark ? Color.WHITE : Color.BLACK;
        Color background = dark ? Color.BLACK : Color.WHITE;
        Color muted = dark ? new Color(150, 150, 150) : new Color(90, 90, 90);
        Map<ThemeToken, Style> styles = new EnumMap<>(ThemeToken.class);
        styles.put(ThemeToken.TEXT, Style.fg(text));
        styles.put(ThemeToken.MUTED, Style.fg(muted));
        styles.put(ThemeToken.ACCENT, Style.fg(new Color(60, 120, 220)).withBold());
        styles.put(ThemeToken.SUCCESS, Style.fg(new Color(40, 150, 80)));
        styles.put(ThemeToken.WARNING, Style.fg(new Color(190, 130, 20)));
        styles.put(ThemeToken.ERROR, Style.fg(new Color(200, 50, 50)).withBold());
        styles.put(ThemeToken.SELECTED, Style.bg(dark ? new Color(60, 70, 90) : new Color(210, 225, 245)));
        styles.put(ThemeToken.BORDER, Style.fg(muted));
        styles.put(ThemeToken.BACKGROUND, Style.bg(background));
        styles.put(ThemeToken.DISABLED, Style.fg(muted));
        styles.put(ThemeToken.FOCUS, Style.fg(new Color(255, 180, 40)).withUnderline());
        return new Theme(name, styles);
    }

    private static Theme highContrast() {
        Map<ThemeToken, Style> styles = new EnumMap<>(ThemeToken.class);
        for (ThemeToken token : ThemeToken.values())
            styles.put(token, Style.fg(Color.WHITE).withBold());
        styles.put(ThemeToken.BACKGROUND, Style.bg(Color.BLACK));
        styles.put(
                ThemeToken.SELECTED,
                Style.NONE
                        .withForeground(Color.BLACK)
                        .withBackground(Color.WHITE)
                        .withInvert());
        return new Theme("high-contrast", styles);
    }
}
