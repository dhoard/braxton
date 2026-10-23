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

package examples.harness.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.TextMeasure;

/**
 * Wrapping never overflows its width, never loses a non-space character, and never rewraps text
 * that already fits.
 */
class HarnessRenderPropertyTest {

    private static final String[] WORDS = {
        "a", "bb", "ccc", "dddd", "\u65E5", "\u65E5\u672C", "e\u0301", "tool_read(path)", "==>",
        // A variation selector with no base of its own, before and after a base: it joins the cell
        // in front of it, which is the class of character the width bound used to miss.
        "\uFE0F", "\uFE0Fx", "x\uFE0Fy"
    };

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int lines = 1 + random.nextInt(3);
        for (int line = 0; line < lines; line++) {
            if (line > 0) {
                text.append('\n');
            }
            int words = random.nextInt(5);
            for (int word = 0; word < words; word++) {
                text.append(" ".repeat(random.nextInt(5)));
                text.append(WORDS[random.nextInt(WORDS.length)]);
            }
            text.append(" ".repeat(random.nextInt(4)));
        }
        return text.toString();
    }

    /** Wrapping turns runs of spaces and line breaks into structure, never into content. */
    private static String content(String text) {
        return text.replace(" ", "").replace("\n", "");
    }

    /** The widest single cluster: a line can never be narrower than a cluster it cannot split. */
    private static int widestCluster(String text) {
        int widest = 1;
        for (String grapheme : TextMeasure.graphemes(text)) {
            widest = Math.max(widest, TextMeasure.columns(grapheme));
        }
        return widest;
    }

    @Test
    void everyWrappedLineFitsAndKeepsEveryCharacter() {
        Random random = new Random(97L);
        for (int iteration = 0; iteration < 4_000; iteration++) {
            String text = randomText(random);
            int width = random.nextInt(20);
            int limit = Math.max(1, width);
            List<String> lines = HarnessRender.wrap(text, width);
            assertThat(lines).as("iteration %d width %d lines", iteration, width)
                    .isNotEmpty();
            assertThat(lines.size()).as("iteration %d width %d line count", iteration, width)
                    .isGreaterThanOrEqualTo(text.split("\n", -1).length);
            for (int row = 0; row < lines.size(); row++) {
                assertThat(HarnessRender.visibleLength(lines.get(row)))
                        .as("iteration %d width %d row %d", iteration, width, row)
                        .isLessThanOrEqualTo(Math.max(limit, widestCluster(text)));
            }
            assertThat(String.join("", lines).replace(" ", ""))
                    .as("iteration %d width %d content", iteration, width)
                    .isEqualTo(content(text));
        }
    }

    @Test
    void textThatAlreadyFitsIsNeverRewrapped() {
        Random random = new Random(131L);
        for (int iteration = 0; iteration < 4_000; iteration++) {
            String line = WORDS[random.nextInt(WORDS.length)] + " "
                    + WORDS[random.nextInt(WORDS.length)];
            int width = Math.max(HarnessRender.visibleLength(line), 1);
            assertThat(HarnessRender.wrap(line, width)).as("iteration %d %s", iteration, line)
                    .containsExactly(line);
        }
    }

    @Test
    void aZeroOrNegativeWidthStillWrapsToOneColumn() {
        Random random = new Random(151L);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            String text = randomText(random);
            List<String> lines = HarnessRender.wrap(text, random.nextInt(3) - 1);
            assertThat(lines).as("iteration %d", iteration).isNotEmpty();
            assertThat(String.join("", lines).replace(" ", ""))
                    .as("iteration %d content", iteration).isEqualTo(content(text));
        }
    }
}