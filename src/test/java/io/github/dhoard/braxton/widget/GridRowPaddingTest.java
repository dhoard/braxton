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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.TextMeasure;

/**
 * The row charge the widgets share, against the whole-prefix measurement it replaces.
 *
 * <p>Before a row kept only its trailing cluster, a cell was charged the columns the row gained when
 * the cell was appended to the whole text painted before it:
 * {@code columns(row + cell) - columns(row)}. Keeping one cluster is only equivalent if that
 * difference depends on the trailing cluster alone, which is what these cases hold it to across the
 * seams that can join a cell to the cell before it - a leading combining mark, a leading variation
 * selector, a wide glyph, a regional-indicator pair, a zero-width run, a tab, and an empty cell.</p>
 */
class GridRowPaddingTest {

    /** Pieces whose seam with a previous cluster is the whole point of the row charge. */
    private static final String[] PIECES = {
        "a", "bc", "xyz", " ", "  ", "\t", "",
        "\u4E2D", "\uD83D\uDE00", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
        "\uD83C\uDDFA\uD83C\uDDF8", "\uD83C\uDDFB", "e\u0301", "\u0301", "\u0301\u0301",
        "\uFE0F", "\uFE0E", "\u2764\uFE0F", "\u200B", "\u00AD", "\uDB40\uDC01"
    };

    /** The pre-change charge: the columns the whole row gains when {@code cell} is appended. */
    private static int wholeRowPad(String row, String cell, int width) {
        int before = TextMeasure.columns(row);
        return Math.max(0, width - (TextMeasure.columns(row + cell) - before));
    }

    @Test
    void everyCellIsChargedWhatMeasuringTheWholeRowCharged() {
        Random random = new Random(20261103L);
        for (int iteration = 0; iteration < 20_000; iteration++) {
            List<String> cells = new ArrayList<>();
            List<Integer> widths = new ArrayList<>();
            int count = 1 + random.nextInt(6);
            for (int i = 0; i < count; i++) {
                StringBuilder cell = new StringBuilder();
                int pieces = random.nextInt(3);
                for (int j = 0; j < pieces; j++) {
                    cell.append(PIECES[random.nextInt(PIECES.length)]);
                }
                cells.add(cell.toString());
                widths.add(random.nextInt(6));
            }
            String painted = "";
            GridRows.Row row = GridRows.Row.empty();
            for (int i = 0; i < count; i++) {
                int expected = wholeRowPad(painted, cells.get(i), widths.get(i));
                assertThat(row.pad(cells.get(i), widths.get(i)))
                        .as("pad of cell %s in %s at widths %s", cells.get(i), cells, widths)
                        .isEqualTo(expected);
                painted = painted + cells.get(i) + " ".repeat(expected);
            }
        }
    }

    @Test
    void aCellIsPaddedToItsColumnAndNeverPaddedNegatively() {
        assertThat(GridRows.Row.empty().pad("ab", 5)).isEqualTo(3);
        assertThat(GridRows.Row.empty().pad("ab", 2)).isZero();
        assertThat(GridRows.Row.empty().pad("abcdef", 2)).isZero();
        // An empty cell paints its whole column as pad.
        assertThat(GridRows.Row.empty().pad("", 4)).isEqualTo(4);
        // The gap is painted after every cell, so it is charged even to a cell that needs no pad.
        assertThat(GridRows.Row.empty().pad("ab", 2, 3)).isEqualTo(3);
        assertThat(GridRows.Row.empty().pad("ab", 5, 3)).isEqualTo(6);
    }

    @Test
    void aClusterThatJoinsTheCellBeforeItIsNotChargedAColumnOfItsOwn() {
        GridRows.Row row = GridRows.Row.empty();
        assertThat(row.pad("ab", 2)).isZero();
        // The mark joins the "b" the row ends with, so it adds no column and the cell's column is
        // pad; a mark that leads a row is a cell of its own and fills its column instead.
        assertThat(row.pad("\u0301", 1)).isEqualTo(1);
        assertThat(GridRows.Row.empty().pad("\u0301", 1)).isZero();
    }

    @Test
    void aSelectorIsChargedTheColumnItWidensTheCellBeforeItBy() {
        GridRows.Row row = GridRows.Row.empty();
        assertThat(row.pad("ab", 2)).isZero();
        // "b" widens from one painted column to two when the selector joins it, so the cell is
        // charged that column and needs no pad of its own.
        assertThat(row.pad("\uFE0F", 1)).isZero();
    }

    @Test
    void aRowThatAlreadyCarriesAPrefixChargesItsFirstCellAgainstItsTail() {
        assertThat(GridRows.Row.after("\u2502 ").pad("\u0301", 1)).isEqualTo(1);
        assertThat(GridRows.Row.after("\u2502").pad("\u0301", 1)).isEqualTo(1);
        assertThat(GridRows.Row.after("").pad("\u0301", 1)).isZero();
        assertThat(GridRows.Row.after(null).pad("ab", 3)).isEqualTo(1);
        assertThat(GridRows.Row.after("\u2502 ").pad("ab", 3))
                .isEqualTo(wholeRowPad("\u2502 ", "ab", 3));
    }
}
