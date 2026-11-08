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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * What wrapping promises the paint path, over random styled text.
 *
 * <p>Paint lays a text view out line by line from what wrapping produced, and a container sizes itself
 * from the height the same wrapping implies. So every line has to fit the width it wrapped to - tabs,
 * dotted circles and wide clusters included - and the height measured for the value has to be the
 * number of lines painting will actually show. A gap between the two is content a container throws away
 * while asking for it by name.</p>
 */
class WrapLinesPropertyTest {

    /**
     * Fragments that break lines in the interesting ways: tabs, newlines, runs of blanks, clusters that
     * are wide, clusters that claim no column of their own, and a mark that has to be drawn behind a
     * dotted circle. Each arrives in its own span, so a style boundary sits in the middle of them.
     */
    private static final String[] FRAGMENTS = {
        "alpha beta",
        "a\tb",
        "x\ny",
        "",
        " ",
        "  ",
        "\u4E2D\u6587",
        "\uD83D\uDE00",
        "\uFE0F",
        "\u0301",
        "\u200B",
        "\u00AD",
        "\uFE0E",
        "e\u0301",
        "\t",
        "\n",
        "\u200B\u0301",
        "\uFE0Fx",
        "\uFEFF\u4E2D",
        "\u200D",
        "cat\u200Ddog",
        "a b\tc d"
    };

    private static final Style[] STYLES = {Style.NONE, Style.NONE.withBold(), Style.NONE.withUnderline()};

    @Test
    void everyWrappedLineFitsAndTheMeasuredHeightIsTheLinesPainted() {
        Random random = new Random(777L);
        LayoutEngine engine = new LayoutEngine();
        for (int round = 0; round < 20000; round++) {
            StyledText text = randomText(random);
            int cols = 1 + random.nextInt(12);
            var lines = engine.wrapLines(text, cols);
            for (StyledText line : lines) {
                assertThat(TextMeasure.columns(line))
                        .as("line [%s] of [%s] wrapped to %d columns", line.plainText(), text.plainText(), cols)
                        .isLessThanOrEqualTo(cols);
            }
            // The height a container measures is the number of lines paint lays out from the same text.
            View<?> view = Views.text(text);
            assertThat(engine.intrinsicHeight(view, cols))
                    .as("the measured height of [%s] at %d columns", text.plainText(), cols)
                    .isEqualTo(lines.size());
        }
    }

    private static StyledText randomText(Random random) {
        StyledText text = StyledText.plain("");
        for (int pieces = random.nextInt(6); pieces > 0; pieces--) {
            text = text.append(
                    StyledText.of(FRAGMENTS[random.nextInt(FRAGMENTS.length)], STYLES[random.nextInt(STYLES.length)]));
        }
        return text;
    }
}
