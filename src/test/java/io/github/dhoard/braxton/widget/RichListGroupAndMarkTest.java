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
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Group headers and the multiple choice of one list.
 */
class RichListGroupAndMarkTest {

    private static RichListConfig bare(List<RichList.Item> items, int rows) {
        return RichListConfig.defaults().withItems(items).withRows(rows);
    }

    private static RichList component(List<RichList.Item> items, int rows) {
        return new RichList(bare(items, rows));
    }

    /**
     * A list with no chrome, so the item rows are the rows a frame paints.
     */
    private static RichList.Chrome bareChrome() {
        return RichList.Chrome.defaults()
                .withTitle(false)
                .withStatus(false)
                .withPagination(false)
                .withHelp(false);
    }

    private static RichListModel model(RichList component) {
        return component.init(new InitContext(null, null)).model().withChrome(bareChrome());
    }

    private static RichListModel apply(RichList component, RichListModel state, RichListMsg message) {
        return component.update(state, message).model();
    }

    private static List<String> painted(RichList component, RichListModel state, int rows, int columns) {
        Element<RichListMsg> view = component.view(state, new ViewContext(new Viewport(rows, columns)));
        return ElementScreen.render(view, rows, columns).lines();
    }

    /**
     * The message a press produces, offered to the list the way the runtime offers it.
     */
    private static Optional<RichListMsg> press(RichList component, RichListModel state, int row, int col) {
        List<RichListMsg> produced = new java.util.ArrayList<>(1);
        component.onPointer(
                PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col),
                new Rect(0, 0, 10, 20),
                state,
                produced::add);
        return produced.stream().findFirst();
    }

    private static List<RichList.Item> grouped() {
        return List.of(
                RichList.Item.of("a", "alpha").withGroup("Fruit"),
                RichList.Item.of("b", "beta").withGroup("Fruit"),
                RichList.Item.of("c", "carrot").withGroup("Vegetable"),
                RichList.Item.of("d", "delta"));
    }

    @Test
    void aGroupHeaderIsPaintedAboveTheFirstItemOfItsGroup() {
        RichList component = component(grouped(), 10);
        RichListModel state = model(component);

        assertThat(painted(component, state, 6, 20))
                .containsExactly("Fruit", "\u276F alpha", "  beta", "Vegetable", "  carrot", "  delta");
    }

    @Test
    void aGroupHeaderIsRepeatedOnAPageThatStartsInsideTheGroup() {
        RichListConfig config = bare(grouped(), 4);
        RichList component = new RichList(config);
        RichListModel state = model(component);

        // The headers are page rows too: each page's two items and two headers fill it exactly.
        assertThat(RichList.pages(state, config)).containsExactly(new RichList.Page(0, 2), new RichList.Page(2, 4));

        RichListModel second = apply(component, state, new RichListMsg.Last());
        assertThat(painted(component, second, 4, 20)).startsWith("Vegetable", "  carrot", "\u276F delta");
    }

    @Test
    void aClickOnAGroupHeaderSelectsNothing() {
        RichList component = component(grouped(), 10);
        RichListModel state = model(component);

        assertThat(press(component, state, 0, 0)).isEmpty();
        assertThat(press(component, state, 1, 0)).contains(new RichListMsg.Select("a"));
    }

    @Test
    void spaceMarksTheSelectedItemAndStepsDown() {
        RichList component = component(grouped(), 10);
        RichListModel state = model(component);

        RichListModel first = apply(component, state, new RichListMsg.ToggleMark());
        assertThat(first.marked()).containsExactly("a");
        assertThat(first.selectedId()).isEqualTo("b");

        RichListModel second = apply(component, first, new RichListMsg.ToggleMark());
        assertThat(second.marked()).containsExactly("a", "b");
        assertThat(second.selectedId()).isEqualTo("c");

        RichListModel toggled = apply(component, second, new RichListMsg.Select("b"));
        assertThat(apply(component, toggled, new RichListMsg.ToggleMark()).marked())
                .containsExactly("a");
    }

    @Test
    void aChoiceReportsEveryMarkedItemInListOrder() {
        RichList component = component(grouped(), 10);
        RichListModel state = model(component);
        RichListModel marked =
                apply(component, apply(component, state, new RichListMsg.MarkAll()), new RichListMsg.Select("c"));

        assertThat(marked.marked()).containsExactly("a", "b", "c", "d");

        List<RichList.Item> chosen = component.update(marked, new RichListMsg.Choose()).outputs().stream()
                .map(output -> ((RichListOutput.Chosen) output).items())
                .findFirst()
                .orElseThrow();
        assertThat(chosen).extracting(RichList.Item::id).containsExactly("a", "b", "c", "d");

        List<RichList.Item> single = component.update(state, new RichListMsg.Choose()).outputs().stream()
                .map(output -> ((RichListOutput.Chosen) output).items())
                .findFirst()
                .orElseThrow();
        assertThat(single).extracting(RichList.Item::id).containsExactly("a");
    }

    @Test
    void aMarkSurvivesAFilterThatHidesIt() {
        RichList component = component(grouped(), 10);
        RichListModel state = apply(component, model(component), new RichListMsg.ToggleMark());
        RichListModel filtered = apply(component, state, new RichListMsg.BeginFilter());
        assertThat(filtered.filter().editing()).isTrue();

        RichListModel cleared = apply(component, state, new RichListMsg.ClearMarks());
        assertThat(cleared.marked()).isEmpty();
        assertThat(state.marked()).containsExactly("a");
    }

    @Test
    void theDefaultRendererPaintsAMarkedItem() {
        RichListDefaultRenderer renderer = RichListDefaultRenderer.defaults();
        RichList.Item item = RichList.Item.of("a", "alpha");

        assertThat(renderer.render(new RichList.Row(item, false, true, 20, List.of()))
                        .get(0)
                        .plainText())
                .isEqualTo(RichList.MARKED + "alpha");
        assertThat(renderer.render(new RichList.Row(item, true, true, 20, List.of()))
                        .get(0)
                        .plainText())
                .isEqualTo(RichList.MARKER + "alpha");
        assertThat(renderer.render(new RichList.Row(item, false, false, 20, List.of()))
                        .get(0)
                        .plainText())
                .isEqualTo(RichList.MARKER_GAP + "alpha");
    }

    @Test
    void theStatusRowNamesHowManyItemsAreMarked() {
        RichList component = component(grouped(), 8);
        RichListModel state = apply(
                component,
                model(component)
                        .withChrome(RichList.Chrome.defaults()
                                .withTitle(false)
                                .withPagination(false)
                                .withHelp(false)),
                new RichListMsg.ToggleMark());

        assertThat(painted(component, state, 8, 30))
                .anySatisfy(line -> assertThat(line).contains("1 marked"));
    }
}
