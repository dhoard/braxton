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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.ProcessHandoffDemo;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalRecoveryException;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import io.github.dhoard.braxton.effect.SuspendResult;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 21 acceptance: process handoff and suspend/resume.
 *
 * <p>Three things are asserted against observable behavior. A handoff request is a value - argv,
 * environment and working directory with no shell and no live handle inside it - and its completion
 * is one scoped result. Running one gives the terminal back before the child starts and takes it
 * again before the result arrives, so the application's screen is its own when it learns what
 * happened, and a session that cannot take the terminal back fails with a typed error instead of
 * painting a guess. A suspend is a request the session refuses when it does not own the terminal's
 * foreground, and a resume reports through exactly one message after the terminal is taken again.
 * </p>
 *
 * <p>The runtime cases drive a real {@code RuntimeLoop} over an owned stream terminal, so the
 * evidence is the byte transcript the session wrote, the state the terminal reported while the
 * session did not own it, and the model the completion produced. The demo cases drive the packaged
 * scene as its own component, which is the artifact the PTY scenario launches.</p>
 */
@Timeout(60)
class ParityLifecycleTest {

    private static final Viewport VIEWPORT = new Viewport(8, 80);

    private static final String SCENE = "scene";

    private static RuntimeServices services() {
        return RuntimeServices.system()
                .withTimers(new ManualTimers())
                .withEffectExecutor(Runnable::run)
                .withNanoTime(() -> 1_000L);
    }

    /** One child result as the token a scene records. */
    private static String describe(ProcessResult result) {
        return switch (result) {
            case ProcessResult.Exited exited -> "handed:exited:" + exited.status();
            case ProcessResult.Failed failed -> "handed:failed";
            case ProcessResult.Rejected rejected -> "handed:rejected";
        };
    }

    /** One suspend outcome as the token a scene records. */
    private static String describe(SuspendResult result) {
        return switch (result) {
            case SuspendResult.Resumed resumed -> "suspend:resumed";
            case SuspendResult.Refused refused -> "suspend:refused";
            case SuspendResult.Failed failed -> "suspend:failed";
        };
    }

    /**
     * A two-key scene whose update is the test's own behaviour.
     *
     * @param behaviour the command each message returns, or null for none
     * @return the program
     */
    private static Component<List<String>, String, Never> scene(
            BiFunction<List<String>, String, Command<String>> behaviour) {
        List<Binding<String>> bindings =
                List.of(new Binding<>(Key.of("r"), "run"), new Binding<>(Key.of("s"), "suspend"));
        return new Component<>() {
            @Override
            public Update<List<String>, String, Never> init(InitContext context) {
                return Update.next(List.of());
            }

            @Override
            public Update<List<String>, String, Never> update(List<String> model, String message) {
                List<String> next = new ArrayList<>(model);
                next.add(message);
                List<String> recorded = List.copyOf(next);
                Command<String> command = behaviour.apply(recorded, message);
                return command == null ? Update.next(recorded) : Update.next(recorded, command);
            }

            @Override
            public View<String> view(List<String> model, ViewContext context) {
                return Views.focus(
                        ElementId.of("scene"),
                        true,
                        bindings,
                        View.InputHandler.none(),
                        Views.text(SCENE + ":" + model.size() + ":"
                                + context.viewport().cols()));
            }
        };
    }

    /** Runs one scene, feeding {@code key} once its frame is painted. */
    private static ExitResult run(TestTerminal fixture, RuntimeLoop<List<String>, String> loop, String key) {
        AtomicBoolean fed = new AtomicBoolean();
        fixture.onOutput(text -> {
            if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                fixture.feed(key);
            }
        });
        return loop.run();
    }

    // ── R-02: the request and its completion are values ─────────────────────

    @Test
    void aProcessRequestIsAnImmutableValidatedValue() {
        ProcessRequest request = ProcessRequest.of("vim", "notes.txt")
                .withEnvironment(Map.of("EDITOR", "vim"))
                .withDirectory(Path.of("/tmp"));

        assertThat(request.command()).containsExactly("vim", "notes.txt");
        assertThat(request.environment()).containsExactly(Map.entry("EDITOR", "vim"));
        assertThat(request.directory()).isEqualTo(Path.of("/tmp"));
        assertThat(ProcessRequest.of("vim").environment()).isEmpty();
        assertThat(ProcessRequest.of("vim").directory()).isNull();
        assertThat(request.withEnvironment(Map.of("TERM", "dumb")).environment())
                .as("entries are added to, or replaced in, the request's own environment")
                .containsExactlyInAnyOrderEntriesOf(Map.of("EDITOR", "vim", "TERM", "dumb"));

        assertThatThrownBy(() -> ProcessRequest.of()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcessRequest.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcessRequest.of("vim", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcessRequest.of("vim\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL");
        assertThatThrownBy(() -> ProcessRequest.of("vim").withEnvironment(Map.of("A=B", "c")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessRequest(List.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChildResultReportsAnExitStatusOnlyWhenTheChildRan() {
        assertThat(new ProcessResult.Exited(7).exitStatus()).isEqualTo(7);
        assertThat(new ProcessResult.Exited(143).exitStatus())
                .as("a child a signal ended reports the shell's own 128+signal convention")
                .isEqualTo(143);
        assertThat(new ProcessResult.Failed(new IOException("gone")).exitStatus())
                .isEqualTo(-1);
        assertThat(new ProcessResult.Rejected(null).reason()).isNotBlank();
    }

    @Test
    void theHandoffCommandsRetagEveryMessageTheyProduce() {
        ProcessRequest request = ProcessRequest.of("true");
        Command<String> exec =
                Command.exec(request, ParityLifecycleTest::describe).map(String::toUpperCase);
        Command<String> suspend = Command.suspend(ParityLifecycleTest::describe).map(String::toUpperCase);
        Command<String> batch = Command.batch(exec, suspend);

        assertThat(batch).isInstanceOf(Command.Batch.class);
        assertThat(((Command.Batch<String>) batch).commands()).hasSize(2);
        assertThat(((Command.Batch<String>) batch).commands().get(0)).isInstanceOf(Command.Exec.class);
        assertThat(((Command.Batch<String>) batch).commands().get(1)).isInstanceOf(Command.Suspend.class);
        assertThatThrownBy(() -> new Command.Exec<String>(null, value -> "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Command.Suspend<String>(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Command.exec(request, null)).isInstanceOf(NullPointerException.class);
    }

    // ── R-02: the terminal is given back, then taken again ──────────────────

    @Test
    void aHandoffGivesTheTerminalBackAndTakesItAgainBeforeTheResult() {
        try (TestTerminal fixture = new TestTerminal()) {
            AtomicBoolean releasedState = new AtomicBoolean();
            int[] writes = {-1, -1};
            fixture.onRelease(() -> {
                releasedState.set(true);
                writes[0] = fixture.writes().size();
            });
            fixture.onReacquire(() -> writes[1] = fixture.writes().size());
            var program = scene((model, message) -> switch (message) {
                case "run" -> Command.exec(ProcessRequest.of("/bin/sh", "-c", "exit 7"), ParityLifecycleTest::describe);
                case "handed:exited:7" -> Command.quit();
                default -> null;
            });
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            ExitResult result = run(fixture, loop, "r");

            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.failure()).isNull();
            assertThat(releasedState).isTrue();
            assertThat(writes[1])
                    .as("no frame is written while the terminal belongs to the child")
                    .isEqualTo(writes[0]);
            assertThat(loop.model())
                    .as("exactly one completion, delivered after the terminal came back")
                    .containsExactly("run", "handed:exited:7");

            String output = fixture.outputText();
            int left = output.indexOf(AnsiEncoder.ALT_SCREEN_OFF);
            int back = output.lastIndexOf(AnsiEncoder.ALT_SCREEN_ON);
            assertThat(left)
                    .as("the session gives the alternate screen back for the child")
                    .isGreaterThan(0);
            assertThat(back).as("and takes it again afterwards").isGreaterThan(left);
            assertThat(output.substring(left, back))
                    .as("the child runs outside the session's screen, so nothing is painted there")
                    .doesNotContain(SCENE);
            assertThat(output.substring(back))
                    .as("the frame after the resume is a full repaint of the scene")
                    .contains(SCENE + ":1:80")
                    .contains(AnsiEncoder.HIDE_CURSOR);
        }
    }

    @Test
    void aChildThatCannotStartIsReportedAndTheSessionKeepsItsTerminal() {
        try (TestTerminal fixture = new TestTerminal()) {
            var program = scene((model, message) -> switch (message) {
                case "run" ->
                    Command.exec(
                            ProcessRequest.of("/nonexistent/braxton-handoff-probe"), ParityLifecycleTest::describe);
                case "handed:failed" -> Command.quit();
                default -> null;
            });
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            ExitResult result = run(fixture, loop, "r");

            assertThat(result.kind())
                    .as("a child that cannot start does not end the session")
                    .isEqualTo(ExitResult.Kind.QUIT);
            assertThat(loop.model()).containsExactly("run", "handed:failed");
            assertThat(fixture.releases()).isEqualTo(1);
            assertThat(fixture.reacquires()).isEqualTo(1);
        }
    }

    @Test
    void aTerminalThatCannotBeTakenBackEndsTheRunWithATypedError() {
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.failReacquire(new IOException("the device disappeared"));
            var program = scene((model, message) -> switch (message) {
                case "run" -> Command.exec(ProcessRequest.of("/bin/sh", "-c", "exit 0"), ParityLifecycleTest::describe);
                case "handed:exited:0" -> Command.quit();
                default -> null;
            });
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            ExitResult result = run(fixture, loop, "r");

            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure())
                    .as("a session that cannot describe its own terminal fails instead of painting a guess")
                    .isInstanceOf(TerminalRecoveryException.class)
                    .hasMessageContaining("cannot take the terminal back");
            assertThat(loop.model())
                    .as("the completion is not delivered when the terminal could not be taken back")
                    .containsExactly("run");
            assertThat(fixture.isOpen()).isFalse();
        }
    }

    // ── R-03: suspend is a request, not a promise ────────────────────────────

    @Test
    void aSuspendFromASessionWithoutTheForegroundIsTypedRefused() {
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.setForeground(false);
            var program = scene((model, message) -> switch (message) {
                case "suspend" -> Command.suspend(ParityLifecycleTest::describe);
                case "suspend:refused" -> Command.quit();
                default -> null;
            });
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            ExitResult result = run(fixture, loop, "s");

            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(loop.model()).containsExactly("suspend", "suspend:refused");
            assertThat(fixture.releases())
                    .as("a refused suspend leaves the terminal where it was")
                    .isZero();
            assertThat(fixture.suspends()).isZero();
            assertThat(fixture.state()).isEqualTo(io.github.dhoard.braxton.internal.terminal.Terminal.State.CLOSED);
        }
    }

    // ── R-02/R-05: a removed scope runs nothing ─────────────────────────────

    @Test
    void aHandoffFromAComponentTheSameCommitRemovedNeverRuns(@TempDir Path directory) {
        Path marker = directory.resolve("handoff-ran");
        ComponentId child = ComponentId.of("child");
        ComponentPath childPath = ComponentPath.ROOT.append(child);
        Component<List<Boolean>, String, Never> program = new Component<>() {
            @Override
            public Update<List<Boolean>, String, Never> init(InitContext context) {
                return Update.next(List.of(true));
            }

            @Override
            public Update<List<Boolean>, String, Never> update(List<Boolean> model, String message) {
                if (!message.equals("run")) {
                    return Update.next(model);
                }
                // The same transition both asks for the handoff and unmounts the component it was
                // asked from, so the command's scope no longer exists when it is interpreted.
                return Update.next(
                        List.of(false),
                        Command.batch(
                                Command.exec(
                                                ProcessRequest.of("/bin/sh", "-c", "touch " + marker),
                                                ParityLifecycleTest::describe)
                                        .scoped(childPath),
                                Command.quit()));
            }

            @Override
            public View<String> view(List<Boolean> model, ViewContext context) {
                return Views.focus(
                        ElementId.of("scene"),
                        true,
                        List.of(new Binding<>(Key.of("r"), "run")),
                        View.InputHandler.none(),
                        Views.text(SCENE));
            }

            @Override
            public ComponentTree children(List<Boolean> model) {
                return model.get(0)
                        ? ComponentTree.of(new ComponentTree.Node(child, ComponentTree.empty()))
                        : ComponentTree.empty();
            }
        };
        try (TestTerminal fixture = new TestTerminal()) {
            AtomicBoolean fed = new AtomicBoolean();
            fixture.onOutput(text -> {
                if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                    fixture.feed("r");
                }
            });
            ExitResult result =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services()).run();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.cleanupFailures()).isEmpty();
            assertThat(Files.exists(marker))
                    .as("a handoff requested by a component the commit removed must not run")
                    .isFalse();
            assertThat(fixture.releases()).isZero();
        }
    }

    // ── R-05: a session that never hands off is unchanged ────────────────────

    @Test
    void aSessionThatNeverHandsOffWritesTheSameEntryAndExitSequenceAsBefore() {
        try (TestTerminal fixture = new TestTerminal()) {
            var program = scene((model, message) -> Command.quit());
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            ExitResult result = run(fixture, loop, "r");

            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            String output = fixture.outputText();
            assertThat(count(output, AnsiEncoder.ALT_SCREEN_ON)).isEqualTo(1);
            assertThat(count(output, AnsiEncoder.ALT_SCREEN_OFF)).isEqualTo(1);
            assertThat(count(output, AnsiEncoder.HIDE_CURSOR)).isEqualTo(1);
            assertThat(count(output, AnsiEncoder.SHOW_CURSOR)).isEqualTo(1);
            assertThat(count(output, AnsiEncoder.ENABLE_BRACKETED_PASTE)).isEqualTo(1);
            assertThat(fixture.releases()).isZero();
            assertThat(fixture.reacquires()).isZero();
        }
    }

    // ── R-04: the packaged demo ─────────────────────────────────────────────

    private static ProcessHandoffDemo.Scene demo() {
        return new ProcessHandoffDemo.Scene();
    }

    private static ProcessHandoffDemo.Model start() {
        return demo().init(new InitContext(VIEWPORT, null)).model();
    }

    private static ProcessHandoffDemo.Model apply(ProcessHandoffDemo.Model model, ProcessHandoffDemo.Msg message) {
        return demo().update(model, message).model();
    }

    /** What one key does to the demo's screen, as the scene's own message. */
    private static Optional<ProcessHandoffDemo.Msg> route(ProcessHandoffDemo.Model model, String spec) {
        Key gesture = Key.parse(spec);
        return InputRouter.route(
                demo().view(model, new ViewContext(VIEWPORT)), InputEvent.KeyInput.of(gesture, gesture.name()));
    }

    private static String row(ProcessHandoffDemo.Model model, int index) {
        return new ScreenRenderer()
                .plainLines(demo().view(model, new ViewContext(VIEWPORT)), VIEWPORT)
                .get(index)
                .strip();
    }

    @Test
    void theDemoRoutesItsKeysToAHandoffAndASuspend() {
        ProcessHandoffDemo.Model model = start();

        assertThat(route(model, "e")).containsInstanceOf(ProcessHandoffDemo.Msg.RunHelper.class);
        assertThat(route(model, "ctrl+z")).containsInstanceOf(ProcessHandoffDemo.Msg.Suspend.class);
        assertThat(route(model, "q")).containsInstanceOf(ProcessHandoffDemo.Msg.Quit.class);
        assertThat(route(model, "x")).containsInstanceOf(ProcessHandoffDemo.Msg.Type.class);

        Command<ProcessHandoffDemo.Msg> handoff =
                demo().update(model, new ProcessHandoffDemo.Msg.RunHelper()).command();
        assertThat(handoff).isInstanceOf(Command.Exec.class);
        ProcessRequest request = ((Command.Exec<?>) handoff).request();
        assertThat(request.command())
                .as("the child is this program started again, so the demo needs nothing installed")
                .contains(ProcessHandoffDemo.class.getName(), "--helper");
        assertThat(request.environment())
                .containsEntry(ProcessHandoffDemo.HELPER_VARIABLE, ProcessHandoffDemo.HELPER_VALUE);
        assertThat(request.directory()).isNotNull();
        assertThat(request.directory().isAbsolute()).isTrue();

        Command<ProcessHandoffDemo.Msg> suspend =
                demo().update(model, new ProcessHandoffDemo.Msg.Suspend()).command();
        assertThat(suspend).isInstanceOf(Command.Suspend.class);
    }

    @Test
    void theDemoKeepsTheDraftAndReportsHowTheHandoffEnded() {
        ProcessHandoffDemo.Model model = start();
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW)).isEqualTo("handoffs=0 last=none exit=- suspend=ready");
        assertThat(row(model, ProcessHandoffDemo.DRAFT_ROW)).isEqualTo("draft=-");

        model = apply(
                apply(apply(model, new ProcessHandoffDemo.Msg.Type("br")), new ProcessHandoffDemo.Msg.Type("ax")),
                new ProcessHandoffDemo.Msg.Type("ton"));
        assertThat(row(model, ProcessHandoffDemo.DRAFT_ROW)).isEqualTo("draft=braxton");

        model = apply(model, new ProcessHandoffDemo.Msg.Type("!".repeat(ProcessHandoffDemo.MAX_DRAFT * 2)));
        assertThat(model.draft()).as("the draft is bounded").hasSize(ProcessHandoffDemo.MAX_DRAFT);

        model = apply(model, new ProcessHandoffDemo.Msg.Handed(new ProcessResult.Exited(0)));
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW)).isEqualTo("handoffs=1 last=exited exit=0 suspend=ready");
        assertThat(row(model, ProcessHandoffDemo.DRAFT_ROW))
                .as("the draft survives the handoff")
                .isNotEqualTo("draft=braxton".substring(0, 6) + "-");

        model = apply(model, new ProcessHandoffDemo.Msg.Handed(new ProcessResult.Exited(9)));
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW)).isEqualTo("handoffs=2 last=exited exit=9 suspend=ready");
        model = apply(model, new ProcessHandoffDemo.Msg.Handed(new ProcessResult.Failed(new IOException("gone"))));
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW)).isEqualTo("handoffs=3 last=failed exit=- suspend=ready");
        model = apply(model, new ProcessHandoffDemo.Msg.Handed(new ProcessResult.Rejected("busy")));
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW))
                .isEqualTo("handoffs=4 last=rejected exit=- suspend=ready");
        model = apply(model, new ProcessHandoffDemo.Msg.Suspended(new SuspendResult.Resumed()));
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW)).endsWith("suspend=resumed");
        model = apply(model, new ProcessHandoffDemo.Msg.Suspended(new SuspendResult.Refused("no foreground")));
        assertThat(row(model, ProcessHandoffDemo.STATUS_ROW)).endsWith("suspend=refused");

        ProcessHandoffDemo.Model typed = apply(start(), new ProcessHandoffDemo.Msg.Type("abc"));
        assertThat(apply(typed, new ProcessHandoffDemo.Msg.Backspace()).draft())
                .as("backspace removes one character of the draft")
                .isEqualTo("ab");
    }

    @Test
    void theDemoPublishesAReusableSceneAndAMain() throws Exception {
        assertThat(ProcessHandoffDemo.class.getMethod("main", String[].class)).isNotNull();
        assertThat(ProcessHandoffDemo.class.getMethod("run")).isNotNull();
        assertThat(ProcessHandoffDemo.class.getMethod("defaultKeys")).isNotNull();
        assertThat(Component.class.isAssignableFrom(ProcessHandoffDemo.Scene.class))
                .as("the packaged PTY launch runs the same scene these cases drive")
                .isTrue();
        assertThat(ProcessHandoffDemo.Model.start().draft()).isEmpty();
        assertThat(ProcessHandoffDemo.defaultKeys().bindings()).isNotEmpty();
    }

    private static int count(String text, String token) {
        int count = 0;
        int at = text.indexOf(token);
        while (at >= 0) {
            count++;
            at = text.indexOf(token, at + token.length());
        }
        return count;
    }
}
