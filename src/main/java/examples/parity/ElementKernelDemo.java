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
import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionState;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import java.util.List;
import java.util.function.Consumer;

/**
 * Phase 27 parity demo: an application's own widget on the public kernel.
 *
 * <p>R-05 asks that a custom widget be first class, and this scene is that claim executed: the
 * meter below is written only against the public API - {@link Widget#measure}, {@link
 * Widget#arrange}, {@link Widget#paint}, {@link Widget#canFocus}, {@link Widget#focused}, {@link
 * Widget#onKey}, {@link Widget#onPointer} and {@link Widget#cursor} - and it composes with the
 * built-in vocabulary through {@link Elements}. There is no framework type for it: the scene builds
 * it, the tree lays it out, the runtime routes to it and the frame presents the caret it
 * declares.</p>
 *
 * <p>The meter is also the smallest complete widget: a fixed number of cells filled out of a width,
 * a caret on the cell after the fill, a click that sets the fill where it landed, and two arrow keys
 * that step it. Everything the scene reports is on a status row, so a terminal verifier can assert
 * the state without decoding styled cells.</p>
 */
public final class ElementKernelDemo {

    /**
     * The cells a fresh meter fills.
     */
    public static final int START_CELLS = 4;

    /**
     * The narrowest bar the scene lays out.
     */
    public static final int MIN_WIDTH = 8;

    /**
     * The widest bar the scene lays out.
     */
    public static final int MAX_WIDTH = 32;

    /**
     * The glyph a filled cell paints.
     */
    public static final String FULL = "\u2588";

    /**
     * The glyph an empty cell paints.
     */
    public static final String EMPTY = "\u2591";

    private static final Style STATUS = Style.NONE.withBold();
    private static final Style FILL = Style.NONE.withBold();
    private static final Style REST = Style.NONE.withForeground(io.github.dhoard.braxton.style.Colors.GRAY_600);

    private ElementKernelDemo() {}

    /**
     * The scene state.
     *
     * @param cells how many cells the meter fills
     * @param width how wide the meter is, derived from the viewport
     * @param focused whether the meter owns focus
     * @param last what the scene or the widget last did
     * @param reports how many pointer and key reports the widget answered
     */
    public record Model(int cells, int width, boolean focused, String last, int reports) {

        /**
         * Substitutes a meter that fills the start cell count and owns focus for missing values.
         */
        public Model {
            width = Math.max(1, width);
            cells = Math.max(0, Math.min(cells, width));
            last = last == null ? "none" : last;
            reports = Math.max(0, reports);
        }

        /**
         * A meter at its starting state for a terminal {@code cols} columns wide.
         *
         * @param cols the viewport columns
         * @return the model
         */
        public static Model starting(int cols) {
            int width = widthFor(cols);
            return new Model(Math.min(START_CELLS, width), width, true, "none", 0);
        }

        /**
         * The bar width a terminal {@code cols} columns wide is laid out at.
         *
         * @param cols the viewport columns
         * @return the width, clamped to the scene's own bounds
         */
        public static int widthFor(int cols) {
            return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, cols - 20));
        }

        /**
         * The filled row: the bar a user reads.
         *
         * @return the row
         */
        public String bar() {
            return FULL.repeat(cells) + EMPTY.repeat(width - cells);
        }

        Model withCells(int value) {
            return new Model(value, width, focused, last, reports);
        }

        Model withWidth(int value) {
            return new Model(Math.min(cells, value), value, focused, last, reports);
        }

        Model withFocused(boolean value) {
            return new Model(cells, width, value, last, reports);
        }

        Model withLast(String value) {
            return new Model(cells, width, focused, value, reports);
        }

        Model reported() {
            return new Model(cells, width, focused, last, reports + 1);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * The meter was asked for a fill level.
         *
         * @param cells the cells to fill
         */
        record Filled(int cells) implements Msg {}

        /**
         * Focus moves between the meter and the application.
         */
        record ToggleFocus() implements Msg {}

        /**
         * The meter is asked to be wider or narrower.
         *
         * @param delta the requested signed change
         */
        record Resize(int delta) implements Msg {}

        /**
         * Starts over.
         */
        record Reset() implements Msg {}

        /**
         * The terminal was resized, so the meter is laid out again.
         *
         * @param size the new terminal size
         */
        record Resized(Size size) implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions: the meter owns no keys, so the map is the whole keyboard.
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Meter",
                        KeyAction.<Msg>of("focus", new Msg.ToggleFocus(), "tab", "toggle focus", "tab", "f"),
                        KeyAction.<Msg>of("wider", new Msg.Resize(4), "+", "wider", "+", "="),
                        KeyAction.<Msg>of("narrower", new Msg.Resize(-4), "-", "narrower", "-", "_")),
                KeyMap.group(
                        "Scene",
                        KeyAction.<Msg>of("reset", new Msg.Reset(), "r", "reset", "r"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q")),
                KeyMap.group("Session", KeyAction.<Msg>of("escape", new Msg.Quit(), "esc", "quit", "escape")));
    }

    /**
     * Runs the demo against the owned terminal.
     *
     * <p>The scene answers clicks, so the application asks for the pointer reports it needs: press
     * and release are enough for a bar that is set by the cell it was pressed on.</p>
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(
                new Scene(),
                io.github.dhoard.braxton.app.RuntimeOptions.defaults()
                        .withTerminal(io.github.dhoard.braxton.app.TerminalOptions.DEFAULT.withMouseMode(
                                io.github.dhoard.braxton.app.MouseMode.CLICK)));
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The application's own widget: a bar of cells, its caret, its keys and its clicks.
     *
     * <p>It is written the way an application's widget is written - against the public widget API
     * only - and it holds no state of its own: the model it is handed says how full it is, whether
     * it owns focus and how wide it is.</p>
     */
    public static final class Meter implements Widget<Model, Msg> {

        /**
         * Creates the meter with its default state.
         */
        public Meter() {}

        /**
         * Asks for one row of the columns it is offered.
         */
        @Override
        public Size measure(Size max, Model model) {
            return new Size(Math.min(1, max.rows()), max.cols());
        }

        /**
         * Paints the bar, the filled cells first and the caret after them when it holds focus.
         */
        @Override
        public void paint(Surface surface, Rect bounds, Model model) {
            int cells = Math.min(model.cells(), bounds.cols());
            surface.writeLine(
                    bounds.row(), bounds.col(), cells, StyledText.of(FULL.repeat(cells), FILL), WidthPolicy.PINNED);
            if (cells < bounds.cols()) {
                surface.writeLine(
                        bounds.row(),
                        bounds.col() + cells,
                        bounds.cols() - cells,
                        StyledText.of(EMPTY.repeat(bounds.cols() - cells), REST),
                        WidthPolicy.PINNED);
            }
        }

        /**
         * The meter takes a turn in the focus walk; the scene's model says whose turn it is.
         */
        @Override
        public boolean canFocus(Model model) {
            return true;
        }

        @Override
        public boolean focused(Model model) {
            return model.focused();
        }

        @Override
        public InteractionState state(Model model) {
            return InteractionState.NONE.withFocused(model.focused());
        }

        /**
         * The caret sits on the cell after the fill, which is the cell a key would fill next.
         */
        @Override
        public Cursor cursor(Rect bounds, Model model) {
            if (!model.focused()) {
                return null;
            }
            return Cursor.at(bounds.row(), bounds.col() + Math.min(model.cells(), Math.max(0, bounds.cols() - 1)));
        }

        /**
         * Left and right step the fill by one cell; a key the meter does not use is not answered.
         */
        @Override
        public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
            if (key.key().equals(Key.LEFT)) {
                emit.accept(new Msg.Filled(Math.max(0, model.cells() - 1)));
            } else if (key.key().equals(Key.RIGHT)) {
                emit.accept(new Msg.Filled(Math.min(model.width(), model.cells() + 1)));
            }
        }

        /**
         * A press fills the bar to the cell it landed on.
         *
         * <p>The report arrives in screen cells, so the widget translates it into its own rectangle
         * with the bounds it was handed: one cell past the pressed cell is how many the bar fills.
         * </p>
         */
        @Override
        public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
            if (pointer.isPress()) {
                PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
                emit.accept(new Msg.Filled(Math.max(0, Math.min(model.width(), local.column() + 1))));
            }
        }
    }

    /**
     * The scene: two status rows, a framed meter and the help line.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        private final KeyMap<Msg> keys = defaultKeys();
        private final Meter meter = new Meter();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(Model.starting(context.viewport().cols()));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Filled filled ->
                    Update.next(model.withCells(filled.cells())
                            .withLast("fill-" + Math.min(filled.cells(), model.width()))
                            .reported());
                case Msg.ToggleFocus toggle ->
                    Update.next(
                            model.withFocused(!model.focused()).withLast("focus-" + (!model.focused() ? "on" : "off")));
                case Msg.Resize resize -> {
                    int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, model.width() + resize.delta()));
                    yield Update.next(model.withWidth(width).withLast("width-" + width));
                }
                case Msg.Reset reset -> Update.next(Model.starting(model.width() + 20));
                case Msg.Resized resized -> {
                    int width = Model.widthFor(resized.size().cols());
                    yield Update.next(width == model.width() ? model : model.withWidth(width));
                }
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * The scene declares one frame presentation: its own title.
         */
        @Override
        public TerminalPresentation presentation(Model model) {
            return TerminalPresentation.title("Braxton element kernel");
        }

        /**
         * The gestures this scene answers once the meter has declined them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            return keys.bindings();
        }

        @Override
        public Element<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            Element<Msg> meterRow = Elements.fixed(1, new Element.Leaf<>(meter, model, Sizing.measured()));
            Element<Msg> framed = Elements.frame(Border.SINGLE, Elements.pad(1, meterRow));
            return Elements.column(
                    Elements.fixed(1, Elements.text(status(model), STATUS)),
                    Elements.fixed(1, Elements.text(pointerRow(model), STATUS)),
                    Elements.spacer(1),
                    framed,
                    Elements.spacer(1),
                    Elements.text("widget: " + Meter.class.getSimpleName() + " (public Widget API only)"),
                    Elements.spacer(1),
                    Help.element(keys, Help.Config.defaults(), false, cols));
        }

        /**
         * The row a verifier asserts: how full the meter is and whose turn it is.
         */
        static String status(Model model) {
            return "kernel widget=painted cells=" + model.cells() + "/" + model.width() + " focus="
                    + (model.focused() ? "meter" : "scene") + " reports=" + model.reports() + " last="
                    + model.last();
        }

        /**
         * The bar itself, one row of cells.
         */
        static String pointerRow(Model model) {
            return "meter " + model.bar();
        }
    }
}
