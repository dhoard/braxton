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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.InitContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pointer input of the two selector widgets: a press selects, a second press confirms.
 */
class SelectorPointerTest {

    /**
     * The message a report produces, offered to a selector the way the runtime offers it.
     */
    private static Optional<Selector.Msg> press(Selector selector, Selector.Model state, PointerEvent report) {
        List<Selector.Msg> produced = new java.util.ArrayList<>(1);
        selector.onPointer(report, new Rect(0, 0, 10, 40), state, produced::add);
        return produced.stream().findFirst();
    }

    /**
     * The message a report produces, offered to a table selector the way the runtime offers it.
     */
    private static Optional<TableSelector.Msg> press(
            TableSelector selector, TableSelector.Model state, PointerEvent report) {
        List<TableSelector.Msg> produced = new java.util.ArrayList<>(1);
        selector.onPointer(report, new Rect(0, 0, 10, 40), state, produced::add);
        return produced.stream().findFirst();
    }

    @Test
    void aPressSelectsTheOptionAndASecondPressConfirmsIt() {
        Selector selector = new Selector(new Selector.Config(
                List.of("alpha", "bravo", "charlie"),
                io.github.dhoard.braxton.style.Style.NONE,
                io.github.dhoard.braxton.style.Style.NONE));
        Selector.Model state = selector.init(new InitContext(null, null)).model();

        assertThat(press(selector, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 1, 0)))
                .contains(new Selector.Msg.Select(1));

        Selector.Model second =
                selector.update(state, new Selector.Msg.Select(1)).model();
        assertThat(press(
                        selector,
                        second,
                        PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 1, 0)
                                .withClickCount(2)))
                .contains(new Selector.Msg.Confirm());

        assertThat(press(selector, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 9, 0)))
                .isEmpty();
    }

    @Test
    void aPressSelectsTheTableCellUnderThePointer() {
        TableSelector selector = new TableSelector(TableSelector.Config.defaults());
        TableSelector.Model state = TableSelector.Model.of(
                List.of("id", "name"), List.of(List.of("A-1", "alpha"), List.of("B-2", "bravo")));
        // The header is row 0; "alpha" starts at the second column's first cell, after "id" and its
        // padding: 2 + 2 = 4 cells.
        assertThat(press(selector, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 1, 5)))
                .contains(new TableSelector.Msg.Select(0, 1));

        assertThat(press(selector, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 0)))
                .as("the header row")
                .isEmpty();
    }
}
