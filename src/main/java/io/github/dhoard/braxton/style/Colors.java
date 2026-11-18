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

    /**
     * The sixteen ANSI colours, as xterm defines them: eight normal, then eight bright.
     *
     * <p>These are the values a terminal that has not been reconfigured displays, which is why they
     * are also the palette an {@link ColorProfile#ANSI16} session maps to. A palette index, not the
     * colour, is what such a session emits, so a terminal that <em>has</em> been reconfigured shows
     * its own colour rather than the value listed here.</p>
     */
    private static final Color[] ANSI_16 = {
        rgb("#000000"), rgb("#800000"), rgb("#008000"), rgb("#808000"),
        rgb("#000080"), rgb("#800080"), rgb("#008080"), rgb("#c0c0c0"),
        rgb("#808080"), rgb("#ff0000"), rgb("#00ff00"), rgb("#ffff00"),
        rgb("#0000ff"), rgb("#ff00ff"), rgb("#00ffff"), rgb("#ffffff"),
    };

    /**
     * The six levels of the 6x6x6 colour cube the xterm 256-colour palette adds.
     *
     * <p>The levels are not evenly spaced: the palette keeps the darkest colours far apart because
     * that is where the eye distinguishes them most.</p>
     */
    private static final int[] CUBE_LEVELS = {0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff};

    /** The first palette index of the grey ramp. */
    private static final int GREY_FIRST = 232;

    /** The first palette index of the 6x6x6 cube. */
    private static final int CUBE_FIRST = 16;

    private Colors() {}

    private static Color rgb(String hex) {
        return Color.parse(hex).orElseThrow(() -> new IllegalArgumentException("bad palette " + hex));
    }

    /**
     * One of the sixteen ANSI colours.
     *
     * @param index the palette index, 0..15
     * @return the colour at that index
     */
    public static Color ansi16(int index) {
        if (index < 0 || index >= ANSI_16.length) {
            throw new IllegalArgumentException("ANSI colour index must be 0..15, was " + index);
        }
        return ANSI_16[index];
    }

    /**
     * One colour of the xterm 256-colour palette.
     *
     * <p>Indices 0..15 are the ANSI colours, 16..231 the 6x6x6 cube and 232..255 the grey ramp from
     * {@code #080808} to {@code #eeeeee} in steps of ten.</p>
     *
     * @param index the palette index, 0..255
     * @return the colour at that index
     */
    public static Color ansi256(int index) {
        if (index < 0 || index > 0xFF) {
            throw new IllegalArgumentException("256-colour index must be 0..255, was " + index);
        }
        if (index < CUBE_FIRST) {
            return ANSI_16[index];
        }
        if (index >= GREY_FIRST) {
            int level = 8 + 10 * (index - GREY_FIRST);
            return new Color(level, level, level);
        }
        int cube = index - CUBE_FIRST;
        return new Color(CUBE_LEVELS[cube / 36], CUBE_LEVELS[(cube / 6) % 6], CUBE_LEVELS[cube % 6]);
    }

    /**
     * The nearest of the sixteen ANSI colours.
     *
     * <p>Nearest is measured in CIE L*a*b*, the perceptual space the palette values are compared in,
     * so a colour maps to the entry it looks like rather than the entry with the closest channel
     * numbers: a mid blue maps to blue, not to the cyan that a plain sRGB distance would pick. An
     * exact tie goes to the lower index, and the arithmetic is {@link StrictMath}, so the same colour
     * maps to the same entry on every platform. A 16-colour session has nothing but these entries to
     * choose from.</p>
     *
     * @param color the colour to map; required
     * @return the palette index, 0..15
     */
    public static int nearestAnsi16(Color color) {
        java.util.Objects.requireNonNull(color, "color");
        return nearest(LAB_ANSI_16, lab(color), 0);
    }

    /**
     * The nearest entry of the 6x6x6 cube or the grey ramp.
     *
     * <p>The ANSI entries are deliberately not candidates: a terminal is free to redefine its first
     * sixteen colours, so emitting one of those indices would show a colour nobody chose. Every other
     * entry names a colour the terminal owns, and the nearest is picked in the same perceptual space
     * with the lower index winning a tie.</p>
     *
     * <p>The mapping is memoized, so a frame that paints the same colour on many cells converts it
     * once. The memo is bounded: past its capacity it simply stops growing and colours are mapped
     * again, which costs arithmetic rather than memory.</p>
     *
     * @param color the colour to map; required
     * @return the palette index, 16..255
     */
    public static int nearestAnsi256(Color color) {
        java.util.Objects.requireNonNull(color, "color");
        int rgb = (color.red() << 16) | (color.green() << 8) | color.blue();
        Integer cached = NEAREST_256.get(rgb);
        if (cached != null) {
            return cached;
        }
        int nearest = nearest(LAB_CUBE_AND_GREYS, lab(color), CUBE_FIRST);
        if (NEAREST_256.size() < NEAREST_CACHE_LIMIT) {
            NEAREST_256.putIfAbsent(rgb, nearest);
        }
        return nearest;
    }

    /** The index of the palette entry whose L*a*b* is nearest, ties going to the lower index. */
    private static int nearest(double[][] palette, double[] wanted, int first) {
        int best = first;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < palette.length; index++) {
            double lightness = wanted[0] - palette[index][0];
            double a = wanted[1] - palette[index][1];
            double b = wanted[2] - palette[index][2];
            double distance = lightness * lightness + a * a + b * b;
            if (distance < bestDistance) {
                best = first + index;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * One sRGB colour in CIE L*a*b*, relative to the D65 white point.
     *
     * <p>The sRGB transfer function is undone first, because the channels a terminal sends are
     * gamma-encoded and comparing them directly is what makes a perceptual metric worth having.</p>
     */
    static double[] lab(Color color) {
        double red = linear(color.red() / 255.0);
        double green = linear(color.green() / 255.0);
        double blue = linear(color.blue() / 255.0);
        double x = (0.4124564 * red + 0.3575761 * green + 0.1804375 * blue) / 0.95047;
        double y = 0.2126729 * red + 0.7151522 * green + 0.0721750 * blue;
        double z = (0.0193339 * red + 0.1191920 * green + 0.9503041 * blue) / 1.08883;
        double fx = pivot(x);
        double fy = pivot(y);
        double fz = pivot(z);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    /** The sRGB transfer function's inverse: a gamma-encoded channel as linear light. */
    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : StrictMath.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** The CIE L*a*b* pivot function. */
    private static double pivot(double value) {
        return value > 216.0 / 24389 ? StrictMath.cbrt(value) : (841.0 / 108) * value + 4.0 / 29;
    }

    /** The L*a*b* values of the sixteen ANSI colours, computed once. */
    private static final double[][] LAB_ANSI_16 = labs(0, 16);

    /** The L*a*b* values of the cube and grey-ramp entries, in palette order. */
    private static final double[][] LAB_CUBE_AND_GREYS = labs(CUBE_FIRST, 256);

    /** Bounded memo of colour to cube-or-grey index. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> NEAREST_256 =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** How many distinct colours the memo keeps before it stops growing. */
    private static final int NEAREST_CACHE_LIMIT = 4_096;

    /** The L*a*b* values of the palette entries from {@code first} to 255. */
    private static double[][] labs(int first, int last) {
        double[][] values = new double[last - first][];
        for (int index = first; index < last; index++) {
            values[index - first] = lab(ansi256(index));
        }
        return values;
    }

    /**
     * Whether a colour reads as a dark background, by its perceived brightness.
     *
     * <p>The one classification rule in the library: the ITU-R BT.601 luma of the channels, with
     * anything below the halfway point counting as dark. Every fact derived from a terminal's
     * reported colours - a theme choice, a status row - goes through here, so two components can
     * never disagree about the same colour.</p>
     *
     * @param color the colour to classify; required
     * @return true when the colour is on the dark side of the halfway point
     */
    public static boolean isDark(Color color) {
        java.util.Objects.requireNonNull(color, "color");
        return 299 * color.red() + 587 * color.green() + 114 * color.blue() < 128_000;
    }

    /** Squared sRGB distance between two colours, exactly as large as the channels allow. */
    private static int distance(Color left, Color right) {
        int red = left.red() - right.red();
        int green = left.green() - right.green();
        int blue = left.blue() - right.blue();
        return red * red + green * green + blue * blue;
    }
}
