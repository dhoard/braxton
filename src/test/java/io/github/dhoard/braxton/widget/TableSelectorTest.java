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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;

/** A table selector: navigation, confirmation, and the drawn grid. */
class TableSelectorTest {

    private static final List<String> HEADER = List.of("id", "name");
    private static final List<List<String>> ROWS = List.of(List.of("1", "alpha"), List.of("2", "be"));

    private static TableSelector.State state() {
        return TableSelector.State.of(HEADER, ROWS);
    }

    @Test
    void navigationClampsAndConfirmNamesTheSelectedCell() {
        TableSelector.State state = state();
        assertThat(TableSelector.update(state, new TableSelector.Action.Up()).state().row()).isZero();
        assertThat(TableSelector.update(state, new TableSelector.Action.Left()).state().column())
                .isZero();
        TableSelector.State moved = TableSelector.update(state, new TableSelector.Action.Down())
                .state();
        moved = TableSelector.update(moved, new TableSelector.Action.Down()).state();
        moved = TableSelector.update(moved, new TableSelector.Action.Right()).state();
        moved = TableSelector.update(moved, new TableSelector.Action.Right()).state();
        assertThat(moved.row()).as("the last row is the floor").isEqualTo(1);
        assertThat(moved.column()).as("the last column is the floor").isEqualTo(1);
        assertThat(moved.selectedValue()).isEqualTo("be");
        assertThat(TableSelector.update(moved, new TableSelector.Action.Confirm()).intent())
                .isEqualTo(new TableSelector.Intent.Chosen(1, 1, "be"));
        assertThat(TableSelector.update(moved, new TableSelector.Action.Cancel()).intent())
                .isEqualTo(new TableSelector.Intent.Cancelled());
    }

    @Test
    void anEmptyTableHasNothingToConfirmAndNoSelectedValue() {
        TableSelector.State empty = TableSelector.State.of(List.of(), List.of());
        assertThat(empty.row()).isEqualTo(-1);
        assertThat(empty.column()).isEqualTo(-1);
        assertThat(empty.selectedValue()).isEmpty();
        assertThat(TableSelector.update(empty, new TableSelector.Action.Confirm()).intent())
                .isEqualTo(new TableSelector.Intent.None());
        // A header without rows still has a column, but no row to name.
        TableSelector.State headerOnly = TableSelector.State.of(HEADER, List.of());
        assertThat(headerOnly.column()).isZero();
        assertThat(TableSelector.update(headerOnly, new TableSelector.Action.Confirm()).intent())
                .isEqualTo(new TableSelector.Intent.None());
    }

    @Test
    void aRowShorterThanTheHeaderHasNoValueForTheMissingCell() {
        TableSelector.State shortRow =
                TableSelector.State.of(HEADER, List.of(List.of("only")));
        assertThat(shortRow.selectedValue()).isEqualTo("only");
        TableSelector.State moved = TableSelector.update(shortRow, new TableSelector.Action.Right()).state();
        assertThat(moved.column()).isEqualTo(1);
        assertThat(moved.selectedValue()).as("a missing cell reads as empty").isEmpty();
    }

    @Test
    void theViewPadsColumnsToOneGridAndMapsItsKeys() {
        List<String> lines = new ScreenRenderer().plainLines(
                TableSelector.view(ElementId.of("table"), state(), true, Style.NONE.withInvert(),
                        Style.NONE, Style.NONE.withBold(), action -> action.getClass().getSimpleName()),
                new Viewport(3, 40));
        assertThat(lines).containsExactly("id  name", "1   alpha", "2   be");
        java.util.Optional<String> down = actionFor(Key.DOWN);
        assertThat(down).contains("Down");
        assertThat(actionFor(Key.ENTER)).contains("Confirm");
        assertThat(actionFor(Key.ESCAPE)).contains("Cancel");
        assertThat(actionFor(Key.of("x"))).isEmpty();
    }

    @Test
    void aCellBreakCannotWidenTheSharedGrid() {
        // TableSelector prepares every cell with the same one-line rule the sibling widgets apply
        // (Selector, Markup, Quote, ImageView, ProgressBar, EditorOptions): CRLF, CR and LF each
        // become one space. Flattening per character instead turned CRLF into two spaces, so a
        // Windows line ending widened the shared column and pushed every later column right.
        List<String> lineBreak = painted(List.of("id", "n"), List.of(List.of("1", "a\r\nb")));
        List<String> bareLf = painted(List.of("id", "n"), List.of(List.of("1", "a\nb")));
        List<String> bareCr = painted(List.of("id", "n"), List.of(List.of("1", "a\rb")));
        assertThat(lineBreak).as("CRLF is one break, so it paints as one space").isEqualTo(bareLf);
        assertThat(bareCr).as("CR is one break, so it paints as one space").isEqualTo(bareLf);
        assertThat(bareLf).containsExactly("id  n", "1   a b");

        // The widened cell also shifted the next column: with the one-space rule every row of the
        // grid puts the third column at the same painted column.
        List<String> threeColumns =
                painted(List.of("id", "n", "x"), List.of(List.of("1", "a\r\nb", "z")));
        List<String> control =
                painted(List.of("id", "n", "x"), List.of(List.of("1", "a\nb", "z")));
        assertThat(threeColumns).isEqualTo(control);
        assertThat(control).containsExactly("id  n    x", "1   a b  z");
    }

    private static List<String> painted(List<String> header, List<List<String>> rows) {
        return new ScreenRenderer()
                .plainLines(TableSelector.view(ElementId.of("table"),
                        TableSelector.State.of(header, rows), true, Style.NONE.withInvert(),
                        Style.NONE, Style.NONE.withBold(), action -> action), new Viewport(2, 40));
    }

    @Test
    void onlyTheSelectedCellIsInvertedAndOnlyWhileFocused() {
        var screen = new ScreenRenderer().screen(TableSelector.view(ElementId.of("table"), state(),
                true, Style.NONE.withInvert(), Style.NONE, Style.NONE.withBold(),
                action -> action.getClass().getSimpleName()), new Viewport(3, 40));
        assertThat(screen.cell(1, 0).style().invert()).as("selected cell").isTrue();
        assertThat(screen.cell(2, 0).style().invert()).as("other row").isFalse();
        assertThat(screen.cell(1, 4).style().invert()).as("other column").isFalse();
        assertThat(screen.cell(0, 0).style().bold()).as("header").isTrue();

        var unfocused = new ScreenRenderer().screen(TableSelector.view(ElementId.of("table"),
                state(), false, Style.NONE.withInvert(), Style.NONE, Style.NONE.withBold(),
                action -> action.getClass().getSimpleName()), new Viewport(3, 40));
        assertThat(unfocused.cell(1, 0).style().invert()).as("nothing is selected while unfocused")
                .isFalse();
        assertThat(unfocused.plainLine(1)).as("the rows are drawn the same").isEqualTo("1   alpha");
    }

    private static java.util.Optional<String> actionFor(Key key) {
        var view = TableSelector.view(ElementId.of("table"), state(), true, Style.NONE,
                Style.NONE, Style.NONE, action -> action.getClass().getSimpleName());
        return view instanceof io.github.dhoard.braxton.view.View.Focused<String> focused
                ? focused.input().handle(InputEvent.KeyInput.of(key, ""))
                : java.util.Optional.empty();
    }
}
