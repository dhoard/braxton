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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;

/** The single-choice list marks its selection, styles it, and maps its keys. */
class SelectorTest {

    private static final Style ACTIVE = Style.NONE.withInvert();
    private static final Style INACTIVE = Style.NONE.withItalic();

    private static Selector.State state(String... options) {
        return Selector.State.of(List.of(options));
    }

    private static View<String> view(Selector.State state, boolean focused) {
        return Selector.view(ElementId.of("selector"), state, focused, ACTIVE, INACTIVE,
                action -> action.getClass().getSimpleName());
    }

    private static List<String> lines(Selector.State state, boolean focused) {
        return lines(state, focused, Math.max(1, state.options().size()));
    }

    private static List<String> lines(Selector.State state, boolean focused, int rows) {
        return new ScreenRenderer().plainLines(view(state, focused), new Viewport(rows, 40));
    }

    private static Optional<String> actionFor(Key key) {
        return view(state("a", "b"), true) instanceof View.Focused<String> focused
                ? focused.input().handle(InputEvent.KeyInput.of(key, ""))
                : Optional.empty();
    }

    @Test
    void theSelectedOptionCarriesTheMarkerAndTheActiveStyle() {
        List<String> rendered = lines(state("alpha", "beta"), true);
        assertThat(rendered).containsExactly("\u276F alpha", "  beta");
        // The marker is two cells wide whether or not it is drawn, so every row starts on the
        // same column as the option text.
        assertThat(rendered).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isEqualTo(2 + TextMeasure.columns(
                        line.stripLeading().isEmpty() ? " " : line.substring(2))));

        var screen = new ScreenRenderer().screen(view(state("alpha", "beta"), true),
                new Viewport(6, 40));
        assertThat(activeFlags(screen, 0, 0)).as("selected row").isEqualTo("invert");
        assertThat(activeFlags(screen, 1, 0)).as("other row").isEqualTo("italic");
    }

    @Test
    void anUnfocusedSelectorKeepsItsMarkerButPaintsNoActiveStyle() {
        // A highlight says "keys land here", which is a property of focus, not of selection. The
        // marker stays so the selection is still legible, and the active style is withheld - the
        // same rule TableSelector follows and the same one Editor follows by dropping its caret.
        assertThat(lines(state("alpha", "beta"), false))
                .containsExactly("\u276F alpha", "  beta");
        var screen = new ScreenRenderer().screen(view(state("alpha", "beta"), false),
                new Viewport(6, 40));
        assertThat(activeFlags(screen, 0, 0)).as("selected row").isEqualTo("italic");
        assertThat(activeFlags(screen, 1, 0)).as("other row").isEqualTo("italic");
    }

    @Test
    void bothSelectorsAgreeOnWhenTheActiveStyleIsUsed() {
        // The two selectors take the same parameters; they must now also behave the same way, so
        // an application cannot get two different answers from one focus state.
        var focused = new ScreenRenderer().screen(view(state("alpha", "beta"), true),
                new Viewport(6, 40));
        assertThat(activeFlags(focused, 0, 0)).as("focused selector").isEqualTo("invert");
        var blurred = new ScreenRenderer().screen(view(state("alpha", "beta"), false),
                new Viewport(6, 40));
        assertThat(activeFlags(blurred, 0, 0)).as("unfocused selector").isEqualTo("italic");

        var tableFocused = new ScreenRenderer().screen(TableSelector.view(
                ElementId.of("table"),
                TableSelector.State.of(List.of("id"), List.of(List.of("alpha"), List.of("beta"))),
                true, ACTIVE, INACTIVE, INACTIVE, action -> action.getClass().getSimpleName()),
                new Viewport(3, 40));
        assertThat(tableFocused.cell(1, 0).style().invert()).as("focused table").isTrue();
        var tableBlurred = new ScreenRenderer().screen(TableSelector.view(
                ElementId.of("table"),
                TableSelector.State.of(List.of("id"), List.of(List.of("alpha"), List.of("beta"))),
                false, ACTIVE, INACTIVE, INACTIVE, action -> action.getClass().getSimpleName()),
                new Viewport(3, 40));
        assertThat(tableBlurred.cell(1, 0).style().invert()).as("unfocused table").isFalse();
    }

    @Test
    void theSelectionMovesAndClampsAndConfirmsTheCurrentValue() {
        Selector.State start = state("alpha", "beta", "gamma");
        assertThat(Selector.update(start, new Selector.Action.Up()).state().selected()).isZero();
        // Moving is not choosing: Down reports no intent, and the following Confirm names the cell.
        Selector.State second = Selector.update(start, new Selector.Action.Down()).state();
        assertThat(Selector.update(start, new Selector.Action.Down()).intent())
                .isEqualTo(new Selector.Intent.None());
        assertThat(Selector.update(second, new Selector.Action.Confirm()).intent())
                .isEqualTo(new Selector.Intent.Chosen(1, "beta"));
        Selector.State third = Selector.update(second, new Selector.Action.Down()).state();
        assertThat(third.selected()).isEqualTo(2);
        assertThat(Selector.update(third, new Selector.Action.Down()).state().selected())
                .isEqualTo(2);
        assertThat(Selector.update(start, new Selector.Action.Cancel()).intent())
                .isEqualTo(new Selector.Intent.Cancelled());
        assertThat(Selector.update(start, new Selector.Action.Up()).intent())
                .isEqualTo(new Selector.Intent.None());
    }

    @Test
    void anOptionCannotSplitItsRowAcrossLineBreaks() {
        // One painted row per option is what makes the marker a legible answer to "which option".
        // An option inserted raw split its own row, pushed every later option down, and left the
        // continuation unmarked; a bare CR was dropped instead of becoming the space it stands for.
        Selector.State three = state("a\nb", "c\rd", "e\r\nf");
        assertThat(lines(three, true, 3)).containsExactly("\u276F a b", "  c d", "  e f");
        // The list also measures one row per option, so a taller viewport cannot paint the breaks
        // as extra rows that the marker does not name.
        assertThat(new LayoutEngine().intrinsicHeight(view(three, true), 40))
                .as("rows measured for three options").isEqualTo(3);
    }

    @Test
    void anEmptySelectorHasNothingToChoose() {
        Selector.State empty = Selector.State.of(List.of());
        assertThat(empty.selected()).isEqualTo(-1);
        assertThat(empty.current()).isEmpty();
        assertThat(Selector.update(empty, new Selector.Action.Confirm()).intent())
                .isEqualTo(new Selector.Intent.None());
        assertThat(lines(empty, true)).isNotEmpty().allSatisfy(line -> assertThat(line).isEmpty());
        assertThatNullPointerException().isThrownBy(() -> new Selector.State(null, 0, true));
    }

    @Test
    void aSelectedIndexIsClampedIntoRangeHoweverTheStateWasBuilt() {
        assertThat(new Selector.State(List.of("a", "b"), 9, true).selected()).isEqualTo(1);
        assertThat(new Selector.State(List.of("a", "b"), -4, true).selected()).isZero();
        assertThat(new Selector.State(List.of(), 3, true).selected()).isEqualTo(-1);
        assertThat(new Selector.State(List.of(), 3, true).active()).isTrue();
    }

    @Test
    void theViewMapsItsKeysAndIgnoresEverythingElse() {
        assertThat(actionFor(Key.UP)).contains("Up");
        assertThat(actionFor(Key.DOWN)).contains("Down");
        assertThat(actionFor(Key.ENTER)).contains("Confirm");
        assertThat(actionFor(Key.ESCAPE)).contains("Cancel");
        assertThat(actionFor(Key.of("x"))).isEmpty();
        assertThat(view(state("a"), true)).isInstanceOf(View.Focused.class);
        assertThat(view(state("a"), false)).isInstanceOf(View.Focused.class);
    }

    /** The cell's style flags these tests set, so the assertion is about style, not identity. */
    private static String activeFlags(io.github.dhoard.braxton.internal.render.Screen screen, int row,
            int col) {
        Style style = screen.cell(row, col).style();
        List<String> flags = new java.util.ArrayList<>();
        if (style.invert()) {
            flags.add("invert");
        }
        if (style.italic()) {
            flags.add("italic");
        }
        return String.join("+", flags);
    }
}