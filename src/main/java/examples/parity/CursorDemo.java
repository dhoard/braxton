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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.view.Cursor;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 03 parity demo: native and virtual cursor presentation.
 *
 * <p>Two panes hold fixed text and a caret. Exactly one pane is focused, and both panes declare a
 * cursor, so the caret that is shown is the focused pane's even though the other pane paints later
 * and covers no part of it: a cursor whose owner is not the focused element is not eligible, which
 * is what keeps a blurred field from showing a second caret.</p>
 *
 * <ul>
 *   <li>{@code strategy=native} moves and shapes the terminal's own cursor; {@code virtual} asks
 *       the layout to reverse the caret's cell and leaves the terminal cursor hidden.</li>
 *   <li>{@code shape=} and {@code mode=} are what the caret asks for; {@code cursor=} is what was
 *       resolved. A virtual caret blinks from the scene's own clock, so {@code cursor=} alternates
 *       between {@code visible} and {@code hidden} only in virtual mode.</li>
 *   <li>{@code caret=row:column} is the model's caret in cells. The left pane's second row holds a
 *       two-column glyph, so stepping the caret one cell right onto its second half is resolved
 *       back to the glyph the caret is drawn on.</li>
 * </ul>
 *
 * <p>Everything the scene reports is on the status row, so a terminal verifier can assert the
 * scene without decoding styling - except for the virtual caret itself, which is a cell style.</p>
 */
public final class CursorDemo {

    /** Each pane is this wide, so both fit an eighty-column terminal side by side. */
    public static final int PANE_WIDTH = 40;

    private static final Style STATUS = Style.NONE.withBold();
    private static final Style FOCUSED_FRAME = Style.NONE.withBold();
    private static final Style FOCUSED_TITLE = Style.NONE.withBold();

    /** The one colour the scene applies, so a terminal's own cursor colour is also restored. */
    public static final Color CARET_COLOR = new Color(0x1E, 0x90, 0xFF);

    /** How often a virtual caret asks the scene for time; two ticks make one blink period. */
    public static final Duration BLINK_TICK = Duration.ofMillis(250);

    private static final String LEFT_TEXT = "alpha beta\nwide \u754c glyph\nthird line";
    private static final String RIGHT_TEXT = "right pane\nsecondary\nlast line";

    private static final ElementId LEFT = ElementId.of("left");
    private static final ElementId RIGHT = ElementId.of("right");

    private CursorDemo() {}

    /**
     * The scene state.
     *
     * @param text the draft the pane shows, one line per {@code \n}
     * @param row the caret's line, counted from zero
     * @param column the caret's cell on that line, counted from zero and allowed one past the end
     */
    public record Pane(String text, int row, int column) {

        /**
         * A pane whose caret starts at the beginning of its first line.
         *
         * @param text the pane's text
         */
        public Pane(String text) {
            this(text, 0, 0);
        }

        /** The pane's lines. */
        public List<String> lines() {
            return List.of(text.split("\n", -1));
        }

        /** The cells the caret's line occupies. */
        public int lineCells() {
            List<String> lines = lines();
            int index = Math.min(row, lines.size() - 1);
            return TextMeasure.columns(lines.get(index), WidthPolicy.PINNED);
        }

        /**
         * The same draft with the caret moved one cell, clamped to the text.
         *
         * <p>Columns move by cells rather than by graphemes on purpose: a two-column glyph occupies
         * two cells, so a caret can be put on the second half of one, which is where the layout has
         * to decide which cell the terminal cursor may address.</p>
         */
        Pane moved(int rowDelta, int columnDelta) {
            List<String> lines = lines();
            int movedRow = Math.max(0, Math.min(lines.size() - 1, row + rowDelta));
            Pane atRow = movedRow == row ? this : new Pane(text, movedRow, 0);
            int movedColumn = Math.max(0, Math.min(atRow.lineCells(), atRow.column + columnDelta));
            return new Pane(text, movedRow, movedColumn);
        }
    }

    /**
     * The scene state.
     *
     * @param left the left pane
     * @param right the right pane
     * @param leftFocused whether the left pane holds focus
     * @param strategy which presentation the panes ask for
     * @param shape the shape a native cursor takes
     * @param mode whether the caret blinks
     * @param color the caret colour, or null for the terminal's own
     * @param elapsedMillis the scene's own clock, advanced only while a virtual caret blinks
     */
    public record Model(
            Pane left,
            Pane right,
            boolean leftFocused,
            Cursor.Strategy strategy,
            Cursor.Shape shape,
            Cursor.Mode mode,
            Color color,
            long elapsedMillis) {

        /** The focused pane's caret, which is the one the cursor presents. */
        public Pane caret() {
            return leftFocused ? left : right;
        }

        Model withPane(boolean leftPane, Pane value) {
            return new Model(
                    leftPane ? value : left,
                    leftPane ? right : value,
                    leftFocused,
                    strategy,
                    shape,
                    mode,
                    color,
                    elapsedMillis);
        }

        Model withLeftFocused(boolean value) {
            return new Model(left, right, value, strategy, shape, mode, color, elapsedMillis);
        }

        Model withStrategy(Cursor.Strategy value) {
            return new Model(left, right, leftFocused, value, shape, mode, color, elapsedMillis);
        }

        Model withShape(Cursor.Shape value) {
            return new Model(left, right, leftFocused, strategy, value, mode, color, elapsedMillis);
        }

        Model withMode(Cursor.Mode value) {
            return new Model(left, right, leftFocused, strategy, shape, value, color, elapsedMillis);
        }

        Model withColor(Color value) {
            return new Model(left, right, leftFocused, strategy, shape, mode, value, elapsedMillis);
        }

        Model withElapsed(long value) {
            return new Model(left, right, leftFocused, strategy, shape, mode, color, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /** Moves the focused pane's caret. */
        record Move(int rows, int columns) implements Msg {}

        /** Moves focus between the two panes. */
        record ToggleFocus() implements Msg {}

        /** Switches between the terminal's cursor and a painted one. */
        record ToggleStrategy() implements Msg {}

        /** Cycles the shape a native cursor takes. */
        record CycleShape() implements Msg {}

        /** Switches between a blinking and a steady caret. */
        record ToggleMode() implements Msg {}

        /** Cycles the caret colour between the terminal's own and one the scene picks. */
        record CycleColor() implements Msg {}

        /** One blink tick of the scene's own clock. */
        record Tick() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Cursor",
                        KeyAction.<Msg>of("toggle-strategy", new Msg.ToggleStrategy(), "m", "native/virtual", "m"),
                        KeyAction.<Msg>of("cycle-shape", new Msg.CycleShape(), "s", "cursor shape", "s"),
                        KeyAction.<Msg>of("toggle-mode", new Msg.ToggleMode(), "b", "blink/steady", "b"),
                        KeyAction.<Msg>of("cycle-color", new Msg.CycleColor(), "c", "caret colour", "c")),
                KeyMap.group(
                        "Move",
                        KeyAction.<Msg>of("left", new Msg.Move(0, -1), "\u2190", "caret left", "left", "h"),
                        KeyAction.<Msg>of("right", new Msg.Move(0, 1), "\u2192", "caret right", "right", "l"),
                        KeyAction.<Msg>of("up", new Msg.Move(-1, 0), "\u2191", "caret up", "up", "k"),
                        KeyAction.<Msg>of("down", new Msg.Move(1, 0), "\u2193", "caret down", "down", "j")),
                KeyMap.group(
                        "General",
                        KeyAction.<Msg>of("focus", new Msg.ToggleFocus(), "tab", "next pane", "tab"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /** The demo scene: two panes, a status row and the help lines. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(new Model(
                    new Pane(LEFT_TEXT),
                    new Pane(RIGHT_TEXT),
                    true,
                    Cursor.Strategy.NATIVE,
                    Cursor.Shape.BLOCK,
                    Cursor.Mode.BLINK,
                    null,
                    0L));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Move move ->
                    Update.next(
                            model.withPane(model.leftFocused(), model.caret().moved(move.rows(), move.columns())));
                case Msg.ToggleFocus toggle -> Update.next(model.withLeftFocused(!model.leftFocused()));
                case Msg.ToggleStrategy toggle ->
                    Update.next(model.withStrategy(
                            model.strategy() == Cursor.Strategy.NATIVE
                                    ? Cursor.Strategy.VIRTUAL
                                    : Cursor.Strategy.NATIVE));
                case Msg.CycleShape cycle -> Update.next(model.withShape(nextShape(model.shape())));
                case Msg.ToggleMode toggle ->
                    Update.next(
                            model.withMode(model.mode() == Cursor.Mode.BLINK ? Cursor.Mode.STEADY : Cursor.Mode.BLINK));
                case Msg.CycleColor cycle -> Update.next(model.withColor(model.color() == null ? CARET_COLOR : null));
                case Msg.Tick tick -> Update.next(model.withElapsed(model.elapsedMillis() + BLINK_TICK.toMillis()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * Only a virtual caret needs a clock: the terminal blinks a native one itself, so a scene
         * that asks for no painted caret runs no timer at all.
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            if (model.strategy() != Cursor.Strategy.VIRTUAL || model.mode() != Cursor.Mode.BLINK) {
                return Subscription.none();
            }
            return Subscription.interval(SubscriptionId.of("blink"), BLINK_TICK, time -> new Msg.Tick());
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            View<Msg> panes = Views.row(
                    Views.fixed(PANE_WIDTH, pane("left", LEFT, model.leftFocused(), model.left(), model)),
                    Views.fixed(PANE_WIDTH, pane("right", RIGHT, !model.leftFocused(), model.right(), model)));
            // The panes hold focus but consume nothing, so every gesture reaches the scene's own
            // bindings: a cursor demo has no editing to do, and the keys are the demonstration.
            return Views.onKeys(
                    keys.bindings(),
                    Views.column(
                            Views.text(presentation(model), STATUS),
                            Views.text(position(model), STATUS),
                            Views.gap(1),
                            panes,
                            Views.gap(1),
                            Help.view(keys, false, width)));
        }

        /**
         * One pane: its text, its caret and its focus identity.
         *
         * <p>Both panes declare a cursor even though only one may be shown. The cursor is owned by
         * the pane's focus identity, so focus - not paint order - decides which caret the terminal
         * shows, and the panes cannot both claim it.</p>
         */
        private static View<Msg> pane(String title, ElementId id, boolean focused, Pane pane, Model model) {
            List<View<Msg>> lines = new ArrayList<>();
            for (String line : pane.lines()) {
                lines.add(Views.text(line));
            }
            Cursor cursor = cursor(model, pane).withOwner(id);
            View<Msg> content = Views.cursor(cursor, Views.column(lines));
            return Views.focus(
                    id,
                    focused,
                    Views.border(
                            Border.SINGLE,
                            StyledText.of(title, focused ? FOCUSED_TITLE : Style.NONE),
                            focused ? FOCUSED_FRAME : Style.NONE,
                            content));
        }

        /**
         * The caret as the scene asks for it: the model's position, strategy, shape and blink mode.
         *
         * <p>A virtual caret reads the scene's clock here, because nothing else will blink a painted
         * cell; a native caret is passed through unchanged and the terminal blinks it.</p>
         */
        private static Cursor cursor(Model model, Pane pane) {
            return new Cursor(
                            model.shape(),
                            model.mode(),
                            model.strategy(),
                            model.color(),
                            pane.row(),
                            pane.column(),
                            Optional.empty())
                    .atTime(model.elapsedMillis());
        }

        private static Cursor.Shape nextShape(Cursor.Shape shape) {
            return switch (shape) {
                case BLOCK -> Cursor.Shape.UNDERLINE;
                case UNDERLINE -> Cursor.Shape.BAR;
                case BAR -> Cursor.Shape.BLOCK;
            };
        }

        /**
         * The presentation row: what the scene asks the caret to be, and what a virtual caret shows
         * at the scene's own clock. A native caret is always {@code cursor=visible} here, because the
         * terminal does its blinking.
         */
        private static String presentation(Model model) {
            return "strategy=" + lower(model.strategy())
                    + " shape=" + lower(model.shape())
                    + " mode=" + lower(model.mode())
                    + " color="
                    + (model.color() == null ? "none" : model.color().hex())
                    + " cursor=" + (cursor(model, model.caret()).visible() ? "visible" : "hidden");
        }

        /** The position row: which pane holds focus, and where its caret is in cells. */
        private static String position(Model model) {
            Pane caret = model.caret();
            return "focus=" + (model.leftFocused() ? "left" : "right") + " caret=" + caret.row() + ":" + caret.column();
        }

        private static String lower(Enum<?> value) {
            return value.name().toLowerCase(Locale.ROOT);
        }
    }
}
