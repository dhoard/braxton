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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic and the glyphs the four data charts share.
 *
 * <p>These are the pieces {@link LineChart}, {@link BarChart}, {@link Sparkline} and {@link Gauge}
 * each depend on, and the rules they must not each re-derive: what the range of a set of values is,
 * where a value sits in it, which block glyph a fraction paints, and how a value is written as a
 * readout.</p>
 */
class ChartRangeTest {

    @Test
    void aRangeCoversItsValuesAndOrdersItsBounds() {
        assertThat(ChartRange.of(List.of(3.0, 1.0, 2.0))).isEqualTo(new ChartRange(1.0, 3.0));
        assertThat(ChartRange.of(List.of()))
                .as("a range with nothing to measure is the one an empty chart paints against")
                .isEqualTo(new ChartRange(0.0, 1.0));
        assertThat(ChartRange.of((List<Double>) null)).isEqualTo(new ChartRange(0.0, 1.0));
        assertThat(ChartRange.of(java.util.Arrays.asList(1.0, null, Double.NaN, 5.0)))
                .as("a value that is not a number takes no part in the range")
                .isEqualTo(new ChartRange(1.0, 5.0));
        assertThat(ChartRange.of(java.util.Arrays.asList(Double.NaN, null)))
                .as("a range of nothing but gaps is the empty range")
                .isEqualTo(new ChartRange(0.0, 1.0));
        assertThat(ChartRange.of(9.0, 2.0))
                .as("reversed bounds are ordered, not rejected")
                .isEqualTo(new ChartRange(2.0, 9.0));
        assertThat(ChartRange.of(4.0, 4.0))
                .as("a flat range is legal: a chart of a constant series paints the middle")
                .isEqualTo(new ChartRange(4.0, 4.0));
        assertThatThrownBy(() -> ChartRange.of(Double.NaN, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> ChartRange.of(0.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFractionIsWhereAValueSitsAndIsClampedOntoTheEnds() {
        ChartRange range = new ChartRange(0.0, 10.0);
        assertThat(range.fraction(0.0)).isZero();
        assertThat(range.fraction(2.5)).isEqualTo(0.25);
        assertThat(range.fraction(10.0)).isEqualTo(1.0);
        assertThat(range.fraction(-4.0))
                .as("a value below the range clamps onto its low end")
                .isZero();
        assertThat(range.fraction(14.0))
                .as("a value above it clamps onto its high end")
                .isEqualTo(1.0);
        assertThat(range.fraction(Double.NaN))
                .as("a gap has no position, so a caller that forgot to skip one paints the first cell")
                .isZero();
        assertThat(new ChartRange(3.0, 3.0).fraction(3.0))
                .as("a flat range has no ends, so everything is in the middle of it")
                .isEqualTo(0.5);
        assertThat(new ChartRange(-5.0, 5.0).fraction(0.0)).isEqualTo(0.5);
    }

    @Test
    void aRangeIsExtendedToZeroAndANullOneIsDerived() {
        ChartRange positive = new ChartRange(10.0, 40.0);
        assertThat(positive.withZero()).isEqualTo(new ChartRange(0.0, 40.0));
        assertThat(new ChartRange(-30.0, -10.0).withZero()).isEqualTo(new ChartRange(-30.0, 0.0));
        ChartRange covering = new ChartRange(-2.0, 8.0);
        assertThat(covering.withZero())
                .as("a range that already covers zero is unchanged")
                .isSameAs(covering);
        assertThat(ChartRange.resolve(null, List.of(2.0, 4.0))).isEqualTo(new ChartRange(2.0, 4.0));
        ChartRange fixed = new ChartRange(0.0, 100.0);
        assertThat(ChartRange.resolve(fixed, List.of(2.0, 4.0)))
                .as("an explicit range is used as it is, whatever the values are")
                .isEqualTo(fixed);
    }

    @Test
    void aFractionChoosesOneOfTheEightEighthsOfACell() {
        assertThat(BlockGlyphs.VERTICAL).hasSize(8);
        assertThat(BlockGlyphs.PARTIAL).hasSize(8);
        assertThat(BlockGlyphs.level(0.0))
                .as("the lowest level is one eighth, not nothing")
                .isEqualTo(1);
        assertThat(BlockGlyphs.level(1.0)).isEqualTo(8);
        assertThat(BlockGlyphs.level(0.5)).isEqualTo(5);
        assertThat(BlockGlyphs.level(-3.0)).isEqualTo(1);
        assertThat(BlockGlyphs.level(9.0)).isEqualTo(8);
        assertThat(BlockGlyphs.level(Double.NaN)).isEqualTo(1);
        assertThat(BlockGlyphs.vertical(1.0)).isEqualTo("\u2588");
        assertThat(BlockGlyphs.vertical(0.0)).isEqualTo("\u2581");
        assertThat(BlockGlyphs.eighth(0)).as("no partial cell paints nothing").isEmpty();
        assertThat(BlockGlyphs.eighth(-2)).isEmpty();
        assertThat(BlockGlyphs.eighth(1)).isEqualTo("\u258F");
        assertThat(BlockGlyphs.eighth(8)).isEqualTo("\u2588");
        assertThat(BlockGlyphs.eighth(12)).isEqualTo("\u2588");
        // The table ProgressBar draws its fractional fill from is this one, not a second copy.
        assertThat(BlockGlyphs.PARTIAL[7]).isEqualTo(BlockGlyphs.FULL);
    }

    @Test
    void aValueIsWrittenAsAReadoutWithNoLocaleAndNoTrailingZeros() {
        assertThat(BlockGlyphs.value(42.0)).isEqualTo("42");
        assertThat(BlockGlyphs.value(-7.0)).isEqualTo("-7");
        assertThat(BlockGlyphs.value(-0.0)).isEqualTo("0");
        assertThat(BlockGlyphs.value(12.5)).isEqualTo("12.5");
        assertThat(BlockGlyphs.value(1.0 / 3.0)).isEqualTo("0.3333");
        assertThat(BlockGlyphs.value(Double.NaN)).as("a gap reads as a dash").isEqualTo("-");
        assertThat(BlockGlyphs.value(Double.POSITIVE_INFINITY)).isEqualTo("-");
        assertThat(BlockGlyphs.value(1.0e13))
                .as("a magnitude too large to read is written in scientific notation")
                .isEqualTo("1E+13");
        assertThat(BlockGlyphs.value(1.5e300)).isEqualTo("1.5E+300");
    }
}
