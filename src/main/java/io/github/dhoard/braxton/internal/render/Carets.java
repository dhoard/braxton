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

package io.github.dhoard.braxton.internal.render;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Cursor;

/**
 * The one step between a widget's caret and the frame: the cell a terminal can address.
 *
 * <p>A widget states its caret in the cells it painted, so nothing has to be translated. What is
 * left is what only the painted frame knows: a caret on the second half of a wide glyph has to move
 * to the glyph it belongs to, because that is the only cell a terminal cursor can be put on, and a
 * caret the screen does not have is not presented at all.</p>
 *
 * <p>A virtual caret is marked in the cells instead of being presented by the terminal, so it is
 * part of the frame: the cell it sits on keeps its text and link and gains reverse video, and a blank
 * cell gets a painted space because a style without a glyph shows nothing at all.</p>
 */
public final class Carets {

    private Carets() {}

    /**
     * The cell a caret is presented on, or null when the frame does not have it.
     *
     * @param screen the painted frame
     * @param cursor the caret the focused widget placed, or null
     * @return the addressable caret, or null
     */
    public static Cursor resolve(Screen screen, Cursor cursor) {
        if (cursor == null || !cursor.visible() || screen == null) {
            return null;
        }
        if (cursor.row() < 0
                || cursor.row() >= screen.rows()
                || cursor.column() < 0
                || cursor.column() >= screen.cols()) {
            return null;
        }
        int start = cursor.column();
        while (start > 0 && screen.isContinuation(cursor.row(), start)) {
            start--;
        }
        return start == cursor.column() ? cursor : cursor.movedTo(cursor.row(), start);
    }

    /**
     * Marks the cell a virtual caret sits on, so the caret is part of the frame itself.
     *
     * <p>Only a {@link Cursor.Strategy#VIRTUAL} caret is marked; a native one is presented by the
     * terminal's own cursor and its cell must stay exactly as the widget painted it.</p>
     *
     * @param screen the painted frame
     * @param cursor the addressable caret
     */
    public static void mark(Screen screen, Cursor cursor) {
        if (cursor == null || cursor.strategy() != Cursor.Strategy.VIRTUAL) {
            return;
        }
        Style style = screen.style(cursor.row(), cursor.column()).withInvert();
        if (!screen.text(cursor.row(), cursor.column()).isEmpty()) {
            screen.restyle(cursor.row(), cursor.column(), style);
            return;
        }
        // A blank cell has no glyph to reverse, so a painted space is what makes the caret visible
        // on it; the styles around it are the frame's, so the cell keeps the background it had.
        screen.put(cursor.row(), cursor.column(), " ", style, screen.link(cursor.row(), cursor.column()), 1);
    }
}
