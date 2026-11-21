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

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.Pilot;
import io.github.dhoard.braxton.widget.ScrollView.Msg;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pane driven through the planted-user driver: keys, snapshots and the painted scrollbar.
 */
class ScrollViewPilotTest {

    private static ScrollView.Content rows(int count) {
        List<StyledText> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(StyledText.plain("L" + index));
        }
        return ScrollView.Content.of(lines);
    }

    private static Pilot<ScrollView.Model, Msg> pilot(int rows, int columns) {
        ScrollView pane = new ScrollView(new ScrollView.Config(
                3,
                false,
                ScrollView.Bar.of(io.github.dhoard.braxton.style.Style.NONE, io.github.dhoard.braxton.style.Style.NONE),
                io.github.dhoard.braxton.style.Style.NONE));
        return Pilot.of(
                pane,
                event -> ScrollView.actionFor(event).orElse(new Msg.ClearSelection()),
                new Viewport(rows, columns));
    }

    @Test
    void aShiftKeyExtendsTheSelectionAndTheFrameShowsIt() {
        Pilot<ScrollView.Model, Msg> pilot = pilot(3, 10)
                .dispatch(new Msg.ContentChanged(rows(9)))
                .press("shift+down")
                .press("shift+down");

        assertThat(pilot.model().selection()).isEqualTo(new ScrollView.Selection(0, 2));
        assertThat(pilot.snapshot().lines())
                .containsExactly(
                        "L0" + " ".repeat(7) + ScrollView.Bar.THUMB,
                        "L1" + " ".repeat(7) + ScrollView.Bar.TRACK,
                        "L2" + " ".repeat(7) + ScrollView.Bar.TRACK);
        assertThat(pilot.trace()).hasSize(4);
    }

    @Test
    void aPressAndDragSelectTheRowsUnderThePointer() {
        Pilot<ScrollView.Model, Msg> pilot = pilot(3, 10).dispatch(new Msg.ContentChanged(rows(9)));
        ScrollView pane = new ScrollView(ScrollView.Config.defaults());
        ScrollView.Model placed = pane.update(pilot.model(), new Msg.Select(1)).model();
        ScrollView.Model extended = pane.update(placed, new Msg.ExtendTo(2)).model();

        assertThat(extended.selection()).isEqualTo(new ScrollView.Selection(1, 2));
        assertThat(ScrollView.visibleLines(extended)).hasSize(3);
    }
}
