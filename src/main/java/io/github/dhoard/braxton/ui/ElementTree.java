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

import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Theme;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Measures, arranges, paints and hit-tests an {@link Element} tree.
 *
 * <p>The phases are the ones a constraint-based layout needs and the order they must happen in:
 * sizes are solved for a group's main axis before any child is arranged, each child is measured
 * across the axis with the size it was given, and only then is anything painted. A frame caches each
 * measurement by element and constraint and paints the rectangles it resolved, so a child can never
 * paint in a rectangle its measurement did not see.</p>
 *
 * <p>An element's {@link Sizing} is what it asks for on its parent's main axis: a measured element
 * gets what it measures, a fixed one gets exactly its cells, and a grown one gets a share of what is
 * left, by weight and in declaration order for the remainder. The cross axis belongs to the
 * parent - a row gives every child its own height - and {@link Element.Group#align()} places a
 * child that is smaller than that axis. Every rectangle is clipped to the space it was offered, so
 * no child can paint outside its parent however large it says it is.</p>
 */
public final class ElementTree {

    private ElementTree() {}

    /**
     * One resolved element and its screen rectangle.
     *
     * @param <Msg> the message type
     * @param element the root element
     * @param bounds the allocated cell rectangle
     * @param children the children in declaration order
     */
    public record Placement<Msg>(Element<Msg> element, Rect bounds, List<Placement<Msg>> children) {
        /**
         * Creates a {@code Placement} value.
         *
         * @param element the root element
         * @param bounds the allocated cell rectangle
         * @param children the children in declaration order
         */
        public Placement {
            children = List.copyOf(children);
        }
    }

    /**
     * Resolves the full tree for one frame.
     *
     * @param <Msg> the message type
     * @param root the root element tree
     * @param bounds the allocated cell rectangle
     * @return the resolved tree for this frame
     */
    public static <Msg> Placement<Msg> layout(Element<Msg> root, Rect bounds) {
        return new Layout().place(root, bounds);
    }

    /**
     * The size an element wants when it is offered {@code max}.
     *
     * <p>A group sums its children's main-axis sizes - a grown child contributes nothing, because it
     * asks for what is left rather than for a size of its own - and takes the widest or the tallest
     * child across the axis. The answer is never larger than {@code max}.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param element the element to measure; required
     * @param max the space the element is offered
     * @return the size it wants, inside {@code max}
     */
    public static <Msg> Size measure(Element<Msg> element, Size max) {
        return new Layout().measure(element, max);
    }

    /**
     * Frame-local memoization: equal constraints reuse a size, and no model survives the frame.
     */
    private static final class Layout {
        private final Map<Element<?>, Map<Size, Size>> sizes = new IdentityHashMap<>();

        private <Msg> Size measure(Element<Msg> element, Size max) {
            Map<Size, Size> constraints = sizes.computeIfAbsent(element, ignored -> new HashMap<>());
            Size cached = constraints.get(max);
            if (cached != null) {
                return cached;
            }
            Size measured = uncached(element, max);
            constraints.put(max, measured);
            return measured;
        }

        private <Msg> Size uncached(Element<Msg> element, Size max) {
            int rows = Math.max(0, max.rows());
            int cols = Math.max(0, max.cols());
            return switch (element) {
                case Element.Leaf<?, Msg> leaf ->
                    clamped(leaf.widget().measure(new Size(rows, cols), leafModel(leaf)), rows, cols);
                case Element.Group<Msg> group -> measureGroup(group, rows, cols);
                case Element.Stack<Msg> stack -> measureStack(stack, rows, cols);
                case Element.Pad<Msg> pad -> measurePad(pad, rows, cols);
                case Element.Modal<Msg> modal -> measure(modal.child(), new Size(rows, cols));
            };
        }

        private <Msg> Size measureGroup(Element.Group<Msg> group, int rows, int cols) {
            if (group.children().isEmpty()) {
                return new Size(0, 0);
            }
            boolean row = group.direction() == Element.Direction.ROW;
            int main = row ? cols : rows;
            int[] sizes = measureSizes(group, new Size(rows, cols), main);
            int cross = 0;
            int used = 0;
            for (int index = 0; index < group.children().size(); index++) {
                Element<Msg> child = group.children().get(index);
                if (child instanceof Element.Modal<Msg>) {
                    continue;
                }
                Size childSize = row
                        ? measure(child, new Size(rows, sizes[index]))
                        : measure(child, new Size(sizes[index], cols));
                cross = Math.max(cross, row ? childSize.rows() : childSize.cols());
                used += sizes[index];
            }
            int crossSize = Math.min(group.direction() == Element.Direction.ROW ? rows : cols, cross);
            return row ? new Size(crossSize, Math.min(cols, used)) : new Size(Math.min(rows, used), crossSize);
        }

        /**
         * The main-axis sizes one group gives its children.
         *
         * <p>Children are served in declaration order: a measured child takes what it measures and a
         * fixed one takes its cells, each clipped to the space still unclaimed, and the grown children
         * then share what is left by weight, with the earliest children taking the remainder cells. That
         * is the rule the cell-budget containers of the layout package use, and it is stated here
         * because a row that overflowed must clip the child that does not fit rather than rescale the
         * children that do.</p>
         */
        private <Msg> int[] solveMain(Element.Group<Msg> group, Size max, int available) {
            List<Element<Msg>> children = group.children();
            int[] sizes = new int[children.size()];
            boolean row = group.direction() == Element.Direction.ROW;
            int space = Math.max(0, available);
            int used = 0;
            long weight = 0;
            for (int index = 0; index < children.size(); index++) {
                Element<Msg> child = children.get(index);
                if (child instanceof Element.Modal<Msg>) {
                    continue;
                }
                Sizing sizing = child.sizing();
                if (sizing.isGrow()) {
                    weight += sizing.value();
                    continue;
                }
                int wanted = sizing.isFixed()
                        ? sizing.value()
                        : (row
                                ? measure(child, max).cols()
                                : measure(child, max).rows());
                sizes[index] = Math.max(0, Math.min(wanted, space - used));
                used += sizes[index];
            }
            int remaining = space - used;
            if (weight > 0 && remaining > 0) {
                int distributed = 0;
                for (int index = 0; index < children.size(); index++) {
                    if (children.get(index).sizing().isGrow()) {
                        sizes[index] = (int)
                                (remaining * (long) children.get(index).sizing().value() / weight);
                        distributed += sizes[index];
                    }
                }
                // The cells the division leaves over go one each to the earliest grown children, so the
                // division is total and a row always fills the width it was given.
                int remainder = remaining - distributed;
                for (int index = 0; index < children.size() && remainder > 0; index++) {
                    if (children.get(index).sizing().isGrow()) {
                        sizes[index]++;
                        remainder--;
                    }
                }
            }
            return sizes;
        }

        /**
         * Above this many cells the offer is an intrinsic probe rather than a real rectangle.
         */
        private static final int UNBOUNDED = 1 << 20;

        /**
         * The main-axis sizes a group wants.
         *
         * <p>A real offer is divided exactly as an arrangement would divide it, so a group measured at
         * 14 columns reports 14. An intrinsic probe has no real offer, and there a grown child answers
         * with its content: reporting the probe itself would make the intrinsic size of any group
         * holding one unbounded, which is not a size a container can answer with.</p>
         */
        private <Msg> int[] measureSizes(Element.Group<Msg> group, Size max, int available) {
            List<Element<Msg>> children = group.children();
            int[] sizes = new int[children.size()];
            boolean row = group.direction() == Element.Direction.ROW;
            int space = Math.max(0, available);
            if (space < UNBOUNDED) {
                return solveMain(group, max, space);
            }
            int used = 0;
            for (int index = 0; index < children.size(); index++) {
                Element<Msg> child = children.get(index);
                if (child instanceof Element.Modal<Msg>) {
                    continue;
                }
                Sizing sizing = child.sizing();
                Size measured = measure(child, max);
                int wanted = sizing.isFixed() ? sizing.value() : (row ? measured.cols() : measured.rows());
                sizes[index] = Math.max(0, Math.min(wanted, space - used));
                used += sizes[index];
            }
            return sizes;
        }

        private <Msg> Placement<Msg> place(Element<Msg> element, Rect bounds) {
            return switch (element) {
                case Element.Leaf<?, Msg> leaf -> placeLeaf(leaf, bounds);
                case Element.Modal<Msg> modal ->
                    new Placement<>(element, bounds, List.of(place(modal.child(), bounds)));
                case Element.Group<Msg> group -> placeGroup(group, bounds);
                case Element.Stack<Msg> stack ->
                    new Placement<>(
                            stack,
                            bounds,
                            stack.children().stream()
                                    .map(child -> place(child, bounds))
                                    .toList());
                case Element.Pad<Msg> pad -> placePad(pad, bounds);
            };
        }

        /**
         * A stack is as large as its largest child, and asks every child for the whole space.
         */
        private <Msg> Size measureStack(Element.Stack<Msg> stack, int rows, int cols) {
            int wantedRows = 0;
            int wantedCols = 0;
            for (Element<Msg> child : stack.children()) {
                Size measured = measure(child, new Size(rows, cols));
                wantedRows = Math.max(wantedRows, measured.rows());
                wantedCols = Math.max(wantedCols, measured.cols());
            }
            return new Size(wantedRows, wantedCols);
        }

        /**
         * Padding adds the insets around what the child measures, inside the space it is offered.
         */
        private <Msg> Size measurePad(Element.Pad<Msg> pad, int rows, int cols) {
            Insets insets = pad.insets();
            int innerRows = Math.max(0, rows - insets.vertical());
            int innerCols = Math.max(0, cols - insets.horizontal());
            Size child = measure(pad.child(), new Size(innerRows, innerCols));
            return new Size(
                    Math.min(rows, child.rows() + insets.vertical()),
                    Math.min(cols, child.cols() + insets.horizontal()));
        }

        private <Msg> Placement<Msg> placePad(Element.Pad<Msg> pad, Rect bounds) {
            Insets insets = pad.insets();
            Rect inner = new Rect(
                            bounds.row() + insets.top(),
                            bounds.col() + insets.left(),
                            Math.max(0, bounds.rows() - insets.vertical()),
                            Math.max(0, bounds.cols() - insets.horizontal()))
                    .clip(bounds);
            return new Placement<>(pad, bounds, List.of(place(pad.child(), inner)));
        }

        private <Msg> Placement<Msg> placeGroup(Element.Group<Msg> group, Rect bounds) {
            if (group.children().isEmpty() || bounds.isEmpty()) {
                return new Placement<>(group, bounds, List.of());
            }
            boolean row = group.direction() == Element.Direction.ROW;
            int main = row ? bounds.cols() : bounds.rows();
            int[] sizes = solveMain(group, new Size(bounds.rows(), bounds.cols()), main);
            List<Placement<Msg>> placed = new ArrayList<>(group.children().size());
            int cursor = 0;
            for (int index = 0; index < group.children().size(); index++) {
                Element<Msg> child = group.children().get(index);
                if (child instanceof Element.Modal<Msg>) {
                    // A modal layer covers the group it is declared in and takes no space in it, so the
                    // layers under it lay out exactly as they would without it.
                    placed.add(place(child, bounds));
                    continue;
                }
                int size = sizes[index];
                Rect childBounds;
                if (row) {
                    // A row's main axis is its columns: the solver names each child's width, and the
                    // child's measured height is placed inside the row's own height.
                    int wanted = Math.min(
                            bounds.rows(),
                            measure(child, new Size(bounds.rows(), size)).rows());
                    int offset = crossOffset(group.align(), bounds.rows(), wanted);
                    childBounds = new Rect(bounds.row() + offset, bounds.col() + cursor, wanted, size);
                } else {
                    int measured = group.align() == Alignment.START
                            ? bounds.cols()
                            : Math.min(
                                    bounds.cols(),
                                    measure(child, new Size(size, bounds.cols()))
                                            .cols());
                    // A column is a block: a start-aligned child spans its width, so a frame, a fill or a
                    // line of text fills the pane it is in. A centered or end-aligned child keeps the width
                    // it measured and sits at the aligned offset, which is the only way that alignment
                    // can be seen. A row is not a block on the vertical axis: its children sit at their
                    // own height, aligned as their group says.
                    int wanted = group.align() == Alignment.START ? bounds.cols() : measured;
                    int offset = crossOffset(group.align(), bounds.cols(), wanted);
                    childBounds = new Rect(bounds.row() + cursor, bounds.col() + offset, size, wanted);
                }
                placed.add(place(child, childBounds.clip(bounds)));
                cursor += size;
            }
            return new Placement<>(group, bounds, placed);
        }

        /**
         * Where a child smaller than the cross axis sits along it.
         */
        private int crossOffset(Alignment align, int cross, int wanted) {
            return switch (align) {
                case START -> 0;
                case CENTER -> Math.max(0, (cross - wanted) / 2);
                case END -> Math.max(0, cross - wanted);
            };
        }

        private <M, Msg> Placement<Msg> placeLeaf(Element.Leaf<M, Msg> leaf, Rect bounds) {
            Size measured = measure(leaf, new Size(bounds.rows(), bounds.cols()));
            Rect arranged =
                    leaf.widget().arrange(bounds, measured, leaf.model()).clip(bounds);
            return new Placement<>(leaf, arranged, List.of());
        }
    }

    /**
     * Paints every leaf in placement order.
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @param surface the clipped surface to paint
     */
    public static <Msg> void paint(Placement<Msg> placement, Surface surface) {
        if (placement.element() instanceof Element.Leaf<?, Msg> leaf) {
            paintLeaf(leaf, placement.bounds(), surface);
            return;
        }
        for (Placement<Msg> child : placement.children()) paint(child, surface);
    }

    private static <M, Msg> void paintLeaf(Element.Leaf<M, Msg> leaf, Rect bounds, Surface surface) {
        leaf.widget().paint(surface, bounds, leaf.model());
    }

    /**
     * Paints using a theme; themed widgets receive it, other widgets keep their own styles.
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @param surface the clipped surface to paint
     * @param theme the resolved application theme
     */
    public static <Msg> void paint(Placement<Msg> placement, Surface surface, Theme theme) {
        if (placement.element() instanceof Element.Leaf<?, Msg> leaf) {
            paintThemedLeaf(leaf, placement.bounds(), surface, theme);
            return;
        }
        for (Placement<Msg> child : placement.children()) paint(child, surface, theme);
    }

    private static <M, Msg> void paintThemedLeaf(Element.Leaf<M, Msg> leaf, Rect bounds, Surface surface, Theme theme) {
        if (leaf.widget() instanceof ThemedWidget<M, Msg> themed) {
            themed.paint(surface, bounds, leaf.model(), theme);
        } else {
            leaf.widget().paint(surface, bounds, leaf.model());
        }
    }

    /**
     * Returns the topmost placed leaf whose widget accepts the cell, or null.
     *
     * <p>A modal child is a scope of its own: it is asked first, and once it has been consulted the
     * layers under it are not, whether or not the modal paints the cell. A dialog traps the pointer
     * over the whole rectangle it was given, not only over the cells its own content happens to
     * cover - a click in the gap beside a dialog belongs to nobody rather than to the pane behind
     * it.</p>
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the topmost placed leaf whose widget accepts the cell, or null
     */
    public static <Msg> Placement<Msg> hitPlacement(Placement<Msg> placement, int row, int col) {
        if (placement.element() instanceof Element.Modal<Msg>) {
            if (placement.children().isEmpty()) return null;
            return hitPlacement(placement.children().get(0), row, col);
        }
        for (int i = placement.children().size() - 1; i >= 0; i--) {
            Placement<Msg> child = placement.children().get(i);
            Placement<Msg> found = hitPlacement(child, row, col);
            if (found != null) return found;
            if (child.element() instanceof Element.Modal<Msg>) return null;
        }
        if (placement.element() instanceof Element.Leaf<?, Msg> leaf && accepts(leaf, placement.bounds(), row, col))
            return placement;
        return null;
    }

    /**
     * Returns the topmost leaf whose widget accepts the cell, or null.
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @param row the zero-based row
     * @param col the zero-based column
     * @return the topmost leaf whose widget accepts the cell, or null
     */
    public static <Msg> Element.Leaf<?, Msg> hit(Placement<Msg> placement, int row, int col) {
        Placement<Msg> found = hitPlacement(placement, row, col);
        return found == null ? null : (Element.Leaf<?, Msg>) found.element();
    }

    private static <M, Msg> boolean accepts(Element.Leaf<M, Msg> leaf, Rect bounds, int row, int col) {
        return leaf.widget().hit(bounds, row, col, leaf.model());
    }

    /**
     * Returns every leaf whose widget reports focus, topmost first.
     *
     * <p>Focus is read from the model, so this is the whole answer to "who owns the keyboard": the
     * first leaf here is the one a key belongs to. A modal child owns its scope, so a leaf the layer
     * covers is not reported however deeply it is nested.</p>
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @return every leaf whose widget reports focus, topmost first
     */
    public static <Msg> List<Element.Leaf<?, Msg>> focused(Placement<Msg> placement) {
        List<Element.Leaf<?, Msg>> found = new ArrayList<>();
        collectFocused(placement, found);
        return List.copyOf(found);
    }

    private static <Msg> void collectFocused(Placement<Msg> placement, List<Element.Leaf<?, Msg>> found) {
        for (Placement<Msg> child : placement.children()) {
            if (child.element() instanceof Element.Modal<Msg>) {
                // A modal child owns its scope: everything else this placement holds is covered by
                // it, so a leaf behind the layer cannot hold focus however deeply it is nested.
                collectFocused(child, found);
                return;
            }
        }
        for (int i = placement.children().size() - 1; i >= 0; i--)
            collectFocused(placement.children().get(i), found);
        if (placement.element() instanceof Element.Leaf<?, Msg> leaf && focusedLeaf(leaf)) found.add(leaf);
    }

    private static <M, Msg> boolean focusedLeaf(Element.Leaf<M, Msg> leaf) {
        return leaf.widget().focused(leaf.model());
    }

    /**
     * Delivers a pointer gesture to the hit leaf, translated to its screen rectangle.
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @param pointer the normalized pointer report
     * @param emit the consumer of typed messages
     */
    public static <Msg> void pointer(Placement<Msg> placement, PointerEvent pointer, Consumer<Msg> emit) {
        Placement<Msg> found = hitPlacement(placement, pointer.row(), pointer.column());
        if (found == null) return;
        Element.Leaf<?, Msg> leaf = (Element.Leaf<?, Msg>) found.element();
        pointerLeaf(leaf, pointer, found.bounds(), emit);
    }

    private static <M, Msg> void pointerLeaf(
            Element.Leaf<M, Msg> leaf, PointerEvent pointer, Rect bounds, Consumer<Msg> emit) {
        leaf.widget().onPointer(pointer, bounds, leaf.model(), emit);
    }

    @SuppressWarnings("unchecked")
    private static <M, Msg> M leafModel(Element.Leaf<?, Msg> leaf) {
        return ((Element.Leaf<M, Msg>) leaf).model();
    }

    private static Size clamped(Size size, int rows, int cols) {
        Size value = size == null ? new Size(0, 0) : size;
        return new Size(Math.max(0, Math.min(value.rows(), rows)), Math.max(0, Math.min(value.cols(), cols)));
    }
}
