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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
 * the same number of rows in its layout (typically {@code Views.fixed(model.visibleRows(), ...)}
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
 * declares one focus element around its rows and consumes only {@link #actionFor}'s gestures, so an
 * unfocused pane receives no keys, a focused one never takes text input, and Ctrl-/Alt-prefixed
 * gestures stay available to the parent. The pane paints no focus decoration: selection,
 * scrollbars and a focus indicator are the application's presentation choice.</p>
 *
 * <p>This component has no semantic outputs; it is {@link Never}-typed, like any component whose
 * interactions are all state changes the parent can observe.</p>
 */
public final class ScrollView implements Component<ScrollView.Model, ScrollView.Msg, Never> {

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
     */
    public record Model(int topLine, boolean followEnd, boolean focused, int visibleRows,
            Content content) {

        public Model {
            content = content == null ? Content.empty() : content;
            visibleRows = Math.max(0, visibleRows);
            int last = lastTopLine(content, visibleRows);
            topLine = followEnd ? last : Math.max(0, Math.min(topLine, last));
        }

        /**
         * The same state with a new focus.
         *
         * @param value whether the pane owns focus
         * @return this instance when the focus did not change, otherwise the new state
         */
        public Model withFocus(boolean value) {
            return value == focused
                    ? this
                    : new Model(topLine, followEnd, value, visibleRows, content);
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

    /** One scrolling gesture or one fact about the content or the pane. */
    public sealed interface Msg {

        /**
         * Moves up by visual rows; a non-positive count changes nothing.
         *
         * @param lines how many rows to move up
         */
        record Up(int lines) implements Msg {
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
            public Down {
                lines = Math.max(0, lines);
            }
        }

        /** Moves up by the pane's visible height: one page is exactly one window, no overlap. */
        record PageUp() implements Msg {}

        /** Moves down by the pane's visible height: one page is exactly one window, no overlap. */
        record PageDown() implements Msg {}

        /** Moves to the first row, releasing follow-end when the window can move at all. */
        record Home() implements Msg {}

        /** Moves to the last window, arming follow-end when the configuration allows it. */
        record End() implements Msg {}

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
            public ViewportChanged {
                rows = Math.max(0, rows);
            }
        }
    }

    /**
     * Immutable definition: the pane height and follow-end policy at initialization.
     *
     * @param visibleRows the height reserved for the pane until {@link Msg.ViewportChanged} says
     *     otherwise
     * @param followEnd whether this pane follows appended content: the initial flag, and whether
     *     {@link Msg.End} may arm it again
     */
    public record Config(int visibleRows, boolean followEnd) {
        public Config {
            if (visibleRows < 0) {
                throw new IllegalArgumentException("visibleRows must not be negative");
            }
        }

        /**
         * A ten-row pane that starts following appended content.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(10, true);
        }

        /**
         * The same definition with a different initial height.
         *
         * @param rows the initial pane height, which must not be negative
         * @return the new definition
         */
        public Config withVisibleRows(int rows) {
            return new Config(rows, followEnd);
        }

        /**
         * The same definition with a different follow-end policy.
         *
         * @param value whether the pane may follow appended content
         * @return the new definition
         */
        public Config withFollowEnd(boolean value) {
            return new Config(visibleRows, value);
        }
    }

    /** A reduction result. The pane has no semantic outputs, so the intent is always absent. */
    private record Result(Model state, Never intent) implements WidgetResult<Model, Never> {}

    /** The identity of the pane's focus element; a mount scopes it to its own path. */
    private static final ElementId ELEMENT = ElementId.of("scroll");

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
        return Update.next(
                new Model(0, config.followEnd(), true, config.visibleRows(), Content.empty()));
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        // The shared widget rule applies: a message that changes nothing keeps the incoming
        // instance, and a reduction never moves the focus the application owns.
        return WidgetUpdate.settled(model, message,
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
    public View<Msg> view(Model model, ViewContext context) {
        return paint(model, model.focused(), Function.identity());
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
     * <p>Only the six navigation gestures a vertical pane owns are mapped, and only without Ctrl or
     * Alt: those prefixes are an application's shortcut space, and the parent's capture bindings
     * see every event before a focused pane in any case. Shift is accepted because it changes no
     * meaning for these keys. Mouse wheels are not mapped - the step is an application policy - so
     * an application that wants them binds {@code wheelup}/{@code wheeldown} to {@link Msg.Up} or
     * {@link Msg.Down} in a parent scope.</p>
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
        return switch (key.name()) {
            case "up" -> Optional.of(new Msg.Up(1));
            case "down" -> Optional.of(new Msg.Down(1));
            case "pageup" -> Optional.of(new Msg.PageUp());
            case "pagedown" -> Optional.of(new Msg.PageDown());
            case "home" -> Optional.of(new Msg.Home());
            case "end" -> Optional.of(new Msg.End());
            default -> Optional.empty();
        };
    }

    /** Applies one gesture. Never performs I/O and never mutates the input state. */
    private static Model reduce(Model state, Config config, Msg action) {
        return switch (action) {
            case Msg.Up up -> movedUp(state, up.lines());
            case Msg.Down down -> movedDown(state, down.lines());
            case Msg.PageUp pageUp -> movedUp(state, state.visibleRows());
            case Msg.PageDown pageDown -> movedDown(state, state.visibleRows());
            case Msg.Home home -> first(state);
            case Msg.End end -> last(state, config.followEnd());
            // Both facts about the content and the pane keep the state they carry and let the
            // model normalize the offset: a following window re-anchors to the end, and a window
            // the user scrolled back keeps its first row as far as the content allows.
            case Msg.ContentChanged changed ->
                    new Model(state.topLine(), state.followEnd(), state.focused(),
                            state.visibleRows(), changed.content());
            case Msg.ViewportChanged viewport ->
                    new Model(state.topLine(), state.followEnd(), state.focused(),
                            viewport.rows(), state.content());
        };
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
                : new Model(target, false, state.focused(), state.visibleRows(),
                        state.content());
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
                : new Model(target, state.followEnd(), state.focused(), state.visibleRows(),
                        state.content());
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

    /** The last window, with follow-end armed as far as the configuration allows. */
    private static Model last(Model state, boolean followAllowed) {
        return new Model(lastTopLine(state.content(), state.visibleRows()), followAllowed,
                state.focused(), state.visibleRows(), state.content());
    }

    /** The largest first row that still fills the window from the content it has. */
    private static int lastTopLine(Content content, int visibleRows) {
        // Floored at zero and widened: content shorter than the window has one window, at the
        // first row, and a zero-row window ends up at the end of the content where it covers
        // nothing. Neither subtraction can leave the int range.
        long last = (long) content.lineCount() - Math.max(0, visibleRows);
        return (int) Math.max(0L, last);
    }

    private static <Message> View<Message> paint(Model state, boolean focused,
            Function<Msg, Message> map) {
        List<StyledText> window = visibleLines(state);
        List<View<Message>> rows = new ArrayList<>(window.size());
        for (StyledText line : window) {
            // One fixed row per content row. The text view wraps to the width it is given, so a row
            // wider than the pane would otherwise claim a second screen row and push the rows
            // below it out of the window; the one-row wrapper clips it at the pane's own width
            // instead, and the window keeps showing exactly the rows it scrolls by.
            rows.add(Views.fixed(1, Views.text(line)));
        }
        return Views.focus(ELEMENT, focused, List.of(),
                event -> actionFor(event).map(map), Views.column(rows));
    }
}
