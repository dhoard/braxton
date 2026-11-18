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

package io.github.dhoard.braxton.view;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Terse factories for {@link View} values. */
public final class Views {

    private Views() {}

    /**
     * Styled text from a plain string.
     *
     * @param text the content; null or empty is a blank view
     * @param <Msg> the message type the view tree produces
     * @return a text view
     */
    public static <Msg> View<Msg> text(String text) {
        return text(StyledText.plain(text));
    }

    /**
     * Styled text from a plain string and one style.
     *
     * @param text the content; null or empty is a blank view
     * @param style the style applied to the whole value
     * @param <Msg> the message type the view tree produces
     * @return a text view
     */
    public static <Msg> View<Msg> text(String text, Style style) {
        return text(StyledText.of(text, style));
    }

    /**
     * Styled text from an already-styled value.
     *
     * @param text the content; required
     * @param <Msg> the message type the view tree produces
     * @return a text view
     */
    public static <Msg> View<Msg> text(StyledText text) {
        return new View.Text<>(text);
    }

    /**
     * An explicit empty branch that occupies no space.
     *
     * @param <Msg> the message type the view tree produces
     * @return a blank view
     */
    public static <Msg> View<Msg> blank() {
        return new View.Blank<>();
    }

    /**
     * A background fill for every cell in its bounds.
     *
     * @param style the style painted across the bounds
     * @param <Msg> the message type the view tree produces
     * @return a fill view
     */
    public static <Msg> View<Msg> fill(Style style) {
        return new View.Fill<>(style);
    }

    /**
     * Children stacked top to bottom, start-aligned.
     *
     * @param items the children
     * @param <Msg> the message type the children produce
     * @return a column view
     */
    @SafeVarargs
    public static <Msg> View<Msg> column(View<Msg>... items) {
        java.util.List<View<Msg>> copy = new java.util.ArrayList<>(items.length);
        for (View<Msg> item : items) {
            copy.add(item);
        }
        return new View.Column<>(copy, Alignment.START);
    }

    /**
     * Children stacked top to bottom, start-aligned.
     *
     * @param items the children
     * @param <Msg> the message type the children produce
     * @return a column view
     */
    public static <Msg> View<Msg> column(List<View<Msg>> items) {
        return new View.Column<>(items, Alignment.START);
    }

    /**
     * Children stacked top to bottom with a cross-axis alignment.
     *
     * @param align the cross-axis placement of a narrower child
     * @param items the children
     * @param <Msg> the message type the children produce
     * @return a column view
     */
    public static <Msg> View<Msg> column(Alignment align, List<View<Msg>> items) {
        return new View.Column<>(items, align);
    }

    /**
     * Children side by side, start-aligned.
     *
     * @param items the children
     * @param <Msg> the message type the children produce
     * @return a row view
     */
    @SafeVarargs
    public static <Msg> View<Msg> row(View<Msg>... items) {
        java.util.List<View<Msg>> copy = new java.util.ArrayList<>(items.length);
        for (View<Msg> item : items) {
            copy.add(item);
        }
        return new View.Row<>(copy, Alignment.START);
    }

    /**
     * Children side by side, start-aligned.
     *
     * @param items the children
     * @param <Msg> the message type the children produce
     * @return a row view
     */
    public static <Msg> View<Msg> row(List<View<Msg>> items) {
        return new View.Row<>(items, Alignment.START);
    }

    /**
     * Children side by side with a cross-axis alignment.
     *
     * @param align the cross-axis placement of a shorter child
     * @param items the children
     * @param <Msg> the message type the children produce
     * @return a row view
     */
    public static <Msg> View<Msg> row(Alignment align, List<View<Msg>> items) {
        return new View.Row<>(items, align);
    }

    /**
     * Uniform spacing around one child.
     *
     * @param value the cells reserved on every side
     * @param child the padded child
     * @param <Msg> the message type the child produces
     * @return a padding view
     */
    public static <Msg> View<Msg> padding(int value, View<Msg> child) {
        return new View.Padding<>(Insets.uniform(value), child);
    }

    /**
     * Asymmetric spacing around one child.
     *
     * @param insets the spacing reserved on each side
     * @param child the padded child
     * @param <Msg> the message type the child produces
     * @return a padding view
     */
    public static <Msg> View<Msg> padding(Insets insets, View<Msg> child) {
        return new View.Padding<>(insets, child);
    }

    /**
     * A frame with no title.
     *
     * @param border the frame characters
     * @param child the framed child
     * @param <Msg> the message type the child produces
     * @return a border view
     */
    public static <Msg> View<Msg> border(Border border, View<Msg> child) {
        return new View.BorderBox<>(border, StyledText.empty(), Style.NONE, child);
    }

    /**
     * A frame with a title in its top edge.
     *
     * @param border the frame characters
     * @param title the top-edge label
     * @param child the framed child
     * @param <Msg> the message type the child produces
     * @return a border view
     */
    public static <Msg> View<Msg> border(Border border, StyledText title, View<Msg> child) {
        return new View.BorderBox<>(border, title, Style.NONE, child);
    }

    /**
     * A frame with a title and its own style.
     *
     * @param border the frame characters
     * @param title the top-edge label
     * @param style the style applied to the frame
     * @param child the framed child
     * @param <Msg> the message type the child produces
     * @return a border view
     */
    public static <Msg> View<Msg> border(Border border, StyledText title, Style style, View<Msg> child) {
        return new View.BorderBox<>(border, title, style, child);
    }

    /**
     * A frame whose top row the caller builds from the columns it is given.
     *
     * <p>Use it when something has to join the frame above it - a tab bar sitting on the content it
     * selects - because a title is painted inside an unbroken edge and cannot open one. The left,
     * right and bottom edges are the border's, and the child is inset like any framed child.</p>
     *
     * @param border the characters used for the edges that are drawn here
     * @param style the style applied to those edges; null is no styling
     * @param top builds the top row from the columns the frame was given; must occupy no more
     * @param child the framed child
     * @param <Msg> the message type the child produces
     * @return a framed view with a supplied top row
     */
    public static <Msg> View<Msg> topEdge(Border border, Style style, IntFunction<StyledText> top, View<Msg> child) {
        return new View.TopEdge<>(border, style, top, child);
    }

    /**
     * Exactly {@code size} cells on the parent's main axis.
     *
     * @param size the reserved cells; never negative
     * @param child the child painted inside the reservation
     * @param <Msg> the message type the child produces
     * @return a fixed-size view
     */
    public static <Msg> View<Msg> fixed(int size, View<Msg> child) {
        return new View.Fixed<>(size, child);
    }

    /**
     * An equal share of the parent's remaining main-axis space.
     *
     * @param child the child painted inside the share
     * @param <Msg> the message type the child produces
     * @return a growing view
     */
    public static <Msg> View<Msg> grow(View<Msg> child) {
        return new View.Grow<>(1, child);
    }

    /**
     * A weighted share of the parent's remaining main-axis space.
     *
     * @param weight the share of the remaining space; never negative
     * @param child the child painted inside the share
     * @param <Msg> the message type the child produces
     * @return a growing view
     */
    public static <Msg> View<Msg> grow(int weight, View<Msg> child) {
        return new View.Grow<>(weight, child);
    }

    /**
     * A vertical gap of {@code rows} blank cells.
     *
     * @param rows the rows to reserve; negative is treated as zero
     * @param <Msg> the message type the view tree produces
     * @return a fixed-size blank view
     */
    public static <Msg> View<Msg> gap(int rows) {
        return fixed(Math.max(0, rows), blank());
    }

    /**
     * Layers painted back to front over the same bounds, with no input restriction.
     *
     * @param layers the layers, back to front
     * @param <Msg> the message type the layers produce
     * @return an overlay view
     */
    @SafeVarargs
    public static <Msg> View<Msg> overlay(View<Msg>... layers) {
        java.util.List<View<Msg>> copy = new java.util.ArrayList<>(layers.length);
        for (View<Msg> layer : layers) {
            copy.add(layer);
        }
        return new View.Overlay<>(copy, false);
    }

    /**
     * Layers painted back to front over the same bounds.
     *
     * @param layers the layers, back to front
     * @param modal whether input routing is restricted to the topmost layer
     * @param <Msg> the message type the layers produce
     * @return an overlay view
     */
    public static <Msg> View<Msg> overlay(List<View<Msg>> layers, boolean modal) {
        return new View.Overlay<>(layers, modal);
    }

    /**
     * A modal layer over a base layer.
     *
     * @param base the layer painted underneath
     * @param layer the layer that owns input while it is shown
     * @param <Msg> the message type the layers produce
     * @return an overlay view whose routing is restricted to {@code layer}
     */
    public static <Msg> View<Msg> modal(View<Msg> base, View<Msg> layer) {
        return new View.Overlay<>(List.of(base, layer), true);
    }

    /**
     * Ancestor bindings around a subtree, without a full-event interceptor.
     *
     * @param capture bindings matched outermost-first before the focused element
     * @param child the wrapped subtree
     * @param bubble bindings matched innermost-first after the focused element
     * @param <Msg> the message type the bindings and child produce
     * @return a keyed view
     */
    public static <Msg> View<Msg> keyed(List<Binding<Msg>> capture, View<Msg> child, List<Binding<Msg>> bubble) {
        return new View.Keyed<>(capture, View.InputHandler.none(), child, bubble);
    }

    /**
     * Ancestor bindings and a full-event interceptor around a subtree.
     *
     * @param capture bindings matched outermost-first before the focused element
     * @param intercept the full-event handler run before the focused element
     * @param child the wrapped subtree
     * @param bubble bindings matched innermost-first after the focused element
     * @param <Msg> the message type the bindings and child produce
     * @return a keyed view
     */
    public static <Msg> View<Msg> keyed(
            List<Binding<Msg>> capture, View.InputHandler<Msg> intercept, View<Msg> child, List<Binding<Msg>> bubble) {
        return new View.Keyed<>(capture, intercept, child, bubble);
    }

    /**
     * Bubble bindings around a subtree.
     *
     * @param bindings the bindings matched after the focused element
     * @param child the wrapped subtree
     * @param <Msg> the message type the bindings and child produce
     * @return a keyed view
     */
    public static <Msg> View<Msg> onKeys(List<Binding<Msg>> bindings, View<Msg> child) {
        return new View.Keyed<>(List.of(), View.InputHandler.none(), child, bindings);
    }

    /**
     * A focus target with no bindings of its own.
     *
     * @param id the focus identity, scoped by the owning mount
     * @param focused whether the element currently owns focus
     * @param child the wrapped subtree
     * @param <Msg> the message type the child produces
     * @return a focused view
     */
    public static <Msg> View<Msg> focus(ElementId id, boolean focused, View<Msg> child) {
        return new View.Focused<>(id, focused, List.of(), View.InputHandler.none(), child);
    }

    /**
     * A focus target with local bindings and an input handler.
     *
     * @param id the focus identity, scoped by the owning mount
     * @param focused whether the element currently owns focus
     * @param bindings the local key bindings, matched while focused
     * @param input the handler for events the bindings do not consume
     * @param child the wrapped subtree
     * @param <Msg> the message type the element produces
     * @return a focused view
     */
    public static <Msg> View<Msg> focus(
            ElementId id, boolean focused, List<Binding<Msg>> bindings, View.InputHandler<Msg> input, View<Msg> child) {
        return new View.Focused<>(id, focused, bindings, input, child);
    }

    /**
     * A focus target that also handles pointer reports, with no key bindings of its own.
     *
     * @param id the focus identity, scoped by the owning mount
     * @param focused whether the element currently owns focus
     * @param pointer the handler for pointer reports inside this element; required
     * @param child the wrapped subtree
     * @param <Msg> the message type the child produces
     * @return a focused view
     */
    public static <Msg> View<Msg> focus(
            ElementId id, boolean focused, View.PointerHandler<Msg> pointer, View<Msg> child) {
        return new View.Focused<>(id, focused, List.of(), View.InputHandler.none(), child, pointer);
    }

    /**
     * A focus target with local bindings, an input handler and a pointer handler.
     *
     * @param id the focus identity, scoped by the owning mount
     * @param focused whether the element currently owns focus
     * @param bindings the local key bindings, matched while focused
     * @param input the handler for events the bindings do not consume
     * @param pointer the handler for pointer reports inside this element; required
     * @param child the wrapped subtree
     * @param <Msg> the message type the element produces
     * @return a focused view
     */
    public static <Msg> View<Msg> focus(
            ElementId id,
            boolean focused,
            List<Binding<Msg>> bindings,
            View.InputHandler<Msg> input,
            View.PointerHandler<Msg> pointer,
            View<Msg> child) {
        return new View.Focused<>(id, focused, bindings, input, child, pointer);
    }

    /**
     * Places a caret inside a subtree.
     *
     * <p>The cursor's row and column are local to the rectangle this wrapper is painted in, and the
     * resolved cell is shown only while it is visible there.</p>
     *
     * @param cursor the caret intent
     * @param child the subtree the cursor sits in
     * @param <Msg> the message type the child produces
     * @return a cursor view
     */
    public static <Msg> View<Msg> cursor(Cursor cursor, View<Msg> child) {
        return new View.CursorAt<>(cursor, child);
    }

    /**
     * A subtree built from the geometry it is given.
     *
     * <p>For a widget that wraps or clips its own content: the builder is called with the rows and
     * columns it is offered while it is measured, and with the rectangle it is allocated while it
     * is painted.</p>
     *
     * @param build the builder, a pure function of the offered or allocated size
     * @param <Msg> the message type the built subtree produces
     * @return a size-aware view
     */
    public static <Msg> View<Msg> sized(Function<Size, View<Msg>> build) {
        return new View.Sized<>(build);
    }

    /**
     * Declares the frame's terminal presentation around a subtree.
     *
     * <p>Use it once, at the root of the view: the intent covers the whole session, so a frame may
     * declare one and a second, different declaration is a structural failure instead of a value
     * chosen by paint order.</p>
     *
     * @param presentation the session presentation the frame asks for; required
     * @param child the wrapped subtree
     * @param <Msg> the message type the child produces
     * @return a presentation view
     */
    public static <Msg> View<Msg> presentation(TerminalPresentation presentation, View<Msg> child) {
        return new View.Presentation<>(presentation, child);
    }

    /**
     * Maps a list of child views into a parent message type.
     *
     * @param views the child views
     * @param mapper maps every child message to a parent message
     * @param <Child> the child message type
     * @param <Parent> the parent message type
     * @return the mapped views, in order
     */
    public static <Child, Parent> List<View<Parent>> all(
            List<View<Child>> views, Function<? super Child, ? extends Parent> mapper) {
        List<View<Parent>> mapped = new ArrayList<>(views.size());
        for (View<Child> view : views) {
            mapped.add(view.map(mapper));
        }
        return List.copyOf(mapped);
    }
}
