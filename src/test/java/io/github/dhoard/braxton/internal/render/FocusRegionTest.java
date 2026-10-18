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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** A focus region is the rectangle the focused element is actually painted in. */
class FocusRegionTest {

    private static final ElementId FIELD = ElementId.of("field");

    private static LayoutEngine.Layout layout(View<?> view, int rows, int cols) {
        return new LayoutEngine().layout(view, new Rect(0, 0, rows, cols));
    }

    @Test
    void aCentredChildReportsTheAlignedOffsetRatherThanTheWholeColumn() {
        LayoutEngine.Layout layout = layout(Views.column(Alignment.CENTER,
                List.of(Views.focus(FIELD, true, Views.text("abcd")))), 3, 10);
        assertThat(layout.regions()).containsExactly(
                new LayoutEngine.Region(FIELD, new Rect(0, 3, 1, 4)));
    }

    @Test
    void paddingNarrowsAndOffsetsTheRegionToTheInnerBox() {
        LayoutEngine.Layout layout = layout(
                Views.padding(Insets.uniform(1), Views.focus(FIELD, true, Views.text("ab"))),
                5, 10);
        assertThat(layout.regions()).containsExactly(
                new LayoutEngine.Region(FIELD, new Rect(1, 1, 3, 8)));
    }

    @Test
    void aBorderBoxReportsTheInnerRectangleTheChildPaintsIn() {
        LayoutEngine.Layout layout = layout(
                Views.border(io.github.dhoard.braxton.layout.Border.SINGLE, StyledText.plain("t"),
                        Views.focus(FIELD, true, Views.text("ab"))),
                4, 8);
        assertThat(layout.regions()).containsExactly(
                new LayoutEngine.Region(FIELD, new Rect(1, 1, 2, 6)));
    }

    /** Every focused element is reported, in the order the layers paint. */
    @Test
    void twoFocusTargetsAreReportedInPaintOrder() {
        ElementId behind = ElementId.of("behind");
        ElementId above = ElementId.of("above");
        LayoutEngine.Layout layout = layout(Views.overlay(List.of(
                Views.focus(behind, true, Views.text("x")),
                Views.focus(above, true, Views.text("y"))), false), 2, 4);
        assertThat(layout.regions()).extracting(LayoutEngine.Region::id)
                .containsExactly(behind, above);
    }

    /**
     * A region is "one focusable element's painted rectangle", not "the focused element's": pointer
     * hit-testing needs every focusable area, and the ownership flag only decides where keys route.
     */
    @Test
    void aFocusableElementDeclaresARegionWhetherOrNotItOwnsFocus() {
        assertThat(layout(Views.focus(FIELD, false, Views.text("ab")), 2, 4).regions())
                .containsExactly(new LayoutEngine.Region(FIELD, new Rect(0, 0, 2, 4)));
        assertThat(layout(Views.focus(FIELD, true, Views.text("ab")), 2, 4).regions())
                .containsExactly(new LayoutEngine.Region(FIELD, new Rect(0, 0, 2, 4)));
    }

    /**
     * No region can fall outside the viewport, and a region can never be larger than the element's
     * allocated rectangle: the renderer clips every painted area to the screen.
     */
    @Test
    void everyRegionStaysInsideTheViewport() {
        Random random = new Random(53L);
        for (int iteration = 0; iteration < 200; iteration++) {
            int rows = 1 + random.nextInt(8);
            int cols = 1 + random.nextInt(12);
            View<Object> view = randomTree(random, 3);
            LayoutEngine.Layout layout = new LayoutEngine()
                    .layout(view, new Rect(0, 0, rows, cols));
            Rect viewport = new Rect(0, 0, rows, cols);
            for (LayoutEngine.Region region : layout.regions()) {
                Rect bounds = region.bounds();
                assertThat(viewport.contains(bounds.row(), bounds.col()))
                        .as("origin of %s inside %dx%d", region.id().value(), rows, cols).isTrue();
                assertThat(bounds.row() + bounds.rows()).isLessThanOrEqualTo(rows);
                assertThat(bounds.col() + bounds.cols()).isLessThanOrEqualTo(cols);
            }
        }
    }

    private static View<Object> randomTree(Random random, int depth) {
        if (depth <= 0) {
            return Views.focus(FIELD, true, Views.text(randomText(random)));
        }
        return switch (random.nextInt(6)) {
            case 0 -> Views.focus(FIELD, random.nextBoolean(), Views.text(randomText(random)));
            case 1 -> Views.column(Alignment.CENTER, items(random, depth));
            case 2 -> Views.row(Alignment.START, items(random, depth));
            case 3 -> Views.padding(Insets.uniform(random.nextInt(2)),
                    Views.column(Alignment.END, items(random, depth)));
            case 4 -> Views.overlay(items(random, depth), false);
            default -> Views.border(io.github.dhoard.braxton.layout.Border.SINGLE,
                    StyledText.plain("t"), Views.column(items(random, depth)));
        };
    }

    private static List<View<Object>> items(Random random, int depth) {
        List<View<Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 1 + random.nextInt(3); i++) {
            items.add(randomTree(random, depth - 1));
        }
        return items;
    }

    private static StyledText randomText(Random random) {
        String[] atoms = {"a", "bb", " ", "\u65E5", "\uD83D\uDE00", "-"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1 + random.nextInt(4); i++) {
            text.append(atoms[random.nextInt(atoms.length)]);
        }
        return StyledText.plain(text.toString());
    }
}
