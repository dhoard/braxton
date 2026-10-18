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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

/** Time-driven frames: the spinner advances and wraps, the shimmer travels and repeats. */
class AnimationTest {

    private static final Color BASE = Color.parse("#101010").orElseThrow();
    private static final Color LIGHT = Color.parse("#f0f0f0").orElseThrow();

    private static List<Integer> litIndices(StyledText text) {
        List<Integer> lit = new java.util.ArrayList<>();
        int index = 0;
        for (StyledText.Span span : text.spans()) {
            for (int i = 0; i < span.text().length(); i++) {
                if (LIGHT.equals(span.style().foreground())) {
                    lit.add(index + i);
                }
            }
            index += span.text().length();
        }
        return lit;
    }

    @Test
    void theSpinnerAdvancesOneFrameEveryQuarterSecondAndWraps() {
        assertThat(Animation.spinnerFrames()).isEqualTo(4);
        assertThat(Animation.spinner(0L, 0L, Style.NONE).plainText()).isEqualTo("\u25C7");
        assertThat(Animation.spinner(249_000_000L, 0L, Style.NONE).plainText()).isEqualTo("\u25C7");
        assertThat(Animation.spinner(250_000_000L, 0L, Style.NONE).plainText()).isEqualTo("\u25C8");
        assertThat(Animation.spinner(500_000_000L, 0L, Style.NONE).plainText()).isEqualTo("\u25C6");
        assertThat(Animation.spinner(1_000_000_000L, 0L, Style.NONE).plainText())
                .as("a full cycle wraps back to the first frame").isEqualTo("\u25C7");
        assertThat(Animation.spinner(0L, 5_000_000_000L, Style.NONE).plainText())
                .as("a clock before the start clamps to the first frame").isEqualTo("\u25C7");
    }

    @Test
    void theShimmerHighlightsAFourClusterWindowThatTravelsAndRepeats() {
        assertThat(litIndices(Animation.shimmer("abcdefgh", 0L, BASE, LIGHT))).containsExactly(0);
        assertThat(litIndices(Animation.shimmer("abcdefgh", 210_000_000L, BASE, LIGHT)))
                .containsExactly(0, 1, 2, 3);
        assertThat(litIndices(Animation.shimmer("abcdefgh", 700_000_000L, BASE, LIGHT)))
                .containsExactly(7);
        // The period is the cluster count plus twelve frames of 70 ms, so it repeats exactly.
        assertThat(Animation.shimmer("abcdefgh", 1_400_000_000L, BASE, LIGHT))
                .isEqualTo(Animation.shimmer("abcdefgh", 0L, BASE, LIGHT));
    }

    @Test
    void shimmerDefaultsAndDegenerateInputsAreSafe() {
        assertThat(Animation.shimmer("", 0L, BASE, LIGHT).plainText()).isEmpty();
        assertThat(Animation.shimmer(null, 0L, BASE, LIGHT).plainText()).isEmpty();
        StyledText unlit = Animation.shimmer("ab", 0L, null, null);
        assertThat(unlit.plainText()).isEqualTo("ab");
        assertThat(unlit.spans()).allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE));
    }

    @Test
    void extremeAndBackwardsClocksStillProduceValidFrames() {
        // Frames are pure functions of a monotonic clock, which may be any long value: a clock
        // that has not started yet (now before started), a negative monotonic origin, and
        // Long.MAX_VALUE must all yield a real frame instead of an exception or a bad index.
        assertThat(Animation.spinner(0L, 10_000_000_000L, Style.NONE).plainText())
                .as("a clock before the start is frame zero")
                .isEqualTo(Animation.spinner(0L, 0L, Style.NONE).plainText());
        assertThat(Animation.spinner(Long.MAX_VALUE, 0L, Style.NONE).plainText()).isNotEmpty();
        assertThat(Animation.spinner(Long.MAX_VALUE, Long.MIN_VALUE, Style.NONE).plainText())
                .isNotEmpty();
        assertThat(Animation.spinner(0L, 0L, null).style()).as("a null style is the default")
                .isEqualTo(Style.NONE);
        // Four frames of 250 ms make a one-second cycle; the glyph set repeats the second glyph,
        // so the cycle is proven by wrapping rather than by counting distinct glyphs.
        java.util.List<String> frames = new java.util.ArrayList<>();
        for (int frame = 0; frame <= Animation.spinnerFrames(); frame++) {
            frames.add(Animation.spinner(frame * 250_000_000L, 0L, Style.NONE).plainText());
        }
        assertThat(frames).allSatisfy(frame -> assertThat(frame).isNotEmpty());
        assertThat(frames.get(Animation.spinnerFrames()))
                .as("the cycle repeats after four frames").isEqualTo(frames.get(0));
        assertThat(frames.subList(0, Animation.spinnerFrames()).stream().distinct().count())
                .as("the frames advance").isGreaterThan(1);

        assertThat(Animation.shimmer("abcdef", Long.MIN_VALUE, BASE, LIGHT).plainText())
                .isEqualTo("abcdef");
        assertThat(Animation.shimmer("abcdef", Long.MAX_VALUE, BASE, LIGHT).plainText())
                .isEqualTo("abcdef");
        assertThat(litIndices(Animation.shimmer("abcdef", Long.MIN_VALUE, BASE, LIGHT)))
                .as("a negative clock still lands inside the shimmer window")
                .hasSizeLessThanOrEqualTo(4);
    }
}
