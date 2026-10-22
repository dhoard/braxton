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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ScreenRenderer;

/**
 * A frame reserves one cell per edge character, so every row a {@link Box} renders occupies exactly
 * the frame width. That holds while each row's content and label are measured as they will be
 * painted: one grapheme-cluster sequence that starts after the left frame cell. A cluster with no
 * base character of its own - a combining mark, a variation selector, a joiner, a byte-order mark -
 * joins the cell beside it instead of occupying its own, so padding computed from the content
 * measured on its own leaves the row short of the frame or one column past it.
 */
class BoxFrameWidthTest {

    private static final Border FRAME = new Border('+', '+', '+', '+', '-', '|');

    /**
     * Clusters that occupy no cell of their own when they are painted next to a frame character.
     * Each one is a legal, encodable character that {@code Span} keeps. The entry {@code "x"} plus
     * U+0301 is the control: that accent has its own base inside the content, so it already
     * occupied its own cell.
     */
    private static final List<String> BASELESS = List.of(
            "\u0301",            // combining acute accent
            "\u0301x",           // accent with nothing before it
            "x\u0301",           // accent that has its own base
            "\u200D",            // zero-width joiner
            "\u200D\u0301a\u200D",
            "\uFE0F",            // variation selector 16 (wide presentation)
            "\uFE0Fx",
            "\uFE0E",            // variation selector 15 (text presentation)
            "\uFE0Ex",
            "\u200B",            // zero-width space
            "\uFEFFx",           // byte-order mark
            "\u00ADx");          // soft hyphen

    private static String show(String value) {
        if (value == null) {
            return "<none>";
        }
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F
                    ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }

    private static List<String> rows(int width, String title, String content) {
        Box box = Box.of(content).border(FRAME).width(width);
        if (title != null) {
            box.title(title);
        }
        return List.of(box.render(width).plainText().split("\n", -1));
    }

    /** Every row of a framed box occupies the frame width, with or without a title. */
    private static void assertFrameKeepsWidth(String title, String content) {
        for (int width = 4; width <= 14; width++) {
            List<String> rows = rows(width, title, content);
            assertThat(rows).as("title %s content %s width %d", show(title), show(content), width)
                    .hasSize(3);
            for (String row : rows) {
                assertThat(TextMeasure.columns(row))
                        .as("title %s content %s width %d row [%s]",
                                show(title), show(content), width, show(row))
                        .isEqualTo(width);
            }
        }
    }

    @Test
    void aTitleWithABaselessClusterKeepsEveryFrameRowAtTheFrameWidth() {
        for (String title : BASELESS) {
            assertFrameKeepsWidth(title, "ab");
        }
    }

    @Test
    void aContentLineWithABaselessClusterKeepsEveryFrameRowAtTheFrameWidth() {
        for (String content : BASELESS) {
            assertFrameKeepsWidth(null, content);
        }
    }

    /**
     * The same row through the layout engine: the frame's right edge is one column, so the closing
     * corner of the top row sits above the closing bar of the row below it. A row the frame
     * reserved more columns for than it painted leaves that column empty and lands every cell after
     * the label left of the row above.
     */
    @Test
    void aTitledFrameDrawsItsRightEdgeInOneColumn() {
        for (String title : List.of("\u0301", "\uFE0Fx")) {
            List<String> painted = new ScreenRenderer().plainLines(
                    Box.of("ab").border(FRAME).title(title).width(8).view(8), new Viewport(3, 8));
            assertThat(painted).as("title %s", show(title))
                    .allSatisfy(row -> assertThat(TextMeasure.columns(row))
                            .as("painted row [%s]", show(row)).isEqualTo(8));
            assertThat(painted.get(0)).as("title %s", show(title)).endsWith("+");
            assertThat(painted.get(1)).as("title %s", show(title)).endsWith("|");
            assertThat(painted.get(2)).as("title %s", show(title)).isEqualTo("+------+");
        }
    }

    /** A title that cannot fit its reserved label space still leaves a complete top edge. */
    @Test
    void aTitleThatNeedsMoreColumnsThanItClaimsStillFitsItsLabelSpace() {
        for (String title : List.of("\uFE0F", "\uFE0Fx", "\uFE0F\uFE0Fx")) {
            for (int width = 4; width <= 8; width++) {
                List<String> rows = rows(width, title, "ab");
                assertThat(TextMeasure.columns(rows.get(0)))
                        .as("title %s width %d row [%s]", show(title), width, show(rows.get(0)))
                        .isEqualTo(width);
                assertThat(rows.get(0)).as("title %s width %d", show(title), width)
                        .startsWith("+").endsWith("+");
            }
        }
    }

    /** A frame with several content lines pads each one on its own terms. */
    @Test
    void everyLineOfATallFrameKeepsTheFrameWidth() {
        for (String content : List.of("\u0301\nab\n\uFE0Fx\n\u200D\nplain",
                "ab\n\u0301x\n\uFE0E\ncd")) {
            for (int width = 5; width <= 12; width++) {
                List<String> rows = rows(width, "\u0301", content);
                assertThat(rows).as("content %s width %d", show(content), width)
                        .hasSize(2 + content.split("\n", -1).length);
                for (String row : rows) {
                    assertThat(TextMeasure.columns(row))
                            .as("content %s width %d row [%s]", show(content), width, show(row))
                            .isEqualTo(width);
                }
            }
        }
    }

    /** A responsive frame (no fixed width) keeps the same rule for its own measured width. */
    @Test
    void aResponsiveFrameKeepsItsWidthWithABaselessCluster() {
        for (String content : BASELESS) {
            for (int width = 4; width <= 14; width++) {
                List<String> rows = List.of(
                        Box.of(content).border(FRAME).title("\u0301").render(width).plainText()
                                .split("\n", -1));
                for (String row : rows) {
                    assertThat(TextMeasure.columns(row))
                            .as("content %s width %d row [%s]", show(content), width, show(row))
                            .isEqualTo(Math.max(2, width));
                }
            }
        }
    }

    /** Ordinary titles and content keep the frames the existing catalog tests pin. */
    @Test
    void anOrdinaryFrameIsUnchanged() {
        assertThat(rows(8, "t", "ab")).containsExactly("+- t --+", "|ab    |", "+------+");
        assertThat(rows(8, null, "ab")).containsExactly("+------+", "|ab    |", "+------+");
        assertThat(rows(7, "\u65E5\u672C", "x"))
                .containsExactly("+- \u65E5 +", "|x    |", "+-----+");
    }
}
