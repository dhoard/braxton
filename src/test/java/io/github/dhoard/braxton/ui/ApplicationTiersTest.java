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

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The three tiers an application boundary has, in order.
 *
 * <p>A gesture the application claims is answered before the tree sees it, the focused widget
 * answers next, and only a gesture it declines is matched against the application's own bindings.
 * These tests are the whole routing contract at that boundary: which tier wins, which one never
 * runs, and what an event that is not a gesture does.</p>
 */
class ApplicationTiersTest {

    /**
     * Two rows of a scene: a field that owns focus and answers one key, and a plain line.
     */
    private static Element<String> scene() {
        return Elements.column(new Element.Leaf<>(new Field(), "focused", Sizing.measured()), Elements.text("second"));
    }

    @Test
    void theFocusedLeafOfTheTreeAnswersAndAnUnansweredKeyFallsThrough() {
        Element<String> tree = scene();

        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.ENTER)))
                .containsExactly("enter:focused");
        // Nothing in the tree answers Tab, so the application still receives the key as itself.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.TAB)))
                .isEmpty();
    }

    @Test
    void theApplicationsOwnBindingsAnswerLastAndOnlyAGestureNothingElseTook() {
        Element<String> tree = scene();
        List<Binding<String>> application = List.of(Binding.of("tab", "app:tab"), Binding.of("enter", "app:enter"));

        // The widget answered Enter, so the application's Enter binding never runs.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.ENTER), List.of(), application))
                .containsExactly("enter:focused");
        // Nothing in the tree answered Tab, so the application's binding is what answers it.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.TAB), List.of(), application))
                .containsExactly("app:tab");
        // A gesture the application has no binding for still reaches nobody.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.PAGE_UP), List.of(), application))
                .isEmpty();
        // A paste is not a gesture a binding can match: it stays text.
        assertThat(InteractionRouter.route(tree, new InputEvent.PasteInput("typed"), List.of(), application))
                .containsExactly("paste:focused:typed");
    }

    @Test
    void aClaimedGestureIsTheApplicationsBeforeItIsTheTrees() {
        Element<String> tree = scene();
        List<Binding<String>> claims = List.of(Binding.of("enter", "app:claimed-enter"));

        // The widget would answer Enter, but the claim is matched first, so the application keeps it.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.ENTER), claims, List.of()))
                .containsExactly("app:claimed-enter");
        // A claim on a gesture the widget does not answer changes nothing.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.TAB), claims, List.of()))
                .isEmpty();
        // Without the claim the widget answers, which is the order an unclaimed key follows.
        assertThat(InteractionRouter.route(tree, InputEvent.KeyInput.of(Key.ENTER), List.of(), List.of()))
                .containsExactly("enter:focused");
        // A release is not the press it ends: a claim never fires on the way up.
        assertThat(InteractionRouter.route(
                        tree, InputEvent.KeyInput.of(Key.ENTER, "").withType(KeyEventType.RELEASE), claims, List.of()))
                .isEmpty();
    }

    @Test
    void aClaimBeatsTheFocusedWidgetEvenWhenItIsTheOnlyAttentionTheKeyGets() {
        // A field answers the keys it edits, so the claim is what keeps a global key out of it:
        // nothing about the tree has to know the application's shortcuts exist.
        Element<String> field = new Element.Leaf<>(new Field(), "a", Sizing.measured());
        List<Binding<String>> claims = List.of(Binding.of("enter", "app:global"));

        assertThat(InteractionRouter.route(field, InputEvent.KeyInput.of(Key.ENTER), claims, List.of()))
                .containsExactly("app:global");
        // Without the claim the same key is the widget's.
        assertThat(InteractionRouter.route(field, InputEvent.KeyInput.of(Key.ENTER), List.of(), List.of()))
                .containsExactly("enter:a");
    }

    @Test
    void aPointerReportIsHitTestedAndReachesItsOwnRectangle() {
        Element<String> tree = Elements.column(Elements.fixed(1, Elements.text("header")), scene());
        ElementTree.Placement<String> placement = ElementTree.layout(tree, new Rect(0, 0, 4, 8));
        List<String> messages = new java.util.ArrayList<>();

        InteractionRouter.Interaction state = InteractionRouter.pointer(
                placement,
                InteractionRouter.Interaction.NONE,
                PointerEvent.press(PointerButton.LEFT, null, 1, 2),
                messages::add);

        // Row 1 is the scene's first row, so the report reaches the field as its first row.
        assertThat(messages).containsExactly("press:focused:0");
        assertThat(state.pressed()).isInstanceOf(Field.class);
    }

    /**
     * A field that owns focus, answers one key, and reports a caret and pointer presses.
     */
    private static final class Field implements Widget<String, String> {

        @Override
        public Size measure(Size max, String model) {
            return new Size(1, Math.min(max.cols(), model.length()));
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {
            surface.write(bounds, StyledText.plain(model), WidthPolicy.PINNED);
        }

        @Override
        public boolean focused(String model) {
            return true;
        }

        @Override
        public InteractionState state(String model) {
            return InteractionState.NONE.withFocused(focused(model));
        }

        @Override
        public void onKey(InputEvent.KeyInput key, String model, Consumer<String> emit) {
            if (key.key().equals(Key.ENTER)) {
                emit.accept("enter:" + model);
            }
        }

        @Override
        public Cursor cursor(Rect bounds, String model) {
            return Cursor.at(bounds.row(), bounds.col() + Math.max(0, model.length() - 1));
        }

        @Override
        public void onInput(InputEvent event, String model, Consumer<String> emit) {
            if (event instanceof InputEvent.PasteInput paste) {
                emit.accept("paste:" + model + ":" + paste.text());
            }
        }

        @Override
        public void onPointer(PointerEvent pointer, Rect bounds, String model, Consumer<String> emit) {
            if (pointer.isPress()) {
                emit.accept("press:" + model + ":" + (pointer.row() - bounds.row()));
            }
        }
    }
}
