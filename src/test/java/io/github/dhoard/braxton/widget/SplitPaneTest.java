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
 * The split pane: where its panes sit, how its divider moves, and the rule it paints.
 */
class SplitPaneTest {

    private static final SplitPane.Direction ROW = SplitPane.Direction.ROW;

    private static final SplitPane.Direction COLUMN = SplitPane.Direction.COLUMN;

    private final SplitPane divider = new SplitPane();

    @Test
    void thePanesShareTheRegionAndTheDividerTakesOneCell() {
        SplitPane.Model model = SplitPane.Model.of(ROW, 4, 11);

        ElementScreen screen = ElementScreen.render(
                Elements.splitPane(divider, model, Elements.text("left"), Elements.text("right"), moved -> "moved"),
                1,
                11);

        assertThat(screen.line(0))
                .isEqualTo("left" + SplitPane.Appearance.defaults().vertical() + "right");
        // The panes and their one-cell divider are the whole region.
        assertThat(model.second()).isEqualTo(6);
    }

    @Test
    void aStackedSplitPaintsAHorizontalRule() {
        SplitPane.Model model = new SplitPane.Model(COLUMN, 1, 3, 0, false);

        ElementScreen screen = ElementScreen.render(
                Elements.splitPane(divider, model, Elements.text("top"), Elements.text("bottom"), moved -> "moved"),
                3,
                5);

        assertThat(screen.lines())
                .containsExactly(
                        "top", SplitPane.Appearance.defaults().horizontal().repeat(5), "botto");
    }

    @Test
    void theModelClampsThePositionIntoTheRegion() {
        assertThat(SplitPane.Model.of(ROW, 100, 10).first()).isEqualTo(9);
        assertThat(new SplitPane.Model(ROW, -4, 10, 2, false).first()).isEqualTo(2);
        assertThat(new SplitPane.Model(null, 3, 0, -5, false))
                .as("a region of no cells is one cell and no minimum")
                .isEqualTo(new SplitPane.Model(ROW, 0, 1, 0, false));
        // A region too small for both minimums gives the first pane what there is.
        assertThat(new SplitPane.Model(ROW, 0, 3, 4, false).first()).isEqualTo(1);
        assertThat(new SplitPane.Model(ROW, 0, 10, 2, false).last()).isEqualTo(7);
        assertThat(SplitPane.Model.of(ROW, 4, 11).withTotal(5).first()).isEqualTo(4);
        assertThat(SplitPane.Model.of(ROW, 4, 11).withFirst(2).first()).isEqualTo(2);
        assertThat(SplitPane.Model.of(ROW, 4, 11).withMinimum(3).first()).isEqualTo(4);
        assertThat(SplitPane.Model.of(ROW, 4, 11).withFocus(true).focused()).isTrue();
    }

    @Test
    void keysNudgeAlongTheSplitsOwnAxis() {
        SplitPane divider = this.divider;
        SplitPane.Model row = new SplitPane.Model(ROW, 4, 11, 1, false);

        assertThat(keys(divider, row, Key.RIGHT)).containsExactly(new SplitPane.Msg.Moved(5));
        assertThat(keys(divider, row, Key.LEFT)).containsExactly(new SplitPane.Msg.Moved(3));
        assertThat(keys(divider, row, Key.HOME)).containsExactly(new SplitPane.Msg.Moved(1));
        assertThat(keys(divider, row, Key.END)).containsExactly(new SplitPane.Msg.Moved(9));
        assertThat(keys(divider, row, Key.UP)).isEmpty();
        assertThat(keys(divider, row, Key.DOWN)).isEmpty();
        assertThat(keys(divider, row, Key.of("x"))).isEmpty();

        // A clamp is still the divider's answer, so a pane-switching binding underneath never runs.
        assertThat(keys(divider, row.withFirst(9), Key.RIGHT)).containsExactly(new SplitPane.Msg.Moved(9));

        SplitPane.Model column = new SplitPane.Model(COLUMN, 2, 9, 1, false);
        assertThat(keys(divider, column, Key.DOWN)).containsExactly(new SplitPane.Msg.Moved(3));
        assertThat(keys(divider, column, Key.UP)).containsExactly(new SplitPane.Msg.Moved(1));
        assertThat(keys(divider, column, Key.RIGHT)).isEmpty();
    }

    @Test
    void aPressAndADragPutTheDividerUnderThePointer() {
        SplitPane divider = this.divider;
        SplitPane.Model model = new SplitPane.Model(ROW, 4, 11, 1, false);
        Rect bounds = new Rect(0, 4, 1, 1);

        assertThat(pointer(divider, model, bounds, press(0, 6))).containsExactly(new SplitPane.Msg.Moved(6));
        assertThat(pointer(divider, model, bounds, drag(0, 2))).containsExactly(new SplitPane.Msg.Moved(2));
        // A drag past an end clamps to the minimum and the largest the model allows.
        assertThat(pointer(divider, model, bounds, drag(0, -8))).containsExactly(new SplitPane.Msg.Moved(1));
        assertThat(pointer(divider, model, bounds, drag(0, 40))).containsExactly(new SplitPane.Msg.Moved(9));
        assertThat(pointer(divider, model, bounds, PointerEvent.press(PointerButton.RIGHT, Modifiers.NONE, 0, 6)))
                .isEmpty();
        assertThat(pointer(divider, model, bounds, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 0, 6)))
                .isEmpty();
        assertThat(pointer(divider, model.withFirst(6), new Rect(0, 6, 1, 1), drag(0, 6)))
                .as("a gesture that names the position it already has is not a move")
                .isEmpty();

        // A stacked split reads the row instead of the column.
        SplitPane.Model column = new SplitPane.Model(COLUMN, 1, 5, 0, false);
        Rect stacked = new Rect(1, 0, 1, 8);
        assertThat(pointer(divider, column, stacked, press(3, 0))).containsExactly(new SplitPane.Msg.Moved(3));
    }

    @Test
    void theDividerIsAHitTargetOnlyWhileItCanMove() {
        SplitPane divider = this.divider;
        SplitPane.Model room = SplitPane.Model.of(ROW, 4, 11);
        SplitPane.Model full = new SplitPane.Model(ROW, 1, 3, 1, false);

        assertThat(divider.canFocus(room)).isTrue();
        assertThat(divider.canFocus(full))
                .as("a split with no room to move is not a focus stop")
                .isFalse();
        assertThat(divider.hit(new Rect(0, 4, 1, 1), 0, 4, room)).isTrue();
        assertThat(divider.hit(new Rect(0, 4, 1, 1), 0, 5, room)).isFalse();
        assertThat(full.last()).isEqualTo(full.minimum());
        assertThat(divider.hit(new Rect(0, 1, 1, 1), 0, 1, full)).isFalse();
        assertThat(keys(divider, full, Key.RIGHT)).isEmpty();
        assertThat(pointer(divider, full, new Rect(0, 1, 1, 1), press(0, 2))).isEmpty();
    }

    @Test
    void theRuleIsPaintedInTheThemesStylesAndTakesFocus() {
        SplitPane divider = this.divider;
        SplitPane.Model focused = new SplitPane.Model(COLUMN, 1, 3, 0, true);

        ElementScreen plain = ElementScreen.render(divider.element(SplitPane.Model.of(ROW, 1, 3)), 3, 1, Themes.DARK);
        ElementScreen focusedScreen = ElementScreen.render(divider.element(focused), 3, 1, Themes.DARK);

        assertThat(plain.lines())
                .containsExactly(
                        SplitPane.Appearance.defaults().vertical(),
                        SplitPane.Appearance.defaults().vertical(),
                        SplitPane.Appearance.defaults().vertical());
        assertThat(plain.styles(0).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.BORDER));
        assertThat(focusedScreen.line(0))
                .isEqualTo(SplitPane.Appearance.defaults().horizontal());
        assertThat(focusedScreen.styles(0).get(0))
                .isEqualTo(Themes.DARK.style(ThemeToken.BORDER).merge(Themes.DARK.style(ThemeToken.FOCUS)));
    }

    @Test
    void theAppearanceChoosesTheRuleGlyphs() {
        SplitPane divider = new SplitPane(new SplitPane.Appearance("", "").withVertical("\u2551"));

        assertThat(divider.appearance().vertical()).isEqualTo("\u2551");
        assertThat(ElementScreen.render(divider.element(SplitPane.Model.of(ROW, 1, 3)), 2, 1)
                        .lines())
                .containsExactly("\u2551", "\u2551");
        assertThat(new SplitPane.Appearance(null, null)).isEqualTo(SplitPane.Appearance.defaults());
        assertThat(SplitPane.Appearance.defaults().withHorizontal("").horizontal())
                .isEqualTo(SplitPane.Appearance.defaults().horizontal());
        assertThat(divider.appearance().withHorizontal("\u2501").horizontal()).isEqualTo("\u2501");
    }

    @Test
    void aRulePaintedWithoutAThemeUsesTheDefaultOne() {
        Element<SplitPane.Msg> root = divider.element(SplitPane.Model.of(COLUMN, 0, 2));
        Screen screen = new Screen(2, 3);

        ElementTree.paint(ElementTree.layout(root, new Rect(0, 0, 2, 3)), screen, null);

        assertThat(screen.plainLines())
                .containsExactly(SplitPane.Appearance.defaults().horizontal().repeat(3), "");
        assertThat(screen.style(0, 0)).isEqualTo(Themes.DARK.style(ThemeToken.BORDER));
    }

    @Test
    void aRegionSmallerThanTheSplitClipsWithoutFailing() {
        SplitPane.Model model = new SplitPane.Model(ROW, 5, 6, 0, false);
        Element<String> split =
                Elements.splitPane(divider, model, Elements.text("left"), Elements.text("right"), moved -> "moved");

        // The first pane takes the columns it asked for, the divider and the second pane clip away.
        assertThat(ElementScreen.render(split, 1, 3).line(0)).isEqualTo("lef");
        assertThat(ElementScreen.render(split, 1, 1).line(0)).isEqualTo("l");
        assertThat(ElementScreen.render(split, 0, 0).lines()).isEmpty();

        // The sizes follow the axis the split runs along.
        SplitPane divider = this.divider;
        assertThat(divider.measure(new Size(6, 9), model)).isEqualTo(new Size(6, 1));
        assertThat(divider.measure(new Size(6, 9), new SplitPane.Model(COLUMN, 0, 6, 0, false)))
                .isEqualTo(new Size(1, 9));
    }

    @Test
    void theComposedTreeCarriesTheDividersMessages() {
        Element<SplitPane.Msg> focused = Elements.splitPane(
                divider,
                new SplitPane.Model(ROW, 4, 11, 1, true),
                Elements.text("left"),
                Elements.text("right"),
                SplitPaneTest::echo);
        Element<SplitPane.Msg> unfocused = Elements.splitPane(
                divider,
                SplitPane.Model.of(ROW, 4, 11),
                Elements.text("left"),
                Elements.text("right"),
                SplitPaneTest::echo);

        assertThat(ElementScreen.render(focused, 1, 11).focusables()).containsExactly(new Rect(0, 4, 1, 1));

        List<SplitPane.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(focused, InputEvent.KeyInput.of(Key.RIGHT), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new SplitPane.Msg.Moved(5));

        produced.clear();
        assertThat(InteractionRouter.key(unfocused, InputEvent.KeyInput.of(Key.RIGHT), produced::add))
                .as("an unfocused divider is not on the key path")
                .isFalse();
        assertThat(produced).isEmpty();
    }

    /**
     * The divider's messages are this test's message type, so the mapper only names the type.
     */
    private static SplitPane.Msg echo(SplitPane.Msg message) {
        return message;
    }

    private static List<SplitPane.Msg> keys(SplitPane divider, SplitPane.Model model, Key key) {
        List<SplitPane.Msg> produced = new ArrayList<>(1);
        divider.onKey(InputEvent.KeyInput.of(key), model, produced::add);
        return produced;
    }

    private static List<SplitPane.Msg> pointer(
            SplitPane divider, SplitPane.Model model, Rect bounds, PointerEvent report) {
        List<SplitPane.Msg> produced = new ArrayList<>(1);
        divider.onPointer(report, bounds, model, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }

    private static PointerEvent drag(int row, int col) {
        return PointerEvent.motion(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
