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
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.widget.ScrollView.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Selection, scrollbar geometry and pointer input of one pane.
 */
class ScrollViewSelectionTest {

    private static final Viewport VIEWPORT = new Viewport(3, 20);

    private static ScrollView.Content rows(int count) {
        List<StyledText> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(StyledText.plain("L" + index));
        }
        return ScrollView.Content.of(lines);
    }

    private static ScrollView view(ScrollView.Config config) {
        return new ScrollView(config);
    }

    private static ScrollView.Model state(int visibleRows, boolean follow, int count) {
        ScrollView pane = view(new ScrollView.Config(visibleRows, follow));
        Update<ScrollView.Model, Msg, Never> init = pane.init(new InitContext(null, null));
        return pane.update(init.model(), new Msg.ContentChanged(rows(count))).model();
    }

    private static ScrollView.Model apply(ScrollView pane, ScrollView.Model model, Msg message) {
        return pane.update(model, message).model();
    }

    @Test
    void aPressPlacesTheCaretAndADragExtendsTheRange() {
        ScrollView pane = view(ScrollView.Config.defaults());
        ScrollView.Model state = state(3, false, 10);

        ScrollView.Model placed = apply(pane, state, new Msg.Select(4));
        assertThat(placed.selection()).isEqualTo(new ScrollView.Selection(4, 4));
        assertThat(placed.selection().hasRange()).isFalse();
        assertThat(placed.topLine()).isEqualTo(2);

        ScrollView.Model extended = apply(pane, placed, new Msg.ExtendTo(6));
        assertThat(extended.selection()).isEqualTo(new ScrollView.Selection(4, 6));
        assertThat(extended.selection().lines()).isEqualTo(3);
        assertThat(extended.selection().contains(5)).isTrue();
        assertThat(extended.selection().contains(3)).isFalse();
    }

    @Test
    void shiftGesturesExtendFromTheCaret() {
        ScrollView pane = view(ScrollView.Config.defaults());
        ScrollView.Model state = state(3, false, 10);

        ScrollView.Model selected = apply(pane, state, new Msg.SelectDown(2));
        assertThat(selected.selection()).isEqualTo(new ScrollView.Selection(0, 2));

        ScrollView.Model home = apply(pane, selected, new Msg.SelectHome());
        assertThat(home.selection()).isEqualTo(new ScrollView.Selection(0, 0));

        ScrollView.Model last = apply(pane, home, new Msg.SelectEnd());
        assertThat(last.selection()).isEqualTo(new ScrollView.Selection(0, 9));

        ScrollView.Model cleared = apply(pane, last, new Msg.ClearSelection());
        assertThat(cleared.selection()).isEqualTo(ScrollView.Selection.NONE);
    }

    @Test
    void aNewSnapshotClampsTheSelection() {
        ScrollView pane = view(ScrollView.Config.defaults());
        ScrollView.Model state = apply(pane, state(3, false, 10), new Msg.SelectEnd());
        assertThat(state.selection()).isEqualTo(new ScrollView.Selection(0, 9));

        ScrollView.Model shrunk = apply(pane, state, new Msg.ContentChanged(rows(2)));
        assertThat(shrunk.selection()).isEqualTo(new ScrollView.Selection(0, 1));

        ScrollView.Model empty = apply(pane, shrunk, new Msg.ContentChanged(ScrollView.Content.empty()));
        assertThat(empty.selection()).isEqualTo(ScrollView.Selection.NONE);
    }

    @Test
    void theThumbShowsWhatTheWindowCovers() {
        ScrollView.Model top = state(5, false, 20);
        assertThat(ScrollView.thumb(top)).isEqualTo(new ScrollView.Thumb(0, 1));

        ScrollView.Model middle = apply(view(ScrollView.Config.defaults()), top, new Msg.Down(5));
        assertThat(ScrollView.thumb(middle)).isEqualTo(new ScrollView.Thumb(1, 1));

        ScrollView.Model end = apply(view(ScrollView.Config.defaults()), top, new Msg.End());
        assertThat(ScrollView.thumb(end)).isEqualTo(new ScrollView.Thumb(4, 1));

        ScrollView.Model shortContent = state(5, false, 3);
        assertThat(ScrollView.thumb(shortContent)).isEqualTo(new ScrollView.Thumb(0, 0));
        assertThat(ScrollView.thumb(new ScrollView.Model(0, false, true, 0, rows(9))))
                .isEqualTo(new ScrollView.Thumb(0, 0));
    }

    @Test
    void theScrollbarTakesOneColumnAndTheSelectionIsStyled() {
        Style selection = Style.bg(new Color(0, 0, 128));
        ScrollView pane = view(new ScrollView.Config(3, false, ScrollView.Bar.of(Style.NONE, Style.NONE), selection));
        ScrollView.Model pressed = apply(pane, state(3, false, 9), new Msg.Select(1));
        ScrollView.Model state = apply(pane, pressed, new Msg.ExtendTo(2));
        var painted = pane.view(state, new ViewContext(VIEWPORT));
        ElementScreen screen = ElementScreen.render(painted, VIEWPORT.rows(), VIEWPORT.cols());
        List<String> lines =
                ElementScreen.render(painted, VIEWPORT.rows(), VIEWPORT.cols()).lines();

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("L0");
        assertThat(lines.get(0)).hasSize(20);
        assertThat(lines.get(0)).endsWith(ScrollView.Bar.THUMB);
        assertThat(lines.get(1)).endsWith(ScrollView.Bar.TRACK);
        assertThat(screen.canvas().style(1, 0).background()).isEqualTo(new Color(0, 0, 128));
        assertThat(screen.canvas().style(2, 0).background()).isEqualTo(new Color(0, 0, 128));
        assertThat(screen.canvas().style(0, 0).background()).isNotEqualTo(new Color(0, 0, 128));
    }

    @Test
    void aPointerPressSelectsTheRowUnderIt() {
        ScrollView pane = view(ScrollView.Config.defaults());
        ScrollView.Model state = state(3, false, 10);

        assertThat(press(pane, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 2, 0)))
                .contains(new Msg.Select(2));
        assertThat(press(pane, state, PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, 1, 0)))
                .contains(new Msg.ExtendTo(1));
        assertThat(press(pane, state, PointerEvent.wheel(PointerButton.WHEEL_DOWN, Modifiers.NONE, 0, 0)))
                .contains(new Msg.Down(3));
        assertThat(press(pane, state, PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, 2, 0)))
                .isEmpty();
    }

    /**
     * The message a report produces, offered to the pane the way the runtime offers it.
     */
    private static Optional<Msg> press(ScrollView pane, ScrollView.Model state, PointerEvent report) {
        List<Msg> produced = new java.util.ArrayList<>(1);
        pane.onPointer(report, new Rect(0, 0, 3, 20), state, produced::add);
        return produced.stream().findFirst();
    }
}
