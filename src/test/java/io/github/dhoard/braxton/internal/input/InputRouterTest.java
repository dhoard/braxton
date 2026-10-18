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

package io.github.dhoard.braxton.internal.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** Routing precedence is structural, not registration order. */
class InputRouterTest {

    private static InputEvent key(String name) {
        return InputEvent.KeyInput.of(Key.parse(name));
    }

    @Test
    void focusedInputHandlerReceivesUnboundKeys() {
        View<String> view = Views.focus(ElementId.of("f"), true, List.of(),
                event -> Optional.of("focused:" + ((InputEvent.KeyInput) event).key()),
                Views.text("x"));
        assertThat(InputRouter.route(view, key("x"))).contains("focused:x");
    }

    @Test
    void captureBindingsWinOverTheFocusedHandler() {
        View<String> view = Views.keyed(
                List.of(Binding.of("up", "captured")),
                Views.focus(ElementId.of("f"), true, List.of(),
                        event -> Optional.of("focused"), Views.text("x")),
                List.of());
        assertThat(InputRouter.route(view, key("up"))).contains("captured");
        assertThat(InputRouter.route(view, key("down"))).contains("focused");
    }

    @Test
    void focusedBindingsRunBeforeTheFocusedHandler() {
        View<String> view = Views.focus(ElementId.of("f"), true,
                List.of(Binding.of("enter", "submitted")),
                event -> Optional.of("handler"), Views.text("x"));
        assertThat(InputRouter.route(view, key("enter"))).contains("submitted");
    }

    @Test
    void bubbleBindingsRunAfterTheFocusedTarget() {
        View<String> view = Views.keyed(List.of(),
                Views.focus(ElementId.of("f"), true, List.of(),
                        event -> Optional.empty(), Views.text("x")),
                List.of(Binding.of("escape", "bubbled")));
        assertThat(InputRouter.route(view, key("escape"))).contains("bubbled");
    }

    @Test
    void interceptorsSeeTheFullEventBeforeTheFocusedHandler() {
        View<String> view = Views.keyed(List.of(),
                event -> Optional.of("stamped:" + ((InputEvent.KeyInput) event).capturedAtNanos()),
                Views.focus(ElementId.of("f"), true, List.of(),
                        event -> Optional.of("focused"), Views.text("x")),
                List.of());
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.ESCAPE, "", 42L)))
                .contains("stamped:42");
    }

    @Test
    void modalOverlayBlocksEverythingUnderneath() {
        View<String> base = Views.focus(ElementId.of("base"), true,
                List.of(Binding.of("x", "base")), event -> Optional.empty(), Views.text("b"));
        View<String> dialog = Views.focus(ElementId.of("dialog"), true,
                List.of(Binding.of("x", "dialog")), event -> Optional.empty(), Views.text("d"));
        View<String> view = Views.modal(base, dialog);
        assertThat(InputRouter.route(view, key("x"))).contains("dialog");
    }

    @Test
    void nestedModalOverlayBlocksEverythingUnderneath() {
        View<String> base = Views.focus(ElementId.of("base"), true,
                List.of(Binding.of("x", "base")), event -> Optional.empty(), Views.text("b"));
        View<String> dialog = Views.focus(ElementId.of("dialog"), true,
                List.of(Binding.of("x", "dialog")), event -> Optional.empty(), Views.text("d"));
        View<String> view = Views.column(Views.text("header"), Views.modal(base, dialog));
        assertThat(InputRouter.route(view, key("x"))).contains("dialog");
    }

    @Test
    void aNonModalOverlayStillRoutesToTheFocusedLayer() {
        View<String> base = Views.text("b");
        View<String> layer = Views.focus(ElementId.of("layer"), true,
                List.of(Binding.of("x", "layer")), event -> Optional.empty(), Views.text("l"));
        assertThat(InputRouter.route(Views.overlay(base, layer), key("x"))).contains("layer");
    }

    @Test
    void aModalOverlayRoutesOnlyToItsTopmostLayer() {
        // The last layer is the one painted on top, so it is also the only scope input may reach,
        // whatever the layers beneath it declare.
        View<String> under = Views.focus(ElementId.of("under"), true,
                List.of(Binding.of("x", "under")), event -> Optional.empty(), Views.text("u"));
        View<String> middle = Views.focus(ElementId.of("middle"), true,
                List.of(Binding.of("x", "middle")), event -> Optional.empty(), Views.text("m"));
        View<String> top = Views.focus(ElementId.of("top"), true,
                List.of(Binding.of("x", "top")), event -> Optional.empty(), Views.text("t"));
        View<String> modal = Views.overlay(List.of(under, middle, top), true);
        assertThat(InputRouter.route(modal, key("x"))).contains("top");
        assertThat(InputRouter.modalScope(modal)).isSameAs(top);

        // Without the modal flag the whole stack stays in scope, and the router takes the first
        // focused node in paint order. Two focused layers at once is a contradictory tree (focus is
        // singular), so this records the precedence rather than a contract of its own.
        assertThat(InputRouter.route(Views.overlay(List.of(under, middle, top), false), key("x")))
                .contains("under");
        // An overlay with no layers at all is not a scope of its own.
        View<String> empty = Views.overlay(List.of(), true);
        assertThat(InputRouter.modalScope(empty)).isSameAs(empty);
    }

    @Test
    void aTopLayerThatItselfHoldsAModalOverlayIsThatOverlay() {
        View<String> under = Views.focus(ElementId.of("under"), true,
                List.of(Binding.of("x", "under")), event -> Optional.empty(), Views.text("u"));
        View<String> dialog = Views.focus(ElementId.of("dialog"), true,
                List.of(Binding.of("x", "dialog")), event -> Optional.empty(), Views.text("d"));
        View<String> sheet = Views.overlay(List.of(under, Views.modal(Views.text("s"), dialog)), true);
        assertThat(InputRouter.modalScope(sheet)).isSameAs(dialog);
        assertThat(InputRouter.route(sheet, key("x"))).contains("dialog");
    }

    @Test
    void withoutFocusOnlyTheScopeBindingsCanConsume() {
        View<String> view = Views.keyed(List.of(Binding.of("q", "quit")), Views.text("x"), List.of());
        assertThat(InputRouter.route(view, key("q"))).contains("quit");
        assertThat(InputRouter.route(view, key("z"))).isEmpty();
    }

    @Test
    void pasteGoesToTheFocusedHandlerAndNeverToBindings() {
        View<String> view = Views.focus(ElementId.of("f"), true,
                List.of(Binding.of("enter", "no")),
                event -> Optional.of("paste:" + ((InputEvent.PasteInput) event).text()),
                Views.text("x"));
        assertThat(InputRouter.route(view, new InputEvent.PasteInput("hi"))).contains("paste:hi");
    }

    @Test
    void onKeysRegistersBubbleBindingsAndAllMapsEveryChild() {
        View<String> onKeys = Views.onKeys(List.of(Binding.of("escape", "bubbled")),
                Views.text("leaf"));
        assertThat(InputRouter.route(onKeys, key("escape")))
                .as("without focus the scope's own bubble binding still fires")
                .contains("bubbled");

        View<String> focusedWins = Views.onKeys(List.of(Binding.of("x", "bubbled")),
                Views.focus(ElementId.of("f"), true, List.of(Binding.of("x", "local")),
                        event -> Optional.empty(), Views.text("leaf")));
        assertThat(InputRouter.route(focusedWins, key("x"))).contains("local");

        List<View<String>> mapped = Views.all(
                List.of(Views.text("a"), Views.focus(ElementId.of("f"), true,
                        List.of(Binding.of("enter", "child")), event -> Optional.empty(),
                        Views.text("b"))),
                (String message) -> "parent:" + message);
        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0)).isInstanceOf(View.Text.class);
        assertThat(InputRouter.route(mapped.get(1), key("enter"))).contains("parent:child");
    }

    @Test
    void duplicateBindingsInOnePhaseAreRejected() {
        assertThatThrownBy(() -> Views.keyed(
                List.of(Binding.of("x", "a"), Binding.of("x", "b")), Views.text("t"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void focusIdentitySurvivesRebuildAndReordering() {
        List<ElementId> orderA = List.of(ElementId.of("a"), ElementId.of("b"));
        List<ElementId> orderB = List.of(ElementId.of("b"), ElementId.of("a"));
        var focus = io.github.dhoard.braxton.view.FocusState.of(ElementId.of("b"));
        assertThat(focus.next(orderA)).isEqualTo(ElementId.of("a"));
        assertThat(focus.next(orderB)).isEqualTo(ElementId.of("a"));
        assertThat(focus.previous(orderA)).isEqualTo(ElementId.of("a"));
    }
}
