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

package io.github.dhoard.braxton.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Focus order, key routing, pointer state and modal hit contracts.
 */
class InteractionRouterTest {

    /**
     * A focusable leaf whose label identifies the messages it emits.
     */
    private static final class Field implements Widget<String, String> {

        @Override
        public Size measure(Size max, String model) {
            return max;
        }

        @Override
        public boolean canFocus(String model) {
            return !model.equals("disabled");
        }

        @Override
        public boolean focused(String model) {
            return model.startsWith("focused");
        }

        @Override
        public InteractionState state(String model) {
            return InteractionState.NONE.withDisabled(model.equals("disabled"));
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {}

        @Override
        public void onKey(InputEvent.KeyInput key, String model, java.util.function.Consumer<String> emit) {
            emit.accept("key:" + model + ":" + key.key().name());
        }

        @Override
        public void onPointer(
                PointerEvent pointer, Rect bounds, String model, java.util.function.Consumer<String> emit) {
            emit.accept("pointer:" + model + ":" + pointer.action());
        }

        @Override
        public void onInput(InputEvent event, String model, java.util.function.Consumer<String> emit) {
            if (event instanceof InputEvent.PasteInput paste) {
                emit.accept("paste:" + model + ":" + paste.text());
            }
        }
    }

    private static Element<String> field(String label) {
        return new Element.Leaf<>(new Field(), label);
    }

    /**
     * A field that claims a known number of columns, so a hit test lands where it is aimed.
     */
    private static Element<String> column(String label, int cols) {
        return new Element.Leaf<>(new Field(), label, Sizing.fixed(cols));
    }

    @Test
    void focusBelongsToTheModelAndAKeyReachesOnlyTheFocusedLeaf() {
        Element<String> disabled = new Element.Leaf<>(new Field(), "disabled");
        Element<String> second = new Element.Leaf<>(new Field(), "focused-b");
        Element<String> root = new Element.Group<>(List.of(field("a"), disabled, second), Element.Direction.ROW);
        var placement = ElementTree.layout(root, new Rect(0, 0, 4, 30));

        // Focus is what the model says it is; the router only reads it and never moves it.
        assertThat(InteractionRouter.focused(placement)).isSameAs(second);
        assertThat(InteractionRouter.focusables(placement).stream()
                        .map(leaf -> (String) leaf.model())
                        .toList())
                .containsExactly("a", "focused-b");

        List<String> messages = new ArrayList<>();
        assertThat(InteractionRouter.key(placement, InputEvent.KeyInput.of(Key.ENTER), messages::add))
                .isTrue();
        assertThat(messages).containsExactly("key:focused-b:enter");
    }

    /**
     * Mapping is transparent in every phase, and a paste is input rather than a gesture: a wrapped
     * field that takes text must still take it through the wrapper. The wrapper forwarded keys,
     * pointer reports and the caret and dropped {@code onInput}, so a mapped text field silently
     * refused everything a user pasted into it - a defect invisible from the composing source,
     * because the field is correct and the frame is correct.
     */
    @Test
    void aMappedLeafDeliversPasteThroughTheMapper() {
        Element<String> mapped = field("focused-a").map(label -> "mapped:" + label);
        assertThat(InteractionRouter.message(mapped, new InputEvent.PasteInput("two\nlines")))
                .contains("mapped:paste:focused-a:two\nlines");
        assertThat(InteractionRouter.message(mapped, InputEvent.KeyInput.of(Key.ENTER)))
                .as("mapping still carries the keys it always did")
                .contains("mapped:key:focused-a:enter");
    }

    @Test
    void theTopmostFocusedLeafWinsAndAModalHidesTheLeavesBehindIt() {
        Element<String> behind = new Element.Leaf<>(new Field(), "focused-behind");
        Element<String> dialog = new Element.Leaf<>(new Field(), "focused-dialog");
        var placement = ElementTree.layout(
                new Element.Group<>(List.of(behind, new Element.Modal<>(dialog)), Element.Direction.COLUMN),
                new Rect(0, 0, 4, 20));

        assertThat(InteractionRouter.focused(placement)).isSameAs(dialog);

        // Nothing focused inside the modal scope: the leaf behind it still must not answer.
        Element<String> quiet = new Element.Leaf<>(new Field(), "dialog");
        var quietPlacement = ElementTree.layout(
                new Element.Group<>(List.of(behind, new Element.Modal<>(quiet)), Element.Direction.COLUMN),
                new Rect(0, 0, 4, 20));
        List<String> messages = new ArrayList<>();
        assertThat(InteractionRouter.key(quietPlacement, InputEvent.KeyInput.of(Key.ENTER), messages::add))
                .isFalse();
        assertThat(messages).isEmpty();
    }

    @Test
    void pointerTracksHoverAndPressedAndIgnoresDisabledLeaves() {
        var placement = ElementTree.layout(
                new Element.Group<>(List.of(column("a", 10), column("disabled", 10)), Element.Direction.ROW),
                new Rect(0, 0, 4, 20));
        InteractionRouter.Interaction state = InteractionRouter.Interaction.NONE;
        List<String> messages = new ArrayList<>();

        state = InteractionRouter.pointer(
                placement, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 2), messages::add);
        assertThat(state.hovered()).as("the first column of the row").isNotNull();
        assertThat(state.pressed()).as("the widget that captured the press").isSameAs(state.hovered());

        state = InteractionRouter.pointer(
                placement, state, PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, 0, 2), messages::add);
        assertThat(state.pressed()).isNull();

        state = InteractionRouter.pointer(
                placement, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 15), messages::add);
        assertThat(state.hovered()).as("a disabled widget is not a target").isNull();
        assertThat(state.pressed()).isNull();
        assertThat(messages).containsExactly("pointer:a:PRESS", "pointer:a:RELEASE");
    }

    /**
     * A widget that answers only one key and places a cursor at its first cell.
     */
    private record Caret(Key answering, boolean showCursor) implements Widget<String, String> {

        @Override
        public Size measure(Size max, String model) {
            return max;
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {}

        @Override
        public boolean focused(String model) {
            return model.startsWith("focused");
        }

        @Override
        public void onKey(InputEvent.KeyInput key, String model, java.util.function.Consumer<String> emit) {
            if (key.key() == answering) emit.accept("answered:" + key.key().name());
        }

        @Override
        public Cursor cursor(Rect bounds, String model) {
            return showCursor ? Cursor.at(bounds.row(), bounds.col()).withMode(Cursor.Mode.STEADY) : null;
        }
    }

    @Test
    void anAnsweredKeyIsConsumedAndAnUnansweredOneFallsThrough() {
        Element<String> root = new Element.Leaf<>(new Caret(Key.ENTER, true), "focused-caret");
        var placement = ElementTree.layout(root, new Rect(0, 0, 3, 5));
        List<String> messages = new ArrayList<>();

        assertThat(InteractionRouter.key(placement, InputEvent.KeyInput.of(Key.ENTER), messages::add))
                .isTrue();
        assertThat(InteractionRouter.key(placement, InputEvent.KeyInput.of(Key.TAB), messages::add))
                .isFalse();
        assertThat(messages).containsExactly("answered:enter");
    }

    @Test
    void nothingIsConsumedWhenNothingIsFocused() {
        Element<String> root = new Element.Leaf<>(new Caret(Key.ENTER, true), "caret");
        var placement = ElementTree.layout(root, new Rect(0, 0, 3, 5));
        List<String> messages = new ArrayList<>();

        assertThat(InteractionRouter.key(placement, InputEvent.KeyInput.of(Key.ENTER), messages::add))
                .isFalse();
        assertThat(messages).isEmpty();
    }

    @Test
    void theCursorIsTheFocusedWidgetsOwn() {
        Element<String> caret = new Element.Leaf<>(new Caret(Key.ENTER, true), "focused-caret");
        Element<String> second = new Element.Leaf<>(new Caret(Key.ENTER, false), "other");
        var placement = ElementTree.layout(
                new Element.Group<>(List.of(caret, second), Element.Direction.COLUMN), new Rect(2, 3, 6, 8));

        Cursor cursor = InteractionRouter.cursor(placement);
        assertThat(cursor).isNotNull();
        assertThat(cursor.row()).isEqualTo(2);
        assertThat(cursor.column()).isEqualTo(3);
        assertThat(cursor.visible()).isTrue();
        // A tree with nothing focused presents no caret at all.
        var unfocused = ElementTree.layout(
                new Element.Group<>(List.of(field("a"), field("b")), Element.Direction.COLUMN), new Rect(2, 3, 6, 8));
        assertThat(InteractionRouter.cursor(unfocused)).isNull();
    }

    @Test
    void aHiddenCursorIsNotPresented() {
        Element<String> root = new Element.Leaf<>(new Caret(Key.ENTER, false), "focused-caret");
        var placement = ElementTree.layout(root, new Rect(0, 0, 3, 5));

        Cursor hidden = new Cursor(Cursor.Shape.BAR, Cursor.Mode.HIDDEN, Cursor.Strategy.NATIVE, null, 0, 0);
        assertThat(hidden.visible()).isFalse();
        assertThat(InteractionRouter.cursor(placement)).isNull();
    }

    @Test
    void modalLayerIsTheOnlyHitTargetUnderneathIt() {
        Element<String> base = field("base");
        Element<String> dialog = field("dialog");
        var placement = ElementTree.layout(
                new Element.Group<>(List.of(base, new Element.Modal<>(dialog)), Element.Direction.COLUMN),
                new Rect(0, 0, 8, 20));

        Element.Leaf<?, String> hit = ElementTree.hit(placement, 0, 0);
        assertThat(hit).isSameAs(dialog);
    }
}
