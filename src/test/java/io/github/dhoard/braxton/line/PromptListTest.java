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

package io.github.dhoard.braxton.line;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The list a prompt puts in front of the user, asserted on its own surface: what it prepares, where
 * its window sits, what its movement answers, and what a row paints.
 */
class PromptListTest {

    private static final List<String> FIVE = List.of("one", "two", "three", "four", "five");

    @Test
    void theOptionsAreFlattenedToOneRowAndTheBlankOnesAreDropped() {
        PromptList list = PromptList.of("Pick", List.of("one", "", "  ", "two\nlines", "tab\there"), 4, false);

        assertThat(list.options()).containsExactly("one", "two lines", "tab here");
        assertThat(PromptList.of("Pick", null, 4, false).options()).isEmpty();
        assertThat(PromptList.of("Pick", List.of(" ", ""), 4, false).options()).isEmpty();
    }

    @Test
    void theWindowShowsAtMostTheRowsItWasGiven() {
        PromptList list = PromptList.of("Pick", FIVE, 3, false);

        assertThat(list.rows()).isEqualTo(3);
        assertThat(list.visible()).isEqualTo(3);
        assertThat(list.height())
                .as("the question row plus the options it shows")
                .isEqualTo(4);
        assertThat(PromptList.of("Pick", FIVE, 9, false).visible()).isEqualTo(5);
        assertThat(PromptList.of("Pick", List.of(), 2, false).height()).isEqualTo(1);
        assertThat(PromptList.of("Pick", FIVE, 0, false).visible())
                .as("a window below one row shows one")
                .isEqualTo(1);
    }

    @Test
    void theWindowFollowsTheHighlightByTheLeastItCan() {
        PromptList list = PromptList.of("Pick", FIVE, 3, false);
        assertThat(list.offset()).isZero();

        PromptList third = list.moved(PromptKeys.Command.DOWN).moved(PromptKeys.Command.DOWN);
        assertThat(third.offset()).as("the third option still fits the window").isZero();
        assertThat(third.moved(PromptKeys.Command.DOWN).offset())
                .as("one row further scrolls the window by one")
                .isEqualTo(1);
        assertThat(list.moved(PromptKeys.Command.LAST).offset())
                .as("the last option")
                .isEqualTo(2);
        assertThat(list.moved(PromptKeys.Command.LAST)
                        .moved(PromptKeys.Command.FIRST)
                        .offset())
                .as("back to the first option")
                .isZero();
    }

    @Test
    void movementClampsAtBothEndsAndAnswersTheSameList() {
        PromptList list = PromptList.of("Pick", FIVE, 3, false);

        assertThat(list.moved(PromptKeys.Command.UP)).isSameAs(list);
        PromptList last = list.moved(PromptKeys.Command.LAST);
        assertThat(last.selected()).isEqualTo(4);
        assertThat(last.moved(PromptKeys.Command.DOWN)).isSameAs(last);
        assertThat(list.moved(null)).isSameAs(list);
        assertThat(list.moved(PromptKeys.Command.CHOOSE)).isSameAs(list);
        assertThat(list.moved(PromptKeys.Command.CANCEL)).isSameAs(list);
        PromptList empty = PromptList.of("Pick", List.of(), 3, false);
        assertThat(empty.moved(PromptKeys.Command.DOWN)).isSameAs(empty);
    }

    @Test
    void aPageIsOneWindowOfOptions() {
        PromptList list = PromptList.of("Pick", FIVE, 2, false);

        PromptList paged = list.moved(PromptKeys.Command.PAGE_DOWN);
        assertThat(paged.selected()).isEqualTo(2);
        assertThat(paged.offset()).isEqualTo(1);
        assertThat(paged.moved(PromptKeys.Command.PAGE_UP).selected()).isZero();
        assertThat(list.moved(PromptKeys.Command.PAGE_DOWN)
                        .moved(PromptKeys.Command.PAGE_DOWN)
                        .selected())
                .as("the last page clamps at the end")
                .isEqualTo(4);
    }

    @Test
    void onlyAMultipleChoiceListMarksAndUnmarks() {
        PromptList single = PromptList.of("Pick", FIVE, 3, false);
        assertThat(single.moved(PromptKeys.Command.TOGGLE)).isSameAs(single);

        PromptList multiple = PromptList.of("Pick", FIVE, 3, true);
        PromptList marked = multiple.moved(PromptKeys.Command.TOGGLE);
        assertThat(marked.marked(0)).isTrue();
        assertThat(marked.marked(1)).isFalse();
        assertThat(marked.moved(PromptKeys.Command.TOGGLE).marked(0))
                .as("the same gesture takes the mark back off")
                .isFalse();
        assertThat(marked.selected())
                .as("a mark leaves the highlight where it is")
                .isZero();
    }

    @Test
    void theMarkedOptionsComeBackInTheListsOrder() {
        PromptList marked = PromptList.of("Pick", FIVE, 5, true)
                .moved(PromptKeys.Command.LAST)
                .moved(PromptKeys.Command.TOGGLE)
                .moved(PromptKeys.Command.FIRST)
                .moved(PromptKeys.Command.TOGGLE);

        assertThat(marked.markedOptions()).containsExactly("one", "five");
        assertThat(marked.selectedOption()).isEqualTo("one");
    }

    @Test
    void aRowPaintsTheQuestionTheHintTheMarkerAndTheHighlight() {
        PromptList single = PromptList.of("Pick a theme", List.of("dark", "light"), 2, false);
        Screen screen = new Screen(single.height(), 60);
        single.paint(screen, 60, Themes.DARK, WidthPolicy.PINNED);

        assertThat(screen.plainLine(0)).isEqualTo("Pick a theme  (enter chooses, esc cancels)");
        assertThat(screen.plainLine(1)).isEqualTo("\u276F dark");
        assertThat(screen.plainLine(2))
                .as("the arrow's column is left blank so the labels line up")
                .isEqualTo("  light");
        // The highlighted row is filled across its whole width, which the label does not cover.
        assertThat(screen.style(1, 59)).isNotEqualTo(screen.style(2, 59));
        assertThat(screen.style(1, 0)).isNotEqualTo(screen.style(2, 0));
    }

    @Test
    void aMultipleChoiceRowPaintsItsBoxes() {
        PromptList multiple =
                PromptList.of("Build", List.of("linux", "macos"), 2, true).moved(PromptKeys.Command.TOGGLE);
        Screen screen = new Screen(multiple.height(), 60);
        multiple.paint(screen, 60, Themes.DARK, WidthPolicy.PINNED);

        assertThat(screen.plainLine(0)).isEqualTo("Build  (space marks, enter chooses, esc cancels)");
        assertThat(screen.plainLine(1)).startsWith("[\u2713] linux");
        assertThat(screen.plainLine(2)).startsWith("[ ] macos");
    }

    @Test
    void aQuestionThatIsBlankLeavesOnlyTheHint() {
        PromptList list = PromptList.of(null, List.of("one"), 1, false);
        Screen screen = new Screen(list.height(), 60);
        list.paint(screen, 60, Themes.DARK, WidthPolicy.PINNED);

        assertThat(list.question()).isEmpty();
        assertThat(screen.plainLine(0)).isEqualTo("(enter chooses, esc cancels)");
    }

    @Test
    void aRowWithNoColumnsPaintsNothing() {
        PromptList list = PromptList.of("Pick", FIVE, 2, false);
        Screen screen = new Screen(list.height(), 0);
        list.paint(screen, 0, Themes.DARK, WidthPolicy.PINNED);

        assertThat(screen.plainLines()).allSatisfy(line -> assertThat(line).isEmpty());
    }
}
