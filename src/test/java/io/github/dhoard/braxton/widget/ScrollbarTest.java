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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scrollbar: its geometry, its keys, its pointer gestures and the cells it paints.
 */
class ScrollbarTest {

    private static final Rect BAR = new Rect(0, 0, 10, 1);

    private final Scrollbar bar = new Scrollbar();

    @Test
    void theThumbCoversTheWindowsShareOfTheContent() {
        // Ten rows of a hundred: one row of thumb, at the top, the middle and the bottom.
        assertThat(Scrollbar.thumb(Scrollbar.Model.of(0, 10, 100))).isEqualTo(new Scrollbar.Thumb(0, 1));
        assertThat(Scrollbar.thumb(Scrollbar.Model.of(45, 10, 100))).isEqualTo(new Scrollbar.Thumb(5, 1));
        assertThat(Scrollbar.thumb(Scrollbar.Model.of(90, 10, 100))).isEqualTo(new Scrollbar.Thumb(9, 1));

        // Half the content: half the bar, and the thumb travels the other half.
        assertThat(Scrollbar.thumb(Scrollbar.Model.of(0, 50, 100))).isEqualTo(new Scrollbar.Thumb(0, 25));
        assertThat(Scrollbar.thumb(Scrollbar.Model.of(50, 50, 100))).isEqualTo(new Scrollbar.Thumb(25, 25));
    }

    @Test
    void thePositionIsClampedIntoTheContent() {
        assertThat(Scrollbar.Model.of(-5, 10, 100).first()).isZero();
        assertThat(Scrollbar.Model.of(500, 10, 100).first()).isEqualTo(90);
        // A window that shrank, or content that was cleared, still names a position the bar paints.
        assertThat(Scrollbar.Model.of(80, 50, 90).first()).isEqualTo(40);
        assertThat(Scrollbar.Model.of(-3, -1, -9)).isEqualTo(new Scrollbar.Model(0, 0, 0, false));
        assertThat(Scrollbar.Model.of(5, 10, 100).withContent(6).first()).isZero();
        assertThat(Scrollbar.Model.of(5, 10, 100).withWindow(20)).isEqualTo(Scrollbar.Model.of(5, 20, 100));
        assertThat(Scrollbar.Model.of(5, 10, 100).withFirst(3).first()).isEqualTo(3);
    }

    @Test
    void theTrackAndTheThumbArePaintedInTheThemesStyles() {
        Element<Scrollbar.Msg> root = bar.element(Scrollbar.Model.of(0, 5, 50));

        ElementScreen screen = ElementScreen.render(root, 5, 1, Themes.DARK);

        assertThat(screen.lines())
                .containsExactly(Scrollbar.Appearance.defaults().thumb(), "\u2502", "\u2502", "\u2502", "\u2502");
        assertThat(screen.styles(0).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(screen.styles(1).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.BORDER));
    }

    @Test
    void aFocusedBarOverlaysTheFocusStyleOnTheThumb() {
        Style thumb = Themes.LIGHT.style(ThemeToken.ACCENT);
        Style focus = Themes.LIGHT.style(ThemeToken.FOCUS);
        Element<Scrollbar.Msg> root = bar.element(Scrollbar.Model.of(0, 5, 50).withFocus(true));

        ElementScreen screen = ElementScreen.render(root, 5, 1, Themes.LIGHT);

        assertThat(screen.styles(0).get(0)).isEqualTo(thumb.merge(focus));
        assertThat(screen.styles(1).get(0))
                .as("the track is not the focused part")
                .isEqualTo(Themes.LIGHT.style(ThemeToken.BORDER));
    }

    @Test
    void theAppearanceChoosesTheGlyphsAndDefaultsTheEmptyOnes() {
        Scrollbar bar = new Scrollbar(
                new Scrollbar.Appearance("", "").withThumb("\u2590").withTrack("\u2506"));

        assertThat(bar.appearance().thumb()).isEqualTo("\u2590");
        assertThat(ElementScreen.render(bar.element(Scrollbar.Model.of(0, 3, 30)), 3, 1)
                        .lines())
                .containsExactly("\u2590", "\u2506", "\u2506");
        assertThat(new Scrollbar.Appearance(null, null)).isEqualTo(Scrollbar.Appearance.defaults());
        assertThat(Scrollbar.Appearance.defaults().withThumb("").thumb())
                .isEqualTo(Scrollbar.Appearance.defaults().thumb());
    }

    @Test
    void aBarPaintedWithoutAThemeUsesTheDefaultOne() {
        Element<Scrollbar.Msg> root = bar.element(Scrollbar.Model.of(0, 2, 20));
        Screen screen = new Screen(2, 1);

        ElementTree.paint(ElementTree.layout(root, new Rect(0, 0, 2, 1)), screen, null);

        assertThat(screen.plainLines())
                .containsExactly(Scrollbar.Appearance.defaults().thumb(), "\u2502");
        assertThat(screen.style(0, 0)).isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
    }

    @Test
    void keysStepARowAPageAndToTheEnds() {
        Scrollbar.Model model = Scrollbar.Model.of(10, 5, 50);

        assertThat(keys(bar, model, Key.UP)).containsExactly(new Scrollbar.Msg.Moved(9));
        assertThat(keys(bar, model, Key.DOWN)).containsExactly(new Scrollbar.Msg.Moved(11));
        assertThat(keys(bar, model, Key.PAGE_UP)).containsExactly(new Scrollbar.Msg.Moved(5));
        assertThat(keys(bar, model, Key.PAGE_DOWN)).containsExactly(new Scrollbar.Msg.Moved(15));
        assertThat(keys(bar, model, Key.HOME)).containsExactly(new Scrollbar.Msg.Moved(0));
        assertThat(keys(bar, model, Key.END)).containsExactly(new Scrollbar.Msg.Moved(45));

        // A clamp is still the bar's answer, so the application's own binding for the key never
        // runs underneath a gesture the bar owns.
        assertThat(keys(bar, Scrollbar.Model.of(0, 5, 50), Key.UP)).containsExactly(new Scrollbar.Msg.Moved(0));
        assertThat(keys(bar, Scrollbar.Model.of(45, 5, 50), Key.DOWN)).containsExactly(new Scrollbar.Msg.Moved(45));
    }

    @Test
    void aKeyTheBarDoesNotOwnIsDeclined() {
        Scrollbar.Model model = Scrollbar.Model.of(10, 5, 50);

        assertThat(keys(bar, model, Key.TAB)).isEmpty();
        assertThat(keys(bar, model, Key.LEFT)).isEmpty();
        assertThat(keys(bar, model, Key.of("x"))).isEmpty();
    }

    @Test
    void aPressAndADragPutTheThumbsCentreOnThePointerRow() {
        Scrollbar.Model model = Scrollbar.Model.of(0, 10, 100);

        assertThat(pointer(bar, model, press(5))).containsExactly(new Scrollbar.Msg.Moved(50));
        assertThat(pointer(bar, model, drag(9))).containsExactly(new Scrollbar.Msg.Moved(90));
        assertThat(pointer(bar, model, drag(0))).isEmpty();
        assertThat(pointer(bar, model, motion(4))).isEmpty();
        assertThat(pointer(bar, model, PointerEvent.press(PointerButton.RIGHT, Modifiers.NONE, 4, 0)))
                .isEmpty();

        // A drag above or below the bar clamps to an end without repeating a position it already has.
        assertThat(pointer(bar, Scrollbar.Model.of(50, 10, 100), drag(-4))).containsExactly(new Scrollbar.Msg.Moved(0));
        assertThat(pointer(bar, Scrollbar.Model.of(90, 10, 100), drag(24))).isEmpty();
    }

    @Test
    void theWheelStepsThreeRows() {
        Scrollbar.Model model = Scrollbar.Model.of(0, 10, 100);

        assertThat(pointer(bar, model, wheel(PointerButton.WHEEL_DOWN)))
                .containsExactly(new Scrollbar.Msg.Moved(Scrollbar.WHEEL_ROWS));
        assertThat(pointer(bar, Scrollbar.Model.of(50, 10, 100), wheel(PointerButton.WHEEL_UP)))
                .containsExactly(new Scrollbar.Msg.Moved(50 - Scrollbar.WHEEL_ROWS));
        assertThat(pointer(bar, Scrollbar.Model.of(90, 10, 100), wheel(PointerButton.WHEEL_DOWN)))
                .containsExactly(new Scrollbar.Msg.Moved(90));
        assertThat(pointer(bar, model, wheel(PointerButton.WHEEL_LEFT))).isEmpty();
        // A one-row window over two rows has no room to move its thumb, so a press names the top.
        assertThat(pointer(bar, Scrollbar.Model.of(0, 1, 2), press(0))).isEmpty();
    }

    @Test
    void contentThatFitsIsNotScrollable() {
        Scrollbar.Model fits = Scrollbar.Model.of(0, 10, 4);

        assertThat(Scrollbar.thumb(fits)).isEqualTo(new Scrollbar.Thumb(0, 10));
        assertThat(ElementScreen.render(bar.element(fits), 10, 1).lines())
                .containsOnly(Scrollbar.Appearance.defaults().thumb());
        assertThat(bar.hit(BAR, 3, 0, fits)).isFalse();
        assertThat(ElementScreen.render(bar.element(Scrollbar.Model.of(0, 0, 4)), 2, 1)
                        .lines())
                .as("a bar with no window paints nothing")
                .containsExactly("", "");
        assertThat(keys(bar, fits, Key.DOWN)).isEmpty();
        assertThat(pointer(bar, fits, press(3))).isEmpty();
        assertThat(pointer(bar, fits, wheel(PointerButton.WHEEL_DOWN))).isEmpty();
    }

    @Test
    void aNarrowBarPaintsWhatFitsAndNothingElse() {
        Scrollbar.Model model = Scrollbar.Model.of(0, 10, 100);

        assertThat(bar.measure(new Size(10, 3), model)).isEqualTo(new Size(10, 1));
        assertThat(bar.measure(new Size(4, 0), model)).isEqualTo(new Size(4, 0));
        assertThat(ElementScreen.render(bar.element(model), 0, 0).lines()).isEmpty();
        // A bar whose rectangle is taller than the window it describes paints no track for the
        // rows the window does not have.
        assertThat(ElementScreen.render(bar.element(Scrollbar.Model.of(0, 3, 30)), 5, 1)
                        .lines())
                .containsExactly(Scrollbar.Appearance.defaults().thumb(), "\u2502", "\u2502", "", "");
    }

    @Test
    void theBarIsAFocusStopThatAnswersForItsOwnMessages() {
        Element<Scrollbar.Msg> focused = Elements.row(
                Elements.scrollbar(bar, Scrollbar.Model.of(0, 10, 100).withFocus(true)));
        Element<Scrollbar.Msg> unfocused = Elements.row(Elements.scrollbar(bar, Scrollbar.Model.of(0, 10, 100)));

        assertThat(bar.canFocus(Scrollbar.Model.of(0, 10, 100))).isTrue();
        assertThat(bar.canFocus(Scrollbar.Model.of(0, 10, 4)))
                .as("a bar with nothing to scroll is not a focus stop")
                .isFalse();
        assertThat(bar.focused(Scrollbar.Model.of(0, 10, 100).withFocus(true))).isTrue();
        assertThat(ElementScreen.render(focused, 10, 3).focusables()).containsExactly(BAR);

        List<Scrollbar.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(focused, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new Scrollbar.Msg.Moved(1));

        produced.clear();
        assertThat(InteractionRouter.key(unfocused, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .as("an unfocused bar is not on the key path")
                .isFalse();
        assertThat(produced).isEmpty();
    }

    @Test
    void thePaneAndTheBarAgreeAboutAPosition() {
        ScrollView.Model pane = new ScrollView.Model(
                45,
                false,
                false,
                10,
                ScrollView.Content.of(java.util.Collections.nCopies(100, StyledText.of("row", Style.NONE))));
        Scrollbar.Thumb geometry = Scrollbar.thumb(Scrollbar.Model.of(45, 10, 100));

        assertThat(new ScrollView.Thumb(geometry.start(), geometry.size())).isEqualTo(ScrollView.thumb(pane));
    }

    @Test
    void eachBarKeepsTheDragItStarted() {
        Scrollbar left = new Scrollbar();
        Scrollbar right = new Scrollbar();
        Element<Scrollbar.Msg> root = Elements.row(
                Elements.fixed(1, Elements.scrollbar(left, Scrollbar.Model.of(0, 4, 100))),
                Elements.fixed(1, Elements.scrollbar(right, Scrollbar.Model.of(0, 8, 100))));
        ElementTree.Placement<Scrollbar.Msg> placement = ElementTree.layout(root, new Rect(0, 0, 8, 2));
        List<Scrollbar.Msg> produced = new ArrayList<>(2);

        InteractionRouter.Interaction state = InteractionRouter.pointer(
                placement,
                InteractionRouter.Interaction.NONE,
                PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 7, 1),
                produced::add);
        state = InteractionRouter.pointer(
                placement, state, PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, 3, 1), produced::add);

        // The gesture stays with the bar it started on: the motion is measured in that bar's
        // eight-row window, not in the four-row one beside it.
        assertThat(state.pressed()).isSameAs(right);
        assertThat(produced).containsExactly(new Scrollbar.Msg.Moved(92), new Scrollbar.Msg.Moved(39));
    }

    private static List<Scrollbar.Msg> keys(Scrollbar bar, Scrollbar.Model model, Key key) {
        List<Scrollbar.Msg> produced = new ArrayList<>(1);
        bar.onKey(InputEvent.KeyInput.of(key), model, produced::add);
        return produced;
    }

    private static List<Scrollbar.Msg> pointer(Scrollbar bar, Scrollbar.Model model, PointerEvent report) {
        List<Scrollbar.Msg> produced = new ArrayList<>(1);
        bar.onPointer(report, BAR, model, produced::add);
        return produced;
    }

    private static PointerEvent press(int row) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, 0);
    }

    private static PointerEvent drag(int row) {
        return PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, row, 0);
    }

    private static PointerEvent motion(int row) {
        return PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, row, 0);
    }

    private static PointerEvent wheel(PointerButton direction) {
        return PointerEvent.wheel(direction, Modifiers.NONE, 0, 0);
    }
}
