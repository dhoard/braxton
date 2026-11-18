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
import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import io.github.dhoard.braxton.effect.SuspendResult;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 21 parity demo: process handoff and suspend/resume.
 *
 * <p>Two things can only be done by a session that is willing to stop owning its terminal, and this
 * scene asks for both from the same screen:</p>
 *
 * <ul>
 *   <li>{@code e} hands the terminal to an owned child process - this program, started again with
 *       {@code --helper} - which prints one line, reads a line the user types in the terminal's own
 *       (cooked) configuration, echoes it, and exits. The scene's draft is still on the screen when
 *       the session takes the terminal back, which is the point of the handoff: the model never
 *       stopped existing, only the terminal was given away.</li>
 *   <li>{@code ctrl+z} suspends the session when it owns the terminal's foreground. The job stops in
 *       the shell's own configuration, {@code fg} continues it, and the row reports
 *       {@code suspend=resumed} on the frame painted afterwards. A session that does not own the
 *       foreground is refused rather than left stopped with nobody to continue it.</li>
 * </ul>
 *
 * <p>Typing any other printable key appends to {@code draft=}, and backspace removes the last
 * character. {@code gestures=} counts everything the scene handled, so a verifier can tell a
 * repainted frame from a skipped one.</p>
 */
public final class ProcessHandoffDemo {

    /** The row carrying how many handoffs ran, how the last one ended, and the suspend state. */
    public static final int STATUS_ROW = 0;

    /** The row carrying the draft the handoff must not lose. */
    public static final int DRAFT_ROW = 1;

    /** The body row, which changes on every gesture. */
    public static final int BODY_ROW = 3;

    /** The first line the owned helper prints. */
    public static final String HELPER_TITLE = "braxton handoff helper";

    /** The environment entry the helper reads back, so the request's environment is visible. */
    public static final String HELPER_VARIABLE = "BRAXTON_HANDOFF";

    /** The environment value the helper reads back. */
    public static final String HELPER_VALUE = "phase-21";

    /** The longest draft the scene keeps. */
    public static final int MAX_DRAFT = 40;

    private static final Style STATUS = Style.NONE.withBold();

    private ProcessHandoffDemo() {}

    /** Every message the scene handles. */
    public sealed interface Msg {

        /** Appends the typed text to the draft. */
        record Type(String text) implements Msg {}

        /** Removes the last character of the draft. */
        record Backspace() implements Msg {}

        /** Hands the terminal to the owned helper. */
        record RunHelper() implements Msg {}

        /** The helper finished; this is its scoped completion. */
        record Handed(ProcessResult result) implements Msg {}

        /** Asks the session to suspend. */
        record Suspend() implements Msg {}

        /** The suspend request resolved. */
        record Suspended(SuspendResult result) implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene state.
     *
     * @param draft the text typed so far, which a handoff must not lose
     * @param handoffs how many handoffs ran
     * @param last how the last handoff ended, as one token
     * @param exit the exit status of the last child, or -1 when none ran
     * @param suspend the suspend state, as one token
     * @param gestures how many gestures the scene has handled
     */
    public record Model(String draft, int handoffs, String last, int exit, String suspend, int gestures) {

        /** Substitutes the starting values for anything missing. */
        public Model {
            draft = draft == null ? "" : draft;
            handoffs = Math.max(0, handoffs);
            last = last == null ? "none" : last;
            suspend = suspend == null ? "ready" : suspend;
            gestures = Math.max(0, gestures);
        }

        /** The scene's starting state: an empty draft and nothing handed off yet. */
        public static Model start() {
            return new Model("", 0, "none", -1, "ready", 0);
        }

        Model advanced() {
            return new Model(draft, handoffs, last, exit, suspend, gestures + 1);
        }

        Model withDraft(String value) {
            return new Model(value, handoffs, last, exit, suspend, gestures + 1);
        }

        Model withResult(ProcessResult result) {
            return new Model(
                    draft,
                    handoffs + 1,
                    switch (result) {
                        case ProcessResult.Exited exited -> "exited";
                        case ProcessResult.Failed failed -> "failed";
                        case ProcessResult.Rejected rejected -> "rejected";
                    },
                    result.exitStatus(),
                    suspend,
                    gestures + 1);
        }

        Model withSuspend(SuspendResult result) {
            String token = switch (result) {
                case SuspendResult.Resumed resumed -> "resumed";
                case SuspendResult.Refused refused -> "refused";
                case SuspendResult.Failed failed -> "failed";
            };
            return new Model(draft, handoffs, last, exit, token, gestures + 1);
        }
    }

    /**
     * The scene's own actions.
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Terminal",
                        KeyAction.<Msg>of("handoff", new Msg.RunHelper(), "e", "run helper", "e"),
                        KeyAction.<Msg>of("suspend", new Msg.Suspend(), "ctrl+z", "suspend", "ctrl+z")),
                KeyMap.group("Session", KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")),
                KeyMap.group(
                        "Draft",
                        KeyAction.<Msg>of("backspace", new Msg.Backspace(), "backspace", "delete", "backspace")));
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--helper")) {
            helper(args);
            return;
        }
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The owned child: prints what it was given, reads one line in the terminal's own configuration,
     * and echoes an edited form of it.
     *
     * <p>It is deliberately a second copy of this program rather than an external tool: the demo
     * needs a child that is certainly present, certainly terminating, and certainly able to say what
     * the request's environment and working directory were.</p>
     *
     * @param args {@code --helper} followed by the exit status to report; required
     */
    static void helper(String[] args) {
        int status = 0;
        if (args.length > 1) {
            try {
                status = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                status = 0;
            }
        }
        System.out.println(HELPER_TITLE);
        System.out.println("cwd=" + Path.of("").toAbsolutePath());
        System.out.println("env=" + System.getenv(HELPER_VARIABLE));
        System.out.print("line> ");
        System.out.flush();
        String line;
        try {
            line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException unreadable) {
            line = null;
        }
        String text = line == null ? "" : line;
        System.out.println();
        System.out.println("echo=" + text);
        System.out.println("edited=" + text.toUpperCase(Locale.ROOT) + "!");
        System.out.flush();
        System.exit(status);
    }

    /** The demo scene: a draft, the handoff rows and the help lines. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(Model.start());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Type type -> Update.next(model.withDraft(appended(model.draft(), type.text())));
                case Msg.Backspace backspace ->
                    Update.next(model.withDraft(
                            model.draft().isEmpty()
                                    ? ""
                                    : model.draft()
                                            .substring(
                                                    0,
                                                    model.draft()
                                                            .offsetByCodePoints(
                                                                    model.draft()
                                                                            .length(),
                                                                    -1))));
                case Msg.RunHelper run -> Update.next(model.advanced(), helperCommand());
                case Msg.Handed handed -> Update.next(model.withResult(handed.result()));
                case Msg.Suspend suspend -> Update.next(model.advanced(), suspendCommand());
                case Msg.Suspended suspended -> Update.next(model.withSuspend(suspended.result()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            return Views.onKeys(
                    keys.bindings(),
                    Views.focus(
                            ElementId.of("draft"),
                            true,
                            keys.bindings(),
                            Scene::type,
                            Views.column(
                                    Views.text(status(model), STATUS),
                                    Views.text("draft=" + (model.draft().isEmpty() ? "-" : model.draft()), STATUS),
                                    Views.gap(1),
                                    Views.text("braxton process handoff gestures=" + model.gestures(), STATUS),
                                    Views.gap(1),
                                    Help.view(keys, false, width))));
        }

        /** The handoff and suspend state, in one row. */
        private static String status(Model model) {
            return "handoffs=" + model.handoffs() + " last=" + model.last() + " exit="
                    + (model.exit() < 0 ? "-" : Integer.toString(model.exit())) + " suspend=" + model.suspend();
        }

        /** Appends typed text, refusing to grow past the bound. */
        private static String appended(String draft, String text) {
            String candidate = draft + (text == null ? "" : text);
            if (candidate.codePointCount(0, candidate.length()) <= MAX_DRAFT) {
                return candidate;
            }
            int end = candidate.offsetByCodePoints(0, MAX_DRAFT);
            return candidate.substring(0, end);
        }

        /**
         * Types into the draft: printable text is appended and everything else is declined.
         *
         * <p>The scene's shortcuts are local bindings on the same element, so a key that runs the
         * helper never also types its letter.</p>
         */
        private static Optional<Msg> type(InputEvent event) {
            if (event instanceof InputEvent.KeyInput key && !key.text().isEmpty()) {
                return Optional.of(new Msg.Type(key.text()));
            }
            return Optional.empty();
        }

        /** The owned helper, run in the terminal the session gives back. */
        static Command<Msg> helperCommand() {
            String java =
                    Path.of(System.getProperty("java.home", ""), "bin", "java").toString();
            ProcessRequest request = ProcessRequest.of(
                            java,
                            "-cp",
                            System.getProperty("java.class.path", ""),
                            ProcessHandoffDemo.class.getName(),
                            "--helper",
                            "0")
                    .withEnvironment(Map.of(HELPER_VARIABLE, HELPER_VALUE))
                    .withDirectory(Path.of("").toAbsolutePath());
            return Command.exec(request, Msg.Handed::new);
        }

        /** The suspend request. */
        static Command<Msg> suspendCommand() {
            return Command.suspend(Msg.Suspended::new);
        }
    }
}
