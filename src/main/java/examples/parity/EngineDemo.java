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
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.internal.text.SelectionModel;
import io.github.dhoard.braxton.internal.text.TextBuffer;
import io.github.dhoard.braxton.internal.text.VirtualScroll;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;

/**
 * Phase 26 scenario: the shared engines used by later widgets.
 */
public final class EngineDemo {

    private EngineDemo() {}

    /**
     * Runs the scenario.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * The scenario's own state: how many steps the engine has been asked for.
     */
    public static final class Scene implements Component<Integer, String, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        /**
         * The one gesture the scenario answers.
         *
         * @return the one gesture the scenario answers
         */
        public static KeyMap<String> keys() {
            return KeyMap.of(KeyMap.group("Scene", KeyAction.<String>of("quit", "quit", "q", "quit", "q", "escape")));
        }

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            return message.equals("quit") ? Update.next(model, Command.quit()) : Update.next(model + 1);
        }

        @Override
        public Element<String> view(Integer model, ViewContext context) {
            return scene(model);
        }

        /**
         * The one gesture the scenario answers, claimed so no field can type it.
         */
        @Override
        public List<Binding<String>> claims(Integer model) {
            return keys().bindings();
        }

        /**
         * The four engine readings, each a row of the scene.
         */
        private static Element<String> scene(int step) {
            return Elements.column(
                    Elements.text(
                            "engine text: " + TextBuffer.of(List.of("alpha")).line(0)),
                    Elements.text("engine selection: " + new SelectionModel(0, 1, 0, 4).endCol()),
                    Elements.text("engine scroll: "
                            + VirtualScroll.of(100, 10).scrollTo(step).offset()),
                    Elements.text("q quit"));
        }
    }

    private static final class ExitResultLike {
        static io.github.dhoard.braxton.mvu.Update<Integer, String, io.github.dhoard.braxton.mvu.Never> quit(int step) {
            return io.github.dhoard.braxton.mvu.Update.next(step, Command.quit());
        }
    }

    /**
     * Packaged demo entry point.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) result.failure().printStackTrace(System.err);
        System.exit(result.status());
    }
}
