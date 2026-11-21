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
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Checkbox;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;

/**
 * Phase 31: runtime diagnostics over two ordinary focused widgets.
 */
public final class DebugOverlayDemo {
    private DebugOverlayDemo() {}

    /**
     * The two checkbox states, inspector visibility, and accepted change count.
     *
     * @param left whether the message belongs to the left checkbox
     * @param right the right inset in cells
     * @param debug whether the overlay is enabled
     * @param changes the number of widget changes
     */
    public record Model(Checkbox.Model left, Checkbox.Model right, boolean debug, int changes) {
        /**
         * Formats the model facts as a plain status row for terminal verification.
         *
         * @return formats the model facts as a plain status row for terminal verification
         */
        public String status() {
            return "debug overlay=" + (debug ? "on" : "off") + " focus=" + (left.focused() ? "left" : "right")
                    + " left=" + (left.checked() ? "on" : "off") + " right=" + (right.checked() ? "on" : "off")
                    + " changes=" + changes;
        }
    }

    /**
     * Typed user and child-widget messages for the diagnostics demo.
     */
    public sealed interface Msg {
        /**
         * Routes a checkbox message to its owning left or right field.
         *
         * @param left whether the message belongs to the left checkbox
         * @param message the typed message to deliver
         */
        record Child(boolean left, Checkbox.Msg message) implements Msg {}

        /**
         * Moves focus to the next field.
         */
        record NextFocus() implements Msg {}

        /**
         * Toggles the developer inspector.
         */
        record ToggleDebug() implements Msg {}

        /**
         * Requests application exit.
         */
        record Quit() implements Msg {}
    }

    /**
     * Returns the default typed actions for this demo.
     *
     * @return the default typed actions for this demo
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(KeyMap.group(
                "Debug scene",
                KeyAction.<Msg>of("focus", new Msg.NextFocus(), "tab", "next field", "tab"),
                KeyAction.<Msg>of("debug", new Msg.ToggleDebug(), "d", "toggle diagnostics", "d"),
                KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "escape")));
    }

    /**
     * The diagnostics demo component with two independently focused fields.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        private final Checkbox left = new Checkbox(StyledText.plain("left option"));
        private final Checkbox right = new Checkbox(StyledText.plain("right option"));

        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(
                    new Model(Checkbox.Model.of(false), Checkbox.Model.of(false).withFocus(false), true, 0),
                    Command.debugOverlay(true));
        }

        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Child child -> {
                    var update = (child.left() ? left : right)
                            .update(child.left() ? model.left() : model.right(), child.message());
                    Checkbox.Model a = child.left() ? update.model() : model.left();
                    Checkbox.Model b = child.left() ? model.right() : update.model();
                    yield Update.next(
                            new Model(
                                    a.withFocus(child.left()),
                                    b.withFocus(!child.left()),
                                    model.debug(),
                                    model.changes() + 1),
                            update.command().map(value -> new Msg.Child(child.left(), value)));
                }
                case Msg.NextFocus ignored ->
                    Update.next(new Model(
                            model.left().withFocus(!model.left().focused()),
                            model.right().withFocus(!model.right().focused()),
                            model.debug(),
                            model.changes()));
                case Msg.ToggleDebug ignored ->
                    Update.next(
                            new Model(model.left(), model.right(), !model.debug(), model.changes()),
                            Command.debugOverlay(!model.debug()));
                case Msg.Quit ignored -> Update.next(model, Command.quit());
            };
        }

        public List<Binding<Msg>> claims(Model model) {
            return defaultKeys().bindings();
        }

        public Element<Msg> view(Model model, ViewContext context) {
            return Elements.column(
                    Elements.text("Braxton debug overlay"),
                    Elements.text(model.status()),
                    left.view(model.left(), context).map(value -> new Msg.Child(true, value)),
                    right.view(model.right(), context).map(value -> new Msg.Child(false, value)),
                    Elements.text("tab focus / space toggle / d diagnostics / q quit"));
        }
    }

    /**
     * Runs the demo and returns its structured exit result.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(
                new Scene(),
                RuntimeOptions.defaults().withTerminal(TerminalOptions.DEFAULT.withMouseMode(MouseMode.CLICK)));
    }

    /**
     * Launches the interactive debug overlay demo.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) result.failure().printStackTrace(System.err);
        System.exit(result.status());
    }
}
