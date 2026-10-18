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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Truncation and fitting keep the column budget and never split a cluster. */
class TextMeasurePropertyTest {

    @Test
    void truncationStaysWithinBudgetAndIsAPrefix() {
        String[] parts = {"a", "bb", " ", "\u65E5", "\uD83D\uDE00", "e\u0301"};
        Random random = new Random(5L);
        for (int iteration = 0; iteration < 500; iteration++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < random.nextInt(8); i++) {
                text.append(parts[random.nextInt(parts.length)]);
            }
            String value = text.toString();
            int budget = random.nextInt(9);
            for (WidthPolicy policy : java.util.List.of(WidthPolicy.PINNED, WidthPolicy.ONE_COLUMN)) {
                String cut = TextMeasure.truncate(value, budget, policy);
                assertThat(value.startsWith(cut)).as("truncate is a prefix").isTrue();
                assertThat(TextMeasure.columns(cut, policy)).as("width %s of [%s] at %d", policy, value, budget)
                        .isLessThanOrEqualTo(budget);
                assertThat(TextMeasure.graphemes(cut)).allSatisfy(cluster ->
                        assertThat(TextMeasure.graphemes(value)).as("no invented cluster").contains(cluster));

            }
            String pinnedCut = TextMeasure.truncate(value, budget, WidthPolicy.PINNED);
            String fitted = TextMeasure.fit(value, budget);
            assertThat(TextMeasure.columns(fitted, WidthPolicy.PINNED))
                    .as("fit always fills its budget").isEqualTo(budget);
            assertThat(fitted).startsWith(pinnedCut);
            assertThat(TextMeasure.fit(value, budget)).isEqualTo(fitted);
            assertThat(TextMeasure.graphemeCount(value)).isEqualTo(TextMeasure.graphemes(value).size());
            assertThat(TextMeasure.columns(value)).isEqualTo(TextMeasure.columns(value));
        }
    }
}
