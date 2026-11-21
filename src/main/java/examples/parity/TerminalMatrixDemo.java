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
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.terminal.Capabilities;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;

/**
 * Phase 25 parity demo: the terminal foundation seen from an application.
 *
 * <p>The status row reports the negotiated capabilities, so an owned PTY verifier can assert the
 * capability part of R-01 without guessing from output bytes. The same scene stays on screen across
 * an external stop and continue: after the native path replays the entry sequence, the runtime must
 * repaint the row from its model rather than leaving a blank alternate screen.</p>
 */
public final class TerminalMatrixDemo {

    private TerminalMatrixDemo() {}

    /**
     * One row of terminal facts, enough for a pyte assertion.
     *
     * @param term the terminal identity from TERM
     * @param capabilities the detected terminal facts
     * @param stopped whether the phase has stopped
     */
    public record Model(String term, Capabilities capabilities, boolean stopped) {}

    private sealed interface Msg permits Quit {}

    private record Quit() implements Msg {}

    /**
     * The component: the session's own facts are captured at initialization, never read later.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Capabilities caps = context.capabilities();
            return Update.next(new Model(caps.term(), caps, false));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Quit ignored -> Update.next(model, Command.quit(0));
            };
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
            return List.of(Binding.of("q", new Quit()));
        }

        /**
         * The scene as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            Capabilities caps = model.capabilities();
            String flags = String.join(
                    "/",
                    caps.color() ? "1" : "0",
                    caps.alternateScreen() ? "1" : "0",
                    caps.bracketedPaste() ? "1" : "0",
                    caps.mouse() ? "1" : "0");
            String status = "term=" + model.term() + " caps=" + flags + " size=" + context.viewport();
            return Elements.column(
                    Elements.text("Braxton terminal matrix"),
                    Elements.text(status),
                    Elements.text("q quit  •  stop and continue to test the session"));
        }
    }

    /**
     * Runs the phase scenario.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Runs the packaged demo.
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
}
