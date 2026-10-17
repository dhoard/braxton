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

package io.github.dhoard.braxton.view;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

/** Terse factories for {@link View} values. */
public final class Views {

    private Views() {
    }

    public static <Msg> View<Msg> text(String text) {
        return text(StyledText.plain(text));
    }

    public static <Msg> View<Msg> text(String text, Style style) {
        return text(StyledText.of(text, style));
    }

    public static <Msg> View<Msg> text(StyledText text) {
        return new View.Text<>(text);
    }

    public static <Msg> View<Msg> blank() {
        return new View.Blank<>();
    }

    public static <Msg> View<Msg> fill(Style style) {
        return new View.Fill<>(style);
    }

    @SafeVarargs
    public static <Msg> View<Msg> column(View<Msg>... items) {
        java.util.List<View<Msg>> copy = new java.util.ArrayList<>(items.length);
        for (View<Msg> item : items) {
            copy.add(item);
        }
        return new View.Column<>(copy, Alignment.START);
    }

    public static <Msg> View<Msg> column(List<View<Msg>> items) {
        return new View.Column<>(items, Alignment.START);
    }

    public static <Msg> View<Msg> column(Alignment align, List<View<Msg>> items) {
        return new View.Column<>(items, align);
    }

    @SafeVarargs
    public static <Msg> View<Msg> row(View<Msg>... items) {
        java.util.List<View<Msg>> copy = new java.util.ArrayList<>(items.length);
        for (View<Msg> item : items) {
            copy.add(item);
        }
        return new View.Row<>(copy, Alignment.START);
    }

    public static <Msg> View<Msg> row(List<View<Msg>> items) {
        return new View.Row<>(items, Alignment.START);
    }

    public static <Msg> View<Msg> row(Alignment align, List<View<Msg>> items) {
        return new View.Row<>(items, align);
    }

    public static <Msg> View<Msg> padding(int value, View<Msg> child) {
        return new View.Padding<>(Insets.uniform(value), child);
    }

    public static <Msg> View<Msg> padding(Insets insets, View<Msg> child) {
        return new View.Padding<>(insets, child);
    }

    public static <Msg> View<Msg> border(Border border, View<Msg> child) {
        return new View.BorderBox<>(border, StyledText.empty(), Style.NONE, child);
    }

    public static <Msg> View<Msg> border(Border border, StyledText title, View<Msg> child) {
        return new View.BorderBox<>(border, title, Style.NONE, child);
    }

    public static <Msg> View<Msg> border(Border border, StyledText title, Style style, View<Msg> child) {
        return new View.BorderBox<>(border, title, style, child);
    }

    public static <Msg> View<Msg> fixed(int size, View<Msg> child) {
        return new View.Fixed<>(size, child);
    }

    public static <Msg> View<Msg> grow(View<Msg> child) {
        return new View.Grow<>(1, child);
    }

    public static <Msg> View<Msg> grow(int weight, View<Msg> child) {
        return new View.Grow<>(weight, child);
    }

    /** A vertical gap of {@code rows} blank cells. */
    public static <Msg> View<Msg> gap(int rows) {
        return fixed(Math.max(0, rows), blank());
    }

    @SafeVarargs
    public static <Msg> View<Msg> overlay(View<Msg>... layers) {
        java.util.List<View<Msg>> copy = new java.util.ArrayList<>(layers.length);
        for (View<Msg> layer : layers) {
            copy.add(layer);
        }
        return new View.Overlay<>(copy, false);
    }

    public static <Msg> View<Msg> overlay(List<View<Msg>> layers, boolean modal) {
        return new View.Overlay<>(layers, modal);
    }

    /** A modal overlay of {@code layer} on top of {@code base}. */
    public static <Msg> View<Msg> modal(View<Msg> base, View<Msg> layer) {
        return new View.Overlay<>(List.of(base, layer), true);
    }

    public static <Msg> View<Msg> keyed(List<Binding<Msg>> capture, View<Msg> child,
            List<Binding<Msg>> bubble) {
        return new View.Keyed<>(capture, View.InputHandler.none(), child, bubble);
    }

    public static <Msg> View<Msg> keyed(List<Binding<Msg>> capture, View.InputHandler<Msg> intercept,
            View<Msg> child, List<Binding<Msg>> bubble) {
        return new View.Keyed<>(capture, intercept, child, bubble);
    }

    public static <Msg> View<Msg> onKeys(List<Binding<Msg>> bindings, View<Msg> child) {
        return new View.Keyed<>(List.of(), View.InputHandler.none(), child, bindings);
    }

    public static <Msg> View<Msg> focus(ElementId id, boolean focused, View<Msg> child) {
        return new View.Focused<>(id, focused, List.of(), View.InputHandler.none(), child);
    }

    public static <Msg> View<Msg> focus(ElementId id, boolean focused, List<Binding<Msg>> bindings,
            View.InputHandler<Msg> input, View<Msg> child) {
        return new View.Focused<>(id, focused, bindings, input, child);
    }

    /** Maps a list of child views into a parent message type. */
    public static <Child, Parent> List<View<Parent>> all(List<View<Child>> views,
            Function<? super Child, ? extends Parent> mapper) {
        List<View<Parent>> mapped = new ArrayList<>(views.size());
        for (View<Child> view : views) {
            mapped.add(view.map(mapper));
        }
        return List.copyOf(mapped);
    }
}
