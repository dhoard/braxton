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

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The block glyphs a bar or a chart is drawn from, and the arithmetic that chooses one.
 *
 * <p>A terminal cell can show a fraction of itself with a block glyph, and two directions of
 * fraction are needed. A vertical measure - a sparkline's column, a line chart's height - grows from
 * the bottom of the cell, so it uses the eight eighths of height; a horizontal measure - a gauge's
 * fill, a bar's end - grows from the left of the cell, so it uses the eight eighths of width. The
 * tables are here rather than in each widget because {@link Sparkline}, {@link BarChart},
 * {@link Gauge} and {@link ProgressBar} all draw from them, and four copies of a glyph table is four
 * ways for two bars of the same value to disagree about how full a cell looks.</p>
 *
 * <p>A level is one-based so a caller can tell "one eighth" from "nothing at all": a sparkline paints
 * a blank cell where it has no sample, and the lowest sample a full cell column, rather than both
 * being the same glyph. {@link #eighth(int)} is zero-based the other way, {@code 0} being no partial
 * cell at all, because that is how a bar asks for the remainder after its whole cells.</p>
 */
final class BlockGlyphs {

    /**
     * The eight eighths of height a cell has, from one eighth to a full block.
     */
    static final String[] VERTICAL = {"\u2581", "\u2582", "\u2583", "\u2584", "\u2585", "\u2586", "\u2587", "\u2588"};

    /**
     * The eight eighths of width a cell has, from one eighth to a full block.
     */
    static final String[] PARTIAL = {"\u258F", "\u258E", "\u258D", "\u258C", "\u258B", "\u258A", "\u2589", "\u2588"};

    /**
     * A fully filled cell, whichever direction the fill grew in.
     */
    static final String FULL = "\u2588";

    /**
     * The magnitudes at or above which a readout is written in engineering notation.
     */
    private static final double LARGE = 1e12;

    /**
     * The significant digits a readout keeps once a value is no longer a plain integer.
     */
    private static final MathContext READOUT = new MathContext(4);

    private BlockGlyphs() {}

    /**
     * The vertical level a fraction of a cell fills.
     *
     * @param fraction the position in the cell's height, from zero to one
     * @return the level, from one eighth to eight eighths
     */
    static int level(double fraction) {
        return (int) Math.round(clamp(fraction) * 7.0) + 1;
    }

    /**
     * The vertical glyph a fraction of a cell fills.
     *
     * @param fraction the position in the cell's height, from zero to one
     * @return the glyph
     */
    static String vertical(double fraction) {
        return VERTICAL[level(fraction) - 1];
    }

    /**
     * The horizontal glyph for a number of eighths of a cell.
     *
     * @param eighths the eighths of width; zero or less is no partial cell at all
     * @return the glyph, empty for none
     */
    static String eighth(int eighths) {
        int value = Math.max(0, Math.min(8, eighths));
        return value == 0 ? "" : PARTIAL[value - 1];
    }

    /**
     * A value as a one-row readout.
     *
     * <p>The text is deterministic and locale-free, because a chart's value is content rather than a
     * number formatted for a reader's region: an integer is written as an integer, a fraction keeps
     * four significant digits with no trailing zeros, and a magnitude too large to read is written
     * in engineering notation instead of three hundred digits.</p>
     *
     * @param value the value to write; a value that is not a number is a gap
     * @return the readout, at least one column wide
     */
    static String value(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        if (value == Math.rint(value) && Math.abs(value) < LARGE) {
            return Long.toString((long) value);
        }
        BigDecimal rounded = BigDecimal.valueOf(value).round(READOUT).stripTrailingZeros();
        return Math.abs(value) >= LARGE ? rounded.toString() : rounded.toPlainString();
    }

    /**
     * A fraction inside the unit interval, with a value that is not a number treated as none.
     */
    private static double clamp(double fraction) {
        if (!Double.isFinite(fraction)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, fraction));
    }
}
