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

import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sizing, alignment and measurement of the element kernel.
 */
class ElementLayoutTest {

    /**
     * A widget that measures itself, fills the rectangle it is given and paints nothing.
     */
    private record Sized(int rows, int cols) implements Widget<Sized, String> {

        @Override
        public Size measure(Size max, Sized model) {
            return new Size(Math.min(model.rows(), max.rows()), Math.min(model.cols(), max.cols()));
        }

        @Override
        public Rect arrange(Rect available, Size measured, Sized model) {
            return available;
        }

        @Override
        public void paint(Surface surface, Rect bounds, Sized model) {}
    }

    private static Element<String> leaf(int rows, int cols, Sizing sizing) {
        return new Element.Leaf<>(new Sized(rows, cols), new Sized(rows, cols), sizing);
    }

    private static Element<String> leaf(int rows, int cols) {
        return leaf(rows, cols, Sizing.measured());
    }

    private static ElementTree.Placement<String> layout(Element<String> root, int rows, int cols) {
        return ElementTree.layout(root, new Rect(0, 0, rows, cols));
    }

    private static Rect bounds(ElementTree.Placement<String> placement, int index) {
        return placement.children().get(index).bounds();
    }

    @Test
    void measurementIsCachedByElementAndConstraintOnlyWithinOneLayout() {
        java.util.Map<Size, Integer> calls = new java.util.HashMap<>();
        Widget<String, String> widget = new Widget<>() {
            public Size measure(Size max, String model) {
                calls.merge(max, 1, Integer::sum);
                return new Size(Math.min(1, max.rows()), Math.min(4, max.cols()));
            }

            public void paint(Surface surface, Rect bounds, String model) {}
        };
        Element<String> leaf = new Element.Leaf<>(widget, "value");
        Element<String> root = new Element.Group<>(
                List.of(new Element.Group<>(List.of(leaf), Element.Direction.COLUMN)), Element.Direction.COLUMN);
        layout(root, 4, 20);
        assertThat(calls.values()).allMatch(count -> count == 1);
        java.util.Map<Size, Integer> first = new java.util.HashMap<>(calls);
        layout(root, 4, 20);
        first.forEach((max, count) -> assertThat(calls.get(max)).isEqualTo(count * 2));
    }

    @Test
    void aRowDividesTheMainAxisByTheSizingEachChildDeclares() {
        Element<String> root = new Element.Group<>(
                List.of(leaf(1, 4, Sizing.fixed(4)), leaf(1, 2, Sizing.grow()), leaf(1, 6, Sizing.grow(2))),
                Element.Direction.ROW);
        var placed = layout(root, 2, 14);

        // Four cells are fixed, so the two grown children share the ten that are left one to two.
        assertThat(bounds(placed, 0)).isEqualTo(new Rect(0, 0, 1, 4));
        assertThat(bounds(placed, 1)).isEqualTo(new Rect(0, 4, 1, 4));
        assertThat(bounds(placed, 2)).isEqualTo(new Rect(0, 8, 1, 6));
        assertThat(ElementTree.measure(root, new Size(2, 14)).cols()).isEqualTo(14);
    }

    @Test
    void aMeasuredChildAsksForWhatItMeasures() {
        Element<String> root = new Element.Group<>(List.of(leaf(1, 5), leaf(1, 3)), Element.Direction.ROW);
        var placed = layout(root, 4, 20);

        assertThat(bounds(placed, 0)).isEqualTo(new Rect(0, 0, 1, 5));
        assertThat(bounds(placed, 1)).isEqualTo(new Rect(0, 5, 1, 3));
    }

    @Test
    void theCrossAxisAlignerPlacesAShorterChild() {
        Element<String> start =
                new Element.Group<>(List.of(leaf(1, 4, Sizing.grow())), Element.Direction.ROW, Alignment.START);
        Element<String> center =
                new Element.Group<>(List.of(leaf(1, 4, Sizing.grow())), Element.Direction.ROW, Alignment.CENTER);
        Element<String> end =
                new Element.Group<>(List.of(leaf(1, 4, Sizing.grow())), Element.Direction.ROW, Alignment.END);

        assertThat(bounds(layout(start, 5, 4), 0).row()).isZero();
        assertThat(bounds(layout(center, 5, 4), 0).row()).isEqualTo(2);
        assertThat(bounds(layout(end, 5, 4), 0).row()).isEqualTo(4);
    }

    @Test
    void aColumnStacksItsChildrenAndAlignmentMovessThemAcross() {
        Element<String> root = new Element.Group<>(
                List.of(leaf(1, 3, Sizing.fixed(1)), leaf(1, 2, Sizing.grow())),
                Element.Direction.COLUMN,
                Alignment.END);
        var placed = layout(root, 6, 10);

        assertThat(bounds(placed, 0)).isEqualTo(new Rect(0, 7, 1, 3));
        assertThat(bounds(placed, 1)).isEqualTo(new Rect(1, 8, 5, 2));
    }

    @Test
    void everyRectangleIsClippedToTheSpaceItWasOffered() {
        Element<String> root = new Element.Group<>(
                List.of(leaf(3, 30, Sizing.fixed(30)), leaf(3, 5, Sizing.fixed(5))), Element.Direction.ROW);
        var placed = layout(root, 2, 12);

        // The row serves its children in order: the fixed thirty takes the twelve cells there are and
        // the child behind it is clipped to nothing rather than the two widths being rescaled.
        assertThat(bounds(placed, 0)).isEqualTo(new Rect(0, 0, 2, 12));
        assertThat(bounds(placed, 1)).isEqualTo(new Rect(0, 12, 2, 0));
    }

    @Test
    void aModalCoversItsGroupAndTakesNoSpaceInIt() {
        Element<String> base = leaf(2, 6, Sizing.grow());
        Element<String> dialog = leaf(2, 4, Sizing.grow());
        Element<String> root =
                new Element.Group<>(List.of(base, new Element.Modal<>(dialog)), Element.Direction.COLUMN);
        var placed = layout(root, 8, 10);

        // The column is a block: its start-aligned children span its width, so the base covers the
        // rectangle the dialog is about to cover.
        assertThat(bounds(placed, 0)).isEqualTo(new Rect(0, 0, 8, 10));
        assertThat(bounds(placed, 1)).isEqualTo(new Rect(0, 0, 8, 10));
        assertThat(ElementTree.hit(placed, 1, 1)).isSameAs(dialog);
    }

    @Test
    void aStackLaysEveryChildOverTheSameRectangleAndPaintsThemInOrder() {
        Element<String> back = leaf(2, 6, Sizing.grow());
        Element<String> front = leaf(1, 3, Sizing.grow());
        Element<String> root = new Element.Stack<>(List.of(back, front));
        var placed = layout(root, 3, 9);

        assertThat(placed.children())
                .extracting(ElementTree.Placement::bounds)
                .containsExactly(new Rect(0, 0, 3, 9), new Rect(0, 0, 3, 9));
        assertThat(ElementTree.measure(root, new Size(3, 9))).isEqualTo(new Size(2, 6));
    }

    @Test
    void theLastLayerOfAStackIsWhatAPointerReachesFirst() {
        Element<String> back = leaf(1, 4, Sizing.grow());
        Element<String> front = leaf(1, 4, Sizing.grow());
        var placed = layout(new Element.Stack<>(List.of(back, front)), 2, 4);

        assertThat(ElementTree.hit(placed, 0, 1)).isSameAs(front);
    }

    @Test
    void paddingKeepsItsInsetsClearOfTheChild() {
        Element<String> root = new Element.Pad<>(new Insets(1, 2, 1, 1), leaf(1, 2, Sizing.grow()));
        var placed = layout(root, 6, 10);

        assertThat(placed.children().get(0).bounds()).isEqualTo(new Rect(1, 1, 4, 7));
        assertThat(ElementTree.measure(root, new Size(6, 10))).isEqualTo(new Size(3, 5));
        // A cell inside the inset belongs to nothing: the child is not there.
        assertThat(ElementTree.hit(placed, 0, 0)).isNull();
        assertThat(ElementTree.hit(placed, 1, 1))
                .isSameAs(placed.children().get(0).element());
    }

    @Test
    void paddingBiggerThanTheSpaceItIsGivenLeavesNoInnerRectangle() {
        Element<String> root = new Element.Pad<>(new Insets(2, 2, 2, 2), leaf(1, 1, Sizing.grow()));
        var placed = layout(root, 3, 3);

        assertThat(placed.children().get(0).bounds()).isEqualTo(new Rect(2, 2, 0, 0));
        assertThat(ElementTree.measure(root, new Size(3, 3))).isEqualTo(new Size(3, 3));
    }

    @Test
    void aDecorationUnderTheContentIsTheShapeBordersUse() {
        Element<String> decoration = leaf(0, 0, Sizing.grow());
        Element<String> content = leaf(1, 3, Sizing.grow());
        Element<String> root =
                new Element.Stack<>(List.of(decoration, new Element.Pad<>(new Insets(1, 1, 1, 1), content)));
        var placed = layout(root, 5, 9);

        assertThat(placed.children().get(0).bounds()).isEqualTo(new Rect(0, 0, 5, 9));
        assertThat(placed.children().get(1).children().get(0).bounds()).isEqualTo(new Rect(1, 1, 3, 7));
        assertThat(ElementTree.hit(placed, 0, 0)).isSameAs(decoration);
        assertThat(ElementTree.hit(placed, 1, 1)).isSameAs(content);
    }

    @Test
    void anEmptyGroupAndAnEmptyRectanglePlaceNothing() {
        Element<String> empty = new Element.Group<>(List.of(), Element.Direction.ROW);
        assertThat(ElementTree.measure(empty, new Size(4, 4))).isEqualTo(new Size(0, 0));
        assertThat(layout(empty, 4, 4).children()).isEmpty();
        assertThat(layout(leaf(2, 2), 0, 4).children()).isEmpty();
    }

    @Test
    void keysAndPointersReachTheLeafTheyBelongTo() {
        List<String> messages = new ArrayList<>();
        Element<String> root = new Element.Group<>(
                List.of(leaf(1, 3, Sizing.fixed(3)), leaf(1, 2, Sizing.fixed(2))), Element.Direction.ROW);
        var placed = layout(root, 3, 5);

        ElementTree.pointer(
                placed,
                PointerEvent.press(
                        io.github.dhoard.braxton.input.PointerButton.LEFT,
                        io.github.dhoard.braxton.input.Modifiers.NONE,
                        0,
                        4),
                messages::add);
        assertThat(ElementTree.hit(placed, 0, 3))
                .isSameAs(placed.children().get(1).element());
        assertThat(ElementTree.hit(placed, 0, 0))
                .isSameAs(placed.children().get(0).element());
        assertThat(ElementTree.hit(placed, 2, 0)).isNull();
    }
}
