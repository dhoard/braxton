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
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A widget written only against public API composes with any other element.
 */
class WidgetTest {

    /**
     * The custom widget under test: itself, its model, its messages.
     */
    private static final class Button implements Widget<String, String> {

        @Override
        public Size measure(Size max, String model) {
            return new Size(3, model.length());
        }

        @Override
        public Rect arrange(Rect available, Size measured, String model) {
            return new Rect(available.row(), available.col(), 3, model.length());
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {
            surface.fill(bounds, Style.NONE);
            for (int i = 0; i < model.length(); i++) {
                surface.put(bounds.row(), bounds.col() + i, String.valueOf(model.charAt(i)), Style.NONE, null, 1);
            }
        }

        @Override
        public boolean focused(String model) {
            return model.equals("focused");
        }

        @Override
        public void onKey(InputEvent.KeyInput key, String model, java.util.function.Consumer<String> emit) {
            if (key.key().equals(Key.ENTER)) emit.accept("key:" + model);
        }

        @Override
        public void onPointer(
                PointerEvent pointer, Rect bounds, String model, java.util.function.Consumer<String> emit) {
            if (pointer.isPress() && bounds.contains(pointer.row(), pointer.column())) {
                emit.accept("pointer:" + model);
            }
        }
    }

    @Test
    void customWidgetsMeasurePaintAndTakeInputSideBySide() {
        Element.Leaf<String, String> custom = new Element.Leaf<>(new Button(), "focused");
        Element<String> aside = new Element.Leaf<>(new Button(), "aside");
        Element<String> root = new Element.Group<>(List.of(custom, aside), Element.Direction.ROW);
        Screen screen = new Screen(6, 20);
        ElementTree.Placement<String> placed = ElementTree.layout(root, new Rect(0, 0, 6, 20));

        ElementTree.paint(placed, screen);
        assertThat(screen.toCanvas().text(0, 0)).isEqualTo("f");
        assertThat(screen.toCanvas().plainLine(0)).contains("focused").contains("aside");
        assertThat(ElementTree.hit(placed, 0, 0)).isSameAs(custom);
        assertThat(ElementTree.focused(placed)).containsExactly(custom);

        List<String> messages = new ArrayList<>();
        InteractionRouter.key(placed, InputEvent.KeyInput.of(Key.ENTER), messages::add);
        ElementTree.pointer(placed, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 0), messages::add);
        assertThat(messages).containsExactly("key:focused", "pointer:focused");
    }
}
