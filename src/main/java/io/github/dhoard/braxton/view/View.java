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
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * An immutable description of what to draw.
 *
 * <p>Views are values: building one has no side effects, drawing one has no side effects, and the
 * same inputs always produce the same tree. Interactive elements declare message handlers, but a
 * handler only produces a message value; it never mutates state or performs I/O.</p>
 *
 * <p>Layout primitives are deliberately small. {@link Column} and {@link Row} stack children,
 * {@link Fixed} and {@link Grow} express main-axis sizing, {@link Padding}, {@link BorderBox}, and
 * {@link Overlay} handle nesting, and {@link Focused} plus {@link Keyed} express routing structure.
 * There is no retained component lifecycle and no virtual-DOM reconciliation.</p>
 *
 * @param <Msg> the message type this view can produce
 */
public sealed interface View<Msg> {

    /** Direct children, used by layout and routing. */
    List<View<Msg>> children();

    /** Maps every message value this view can produce. */
    <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper);

    /** Scopes every focus identity without changing bindings, handlers, or layout. */
    default View<Msg> scoped(ComponentPath path) {
        return switch (this) {
            case Text<Msg> text -> text;
            case Blank<Msg> blank -> blank;
            case Fill<Msg> fill -> fill;
            case Column<Msg> column ->
                new Column<>(
                        column.items().stream().map(item -> item.scoped(path)).toList(), column.align());
            case Row<Msg> row ->
                new Row<>(row.items().stream().map(item -> item.scoped(path)).toList(), row.align());
            case Padding<Msg> padding ->
                new Padding<>(padding.insets(), padding.child().scoped(path));
            case BorderBox<Msg> box ->
                new BorderBox<>(
                        box.border(), box.title(), box.style(), box.child().scoped(path));
            case TopEdge<Msg> edge ->
                new TopEdge<>(
                        edge.border(), edge.style(), edge.top(), edge.child().scoped(path));
            case Fixed<Msg> fixed -> new Fixed<>(fixed.size(), fixed.child().scoped(path));
            case Grow<Msg> grow -> new Grow<>(grow.weight(), grow.child().scoped(path));
            case Overlay<Msg> overlay ->
                new Overlay<>(
                        overlay.layers().stream().map(item -> item.scoped(path)).toList(), overlay.modal());
            case Keyed<Msg> keyed ->
                new Keyed<>(keyed.capture(), keyed.intercept(), keyed.child().scoped(path), keyed.bubble());
            case CursorAt<Msg> cursor ->
                new CursorAt<>(cursor.cursor().scoped(path), cursor.child().scoped(path));
            case Presentation<Msg> presentation ->
                new Presentation<>(
                        presentation.presentation(), presentation.child().scoped(path));
            case Sized<Msg> sized ->
                new Sized<>(size -> sized.build().apply(size).scoped(path));
            case Focused<Msg> focused ->
                new Focused<>(
                        focused.id().scoped(path),
                        focused.focused(),
                        focused.bindings(),
                        focused.input(),
                        focused.child().scoped(path),
                        focused.pointer());
        };
    }

    /**
     * Handles one input event, returning a message when the event is consumed.
     *
     * @param <Msg> the message type this handler can produce
     */
    @FunctionalInterface
    interface InputHandler<Msg> {

        Optional<Msg> handle(InputEvent event);

        static <Msg> InputHandler<Msg> none() {
            return event -> Optional.empty();
        }

        default <Msg2> InputHandler<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return event -> handle(event).map(mapper);
        }
    }

    /**
     * Handles one pointer report delivered to this element, returning a message when it is consumed.
     *
     * <p>The report arrives in the element's own coordinates: its row and column are counted from the
     * top-left cell of the rectangle the element was painted in, so a widget never has to know where
     * on screen it ended up. The second argument is that rectangle's size, which is what lets a
     * handler map a cell onto its content without having been built for one particular width.</p>
     *
     * <p>A handler is built with the view, not with the frame: routing may build a size-aware
     * subtree at zero columns just to find the handlers inside it. It must therefore derive its
     * geometry from the size it is handed rather than from the size the subtree was built with.</p>
     *
     * @param <Msg> the message type this handler can produce
     */
    @FunctionalInterface
    interface PointerHandler<Msg> {

        /**
         * Handles one report.
         *
         * @param event the report in this element's coordinates
         * @param region the size of the rectangle it was painted in
         * @return the message when the report is consumed
         */
        Optional<Msg> handle(PointerEvent event, Size region);

        static <Msg> PointerHandler<Msg> none() {
            return (event, region) -> Optional.empty();
        }

        default <Msg2> PointerHandler<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return (event, region) -> handle(event, region).map(mapper);
        }
    }

    /** Styled text. The text wraps to the available width during layout.
     *
     * @param text the styled text to paint
     * @param <Msg> the message type this view can produce
     */
    record Text<Msg>(StyledText text) implements View<Msg> {

        public Text {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return (View<Msg2>) this;
        }
    }

    /** Occupies no space; useful as an explicit empty branch.
     *
     * @param <Msg> the message type this view can produce
     */
    record Blank<Msg>() implements View<Msg> {

        @Override
        public List<View<Msg>> children() {
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return (View<Msg2>) this;
        }
    }

    /**
     * Paints every cell in its bounds with spaces carrying {@code style}; uses no messages.
     *
     * <p>A fill is opaque: it replaces whatever an earlier layer painted in those cells. Use it as a
     * background layer (the usual case) or to clear a region deliberately, never as a tint over
     * content you want to keep. In a row it also spans the row's full height, so it can back the
     * siblings beside it.</p>
     * @param style the style painted across every cell of its bounds
     * @param <Msg> the message type this view can produce
     */
    record Fill<Msg>(Style style) implements View<Msg> {

        public Fill {
            style = style == null ? Style.NONE : style;
        }

        @Override
        public List<View<Msg>> children() {
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return (View<Msg2>) this;
        }
    }

    /**
     * Children stacked top to bottom, each in the rows it measured. A start-aligned child spans the
     * column's full width; a centred or end-aligned child keeps its measured width at the aligned
     * offset. That width is the content the child occupies at the column's width, so a {@link Grow}
     * nested inside a row or overlay layer contributes what it holds rather than the zero it
     * measures on its own main axis - the rows a child is measured in and the rectangle it is
     * painted in are then the same width, and alignment cannot clip its content away.
     *
     * @param items the children, stacked top to bottom
     * @param align the cross-axis placement of a child narrower than the column
     * @param <Msg> the message type these children can produce
     */
    record Column<Msg>(List<View<Msg>> items, Alignment align) implements View<Msg> {

        public Column {
            items = List.copyOf(items);
            align = align == null ? Alignment.START : align;
        }

        @Override
        public List<View<Msg>> children() {
            return items;
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Column<>(mapAll(items, mapper), align);
        }
    }

    /**
     * Children side by side, each in the columns it measured. A start-aligned child is painted from
     * the top at its content height; a centred or end-aligned child keeps its content height at the
     * aligned offset.
     *
     * @param items the children, painted side by side
     * @param align the cross-axis placement of a child shorter than the row
     * @param <Msg> the message type these children can produce
     */
    record Row<Msg>(List<View<Msg>> items, Alignment align) implements View<Msg> {

        public Row {
            items = List.copyOf(items);
            align = align == null ? Alignment.START : align;
        }

        @Override
        public List<View<Msg>> children() {
            return items;
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Row<>(mapAll(items, mapper), align);
        }
    }

    /**
     * Adds asymmetric spacing around one child.
     *
     * @param insets the spacing reserved on each side
     * @param child the padded child
     * @param <Msg> the message type the child can produce
     */
    record Padding<Msg>(Insets insets, View<Msg> child) implements View<Msg> {

        public Padding {
            insets = insets == null ? Insets.NONE : insets;
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Padding<>(insets, child.map(mapper));
        }
    }

    /**
     * Frames one child; an optional title is drawn into the top edge.
     *
     * @param border the characters used for the frame, or a border that draws nothing
     * @param title the label drawn into the top edge; empty for no title
     * @param style the style applied to the frame itself
     * @param child the framed child
     * @param <Msg> the message type the child can produce
     */
    record BorderBox<Msg>(Border border, StyledText title, Style style, View<Msg> child) implements View<Msg> {

        public BorderBox {
            border = border == null ? Border.SINGLE : border;
            title = title == null ? StyledText.empty() : title;
            style = style == null ? Style.NONE : style;
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new BorderBox<>(border, title, style, child.map(mapper));
        }
    }

    /**
     * Frames one child with a top edge supplied by the caller instead of drawn from the border.
     *
     * <p>A frame that joins something above it - a tab bar sitting on its content, a header row
     * owned by a widget - cannot express that row as a {@link BorderBox} title, which is painted
     * inside an unbroken edge. Here the whole top row is built from the width the frame is given, so
     * the caller paints the corners, the horizontal run and any opening it needs, while the left,
     * right and bottom edges and the child's inset stay the border's own.</p>
     *
     * <p>A frame reserves its top row, and one column per side plus one row for the bottom edge only
     * when the border is visible, so a border that draws nothing leaves a header row above the child
     * that it never inset. The builder is a pure function of the columns the row is painted into and
     * is called once per measurement and once per paint; a row narrower than the columns offered is
     * painted as built and a wider one is clipped, so the row can never paint past the frame.</p>
     *
     * @param border the characters used for the left, right and bottom edges
     * @param style the style applied to those edges
     * @param top builds the top row from the columns the frame was given; required
     * @param child the framed child
     * @param <Msg> the message type the child can produce
     */
    record TopEdge<Msg>(Border border, Style style, IntFunction<StyledText> top, View<Msg> child) implements View<Msg> {

        public TopEdge {
            border = border == null ? Border.SINGLE : border;
            style = style == null ? Style.NONE : style;
            Objects.requireNonNull(top, "top");
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new TopEdge<>(border, style, top, child.map(mapper));
        }
    }

    /**
     * Reserves exactly {@code size} cells on the parent's main axis.
     *
     * @param size the reserved cells; never negative
     * @param child the child painted inside that reservation
     * @param <Msg> the message type the child can produce
     */
    record Fixed<Msg>(int size, View<Msg> child) implements View<Msg> {

        public Fixed {
            if (size < 0) {
                throw new IllegalArgumentException("fixed size must be non-negative");
            }
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Fixed<>(size, child.map(mapper));
        }
    }

    /**
     * Takes a share of the parent's remaining main-axis space.
     *
     * @param weight the share of the remaining space; never negative
     * @param child the child painted inside that share
     * @param <Msg> the message type the child can produce
     */
    record Grow<Msg>(int weight, View<Msg> child) implements View<Msg> {

        public Grow {
            if (weight < 0) {
                throw new IllegalArgumentException("weight must be non-negative");
            }
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Grow<>(weight, child.map(mapper));
        }
    }

    /**
     * Layers painted back to front over the same bounds.
     *
     * <p>When {@code modal} is true, input routing is restricted to the topmost modal layer; the
     * layers underneath neither receive keys nor hit-testing.</p>
     *
     * @param layers the layers, painted back to front
     * @param modal whether input routing is restricted to the topmost layer
     * @param <Msg> the message type these layers can produce
     */
    record Overlay<Msg>(List<View<Msg>> layers, boolean modal) implements View<Msg> {

        public Overlay {
            layers = List.copyOf(layers);
        }

        @Override
        public List<View<Msg>> children() {
            return layers;
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Overlay<>(mapAll(layers, mapper), modal);
        }
    }

    /**
     * Adds ancestor-level bindings around a subtree.
     *
     * <p>{@code capture} bindings run outermost-first before the focused target; {@code bubble}
     * bindings run after it, innermost-first. Duplicate keys within the same list are rejected at
     * build time rather than resolved by insertion order.</p>
     *
     * @param capture bindings matched outermost-first before the focused element
     * @param intercept the full-event handler run before the focused element
     * @param child the wrapped subtree
     * @param bubble bindings matched innermost-first after the focused element
     * @param <Msg> the message type these bindings and the child can produce
     */
    record Keyed<Msg>(
            List<Binding<Msg>> capture, InputHandler<Msg> intercept, View<Msg> child, List<Binding<Msg>> bubble)
            implements View<Msg> {

        public Keyed {
            capture = List.copyOf(capture);
            bubble = List.copyOf(bubble);
            intercept = intercept == null ? InputHandler.none() : intercept;
            Objects.requireNonNull(child, "child");
            requireUnique(capture, "capture");
            requireUnique(bubble, "bubble");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Keyed<>(
                    mapBindings(capture, mapper),
                    intercept.map(mapper),
                    child.map(mapper),
                    mapBindings(bubble, mapper));
        }
    }

    /**
     * Declares a focus target: its stable identity, whether it currently owns focus, its local key
     * bindings, the handler for events its bindings do not consume, and the handler for pointer
     * reports.
     *
     * <p>The rectangle this element is painted in is also the region a pointer report is hit-tested
     * against, which is what makes "the element under the pointer" and "the element that owns these
     * keys" the same identity. A press inside the region makes this element the target of the
     * gesture: the runtime keeps it captured until the release, so a drag that leaves the rectangle
     * still belongs to it.</p>
     *
     * @param id the focus identity, scoped by the owning mount
     * @param focused whether this element currently owns focus
     * @param bindings the local key bindings, matched while focused
     * @param input the handler for events the bindings do not consume
     * @param child the wrapped subtree
     * @param pointer the handler for pointer reports inside this element; never null
     * @param <Msg> the message type this element can produce
     */
    record Focused<Msg>(
            ElementId id,
            boolean focused,
            List<Binding<Msg>> bindings,
            InputHandler<Msg> input,
            View<Msg> child,
            PointerHandler<Msg> pointer)
            implements View<Msg> {

        public Focused {
            Objects.requireNonNull(id, "id");
            bindings = List.copyOf(bindings);
            input = input == null ? InputHandler.none() : input;
            Objects.requireNonNull(child, "child");
            pointer = pointer == null ? PointerHandler.none() : pointer;
            requireUnique(bindings, "focus");
        }

        /**
         * The same element with no pointer handler, the shape every element had before pointer
         * input existed.
         *
         * @param id the focus identity
         * @param focused whether this element currently owns focus
         * @param bindings the local key bindings
         * @param input the handler for events the bindings do not consume
         * @param child the wrapped subtree
         */
        public Focused(
                ElementId id, boolean focused, List<Binding<Msg>> bindings, InputHandler<Msg> input, View<Msg> child) {
            this(id, focused, bindings, input, child, PointerHandler.none());
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        public Focused<Msg> withFocus(boolean value) {
            return new Focused<>(id, value, bindings, input, child, pointer);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Focused<>(
                    id,
                    focused,
                    mapBindings(bindings, mapper),
                    input.map(mapper),
                    child.map(mapper),
                    pointer.map(mapper));
        }
    }

    /**
     * Declares where the caret sits and how it looks, inside the subtree it wraps.
     *
     * <p>The cursor's row and column are local to this wrapper's painted rectangle, so nesting it
     * under padding, a frame or a split pane is what moves the caret on screen. Layout resolves the
     * intent to one screen cell: the topmost eligible cursor in paint order wins, a cursor whose
     * {@link Cursor#owner() owner} is not the focused element is not eligible, and a position that
     * is clipped away or off the screen is hidden. A modal layer is a screen of its own, so only
     * its topmost layer's cursor can be shown.</p>
     *
     * <p>The wrapper is layout-transparent: it reserves no space and paints only its child, so
     * adding a cursor never changes what the content measures or where it is painted.</p>
     *
     * @param cursor the immutable caret intent; required
     * @param child the wrapped subtree
     * @param <Msg> the message type the child can produce
     */
    record CursorAt<Msg>(Cursor cursor, View<Msg> child) implements View<Msg> {

        public CursorAt {
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new CursorAt<>(cursor, child.map(mapper));
        }
    }

    /**
     * Declares the frame's terminal presentation around the subtree it wraps.
     *
     * <p>A frame draws cells; this node says what the terminal itself should be while those cells are
     * on screen - which screen buffer, whether the pointer, paste and focus reports are asked for, the
     * window title, the terminal's default colours, a taskbar progress value and a program-status
     * record. The value is {@link TerminalPresentation}, which is as terminal-free as any other view
     * data: nothing here writes, and the runtime resolves it once per painted frame and writes only
     * what changed.</p>
     *
     * <p>The intent belongs to the root of the tree: a frame may declare one presentation, and two
     * different declarations are rejected rather than resolved by paint order, so no widget can change
     * the window title or the screen buffer behind the application's back. The wrapper is
     * layout-transparent like {@link CursorAt}: it reserves no space and paints only its child, so
     * declaring a presentation never changes what the content measures or where it is painted.</p>
     *
     * <p>A {@link View.Sized} subtree is not part of the tree until layout offers it a size, so a
     * declaration inside one is not seen: state the intent at the root.</p>
     *
     * @param presentation the session presentation this frame asks for; required
     * @param child the wrapped subtree
     * @param <Msg> the message type the child can produce
     */
    record Presentation<Msg>(TerminalPresentation presentation, View<Msg> child) implements View<Msg> {

        public Presentation {
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(child, "child");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Presentation<>(presentation, child.map(mapper));
        }
    }

    /**
     * A subtree whose shape depends on the geometry it is given.
     *
     * <p>A widget that wraps or clips its own content cannot describe it before it knows how much
     * space it has, and the width it is painted at is decided by the ancestors it is placed in - a
     * share of a split, the inside of a frame, what a {@link Grow} leaves over. This node closes
     * that gap without giving the widget a terminal or a retained state: the builder is a pure
     * function of a {@link Size}, so building, measuring and painting the same view remains a
     * function of its inputs.</p>
     *
     * <p>Measurement offers the rows and columns still available and painting offers the rectangle
     * actually allocated, so a subtree that clamps its own height measures as the height it will
     * paint. A builder must not build another {@code Sized}: the geometry is offered once, which is
     * what keeps a measurement from recursing.</p>
     *
     * <p>{@link #children()} is empty because the child does not exist until a size does;
     * {@link #map} and {@link #scoped} therefore wrap the built subtree instead of transforming a
     * child that is already there.</p>
     *
     * @param build the builder, called with the offered or allocated geometry; required
     * @param <Msg> the message type the built subtree can produce
     */
    record Sized<Msg>(Function<Size, View<Msg>> build) implements View<Msg> {

        public Sized {
            Objects.requireNonNull(build, "build");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of();
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Sized<>(size -> build.apply(size).map(mapper));
        }
    }

    private static <Msg, Msg2> List<View<Msg2>> mapAll(
            List<View<Msg>> views, Function<? super Msg, ? extends Msg2> mapper) {
        List<View<Msg2>> mapped = new ArrayList<>(views.size());
        for (View<Msg> view : views) {
            mapped.add(view.map(mapper));
        }
        return List.copyOf(mapped);
    }

    private static <Msg, Msg2> List<Binding<Msg2>> mapBindings(
            List<Binding<Msg>> bindings, Function<? super Msg, ? extends Msg2> mapper) {
        List<Binding<Msg2>> mapped = new ArrayList<>(bindings.size());
        for (Binding<Msg> binding : bindings) {
            mapped.add(new Binding<>(binding.key(), mapper.apply(binding.message())));
        }
        return List.copyOf(mapped);
    }

    private static <Msg> void requireUnique(List<Binding<Msg>> bindings, String phase) {
        List<io.github.dhoard.braxton.input.Key> seen = new ArrayList<>();
        for (Binding<Msg> binding : bindings) {
            if (seen.contains(binding.key())) {
                throw new IllegalArgumentException("duplicate " + phase + " binding for " + binding.key());
            }
            seen.add(binding.key());
        }
    }
}
