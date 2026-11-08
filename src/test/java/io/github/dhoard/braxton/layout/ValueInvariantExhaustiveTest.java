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

package io.github.dhoard.braxton.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every rectangle, inset and size the arithmetic can be asked about, enumerated rather than sampled.
 *
 * <p>{@link Rect#clip} and {@link Rect#inset} are the whole basis a container decides what to paint
 * from. Their arithmetic is done with ints and long casts, and the result is a rectangle whose cells are
 * supposed to be exactly the cells both inputs had - a claim that is easy to state and easy to get wrong
 * by one row at an edge. So each pair is checked against the same question answered the slow way: walk
 * the cells and ask {@link Rect#contains}.</p>
 *
 * <p>The domains here are small and closed, which is what makes enumeration the right tool: every
 * rectangle over a 0..3 grid, every inset over 0..3, every target cell over 0..5. Within those domains
 * the answer is not a sample; it is the whole answer.</p>
 */
class ValueInvariantExhaustiveTest {

    private static final int COORD_MAX = 3;
    private static final int INSET_MAX = 3;
    private static final int TARGET_MAX = 5;

    @Test
    void clippingARectangleLeavesExactlyTheCellsBothRectanglesHad() {
        List<Rect> all = rectangles(COORD_MAX);
        for (Rect outer : all) {
            for (Rect inner : all) {
                Rect clipped = outer.clip(inner);
                List<int[]> shared = sharedCells(outer, inner);
                assertThat(clipped.rows() * clipped.cols())
                        .as(
                                "clip of %s by %s covers %d cells, the pair share %d",
                                outer, inner, clipped.rows() * clipped.cols(), shared.size())
                        .isEqualTo(shared.size());
                // Every cell the clipped rectangle claims has to be a cell both inputs had.
                for (int row = clipped.row(); row < clipped.row() + clipped.rows(); row++) {
                    for (int col = clipped.col(); col < clipped.col() + clipped.cols(); col++) {
                        assertThat(outer.contains(row, col))
                                .as("%s contains a cell %d,%d it does not have", outer, row, col)
                                .isTrue();
                        assertThat(inner.contains(row, col))
                                .as("%s contains a cell %d,%d it does not have", inner, row, col)
                                .isTrue();
                    }
                }
                // And every cell both had has to be inside the clipped rectangle.
                for (int[] cell : shared) {
                    assertThat(clipped.contains(cell[0], cell[1]))
                            .as("clip of %s by %s dropped the shared cell %d,%d", outer, inner, cell[0], cell[1])
                            .isTrue();
                }
            }
        }
    }

    @Test
    void insettingNeverTurnsATableInsideOut() {
        List<Insets> insets = insets(INSET_MAX);
        for (Rect rect : rectangles(5)) {
            for (Insets inset : insets) {
                Rect shrunk = rect.inset(inset);
                assertThat(shrunk.rows())
                        .as("%s inset by %s gave a negative height", rect, inset)
                        .isNotNegative();
                assertThat(shrunk.cols())
                        .as("%s inset by %s gave a negative width", rect, inset)
                        .isNotNegative();
                // A cell survives the inset when it is inside the rectangle and at least the inset's
                // own margin away from that edge - counted the slow way and compared with the result.
                int survived = 0;
                for (int row = rect.row(); row < rect.row() + rect.rows(); row++) {
                    for (int col = rect.col(); col < rect.col() + rect.cols(); col++) {
                        if (row >= rect.row() + inset.top()
                                && row < rect.row() + rect.rows() - inset.bottom()
                                && col >= rect.col() + inset.left()
                                && col < rect.col() + rect.cols() - inset.right()) {
                            survived++;
                        }
                    }
                }
                assertThat(shrunk.rows() * shrunk.cols())
                        .as(
                                "%s inset by %s kept %d cells, expected %d",
                                rect, inset, shrunk.rows() * shrunk.cols(), survived)
                        .isEqualTo(survived);
                if (survived > 0) {
                    assertThat(shrunk.row())
                            .as("%s inset by %s did not move its first row to the margin", rect, inset)
                            .isEqualTo(rect.row() + inset.top());
                    assertThat(shrunk.col())
                            .as("%s inset by %s did not move its first column to the margin", rect, inset)
                            .isEqualTo(rect.col() + inset.left());
                }
            }
        }
    }

    @Test
    void containsAnswersTheSameQuestionAsTheCellsTheRectangleHolds() {
        for (Rect rect : rectangles(TARGET_MAX)) {
            for (int row = 0; row <= TARGET_MAX + 1; row++) {
                for (int col = 0; col <= TARGET_MAX + 1; col++) {
                    boolean inside = row >= rect.row()
                            && row < rect.row() + rect.rows()
                            && col >= rect.col()
                            && col < rect.col() + rect.cols();
                    assertThat(rect.contains(row, col))
                            .as("%s and cell %d,%d", rect, row, col)
                            .isEqualTo(inside);
                }
            }
            assertThat(rect.isEmpty()).as("%s emptiness", rect).isEqualTo(rect.rows() == 0 || rect.cols() == 0);
        }
    }

    @Test
    void theValueTypesRejectWhatTheyCannotDraw() {
        for (int first : new int[] {-1, -5, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> new Rect(first, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Rect(0, first, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Rect(0, 0, first, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Rect(0, 0, 1, first)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Insets(first, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Size(first, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Viewport(0, first)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int rows = 0; rows <= 4; rows++) {
            for (int cols = 0; cols <= 4; cols++) {
                Viewport viewport = new Viewport(rows, cols);
                assertThat(viewport.bounds()).as("%s bounds", viewport).isEqualTo(new Rect(0, 0, rows, cols));
                assertThat(Viewport.of(new Size(rows, cols)).size())
                        .as("%s round through a viewport", new Size(rows, cols))
                        .isEqualTo(new Size(rows, cols));
                int cells = 0;
                for (int row = 0; row < TARGET_MAX + 2; row++) {
                    for (int col = 0; col < TARGET_MAX + 2; col++) {
                        if (viewport.bounds().contains(row, col)) {
                            cells++;
                        }
                    }
                }
                assertThat(cells).as("%s holds %d cells", viewport, rows * cols).isEqualTo(rows * cols);
                assertThat(viewport.size().isEmpty())
                        .as("%s emptiness", viewport)
                        .isEqualTo(viewport.rows() == 0 || viewport.cols() == 0);
            }
        }
        for (int vertical = 0; vertical <= INSET_MAX; vertical++) {
            for (int horizontal = 0; horizontal <= INSET_MAX; horizontal++) {
                Insets symmetric = Insets.symmetric(vertical, horizontal);
                assertThat(symmetric.vertical()).as("%s vertical", symmetric).isEqualTo(2 * vertical);
                assertThat(symmetric.horizontal())
                        .as("%s horizontal", symmetric)
                        .isEqualTo(2 * horizontal);
            }
        }
    }

    /** The cells both rectangles hold, found by walking the first one. */
    private static List<int[]> sharedCells(Rect first, Rect second) {
        List<int[]> shared = new ArrayList<>();
        for (int row = first.row(); row < first.row() + first.rows(); row++) {
            for (int col = first.col(); col < first.col() + first.cols(); col++) {
                if (second.contains(row, col)) {
                    shared.add(new int[] {row, col});
                }
            }
        }
        return shared;
    }

    private static List<Rect> rectangles(int coordinateMax) {
        List<Rect> all = new ArrayList<>();
        for (int row = 0; row <= coordinateMax; row++) {
            for (int col = 0; col <= coordinateMax; col++) {
                for (int rows = 0; rows <= coordinateMax; rows++) {
                    for (int cols = 0; cols <= coordinateMax; cols++) {
                        all.add(new Rect(row, col, rows, cols));
                    }
                }
            }
        }
        return all;
    }

    private static List<Insets> insets(int max) {
        List<Insets> all = new ArrayList<>();
        for (int top = 0; top <= max; top++) {
            for (int right = 0; right <= max; right++) {
                for (int bottom = 0; bottom <= max; bottom++) {
                    for (int left = 0; left <= max; left++) {
                        all.add(new Insets(top, right, bottom, left));
                    }
                }
            }
        }
        return all;
    }
}
