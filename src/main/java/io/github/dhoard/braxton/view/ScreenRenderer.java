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

import java.util.List;

import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * Headless rendering: lay a view out for a fixed viewport and read the result back as cells or plain
 * text.
 *
 * <p>This is the same layout path the runtime paints through, so tests exercise real geometry
 * without a terminal, native access, or output side effects.</p>
 */
public final class ScreenRenderer {

    private final LayoutEngine engine;
    private final WidthPolicy policy;

    public ScreenRenderer() {
        this(WidthPolicy.PINNED);
    }

    public ScreenRenderer(WidthPolicy policy) {
        this.policy = policy == null ? WidthPolicy.PINNED : policy;
        this.engine = new LayoutEngine(this.policy);
    }

    /** Painted screen for {@code view}. */
    public RenderedScreen screen(View<?> view, Viewport viewport) {
        LayoutEngine.Layout layout = engine.layout(view, viewport.bounds());
        var screen = layout.screen();
        java.util.List<java.util.List<RenderedScreen.Cell>> rows = new java.util.ArrayList<>();
        for (int row = 0; row < screen.rows(); row++) {
            java.util.List<RenderedScreen.Cell> cells = new java.util.ArrayList<>();
            for (int col = 0; col < screen.cols(); col++) {
                var cell = screen.cell(row, col);
                boolean continuation = screen.isContinuation(row, col);
                int columns = continuation
                        ? 0
                        : col + 1 < screen.cols() && screen.isContinuation(row, col + 1) ? 2 : 1;
                cells.add(new RenderedScreen.Cell(
                        cell.text(), cell.style(), cell.link(), columns, continuation));
            }
            rows.add(cells);
        }
        return new RenderedScreen(
                screen.rows(),
                screen.cols(),
                rows,
                layout.regions().stream()
                        .map(r -> new RenderedScreen.Region(r.id(), r.bounds()))
                        .toList());
    }

    /**
     * One string per row, trailing blank padding trimmed. A row-final character that is not a blank
     * was painted, so it is kept, matching {@link RenderedScreen#plainLine(int)}.
     */
    public List<String> plainLines(View<?> view, Viewport viewport) {
        return List.copyOf(engine.layout(view, viewport.bounds()).screen().plainLines());
    }

    /** Rows joined with {@code \n}. */
    public String plainText(View<?> view, Viewport viewport) {
        return engine.layout(view, viewport.bounds()).screen().plainText();
    }

    /** Focus regions in paint order, for pointer and focus tests. */
    public List<RenderedScreen.Region> regions(View<?> view, Viewport viewport) {
        return engine.layout(view, viewport.bounds()).regions().stream()
                .map(region -> new RenderedScreen.Region(region.id(), region.bounds())).toList();
    }

    /** Visible column width under the renderer's policy. */
    public int columns(String text) {
        return io.github.dhoard.braxton.style.TextMeasure.columns(text, policy);
    }
}
