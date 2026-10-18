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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** A badge is one row of padded pills, joined by arrows only when a pathway is requested. */
class BadgeTest {

    @Test
    void everyPillIsPaddedByOneColumnAndAdjacentPillsTouch() {
        Badge badge = Badge.of()
                .append(StyledText.plain("a"))
                .append(StyledText.plain("bc"));
        assertThat(badge.render().plainText()).isEqualTo(" a  bc ");
        assertThat(badge.columns()).isEqualTo(7);
    }

    @Test
    void aPathwayJoinsThePillsWithAnArrow() {
        Badge badge = Badge.of()
                .append(StyledText.plain("a"), Color.parse("#ff0000").orElseThrow())
                .append(StyledText.plain("b"))
                .pathway();
        assertThat(badge.render().plainText()).isEqualTo(" a  \u2192  b ");
    }

    /** A pill is one row, so line breaks and tabs become spaces instead of splitting the badge. */
    @Test
    void aLabelWithLineBreaksAndTabsStaysOnOneRow() {
        Badge badge = Badge.of().append(StyledText.plain("a\nb\tc")).append(StyledText.plain("d"));
        String rendered = badge.render().plainText();
        assertThat(rendered).doesNotContain("\n").doesNotContain("\t");
        assertThat(rendered).isEqualTo(" a b c  d ");
        assertThat(badge.render().lines()).hasSize(1);
    }

    @Test
    void thePillStyleAppliesToItsPaddingAndMergesWithTheLabel() {
        Style background = Style.bg(Color.parse("#0000ff").orElseThrow());
        Badge badge = Badge.of().append(StyledText.of("x", Style.NONE.withBold()), background);
        List<StyledText.Span> spans = badge.render().spans();
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.style().background()).as("background on [%s]", span.text()).isNotNull();
            assertThat(span.style().bold() || span.text().isBlank())
                    .as("own styling kept on [%s]", span.text()).isTrue();
        });
        assertThat(spans.get(0).text()).isEqualTo(" ");
        assertThat(spans.get(spans.size() - 1).text()).isEqualTo(" ");
    }

    @Test
    void theSegmentsAreAnImmutableCopy() {
        Badge badge = Badge.of().append(StyledText.plain("a"));
        List<Badge.Segment> segments = badge.segments();
        assertThatThrownBy(() -> segments.add(new Badge.Segment(StyledText.plain("b"),
                Style.NONE))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Badge.of().render().plainText()).isEmpty();
    }

    /**
     * Rendered width is exactly the sum of the parts: one pad per side of every pill, one space on
     * each side of every pathway arrow.
     */
    @Test
    void theRenderedWidthIsTheSumOfItsParts() {
        String[] atoms = {"a", "bc", " ", "\u65E5", "\uD83D\uDE00", "e\u0301", "\n", "\t"};
        Random random = new Random(23L);
        for (int iteration = 0; iteration < 300; iteration++) {
            Badge badge = Badge.of();
            int expected = 0;
            int pills = 1 + random.nextInt(4);
            for (int pill = 0; pill < pills; pill++) {
                StringBuilder label = new StringBuilder();
                for (int i = 0; i < random.nextInt(4); i++) {
                    label.append(atoms[random.nextInt(atoms.length)]);
                }
                String flat = label.toString().replace('\n', ' ').replace('\t', ' ');
                badge.append(StyledText.plain(label.toString()));
                expected += TextMeasure.columns(flat) + 2;
            }
            boolean pathway = pills > 1 && random.nextBoolean();
            if (pathway) {
                badge.pathway();
                expected += 3 * (pills - 1);
                assertThat(badge.render().plainText().split("\u2192", -1)).hasSize(pills);
            }
            assertThat(badge.columns()).as("width of [%s]", badge.render().plainText())
                    .isEqualTo(expected);
        }
    }
}
