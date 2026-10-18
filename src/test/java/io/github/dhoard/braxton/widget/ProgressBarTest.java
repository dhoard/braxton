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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** The bar clamps its fill, renders nothing unusable, and never draws past its width. */
class ProgressBarTest {

    private static final String ACTIVE = "\u2588";
    private static final String INACTIVE = "\u2591";

    @Test
    void anUnusableWidthOrMaximumRendersNothing() {
        assertThat(ProgressBar.of(0).render(0.5, 1.0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(-4).render(0.5, 1.0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(0.5, 0)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(0.5, -1)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(0.5, Double.NaN)).isEqualTo(StyledText.empty());
        assertThat(ProgressBar.of(4).render(0.5, Double.POSITIVE_INFINITY))
                .isEqualTo(StyledText.empty());
        // NaN has no place on the 0..1 scale; an infinite value still clamps to an end of it.
        assertThat(ProgressBar.of(4).render(Double.NaN, 1.0)).isEqualTo(StyledText.empty());
        // An infinite value still renders the bar, clamped to one end of the scale.
        assertThat(ProgressBar.of(4).icons(4, Double.NEGATIVE_INFINITY, 1.0))
                .containsOnly(INACTIVE);
        assertThat(ProgressBar.of(4).icons(4, Double.POSITIVE_INFINITY, 1.0))
                .containsOnly(ACTIVE);
        assertThat(ProgressBar.of(4).columns(Double.NEGATIVE_INFINITY, 1.0)).isEqualTo(4);
        assertThat(ProgressBar.of(4).columns(Double.POSITIVE_INFINITY, 1.0)).isEqualTo(4);
    }

    @Test
    void theFillIsClampedToTheBarItself() {
        ProgressBar bar = ProgressBar.of(4);
        assertThat(bar.icons(4, 0.0, 10.0)).containsExactly(INACTIVE, INACTIVE, INACTIVE, INACTIVE);
        assertThat(bar.icons(4, 10.0, 10.0)).containsExactly(ACTIVE, ACTIVE, ACTIVE, ACTIVE);
        assertThat(bar.icons(4, 20.0, 10.0)).containsExactly(ACTIVE, ACTIVE, ACTIVE, ACTIVE);
        assertThat(bar.icons(4, -5.0, 10.0)).containsExactly(INACTIVE, INACTIVE, INACTIVE, INACTIVE);
        assertThat(bar.icons(4, 2.0, 4.0)).containsExactly(ACTIVE, ACTIVE, INACTIVE, INACTIVE);
        // Half a cell rounds up, as Math.round does: 0.125 of four cells is 0.5.
        assertThat(bar.icons(4, 1.0, 8.0)).containsExactly(ACTIVE, INACTIVE, INACTIVE, INACTIVE);
        assertThat(bar.icons(4, 1.0, 32.0)).containsExactly(INACTIVE, INACTIVE, INACTIVE, INACTIVE);
        assertThat(bar.icons(9, 5.0, 10.0)).hasSize(4);
        assertThat(bar.icons(0, 0.5, 1.0)).isEmpty();
    }

    @Test
    void thePercentageLabelFollowsTheSameClamp() {
        ProgressBar labelled = ProgressBar.of(4).showPercentage(true);
        assertThat(labelled.render(0.0, 10.0).plainText()).isEqualTo(INACTIVE.repeat(4) + " 0%");
        assertThat(labelled.render(10.0, 10.0).plainText()).isEqualTo(ACTIVE.repeat(4) + " 100%");
        assertThat(labelled.render(-1.0, 10.0).plainText()).endsWith(" 0%");
        assertThat(labelled.render(1.0, 3.0).plainText()).endsWith(" 33%");
        assertThat(labelled.render(0.005, 1.0).plainText()).endsWith(" 1%");
        assertThat(ProgressBar.of(4).render(10.0, 10.0).plainText()).doesNotContain("%");
    }

    @Test
    void theBarIsOneRowAndSpansExactlyItsWidthInCells() {
        ProgressBar spaced = ProgressBar.of(3).buffer(2);
        assertThat(spaced.render(1.0, 1.0).plainText())
                .isEqualTo(ACTIVE + "  " + ACTIVE + "  " + ACTIVE);
        assertThat(spaced.columns(1.0, 1.0)).isEqualTo(3 + 2 * 2);
        assertThat(ProgressBar.of(3).buffer(-5).render(1.0, 1.0).plainText())
                .isEqualTo(ACTIVE.repeat(3));
        assertThat(ProgressBar.of(3).columns(1.0, 1.0)).isEqualTo(3);
    }

    @Test
    void iconsAndLabelsAreFlattenedToOneRow() {
        ProgressBar multi = ProgressBar.of(2).icons(ACTIVE + "\n" + ACTIVE, "a\tb")
                .prepend(StyledText.plain("pre\nfix")).append(StyledText.plain("\npost"));
        // Half filled: the first cell draws the flattened active icon, the second the inactive one.
        assertThat(multi.render(0.5, 1.0).plainText()).isEqualTo("pre fix" + ACTIVE + " " + ACTIVE
                + "a b" + " post");
        assertThat(multi.render(0.5, 1.0).plainText().lines()).hasSize(1);
        assertThat(multi.render(1.0, 1.0).plainText()).doesNotContain("a b");
    }

    @Test
    void aWideIconSpansItsOwnColumnsRatherThanBeingSqueezed() {
        // The bar owns cells, not columns: a two-column icon is the caller's choice and is drawn
        // whole, exactly as the widget documents.
        StyledText rendered = ProgressBar.of(2).icons("\u65E5", " ").render(1.0, 1.0);
        assertThat(TextMeasure.columns(rendered.plainText())).isEqualTo(4);
        assertThat(rendered.plainText()).isEqualTo("\u65E5\u65E5");
    }

    @Test
    void randomValuesFillTheExpectedNumberOfCells() {
        Random random = new Random(211L);
        for (int iteration = 0; iteration < 5_000; iteration++) {
            int width = random.nextInt(21);
            double max = 0.1 + random.nextDouble() * 2.0;
            double value = -2.0 + random.nextDouble() * 6.0;
            ProgressBar bar = ProgressBar.of(width);
            int filled = (int) Math.round(Math.max(0, Math.min(1, value / max)) * width);
            List<String> icons = bar.icons(width, value, max);
            assertThat(icons).as("iteration %d", iteration).hasSize(width);
            assertThat(icons.stream().filter(ACTIVE::equals).count())
                    .as("iteration %d width %d", iteration, width).isEqualTo(filled);
            assertThat(icons.subList(0, filled)).as("iteration %d fills from the left", iteration)
                    .allMatch(ACTIVE::equals);
            assertThat(icons.subList(filled, icons.size()))
                    .as("iteration %d leaves the rest inactive", iteration)
                    .allMatch(INACTIVE::equals);
            assertThat(bar.columns(value, max)).as("iteration %d", iteration).isEqualTo(width);
        }
    }
}