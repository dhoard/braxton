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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReducedMotionTest {
    @Test
    void aPreparedSequenceCopiesItsFramesAndUsesTheExistingCadence() {
        List<StyledText> frames = new ArrayList<>(List.of(StyledText.plain("a"), StyledText.plain("b")));
        var sequence = new Animation.Sequence(frames, Duration.ofNanos(10));
        frames.clear();
        assertThat(sequence.frameAt(-1, false).plainText()).isEqualTo("a");
        assertThat(sequence.frameAt(9, false).plainText()).isEqualTo("a");
        assertThat(sequence.frameAt(10, false).plainText()).isEqualTo("b");
        assertThat(sequence.frameAt(20, false).plainText()).isEqualTo("a");
        for (long time : new long[] {0, 10, Long.MAX_VALUE})
            assertThat(sequence.frameAt(time, true).plainText()).isEqualTo("a");
        assertThatThrownBy(() -> sequence.frames().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidSequencesAndCadencesAreRejected() {
        List<StyledText> frames = List.of(StyledText.plain("a"));
        assertThatThrownBy(() -> new Animation.Sequence(List.of(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Animation.Sequence(null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Animation.Sequence(frames, null)).isInstanceOf(NullPointerException.class);
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThatThrownBy(() -> new Animation.Sequence(frames, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void existingAnimationHelpersOfferAStableReducedMotionPresentation() {
        StyledText first = Animation.spinner(0, 0, Style.NONE, true);
        assertThat(Animation.spinner(Long.MAX_VALUE, 0, Style.NONE, true)).isEqualTo(first);
        StyledText base = StyledText.of("quiet", Style.fg(Color.BLACK));
        for (long time : new long[] {0, 99, Long.MAX_VALUE}) {
            assertThat(Animation.shimmer("quiet", time, Color.BLACK, Color.WHITE, true))
                    .isEqualTo(base);
        }
        assertThat(Animation.shimmer("quiet", 0, null, null, true)).isEqualTo(StyledText.plain("quiet"));
        assertThat(Animation.shimmer(null, 0, null, null, true)).isEqualTo(StyledText.empty());
    }
}
