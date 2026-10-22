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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.Views;

/** A framed view keeps its width whatever the title contains. */
class BorderTitleTest {

    @Test
    void everyBuiltInVariantFramesItsChildWithItsOwnCharacters() {
        // Each documented variant is a value: how it decides "is there a frame?" (isVisible, cycle
        // 187) is shared by Box, the layout engine and Table, so the painted frame must use the
        // variant's own characters and one column per edge.
        record Case(Border border, String top, String bottom) {
        }
        java.util.List<Case> cases = java.util.List.of(
                new Case(Border.SINGLE, "\u250C\u2500\u2500\u2510", "\u2514\u2500\u2500\u2518"),
                new Case(Border.ROUNDED, "\u256D\u2500\u2500\u256E", "\u2570\u2500\u2500\u256F"),
                new Case(Border.DOUBLE, "\u2554\u2550\u2550\u2557", "\u255A\u2550\u2550\u255D"));
        assertThat(cases).allSatisfy(entry -> {
            Screen screen = new LayoutEngine()
                    .layout(Views.border(entry.border(), Views.text("xx")), new Rect(0, 0, 3, 4))
                    .screen();
            java.util.List<String> lines = screen.plainLines();
            assertThat(lines).as("%s frame", entry.border()).hasSize(3);
            assertThat(lines.get(0)).isEqualTo(entry.top());
            assertThat(lines.get(1)).isEqualTo(entry.border().vertical() + "xx"
                    + entry.border().vertical());
            assertThat(lines.get(2)).isEqualTo(entry.bottom());
        });
        assertThat(Border.NONE.isVisible()).isFalse();
        var none = new LayoutEngine()
                .layout(Views.border(Border.NONE, Views.text("xx")), new Rect(0, 0, 1, 4)).screen();
        assertThat(none.plainLines()).as("an invisible border adds no frame and no inset")
                .containsExactly("xx");
        var horizontalOnly = new LayoutEngine()
                .layout(Views.border(new Border('\u250C', '\u2510', '\u2514', '\u2518', '\u2500', ' '),
                        Views.text("xx")), new Rect(0, 0, 2, 4)).screen();
        assertThat(horizontalOnly.plainLines())
                .as("a border with a blank vertical edge is not a frame")
                .containsExactly("xx", "");
    }

    @Test
    void aCustomBorderCannotCompleteAnEscapeSequenceFromItsOwnCharacters() {
        // A Border holds raw characters, but the frame paints them as content. The paint path
        // wrote them straight into cells, so a border whose vertical edge is ESC, around child
        // text "[2K", spelled ESC [ 2 K: a complete CSI that erases the row after the encoder had
        // painted it. Every other content path - StyledText.Span, and therefore Box and Table -
        // drops a control character or an unpaired surrogate before it can reach a cell.
        Border crafted = new Border('+', '+', '+', '+', '-', '\u001b');
        Screen screen = new LayoutEngine()
                .layout(Views.border(crafted, Views.text("[2K")), new Rect(0, 0, 3, 6)).screen();
        assertThat(screen.cell(1, 0).text()).as("the edge is a blank cell, not a control character")
                .isEqualTo(" ");
        assertThat(screen.plainLines()).containsExactly("+----+", " [2K", "+----+");
        String frame = AnsiEncoder.frame(screen);
        // The encoder clears each row exactly once; a fourth clear could only come from content.
        assertThat(frame.split("\u001b\\[2K", -1)).as("line clears in the frame").hasSize(4);
        // The same boundary owns an unpaired surrogate: UTF-8 would replace it with '?', so the
        // cell would display a character the border never held.
        Border surrogate = new Border('\ud800', '+', '+', '+', '-', '|');
        Screen surrogateScreen = new LayoutEngine()
                .layout(Views.border(surrogate, Views.text("[2K")), new Rect(0, 0, 3, 6)).screen();
        assertThat(surrogateScreen.cell(0, 0).text()).isEqualTo(" ");
    }

    @Test
    void aWideTitleNeverWidensTheFrame() {
        for (int width = 2; width <= 12; width++) {
            for (String title : new String[] {"", "ab", "\u65E5", "\u65E5\u672C", "abcdefghij"}) {
                var view = Views.border(Border.SINGLE, StyledText.of(title, Style.NONE.withBold()),
                        Views.text("x"));
                Screen screen = new LayoutEngine().layout(view, new Rect(0, 0, 3, width)).screen();
                for (String line : screen.plainLines()) {
                    assertThat(TextMeasure.columns(line))
                            .as("width %d title [%s] line [%s]", width, title, line)
                            .isLessThanOrEqualTo(Math.max(2, width));
                }
            }
        }
    }

    @Test
    void aBorderBreakOrTabOccupiesItsCellAsABlank() {
        // Border says each character is painted as content, so a control character occupies its
        // cell as a blank rather than reaching the terminal. A line break and a tab are control
        // characters the span boundary keeps for text, but a frame row has no line structure to
        // break and no tab stop to advance: the raw byte would split the row or move the cursor.
        for (char edge : new char[] {'\n', '\t', '\r', '\u0007'}) {
            Border horizontal = new Border('+', '+', '+', '+', edge, '|');
            Screen screen = new LayoutEngine()
                    .layout(Views.border(horizontal, Views.text("ab")), new Rect(0, 0, 3, 4))
                    .screen();
            assertThat(screen.plainLines()).as("horizontal edge 0x%04x", (int) edge)
                    .containsExactly("+  +", "|ab|", "+  +");
            assertThat(screen.cell(0, 1).text()).as("horizontal edge 0x%04x", (int) edge)
                    .isEqualTo(" ");
            assertThat(AnsiEncoder.frame(screen)).as("horizontal edge 0x%04x", (int) edge)
                    .doesNotContain("\n").doesNotContain("\t");
            Border vertical = new Border('+', '+', '+', '+', '-', edge);
            Screen side = new LayoutEngine()
                    .layout(Views.border(vertical, Views.text("ab")), new Rect(0, 0, 3, 4))
                    .screen();
            // plainLines trims trailing blanks, so the right edge is checked on the cell.
            assertThat(side.plainLines()).as("vertical edge 0x%04x", (int) edge)
                    .containsExactly("+--+", " ab", "+--+");
            assertThat(side.cell(1, 0).text()).as("vertical edge 0x%04x", (int) edge)
                    .isEqualTo(" ");
            assertThat(side.cell(1, 3).text()).as("trailing edge 0x%04x", (int) edge)
                    .isEqualTo(" ");
            assertThat(AnsiEncoder.frame(side)).as("vertical edge 0x%04x", (int) edge)
                    .doesNotContain("\n").doesNotContain("\t");
        }
    }

    @Test
    void aBorderFormatCharacterOccupiesItsCellAsABlank() {
        // A frame reserves exactly one cell per edge character. A format character is neither a
        // control character nor a surrogate, so the rule above let a zero-width space, joiner,
        // byte-order mark or soft hyphen into a cell, and measurement counts it as no column at
        // all: the row the frame reserved a cell for came back a column short and the cells after
        // it landed left of where the frame put them.
        for (char edge : new char[] {'\u200B', '\u200D', '\uFEFF', '\u00AD'}) {
            Border horizontal = new Border('+', '+', '+', '+', edge, '|');
            Screen screen = new LayoutEngine()
                    .layout(Views.border(horizontal, Views.text("ab")), new Rect(0, 0, 3, 4))
                    .screen();
            assertThat(screen.plainLines()).as("horizontal edge 0x%04x", (int) edge)
                    .containsExactly("+  +", "|ab|", "+  +");
            assertThat(TextMeasure.columns(screen.plainLines().get(0)))
                    .as("horizontal edge 0x%04x top row", (int) edge).isEqualTo(4);
            assertThat(screen.cell(0, 1).text()).as("horizontal edge 0x%04x", (int) edge)
                    .isEqualTo(" ");
            assertThat(AnsiEncoder.frame(screen)).as("horizontal edge 0x%04x", (int) edge)
                    .doesNotContain(String.valueOf(edge));
            Border vertical = new Border('+', '+', '+', '+', '-', edge);
            Screen side = new LayoutEngine()
                    .layout(Views.border(vertical, Views.text("ab")), new Rect(0, 0, 3, 4))
                    .screen();
            // plainLines trims trailing blanks, so the right edge is checked on the cell.
            assertThat(side.plainLines()).as("vertical edge 0x%04x", (int) edge)
                    .containsExactly("+--+", " ab", "+--+");
            assertThat(side.cell(1, 0).text()).as("vertical edge 0x%04x", (int) edge)
                    .isEqualTo(" ");
            assertThat(side.cell(1, 3).text()).as("trailing edge 0x%04x", (int) edge)
                    .isEqualTo(" ");
            assertThat(AnsiEncoder.frame(side)).as("vertical edge 0x%04x", (int) edge)
                    .doesNotContain(String.valueOf(edge));
        }
    }

    @Test
    void aTwoColumnBorderEdgeNeverWidensTheFrame() {
        // The perimeter is written cell by cell with a declared span of one column, so an edge
        // character the width policy measures as two - a fullwidth bar, a CJK corner bracket, a
        // fullwidth hyphen, a wide emoji - was painted into a cell that had not reserved its second
        // column. The encoder positions the row once and then writes cells in order, skipping only
        // continuation cells, and none was marked: every cell after the left edge landed a column
        // right of where the frame put it and the right edge was written past the last column.
        for (char edge : new char[] {'\uFF5C', '\u300C', '\uFF0D', '\u2B50'}) {
            java.util.List<Border> borders = java.util.List.of(
                    new Border('+', '+', '+', '+', '-', edge),
                    new Border('+', '+', '+', '+', edge, '|'),
                    new Border(edge, edge, edge, edge, '\u2500', '|'));
            assertThat(borders).allSatisfy(border -> {
                for (int width = 2; width <= 10; width++) {
                    Screen screen = new LayoutEngine()
                            .layout(Views.border(border, Views.text("ab")), new Rect(0, 0, 3, width))
                            .screen();
                    for (String line : screen.plainLines()) {
                        assertThat(TextMeasure.columns(line))
                                .as("edge 0x%04x width %d line [%s]", (int) edge, width, line)
                                .isLessThanOrEqualTo(Math.max(2, width));
                    }
                    // Every painted cell occupies at most the column the perimeter reserved for it,
                    // which is what keeps the cells after it in the columns the frame measured.
                    for (int row = 0; row < screen.rows(); row++) {
                        for (int col = 0; col < screen.cols(); col++) {
                            assertThat(TextMeasure.columns(screen.cell(row, col).text()))
                                    .as("edge 0x%04x width %d cell (%d,%d)",
                                            (int) edge, width, row, col)
                                    .isLessThanOrEqualTo(1);
                        }
                    }
                    assertThat(AnsiEncoder.frame(screen))
                            .as("edge 0x%04x width %d never reaches the terminal", (int) edge, width)
                            .doesNotContain(String.valueOf(edge));
                }
            });
            // The edge keeps its cell as a blank, so the frame still insets the child by one column
            // on each side and its corners line up with the rows they frame.
            Screen side = new LayoutEngine()
                    .layout(Views.border(new Border('+', '+', '+', '+', '-', edge),
                            Views.text("ab")), new Rect(0, 0, 3, 4)).screen();
            assertThat(side.cell(1, 0).text()).as("vertical edge 0x%04x", (int) edge).isEqualTo(" ");
            assertThat(side.cell(1, 3).text()).as("trailing edge 0x%04x", (int) edge).isEqualTo(" ");
            assertThat(side.plainLines()).as("vertical edge 0x%04x", (int) edge)
                    .containsExactly("+--+", " ab", "+--+");
        }
    }
}
