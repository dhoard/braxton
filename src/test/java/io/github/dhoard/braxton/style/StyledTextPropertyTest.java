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

/** Concatenation is associative, and line splitting round-trips the plain text. */
class StyledTextPropertyTest {

    @Test
    void appendIsAssociativeAndLinesRoundTrip() {
        String[] parts = {"a", "b", "\n", " ", "\u65E5", "e\u0301"};
        Random random = new Random(3L);
        for (int iteration = 0; iteration < 300; iteration++) {
            StyledText a = StyledText.plain(randomText(parts, random));
            StyledText b = StyledText.plain(randomText(parts, random));
            StyledText c = StyledText.plain(randomText(parts, random));
            assertThat(a.append(b).append(c)).isEqualTo(a.append(b.append(c)));
            assertThat(StyledText.concat(a, b, c)).isEqualTo(a.append(b).append(c));
            String joined = String.join("\n", a.lines().stream().map(StyledText::plainText).toList());
            assertThat(joined).isEqualTo(a.plainText());
            assertThat(a.stripTrailingNewlines().plainText())
                    .isEqualTo(a.plainText().replaceAll("\n+$", ""));
            assertThat(a.columns()).isGreaterThanOrEqualTo(0);
            assertThat(a.graphemeCount()).isEqualTo(TextMeasure.graphemeCount(a.plainText()));
        }
    }

    private static String randomText(String[] parts, Random random) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < random.nextInt(5); i++) {
            text.append(parts[random.nextInt(parts.length)]);
        }
        return text.toString();
    }
}
