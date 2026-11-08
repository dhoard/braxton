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

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * What a layout owes the value it was measured at, over random view trees.
 *
 * <p>Painting is only correct if the grid describes the bytes the encoder writes: a row may not occupy
 * more columns than it was given, a cell may not paint columns the grid did not give it, and a cluster
 * may not be split across the boundary between two painted regions - a run that continues a cell
 * already on the row belongs to that cell and costs the column the join gains. And a value painted at
 * the height it measured must show the same content as the same value given more room: if it does not,
 * a container that sized itself from the measurement silently dropped what it asked for.</p>
 */
class LayoutConsistencyPropertyTest {

    /**
     * Text whose clusters claim no column of their own, mixed with clusters that are wide, narrowed by
     * a variation selector, drawn behind a dotted circle, or broken by tabs and newlines. These are the
     * shapes that move a column from one cell to the next.
     */
    private static final String[] TEXTS = {
        "alpha",
        "a b c",
        "\u4E2D\u6587 mixed",
        "\uD83D\uDE00 done",
        "\uFE0Fx",
        "\u0301z",
        "e\u0301",
        "\u200B\u0301",
        "\uFE0E \u4E2D",
        "\uD83D\uDE42\uFE0E tail",
        "",
        "x".repeat(9),
        "tab\there",
        "line1\nline2",
        "  padded  ",
        "\u00ADsoft",
        "a\tb\tc"
    };

    /**
     * Text that opens with a cluster claiming no column of its own, next to text that does not. Painted
     * one after another on a row, this is where a run meets a cell the row has already painted - and
     * where the cell it meets may itself hold more than one cluster, a dotted circle drawn behind a
     * bare mark after the invisible run that run was drawn for.
     */
    private static final String[] JOINS = {
        "\u200B\u0301", "\uFE0Fx", "\u0301z", "\uFE0E x", "\uFE0F", "alpha", "\u200B\u0301y", "\u4E2D"
    };

    private static final Border[] BORDERS = {Border.SINGLE, Border.NONE, new Border('+', '+', '+', '+', '-', '|')};

    @Test
    void aPaintedLayoutMatchesWhatItWasMeasuredAt() {
        Random random = new Random(20261107L);
        for (int round = 0; round < 1200; round++) {
            Built built = build(3, random);
            View<Object> view = built.view();
            int maxCols = 1 + random.nextInt(18);
            LayoutEngine engine = new LayoutEngine();
            int width = engine.intrinsicWidth(view, maxCols);
            assertThat(width)
                    .as("the measured width of %s fits the %d columns offered", view, maxCols)
                    .isBetween(0, maxCols);
            for (int cols : new int[] {Math.max(1, width), Math.max(1, width - 1), maxCols}) {
                int rows = engine.intrinsicHeight(view, cols);
                assertThat(rows)
                        .as("the measured height of %s at %d columns", view, cols)
                        .isNotNegative();
                Screen painted = engine.layout(view, new Rect(0, 0, rows, cols)).screen();
                assertGridDescribesTheStream(painted, cols, view, rows + "x" + cols);
                if (built.fills()) {
                    // A frame or a fill paints to the bounds it is given, so its own rows move when the
                    // bounds move. The height contract is about content that asked for its size.
                    continue;
                }
                Screen taller =
                        engine.layout(view, new Rect(0, 0, rows + 4, cols)).screen();
                for (int row = 0; row < Math.min(rows, taller.rows()); row++) {
                    assertThat(painted.plainLine(row))
                            .as(
                                    "%s at %dx%d lost row %d [%s] that it shows when given %d rows [%s]",
                                    view,
                                    rows,
                                    cols,
                                    row,
                                    show(painted.plainLine(row)),
                                    rows + 4,
                                    show(taller.plainLine(row)))
                            .isEqualTo(taller.plainLine(row));
                }
            }
        }
    }

    /** Every row of the grid agrees with the columns that row paints. */
    private static void assertGridDescribesTheStream(Screen screen, int cols, View<?> view, String where) {
        for (int row = 0; row < screen.rows(); row++) {
            String line = screen.plainLine(row);
            assertThat(TextMeasure.columns(line))
                    .as(
                            "%s at %s: row %d [%s] fills more than the %d columns offered",
                            view, where, row, show(line), cols)
                    .isLessThanOrEqualTo(cols);
            for (int col = 0; col < cols; col++) {
                String text = screen.cell(row, col).text();
                if (screen.isContinuation(row, col)) {
                    assertThat(text)
                            .as("%s at %s: the second half of a wide glyph is blank at %d,%d", view, where, row, col)
                            .isEmpty();
                    assertThat(TextMeasure.columns(screen.cell(row, col - 1).text()))
                            .as(
                                    "%s at %s: a continuation needs a two-column base before it at %d,%d",
                                    view, where, row, col)
                            .isEqualTo(2);
                    continue;
                }
                if (text.isEmpty()) {
                    continue;
                }
                int columns = TextMeasure.columns(text);
                assertThat(columns)
                        .as("%s at %s: cell %d,%d paints %d columns", view, where, row, col, columns)
                        .isBetween(1, 2);
                if (columns == 2 && col + 1 < cols) {
                    assertThat(screen.isContinuation(row, col + 1))
                            .as("%s at %s: the two-column cell at %d,%d owns the next cell too", view, where, row, col)
                            .isTrue();
                }
                if (col > 0 && !screen.isContinuation(row, col - 1)) {
                    // The row's segmentation decides what continues what. A run at a cell's head that
                    // joins the cell before it is part of that cell's cluster, and the column the join
                    // gains belongs to that cell - not to whoever is painted next.
                    String painted = screen.cell(row, col - 1).text();
                    String held = painted.isEmpty() ? " " : painted;
                    assertThat(TextMeasure.graphemeCount(held + text))
                            .as(
                                    "%s at %s: cell %d,%d [%s] continues the cell the row holds before it",
                                    view, where, row, col, show(text))
                            .isEqualTo(TextMeasure.graphemeCount(held) + TextMeasure.graphemeCount(text));
                }
            }
        }
    }

    /**
     * A random tree, and whether anything in it paints to its bounds rather than to its content. Such a
     * view legitimately changes shape when the bounds do, so it is held to the column invariants only.
     */
    private record Built(View<Object> view, boolean fills) {

        private static Built of(View<Object> view) {
            return new Built(view, view.toString().contains("BorderBox"));
        }
    }

    private static Built build(int depth, Random random) {
        if (depth <= 0 || random.nextInt(3) == 0) {
            return Built.of(Views.text(TEXTS[random.nextInt(TEXTS.length)]));
        }
        return switch (random.nextInt(8)) {
            case 0 -> Built.of(Views.border(BORDERS[random.nextInt(BORDERS.length)], child(depth - 1, random)));
            case 1 -> Built.of(Views.padding(Insets.uniform(random.nextInt(3)), child(depth - 1, random)));
            case 2 ->
                Built.of(Views.row(Alignment.START, random.nextBoolean() ? joins(random) : items(depth - 1, random)));
            case 3 -> Built.of(Views.column(Alignment.START, items(depth - 1, random)));
            case 4 -> Built.of(Views.overlay(child(depth - 1, random), child(depth - 1, random)));
            case 5 ->
                Built.of(Views.padding(
                        new Insets(random.nextInt(2), random.nextInt(3), random.nextInt(2), random.nextInt(3)),
                        child(depth - 1, random)));
            default -> Built.of(Views.column(Alignment.END, items(depth - 1, random)));
        };
    }

    /** Two or three texts in a row, each of which may open with a run that continues its neighbour. */
    private static List<View<Object>> joins(Random random) {
        List<View<Object>> items = new ArrayList<>();
        for (int count = 2 + random.nextInt(2); count > 0; count--) {
            items.add(Views.text(JOINS[random.nextInt(JOINS.length)]));
        }
        return items;
    }

    private static View<Object> child(int depth, Random random) {
        return build(depth, random).view();
    }

    private static List<View<Object>> items(int depth, Random random) {
        List<View<Object>> items = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            items.add(build(depth, random).view());
        }
        return items;
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }
}
