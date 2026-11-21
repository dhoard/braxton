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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import org.junit.jupiter.api.Test;

/**
 * What one frame costs when the screen it follows already shows almost the same thing.
 *
 * <p>R-13 fixes the budget: a one-cell change emits at most 48 bytes with the synchronized-update
 * wrappers excluded. {@link RenderCostTest} holds the other half of that requirement, the allocation
 * of the same change. The cases here pin the two mechanisms that meet it - a run of changed cells
 * addressed where it is, and a region of rows moved with the terminal's own insert and delete line
 * sequences - and the rules that keep them honest: whole graphemes, a cleared tail, a repainted row
 * only where a full row can have spilled, and a full frame whenever the geometry changed.</p>
 */
class AnsiDiffTest {

    @Test
    void aOneCellChangeStaysInsideTheFortyEightByteBudget() {
        Screen before = scene(24, 80);
        Screen after = scene(24, 80);
        after.put(12, 40, "Z", Style.fg(Colors.ERROR_400), null, 1);
        String out = AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR);
        assertThat(out)
                .as("R-13: one cell change, addressed at its own cell")
                .startsWith("\u001B[13;41H")
                .contains("Z")
                .doesNotContain("\u001B[13;1H", "\u001B[14;1H", "\u001B[1;1H", "\u001B[0K", "\u001B[2K");
        assertThat(out.length()).as("R-13's byte budget for a one-cell change").isLessThanOrEqualTo(48);
    }

    @Test
    void anUnchangedFrameWritesNothingAndAColorOnlyChangeWritesOneCell() {
        Screen before = scene(4, 10);
        Screen after = scene(4, 10);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR)).isEmpty();
        after.put(1, 3, "x", Style.fg(Colors.ERROR_400), null, 1);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("the same glyph in a new color is one addressed cell")
                .startsWith("\u001B[2;4H")
                .contains(Colors.ERROR_400.red() + ";" + Colors.ERROR_400.green() + ";" + Colors.ERROR_400.blue())
                .doesNotContain("\u001B[2;1H");
    }

    @Test
    void aLinkChangeRepaintsTheCellThatCarriesIt() {
        Screen before = new Screen(1, 4);
        before.put(0, 1, "a", Style.NONE, null, 1);
        Screen after = new Screen(1, 4);
        after.put(0, 1, "a", Style.NONE, "https://example.com", 1);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .isEqualTo("\u001B[1;2H\u001B]8;;https://example.com\u001B\\a\u001B]8;;\u001B\\"
                        + "\u001B[1;3H\u001B[0m\u001B[0K");
    }

    @Test
    void twoSeparatedRunsInOneRowAreAddressedSeparatelyAndTheCellsBetweenThemKept() {
        Screen before = lines(1, 8, "abcdefgh");
        Screen after = lines(1, 8, "abcXefYh");
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("two changed cells, each at its own column, and nothing in between")
                .isEqualTo("\u001B[1;4HX\u001B[1;7HY");
    }

    @Test
    void aRunIsWidenedToWholeGraphemesOnBothSidesOfTheChange() {
        Screen before = new Screen(1, 6);
        before.put(0, 1, "界", Style.NONE, null, 2);
        before.put(0, 3, "b", Style.NONE, null, 1);
        Screen after = new Screen(1, 6);
        after.put(0, 1, "漢", Style.NONE, null, 2);
        after.put(0, 3, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("one wide grapheme is painted once, from the column it starts in")
                .isEqualTo("\u001B[1;2H漢");

        Screen narrowed = new Screen(1, 6);
        narrowed.put(0, 1, "a", Style.NONE, null, 1);
        narrowed.put(0, 2, "b", Style.NONE, null, 1);
        narrowed.put(0, 3, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(before, narrowed, ColorProfile.TRUECOLOR))
                .as("a wide grapheme that became narrow repaints the column it used to hold")
                .startsWith("\u001B[1;2Hab");

        Screen wasWide = new Screen(1, 6);
        wasWide.put(0, 1, "界", Style.NONE, null, 2);
        wasWide.put(0, 3, "b", Style.NONE, null, 1);
        Screen oneColumn = new Screen(1, 6);
        oneColumn.put(0, 1, "界", Style.NONE, null, 1);
        oneColumn.put(0, 2, "b", Style.NONE, null, 1);
        oneColumn.put(0, 3, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(wasWide, oneColumn, ColorProfile.TRUECOLOR))
                .as("the column that was a grapheme's second half is repainted with the one before it")
                .startsWith("\u001B[1;2H界b");
    }

    @Test
    void aShrunkenRowClearsItsTailAndARowThatEmptiedClearsItsWholeLine() {
        Screen before = new Screen(2, 6);
        for (int col = 0; col < 6; col++) {
            before.put(0, col, "x", Style.NONE, null, 1);
            before.put(1, col, "y", Style.NONE, null, 1);
        }
        Screen after = new Screen(2, 6);
        for (int col = 0; col < 3; col++) {
            after.put(0, col, "x", Style.NONE, null, 1);
        }
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("a row that got shorter erases from where its content ends")
                .isEqualTo("\u001B[1;4H\u001B[0m\u001B[0K\u001B[2;1H\u001B[0m\u001B[2K");
    }

    @Test
    void aRowBelowAFullWidthRowIsRepaintedButARowBelowAShortOneIsNot() {
        Screen before = new Screen(3, 4);
        Screen after = new Screen(3, 4);
        for (Screen screen : new Screen[] {before, after}) {
            screen.put(1, 0, "m", Style.NONE, null, 1);
            screen.put(2, 0, "z", Style.NONE, null, 1);
        }
        after.put(0, 3, "f", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("a change in the last column can spill into the row below it")
                .contains("\u001B[1;4H", "\u001B[2;1H")
                .doesNotContain("\u001B[3;1H");

        Screen shortRow = new Screen(3, 4);
        Screen shortRowAfter = new Screen(3, 4);
        for (Screen screen : new Screen[] {shortRow, shortRowAfter}) {
            screen.put(1, 0, "m", Style.NONE, null, 1);
            screen.put(2, 0, "z", Style.NONE, null, 1);
        }
        shortRowAfter.put(0, 1, "f", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(shortRow, shortRowAfter, ColorProfile.TRUECOLOR))
                .as("a row that stops short of the last column spills nowhere")
                .contains("\u001B[1;2H")
                .doesNotContain("\u001B[2;1H", "\u001B[3;1H");
    }

    @Test
    void aRunThatReachesTheFramesLastPaintedCellErasesTheTailItDoesNotPaint() {
        // The shape a full-screen row takes when the frame it follows painted more of it: the cells
        // past the frame's own painted end differ from the previous row, but the frame leaves them
        // empty, so one erase has to cover them instead of a blank per cell.
        Screen before = lines(1, 12, "REFRESH_FULL");
        Screen after = lines(1, 12, "short");
        after.put(0, 5, "界", Style.NONE, null, 2);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("the new cells, then one erase from where the frame stops painting")
                .isEqualTo("\u001B[1;1Hshort界\u001B[1;8H\u001B[0m\u001B[0K");
    }

    @Test
    void aTailThatOnlyThePreviousFramePaintedIsErasedWithoutARun() {
        Screen before = new Screen(1, 8);
        for (int col = 0; col < 8; col++) {
            before.put(0, col, "x", Style.NONE, null, 1);
        }
        Screen after = new Screen(1, 8);
        after.put(0, 0, "x", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("nothing changed inside the painted extent, so the erase is the whole change")
                .isEqualTo("\u001B[1;2H\u001B[0m\u001B[0K");
    }

    @Test
    void aRegionThatScrolledIsMovedInsteadOfRepaintedWithTheMarginsGivenBack() {
        Screen before = panel(10, 20, "one", "two", "three", "four", "five", "six", "seven", "eight");
        // Rows 1..8 are the scrolling pane's; its content moved up by two and two new lines arrived.
        Screen after = new Screen(10, 20);
        copyRow(before, 0, after, 0);
        copyRow(before, 3, after, 1);
        copyRow(before, 4, after, 2);
        copyRow(before, 5, after, 3);
        copyRow(before, 6, after, 4);
        copyRow(before, 7, after, 5);
        copyRow(before, 8, after, 6);
        write(after, 7, "nine");
        write(after, 8, "ten");
        copyRow(before, 9, after, 9);

        String out = AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR);
        assertThat(out)
                .as("the region's own margins, the delete, and the margins given back")
                .contains("\u001B[2;9r", "\u001B[2;1H\u001B[2M", "\u001B[r")
                .as("only the two rows the scroll exposed are painted")
                .doesNotContain("three", "four", "five", "six")
                .contains("nine", "ten")
                .contains("\u001B[8;1H", "\u001B[9;1H")
                .doesNotContain("\u001B[1;1H", "\u001B[10;1H");
    }

    @Test
    void aWholeScreenScrollNeedsNoMarginsAndAScrollDownInsertsInstead() {
        Screen before = lines(6, 10, "a", "b", "c", "d", "e", "f");
        Screen up = lines(6, 10, "b", "c", "d", "e", "f", "g");
        assertThat(AnsiEncoder.diff(before, up, ColorProfile.TRUECOLOR))
                .as("the whole screen is already the scrolling region")
                .isEqualTo("\u001B[1;1H\u001B[1M" + row(5, "g"))
                .doesNotContain("r");

        Screen down = lines(6, 10, "z", "a", "b", "c", "d", "e");
        assertThat(AnsiEncoder.diff(before, down, ColorProfile.TRUECOLOR))
                .isEqualTo("\u001B[1;1H\u001B[1L" + row(0, "z"))
                .doesNotContain("r");
    }

    @Test
    void aShiftThatExposesAsManyRowsAsItCoversIsNotWorthItsSequences() {
        Screen before = lines(4, 10, "a", "b", "c", "d");
        Screen after = lines(4, 10, "c", "d", "e", "f");
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("a two-row shift over four rows covers no more than it exposes")
                .doesNotContain("\u001B[2M", "\u001B[2L", "\u001B[r");
    }

    @Test
    void aScrollThatAlsoChangesOutsideItsRegionRepaintsInstead() {
        Screen before = panel(6, 10, "one", "two", "three", "four");
        Screen after = new Screen(6, 10);
        copyRow(before, 2, after, 1);
        copyRow(before, 3, after, 2);
        copyRow(before, 4, after, 3);
        write(after, 4, "new");
        write(after, 1, "ONE");
        copyRow(before, 0, after, 0);
        copyRow(before, 5, after, 5);
        after.put(5, 0, "Z", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(before, after, ColorProfile.TRUECOLOR))
                .as("a row outside the region changed, so no scroll explains the frame")
                .contains("\u001B[2;1HONE")
                .doesNotContain("\u001B[1M", "\u001B[1L", "\u001B[r");
    }

    @Test
    void aResizePaintsTheWholeFrameHoweverItChanged() {
        Screen before = lines(4, 6, "a", "b", "c", "d");
        Screen taller = lines(6, 6, "b", "c", "d", "e", "f", "g");
        assertThat(AnsiEncoder.diff(before, taller, ColorProfile.TRUECOLOR))
                .as("cell diffs need the same geometry")
                .isEqualTo(AnsiEncoder.frame(taller, ColorProfile.TRUECOLOR));
        Screen wider = lines(4, 8, "a", "b", "c", "d");
        assertThat(AnsiEncoder.diff(before, wider, ColorProfile.TRUECOLOR))
                .isEqualTo(AnsiEncoder.frame(wider, ColorProfile.TRUECOLOR));
    }

    @Test
    void anEmptyOrGeometrylessScreenWritesNothingInsteadOfAFrame() {
        assertThat(AnsiEncoder.diff(new Screen(0, 0), new Screen(0, 0), ColorProfile.TRUECOLOR))
                .isEmpty();
        assertThat(AnsiEncoder.diff(new Screen(2, 0), new Screen(2, 0), ColorProfile.TRUECOLOR))
                .isEmpty();
        assertThat(AnsiEncoder.diff(new Screen(2, 0), new Screen(3, 0), ColorProfile.TRUECOLOR))
                .isEqualTo(AnsiEncoder.frame(new Screen(3, 0), ColorProfile.TRUECOLOR));
    }

    /**
     * A 24x80 scene painted cell by cell with the style changes a real frame has.
     */
    private static Screen scene(int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Style style = switch ((row + col) % 3) {
                    case 0 -> Style.fg(Colors.BLUE_400);
                    case 1 -> Style.NONE.withBold();
                    default -> Style.NONE;
                };
                screen.put(row, col, "x", style, null, 1);
            }
        }
        return screen;
    }

    /**
     * A frame of one-letter rows, so a shift is visible in the text.
     */
    private static Screen lines(int rows, int cols, String... content) {
        Screen screen = new Screen(rows, cols);
        for (int row = 0; row < content.length; row++) {
            write(screen, row, content[row]);
        }
        return screen;
    }

    /**
     * A frame with a fixed first and last row and words between them.
     */
    private static Screen panel(int rows, int cols, String... content) {
        Screen screen = lines(rows, cols, content);
        write(screen, 0, "TOP");
        write(screen, rows - 1, "BOTTOM");
        return screen;
    }

    /**
     * Writes one plain word at the start of a row, or blanks the row when the word is empty.
     */
    private static void write(Screen screen, int row, String text) {
        for (int col = 0; col < text.length(); col++) {
            screen.put(row, col, String.valueOf(text.charAt(col)), Style.NONE, null, 1);
        }
    }

    private static void copyRow(Screen from, int fromRow, Screen to, int toRow) {
        for (int col = 0; col < from.cols(); col++) {
            if (from.isContinuation(fromRow, col) || from.text(fromRow, col).isEmpty()) {
                continue;
            }
            to.put(toRow, col, from.text(fromRow, col), from.style(fromRow, col), from.link(fromRow, col), 1);
        }
    }

    /**
     * One row as the encoder writes it on its own, which is what a scrolled-into-view row costs.
     */
    private static String row(int row, String text) {
        Screen screen = new Screen(row + 1, 10);
        write(screen, row, text);
        return AnsiEncoder.encodeRow(screen, row, ColorProfile.TRUECOLOR);
    }
}
