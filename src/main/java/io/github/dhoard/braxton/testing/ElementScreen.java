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

package io.github.dhoard.braxton.testing;

import io.github.dhoard.braxton.internal.render.Carets;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Canvas;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.List;

/**
 * A painted element tree a test can assert on, with no terminal and no runtime.
 *
 * <p>The two calls every element test needs - resolve the tree for a size, then paint it - are the
 * same two calls the runtime makes, so a test that reads this screen is reading what a frame would
 * show. Nothing here opens a session, touches a native library or writes output.</p>
 *
 * <p>The screen is built once and read back by {@link #canvas()}, {@link #lines()},
 * {@link #text()}, {@link #hit} and {@link #cursor()}, so a test can assert geometry, content,
 * interaction targets and the caret against the same frame.</p>
 */
public final class ElementScreen {

    private final Screen screen;
    private final ElementTree.Placement<?> placement;
    private final Cursor cursor;

    private ElementScreen(Screen screen, ElementTree.Placement<?> placement) {
        this.screen = screen;
        this.placement = placement;
        // The two steps the runtime takes after painting: a caret is resolved to a cell a terminal
        // can address, and a virtual one is marked in the cells instead of being presented by the
        // terminal's own cursor. A test that reads this frame sees what the screen would show.
        this.cursor = Carets.resolve(screen, InteractionRouter.cursor(placement));
        Carets.mark(screen, cursor);
    }

    /**
     * Lays the tree out for a screen of {@code rows} by {@code cols} cells and paints it.
     *
     * @param root the element tree; required
     * @param rows the number of rows
     * @param cols the number of columns
     * @return the painted screen
     */
    public static ElementScreen render(Element<?> root, int rows, int cols) {
        return render(root, new Size(rows, cols));
    }

    /**
     * Lays the tree out for {@code size} and paints it.
     *
     * @param root the element tree; required
     * @param size the screen size
     * @return the painted screen
     */
    public static ElementScreen render(Element<?> root, Size size) {
        Screen screen = new Screen(Math.max(0, size.rows()), Math.max(0, size.cols()));
        ElementTree.Placement<?> placement = ElementTree.layout(root, new Rect(0, 0, screen.rows(), screen.cols()));
        ElementTree.paint(placement, screen);
        return new ElementScreen(screen, placement);
    }

    /**
     * Lays the tree out and paints it with a theme, as a themed widget would be painted.
     *
     * @param root the element tree; required
     * @param rows the number of rows
     * @param cols the number of columns
     * @param theme the theme to paint with; required
     * @return the painted screen
     */
    public static ElementScreen render(Element<?> root, int rows, int cols, Theme theme) {
        Screen screen = new Screen(Math.max(0, rows), Math.max(0, cols));
        ElementTree.Placement<?> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        ElementTree.paint(placement, screen, theme);
        return new ElementScreen(screen, placement);
    }

    /**
     * The painted cells, readable by position, style and link.
     *
     * @return the painted cells, readable by position, style and link
     */
    public Canvas canvas() {
        return screen.toCanvas();
    }

    /**
     * One plain string per row, trailing blanks trimmed.
     *
     * @return one plain string per row, trailing blanks trimmed
     */
    public List<String> lines() {
        return screen.plainLines();
    }

    /**
     * One row as plain text, trailing blanks trimmed.
     *
     * @param row the screen row
     * @return the row's painted text
     */
    public String line(int row) {
        return screen.plainLine(row);
    }

    /**
     * The rows joined with {@code \n}.
     *
     * @return the painted frame as newline-separated plain-text rows
     */
    public String text() {
        return screen.plainText();
    }

    /**
     * The resolved tree, for assertions about geometry.
     *
     * @return the resolved tree, for assertions about geometry
     */
    public ElementTree.Placement<?> placement() {
        return placement;
    }

    /**
     * The leaf a pointer at {@code row}, {@code col} would reach.
     *
     * @param row the screen row
     * @param col the screen column
     * @return the leaf, or null when nothing accepts that cell
     */
    public Element.Leaf<?, ?> hit(int row, int col) {
        return ElementTree.hit(placement, row, col);
    }

    /**
     * The cursor the focused widget places, as the frame presents it, or null when there is none.
     *
     * @return the cursor the focused widget places, as the frame presents it, or null when there is none
     */
    public Cursor cursor() {
        return cursor;
    }

    /**
     * One row's per-cell styles, so a test can assert that a cell was painted styled.
     *
     * @param row the screen row
     * @return the style of each cell in the row
     */
    public List<Style> styles(int row) {
        List<Style> styles = new java.util.ArrayList<>(screen.cols());
        for (int col = 0; col < screen.cols(); col++) {
            styles.add(screen.style(row, col));
        }
        return List.copyOf(styles);
    }

    /**
     * The rectangles of the widgets that can take focus, in the order a focus walk moves through
     * them.
     *
     * @return the focusable rectangles
     */
    public List<Rect> focusables() {
        List<Rect> rects = new java.util.ArrayList<>();
        List<? extends ElementTree.Placement<?>> focusable = InteractionRouter.focusablePlacements(placement);
        for (ElementTree.Placement<?> found : focusable) {
            rects.add(found.bounds());
        }
        return List.copyOf(rects);
    }
}
