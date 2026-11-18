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
import io.github.dhoard.braxton.app.IoOptions;
import io.github.dhoard.braxton.app.ProgramHandle;
import io.github.dhoard.braxton.app.ProgramResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
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
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Phase 23 parity demo: inline output, streaming and headless I/O.
 *
 * <p>Three launch modes, one library:</p>
 *
 * <ul>
 *   <li>{@code --inline} (the default) runs a package installer on the terminal's normal screen. Its
 *       live region - the status, the progress bar and the help - is repainted in place while each
 *       finished package is printed <em>above</em> it as a durable line, so the installation log
 *       survives every later frame and so does whatever the shell printed before the program
 *       started;</li>
 *   <li>{@code --pipe} reads stdin as UTF-8 application data - not as terminal key input - and runs
 *       with the renderer disabled, so the session writes no frame and no escape sequence. Every line
 *       it receives is printed through the session's own output, which is stdout;</li>
 *   <li>{@code --headless} does the same with local fixtures and a {@link StringWriter} for output, so
 *       the whole session - configured streams, streaming source, print commands, final model - runs
 *       with no terminal and no native library at all.</li>
 * </ul>
 *
 * <p>After either of the non-interactive modes the demo prints the final model the run returned as
 * plain text. Nothing in this file closes a stream the runtime was given: the host owns them, and
 * they are still usable after the session ends.</p>
 */
public final class InlineIoDemo {

    /** The packages the installer works through, in order. */
    public static final List<String> PACKAGES =
            List.of("parser", "lexer", "runtime", "renderer", "widgets", "examples");

    /** How long one package takes to install, so the live region is visibly repainted between prints. */
    public static final Duration INSTALL_DELAY = Duration.ofMillis(120);

    /** The text every interactive status row starts with, which the terminal verifier waits for. */
    public static final String STATUS_HEADER = "packages=6";

    private static final Style STATUS = Style.NONE.withBold();

    private InlineIoDemo() {}

    /**
     * The demo's launch mode.
     *
     * <p>Each mode is a different answer to "where do this session's bytes come from and go to": the
     * owned terminal, the process's piped stdin and stdout, or a pair of local fixtures.</p>
     */
    public enum Mode {
        /** The owned terminal's normal screen, with durable lines above a live region. */
        INLINE,
        /** Piped stdin as application data, renderer disabled, stdout for output. */
        PIPE,
        /** Local fixtures and a string writer, renderer disabled. */
        HEADLESS
    }

    /** Every message the installer handles. */
    public sealed interface Msg {

        /** Installs the next package. */
        record Install() implements Msg {}

        /** Starts installing everything that is left, one package at a time. */
        record InstallAll() implements Msg {}

        /** One package finished; its name is what the durable line prints. */
        record Installed(String name) implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The installer's state.
     *
     * @param installed how many packages are installed
     * @param phase what the installer is doing, as one token
     * @param automatic whether the installer keeps going on its own after each package
     * @param steps how many messages the scene has handled
     */
    public record Model(int installed, String phase, boolean automatic, int steps) {

        /** Substitutes the starting values for anything missing. */
        public Model {
            phase = phase == null ? "idle" : phase;
            installed = Math.max(0, Math.min(installed, PACKAGES.size()));
            steps = Math.max(0, steps);
        }

        /** The scene's starting state. */
        public static Model start() {
            return new Model(0, "idle", false, 0);
        }

        /** The package being installed, or null when everything is done. */
        public String current() {
            return installed < PACKAGES.size() ? PACKAGES.get(installed) : null;
        }

        /** Whether every package is installed. */
        public boolean complete() {
            return installed >= PACKAGES.size();
        }

        Model installed(String name) {
            // The phase is decided by the model this message produces, not the one it arrived under.
            boolean finished = installed + 1 >= PACKAGES.size();
            return new Model(installed + 1, finished ? "done" : "installing", automatic, steps + 1);
        }

        Model starting(boolean continuous) {
            return new Model(installed, "installing", continuous, steps + 1);
        }
    }

    /** The interactive scene's bindings. */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Install",
                        KeyAction.<Msg>of("install", new Msg.Install(), "n", "next package", "n"),
                        KeyAction.<Msg>of("all", new Msg.InstallAll(), "a", "install all", "a")),
                KeyMap.group("Session", KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /**
     * The status row: how far the installation has got.
     *
     * @param model the committed model
     * @return the row's text
     */
    public static String status(Model model) {
        return STATUS_HEADER + " installed=" + model.installed() + " phase=" + model.phase() + " mode=inline";
    }

    /**
     * The live row: the package being installed and a bar that fills as packages finish.
     *
     * @param model the committed model
     * @return the row's text
     */
    public static String live(Model model) {
        StringBuilder bar = new StringBuilder();
        for (int index = 0; index < PACKAGES.size(); index++) {
            bar.append(index < model.installed() ? '#' : '-');
        }
        String current = model.current();
        return "live=" + (current == null ? "complete" : current) + " progress=[" + bar + "]";
    }

    /**
     * The final model, as the plain line the demo prints once the session is over.
     *
     * @param model the final model, or null when the run ended before it committed one
     * @return the summary line
     */
    public static String summary(Model model) {
        if (model == null) {
            return "final installed=0 phase=unknown steps=0";
        }
        return "final installed=" + model.installed() + " phase=" + model.phase() + " steps=" + model.steps();
    }

    /** One exit result as a token, without any terminal output. */
    public static String describe(ExitResult result) {
        return result.kind() + "/" + result.status();
    }

    /**
     * Runs the demo in the mode the arguments name.
     *
     * @param args the launch arguments; none means {@link Mode#INLINE}
     * @return the process exit status
     */
    public static void main(String[] args) {
        ExitResult result = run(mode(args));
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The mode a launch asks for.
     *
     * @param args the launch arguments
     * @return the mode; {@link Mode#INLINE} when none was named
     */
    public static Mode mode(String[] args) {
        if (args == null || args.length == 0) {
            return Mode.INLINE;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "--inline", "inline" -> Mode.INLINE;
            case "--pipe", "pipe" -> Mode.PIPE;
            case "--headless", "headless" -> Mode.HEADLESS;
            default ->
                throw new IllegalArgumentException(
                        "unknown mode: " + args[0] + " (expected --inline, --pipe or --headless)");
        };
    }

    /**
     * Runs the demo in the inline mode the terminal verifier launches.
     *
     * @return how the run ended; never null
     */
    public static ExitResult run() {
        return run(Mode.INLINE);
    }

    /**
     * Runs the demo in one mode.
     *
     * <p>{@link Mode#INLINE} runs on the owned terminal with the alternate screen off, which is what
     * makes the session render a live region instead of a full screen. The other two modes run with
     * the renderer disabled over their own streams and print the final model as plain text, which is
     * the shape a piped or batch run has.</p>
     *
     * @param mode the launch mode; required
     * @return how the run ended; never null
     */
    public static ExitResult run(Mode mode) {
        return switch (mode) {
            case INLINE -> runInline();
            case PIPE -> runPipe();
            case HEADLESS -> runHeadless();
        };
    }

    /** The interactive session: the owned terminal's normal screen. */
    private static ExitResult runInline() {
        RuntimeOptions options =
                RuntimeOptions.defaults().withTerminal(TerminalOptions.DEFAULT.withAlternateScreen(false));
        ExitResult result = Application.run(new Scene(), options, IoOptions.defaults());
        System.out.println("inline " + describe(result));
        return result;
    }

    /** A piped session: stdin is data, stdout is durable output, nothing is painted. */
    private static ExitResult runPipe() {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            ProgramResult<StreamModel> result =
                    session(input, new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
            System.out.println(streamSummary(result.model()));
            System.out.println("pipe " + describe(result.exit()));
            return result.exit();
        } catch (IOException failure) {
            ExitResult result = ExitResult.failure(failure, List.of());
            result.failure().printStackTrace(System.err);
            return result;
        }
    }

    /** A headless session: local fixtures and a string writer. */
    private static ExitResult runHeadless() {
        StringWriter output = new StringWriter();
        Reader input = new StringReader(String.join("\n", List.of("alpha", "beta", "gamma")) + "\n");
        ProgramResult<StreamModel> result = session(input, output);
        System.out.print(output);
        System.out.println(streamSummary(result.model()));
        System.out.println("headless " + describe(result.exit()));
        return result.exit();
    }

    /**
     * Runs one stream session: the reader is the application's own data, the writer is the session's
     * output, and the renderer is off.
     *
     * @param input the lines to consume, as UTF-8 text
     * @param output where the session's durable output goes
     * @return how the run ended; never null
     */
    public static ProgramResult<StreamModel> session(Reader input, Writer output) {
        IoOptions io = IoOptions.defaults().withOutput(output).withRenderer(false);
        ProgramHandle<StreamModel, StreamMsg> handle =
                Application.start(new StreamScene(input), RuntimeOptions.defaults(), io);
        ProgramResult<StreamModel> result = handle.await();
        try {
            output.flush();
        } catch (IOException failure) {
            return new ProgramResult<>(result.model(), ExitResult.failure(failure, List.of()));
        }
        return result;
    }

    /**
     * The interactive scene: a live region on the terminal's normal screen, and one durable line per
     * installed package above it.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(Model.start());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Install install ->
                    model.complete() ? Update.next(model) : Update.next(model.starting(false), install(model));
                case Msg.InstallAll all ->
                    model.complete() ? Update.next(model) : Update.next(model.starting(true), install(model));
                case Msg.Installed installed ->
                    Update.next(model.installed(installed.name()), next(model, installed.name()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            return Views.onKeys(
                    keys.bindings(),
                    Views.focus(
                            ElementId.of("inline"),
                            true,
                            keys.bindings(),
                            Scene::type,
                            Views.column(
                                    Views.text(status(model), STATUS),
                                    Views.text(live(model), STATUS),
                                    Views.gap(1),
                                    Views.text("braxton inline output packages=6", STATUS),
                                    Views.gap(1),
                                    Help.view(keys, false, width))));
        }

        /** Types nothing: the scene's screen is a status board, not a field. */
        private static Optional<Msg> type(InputEvent event) {
            return Optional.empty();
        }

        /**
         * What follows one finished package: its durable line, and - when the installer is running on
         * its own - the next package, as a step that keeps going until everything is installed.
         *
         * @param before the model the package finished under
         * @param name the package that finished
         * @return the command
         */
        static Command<Msg> next(Model before, String name) {
            Command<Msg> line = Command.println("installed " + name);
            boolean last = before.installed() + 1 >= PACKAGES.size();
            if (last || !before.automatic()) {
                return line;
            }
            return Command.batch(
                    line,
                    Command.delay(
                            EffectId.of("install-next"), INSTALL_DELAY, new Msg.InstallAll(), new Msg.InstallAll()));
        }

        /**
         * Installs the package the model names, as application work on the effect executor.
         *
         * @param model the model that names the package
         * @return the task command
         */
        static Command<Msg> install(Model model) {
            String name = model.current();
            return Command.task(
                    EffectId.of("install-" + name),
                    () -> {
                        TimeUnit.MILLISECONDS.sleep(INSTALL_DELAY.toMillis());
                        return name;
                    },
                    value -> new Msg.Installed(String.valueOf(value)),
                    failure -> new Msg.Installed(name));
        }
    }

    /** Every message the streaming scene handles. */
    public sealed interface StreamMsg {

        /** One line of application data arrived. */
        record Line(int number, String text) implements StreamMsg {}

        /** The input ended. */
        record Eof() implements StreamMsg {}

        /** Asks the session to end. */
        record Quit() implements StreamMsg {}
    }

    /**
     * The streaming scene's state.
     *
     * @param lines the lines received so far, in order
     * @param finished whether the input ended
     */
    public record StreamModel(List<String> lines, boolean finished) {

        /** Substitutes the starting values for anything missing. */
        public StreamModel {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** The scene's starting state. */
        public static StreamModel start() {
            return new StreamModel(List.of(), false);
        }

        StreamModel withLine(String text) {
            List<String> next = new ArrayList<>(lines);
            next.add(text);
            return new StreamModel(next, finished);
        }

        StreamModel ending() {
            return new StreamModel(lines, true);
        }
    }

    /**
     * The final model of a streaming session, as plain text.
     *
     * @param model the final model, or null when the run committed none
     * @return the summary line
     */
    public static String streamSummary(StreamModel model) {
        if (model == null) {
            return "stream lines=0 text=- finished=false";
        }
        return "stream lines=" + model.lines().size() + " text=" + String.join(",", model.lines()) + " finished="
                + model.finished();
    }

    /**
     * The streaming scene: a source subscription consumes the reader, and every line is printed
     * through the session's own output.
     *
     * <p>The reader is application data, not terminal input: it is read by the scene's own virtual
     * thread inside a {@link Subscription.Source}, so the runtime never decodes these bytes as keys and
     * the session needs no terminal to run. The reader belongs to whoever opened it, so the scene never
     * closes it.</p>
     */
    public static final class StreamScene implements Component<StreamModel, StreamMsg, Never> {

        private final Reader input;

        /**
         * Creates the scene over one reader.
         *
         * @param input the lines to consume; required
         */
        public StreamScene(Reader input) {
            this.input = java.util.Objects.requireNonNull(input, "input");
        }

        @Override
        public Update<StreamModel, StreamMsg, Never> init(InitContext context) {
            return Update.next(StreamModel.start());
        }

        @Override
        public Update<StreamModel, StreamMsg, Never> update(StreamModel model, StreamMsg message) {
            return switch (message) {
                case StreamMsg.Line line ->
                    Update.next(
                            model.withLine(line.text()),
                            Command.println("line=" + line.number() + " text=" + line.text()));
                case StreamMsg.Eof eof -> Update.next(model.ending(), Command.quit());
                case StreamMsg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public Subscription<StreamMsg> subscriptions(StreamModel model) {
            if (model.finished()) {
                return Subscription.none();
            }
            return Subscription.source(SubscriptionId.of("lines"), input, this::readLines, value -> (StreamMsg) value);
        }

        @Override
        public View<StreamMsg> view(StreamModel model, ViewContext context) {
            // A renderer-disabled session never paints this; the view still states what the model is,
            // because a view is a pure function of it and nothing here may depend on being drawn.
            return Views.text(streamSummary(model), STATUS);
        }

        /**
         * Reads every line of the scene's reader, emitting one message each and then one end-of-input
         * message.
         *
         * @param emitter where the lines are delivered
         * @return the resource that stops the read
         */
        private AutoCloseable readLines(Consumer<Object> emitter) {
            Thread worker = Thread.ofVirtual().name("braxton-demo-lines").start(() -> {
                int number = 0;
                try {
                    BufferedReader reader = new BufferedReader(input);
                    // Deliberately not closed: the host that supplied the stream decides when it ends.
                    for (String line; (line = reader.readLine()) != null; ) {
                        number++;
                        emitter.accept(new StreamMsg.Line(number, line));
                    }
                } catch (IOException closed) {
                    // The input is the host's: a read failure ends the stream, and the session reports
                    // the model it reached instead of turning a closed pipe into a failure.
                } finally {
                    emitter.accept(new StreamMsg.Eof());
                }
            });
            return worker::interrupt;
        }
    }
}
