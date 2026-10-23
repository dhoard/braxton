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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;

class TextMeasureStreamingTest {

    @Test
    void streamingSegmentationRetainsTheJdkBoundaries() {
        List<String> units = List.of("a", " ", "\n", "\t", "\r", "é", "\u0301", "界", "🙂",
                "\u200B", "\u200D", "\uFE0E", "\uFE0F", "🇺", "🇸", "\uD800", "\uDC00");
        Random random = new Random(48291);
        for (int sample = 0; sample < 500; sample++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < sample % 30; i++) {
                text.append(units.get(random.nextInt(units.size())));
            }
            String value = text.toString();
            BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(value);
            List<String> expected = new ArrayList<>();
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                expected.add(value.substring(start, end));
            }
            assertThat(TextMeasure.graphemes(value)).isEqualTo(expected);
            assertThat(TextMeasure.graphemeCount(value)).isEqualTo(expected.size());
            for (WidthPolicy policy : List.of(WidthPolicy.PINNED, WidthPolicy.ONE_COLUMN)) {
                assertThat(TextMeasure.columns(value, policy)).isEqualTo(expected.stream()
                        .mapToInt(cluster -> referenceWidth(cluster, policy)).sum());
                for (int columns = 0; columns < 8; columns++) {
                    StringBuilder clipped = new StringBuilder();
                    int used = 0;
                    if (columns > 0) {
                        for (String cluster : expected) {
                            int width = referenceWidth(cluster, policy);
                            if (used + width > columns) {
                                break;
                            }
                            clipped.append(cluster);
                            used += width;
                        }
                    }
                    assertThat(TextMeasure.truncate(value, columns, policy)).isEqualTo(clipped.toString());
                }
            }
        }
        assertThat(TextMeasure.graphemes(null)).isEmpty();
        assertThat(TextMeasure.graphemeCount(null)).isZero();
    }

    // The previous code-point-array implementation, retained as an independent width oracle.
    private static int referenceWidth(String cluster, WidthPolicy policy) {
        boolean base = false;
        boolean mark = false;
        boolean wide = false;
        for (int codePoint : cluster.codePoints().toArray()) {
            if (codePoint == 0xFE0E) {
                return 1;
            }
            if (codePoint == 0xFE0F) {
                wide = policy.wideEmoji();
                continue;
            }
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                mark = true;
            } else if (type != Character.FORMAT) {
                base = true;
                wide |= policy.wideEmoji()
                        && io.github.dhoard.braxton.internal.text.Unicode16Width.isWide(codePoint);
            }
        }
        // A cluster with no base of its own is painted behind the dotted circle that stands in for
        // that base, so a selector in that cluster widens the circle the way it widens any other
        // base: the cell occupies the two columns the painted text measures, not one.
        return !base && !mark ? 0 : wide ? 2 : 1;
    }
}
