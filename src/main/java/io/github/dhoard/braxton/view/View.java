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

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
                            column.items().stream().map(item -> item.scoped(path)).toList(),
                            column.align());
            case Row<Msg> row ->
                    new Row<>(
                            row.items().stream().map(item -> item.scoped(path)).toList(),
                            row.align());
            case Padding<Msg> padding ->
                    new Padding<>(padding.insets(), padding.child().scoped(path));
            case BorderBox<Msg> box ->
                    new BorderBox<>(
                            box.border(), box.title(), box.style(), box.child().scoped(path));
            case Fixed<Msg> fixed -> new Fixed<>(fixed.size(), fixed.child().scoped(path));
            case Grow<Msg> grow -> new Grow<>(grow.weight(), grow.child().scoped(path));
            case Overlay<Msg> overlay ->
                    new Overlay<>(
                            overlay.layers().stream().map(item -> item.scoped(path)).toList(),
                            overlay.modal());
            case Keyed<Msg> keyed ->
                    new Keyed<>(
                            keyed.capture(),
                            keyed.intercept(),
                            keyed.child().scoped(path),
                            keyed.bubble());
            case Focused<Msg> focused ->
                    new Focused<>(
                            focused.id().scoped(path),
                            focused.focused(),
                            focused.bindings(),
                            focused.input(),
                            focused.child().scoped(path));
        };
    }

    /** Handles one input event, returning a message when the event is consumed. */
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

    /** Styled text. The text wraps to the available width during layout. */
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

    /** Occupies no space; useful as an explicit empty branch. */
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

    /** Adds asymmetric spacing around one child. */
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

    /** Frames one child; an optional title is drawn into the top edge. */
    record BorderBox<Msg>(Border border, StyledText title, Style style, View<Msg> child)
            implements View<Msg> {

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

    /** Reserves exactly {@code size} cells on the parent's main axis. */
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

    /** Takes a share of the parent's remaining main-axis space. */
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
     */
    record Keyed<Msg>(List<Binding<Msg>> capture, InputHandler<Msg> intercept, View<Msg> child,
            List<Binding<Msg>> bubble) implements View<Msg> {

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
            return new Keyed<>(mapBindings(capture, mapper), intercept.map(mapper),
                    child.map(mapper), mapBindings(bubble, mapper));
        }
    }

    /**
     * Declares a focus target: its stable identity, whether it currently owns focus, its local key
     * bindings, and the handler for events its bindings do not consume.
     */
    record Focused<Msg>(ElementId id, boolean focused, List<Binding<Msg>> bindings,
            InputHandler<Msg> input, View<Msg> child) implements View<Msg> {

        public Focused {
            Objects.requireNonNull(id, "id");
            bindings = List.copyOf(bindings);
            input = input == null ? InputHandler.none() : input;
            Objects.requireNonNull(child, "child");
            requireUnique(bindings, "focus");
        }

        @Override
        public List<View<Msg>> children() {
            return List.of(child);
        }

        public Focused<Msg> withFocus(boolean value) {
            return new Focused<>(id, value, bindings, input, child);
        }

        @Override
        public <Msg2> View<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Focused<>(id, focused, mapBindings(bindings, mapper), input.map(mapper),
                    child.map(mapper));
        }
    }

    private static <Msg, Msg2> List<View<Msg2>> mapAll(List<View<Msg>> views,
            Function<? super Msg, ? extends Msg2> mapper) {
        List<View<Msg2>> mapped = new ArrayList<>(views.size());
        for (View<Msg> view : views) {
            mapped.add(view.map(mapper));
        }
        return List.copyOf(mapped);
    }

    private static <Msg, Msg2> List<Binding<Msg2>> mapBindings(List<Binding<Msg>> bindings,
            Function<? super Msg, ? extends Msg2> mapper) {
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
                throw new IllegalArgumentException(
                        "duplicate " + phase + " binding for " + binding.key());
            }
            seen.add(binding.key());
        }
    }
}
