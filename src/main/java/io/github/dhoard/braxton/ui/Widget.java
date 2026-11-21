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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import java.util.function.Consumer;

/**
 * The public extension point for a custom widget.
 *
 * <p>Measure, arrange and paint are separate so a parent can solve constraints before any cell is
 * written. Key and pointer hooks are data-only: a widget emits application messages and never
 * touches the terminal.</p>
 *
 * @param <M> the widget's immutable model
 * @param <Msg> the messages the widget emits
 */
public interface Widget<M, Msg> {

    /**
     * The size this widget wants when given at most {@code max}.
     *
     * @param max the maximum available cell size
     * @param model the immutable model to inspect
     * @return the desired size in terminal cells
     */
    Size measure(Size max, M model);

    /**
     * Places the widget inside {@code available}, usually clipping the measured size.
     *
     * @param available the available cell rectangle
     * @param measured the measured desired cell size
     * @param model the immutable model to inspect
     * @return the allocated rectangle within the available bounds
     */
    default Rect arrange(Rect available, Size measured, M model) {
        int rows = Math.min(available.rows(), Math.max(0, measured.rows()));
        int cols = Math.min(available.cols(), Math.max(0, measured.cols()));
        return new Rect(available.row(), available.col(), rows, cols);
    }

    /**
     * Paints inside {@code bounds}; every write is relative to the screen grid.
     *
     * @param surface the clipped surface to paint
     * @param bounds the allocated cell rectangle
     * @param model the immutable model to inspect
     */
    void paint(Surface surface, Rect bounds, M model);

    /**
     * Whether this widget can take focus.
     *
     * <p>False by default: most leaves paint a value and answer nothing, so a tree of them would give
     * the focus walk one stop per painted cell. A widget that answers keys or pointer reports
     * overrides this, which is also how an application's own field or button joins the walk.</p>
     *
     * @param model the immutable model to inspect
     * @return {@code true} if this widget can take focus; otherwise {@code false}
     */
    default boolean canFocus(M model) {
        return false;
    }

    /**
     * Whether this widget currently owns focus.
     *
     * @param model the immutable model to inspect
     * @return {@code true} if this widget currently owns focus; otherwise {@code false}
     */
    default boolean focused(M model) {
        return false;
    }

    /**
     * The interaction flags this widget paints with.
     *
     * @param model the immutable model to inspect
     * @return the interaction flags this widget paints with
     */
    default InteractionState state(M model) {
        return InteractionState.NONE.withFocused(focused(model));
    }

    /**
     * Whether a screen cell belongs to this widget.
     *
     * @param bounds the allocated cell rectangle
     * @param row the zero-based row
     * @param col the zero-based column
     * @param model the immutable model to inspect
     * @return {@code true} if a screen cell belongs to this widget; otherwise {@code false}
     */
    default boolean hit(Rect bounds, int row, int col, M model) {
        return bounds.contains(row, col);
    }

    /**
     * Handles a key gesture, emitting at most the messages the widget decides.
     *
     * <p>Emitting is how a widget consumes a gesture: a key it answers becomes at least one message,
     * and a key it emits nothing for falls through to the application's update, which is where a
     * global shortcut belongs. That rule keeps one update path - a gesture either changes state
     * through a message or is not the widget's business - and it is what lets an editor own the
     * keys it edits while the application still sees everything else.</p>
     *
     * @param key the normalized key gesture
     * @param model the immutable model to inspect
     * @param emit the consumer of typed messages
     */
    default void onKey(InputEvent.KeyInput key, M model, Consumer<Msg> emit) {}

    /**
     * Handles an input event that is not a key gesture, such as a paste.
     *
     * <p>Key gestures have their own hook because they are the ones a widget consumes on a single
     * update path; everything else a terminal reports while the widget is focused arrives here. A
     * widget that inserts text has to see a paste as the text it is, so the two hooks are separate:
     * a paste is never a binding, and a binding is never inserted.</p>
     *
     * @param event the event to handle
     * @param model the widget's model
     * @param emit where the widget's messages go
     */
    default void onInput(InputEvent event, M model, Consumer<Msg> emit) {}

    /**
     * The cursor this widget places, or null when it places none.
     *
     * <p>A widget knows where its caret is because it paints it; the runtime asks the focused
     * widget for the caret and presents the answer, so no cursor has to be declared in the element
     * tree and no element has to own it. The position is in screen cells, so a widget returns the
     * cell inside {@code bounds} that its caret sits on.</p>
     *
     * @param bounds the rectangle the widget was arranged in
     * @param model the widget's model
     * @return the cursor to present, or null for none
     */
    default Cursor cursor(Rect bounds, M model) {
        return null;
    }

    /**
     * Handles a pointer gesture reported in screen coordinates.
     *
     * <p>A press captures the widget it landed on, so the motion and release that follow are
     * delivered to the same widget until the gesture ends - wherever the pointer has moved, with
     * whatever the widget's rectangle and model are by then. Capture is kept by the widget
     * instance, so a widget that answers pointer reports is one object per placement, held across
     * frames: a widget rebuilt every frame loses its drag between frames, and one instance placed
     * twice has the two placements' gestures mixed. The rectangle is the widget's own, and an event
     * outside it names a cell outside it, which the widget clamps as it sees fit.</p>
     *
     * @param pointer the gesture, in screen cells
     * @param bounds the rectangle the widget was arranged in
     * @param model the widget's model
     * @param emit where the widget's messages go
     */
    default void onPointer(PointerEvent pointer, Rect bounds, M model, Consumer<Msg> emit) {}
}
