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
import java.util.function.Function;

/**
 * Two panes and the divider between them, split along one axis.
 *
 * <p>The region is divided in cells, not in fractions: the model says how many cells the first pane
 * takes, how many the region has and the least either pane may keep, and the divider between them
 * is one cell. Cells rather than a ratio because a terminal grid has no sub-cell to round into:
 * {@code 37} of {@code 100} columns is a position an application can compute, compare and persist,
 * while a {@code 0.37} ratio has to be rounded by whoever paints it, and two roundings of the same
 * ratio do not have to agree.</p>
 *
 * <p>The widget is the divider. It paints the rule, takes a turn in the focus walk while it has room
 * to move, and answers the gestures that move it: {@code left}/{@code right} - or {@code up}/
 * {@code down} for a stacked split - nudge by one cell, {@code home} puts the divider at the first
 * cell the model allows and {@code end} at the last, and a press or drag puts it on the cell the
 * pointer is at. The panes themselves are the application's elements, placed by {@link #element} or
 * {@link Elements#splitPane}: the first takes the cells the model names, the divider takes one, and
 * the second takes what is left, so a split wider than the model says is filled rather than left
 * ragged.</p>
 *
 * <h2>Directions</h2>
 *
 * <p>{@link Direction#ROW} is panes side by side and a vertical rule between them;
 * {@link Direction#COLUMN} is panes stacked and a horizontal rule. The names are the axis the panes
 * are laid out on, which is {@link Element.Direction}'s, so a split reads the same way as the
 * layout it replaces.</p>
 *
 * <h2>Themes</h2>
 *
 * <p>The rule is painted in the theme's {@link ThemeToken#BORDER} and a focused divider overlays
 * {@link ThemeToken#FOCUS} on it, because focus is how an application shows which divider its arrow
 * keys move. {@link Appearance} changes the two glyphs, not the styles.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A divider is one object per split an application places, held across frames the way a
 * component is: a pointer gesture is kept by the widget it started on, so a divider rebuilt every
 * frame would lose its drag between frames and one instance placed twice would mix the two splits'
 * gestures. The widget holds nothing but its appearance, so the instance is cheap and immutable.</p>
 */
public final class SplitPane implements ThemedWidget<SplitPane.Model, SplitPane.Msg> {

    private final Appearance appearance;

    /**
     * A divider with the default rule glyphs.
     */
    public SplitPane() {
        this(Appearance.defaults());
    }

    /**
     * A divider with its own glyphs.
     *
     * @param appearance the glyphs to paint; required
     */
    public SplitPane(Appearance appearance) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
    }

    /**
     * The glyphs this divider paints.
     *
     * @return the glyphs this divider paints
     */
    public Appearance appearance() {
        return appearance;
    }

    /**
     * The axis the panes are laid out on.
     */
    public enum Direction {

        /**
         * Panes side by side, divided by a vertical rule.
         */
        ROW,

        /**
         * Panes stacked, divided by a horizontal rule.
         */
        COLUMN
    }

    /**
     * What a divider paints.
     *
     * <p>A glyph is one cell: the divider writes it with a width of one column, so a value of two
     * columns or of no printable character would shift the pane after it.</p>
     *
     * @param vertical the glyph a vertical rule paints; empty for the default
     * @param horizontal the glyph a horizontal rule paints; empty for the default
     */
    public record Appearance(String vertical, String horizontal) {

        /**
         * The rule glyphs a box-drawing border uses.
         */
        private static final String VERTICAL = "\u2502";

        private static final String HORIZONTAL = "\u2500";

        /**
         * Substitutes the default glyphs for empty ones.
         */
        public Appearance {
            vertical = glyph(vertical, VERTICAL);
            horizontal = glyph(horizontal, HORIZONTAL);
        }

        /**
         * A thin rule in each direction.
         *
         * @return the default appearance
         */
        public static Appearance defaults() {
            return new Appearance(VERTICAL, HORIZONTAL);
        }

        /**
         * The same appearance with a different vertical rule.
         *
         * @param value the glyph; empty for the default
         * @return the new appearance
         */
        public Appearance withVertical(String value) {
            return new Appearance(value, horizontal);
        }

        /**
         * The same appearance with a different horizontal rule.
         *
         * @param value the glyph; empty for the default
         * @return the new appearance
         */
        public Appearance withHorizontal(String value) {
            return new Appearance(vertical, value);
        }

        /**
         * {@code value} when it is not empty, {@code fallback} otherwise.
         */
        private static String glyph(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }
    }

    /**
     * A split: the cells the first pane takes, the cells the region has, and the least a pane may
     * keep.
     *
     * <p>{@code first} is clamped into what the region allows, so a split restored after the
     * terminal shrank - or after the region changed under it - is still a split that can be
     * painted: the first pane keeps {@code minimum} cells and the second keeps the rest, and when
     * the region is too small to give both panes their minimum, the first pane takes what there
     * is. Every value is non-negative.</p>
     *
     * @param direction the axis the panes are laid out on
     * @param first the cells the first pane takes, excluding the divider
     * @param total the cells the region has, including the divider
     * @param minimum the fewest cells either pane keeps
     * @param focused whether the divider owns focus
     */
    public record Model(Direction direction, int first, int total, int minimum, boolean focused) {

        /**
         * Clamps the position into the region.
         */
        public Model {
            direction = direction == null ? Direction.ROW : direction;
            total = Math.max(1, total);
            minimum = Math.max(0, Math.min(minimum, (total - 1) / 2));
            first = Math.max(minimum, Math.min(first, total - 1 - minimum));
        }

        /**
         * A split with no minimum and no focus.
         *
         * @param direction the axis the panes are laid out on; null is {@link Direction#ROW}
         * @param first the cells the first pane takes
         * @param total the cells the region has
         * @return the model
         */
        public static Model of(Direction direction, int first, int total) {
            return new Model(direction, first, total, 0, false);
        }

        /**
         * This split at a different size for its first pane.
         *
         * @param value the cells the first pane takes
         * @return the new model
         */
        public Model withFirst(int value) {
            return new Model(direction, value, total, minimum, focused);
        }

        /**
         * This split of a region of a different size.
         *
         * @param value the cells the region has
         * @return the new model
         */
        public Model withTotal(int value) {
            return new Model(direction, first, value, minimum, focused);
        }

        /**
         * This split with a different minimum.
         *
         * @param value the fewest cells either pane keeps
         * @return the new model
         */
        public Model withMinimum(int value) {
            return new Model(direction, first, total, value, focused);
        }

        /**
         * The same split, focused or not.
         *
         * @param value whether the divider owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(direction, first, total, minimum, value);
        }

        /**
         * The cells the second pane takes.
         *
         * @return the cells after the divider
         */
        public int second() {
            return Math.max(0, total - 1 - first);
        }

        /**
         * The largest first pane the region allows.
         *
         * @return the last first-pane size inside the minimum
         */
        public int last() {
            return total - 1 - minimum;
        }
    }

    /**
     * The divider's messages.
     *
     * <p>One message, because a divider has one thing to say: how many cells the first pane should
     * take. The model clamps it, so an application stores the number it is given.</p>
     */
    public sealed interface Msg {

        /**
         * The split was asked to give the first pane a number of cells.
         *
         * @param first the cells the first pane should take, already clamped to the region
         */
        record Moved(int first) implements Msg {}
    }

    /**
     * The divider's element, sized as one cell on the parent's main axis.
     *
     * @param model the split to paint; required
     * @return the element, emitting this divider's messages
     */
    public Element<Msg> element(Model model) {
        return new Element.Leaf<Model, Msg>(this, Objects.requireNonNull(model, "model"), Sizing.fixed(1));
    }

    /**
     * The two panes and their divider as one element tree.
     *
     * <p>The first pane takes the cells the model names, the divider one, and the second pane grows
     * into the rest, so the region is filled whatever the viewport turned out to be. The panes are
     * the caller's elements and are composed, never rebuilt: this method only places them and lifts
     * the divider's messages into the caller's message type.</p>
     *
     * @param <Out> the message type the composition emits
     * @param state the split to paint; required
     * @param first the first pane's content; required
     * @param second the second pane's content; required
     * @param messages maps the divider's messages into the tree's message type; required
     * @return the composed element
     */
    public <Out> Element<Out> element(
            Model state, Element<Out> first, Element<Out> second, Function<? super Msg, ? extends Out> messages) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(messages, "messages");
        Element<Out> divider = element(state).map(messages);
        Element<Out> leading = Elements.fixed(state.first(), first);
        Element<Out> trailing = Elements.grow(second);
        return state.direction() == Direction.ROW
                ? Elements.row(leading, divider, trailing)
                : Elements.column(leading, divider, trailing);
    }

    /**
     * One cell on the axis the split runs along, and as much of the other axis as it is offered.
     */
    @Override
    public Size measure(Size max, Model model) {
        return model.direction() == Direction.ROW
                ? new Size(max.rows(), Math.min(1, max.cols()))
                : new Size(Math.min(1, max.rows()), max.cols());
    }

    /**
     * Paints the rule in the theme's border style, with focus overlaid on it.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (bounds.isEmpty()) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Style style = resolved.style(ThemeToken.BORDER);
        if (model.focused()) {
            style = style.merge(resolved.style(ThemeToken.FOCUS));
        }
        if (model.direction() == Direction.ROW) {
            for (int index = 0; index < bounds.rows(); index++) {
                surface.put(bounds.row() + index, bounds.col(), appearance.vertical(), style, null, 1);
            }
        } else {
            for (int index = 0; index < bounds.cols(); index++) {
                surface.put(bounds.row(), bounds.col() + index, appearance.horizontal(), style, null, 1);
            }
        }
    }

    /**
     * A divider answers keys and pointer reports while it can move: it takes a turn in the focus
     * walk so the arrows can reach it, and a split with no room to move takes none.
     */
    @Override
    public boolean canFocus(Model model) {
        return model.last() > model.minimum();
    }

    /**
     * Focus is model data: the application sets it and the divider only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A click on the divider belongs to it while there is room to move.
     */
    @Override
    public boolean hit(Rect bounds, int row, int col, Model model) {
        return model.last() > model.minimum() && bounds.contains(row, col);
    }

    /**
     * The divider's keys: one cell along the split, or straight to either end.
     *
     * <p>The keys of the other axis are declined, so an application that moves between panes with
     * {@code up} and {@code down} keeps them while a side-by-side divider owns focus. A clamp is
     * still an answer, for the same reason a scrollbar's is: the gesture belongs to the divider even
     * at a boundary.</p>
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (model.last() <= model.minimum()) {
            return;
        }
        Key pressed = key.key();
        boolean row = model.direction() == Direction.ROW;
        Key smaller = row ? Key.LEFT : Key.UP;
        Key larger = row ? Key.RIGHT : Key.DOWN;
        int target;
        if (pressed.equals(smaller)) {
            target = model.first() - 1;
        } else if (pressed.equals(larger)) {
            target = model.first() + 1;
        } else if (pressed.equals(Key.HOME)) {
            target = model.minimum();
        } else if (pressed.equals(Key.END)) {
            target = model.last();
        } else {
            return;
        }
        emit.accept(new Msg.Moved(Math.max(model.minimum(), Math.min(target, model.last()))));
    }

    /**
     * The divider's pointer gestures: a press or a drag puts the rule on the cell under the pointer.
     *
     * <p>The report carries a screen cell, so the region's own first cell is recovered from the
     * rectangle the divider was painted in and the split's current size - the divider is exactly
     * {@code first} cells after the region begins - and the cell becomes a split size. A drag past
     * either end clamps, which is what keeps a pane from collapsing below the model's minimum.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (bounds.isEmpty() || model.last() <= model.minimum()) {
            return;
        }
        boolean dragging = pointer.isMotion() && pointer.button() == PointerButton.LEFT;
        if ((!pointer.isPress() || pointer.button() != PointerButton.LEFT) && !dragging) {
            return;
        }
        boolean row = model.direction() == Direction.ROW;
        long position = row ? pointer.column() : pointer.row();
        long regionStart = (long) (row ? bounds.col() : bounds.row()) - model.first();
        int target = (int) Math.max(model.minimum(), Math.min(position - regionStart, model.last()));
        if (target != model.first()) {
            emit.accept(new Msg.Moved(target));
        }
    }
}
