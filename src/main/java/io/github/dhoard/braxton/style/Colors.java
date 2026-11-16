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

/**
 * The named palette Braxton documents for application chrome.
 *
 * <p>Colours are values, not escape-sequence strings: styling is only turned into terminal bytes
 * by the renderer. Applications may use their own {@link Color} values instead.</p>
 */
public final class Colors {

    /** Pure white. */
    public static final Color WHITE = Color.WHITE;
    /** Pure black. */
    public static final Color BLACK = Color.BLACK;

    /** Palette colour gray 100 (#e9eaeb). */
    public static final Color GRAY_100 = rgb("#e9eaeb");
    /** Palette colour gray 200 (#d5d7da). */
    public static final Color GRAY_200 = rgb("#d5d7da");
    /** Palette colour gray 300 (#a4a7ae). */
    public static final Color GRAY_300 = rgb("#a4a7ae");
    /** Palette colour gray 400 (#717680). */
    public static final Color GRAY_400 = rgb("#717680");
    /** Palette colour gray 500 (#535862). */
    public static final Color GRAY_500 = rgb("#535862");
    /** Palette colour gray 600 (#414651). */
    public static final Color GRAY_600 = rgb("#414651");

    /** Palette colour error 100 (#fecdc9). */
    public static final Color ERROR_100 = rgb("#fecdc9");
    /** Palette colour error 200 (#fda19b). */
    public static final Color ERROR_200 = rgb("#fda19b");
    /** Palette colour error 300 (#f97066). */
    public static final Color ERROR_300 = rgb("#f97066");
    /** Palette colour error 400 (#f04438). */
    public static final Color ERROR_400 = rgb("#f04438");
    /** Palette colour error 500 (#d92d20). */
    public static final Color ERROR_500 = rgb("#d92d20");
    /** Palette colour error 600 (#b32318). */
    public static final Color ERROR_600 = rgb("#b32318");

    /** Palette colour warning 100 (#fedf89). */
    public static final Color WARNING_100 = rgb("#fedf89");
    /** Palette colour warning 200 (#fec84b). */
    public static final Color WARNING_200 = rgb("#fec84b");
    /** Palette colour warning 300 (#fdb022). */
    public static final Color WARNING_300 = rgb("#fdb022");
    /** Palette colour warning 400 (#f79009). */
    public static final Color WARNING_400 = rgb("#f79009");
    /** Palette colour warning 500 (#dc6803). */
    public static final Color WARNING_500 = rgb("#dc6803");
    /** Palette colour warning 600 (#b54708). */
    public static final Color WARNING_600 = rgb("#b54708");

    /** Palette colour success 100 (#a9efc5). */
    public static final Color SUCCESS_100 = rgb("#a9efc5");
    /** Palette colour success 200 (#75e0a7). */
    public static final Color SUCCESS_200 = rgb("#75e0a7");
    /** Palette colour success 300 (#47cd89). */
    public static final Color SUCCESS_300 = rgb("#47cd89");
    /** Palette colour success 400 (#17b26a). */
    public static final Color SUCCESS_400 = rgb("#17b26a");
    /** Palette colour success 500 (#079455). */
    public static final Color SUCCESS_500 = rgb("#079455");
    /** Palette colour success 600 (#067647). */
    public static final Color SUCCESS_600 = rgb("#067647");

    /** Palette colour yellow 200 (#fde172). */
    public static final Color YELLOW_200 = rgb("#fde172");
    /** Palette colour yellow 400 (#eaaa08). */
    public static final Color YELLOW_400 = rgb("#eaaa08");
    /** Palette colour orange 400 (#ef6820). */
    public static final Color ORANGE_400 = rgb("#ef6820");
    /** Palette colour rose 400 (#f63d68). */
    public static final Color ROSE_400 = rgb("#f63d68");
    /** Palette colour pink 400 (#ee46bc). */
    public static final Color PINK_400 = rgb("#ee46bc");
    /** Palette colour fuchsia 400 (#d444f1). */
    public static final Color FUCHSIA_400 = rgb("#d444f1");
    /** Palette colour purple 400 (#7a5af8). */
    public static final Color PURPLE_400 = rgb("#7a5af8");
    /** Palette colour violet 400 (#875bf7). */
    public static final Color VIOLET_400 = rgb("#875bf7");
    /** Palette colour indigo 400 (#6172f3). */
    public static final Color INDIGO_400 = rgb("#6172f3");
    /** Palette colour blue 400 (#2e90fa). */
    public static final Color BLUE_400 = rgb("#2e90fa");
    /** Palette colour cyan 400 (#0ba5ec). */
    public static final Color CYAN_400 = rgb("#0ba5ec");
    /** Palette colour teal 400 (#15b79e). */
    public static final Color TEAL_400 = rgb("#15b79e");

    private Colors() {}

    private static Color rgb(String hex) {
        return Color.parse(hex).orElseThrow(() -> new IllegalArgumentException("bad palette " + hex));
    }
}
