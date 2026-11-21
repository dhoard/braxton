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

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The small open kernel behind the widget API: a leaf owns a widget and a model, a group owns
 * children in one direction. New widgets need no change to this type.
 *
 * <p>Composition is data. A leaf is a widget and the model it paints; a group is its children and
 * the axis it places them along; a modal is a layer that owns the screen while it is present. The
 * engine walks that tree, so a new kind of widget - a chart, a form, an application's own control -
 * is a {@link Widget} implementation and never a new case in a switch.</p>
 *
 * <p>Each element states its {@link #sizing()} on the main axis of the group that holds it, and a
 * group states its own sizing for the group above it, so a nested composition is sized like any
 * other child. The cross axis is the parent's: a row gives every child its height and the group's
 * {@link Group#align() alignment} says where a shorter child sits in it.</p>
 *
 * @param <Msg> the message type the tree emits
 */
public sealed interface Element<Msg> permits Element.Leaf, Element.Group, Element.Stack, Element.Pad, Element.Modal {

    /**
     * How this element takes its share of its parent's main axis.
     *
     * @return the sizing
     */
    Sizing sizing();

    /**
     * The same tree emitting a different message type.
     *
     * <p>Composition needs it: a child component speaks its own messages and its parent speaks
     * another, so the parent maps the child's tree while it places it. Mapping cannot change
     * geometry, focus or the caret, because it only lifts what the leaves emit.</p>
     *
     * @param <Msg2> the message type the mapped tree emits
     * @param mapper maps every message this tree emits; required
     * @return the mapped tree
     */
    <Msg2> Element<Msg2> map(Function<? super Msg, ? extends Msg2> mapper);

    /**
     * The children this element places, in paint order; empty for a leaf.
     *
     * @return the children this element places, in paint order; empty for a leaf
     */
    List<Element<Msg>> children();

    /**
     * One widget and its current model.
     *
     * @param <M> the widget's model type
     * @param <Msg> the message type the tree emits
     * @param widget the widget; required
     * @param model the widget's model, which may be null for a widget without one
     * @param sizing how the widget is sized in its parent
     */
    record Leaf<M, Msg>(Widget<M, Msg> widget, M model, Sizing sizing) implements Element<Msg> {

        /**
         * Rejects a missing widget and substitutes the measured sizing for a missing one.
         */
        public Leaf {
            if (widget == null) {
                throw new IllegalArgumentException("widget is required");
            }
            sizing = sizing == null ? Sizing.measured() : sizing;
        }

        /**
         * A widget that asks for the cells it measures.
         *
         * @param widget the widget; required
         * @param model the widget's model
         */
        public Leaf(Widget<M, Msg> widget, M model) {
            this(widget, model, Sizing.measured());
        }

        /**
         * This element taking its share of its parent's main axis another way.
         *
         * @param value the sizing; null keeps the current one
         * @return a leaf with the same widget and model
         */
        public Leaf<M, Msg> withSizing(Sizing value) {
            return new Leaf<>(widget, model, value == null ? sizing : value);
        }

        @Override
        public <Msg2> Element<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Leaf<>(new Mapped<>(widget, mapper), model, sizing);
        }

        @Override
        public List<Element<Msg>> children() {
            return List.of();
        }
    }

    /**
     * Children placed along one axis, sharing the parent's space.
     *
     * <p>The main axis is the direction's own: a row hands its children the columns they asked for
     * and a column hands them the rows they asked for. The cross axis follows the direction too, and
     * not symmetrically. A column is a block - a start-aligned child spans its width, so a frame, a
     * fill or a line of text fills the pane it is in, and a centered or end-aligned child keeps the
     * width it measured and sits where its alignment puts it. A row's children keep the height they
     * measured and sit at their alignment's offset, because stretching a row's children vertically
     * would give a one-line label a pane's height.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param children the children, in paint order
     * @param direction the axis the children are placed along; required
     * @param align where a child smaller than the cross axis sits; never null
     * @param sizing how the group is sized in its parent
     */
    record Group<Msg>(List<Element<Msg>> children, Direction direction, Alignment align, Sizing sizing)
            implements Element<Msg> {

        /**
         * Rejects a missing direction and substitutes the defaults for the rest.
         */
        public Group {
            if (direction == null) {
                throw new IllegalArgumentException("direction is required");
            }
            children = children == null ? List.of() : List.copyOf(children);
            align = align == null ? Alignment.START : align;
            sizing = sizing == null ? Sizing.measured() : sizing;
        }

        /**
         * A start-aligned group that asks for the cells it measures.
         *
         * @param children the children, in paint order
         * @param direction the axis the children are placed along; required
         */
        public Group(List<Element<Msg>> children, Direction direction) {
            this(children, direction, Alignment.START, Sizing.measured());
        }

        /**
         * A group with its own alignment that asks for the cells it measures.
         *
         * @param children the children, in paint order
         * @param direction the axis the children are placed along; required
         * @param align where a smaller child sits on the cross axis
         */
        public Group(List<Element<Msg>> children, Direction direction, Alignment align) {
            this(children, direction, align, Sizing.measured());
        }

        /**
         * This element taking its share of its parent's main axis another way.
         *
         * @param value the sizing; null keeps the current one
         * @return a group with the same children and alignment
         */
        public Group<Msg> withSizing(Sizing value) {
            return new Group<>(children, direction, align, value == null ? sizing : value);
        }

        @Override
        public <Msg2> Element<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Group<>(mapAll(children, mapper), direction, align, sizing);
        }

        @Override
        public List<Element<Msg>> children() {
            return children;
        }
    }

    /**
     * Children that share one rectangle, painted in declaration order.
     *
     * <p>A stack is how a decoration and the content it surrounds are composed: the decoration is
     * declared first and paints the whole rectangle, and the content is placed inside it - usually
     * through {@link Pad} - so the two never have to agree on a size. The last child is on top and
     * is what a pointer reaches first.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param children the children, in paint order
     * @param sizing how the stack is sized in its parent
     */
    record Stack<Msg>(List<Element<Msg>> children, Sizing sizing) implements Element<Msg> {

        /**
         * Rejects nothing: a stack of no children occupies no cells.
         */
        public Stack {
            children = children == null ? List.of() : List.copyOf(children);
            sizing = sizing == null ? Sizing.measured() : sizing;
        }

        /**
         * A stack that asks for the cells it measures.
         *
         * @param children the children, in paint order
         */
        public Stack(List<Element<Msg>> children) {
            this(children, Sizing.measured());
        }

        /**
         * This element taking its share of its parent's main axis another way.
         *
         * @param value the sizing; null keeps the current one
         * @return a stack with the same children
         */
        public Stack<Msg> withSizing(Sizing value) {
            return new Stack<>(children, value == null ? sizing : value);
        }

        @Override
        public <Msg2> Element<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Stack<>(mapAll(children, mapper), sizing);
        }

        @Override
        public List<Element<Msg>> children() {
            return children;
        }
    }

    /**
     * One child inside the insets that surround it.
     *
     * @param <Msg> the message type the tree emits
     * @param insets the space kept clear on each side of the child; never null
     * @param child the child to place; required
     * @param sizing how the padding is sized in its parent
     */
    record Pad<Msg>(Insets insets, Element<Msg> child, Sizing sizing) implements Element<Msg> {

        /**
         * Rejects a missing child and substitutes no insets and the measured sizing.
         */
        public Pad {
            if (child == null) {
                throw new IllegalArgumentException("child is required");
            }
            insets = insets == null ? Insets.NONE : insets;
            sizing = sizing == null ? Sizing.measured() : sizing;
        }

        /**
         * Padding that asks for the cells it measures.
         *
         * @param insets the space kept clear on each side of the child
         * @param child the child to place; required
         */
        public Pad(Insets insets, Element<Msg> child) {
            this(insets, child, Sizing.measured());
        }

        /**
         * This element taking its share of its parent's main axis another way.
         *
         * @param value the sizing; null keeps the current one
         * @return padding with the same insets and child
         */
        public Pad<Msg> withSizing(Sizing value) {
            return new Pad<>(insets, child, value == null ? sizing : value);
        }

        @Override
        public <Msg2> Element<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Pad<>(insets, child.map(mapper), sizing);
        }

        @Override
        public List<Element<Msg>> children() {
            return List.of(child);
        }
    }

    /**
     * A modal layer: only its child can be hit or take focus while it is present.
     *
     * @param <Msg> the message type the tree emits
     * @param child the layer's content; required
     */
    record Modal<Msg>(Element<Msg> child) implements Element<Msg> {

        /**
         * Rejects a missing child.
         */
        public Modal {
            if (child == null) {
                throw new IllegalArgumentException("child is required");
            }
        }

        @Override
        public Sizing sizing() {
            return Sizing.measured();
        }

        @Override
        public <Msg2> Element<Msg2> map(Function<? super Msg, ? extends Msg2> mapper) {
            return new Modal<>(child.map(mapper));
        }

        @Override
        public List<Element<Msg>> children() {
            return List.of(child);
        }
    }

    private static <Msg, Msg2> List<Element<Msg2>> mapAll(
            List<Element<Msg>> elements, Function<? super Msg, ? extends Msg2> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        List<Element<Msg2>> mapped = new ArrayList<>(elements.size());
        for (Element<Msg> element : elements) {
            mapped.add(element.map(mapper));
        }
        return List.copyOf(mapped);
    }

    /**
     * Group placement direction.
     */
    enum Direction {
        /**
         * Places children in a horizontal row.
         */
        ROW,
        /**
         * Places children in a vertical column.
         */
        COLUMN
    }
}
