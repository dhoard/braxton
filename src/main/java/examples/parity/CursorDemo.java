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
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

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

    /**
     * Each pane is this wide, so both fit an eighty-column terminal side by side.
     */
    public static final int PANE_WIDTH = 40;

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style FOCUSED_FRAME = Style.NONE.withBold();
    private static final Style FOCUSED_TITLE = Style.NONE.withBold();

    /**
     * The one color the scene applies, so a terminal's own cursor color is also restored.
     */
    public static final Color CARET_COLOR = new Color(0x1E, 0x90, 0xFF);

    /**
     * How often a virtual caret asks the scene for time; two ticks make one blink period.
     */
    public static final Duration BLINK_TICK = Duration.ofMillis(250);

    private static final String LEFT_TEXT = "alpha beta\nwide \u754c glyph\nthird line";
    private static final String RIGHT_TEXT = "right pane\nsecondary\nlast line";

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

        /**
         * The pane's lines.
         *
         * @return the pane's lines
         */
        public List<String> lines() {
            return List.of(text.split("\n", -1));
        }

        /**
         * The cells the caret's line occupies.
         *
         * @return the cells the caret's line occupies
         */
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
     * @param color the caret color, or null for the terminal's own
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

        /**
         * The focused pane's caret, which is the one the cursor presents.
         *
         * @return the focused pane's caret, which is the one the cursor presents
         */
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

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * Moves the focused pane's caret.
         *
         * @param rows the number of rows
         * @param columns the number of columns
         */
        record Move(int rows, int columns) implements Msg {}

        /**
         * Moves focus between the two panes.
         */
        record ToggleFocus() implements Msg {}

        /**
         * Switches between the terminal's cursor and a painted one.
         */
        record ToggleStrategy() implements Msg {}

        /**
         * Cycles the shape a native cursor takes.
         */
        record CycleShape() implements Msg {}

        /**
         * Switches between a blinking and a steady caret.
         */
        record ToggleMode() implements Msg {}

        /**
         * Cycles the caret color between the terminal's own and one the scene picks.
         */
        record CycleColor() implements Msg {}

        /**
         * One blink tick of the scene's own clock.
         */
        record Tick() implements Msg {}

        /**
         * Ends the session.
         */
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
                        KeyAction.<Msg>of("cycle-color", new Msg.CycleColor(), "c", "caret color", "c")),
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

    /**
     * Runs the demo against the owned terminal.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Launches the interactive cursor demo.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The demo scene: two panes, a status row and the help lines.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

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
        public Element<Msg> view(Model model, ViewContext context) {
            return scene(model, context);
        }

        /**
         * The gestures this scene answers once no widget has taken them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            return keys.bindings();
        }

        /**
         * The scene as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            int width = context.viewport().cols();
            Element<Msg> panes = Elements.row(
                    Elements.fixed(PANE_WIDTH, pane("left", model.leftFocused(), model.left(), model)),
                    Elements.fixed(PANE_WIDTH, pane("right", !model.leftFocused(), model.right(), model)));
            // The panes hold focus but consume nothing, so every gesture reaches the scene's own
            // bindings: a cursor demo has no editing to do, and the keys are the demonstration.
            return Elements.column(
                    Elements.text(presentationRow(model), STATUS),
                    Elements.text(position(model), STATUS),
                    Elements.spacer(1),
                    panes,
                    Elements.spacer(1),
                    Help.element(keys, Help.Config.defaults(), false, width));
        }

        /**
         * One pane: the text it shows and the caret it places when it owns focus.
         *
         * <p>A caret is the widget's own answer, not a node beside it: the leaf that paints the lines
         * is the leaf that knows where the caret belongs, so the frame, the text and the caret can
         * never disagree about the rectangle. Only the focused pane answers, which is what makes the
         * terminal's one caret follow focus rather than paint order.</p>
         */
        private static Element<Msg> pane(String title, boolean focused, Pane pane, Model model) {
            Style frameStyle = focused ? FOCUSED_FRAME : Style.NONE;
            StyledText heading = StyledText.of(title, focused ? FOCUSED_TITLE : Style.NONE);
            Element<Msg> content = new Element.Leaf<PaneLeaf, Msg>(new PaneText(), new PaneLeaf(pane, focused, model));
            return Elements.frame(Border.SINGLE, heading, frameStyle, content);
        }

        /**
         * What a pane leaf paints and where its caret goes.
         */
        private record PaneLeaf(Pane pane, boolean focused, Model model) {}

        /**
         * A pane's lines as a leaf widget that answers for the caret it shows.
         */
        private static final class PaneText implements Widget<PaneLeaf, Msg> {

            @Override
            public Size measure(Size max, PaneLeaf model) {
                int cols = 0;
                for (String line : model.pane().lines()) {
                    cols = Math.max(cols, TextMeasure.columns(line));
                }
                return new Size(Math.min(max.rows(), model.pane().lines().size()), Math.min(max.cols(), cols));
            }

            @Override
            public void paint(Surface surface, Rect bounds, PaneLeaf model) {
                List<String> lines = model.pane().lines();
                for (int index = 0; index < lines.size() && index < bounds.rows(); index++) {
                    surface.writeLine(
                            bounds.row() + index,
                            bounds.col(),
                            bounds.cols(),
                            StyledText.plain(lines.get(index)),
                            WidthPolicy.PINNED);
                }
            }

            @Override
            public boolean focused(PaneLeaf model) {
                return model.focused();
            }

            @Override
            public io.github.dhoard.braxton.ui.Cursor cursor(Rect bounds, PaneLeaf model) {
                if (!model.focused()) {
                    return null;
                }
                Pane pane = model.pane();
                Cursor caret = CursorDemo.Scene.cursor(model.model(), pane);
                return new io.github.dhoard.braxton.ui.Cursor(
                        io.github.dhoard.braxton.ui.Cursor.Shape.valueOf(
                                caret.shape().name()),
                        io.github.dhoard.braxton.ui.Cursor.Mode.valueOf(
                                caret.mode().name()),
                        io.github.dhoard.braxton.ui.Cursor.Strategy.valueOf(
                                caret.strategy().name()),
                        caret.color(),
                        bounds.row() + pane.row(),
                        bounds.col() + pane.column());
            }
        }

        /**
         * The caret as the scene asks for it: the model's position, strategy, shape and blink mode.
         *
         * <p>A virtual caret reads the scene's clock here, because nothing else will blink a painted
         * cell; a native caret is passed through unchanged and the terminal blinks it.</p>
         */
        private static Cursor cursor(Model model, Pane pane) {
            return new Cursor(model.shape(), model.mode(), model.strategy(), model.color(), pane.row(), pane.column())
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
        private static String presentationRow(Model model) {
            return "strategy=" + lower(model.strategy())
                    + " shape=" + lower(model.shape())
                    + " mode=" + lower(model.mode())
                    + " color="
                    + (model.color() == null ? "none" : model.color().hex())
                    + " cursor=" + (cursor(model, model.caret()).visible() ? "visible" : "hidden");
        }

        /**
         * The position row: which pane holds focus, and where its caret is in cells.
         */
        private static String position(Model model) {
            Pane caret = model.caret();
            return "focus=" + (model.leftFocused() ? "left" : "right") + " caret=" + caret.row() + ":" + caret.column();
        }

        private static String lower(Enum<?> value) {
            return value.name().toLowerCase(Locale.ROOT);
        }
    }
}
