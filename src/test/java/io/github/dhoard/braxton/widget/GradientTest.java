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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** A gradient recolours every cluster in place and leaves the text itself untouched. */
class GradientTest {

    private static final Color RED = new Color(255, 0, 0);
    private static final Color BLUE = new Color(0, 0, 255);

    @Test
    void everyDirectionKeepsTheTextAndItsAttributes() {
        StyledText source = StyledText.of("ab\nc日", Style.NONE.withBold(), "https://example.test");
        for (Gradient.Direction direction : Gradient.Direction.values()) {
            StyledText painted = Gradient.apply(source, List.of(RED, BLUE), direction);
            assertThat(painted.plainText()).as("text for %s", direction)
                    .isEqualTo(source.plainText());
            for (StyledText.Span span : painted.spans()) {
                if ("\n".equals(span.text())) {
                    continue;
                }
                assertThat(span.style().bold()).as("bold kept for %s", direction).isTrue();
                assertThat(span.style().foreground()).as("colour set for %s", direction).isNotNull();
                assertThat(span.link()).as("link kept for %s", direction)
                        .isEqualTo("https://example.test");
            }
        }
    }

    @Test
    void thePaletteEndsLandOnTheFirstAndLastCluster() {
        StyledText vertical = Gradient.apply(StyledText.plain("a\nb"), List.of(RED, BLUE),
                Gradient.Direction.VERTICAL);
        List<StyledText.Span> verticalSpans = vertical.spans();
        assertThat(verticalSpans).hasSize(3);
        assertThat(verticalSpans.get(0).style().foreground()).isEqualTo(RED);
        assertThat(verticalSpans.get(2).style().foreground()).isEqualTo(BLUE);

        StyledText horizontal = Gradient.apply(StyledText.plain("ab"), List.of(RED, BLUE),
                Gradient.Direction.HORIZONTAL);
        assertThat(horizontal.spans()).hasSize(2);
        assertThat(horizontal.spans().get(0).style().foreground()).isEqualTo(RED);
        assertThat(horizontal.spans().get(1).style().foreground()).isEqualTo(BLUE);
    }

    /** A wide cluster advances the horizontal column by its painted width, not by one. */
    @Test
    void aWideClusterAdvancesTheHorizontalPositionByItsWidth() {
        StyledText horizontal = Gradient.apply(StyledText.plain("日ab"), List.of(RED, BLUE),
                Gradient.Direction.HORIZONTAL);
        List<StyledText.Span> spans = horizontal.spans();
        assertThat(TextMeasure.columns(spans.get(0).text())).isEqualTo(2);
        Color twoThirds = Gradient.interpolate(List.of(RED, BLUE), 2.0 / 3.0);
        assertThat(spans.get(1).style().foreground()).isEqualTo(twoThirds);
        assertThat(spans.get(2).style().foreground()).isEqualTo(BLUE);
    }

    @Test
    void anUnusablePaletteReturnsTheOriginalText() {
        StyledText source = StyledText.of("ab", Style.NONE.withBold());
        assertThat(Gradient.apply(source, null, Gradient.Direction.VERTICAL)).isSameAs(source);
        assertThat(Gradient.apply(source, List.of(), Gradient.Direction.VERTICAL)).isSameAs(source);
        assertThat(Gradient.apply(source, Arrays.asList(null, null),
                Gradient.Direction.VERTICAL)).isSameAs(source);
        assertThat(Gradient.apply(StyledText.empty(), List.of(RED), null).isEmpty()).isTrue();
    }

    /** A null entry is dropped rather than treated as a colour between the neighbours. */
    @Test
    void aNullEntryIsIgnoredRatherThanInterpolated() {
        StyledText painted = Gradient.apply(StyledText.plain("a\nb\nc"),
                Arrays.asList(RED, null, BLUE), Gradient.Direction.VERTICAL);
        List<StyledText.Span> spans = painted.spans();
        assertThat(spans.get(0).style().foreground()).isEqualTo(RED);
        assertThat(spans.get(4).style().foreground()).isEqualTo(BLUE);
        assertThat(spans.get(2).style().foreground())
                .isEqualTo(Gradient.interpolate(List.of(RED, BLUE), 0.5));
    }

    @Test
    void aRepeatedApplicationIsDeterministic() {
        StyledText source = StyledText.of("abc\ndef", Style.NONE.withItalic());
        assertThat(Gradient.apply(source, List.of(RED, BLUE), Gradient.Direction.DIAGONAL))
                .isEqualTo(Gradient.apply(source, List.of(RED, BLUE), Gradient.Direction.DIAGONAL));
    }
}
