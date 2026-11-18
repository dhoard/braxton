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

package io.github.dhoard.braxton.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.ProcessRequest;
import io.github.dhoard.braxton.effect.ProcessResult;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.effect.SuspendResult;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 21 acceptance at the runtime's own seam: the terminal lifecycle a handoff drives.
 *
 * <p>These cases need what only the package can reach - the handoff the command interpreter calls,
 * the shutdown path a JVM shutdown hook runs, and the interval scheduler. The public behavior of the
 * same features is asserted in {@code ParityLifecycleTest}; here the evidence is the owned stream
 * terminal's state, the bytes written while it was released, and the child processes that must not
 * outlive the session.</p>
 */
@Timeout(60)
class TerminalHandoffTest {

    private static final Viewport VIEWPORT = new Viewport(8, 40);

    private static final String SCENE = "scene";

    /** Every scene here quits with this command once it has seen what it was waiting for. */
    private static final Command<String> QUIT = Command.quit();

    private static RuntimeOptions options(Duration grace) {
        return new RuntimeOptions(
                grace,
                4_096,
                64,
                64,
                4_096,
                64,
                1_024,
                ClipboardConfig.defaults(),
                io.github.dhoard.braxton.app.TerminalOptions.DEFAULT,
                InputOptions.DEFAULT,
                ColorProfile.AUTO);
    }

    /**
     * A two-key scene whose update is the test's own behaviour.
     *
     * <p>{@code r} produces {@code run} and {@code s} produces {@code suspend}; the behaviour decides
     * what command each message returns, so one scene serves every lifecycle case.</p>
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

    /** The exec command for one child, reported as {@code handed:<token>}. */
    private static Command<String> exec(String... argv) {
        return Command.exec(ProcessRequest.of(argv), TerminalHandoffTest::describe);
    }

    /** Runs one scene over an owned stream terminal, feeding {@code key} once it is painted. */
    private static ExitResult run(
            TestTerminal fixture,
            RuntimeOptions options,
            Component<List<String>, String, Never> program,
            RuntimeServices services,
            String key,
            AtomicReference<RuntimeLoop<List<String>, String>> handle) {
        var fed = new java.util.concurrent.atomic.AtomicBoolean();
        fixture.onOutput(text -> {
            if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                fixture.feed(key);
            }
        });
        RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(program, options, fixture.terminal(), services);
        if (handle != null) {
            handle.set(loop);
        }
        return loop.run();
    }

    private static RuntimeServices services(ManualTimers timers) {
        return RuntimeServices.system()
                .withTimers(timers)
                .withEffectExecutor(Runnable::run)
                .withNanoTime(() -> 1_000L);
    }

    // ── R-02: one handoff at a time ──────────────────────────────────────────

    @Test
    void aSecondHandoffIsRejectedWhileOneIsRunning() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            var released = new CountDownLatch(1);
            fixture.onRelease(released::countDown);
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var result = new AtomicReference<ExitResult>();
            var program = scene((model, message) -> switch (message) {
                case "run" -> exec("/bin/sh", "-c", "sleep 1");
                case "handed:exited:0" -> QUIT;
                default -> null;
            });
            Thread owner = Thread.ofVirtual()
                    .start(() -> result.set(run(
                            fixture,
                            options(Duration.ofSeconds(2)),
                            program,
                            services(new ManualTimers()),
                            "r",
                            loop)));
            try {
                assertThat(released.await(20, TimeUnit.SECONDS)).isTrue();
                ProcessResult second = loop.get().handOff(ProcessRequest.of("/bin/sh", "-c", "exit 0"));
                assertThat(second)
                        .as("two processes cannot own one terminal")
                        .isInstanceOfSatisfying(
                                ProcessResult.Rejected.class,
                                rejected -> assertThat(rejected.reason()).contains("another handoff"));
                assertThat(fixture.releases())
                        .as("the rejected handoff released nothing")
                        .isEqualTo(1);
            } finally {
                owner.join(30_000);
            }
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(loop.get().model()).containsExactly("run", "handed:exited:0");
            assertThat(fixture.reacquires()).isEqualTo(1);
        }
    }

    // ── R-03/R-05: shutdown from every state ─────────────────────────────────

    @Test
    void aSessionClosedWhileReleasedClosesTheTerminalOnce() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            var released = new CountDownLatch(1);
            fixture.onRelease(released::countDown);
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var result = new AtomicReference<ExitResult>();
            var program = scene((model, message) -> switch (message) {
                case "run" -> exec("/bin/sh", "-c", "sleep 30");
                default -> null;
            });
            Thread owner = Thread.ofVirtual()
                    .start(() -> result.set(run(
                            fixture,
                            options(Duration.ofMillis(200)),
                            program,
                            services(new ManualTimers()),
                            "r",
                            loop)));
            try {
                assertThat(released.await(20, TimeUnit.SECONDS)).isTrue();
                // This is what the JVM shutdown hook does on SIGTERM: give the terminal back, end
                // the child the session handed it to, and close from whatever state it is in.
                loop.get().shutdownRestore();
                owner.join(30_000);
            } finally {
                owner.join(30_000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(fixture.state()).isEqualTo(Terminal.State.CLOSED);
            assertThat(fixture.reacquires())
                    .as("a closing session does not take the terminal again")
                    .isZero();
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
            String output = fixture.outputText();
            assertThat(output)
                    .as("the exit path gave the modes back once, and the close added nothing")
                    .containsOnlyOnce("\u001B[?1049l");
            assertThat(result.get().cleanupFailures()).isEmpty();
        }
    }

    @Test
    void aChildThatIgnoresTerminationIsKilledAfterTheGrace() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            var released = new CountDownLatch(1);
            fixture.onRelease(released::countDown);
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var program = scene((model, message) -> switch (message) {
                // Stops itself and ignores SIGTERM: only a forced kill ends it.
                case "run" -> exec("/bin/sh", "-c", "trap '' TERM; kill -s STOP $$");
                default -> null;
            });
            long started = System.nanoTime();
            Thread owner = Thread.ofVirtual()
                    .start(() -> run(
                            fixture,
                            options(Duration.ofMillis(200)),
                            program,
                            services(new ManualTimers()),
                            "r",
                            loop));
            try {
                assertThat(released.await(20, TimeUnit.SECONDS)).isTrue();
                loop.get().shutdownRestore();
                owner.join(30_000);
                assertThat(owner.isAlive()).isFalse();
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .as("the forced kill ends the child instead of waiting for it")
                        .isLessThan(20_000L);
            } finally {
                owner.join(30_000);
            }
            assertThat(fixture.state()).isEqualTo(Terminal.State.CLOSED);
        }
    }

    @Test
    void anInterruptedOwnerEndsTheChildAndReportsTheInterruption() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            var released = new CountDownLatch(1);
            fixture.onRelease(released::countDown);
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var result = new AtomicReference<ExitResult>();
            var program = scene((model, message) -> switch (message) {
                case "run" -> exec("/bin/sh", "-c", "sleep 30");
                default -> null;
            });
            Thread owner = Thread.ofVirtual()
                    .start(() -> result.set(run(
                            fixture,
                            options(Duration.ofSeconds(2)),
                            program,
                            services(new ManualTimers()),
                            "r",
                            loop)));
            try {
                assertThat(released.await(20, TimeUnit.SECONDS)).isTrue();
                owner.interrupt();
                owner.join(30_000);
            } finally {
                owner.join(30_000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INTERRUPTED);
            assertThat(fixture.reacquires())
                    .as("the terminal is taken back before the run ends")
                    .isEqualTo(1);
            assertThat(fixture.state()).isEqualTo(Terminal.State.CLOSED);
        }
    }

    // ── R-03: suspend and resume ─────────────────────────────────────────────

    @Test
    void aSuspendWithoutTheForegroundIsRefusedWithoutReleasingTheTerminal() {
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.setForeground(false);
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var program = scene((model, message) -> switch (message) {
                case "suspend" -> Command.suspend(TerminalHandoffTest::describeSuspend);
                case "suspend:refused" -> QUIT;
                default -> null;
            });
            ExitResult result =
                    run(fixture, options(Duration.ofSeconds(2)), program, services(new ManualTimers()), "s", loop);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(fixture.releases()).isZero();
            assertThat(fixture.suspends()).isZero();
            assertThat(loop.get().model()).containsExactly("suspend", "suspend:refused");
        }
    }

    @Test
    void aSuspendReturnsTheTerminalMeasuresItAgainAndDeliversOneResume() {
        try (TestTerminal fixture = new TestTerminal()) {
            var writesAtRelease = new java.util.concurrent.atomic.AtomicInteger();
            var writesAtReacquire = new java.util.concurrent.atomic.AtomicInteger();
            var releasedState = new AtomicReference<Terminal.State>();
            fixture.onRelease(() -> {
                releasedState.set(fixture.state());
                writesAtRelease.set(fixture.writes().size());
                // The job was stopped long enough for the user to resize the terminal.
                fixture.resize(new Size(10, 50));
            });
            fixture.onReacquire(() -> writesAtReacquire.set(fixture.writes().size()));
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var program = scene((model, message) -> switch (message) {
                case "suspend" -> Command.suspend(TerminalHandoffTest::describeSuspend);
                case "suspend:resumed" -> QUIT;
                default -> null;
            });
            ExitResult result =
                    run(fixture, options(Duration.ofSeconds(2)), program, services(new ManualTimers()), "s", loop);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(releasedState.get()).isEqualTo(Terminal.State.RELEASED);
            assertThat(writesAtReacquire.get())
                    .as("no frame is written while the session does not own the terminal")
                    .isEqualTo(writesAtRelease.get());
            assertThat(fixture.releases()).isEqualTo(1);
            assertThat(fixture.reacquires()).isEqualTo(1);
            assertThat(fixture.suspends()).isEqualTo(1);
            assertThat(loop.get().model()).containsExactly("suspend", "suspend:resumed");
            String output = fixture.outputText();
            assertThat(output).contains("\u001B[?1049l", "\u001B[?1049h");
            assertThat(output)
                    .as("the resumed frame is laid out for the size measured after the resume")
                    .contains(SCENE + ":2:50");
            assertThat(output.lastIndexOf("\u001B[?1049h"))
                    .as("the alternate screen is taken back after the suspend")
                    .isGreaterThan(output.indexOf("\u001B[?1049l"));
        }
    }

    @Test
    void repeatedSuspendCyclesKeepOneSessionTerminal() {
        try (TestTerminal fixture = new TestTerminal()) {
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var program = scene((model, message) -> switch (message) {
                case "suspend" -> Command.suspend(TerminalHandoffTest::describeSuspend);
                case "suspend:resumed" ->
                    model.size() < 4 ? Command.suspend(TerminalHandoffTest::describeSuspend) : QUIT;
                default -> null;
            });
            ExitResult result =
                    run(fixture, options(Duration.ofSeconds(2)), program, services(new ManualTimers()), "s", loop);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(fixture.releases()).isEqualTo(3);
            assertThat(fixture.reacquires()).isEqualTo(3);
            assertThat(fixture.suspends()).isEqualTo(3);
            assertThat(loop.get().model())
                    .containsExactly("suspend", "suspend:resumed", "suspend:resumed", "suspend:resumed");
        }
    }

    @Test
    void aJobControlFailureIsReportedAndTheTerminalIsTakenBack() {
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.failSuspend(new IOException("no job control in this process"));
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            var program = scene((model, message) -> switch (message) {
                case "suspend" -> Command.suspend(TerminalHandoffTest::describeSuspend);
                case "suspend:failed" -> QUIT;
                default -> null;
            });
            ExitResult result =
                    run(fixture, options(Duration.ofSeconds(2)), program, services(new ManualTimers()), "s", loop);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(fixture.reacquires()).isEqualTo(1);
            assertThat(loop.get().model()).containsExactly("suspend", "suspend:failed");
        }
    }

    // ── R-03: interval ticks coalesce across a handoff ───────────────────────

    @Test
    void anInlineSessionPutsItsRegionBackAfterAHandoff() throws Exception {
        // Phase 23: the region a non-alternate-screen session manages is repainted below whatever the
        // child printed, and nothing above it is erased on the way back.
        RuntimeException failure = null;
        try (TestTerminal fixture = new TestTerminal()) {
            Component<List<String>, String, Never> program = scene((model, message) -> switch (message) {
                case "run" -> exec("/bin/sh", "-c", "exit 0");
                case "handed:exited:0" -> QUIT;
                default -> null;
            });
            RuntimeOptions inline = new RuntimeOptions(
                    Duration.ofSeconds(2),
                    4_096,
                    64,
                    64,
                    4_096,
                    64,
                    1_024,
                    ClipboardConfig.defaults(),
                    io.github.dhoard.braxton.app.TerminalOptions.DEFAULT.withAlternateScreen(false),
                    InputOptions.DEFAULT,
                    ColorProfile.AUTO);
            ExitResult result = run(fixture, inline, program, services(new ManualTimers()), "r", null);

            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            String transcript = fixture.outputText();
            assertThat(transcript)
                    .as("the inline session never entered the alternate screen")
                    .doesNotContain("\u001b[?1049h");
            assertThat(transcript)
                    .as("nothing above the region was ever erased")
                    .doesNotContain("\u001b[2J");
            assertThat(java.util.regex.Pattern.compile("\u001b\\[\\d+;\\d+H")
                            .matcher(transcript)
                            .find())
                    .as("an inline frame addresses no row absolutely: " + transcript.replace("\u001b", "\\e"))
                    .isFalse();
            assertThat(transcript)
                    .as("the region was repainted after the terminal was taken back, with both messages")
                    .contains("scene:2:80");
        } catch (RuntimeException thrown) {
            failure = thrown;
        }
        if (failure != null) throw failure;
    }

    @Test
    void intervalTicksThatElapseWhileReleasedArriveOnce() {
        try (TestTerminal fixture = new TestTerminal()) {
            var timers = new ManualTimers();
            var program = new Component<List<String>, String, Never>() {
                @Override
                public Update<List<String>, String, Never> init(InitContext context) {
                    return Update.next(List.of());
                }

                @Override
                public Update<List<String>, String, Never> update(List<String> model, String message) {
                    List<String> next = new ArrayList<>(model);
                    next.add(message);
                    return switch (message) {
                        case "run" -> Update.next(List.copyOf(next), exec("/bin/sh", "-c", "exit 0"));
                        case "handed:exited:0" -> Update.next(List.copyOf(next), QUIT);
                        default -> Update.next(List.copyOf(next));
                    };
                }

                @Override
                public View<String> view(List<String> model, ViewContext context) {
                    return Views.focus(
                            ElementId.of("scene"),
                            true,
                            List.of(new Binding<>(Key.of("r"), "run")),
                            View.InputHandler.none(),
                            Views.text(SCENE + ":" + model.size()));
                }

                @Override
                public Subscription<String> subscriptions(List<String> model) {
                    return Subscription.interval(SubscriptionId.of("ticks"), Duration.ofMillis(10), now -> "tick");
                }
            };
            fixture.onRelease(() -> {
                // Three periods elapse while somebody else owns the terminal.
                timers.advance(10_000_000L);
                timers.advance(10_000_000L);
                timers.advance(10_000_000L);
            });
            var loop = new AtomicReference<RuntimeLoop<List<String>, String>>();
            ExitResult result = run(fixture, options(Duration.ofSeconds(2)), program, services(timers), "r", loop);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(loop.get().model())
                    .as("queued ticks coalesce into one on resume rather than arriving as a burst")
                    .containsExactly("run", "tick", "handed:exited:0");
        }
    }

    /** One child result as the token the scene records. */
    static String describe(ProcessResult result) {
        return switch (result) {
            case ProcessResult.Exited exited -> "handed:exited:" + exited.status();
            case ProcessResult.Failed failed -> "handed:failed";
            case ProcessResult.Rejected rejected -> "handed:rejected";
        };
    }

    /** One suspend outcome as the token the scene records. */
    static String describeSuspend(SuspendResult result) {
        return switch (result) {
            case SuspendResult.Resumed resumed -> "suspend:resumed";
            case SuspendResult.Refused refused -> "suspend:refused";
            case SuspendResult.Failed failed -> "suspend:failed";
        };
    }
}
