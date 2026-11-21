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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The inline renderer's own contract: relative painting, its own region, and nothing above it.
 *
 * <p>These cases drive the encoder directly, so a rule that the runtime exercises through a session is
 * also asserted where it is decided: how many rows a region occupies, what a repaint moves, what a
 * durable line does to the region's origin, and what the exit leaves behind.</p>
 */
class InlineRendererTest {

    /**
     * A renderer whose caret is hidden, which is what a view without a cursor asks for.
     */
    private static InlineRenderer renderer() {
        return new InlineRenderer(new CursorPresenter(false));
    }

    /**
     * A screen with one plain row per text.
     */
    private static Screen screen(String... rows) {
        Screen screen = new Screen(Math.max(rows.length, 1) + 1, 20);
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                screen.put(row, column, rows[row].substring(column, column + 1), Style.NONE, null, 1);
            }
        }
        return screen;
    }

    @Test
    void aFirstFrameAnchorsTheRegionWhereTheCursorIs() {
        InlineRenderer inline = renderer();
        String frame = inline.frame(null, screen("one", "two"), ColorProfile.NONE, Optional.empty());

        assertThat(frame)
                .as("the region starts at the cursor's own row: a carriage return, then a forward clear")
                .startsWith("\r" + AnsiEncoder.ERASE_DISPLAY_BELOW);
        assertThat(frame)
                .as("each row is painted, its own suffix erased, and the next one started by CR LF")
                .contains("one" + AnsiEncoder.ERASE_LINE_RIGHT + "\r\ntwo" + AnsiEncoder.ERASE_LINE_RIGHT);
        assertThat(frame)
                .as("the cursor is left at the start of the last region row")
                .endsWith("\r");
        assertThat(frame).as("nothing is addressed absolutely").doesNotContain("H");
        assertThat(inline.height()).isEqualTo(2);
    }

    @Test
    void anUnchangedFrameWritesNothing() {
        InlineRenderer inline = renderer();
        Screen showing = screen("one");
        inline.frame(null, showing, ColorProfile.NONE, Optional.empty());

        assertThat(inline.frame(showing, screen("one"), ColorProfile.NONE, Optional.empty()))
                .as("an idle region costs no traffic")
                .isEmpty();
    }

    @Test
    void aShorterFrameClearsTheRowsTheViewNoLongerPaints() {
        InlineRenderer inline = renderer();
        inline.frame(null, screen("one", "two", "three"), ColorProfile.NONE, Optional.empty());

        String shorter =
                inline.frame(screen("one", "two", "three"), screen("one"), ColorProfile.NONE, Optional.empty());
        assertThat(shorter)
                .as("the region returns to its first row before painting")
                .startsWith("\r" + AnsiEncoder.moveUp(2));
        assertThat(shorter)
                .as("everything below the first row is cleared instead of the whole display")
                .contains(AnsiEncoder.ERASE_DISPLAY_BELOW);
        assertThat(inline.height()).isEqualTo(1);
    }

    @Test
    void aDurableLineMovesTheRegionBelowIt() {
        InlineRenderer inline = renderer();
        inline.frame(null, screen("one", "two"), ColorProfile.NONE, Optional.empty());

        String printed = inline.print("installed parser");
        assertThat(printed)
                .as("the region is erased from its own first row")
                .startsWith("\r" + AnsiEncoder.moveUp(1) + AnsiEncoder.ERASE_DISPLAY_BELOW);
        assertThat(printed)
                .as("the line is written where the region began, and the cursor moves past it")
                .contains(AnsiEncoder.RESET + "installed parser\r\n");
        assertThat(inline.height()).as("the region no longer occupies any row").isZero();

        String repaint = inline.frame(null, screen("one", "two"), ColorProfile.NONE, Optional.empty());
        assertThat(repaint)
                .as("the next frame starts at the new origin without moving up")
                .startsWith("\r" + AnsiEncoder.ERASE_DISPLAY_BELOW);
    }

    @Test
    void printedTextIsNormalizedToPlainLines() {
        assertThat(renderer().print("")).as("an empty text prints nothing").isEmpty();
        assertThat(renderer().print(null)).isEmpty();
        String printed = renderer().print("a\r\nb\rc\u001b[31md\te\u0007");
        assertThat(printed).contains("a\r\nb\r\nc[31md\te\r\n");
        assertThat(printed.replace(AnsiEncoder.RESET, ""))
                .as("no control byte of the caller's survives; the one reset is the session's own")
                .doesNotContain("\u001b");
    }

    @Test
    void anEmptyRegionIsNotReachableAndLeavesNothingBehind() {
        InlineRenderer inline = renderer();
        assertThat(inline.finish())
                .as("a session that painted nothing writes nothing")
                .isEmpty();

        String empty = inline.frame(null, screen(), ColorProfile.NONE, Optional.empty());
        assertThat(empty)
                .as("a view with no rows paints none, and still clears the region")
                .contains(AnsiEncoder.ERASE_DISPLAY_BELOW)
                .doesNotContain("row");
        assertThat(inline.height()).isZero();
        assertThat(inline.finish()).isEmpty();
    }

    @Test
    void theExitLeavesTheFinalFrameAndPutsTheCursorBelowIt() {
        InlineRenderer inline = renderer();
        inline.frame(null, screen("one", "two", "three"), ColorProfile.NONE, Optional.empty());

        String finish = inline.finish();
        assertThat(finish)
                .as("the cursor is on the region's last row, so the newline after it ends the region")
                .isEqualTo("\r\r\n");
        assertThat(inline.height()).isZero();
        assertThat(inline.finish()).as("the exit sequence is written once").isEmpty();
    }

    @Test
    void aNativeCaretIsPlacedByMovingInsideTheRegion() {
        InlineRenderer inline = new InlineRenderer(new CursorPresenter(false));
        Screen screen = screen("one", "two");
        String frame = inline.frame(
                null,
                screen,
                ColorProfile.NONE,
                Optional.of(Cursor.at(0, 2).withShape(Cursor.Shape.BAR).withMode(Cursor.Mode.STEADY)));

        assertThat(frame)
                .as("the caret moves back up inside the region and across to its column")
                .contains(AnsiEncoder.moveUp(1) + AnsiEncoder.moveToColumn(2));
        assertThat(frame).as("nothing is addressed absolutely").doesNotContain("H");
        assertThat(frame).as("the caret is shown once it is in place").endsWith(AnsiEncoder.SHOW_CURSOR);

        String hidden = inline.frame(
                screen, screen, ColorProfile.NONE, Optional.of(Cursor.at(0, 2).withStrategy(Cursor.Strategy.VIRTUAL)));
        assertThat(hidden)
                .as("a virtual caret is painted by the frame, so the terminal's own is hidden again")
                .endsWith(AnsiEncoder.HIDE_CURSOR);
        assertThat(hidden)
                .as("the terminal's cursor returns to the region's last row")
                .contains("\r");
    }

    @Test
    void aRowThatReachesTheLastColumnIsGivenAGuardRow() {
        InlineRenderer inline = renderer();
        Screen full = new Screen(3, 4);
        for (int column = 0; column < 4; column++) {
            full.put(0, column, "x", Style.NONE, null, 1);
        }
        inline.frame(null, full, ColorProfile.NONE, Optional.empty());

        assertThat(inline.height())
                .as("the row below a full-width row belongs to the region, so a spilled cell lands inside it")
                .isEqualTo(2);
    }
}
