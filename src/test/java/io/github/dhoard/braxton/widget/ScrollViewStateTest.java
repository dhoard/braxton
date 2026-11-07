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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.widget.ScrollView.Msg;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Offset, paging, follow-end and geometry transitions of one pane. Every expected value is a
 * whole model, so a rule cannot be satisfied by a field the assertion forgot to name.
 */
class ScrollViewStateTest {

    /** Content of {@code count} rows named {@code prefix + index}. */
    private static ScrollView.Content rows(String prefix, int count) {
        List<StyledText> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(StyledText.plain(prefix + index));
        }
        return ScrollView.Content.of(lines);
    }

    /** Plain rows {@code L0..L(count-1)}. */
    private static ScrollView.Content rows(int count) {
        return rows("L", count);
    }

    private static ComponentDriver<ScrollView.Model, Msg, Never> pane(int visibleRows,
            boolean follow, int count) {
        var driver =
                ComponentDriver.initialize(
                        new ScrollView(new ScrollView.Config(visibleRows, follow)),
                        new InitContext(null, null));
        driver.send(new Msg.ContentChanged(rows(count)));
        return driver;
    }

    private static ComponentDriver<ScrollView.Model, Msg, Never> pane(int visibleRows, int count) {
        return pane(visibleRows, false, count);
    }

    /** The row names the window covers. */
    private static List<String> window(ScrollView.Model state) {
        List<String> names = new ArrayList<>();
        for (StyledText line : ScrollView.visibleLines(state)) {
            names.add(line.plainText());
        }
        return names;
    }

    @Test
    void emptyContentCannotScrollAndEveryGestureIsANoOp() {
        var driver = pane(5, false, 0);

        assertThat(driver.model().topLine()).isZero();
        assertThat(driver.model().lineCount()).isZero();
        assertThat(ScrollView.visibleLines(driver.model())).isEmpty();
        for (Msg message : List.of(new Msg.Up(1), new Msg.Down(3), new Msg.PageUp(),
                new Msg.PageDown(), new Msg.Home(), new Msg.End())) {
            ScrollView.Model before = driver.model();
            assertThat(driver.send(message).model())
                    .as("an empty pane has no state left to change: %s", message)
                    .isSameAs(before);
        }
    }

    @Test
    void contentThatFitsTheWindowCannotScroll() {
        for (int count : new int[] {1, 3, 5}) {
            var driver = pane(5, false, count);
            assertThat(driver.model().topLine()).isZero();
            assertThat(window(driver.model())).hasSize(count);
            for (Msg message : List.of(new Msg.Down(1), new Msg.Down(100), new Msg.PageDown(),
                    new Msg.End(), new Msg.Up(1), new Msg.Home())) {
                ScrollView.Model before = driver.model();
                assertThat(driver.send(message).model())
                        .as("%d rows in a five-row window, %s", count, message)
                        .isSameAs(before);
            }
        }
    }

    @Test
    void upAndDownMoveByRowsAndStopAtTheContentBounds() {
        var driver = pane(3, 10);
        assertThat(driver.model().topLine()).isZero();

        assertThat(driver.send(new Msg.Down(1)).model().topLine()).isEqualTo(1);
        assertThat(driver.send(new Msg.Down(100)).model().topLine()).isEqualTo(7);
        ScrollView.Model atBottom = driver.model();
        assertThat(driver.send(new Msg.Down(1)).model()).isSameAs(atBottom);

        assertThat(driver.send(new Msg.Up(2)).model().topLine()).isEqualTo(5);
        assertThat(driver.send(new Msg.Up(100)).model().topLine()).isZero();
        ScrollView.Model atTop = driver.model();
        assertThat(driver.send(new Msg.Up(1)).model()).isSameAs(atTop);

        // A non-positive count names no movement; the message constructor normalizes it away.
        assertThat(new Msg.Up(-4).lines()).isZero();
        assertThat(new Msg.Down(-4).lines()).isZero();
        assertThat(driver.send(new Msg.Down(0)).model()).isSameAs(atTop);
    }

    @Test
    void pagingMovesByTheVisibleHeightAndClampsAtBothEnds() {
        var driver = pane(3, 10);

        assertThat(driver.send(new Msg.PageDown()).model().topLine()).isEqualTo(3);
        assertThat(driver.send(new Msg.PageDown()).model().topLine()).isEqualTo(6);
        assertThat(driver.send(new Msg.PageDown()).model().topLine()).isEqualTo(7);
        ScrollView.Model atBottom = driver.model();
        assertThat(driver.send(new Msg.PageDown()).model()).isSameAs(atBottom);

        assertThat(driver.send(new Msg.PageUp()).model().topLine()).isEqualTo(4);
        assertThat(driver.send(new Msg.PageUp()).model().topLine()).isEqualTo(1);
        assertThat(driver.send(new Msg.PageUp()).model().topLine()).isZero();
    }

    @Test
    void aZeroRowPaneScrollsNowhereAndPaintsNothing() {
        var driver = pane(0, 10);

        assertThat(ScrollView.visibleLines(driver.model())).isEmpty();
        ScrollView.Model before = driver.model();
        assertThat(driver.send(new Msg.PageDown()).model())
                .as("a zero-row page moves nothing")
                .isSameAs(before);
        assertThat(driver.send(new Msg.End()).model().topLine()).isEqualTo(10);
        assertThat(ScrollView.visibleLines(driver.model())).isEmpty();
    }

    @Test
    void followEndKeepsTheLastRowsVisibleWhileContentIsAppended() {
        var driver = pane(3, true, 10);
        assertThat(driver.model().topLine()).isEqualTo(7);
        assertThat(window(driver.model())).containsExactly("L7", "L8", "L9");

        driver.send(new Msg.ContentChanged(rows(11)));
        assertThat(driver.model().topLine()).isEqualTo(8);
        assertThat(window(driver.model())).containsExactly("L8", "L9", "L10");

        driver.send(new Msg.ContentChanged(rows(20)));
        assertThat(driver.model().topLine()).isEqualTo(17);
        assertThat(window(driver.model())).containsExactly("L17", "L18", "L19");
    }

    @Test
    void aDeliberateScrollUpwardReleasesFollowEndAndLaterAppendsDoNotSnapBack() {
        var driver = pane(3, true, 10);
        driver.send(new Msg.PageUp());

        assertThat(driver.model().topLine()).isEqualTo(4);
        assertThat(driver.model().followEnd()).isFalse();

        driver.send(new Msg.ContentChanged(rows(11)));
        assertThat(driver.model().topLine()).isEqualTo(4);
        driver.send(new Msg.ContentChanged(rows(30)));
        assertThat(driver.model().topLine())
                .as("content arriving must not move a window the user scrolled back")
                .isEqualTo(4);
        assertThat(window(driver.model())).containsExactly("L4", "L5", "L6");
    }

    @Test
    void steppingToTheBottomByHandDoesNotArmFollowEndButEndDoes() {
        var driver = pane(3, true, 10);
        driver.send(new Msg.PageUp());
        driver.send(new Msg.PageDown());
        driver.send(new Msg.PageDown());

        assertThat(driver.model().topLine()).isEqualTo(7);
        assertThat(driver.model().followEnd()).isFalse();

        driver.send(new Msg.ContentChanged(rows(20)));
        assertThat(driver.model().topLine())
                .as("a window the user stepped to the bottom does not follow the next append")
                .isEqualTo(7);
        assertThat(driver.model().followEnd()).isFalse();

        driver.send(new Msg.End());
        assertThat(driver.model().topLine()).isEqualTo(17);
        assertThat(driver.model().followEnd()).isTrue();

        driver.send(new Msg.ContentChanged(rows(21)));
        assertThat(driver.model().topLine()).isEqualTo(18);
    }

    @Test
    void homeReleasesFollowEndOnlyWhenTheWindowCanMove() {
        var shortContent = pane(3, true, 2);
        ScrollView.Model before = shortContent.model();
        assertThat(shortContent.send(new Msg.Home()).model())
                .as("content that fits the window is already at its first row")
                .isSameAs(before);

        var driver = pane(3, true, 10);
        driver.send(new Msg.Home());
        assertThat(driver.model().topLine()).isZero();
        assertThat(driver.model().followEnd()).isFalse();
    }

    @Test
    void endArmsFollowEndOnlyWhenTheConfigurationAllowsIt() {
        var fixed = pane(3, false, 10);
        fixed.send(new Msg.Down(3));
        fixed.send(new Msg.End());
        assertThat(fixed.model().topLine()).isEqualTo(7);
        assertThat(fixed.model().followEnd()).isFalse();
        fixed.send(new Msg.ContentChanged(rows(20)));
        assertThat(fixed.model().topLine())
                .as("a pane that may not follow keeps its offset")
                .isEqualTo(7);

        var following = pane(3, true, 10);
        following.send(new Msg.PageUp());
        following.send(new Msg.End());
        assertThat(following.model().topLine()).isEqualTo(7);
        assertThat(following.model().followEnd()).isTrue();
        following.send(new Msg.ContentChanged(rows(20)));
        assertThat(following.model().topLine()).isEqualTo(17);
    }

    @Test
    void truncationAndReplacementClampTheOffsetWithoutExceptions() {
        var scrolledBack = pane(3, false, 10);
        scrolledBack.send(new Msg.Down(7));
        assertThat(scrolledBack.model().topLine()).isEqualTo(7);

        scrolledBack.send(new Msg.ContentChanged(rows(5)));
        assertThat(scrolledBack.model().topLine()).isEqualTo(2);
        assertThat(window(scrolledBack.model())).containsExactly("L2", "L3", "L4");

        scrolledBack.send(new Msg.ContentChanged(rows(2)));
        assertThat(scrolledBack.model().topLine()).isZero();
        scrolledBack.send(new Msg.ContentChanged(ScrollView.Content.empty()));
        assertThat(scrolledBack.model().topLine()).isZero();
        assertThat(ScrollView.visibleLines(scrolledBack.model())).isEmpty();

        var following = pane(3, true, 10);
        following.send(new Msg.ContentChanged(rows(2)));
        assertThat(following.model().topLine()).isZero();
        assertThat(following.model().followEnd()).isTrue();
    }

    @Test
    void aContentSnapshotThatDidNotChangeIsANoOp() {
        var driver = pane(3, 10);
        ScrollView.Content content = driver.model().content();
        ScrollView.Model before = driver.model();

        assertThat(driver.send(new Msg.ContentChanged(content)).model()).isSameAs(before);
        assertThat(driver.send(new Msg.ViewportChanged(3)).model()).isSameAs(before);
        assertThat(driver.send(new Msg.ContentChanged(null)).model().lineCount()).isZero();
    }

    @Test
    void aResizedPaneKeepsItsFirstVisibleRowOrTheEndItWasFollowing() {
        var scrolledBack = pane(5, false, 20);
        scrolledBack.send(new Msg.End());
        assertThat(scrolledBack.model().topLine()).isEqualTo(15);

        scrolledBack.send(new Msg.ViewportChanged(3));
        assertThat(scrolledBack.model().topLine())
                .as("shrinking the pane keeps the row the user put at the top")
                .isEqualTo(15);
        scrolledBack.send(new Msg.ViewportChanged(10));
        assertThat(scrolledBack.model().topLine()).isEqualTo(10);
        scrolledBack.send(new Msg.ViewportChanged(20));
        assertThat(scrolledBack.model().topLine()).isZero();

        var following = pane(5, true, 20);
        assertThat(following.model().topLine()).isEqualTo(15);
        following.send(new Msg.ViewportChanged(10));
        assertThat(following.model().topLine()).isEqualTo(10);
        following.send(new Msg.ViewportChanged(2));
        assertThat(following.model().topLine()).isEqualTo(18);
        following.send(new Msg.ViewportChanged(0));
        assertThat(following.model().topLine()).isEqualTo(20);
        assertThat(following.model().visibleRows()).isZero();
        assertThat(ScrollView.visibleLines(following.model())).isEmpty();
    }

    @Test
    void focusIsApplicationDataAndAReductionNeverMovesIt() {
        var driver = pane(3, 5);
        assertThat(driver.model().focused()).isTrue();

        ScrollView.Model unfocused = driver.model().withFocus(false);
        assertThat(unfocused.focused()).isFalse();
        assertThat(unfocused.withFocus(false)).isSameAs(unfocused);

        driver.send(new Msg.Down(1));
        assertThat(driver.model().focused())
                .as("a gesture is not a focus change")
                .isTrue();

        assertThat(unfocused.withFocus(true).focused()).isTrue();
        assertThat(unfocused.topLine())
                .as("a model is a value: the earlier state keeps its own offset")
                .isZero();
        assertThat(driver.model().topLine()).isEqualTo(1);
    }

    @Test
    void aModelNormalizesAnyOffsetItIsGiven() {
        ScrollView.Content content = rows(10);

        assertThat(new ScrollView.Model(-5, false, true, 3, content).topLine()).isZero();
        assertThat(new ScrollView.Model(999, false, true, 3, content).topLine()).isEqualTo(7);
        assertThat(new ScrollView.Model(5, true, true, 3, content).topLine()).isEqualTo(7);
        assertThat(new ScrollView.Model(0, false, true, -3, content).visibleRows()).isZero();
        assertThat(new ScrollView.Model(0, false, true, 3, null).content()).isEqualTo(
                ScrollView.Content.empty());
    }

    @Test
    void aContentCountAtTheIntegerCeilingCannotOverflowAnOffset() {
        // Counting 100,000 rows is cheap; a count at the int ceiling costs the same because the
        // provider answers it directly. Navigation must do its arithmetic without overflowing.
        ScrollView.Content huge = new ScrollView.Content() {
            @Override
            public int lineCount() {
                return Integer.MAX_VALUE;
            }

            @Override
            public StyledText line(int index) {
                return StyledText.plain("r" + index);
            }
        };
        var driver =
                ComponentDriver.initialize(
                        new ScrollView(new ScrollView.Config(10, false)),
                        new InitContext(null, null));
        driver.send(new Msg.ContentChanged(huge));

        assertThat(driver.send(new Msg.Down(Integer.MAX_VALUE)).model().topLine())
                .isEqualTo(Integer.MAX_VALUE - 10);
        assertThat(driver.send(new Msg.Up(Integer.MAX_VALUE)).model().topLine()).isZero();
        assertThat(new ScrollView.Model(Integer.MAX_VALUE, false, true, 10, huge).topLine())
                .isEqualTo(Integer.MAX_VALUE - 10);
        assertThat(ScrollView.visibleLines(driver.model())).hasSize(10);
    }

    @Test
    void aNegativePaneHeightIsRejectedAtConfigurationTime() {
        assertThatThrownBy(() -> new ScrollView.Config(-1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibleRows");
        assertThatThrownBy(() -> ScrollView.Config.defaults().withVisibleRows(-2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibleRows");
        assertThat(ScrollView.Config.defaults().withVisibleRows(0).visibleRows()).isZero();
    }
}
