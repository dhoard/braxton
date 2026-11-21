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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.internal.render.Carets;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;

/**
 * Resolves, paints, and decorates one cell frame without writing terminal output.
 */
final class RuntimeFrame {
    record Painted<Msg>(ElementTree.Placement<Msg> placement, Screen screen, Cursor caret) {}

    static <Msg> Painted<Msg> paint(
            Element<Msg> tree,
            Viewport viewport,
            RuntimeDiagnostics diagnostics,
            boolean renders,
            long debugStarted,
            java.util.function.LongSupplier nanoTime) {
        ElementTree.Placement<Msg> placement = ElementTree.layout(tree, viewport.bounds());
        Screen screen = new Screen(viewport.rows(), viewport.cols());
        ElementTree.paint(placement, screen);
        Cursor caret = Carets.resolve(screen, InteractionRouter.cursor(placement));
        if (caret != null) {
            // A virtual caret is a cell style, so it is part of the frame rather than a cursor
            // movement; a native one is presented by the cursor presenter below.
            Carets.mark(screen, caret);
        }
        if (diagnostics != null && renders) {
            diagnostics.paint(placement, viewport.bounds(), screen, debugStarted, nanoTime.getAsLong());
        }
        return new Painted<>(placement, screen, caret);
    }
}
