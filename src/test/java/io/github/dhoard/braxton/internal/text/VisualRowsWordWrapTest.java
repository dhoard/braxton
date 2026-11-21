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

package io.github.dhoard.braxton.internal.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Word wrapping: where a row ends, what it paints, and what a word too long for a row does.
 */
class VisualRowsWordWrapTest {

    private static List<VisualRows.Row> words(String text, int width) {
        return VisualRows.project(
                text,
                0,
                text.length(),
                0,
                width,
                VisualRows.Cells.NATURAL,
                VisualRows.CLOSED,
                -1,
                VisualRows.Wrap.WORD);
    }

    private static List<VisualRows.Row> cells(String text, int width) {
        return VisualRows.project(text, 0, text.length(), 0, width, VisualRows.Cells.NATURAL, VisualRows.CLOSED, -1);
    }

    private static List<String> painted(String text, List<VisualRows.Row> rows) {
        return rows.stream().map(row -> text.substring(row.start(), row.end())).toList();
    }

    @Test
    void aWordMovesToTheNextRowWhole() {
        String text = "alpha beta gamma";
        List<VisualRows.Row> rows = words(text, 8);

        assertThat(painted(text, rows)).containsExactly("alpha", "beta", "gamma");
        assertThat(rows)
                .containsExactly(
                        new VisualRows.Row(0, 5, 5), new VisualRows.Row(6, 10, 4), new VisualRows.Row(11, 16, 5));
        assertThat(painted(text, cells(text, 8))).containsExactly("alpha be", "ta gamma");
    }

    @Test
    void aWordWiderThanTheRowFallsBackToACellBreak() {
        String text = "abcdefghij";
        assertThat(painted(text, words(text, 4))).containsExactly("abcd", "efgh", "ij");
        assertThat(painted("ab cdefghij", words("ab cdefghij", 4))).containsExactly("ab", "cdef", "ghij");
    }

    @Test
    void aBlankRunIsDroppedAtTheBreak() {
        String text = "abc   def";
        List<VisualRows.Row> rows = words(text, 5);

        assertThat(painted(text, rows)).containsExactly("abc", "def");
        assertThat(rows.get(0).columns()).isEqualTo(3);
        assertThat(rows.get(1).start()).isEqualTo(6);
        assertThat(rows).allSatisfy(row -> assertThat(row.columns()).isLessThanOrEqualTo(5));
    }

    @Test
    void aRowThatFitsIsNotBroken() {
        String text = "abc def";
        assertThat(painted(text, words(text, 7))).containsExactly(text);
        assertThat(painted(text, words("", 5))).containsExactly("");
    }

    @Test
    void aCaretInsideTheDroppedRunKeepsACellBreak() {
        String text = "alpha beta";
        List<VisualRows.Row> rows = VisualRows.project(
                text, 0, text.length(), 0, 7, VisualRows.Cells.NATURAL, VisualRows.CLOSED, 5, VisualRows.Wrap.WORD);

        assertThat(painted(text, rows)).containsExactly("alpha b", "eta");
        assertThat(rows.get(0).start()).isZero();
        assertThat(rows.get(0).end()).isEqualTo(7);
        assertThat(rows.get(0).columns()).isEqualTo(7);
    }

    @Test
    void wordWrapNeverPaintsWiderThanTheRow() {
        String text = "the quick brown fox jumps over the lazy dog 0123456789";
        for (int width = 1; width < 24; width++) {
            for (VisualRows.Row row : words(text, width)) {
                assertThat(row.columns()).as("width %d row %s", width, row).isLessThanOrEqualTo(width);
            }
        }
    }

    @Test
    void wordWrapKeepsWideClustersAndTabsIntact() {
        String text = "\u671d\u671d word";
        List<VisualRows.Row> rows = words(text, 5);

        assertThat(rows).allSatisfy(row -> assertThat(row.columns()).isLessThanOrEqualTo(5));
        assertThat(painted(text, rows)).containsExactly("\u671d\u671d", "word");

        String tabbed = "ab\tcd";
        assertThat(painted(tabbed, words(tabbed, 4))).containsExactly("ab\t", "cd");
    }
}
