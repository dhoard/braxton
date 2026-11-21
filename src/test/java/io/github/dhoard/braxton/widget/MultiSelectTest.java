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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The multi-select: its marks, its popup, keys, presses, its window and its themes.
 */
class MultiSelectTest {

    private static final List<Option> OPTIONS =
            List.of(Option.of("name", "Name"), Option.of("size", "Size"), Option.of("date", "Date"));

    private final MultiSelect multi = new MultiSelect(OPTIONS);

    @Test
    void aClosedFieldPaintsTheMarkedLabelsAndOpensOntoItsBoxes() {
        MultiSelect.Model model =
                multi.init(new InitContext(null, null)).model().withChosen(List.of("name", "date"));

        assertThat(ElementScreen.render(multi.element(model), 1, 14).lines())
                .containsExactly("Name, Date   " + MultiSelect.CLOSED_GLYPH);

        MultiSelect.Model open = model.withOpen(true);
        assertThat(open.chosen()).containsExactly("name", "date");
        assertThat(ElementScreen.render(multi.element(open), 4, 14).lines())
                .containsExactly(
                        "Name, Date   " + MultiSelect.OPEN_GLYPH,
                        MultiSelect.MARKED + " Name",
                        MultiSelect.UNMARKED + " Size",
                        MultiSelect.MARKED + " Date");
        assertThat(ElementScreen.render(multi.element(open), 5, 14).lines().get(4))
                .isEmpty();
    }

    @Test
    void nothingMarkedIsAnEmptyValueThatStillShowsTheGlyph() {
        MultiSelect.Model model = multi.init(new InitContext(null, null)).model();

        assertThat(model.chosen()).isEmpty();
        assertThat(model.summary().isEmpty()).isTrue();
        assertThat(ElementScreen.render(multi.element(model), 1, 8).line(0))
                .isEqualTo("       " + MultiSelect.CLOSED_GLYPH);
    }

    @Test
    void aNarrowFieldClipsTheLabelsAndDropsTheGlyphBeforeItCostsThemTooMuch() {
        MultiSelect.Model model =
                multi.init(new InitContext(null, null)).model().withChosen(List.of("name", "date"));

        assertThat(ElementScreen.render(multi.element(model), 1, 6).line(0))
                .as("four columns of value and the glyph")
                .isEqualTo("Name " + MultiSelect.CLOSED_GLYPH);
        assertThat(ElementScreen.render(multi.element(model), 1, 4).line(0))
                .as("four columns keep two of them for the value")
                .isEqualTo("Na " + MultiSelect.CLOSED_GLYPH);
        assertThat(ElementScreen.render(multi.element(model), 1, 3).line(0))
                .as("three columns hold the value instead of half a glyph")
                .isEqualTo("Nam");
        assertThat(ElementScreen.render(multi.element(model), 1, 1).line(0)).isEqualTo("N");
        assertThat(ElementScreen.render(multi.element(model), 0, 0).lines()).isEmpty();
    }

    @Test
    void spaceOpensTheListAndMarksOrUnmarksTheHighlightedOption() {
        MultiSelect.Model model = multi.init(new InitContext(null, null)).model();

        var opened = multi.update(model, new MultiSelect.Msg.Toggle());
        assertThat(opened.model().open()).isTrue();
        assertThat(opened.model().highlighted()).isEqualTo("name");
        assertThat(opened.outputs()).isEmpty();

        var marked = multi.update(opened.model(), new MultiSelect.Msg.Toggle());
        assertThat(marked.model().open()).isTrue();
        assertThat(marked.model().chosen()).containsExactly("name");
        assertThat(marked.outputs())
                .containsExactly(new MultiSelect.Output.ChosenMany(List.of(Option.of("name", "Name"))));

        MultiSelect.Model several =
                apply(multi, apply(multi, marked.model(), new MultiSelect.Msg.Down()), new MultiSelect.Msg.Toggle());
        assertThat(several.chosen()).containsExactly("name", "size");
        var cleared = multi.update(several, new MultiSelect.Msg.Toggle());
        assertThat(cleared.model().chosen()).containsExactly("name");
        assertThat(cleared.outputs())
                .containsExactly(new MultiSelect.Output.ChosenMany(List.of(Option.of("name", "Name"))));

        // Closing reports nothing; dismissing an open list reports that it was dismissed.
        assertThat(multi.update(several, new MultiSelect.Msg.ToggleOpen()).outputs())
                .isEmpty();
        assertThat(multi.update(several, new MultiSelect.Msg.ToggleOpen())
                        .model()
                        .open())
                .isFalse();
        var dismissed = multi.update(several, new MultiSelect.Msg.Cancel());
        assertThat(dismissed.model().open()).isFalse();
        assertThat(dismissed.outputs()).containsExactly(new MultiSelect.Output.Canceled());
        assertThat(dismissed.model().chosen()).containsExactly("name", "size");

        // A mark no option names is not a state change and reports nothing.
        assertThat(multi.update(several, new MultiSelect.Msg.Choose("gone")).outputs())
                .isEmpty();
        assertThat(multi.update(model, new MultiSelect.Msg.Toggle()).model().open())
                .isTrue();
        assertThat(multi.update(model.withOpen(true), new MultiSelect.Msg.Cancel())
                        .model()
                        .open())
                .isFalse();
        assertThat(multi.update(model, new MultiSelect.Msg.Cancel()).model()).isSameAs(model);
    }

    @Test
    void arrowsAndEscapeAreTheApplicationsWhileTheListIsClosed() {
        MultiSelect.Model closed = multi.init(new InitContext(null, null)).model();
        MultiSelect.Model open = closed.withOpen(true);

        for (Key key : List.of(Key.UP, Key.DOWN, Key.HOME, Key.END, Key.ESCAPE)) {
            List<MultiSelect.Msg> produced = new ArrayList<>(1);
            multi.onKey(InputEvent.KeyInput.of(key), closed, produced::add);
            assertThat(produced).as("a closed field declines %s", key).isEmpty();
        }
        List<MultiSelect.Msg> produced = new ArrayList<>(1);
        multi.onKey(InputEvent.KeyInput.of(Key.SPACE), closed, produced::add);
        assertThat(produced).containsExactly(new MultiSelect.Msg.Toggle());
        assertThat(InteractionRouter.key(
                        Elements.multiSelect(multi, closed, message -> message),
                        InputEvent.KeyInput.of(Key.DOWN),
                        m -> {}))
                .as("a closed field is not a stop for the arrows")
                .isFalse();

        produced.clear();
        multi.onKey(InputEvent.KeyInput.of(Key.DOWN), open, produced::add);
        assertThat(produced).containsExactly(new MultiSelect.Msg.Down());
        produced.clear();
        multi.onKey(InputEvent.KeyInput.of(Key.ESCAPE), open, produced::add);
        assertThat(produced).containsExactly(new MultiSelect.Msg.Cancel());
        produced.clear();
        multi.onKey(InputEvent.KeyInput.of(Key.SPACE, "", KeyEventType.RELEASE), closed, produced::add);
        assertThat(produced).as("a release never fires the press again").isEmpty();
        produced.clear();
        multi.onKey(InputEvent.KeyInput.of(Key.of("x")), open, produced::add);
        assertThat(produced).as("a field answers only its own keys").isEmpty();

        assertThat(apply(multi, open, new MultiSelect.Msg.End()).highlighted()).isEqualTo("date");
        assertThat(apply(multi, open, new MultiSelect.Msg.Home()).highlighted()).isEqualTo("name");
        // Closed, the same messages are inert, so a gesture that arrived anyway moves nothing.
        assertThat(apply(multi, closed, new MultiSelect.Msg.Down())).isSameAs(closed);
    }

    @Test
    void aPressOnAnOptionMarksItAndLeavesTheListOpen() {
        MultiSelect.Model closed = multi.init(new InitContext(null, null)).model();
        Element<MultiSelect.Msg> element = multi.element(closed.withOpen(true));

        assertThat(pointer(element, 6, 14, press(0, 2))).containsExactly(new MultiSelect.Msg.ToggleOpen());
        assertThat(pointer(multi.element(closed), 1, 14, press(0, 0)))
                .containsExactly(new MultiSelect.Msg.ToggleOpen());
        assertThat(pointer(element, 6, 14, press(2, 0))).containsExactly(new MultiSelect.Msg.Choose("size"));
        assertThat(pointer(element, 6, 14, press(5, 0)))
                .as("a row past the last option marks nothing")
                .isEmpty();
        assertThat(pointer(element, 6, 14, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 2, 0)))
                .isEmpty();

        MultiSelect.Model marked = apply(multi, closed.withOpen(true), new MultiSelect.Msg.Choose("size"));
        assertThat(marked.chosen()).containsExactly("size");
        assertThat(marked.open()).isTrue();
        assertThat(marked.highlighted()).isEqualTo("size");
    }

    @Test
    void thePopupWindowFollowsTheHighlightAndIsClampedIntoTheOptions() {
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            options.add(Option.of("i" + index, "i" + index));
        }
        MultiSelect eight = new MultiSelect(options);
        MultiSelect.Model model =
                eight.init(new InitContext(null, null)).model().withRows(3).withOpen(true);

        MultiSelect.Model last = apply(eight, model, new MultiSelect.Msg.End());
        assertThat(last.highlighted()).isEqualTo("i7");
        assertThat(last.offset()).isEqualTo(5);
        assertThat(ElementScreen.render(eight.element(last), 4, 10).lines())
                .containsExactly(
                        "         " + MultiSelect.OPEN_GLYPH,
                        MultiSelect.UNMARKED + " i5",
                        MultiSelect.UNMARKED + " i6",
                        MultiSelect.UNMARKED + " i7");

        assertThat(apply(eight, last, new MultiSelect.Msg.Home()).offset()).isZero();
        assertThat(model.withRows(8).offset()).isZero();
        assertThat(model.withRows(-1).rows()).isZero();
    }

    @Test
    void boxesAndLabelsArePaintedInTheThemesStyles() {
        MultiSelect.Model model = new MultiSelect.Model(OPTIONS, List.of("name"), true, "size", true, 0, 8);

        ElementScreen dark = ElementScreen.render(multi.element(model), 4, 14, Themes.DARK);
        ElementScreen light = ElementScreen.render(multi.element(model), 4, 14, Themes.LIGHT);
        ElementScreen unfocused = ElementScreen.render(multi.element(model.withFocus(false)), 4, 14, Themes.DARK);

        assertThat(dark.styles(0).get(0))
                .as("the focused value")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(0).get(13))
                .as("the focused glyph is the accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(1).get(0))
                .as("a marked box is the accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(2).get(0))
                .as("a highlighted box merges focus over the fill")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.MUTED).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(2).get(4))
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.SELECTED)
                        .merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS))));
        assertThat(dark.styles(2).get(13))
                .as("the highlight fills the whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(dark.styles(3).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(unfocused.styles(0).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(unfocused.styles(2).get(0))
                .as("an unfocused highlight keeps the fill and loses the focus style")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED).merge(Themes.DARK.style(ThemeToken.MUTED)));
        assertThat(light.styles(1).get(0)).isEqualTo(Themes.LIGHT.style(ThemeToken.ACCENT));
    }

    @Test
    void theFieldIsAFocusStopOnlyWhileItHasOptions() {
        MultiSelect.Model model = multi.init(new InitContext(null, null)).model();
        Element<MultiSelect.Msg> root = Elements.multiSelect(multi, model, message -> message);

        assertThat(multi.focused(model)).isTrue();
        assertThat(multi.focused(model.withFocus(false))).isFalse();
        assertThat(multi.canFocus(model)).isTrue();
        assertThat(multi.canFocus(new MultiSelect(List.of())
                        .init(new InitContext(null, null))
                        .model()))
                .isFalse();
        assertThat(ElementScreen.render(root, 1, 14).focusables()).containsExactly(new Rect(0, 0, 1, 14));

        List<MultiSelect.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.SPACE), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new MultiSelect.Msg.Toggle());
        assertThat(multi.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("up", "down", "home", "end", "mark", "dismiss");
        assertThat(multi.keys().actionFor(Key.SPACE)).map(KeyAction::message).contains(new MultiSelect.Msg.Toggle());
        assertThat(multi.keys().actionFor(Key.ENTER)).map(KeyAction::message).contains(new MultiSelect.Msg.Toggle());
        assertThat(multi.config().options()).isEqualTo(OPTIONS);
    }

    @Test
    void optionsAndMarksAreValidatedAndNormalized() {
        assertThatThrownBy(() -> new MultiSelect.Config(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> new MultiSelect((MultiSelect.Config) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> multi.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> multi.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> multi.update(null, new MultiSelect.Msg.Up())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> multi.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Option.copyAll(Arrays.asList(Option.of("a", "A"), null)))
                .isInstanceOf(NullPointerException.class);

        // Marks the options do not name are dropped, and the order is the options' own.
        MultiSelect.Model model =
                new MultiSelect.Model(OPTIONS, List.of("date", "gone", "name", "date"), false, "", true, 0, 4);
        assertThat(model.chosen()).containsExactly("name", "date");
        assertThat(model.chosenOptions()).extracting(Option::id).containsExactly("name", "date");
        assertThat(model.summary().plainText()).isEqualTo("Name, Date");
        assertThat(model.withOptions(List.of(Option.of("size", "Size"))).chosen())
                .isEmpty();
        assertThat(model.withChosen(null).chosen()).isEmpty();
        assertThat(model.withFocus(false).focused()).isFalse();
        assertThat(model.withHighlighted("size").highlighted()).isEqualTo("size");
        assertThat(model.visibleRows()).as("a closed field paints no highlight").allMatch(row -> !row.highlighted());
        assertThat(model.withOpen(true).highlighted())
                .as("opening anchors the highlight on the first mark")
                .isEqualTo("name");
        assertThat(new MultiSelect.Model(OPTIONS, List.of("date"), true, "gone", true, 0, 4).highlighted())
                .as("a stale highlight falls back to the first mark")
                .isEqualTo("date");
        assertThat(new MultiSelect.Model(List.of(), List.of(), true, "", true, 0, 4).highlighted())
                .isEmpty();
        assertThat(new MultiSelect.Model(List.of(), List.of("gone"), true, "", true, 0, 4).chosen())
                .isEmpty();
        assertThat(MultiSelect.Model.of(null).options()).isEmpty();
        assertThat(MultiSelect.Config.of(null).options()).isEmpty();
        assertThat(MultiSelect.Config.of(OPTIONS).withOptions(List.of()).options())
                .isEmpty();
        assertThat(MultiSelect.Config.of(OPTIONS).withRows(2).rows()).isEqualTo(2);
        assertThat(model.visibleRows()).extracting(MultiSelect.Row::id).containsExactly("name", "size", "date");
        assertThat(model.visibleRows().get(0).marked()).isTrue();
        assertThat(model.visibleRows().get(1).marked()).isFalse();
    }

    private static MultiSelect.Model apply(MultiSelect multi, MultiSelect.Model model, MultiSelect.Msg message) {
        return multi.update(model, message).model();
    }

    private static List<MultiSelect.Msg> pointer(
            Element<MultiSelect.Msg> root, int rows, int cols, PointerEvent report) {
        List<MultiSelect.Msg> produced = new ArrayList<>(1);
        ElementTree.Placement<MultiSelect.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}
