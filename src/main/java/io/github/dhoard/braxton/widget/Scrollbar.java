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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A vertical scrollbar over a scroll position the application owns.
 *
 * <p>The bar is a leaf, not a pane: it renders the window's position in its content and answers
 * the gestures that move it, and where the content is scrolled to stays in the application's model,
 * exactly as a pane's offset does. That is what lets one bar drive a pane, a table or a list of the
 * application's own, and what keeps the scroll position of a frame in the model that produced it.</p>
 *
 * <p>The model names a first content row, the rows the window covers and how many rows the content
 * has; the bar computes the rest. The thumb covers the window's fraction of the content and sits at
 * the fraction the window has scrolled past, so {@code 15/100} with a ten-row window shows a
 * one-row thumb at the top and {@code 85/100} shows it at the bottom. The arithmetic is the one
 * {@link ScrollView} paints its own bar with, so a standalone bar and a pane's bar agree about any
 * position.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>A bar is one column and as many rows as its <em>window</em>: it is measured as one column of
 * every row it is offered and painted in the first {@link Model#window()} rows of its rectangle,
 * which is why the parent gives it the pane's height - in a row of panes, that is the pane beside
 * it. A taller rectangle is not filled past the window, and a shorter one clips the thumb, so a bar
 * can be placed in a region smaller than the window it describes without painting a wrong
 * fraction.</p>
 *
 * <h2>Keys, pointer and wheel</h2>
 *
 * <p>A bar takes a turn in the focus walk while there is something to scroll, because a scrollbar
 * is a control: {@code up}/{@code down} move one row, {@code pageup}/{@code pagedown} one window,
 * and {@code home}/{@code end} the ends of the content. Every one of
 * those clamps, so a gesture at a boundary is still the bar's and moves nothing. A pointer press or
 * drag on the bar puts the thumb's center on the cell the pointer is at, which is what makes a
 * track click jump toward its cell and a drag move the position continuously, and a wheel over the
 * bar steps {@link #WHEEL_ROWS} rows. Nothing is emitted while the content fits the window: there
 * is no position to move to, so the bar neither answers a gesture nor takes a pointer.</p>
 *
 * <h2>Themes</h2>
 *
 * <p>The track is painted in the theme's {@link ThemeToken#BORDER}, the thumb in its
 * {@link ThemeToken#ACCENT}, and a focused bar overlays {@link ThemeToken#FOCUS} on the thumb.
 * {@link Appearance} changes the two glyphs, not the styles, because color belongs to the theme
 * and a widget that hard-coded its own would be a bar that ignores a live theme switch.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A bar is one object per bar an application places, held across frames the way a component
 * is: a pointer gesture is kept by the widget it started on, so a bar rebuilt every frame would
 * lose its drag between frames and one instance placed twice would mix the two bars' gestures.
 * The widget holds nothing but its appearance, so the instance is cheap and immutable.</p>
 *
 * @see Elements#scrollbar(Scrollbar, Scrollbar.Model)
 */
public final class Scrollbar implements ThemedWidget<Scrollbar.Model, Scrollbar.Msg> {

    /**
     * The rows one wheel notch scrolls, which is the step a terminal reader expects.
     */
    public static final int WHEEL_ROWS = 3;

    private final Appearance appearance;

    /**
     * A bar with the pane's own track and thumb glyphs.
     */
    public Scrollbar() {
        this(Appearance.defaults());
    }

    /**
     * A bar with its own glyphs.
     *
     * @param appearance the glyphs to paint; required
     */
    public Scrollbar(Appearance appearance) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
    }

    /**
     * The glyphs this bar paints.
     *
     * @return the glyphs this bar paints
     */
    public Appearance appearance() {
        return appearance;
    }

    /**
     * What a bar paints.
     *
     * <p>A glyph is one cell: the bar writes it with a width of one column, so a value of two
     * columns or of no printable character would shift everything after it.</p>
     *
     * @param track the glyph a track cell paints; empty for the default
     * @param thumb the glyph a thumb cell paints; empty for the default
     */
    public record Appearance(String track, String thumb) {

        /**
         * Substitutes the default glyphs for empty ones.
         */
        public Appearance {
            track = glyph(track, BarGeometry.TRACK);
            thumb = glyph(thumb, BarGeometry.THUMB);
        }

        /**
         * The bar's own glyphs: a solid block on a thin rule, the pair {@link ScrollView} paints.
         *
         * @return the default appearance
         */
        public static Appearance defaults() {
            return new Appearance(BarGeometry.TRACK, BarGeometry.THUMB);
        }

        /**
         * The same appearance with a different track glyph.
         *
         * @param value the glyph; empty for the default
         * @return the new appearance
         */
        public Appearance withTrack(String value) {
            return new Appearance(value, thumb);
        }

        /**
         * The same appearance with a different thumb glyph.
         *
         * @param value the glyph; empty for the default
         * @return the new appearance
         */
        public Appearance withThumb(String value) {
            return new Appearance(track, value);
        }

        /**
         * {@code value} when it names a glyph, {@code fallback} otherwise.
         */
        private static String glyph(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }
    }

    /**
     * A scroll position: the first content row the window shows, the rows it covers, and how many
     * rows there are.
     *
     * <p>{@code first} is clamped into the content on construction, so a position restored after
     * content shrank - a filter narrowed a list, a transcript was cleared - is still a position the
     * bar can paint. Every value is non-negative, and a window with no rows is a bar with nothing to
     * show.</p>
     *
     * @param first the first content row the window shows
     * @param window the rows the window covers
     * @param content the content rows there are
     * @param focused whether the bar owns focus
     */
    public record Model(int first, int window, int content, boolean focused) {

        /**
         * Clamps the position into the content.
         */
        public Model {
            window = Math.max(0, window);
            content = Math.max(0, content);
            first = Math.max(0, Math.min(first, travel(content, window)));
        }

        /**
         * A bar at a position, out of focus.
         *
         * @param first the first content row the window shows
         * @param window the rows the window covers
         * @param content the content rows there are
         * @return the model
         */
        public static Model of(int first, int window, int content) {
            return new Model(first, window, content, false);
        }

        /**
         * This position at a first content row, clamped as the constructor clamps it.
         *
         * @param value the first content row to show
         * @return the new model
         */
        public Model withFirst(int value) {
            return new Model(value, window, content, focused);
        }

        /**
         * This position over a window of a different height.
         *
         * @param value the rows the window covers
         * @return the new model
         */
        public Model withWindow(int value) {
            return new Model(first, value, content, focused);
        }

        /**
         * This position over content of a different length.
         *
         * @param value the content rows there are
         * @return the new model
         */
        public Model withContent(int value) {
            return new Model(first, window, value, focused);
        }

        /**
         * The same position, focused or not.
         *
         * @param value whether the bar owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(first, window, content, value);
        }

        /**
         * The content rows the window cannot show.
         *
         * @return the largest first row the model allows
         */
        public int travel() {
            return travel(content, window);
        }

        private static int travel(int content, int window) {
            return Math.max(0, content - window);
        }
    }

    /**
     * The bar's messages.
     *
     * <p>One message, because a bar has one thing to say: where the window should start. A
     * reduction that stores that number needs nothing else, and a bar that reported the gesture
     * instead would make every application repeat the arithmetic the bar already did.</p>
     */
    public sealed interface Msg {

        /**
         * The window was asked to start at a content row.
         *
         * @param first the first content row to show, already clamped to the content
         */
        record Moved(int first) implements Msg {}
    }

    /**
     * Where the thumb sits in the window.
     *
     * @param start the first window row the thumb covers
     * @param size the rows the thumb covers; zero when there is nothing to scroll
     */
    public record Thumb(int start, int size) {}

    /**
     * The thumb geometry a model paints.
     *
     * <p>Content that fits the window fills the whole bar, which is the bar's way of saying the
     * window shows everything: the track is drawn for the rows around the thumb, and with no travel
     * there are none. A window of no rows has no thumb, because there are no cells to cover.</p>
     *
     * @param model the position to measure; required
     * @return the thumb geometry
     */
    public static Thumb thumb(Model model) {
        Objects.requireNonNull(model, "model");
        if (model.travel() == 0) {
            return new Thumb(0, model.window());
        }
        BarGeometry.Span span = BarGeometry.thumb(model.first(), model.window(), model.content());
        return new Thumb(span.start(), span.size());
    }

    /**
     * The bar's element: one cell on the parent's main axis.
     *
     * <p>It is sized as one cell, which is a column in a row - the bar beside the pane it scrolls.
     * A bar that is a child of a column asks for one row; wrap it in {@code Elements.grow} to have
     * it take the rows it is placed in.</p>
     *
     * @param model the position to paint; required
     * @return the element, emitting this bar's messages
     */
    public Element<Msg> element(Model model) {
        return new Element.Leaf<Model, Msg>(this, Objects.requireNonNull(model, "model"), Sizing.fixed(1));
    }

    /**
     * One column of the rows it is offered; the parent's sizing decides how many rows that is.
     */
    @Override
    public Size measure(Size max, Model model) {
        return new Size(max.rows(), Math.min(1, max.cols()));
    }

    /**
     * Paints the track and the thumb in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (bounds.isEmpty() || model.window() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Style track = resolved.style(ThemeToken.BORDER);
        Style thumb = resolved.style(ThemeToken.ACCENT);
        if (model.focused()) {
            thumb = thumb.merge(resolved.style(ThemeToken.FOCUS));
        }
        Thumb geometry = thumb(model);
        // The window is what the bar describes, so a rectangle taller than it stays unpainted
        // rather than showing track for rows the pane does not have.
        int rows = Math.min(bounds.rows(), model.window());
        for (int index = 0; index < rows; index++) {
            boolean onThumb = index >= geometry.start() && index < geometry.start() + geometry.size();
            surface.put(
                    bounds.row() + index,
                    bounds.col(),
                    onThumb ? appearance.thumb() : appearance.track(),
                    onThumb ? thumb : track,
                    null,
                    1);
        }
    }

    /**
     * A bar is a control while there is something to scroll: it takes a turn in the focus walk so
     * its keys can reach it, and a bar whose content fits answers nothing, so it takes none.
     */
    @Override
    public boolean canFocus(Model model) {
        return model.travel() > 0;
    }

    /**
     * Focus is model data: the application sets it and the bar only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A click on the bar belongs to the bar, unless there is nothing to scroll.
     */
    @Override
    public boolean hit(Rect bounds, int row, int col, Model model) {
        return model.travel() > 0 && bounds.contains(row, col);
    }

    /**
     * The bar's keys: arrows by one row, page keys by one window, home and end at the ends.
     *
     * <p>A clamp is still an answer: the gesture is the bar's even at a boundary, so it emits the
     * position it already had rather than letting the application's own binding for the same key
     * run underneath. Every other gesture is declined by emitting nothing, which is what leaves the
     * application's keys alone.</p>
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (model.travel() <= 0) {
            return;
        }
        Key pressed = key.key();
        long first = model.first();
        long target;
        if (pressed.equals(Key.UP)) {
            target = first - 1;
        } else if (pressed.equals(Key.DOWN)) {
            target = first + 1;
        } else if (pressed.equals(Key.PAGE_UP)) {
            target = first - model.window();
        } else if (pressed.equals(Key.PAGE_DOWN)) {
            target = first + model.window();
        } else if (pressed.equals(Key.HOME)) {
            target = 0L;
        } else if (pressed.equals(Key.END)) {
            target = model.travel();
        } else {
            return;
        }
        emit.accept(new Msg.Moved(clamp(target, model)));
    }

    /**
     * The bar's pointer gestures: a press and a drag position the thumb, and the wheel steps it.
     *
     * <p>The thumb's center follows the pointer, so a press on the track jumps toward the cell it
     * landed on and a drag keeps the position under the pointer as it moves. A drag that leaves the
     * bar keeps being delivered to it, and a row past either end clamps, which is what stops a
     * gesture from ending at the edge of the region.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (bounds.isEmpty() || model.travel() <= 0) {
            return;
        }
        if (pointer.isWheel()) {
            long step = pointer.button() == PointerButton.WHEEL_UP
                    ? -WHEEL_ROWS
                    : pointer.button() == PointerButton.WHEEL_DOWN ? WHEEL_ROWS : 0L;
            if (step != 0L) {
                emit.accept(new Msg.Moved(clamp(model.first() + step, model)));
            }
            return;
        }
        boolean dragging = pointer.isMotion() && pointer.button() == PointerButton.LEFT;
        if ((!pointer.isPress() || pointer.button() != PointerButton.LEFT) && !dragging) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        int target = positionAt(local.row(), model);
        if (target != model.first()) {
            emit.accept(new Msg.Moved(target));
        }
    }

    /**
     * The first content row whose thumb center sits on {@code row}.
     */
    private static int positionAt(int row, Model model) {
        Thumb geometry = thumb(model);
        int span = model.window() - geometry.size();
        if (span <= 0) {
            return 0;
        }
        long center = (long) Math.max(0, Math.min(row, model.window() - 1)) - Math.max(0, (geometry.size() - 1) / 2);
        long start = Math.max(0L, Math.min(center, span));
        return (int) Math.max(0L, Math.min(Math.round((double) start * model.travel() / span), model.travel()));
    }

    /**
     * A position inside the model's content; a gesture's arithmetic is widened before it here.
     */
    private static int clamp(long target, Model model) {
        return (int) Math.max(0L, Math.min(target, model.travel()));
    }
}
