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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.AllocationProbe;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A style with no background inherits the background already painted in its cell, which is how text
 * keeps the colour of a background fill it is painted over.
 *
 * <p>That merged style is derived per cell, so a frame that fills a background and paints text over
 * it derived a fresh style for every column of that text - as many objects as the frame writes
 * cells - on the path every dirty presentation pays. The bounds below pin the cost of such a run;
 * the parity tests pin the frame the cheaper path has to paint, against the same text styled with
 * that background itself.</p>
 */
class BackgroundInheritanceCostTest {

    private static final int COLUMNS = 2048;
    private static final int ROWS = 40;
    private static final int FRAME_COLUMNS = 120;

    private static final Color FILL = Colors.GRAY_600;

    private final LayoutEngine engine = new LayoutEngine();

    /** Every painted cell of a screen, so two frames can be compared column by column. */
    private static String paintedCells(Screen screen) {
        StringBuilder cells = new StringBuilder();
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                Cell cell = screen.cell(row, col);
                cells.append(screen.isContinuation(row, col) ? ">" : " ")
                        .append('[').append(cell.text()).append(']')
                        .append(cell.style()).append(cell.link()).append('|');
            }
            cells.append('\n');
        }
        return cells.toString();
    }

    /** {@code child} painted over a background that fills the bounds. */
    private static View<Object> overFill(View<Object> child, Style background) {
        List<View<Object>> layers = new ArrayList<>();
        layers.add(Views.fill(background));
        layers.add(child);
        return Views.overlay(layers, false);
    }

    /** One row per {@code rows}, each exactly {@code columns} characters of its own style. */
    private static View<Object> textRows(int rows, int columns) {
        List<View<Object>> lines = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            String prefix = "row " + row + ": ";
            String line = prefix + "x".repeat(Math.max(0, columns - prefix.length()));
            lines.add(Views.text(StyledText.of(line, Style.fg(new Color(row % 251, 200, 230)))));
        }
        return Views.column(lines);
    }

    @Test
    void aRunOfColumnsOverABackgroundFillDoesNotDeriveAStylePerColumn() {
        Style background = Style.bg(FILL);
        Style foreground = Style.fg(Colors.WHITE);
        long bytes = AllocationProbe.bytes(() -> {
            Screen screen = new Screen(1, COLUMNS);
            screen.fill(new Rect(0, 0, 1, COLUMNS), background);
            for (int col = 0; col < COLUMNS; col++) {
                screen.put(0, col, "x", foreground, null, 1);
            }
            return screen.cell(0, COLUMNS - 1);
        });
        assertThat(bytes)
                .as("a run of columns painted over one background costs the cell it writes and not "
                        + "a style it derived a second time: the merged style is remembered")
                .isLessThan(COLUMNS * 40L);
    }

    @Test
    void aFramePaintedOverABackgroundFillCostsWhatTheSameFrameCostsWithoutOne() {
        Style background = Style.bg(FILL);
        View<Object> rows = textRows(ROWS, FRAME_COLUMNS);
        long withoutFill = AllocationProbe.bytes(() -> engine.layout(rows,
                new Rect(0, 0, ROWS, FRAME_COLUMNS)));
        long overFill = AllocationProbe.bytes(() -> engine.layout(overFill(rows, background),
                new Rect(0, 0, ROWS, FRAME_COLUMNS)));
        assertThat(overFill)
                .as("painting a frame over a background fill must not add a style per column of the "
                        + "text it paints over that fill")
                .isLessThan(withoutFill + withoutFill / 2);
    }

    @Test
    void inheritingTheBackgroundPaintsTheCellsAndBytesAnExplicitBackgroundPaints() {
        String line = "status: idle, running, stopped";
        Style foreground = Style.fg(Colors.WHITE).withBold();
        Screen inherited = engine.layout(
                overFill(Views.text(StyledText.of(line, foreground)), Style.bg(FILL)),
                new Rect(0, 0, 1, line.length())).screen();
        Screen explicit = engine.layout(
                Views.text(StyledText.of(line, foreground.withBackground(FILL))),
                new Rect(0, 0, 1, line.length())).screen();
        assertThat(paintedCells(inherited)).isEqualTo(paintedCells(explicit));
        assertThat(AnsiEncoder.frame(inherited, true))
                .isEqualTo(AnsiEncoder.frame(explicit, true));
    }

    @Test
    void aFrameMixingMoreStylesThanTheScreenRemembersStillInheritsTheBackground() {
        Style background = Style.bg(FILL);
        List<View<Object>> lines = new ArrayList<>();
        for (int row = 0; row < 9; row++) {
            // More distinct styles than the screen remembers pairs, so a painted column cannot be
            // answered from the pair the column before it happened to leave behind.
            Style style = new Style(new Color(10 + row, 200, 230), null, false, false,
                    row % 2 == 0, false, false, false);
            lines.add(Views.text(StyledText.of("row " + row + " text", style)));
        }
        Screen screen = engine.layout(overFill(Views.column(lines), background),
                new Rect(0, 0, 9, 20)).screen();
        List<Style> painted = new ArrayList<>();
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                if (screen.cell(row, col).style().foreground() != null) {
                    painted.add(screen.cell(row, col).style());
                }
            }
        }
        assertThat(painted).isNotEmpty();
        assertThat(painted).allSatisfy(style ->
                assertThat(style.background()).isEqualTo(FILL));
        assertThat(painted.stream().map(Style::foreground).distinct()).hasSize(9);
    }

    @Test
    void clearingTheHalfOfABackgroundWideGlyphKeepsTheInheritedStyle() {
        Screen screen = new Screen(1, 4);
        screen.fill(new Rect(0, 0, 1, 4), Style.bg(FILL));
        screen.put(0, 0, "\u65E5", Style.fg(Colors.WHITE), null, 2);
        screen.put(0, 1, "x", Style.NONE, null, 1);
        assertThat(screen.plainLine(0)).isEqualTo(" x");
        assertThat(screen.cell(0, 0).style().background()).isEqualTo(FILL);
        assertThat(screen.cell(0, 1).style().background()).isEqualTo(FILL);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).endsWith(" x  \u001B[0m");
    }
}
