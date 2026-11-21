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

package io.github.dhoard.braxton.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deterministic constraint solving and container layout on a cell grid.
 */
class ConstraintLayoutTest {

    @Test
    void solverCombinesDefiniteAndFlexibleRules() {
        int[] sizes = Constraints.solve(
                100,
                List.of(
                        List.of(Constraint.fixed(20)),
                        List.of(Constraint.percent(25)),
                        List.of(Constraint.fraction(1, 2)),
                        List.of(Constraint.flex(1)),
                        List.of(Constraint.flex(2))));

        assertThat(sizes).containsExactly(20, 25, 50, 2, 3);
    }

    @Test
    void solverShrinksOversubscribedDefiniteSizesDeterministically() {
        assertThat(Constraints.solve(10, List.of(List.of(Constraint.fixed(8)), List.of(Constraint.fixed(8)))))
                .containsExactly(5, 5);
        assertThat(Constraints.solve(5, List.of(List.of(Constraint.fixed(7)), List.of(Constraint.fixed(3)))))
                .containsExactly(4, 1);
    }

    @Test
    void solverHonoursBoundsAndConflicts() {
        assertThat(Constraints.solve(100, List.of(List.of(Constraint.flex(1), Constraint.min(8), Constraint.max(12)))))
                .containsExactly(12);
        assertThat(Constraints.solve(100, List.of(List.of(Constraint.fixed(50), Constraint.max(20)))))
                .containsExactly(20);
        assertThat(Constraints.solve(100, List.of(List.of(Constraint.min(10), Constraint.max(5)))))
                .containsExactly(5);
        assertThat(Constraints.solve(0, List.of(List.of(Constraint.flex(1)), List.of(Constraint.fixed(9)))))
                .containsExactly(0, 0);
    }

    @Test
    void flexPlacesChildrenAlongTheMainAxis() {
        List<Rect> row = FlexLayout.layout(
                new Rect(0, 0, 4, 12),
                FlexLayout.Direction.ROW,
                List.of(List.of(Constraint.fixed(2)), List.of(Constraint.flex(1)), List.of(Constraint.flex(2))),
                1);

        assertThat(row).containsExactly(new Rect(0, 0, 4, 2), new Rect(0, 3, 4, 3), new Rect(0, 7, 4, 5));
    }

    @Test
    void gridUsesIndependentRowAndColumnConstraints() {
        List<Rect> cells = GridLayout.layout(
                new Rect(0, 0, 6, 12),
                2,
                3,
                List.of(List.of(Constraint.fixed(2)), List.of(Constraint.flex(1))),
                List.of(List.of(Constraint.fixed(4)), List.of(Constraint.flex(1)), List.of(Constraint.flex(1))),
                0,
                0);

        assertThat(cells)
                .containsExactly(
                        new Rect(0, 0, 2, 4),
                        new Rect(0, 4, 2, 4),
                        new Rect(0, 8, 2, 4),
                        new Rect(2, 0, 4, 4),
                        new Rect(2, 4, 4, 4),
                        new Rect(2, 8, 4, 4));
    }

    @Test
    void dockGivesTheRemainderToTheCentre() {
        Map<DockLayout.Edge, Rect> docked = DockLayout.layout(
                new Rect(0, 0, 10, 20),
                Map.of(
                        DockLayout.Edge.TOP, new Size(2, 0),
                        DockLayout.Edge.BOTTOM, new Size(1, 0),
                        DockLayout.Edge.LEFT, new Size(0, 3),
                        DockLayout.Edge.RIGHT, new Size(0, 4)));

        assertThat(docked.get(DockLayout.Edge.CENTER)).isEqualTo(new Rect(2, 3, 7, 13));
        assertThat(docked.get(DockLayout.Edge.TOP)).isEqualTo(new Rect(0, 0, 2, 20));
    }

    @Test
    void wrapClipsAtTheEdgeAndWrapsToTheNextRow() {
        List<Rect> wrapped =
                WrapLayout.layout(new Rect(0, 0, 3, 7), List.of(new Size(2, 3), new Size(2, 3), new Size(2, 3)), 1, 0);

        assertThat(wrapped).containsExactly(new Rect(0, 0, 2, 3), new Rect(0, 4, 2, 3), new Rect(2, 0, 1, 3));
    }

    @Test
    void anchorAndScrollStayInsideTheirBounds() {
        Rect overlay = AnchorLayout.layout(
                new Rect(0, 0, 10, 20), AnchorLayout.Anchor.BOTTOM_RIGHT, new Size(3, 4), Insets.uniform(1));
        assertThat(overlay).isEqualTo(new Rect(6, 15, 3, 4));

        Rect visible = ScrollLayout.visible(new Rect(0, 0, 10, 8), new Size(20, 30), 15, 100);
        assertThat(visible).isEqualTo(new Rect(10, 22, 10, 8));
        assertThat(ScrollLayout.thumb(new Rect(0, 0, 10, 1), new Size(20, 1), 5, true))
                .isEqualTo(new Rect(2, 0, 5, 1));
        assertThat(ScrollLayout.thumb(new Rect(0, 0, 10, 1), new Size(5, 1), 0, true))
                .isEqualTo(new Rect(0, 0, 10, 1));
    }
}
