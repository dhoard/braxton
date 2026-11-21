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
import io.github.dhoard.braxton.style.StyledText;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The bar's pointer input: a tab cell activates, a marker scrolls and the wheel steps.
 */
class TabsPointerTest {

    private static Tabs bar() {
        return new Tabs(Tabs.Config.defaults());
    }

    private static Tabs.Model model(int active) {
        List<Tabs.Tab> tabs =
                List.of(Tabs.Tab.of("a", "alpha"), Tabs.Tab.of("b", "bravo"), Tabs.Tab.of("c", "charlie"));
        return new Tabs.Model(tabs, tabs.get(active).id(), true);
    }

    @Test
    void aPressOnATabCellActivatesThatTab() {
        Tabs tabs = bar();
        Tabs.Model state = model(0);

        int second = Tabs.render(state, null, 40, StyledText.empty())
                .plainText()
                .split("\n", -1)[1]
                .indexOf("bravo");
        assertThat(second).isPositive();

        assertThat(press(tabs, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, second), 40))
                .contains(new Tabs.Msg.Activate("b"));
    }

    @Test
    void aPressOnTheOverflowMarkerScrollsTheBar() {
        Tabs tabs = bar();
        Tabs.Model state = new Tabs.Model(
                List.of(
                        Tabs.Tab.of("a", "alpha"),
                        Tabs.Tab.of("b", "bravo"),
                        Tabs.Tab.of("c", "charlie"),
                        Tabs.Tab.of("d", "delta")),
                "a",
                true);

        // A narrow bar windows its tabs and paints the trailing marker in the last cell.
        String labels =
                Tabs.render(state, null, 16, StyledText.empty()).plainText().split("\n", -1)[1];
        assertThat(labels).contains(Tabs.OVERFLOW_RIGHT);
        int marker = labels.indexOf(Tabs.OVERFLOW_RIGHT);

        assertThat(press(tabs, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, marker), 16))
                .contains(new Tabs.Msg.Next());
    }

    @Test
    void theWheelStepsTheBar() {
        Tabs tabs = bar();

        Tabs.Model state = model(1);
        assertThat(press(tabs, state, PointerEvent.wheel(PointerButton.WHEEL_DOWN, Modifiers.NONE, 0, 0), 40))
                .contains(new Tabs.Msg.Next());
        assertThat(press(tabs, state, PointerEvent.wheel(PointerButton.WHEEL_UP, Modifiers.NONE, 0, 0), 40))
                .contains(new Tabs.Msg.Previous());
    }

    /**
     * The message a report produces, offered to the bar the way the runtime offers it.
     */
    private static Optional<Tabs.Msg> press(Tabs tabs, Tabs.Model state, PointerEvent report, int cols) {
        List<Tabs.Msg> produced = new java.util.ArrayList<>(1);
        tabs.onPointer(report, new Rect(0, 0, 6, cols), state, produced::add);
        return produced.stream().findFirst();
    }
}
