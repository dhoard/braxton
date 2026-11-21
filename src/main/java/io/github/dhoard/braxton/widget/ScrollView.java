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
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A focusable vertical viewport over text the application owns.
 *
 * <p>A {@code ScrollView} exposes a fixed number of screen rows of a much longer list and scrolls
 * that window with explicit navigation. It is the transcript/log pane of an MVU application: the
 * application publishes its content, the pane owns only the offset, the follow-end flag, its
 * visible height and whether it is focused. There is no terminal I/O, no effect, no timer and no
 * cached view in the state.</p>
 *
 * <h2>Content</h2>
 *
 * <p>Content is a {@link Content} snapshot supplied by the application through {@link
 * Msg.ContentChanged}. The model holds that snapshot by reference; the widget never copies, splits
 * or concatenates it, and never reads a row it is not about to paint. A frame reads {@link
 * Content#lineCount()} once and one row per visible line, so the cost of a frame follows the
 * window, not the transcript. Publishing a new snapshot is the application's decision and its cost:
 * {@link Content#of} copies the list of references once, while an application that implements
 * {@link Content} over its own immutable structure publishes without copying anything.</p>
 *
 * <p>A line is <em>one display row</em>, already wrapped by the application: viewport slicing and
 * paging then correspond exactly to screen rows. {@link StyledText} keeps styles, links and empty
 * rows; a row wider than the pane is clipped to the pane's columns rather than wrapped onto a
 * second row, because a wrap would change how many rows the window covers. A break inside one row
 * is not a second row either, and the pane clips at it. An application that wants wrapping must
 * wrap to its pane width before publishing; the pane does not reflow on resize, and it cannot,
 * because it deliberately does not know the width it is painted at.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The visible height is explicit: {@link Model#visibleRows()} on every message and rendering
 * decision. It is not {@code ViewContext.viewport()}, because a component mounted in a border,
 * padding, row or shared column receives its parent's viewport - {@code Mount} passes the parent's
 * context through unchanged - and the root height is not this pane's height. The parent reserves
 * the same number of rows in its layout (typically {@code Elements.fixed(model.visibleRows(), ...)}
 * around {@link #view}) and tells the pane when its allocation changes with {@link
 * Msg.ViewportChanged}. One number therefore decides both the rows the pane paints and the rows
 * {@link Msg.PageUp}/{@link Msg.PageDown} move by.</p>
 *
 * <h2>Navigation and follow-end</h2>
 *
 * <p>{@link Msg.Up}/{@link Msg.Down} move by visual rows, {@link Msg.PageUp}/{@link
 * Msg.PageDown} by the visible height, {@link Msg.Home} goes to the first row and {@link Msg.End}
 * to the last window. Non-positive counts and moves that the content bounds already forbid are
 * no-ops that keep the incoming model instance. With follow-end enabled, the window stays on the
 * last rows while content is appended; an upward move that actually changes the offset releases
 * it, and only {@link Msg.End} arms it again - reaching the bottom with {@link Msg.Down} does not,
 * so a pane the user stepped down by hand does not silently start following. Content replacement,
 * truncation and resizes clamp the offset to a valid window instead of throwing.</p>
 *
 * <h2>Focus and input</h2>
 *
 * <p>Focus is model data, exactly as in {@link Editor} and {@link Selector}: the application owns
 * it and sets it through {@link Model#withFocus}, and a reduction never changes it. The pane
 * declares one focus element around its rows and consumes only {@link #defaultKeys()}'s gestures -
 * the same ones {@link #actionFor} maps - so an unfocused pane receives no keys, a focused one never
 * takes text input, and Ctrl-/Alt-prefixed gestures stay available to the parent. The pane paints no
 * focus decoration: a focus indicator is the application's presentation choice, while the selection
 * and the scrollbar are the pane's own and are enabled by {@link Config}.</p>
 *
 * <h2>Selection, scrollbar and pointer</h2>
 *
 * <p>{@link Selection} names a caret row and the anchor the range runs from. It lives in the model,
 * so an application can read the selected rows, copy them and clear them; the reduction clamps it to
 * the content a new snapshot holds. The pointer places the caret on a click and extends the range
 * while the left button is held, the wheel scrolls three rows, and Shift with a navigation key
 * extends the same range from the keyboard. A selection is painted over a row's own style with
 * {@link Config#selection()}, and the pane paints the resulting rows as they are.</p>
 *
 * <p>{@link Bar} is painted in one column on the pane's right edge when it is enabled and the
 * content is taller than the window: the thumb shows the fraction of the content the window covers
 * and where it sits, and the track fills the rest. {@link #thumb} exposes that geometry, so an
 * application that paints its own scrollbar and one that enables the pane's agree about it.</p>
 *
 * <p>For logical lines the pane should wrap itself, a horizontal offset, gutters and highlighted
 * ranges, use {@link TextViewport}: it is the richer counterpart of this pane over the same MVU
 * contract. A {@code ScrollView} stays the cheap component for rows the application has already
 * prepared, and its content contract is unchanged.</p>
 *
 * <p>This component has no semantic outputs; it is {@link Never}-typed, like any component whose
 * interactions are all state changes the parent can observe.</p>
 */
public final class ScrollView
        implements Component<ScrollView.Model, ScrollView.Msg, Never>, Interactive<ScrollView.Model, ScrollView.Msg> {

    /**
     * The application's display rows: an immutable, line-indexed snapshot.
     *
     * <p>An implementation must answer the same way for every call while a model holds it, must
     * answer {@link #lineCount()} in constant time and {@link #line(int)} for one index in constant
     * time over an immutable structure, and must treat one instance as one content version. Two
     * snapshots holding equal lines are still two versions, so implementations should keep
     * reference equality (the default): {@link Model} compares content by {@code equals}, and a
     * value-based {@code equals} would make every model comparison proportional to the content it
     * holds. The pane never mutates a snapshot; replacing one is a new {@link Msg.ContentChanged}.
     * A row must not be null.</p>
     */
    public interface Content {

        /**
         * The number of display rows in this snapshot.
         *
         * @return a non-negative row count, answered in constant time
         */
        int lineCount();

        /**
         * The row at {@code index}.
         *
         * @param index the row to read, in {@code [0, lineCount())}
         * @return that row, never null
         */
        StyledText line(int index);

        /**
         * A snapshot with no rows.
         *
         * @return an empty content value
         */
        static Content empty() {
            return Snapshot.EMPTY;
        }

        /**
         * A snapshot of {@code lines}, copied once by reference.
         *
         * <p>The list is copied so a caller's list cannot change under a model; the rows are
         * application-owned values and are not copied. For zero-copy publishing implement {@link
         * Content} directly over an immutable structure of your own.</p>
         *
         * @param lines the display rows in paint order, or null for empty content
         * @return an immutable snapshot of those rows
         */
        static Content of(List<StyledText> lines) {
            return lines == null || lines.isEmpty() ? Snapshot.EMPTY : new Snapshot(lines);
        }
    }

    /**
     * Immutable list-backed snapshot. Reference equality is the content-version identity {@link
     * Content} documents.
     */
    private static final class Snapshot implements Content {
        private static final Content EMPTY = new Snapshot(List.of());

        private final List<StyledText> lines;

        private Snapshot(List<StyledText> lines) {
            this.lines = List.copyOf(lines);
        }

        @Override
        public int lineCount() {
            return lines.size();
        }

        @Override
        public StyledText line(int index) {
            return lines.get(index);
        }

        @Override
        public String toString() {
            return "Content[" + lines.size() + " lines]";
        }
    }

    /**
     * A caret row and the row the selected range runs from, both display-row indices.
     *
     * <p>A pane selects whole display rows: its content is rows the application already prepared, so
     * there are no cells to name. {@link #NONE} is no selection at all, {@link #at} is a caret with
     * no range, and a range is the closed span between the anchor and the caret in either direction.
     * A negative index means no selection, so a selection cannot claim a row the content does not
     * have once {@link Model} clamps it.</p>
     *
     * @param anchor the row the range runs from, or -1 when nothing is selected
     * @param caret the row the range runs to, or -1 when nothing is selected
     */
    public record Selection(int anchor, int caret) {

        /**
         * No caret and no range.
         */
        public static final Selection NONE = new Selection(-1, -1);

        /**
         * Keeps a selection either whole or absent.
         */
        public Selection {
            if (anchor < 0 || caret < 0) {
                anchor = -1;
                caret = -1;
            }
        }

        /**
         * A caret on {@code line} with no range.
         *
         * @param line the row the caret sits on; negative is {@link #NONE}
         * @return the selection
         */
        public static Selection at(int line) {
            return line < 0 ? NONE : new Selection(line, line);
        }

        /**
         * Whether a row is named at all.
         *
         * @return true when this selection has a caret
         */
        public boolean hasCaret() {
            return caret >= 0;
        }

        /**
         * Whether more than one row is selected.
         *
         * @return true when the anchor and the caret name different rows
         */
        public boolean hasRange() {
            return hasCaret() && anchor != caret;
        }

        /**
         * The first selected row, whichever direction the selection was made in.
         *
         * @return the lower of the anchor and the caret, or -1 for {@link #NONE}
         */
        public int start() {
            return hasCaret() ? Math.min(anchor, caret) : -1;
        }

        /**
         * The last selected row, whichever direction the selection was made in.
         *
         * @return the higher of the anchor and the caret, or -1 for {@link #NONE}
         */
        public int end() {
            return hasCaret() ? Math.max(anchor, caret) : -1;
        }

        /**
         * How many rows a range covers.
         *
         * @return the row count of the range, zero when there is no range
         */
        public int lines() {
            return hasRange() ? end() - start() + 1 : 0;
        }

        /**
         * Whether a row lies inside the range.
         *
         * @param line the display-row index to test
         * @return true when the row is inside a multi-row selection
         */
        public boolean contains(int line) {
            return hasRange() && line >= start() && line <= end();
        }

        /**
         * Whether a row is painted as selected.
         *
         * <p>It is {@link #contains} widened by the caret: a press that has selected no range yet
         * still paints the row the caret is on, which is what shows a user that the click landed.</p>
         *
         * @param line the display-row index to test
         * @return true when the row is inside the range or is the caret's row
         */
        public boolean covers(int line) {
            return hasCaret() && line >= start() && line <= end();
        }

        /**
         * The same selection clamped to content of {@code lines} rows, absent when it holds none.
         */
        static Selection clamped(Selection selection, int lines) {
            if (selection == null || !selection.hasCaret() || lines <= 0) {
                return NONE;
            }
            int last = lines - 1;
            return new Selection(Math.min(selection.anchor(), last), Math.min(selection.caret(), last));
        }
    }

    /**
     * Scroll state and application-owned content.
     *
     * <p>The canonical constructor keeps the state valid: a negative height becomes zero, and the
     * first visible row is clamped to the last window the content allows - the end of the content
     * while following, otherwise as close to the previous offset as the content permits. Every
     * message is therefore free to express an offset the constructor normalizes, and no view has to
     * correct state.</p>
     *
     * @param topLine the index of the first visible row
     * @param followEnd whether the window follows appended content
     * @param focused whether the application has focused this pane
     * @param visibleRows the height of the pane, in rows
     * @param content the application's current display rows
     * @param selection the caret and the range the application or the user selected
     */
    public record Model(
            int topLine, boolean followEnd, boolean focused, int visibleRows, Content content, Selection selection) {

        /**
         * Creates a {@code Model} value.
         *
         * @param topLine the index of the first visible row
         * @param followEnd whether the window follows appended content
         * @param focused whether the application has focused this pane
         * @param visibleRows the height of the pane, in rows
         * @param content the application's current display rows
         * @param selection the caret and the range the application or the user selected
         */
        public Model {
            content = content == null ? Content.empty() : content;
            visibleRows = Math.max(0, visibleRows);
            selection = Selection.clamped(selection, content.lineCount());
            int last = lastTopLine(content, visibleRows);
            topLine = followEnd ? last : Math.max(0, Math.min(topLine, last));
        }

        /**
         * The same state with nothing selected.
         *
         * @param topLine the index of the first visible row
         * @param followEnd whether the window follows appended content
         * @param focused whether the application has focused this pane
         * @param visibleRows the height of the pane, in rows
         * @param content the application's current display rows
         */
        public Model(int topLine, boolean followEnd, boolean focused, int visibleRows, Content content) {
            this(topLine, followEnd, focused, visibleRows, content, Selection.NONE);
        }

        /**
         * The same state with a new focus.
         *
         * @param value whether the pane owns focus
         * @return this instance when the focus did not change, otherwise the new state
         */
        public Model withFocus(boolean value) {
            return value == focused ? this : new Model(topLine, followEnd, value, visibleRows, content, selection);
        }

        /**
         * The same state with a new selection, clamped to the content it names.
         *
         * @param value the selection to adopt; null clears it
         * @return this instance when the selection did not change, otherwise the new state
         */
        public Model withSelection(Selection value) {
            return selection.equals(value) ? this : new Model(topLine, followEnd, focused, visibleRows, content, value);
        }

        /**
         * The number of display rows currently published.
         *
         * @return the row count of {@link #content()}
         */
        public int lineCount() {
            return content.lineCount();
        }
    }

    /**
     * One scrolling gesture or one fact about the content or the pane.
     */
    public sealed interface Msg {

        /**
         * Moves up by visual rows; a non-positive count changes nothing.
         *
         * @param lines how many rows to move up
         */
        record Up(int lines) implements Msg {
            /**
             * Creates a {@code Up} value.
             *
             * @param lines how many rows to move up
             */
            public Up {
                lines = Math.max(0, lines);
            }
        }

        /**
         * Moves down by visual rows; a non-positive count changes nothing.
         *
         * @param lines how many rows to move down
         */
        record Down(int lines) implements Msg {
            /**
             * Creates a {@code Down} value.
             *
             * @param lines how many rows to move down
             */
            public Down {
                lines = Math.max(0, lines);
            }
        }

        /**
         * Moves up by the pane's visible height: one page is exactly one window, no overlap.
         */
        record PageUp() implements Msg {}

        /**
         * Moves down by the pane's visible height: one page is exactly one window, no overlap.
         */
        record PageDown() implements Msg {}

        /**
         * Moves to the first row, releasing follow-end when the window can move at all.
         */
        record Home() implements Msg {}

        /**
         * Moves to the last window, arming follow-end when the configuration allows it.
         */
        record End() implements Msg {}

        /**
         * Places the caret on a row and clears the range: a pointer press or a single click.
         *
         * <p>The window moves only as far as it must to show the caret, and follow-end is released
         * when it does. An index outside the content clamps to the nearest row; content with no rows
         * keeps the selection absent.</p>
         *
         * @param line the display row the caret is placed on
         */
        record Select(int line) implements Msg {}

        /**
         * Extends the range to a row, keeping the anchor: a pointer drag or a Shift gesture.
         *
         * <p>A pane with no caret anchors on its first visible row, so a drag that starts without a
         * click still selects the span the user swept.</p>
         *
         * @param line the display row the range is extended to
         */
        record ExtendTo(int line) implements Msg {}

        /**
         * Extends the range up by visual rows.
         *
         * @param lines how many rows to extend up; a non-positive count changes nothing
         */
        record SelectUp(int lines) implements Msg {
            /**
             * Creates a {@code SelectUp} value.
             *
             * @param lines how many rows to extend up; a non-positive count changes nothing
             */
            public SelectUp {
                lines = Math.max(0, lines);
            }
        }

        /**
         * Extends the range down by visual rows.
         *
         * @param lines how many rows to extend down; a non-positive count changes nothing
         */
        record SelectDown(int lines) implements Msg {
            /**
             * Creates a {@code SelectDown} value.
             *
             * @param lines how many rows to extend down; a non-positive count changes nothing
             */
            public SelectDown {
                lines = Math.max(0, lines);
            }
        }

        /**
         * Extends the range up by the pane's visible height.
         */
        record SelectPageUp() implements Msg {}

        /**
         * Extends the range down by the pane's visible height.
         */
        record SelectPageDown() implements Msg {}

        /**
         * Extends the range to the first row.
         */
        record SelectHome() implements Msg {}

        /**
         * Extends the range to the last row.
         */
        record SelectEnd() implements Msg {}

        /**
         * Drops the caret and the range.
         */
        record ClearSelection() implements Msg {}

        /**
         * Publishes a new content snapshot, replacing the previous one.
         *
         * <p>A following window moves to the end of the new content; a window the user scrolled
         * back keeps its offset as far as the new content allows. A null snapshot is empty
         * content.</p>
         *
         * @param content the new display rows
         */
        record ContentChanged(Content content) implements Msg {
            /**
             * Creates a {@code ContentChanged} value.
             *
             * @param content the new display rows
             */
            public ContentChanged {
                content = content == null ? Content.empty() : content;
            }
        }

        /**
         * Reports the height the parent allocated to this pane, in rows.
         *
         * <p>A following window stays on the last rows; a window the user scrolled back keeps its
         * first visible row and is clamped only when the new height leaves the offset outside the
         * content. A negative height becomes zero, which paints and scrolls nothing.</p>
         *
         * @param rows the height the parent allocated to this pane
         */
        record ViewportChanged(int rows) implements Msg {
            /**
             * Creates a {@code ViewportChanged} value.
             *
             * @param rows the height the parent allocated to this pane
             */
            public ViewportChanged {
                rows = Math.max(0, rows);
            }
        }
    }

    /**
     * Immutable definition: the pane height and follow-end policy at initialization, and how the
     * pane paints a selection and a scrollbar.
     *
     * @param visibleRows the height reserved for the pane until {@link Msg.ViewportChanged} says
     *     otherwise
     * @param followEnd whether this pane follows appended content: the initial flag, and whether
     *     {@link Msg.End} may arm it again
     * @param scrollbar the scrollbar the pane paints on its right edge; never null
     * @param selection the style merged over every selected row; never null
     */
    public record Config(int visibleRows, boolean followEnd, Bar scrollbar, Style selection) {
        /**
         * Creates a {@code Config} value.
         *
         * @param visibleRows the height reserved for the pane until {@link Msg.ViewportChanged} says otherwise
         * @param followEnd whether this pane follows appended content: the initial flag, and whether {@link Msg.End} may arm it again
         * @param scrollbar the scrollbar the pane paints on its right edge; never null
         * @param selection the style merged over every selected row; never null
         */
        public Config {
            if (visibleRows < 0) {
                throw new IllegalArgumentException("visibleRows must not be negative");
            }
            scrollbar = scrollbar == null ? Bar.none() : scrollbar;
            selection = selection == null ? Style.NONE : selection;
        }

        /**
         * The same defined pane with no scrollbar and no selection style.
         *
         * @param visibleRows the height reserved for the pane
         * @param followEnd whether this pane may follow appended content
         */
        public Config(int visibleRows, boolean followEnd) {
            this(visibleRows, followEnd, Bar.none(), Style.NONE);
        }

        /**
         * The same definition with a different scrollbar.
         *
         * @param value the scrollbar to paint; null is {@link Bar#none()}
         * @return the new definition
         */
        public Config withScrollbar(Bar value) {
            return new Config(visibleRows, followEnd, value, selection);
        }

        /**
         * The same definition with a different selection style.
         *
         * @param value the style merged over selected rows; null is {@link Style#NONE}
         * @return the new definition
         */
        public Config withSelection(Style value) {
            return new Config(visibleRows, followEnd, scrollbar, value);
        }

        /**
         * A ten-row pane that starts following appended content.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(10, true, Bar.none(), Style.NONE);
        }

        /**
         * The same definition with a different initial height.
         *
         * @param rows the initial pane height, which must not be negative
         * @return the new definition
         */
        public Config withVisibleRows(int rows) {
            return new Config(rows, followEnd, scrollbar, selection);
        }

        /**
         * The same definition with a different follow-end policy.
         *
         * @param value whether the pane may follow appended content
         * @return the new definition
         */
        public Config withFollowEnd(boolean value) {
            return new Config(visibleRows, value, scrollbar, selection);
        }
    }

    /**
     * The scrollbar the pane paints on its right edge.
     *
     * <p>It takes exactly one column, and only while the content is taller than the window: the
     * thumb shows how much of the content the window covers and where it sits, and the track fills
     * the rows around it. Both parts paint one glyph per row, so an enabled scrollbar changes the
     * width the rows are painted at and nothing else.</p>
     *
     * @param visible whether the pane paints its own scrollbar
     * @param thumb the style the thumb cells paint with; never null
     * @param track the style the track cells paint with; never null
     */
    public record Bar(boolean visible, Style thumb, Style track) {

        /**
         * The glyph a thumb cell paints.
         */
        public static final String THUMB = BarGeometry.THUMB;

        /**
         * The glyph a track cell paints.
         */
        public static final String TRACK = BarGeometry.TRACK;

        /**
         * Substitutes a plain style for a missing one.
         */
        public Bar {
            thumb = thumb == null ? Style.NONE : thumb;
            track = track == null ? Style.NONE : track;
        }

        /**
         * No scrollbar at all.
         *
         * @return the disabled scrollbar
         */
        public static Bar none() {
            return new Bar(false, Style.NONE, Style.NONE);
        }

        /**
         * An enabled scrollbar with its two styles.
         *
         * @param thumb the style the thumb paints with; null is plain
         * @param track the style the track paints with; null is plain
         * @return the scrollbar
         */
        public static Bar of(Style thumb, Style track) {
            return new Bar(true, thumb, track);
        }

        /**
         * The same scrollbar, shown or hidden.
         *
         * @param value whether the pane paints this scrollbar
         * @return the new scrollbar
         */
        public Bar withVisible(boolean value) {
            return new Bar(value, thumb, track);
        }

        /**
         * The same scrollbar with a different thumb style.
         *
         * @param value the style the thumb paints with; null is plain
         * @return the new scrollbar
         */
        public Bar withThumb(Style value) {
            return new Bar(visible, value, track);
        }

        /**
         * The same scrollbar with a different track style.
         *
         * @param value the style the track paints with; null is plain
         * @return the new scrollbar
         */
        public Bar withTrack(Style value) {
            return new Bar(visible, thumb, value);
        }
    }

    /**
     * Where the scrollbar's thumb sits in the window.
     *
     * @param start the first window row the thumb covers, counted from the window's top
     * @param size the rows the thumb covers; zero when the content fits the window
     */
    public record Thumb(int start, int size) {}

    /**
     * The thumb geometry a state paints.
     *
     * <p>The thumb covers the fraction of the window the content fills and sits at the fraction of
     * the content the window has scrolled past, both rounded to whole rows and clamped so the thumb
     * is never empty, never longer than the window and never leaves it. Content that fits the window
     * has no thumb at all, which is what lets the pane paint no scrollbar for it. The arithmetic is
     * the one {@link Scrollbar} uses, so a standalone bar painted beside the pane agrees with the
     * pane's own.</p>
     *
     * @param state the state to measure; required
     * @return the thumb geometry
     */
    public static Thumb thumb(Model state) {
        Objects.requireNonNull(state, "state");
        BarGeometry.Span span = BarGeometry.thumb(
                state.topLine(), state.visibleRows(), state.content().lineCount());
        return new Thumb(span.start(), span.size());
    }

    /**
     * A reduction result. The pane has no semantic outputs, so the intent is always absent.
     */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    /**
     * The identity of the pane's focus element; a mount scopes it to its own path.
     */
    private final Config config;

    /**
     * Creates the component.
     *
     * @param config the immutable pane definition; required
     */
    public ScrollView(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        return Update.next(new Model(0, config.followEnd(), true, config.visibleRows(), Content.empty()));
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        // The shared widget rule applies: a message that changes nothing keeps the incoming
        // instance, and a reduction never moves the focus the application owns.
        return WidgetUpdate.settled(
                model,
                message,
                action -> new Result(reduce(model, config, action), null),
                state -> state.withFocus(model.focused()));
    }

    /**
     * Describes the window this state covers.
     *
     * <p>The context is deliberately unused: its viewport is the one the pane's parent lays out
     * for, not the pane's own height. {@link Model#visibleRows()} is the height the parent reserved
     * and reported, and it is the only geometry a scroll decision may use.</p>
     */
    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        return scene(model);
    }

    /**
     * The pane's keys, from the same mapping {@link #actionFor} documents.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        actionFor(key).ifPresent(emit);
    }

    /**
     * Everything that is not a key gesture is offered to the same mapping, which answers none.
     */
    @Override
    public void onInput(InputEvent event, Model model, Consumer<Msg> emit) {
        actionFor(event).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * The pane's pointer handler: a press places the caret, a drag extends the range and the wheel
     * scrolls.
     *
     * <p>The row comes from the rectangle the pane was painted in, so a click on a
     * scrollbar-enabled pane still names the row under the pointer. A drag that leaves the pane
     * clamps to its nearest row, which keeps a selection extending instead of ending at the edge.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        pointer(pointer.localTo(bounds.row(), bounds.col()), model).ifPresent(emit);
    }

    /**
     * The rows the window covers, in paint order.
     *
     * <p>This is the only content a frame reads besides {@link Content#lineCount()}: one {@link
     * Content#line(int)} call per visible row, and none when the window is empty. Applications use
     * it to copy or inspect exactly what the pane shows.</p>
     *
     * @param state the current scroll state; required
     * @return the visible rows, empty when the pane or its content is empty
     */
    public static List<StyledText> visibleLines(Model state) {
        Objects.requireNonNull(state, "state");
        Content content = state.content();
        int count = content.lineCount();
        int first = Math.min(state.topLine(), count);
        int limit = Math.max(0, Math.min(state.visibleRows(), count - first));
        if (limit == 0) {
            return List.of();
        }
        List<StyledText> lines = new ArrayList<>(limit);
        for (int index = first; index < first + limit; index++) {
            lines.add(content.line(index));
        }
        return List.copyOf(lines);
    }

    /**
     * Maps one input event to a scrolling message, or empty when the gesture is not a pane
     * gesture.
     *
     * <p>Only the navigation and selection gestures a vertical pane owns are mapped, and only
     * without Ctrl or Alt: those prefixes are an application's shortcut space, and the parent's
     * capture bindings see every event before a focused pane in any case. Shift extends the
     * selection, which is what the shifted forms of the navigation keys are bound to. The wheel is
     * not a key event and is handled by the pane's pointer handler.</p>
     *
     * @param event the normalized input event to map
     * @return the gesture's message, or empty when this pane does not handle it
     */
    public static Optional<Msg> actionFor(InputEvent event) {
        if (!(event instanceof InputEvent.KeyInput keyInput)) {
            return Optional.empty();
        }
        Key key = keyInput.key();
        if (key.modifiers().ctrl() || key.modifiers().alt()) {
            return Optional.empty();
        }
        return defaultKeys().actionFor(key).map(KeyAction::message);
    }

    /**
     * The gestures a vertical pane defines, as the one definition its routing and any help view
     * read.
     *
     * <p>A pane maps the gestures {@link #actionFor} documents: the six navigation keys and their
     * shifted forms, which extend the selection instead of moving the caret alone. A pane can also
     * be driven by a key map the application builds - the map is a value, not a registration - and
     * the component routes this one itself.</p>
     *
     * @return the pane's gestures
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(KeyMap.group(
                "Scroll",
                KeyAction.<Msg>of("up", new Msg.Up(1), "\u2191", "up", "up"),
                KeyAction.<Msg>of("down", new Msg.Down(1), "\u2193", "down", "down"),
                KeyAction.<Msg>of("page-up", new Msg.PageUp(), "pgup", "page up", "pageup")
                        .withCompact(false),
                KeyAction.<Msg>of("page-down", new Msg.PageDown(), "pgdn", "page down", "pagedown")
                        .withCompact(false),
                KeyAction.<Msg>of("home", new Msg.Home(), "home", "first row", "home")
                        .withCompact(false),
                KeyAction.<Msg>of("end", new Msg.End(), "end", "last row", "end")
                        .withCompact(false),
                KeyAction.<Msg>of("select-up", new Msg.SelectUp(1), "\u21e7\u2191", "extend up", "shift+up")
                        .withCompact(false),
                KeyAction.<Msg>of("select-down", new Msg.SelectDown(1), "\u21e7\u2193", "extend down", "shift+down")
                        .withCompact(false),
                KeyAction.<Msg>of(
                                "select-page-up",
                                new Msg.SelectPageUp(),
                                "\u21e7pgup",
                                "extend page up",
                                "shift+pageup")
                        .withCompact(false),
                KeyAction.<Msg>of(
                                "select-page-down",
                                new Msg.SelectPageDown(),
                                "\u21e7pgdn",
                                "extend page down",
                                "shift+pagedown")
                        .withCompact(false),
                KeyAction.<Msg>of("select-home", new Msg.SelectHome(), "\u21e7home", "extend to first", "shift+home")
                        .withCompact(false),
                KeyAction.<Msg>of("select-end", new Msg.SelectEnd(), "\u21e7end", "extend to last", "shift+end")
                        .withCompact(false)));
    }

    /**
     * Applies one gesture. Never performs I/O and never mutates the input state.
     */
    private static Model reduce(Model state, Config config, Msg action) {
        return switch (action) {
            case Msg.Up up -> movedUp(state, up.lines());
            case Msg.Down down -> movedDown(state, down.lines());
            case Msg.PageUp pageUp -> movedUp(state, state.visibleRows());
            case Msg.PageDown pageDown -> movedDown(state, state.visibleRows());
            case Msg.Home home -> first(state);
            case Msg.End end -> last(state, config.followEnd());
            case Msg.Select select -> selected(state, select.line());
            case Msg.ExtendTo extend -> extended(state, extend.line());
            case Msg.SelectUp up -> extendedBy(state, -up.lines());
            case Msg.SelectDown down -> extendedBy(state, down.lines());
            case Msg.SelectPageUp pageUp -> extendedBy(state, -state.visibleRows());
            case Msg.SelectPageDown pageDown -> extendedBy(state, state.visibleRows());
            case Msg.SelectHome home -> extended(state, 0);
            case Msg.SelectEnd end -> extended(state, lastContentLine(state));
            case Msg.ClearSelection clear -> state.withSelection(Selection.NONE);
            // Both facts about the content and the pane keep the state they carry and let the
            // model normalize the offset: a following window re-anchors to the end, and a window
            // the user scrolled back keeps its first row as far as the content allows. The new
            // content also clamps the selection to the rows it has.
            case Msg.ContentChanged changed ->
                new Model(
                        state.topLine(),
                        state.followEnd(),
                        state.focused(),
                        state.visibleRows(),
                        changed.content(),
                        state.selection());
            case Msg.ViewportChanged viewport ->
                new Model(
                        state.topLine(),
                        state.followEnd(),
                        state.focused(),
                        viewport.rows(),
                        state.content(),
                        state.selection());
        };
    }

    /**
     * Places the caret on one row and drops the range, showing the caret.
     *
     * <p>A click is a caret, not a range: the anchor and the caret are the same row, so a drag that
     * follows a press extends from the row that was pressed.</p>
     */
    private static Model selected(Model state, int line) {
        int target = clampedLine(state, line);
        Model placed = new Model(
                state.topLine(), false, state.focused(), state.visibleRows(), state.content(), Selection.at(target));
        return shown(placed, target);
    }

    /**
     * Extends the range to one row, anchoring on the caret or, with no caret, on the first row.
     */
    private static Model extended(Model state, int line) {
        Selection current = state.selection();
        int anchor = current.hasCaret() ? current.anchor() : state.topLine();
        int target = clampedLine(state, line);
        Model placed = new Model(
                state.topLine(),
                false,
                state.focused(),
                state.visibleRows(),
                state.content(),
                new Selection(anchor, target));
        return shown(placed, target);
    }

    /**
     * Extends the range by rows from the caret, or from the window's first row when there is none.
     */
    private static Model extendedBy(Model state, int rows) {
        int from = state.selection().hasCaret() ? state.selection().caret() : state.topLine();
        long target = (long) from + rows;
        return extended(state, (int) Math.max(0L, Math.min(target, lastContentLine(state))));
    }

    /**
     * The same state scrolled only as far as it must be to show {@code line}.
     */
    private static Model shown(Model state, int line) {
        int rows = state.visibleRows();
        int top = state.topLine();
        if (line < top) {
            top = line;
        } else if (rows > 0 && line >= top + rows) {
            top = line - rows + 1;
        }
        return top == state.topLine()
                ? state
                : new Model(top, false, state.focused(), state.visibleRows(), state.content(), state.selection());
    }

    /**
     * One row index clamped to the content; zero for content that holds no rows.
     */
    private static int clampedLine(Model state, int line) {
        return Math.max(0, Math.min(line, lastContentLine(state)));
    }

    /**
     * The last row index the content has, or zero when it holds none.
     */
    private static int lastContentLine(Model state) {
        return Math.max(0, state.content().lineCount() - 1);
    }

    /**
     * One deliberate move upwards. Follow-end is released only by a move that really happened:
     * a window already at the first row has nothing to release, and a key press there must not
     * turn following off.
     */
    private static Model movedUp(Model state, int lines) {
        // Widened before the subtraction: a legal line count can be as large as an int holds.
        int target = (int) Math.max(0L, (long) state.topLine() - Math.max(0, lines));
        return target == state.topLine()
                ? state
                : new Model(target, false, state.focused(), state.visibleRows(), state.content());
    }

    /**
     * One move downwards. Reaching the last window by hand does not arm follow-end: only {@link
     * Msg.End} does, so a pane the user stepped to the bottom does not start following the next
     * appended row on its own.
     */
    private static Model movedDown(Model state, int lines) {
        long last = lastTopLine(state.content(), state.visibleRows());
        int target = (int) Math.min(last, (long) state.topLine() + Math.max(0, lines));
        return target == state.topLine()
                ? state
                : new Model(target, state.followEnd(), state.focused(), state.visibleRows(), state.content());
    }

    /**
     * The first row. Follow-end is released only when the window can move: content that fits the
     * pane has its first row at the end already, and losing following there would surprise the
     * next append.
     */
    private static Model first(Model state) {
        return state.topLine() == 0
                ? state
                : new Model(0, false, state.focused(), state.visibleRows(), state.content());
    }

    /**
     * The last window, with follow-end armed as far as the configuration allows.
     */
    private static Model last(Model state, boolean followAllowed) {
        return new Model(
                lastTopLine(state.content(), state.visibleRows()),
                followAllowed,
                state.focused(),
                state.visibleRows(),
                state.content());
    }

    /**
     * The largest first row that still fills the window from the content it has.
     */
    private static int lastTopLine(Content content, int visibleRows) {
        // Floored at zero and widened: content shorter than the window has one window, at the
        // first row, and a zero-row window ends up at the end of the content where it covers
        // nothing. Neither subtraction can leave the int range.
        long last = (long) content.lineCount() - Math.max(0, visibleRows);
        return (int) Math.max(0L, last);
    }

    /**
     * The rows the window covers, with the pane itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model state) {
        List<StyledText> window = visibleLines(state);
        Bar bar = config.scrollbar();
        // A pane that paints no scrollbar never measures one: the frame's cost is the window's rows
        // and one line count, whether or not the application asked for the bar.
        Thumb thumb = bar.visible() ? thumb(state) : new Thumb(0, 0);
        boolean barPainted = bar.visible() && thumb.size() > 0;
        List<Element<Msg>> rows = new ArrayList<>(window.size());
        for (int index = 0; index < window.size(); index++) {
            StyledText line = window.get(index);
            if (state.selection().covers(state.topLine() + index)) {
                line = line.withStyle(config.selection());
            }
            // One fixed row per content row. The text leaf wraps to the width it is given, so a row
            // wider than the pane would otherwise claim a second screen row and push the rows
            // below it out of the window; the one-row wrapper clips it at the pane's own width
            // instead, and the window keeps showing exactly the rows it scrolls by.
            Element<Msg> text = Elements.fixed(1, Elements.text(line));
            if (!barPainted) {
                rows.add(text);
                continue;
            }
            boolean onThumb = index >= thumb.start() && index < thumb.start() + thumb.size();
            rows.add(Elements.row(
                    Elements.grow(text),
                    Elements.fixed(
                            1, Elements.text(onThumb ? Bar.THUMB : Bar.TRACK, onThumb ? bar.thumb() : bar.track()))));
        }
        return Elements.stack(Elements.column(rows), new Element.Leaf<>(this, state, Sizing.measured()));
    }

    private static Optional<Msg> pointer(PointerEvent event, Model state) {
        if (event.isWheel()) {
            return Optional.of(
                    switch (event.button()) {
                        case WHEEL_UP, WHEEL_LEFT -> new Msg.Up(WHEEL_ROWS);
                        default -> new Msg.Down(WHEEL_ROWS);
                    });
        }
        boolean dragging = event.isMotion() && event.button() == PointerButton.LEFT;
        if (!event.isPress() && !dragging) {
            return Optional.empty();
        }
        // The row is the window row the pointer covered, counted from the row this state names: the
        // handler is built from the same state the rows were painted from, so the two agree.
        int line = state.topLine() + event.row();
        return Optional.of(dragging ? new Msg.ExtendTo(line) : new Msg.Select(line));
    }

    /**
     * The rows one wheel notch scrolls, which is the step a terminal reader expects.
     */
    private static final int WHEEL_ROWS = 3;
}
