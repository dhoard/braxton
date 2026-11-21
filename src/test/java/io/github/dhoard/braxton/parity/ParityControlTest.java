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

import examples.parity.ApplicationControlDemo;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.MessageFilter;
import io.github.dhoard.braxton.app.QuitRequest;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.runtime.EffectRunner;
import io.github.dhoard.braxton.internal.runtime.Mailbox;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.ScopeManager;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Initialization;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 22 acceptance: command ordering and application control.
 *
 * <p>Four things are asserted against observable behavior. A {@link Command.Sequence} submits its next
 * step only when the previous one has finished, and its messages arrive in that order - proven with a
 * gated task, a delay step, a batch step, a canceled step, a refused step and a removed scope, not
 * with timings. A {@link MessageFilter} can rewrite and drop the messages a session delivers and can
 * veto a graceful quit, while its own failure fails the run after cleanup. The frame ceiling is a
 * documented range and paces presentations. The packaged demo - the artifact the PTY scenario launches
 * - routes its keys, runs its sequence and guards its quit.</p>
 *
 * <p>Runtime cases drive a real {@code RuntimeLoop} over an owned stream terminal, so the evidence is
 * the model the program committed and the bytes the session wrote. Effect-level cases drive the
 * interpreter directly where the state under test is the interpreter's own bookkeeping.</p>
 */
@Timeout(120)
class ParityControlTest {

    private static final Viewport VIEWPORT = new Viewport(8, 80);
    private static final String SCENE = "control";
    /**
     * One synchronized-output bracket start; a presented frame opens exactly one.
     */
    private static final Pattern BEGIN = Pattern.compile(Pattern.quote("\u001b[?2026h"));

    private static final long WAIT_SECONDS = 10L;

    /**
     * Real timers and virtual-thread tasks: the runtime a program actually gets.
     */
    private static RuntimeServices services() {
        return RuntimeServices.system()
                .withEffectExecutor(
                        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
    }

    /**
     * The services a case needs when it supplies its own executor.
     */
    private static RuntimeServices services(ExecutorService executor) {
        return RuntimeServices.system().withEffectExecutor(executor);
    }

    /**
     * A one-row scene whose update is the test's own behavior.
     *
     * @param behavior the command each message returns, or null for none
     * @param snapshots where to record every committed model, or null to skip
     * @param keys the key specs this scene binds, each delivering its own name
     * @return the program
     */
    private static Component<List<String>, String, Never> scene(
            BiFunction<List<String>, String, Command<String>> behavior, List<List<String>> snapshots, String... keys) {
        List<Binding<String>> bindings = new ArrayList<>();
        for (String key : keys) {
            bindings.add(new Binding<>(Key.of(key), key));
        }
        List<Binding<String>> frozen = List.copyOf(bindings);
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
                Command<String> command = behavior.apply(recorded, message);
                return command == null ? Update.next(recorded) : Update.next(recorded, command);
            }

            @Override
            public Element<String> view(List<String> model, ViewContext context) {
                if (snapshots != null) {
                    snapshots.add(List.copyOf(model));
                }
                return Elements.text(SCENE + ":" + String.join(",", model));
            }

            /**
             * The gestures this fixture answers, once no leaf has taken them.
             */
            @Override
            public java.util.List<Binding<String>> claims(List<String> model) {
                return frozen;
            }
        };
    }

    /**
     * Starts a loop on its own thread and feeds {@code key} once its first frame is painted.
     */
    private static Thread start(
            TestTerminal fixture,
            RuntimeLoop<List<String>, String> loop,
            AtomicReference<ExitResult> result,
            String key) {
        AtomicBoolean fed = new AtomicBoolean();
        fixture.onOutput(text -> {
            if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                fixture.feed(key);
            }
        });
        return Thread.ofVirtual().start(() -> result.set(loop.run()));
    }

    /**
     * Waits until some committed model satisfies {@code condition}.
     */
    private static List<String> awaitModel(
            List<List<String>> snapshots, Predicate<List<String>> condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (List<String> snapshot : snapshots) {
                if (condition.test(snapshot)) {
                    return snapshot;
                }
            }
            Thread.sleep(5L);
        }
        throw new AssertionError(description + " did not happen; committed models: " + snapshots);
    }

    /**
     * The last model the program committed.
     */
    private static List<String> last(List<List<String>> snapshots) {
        return snapshots.isEmpty() ? List.of() : snapshots.get(snapshots.size() - 1);
    }

    // ── R-01: a sequence starts the next step when the previous one finishes ─

    @Test
    void aSequenceStartsEachStepOnlyAfterThePreviousOneFinished() throws Exception {
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "r" ->
                        Command.sequence(
                                gated("a", gate, started), gated("b", gate, started), gated("c", gate, started));
                    case "c" -> Command.quit();
                    default -> null;
                },
                snapshots,
                "r");
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services(tasks));
            Thread owner = start(fixture, loop, result, "r");

            // The first step is blocked inside its body: nothing after it may have started.
            awaitModel(snapshots, model -> started.get() >= 1, "the first step starting");
            Thread.sleep(150L);
            assertThat(started.get())
                    .as("the second step must not start while the first one is still running")
                    .isEqualTo(1);
            assertThat(last(snapshots))
                    .as("no later step's message may arrive yet")
                    .containsExactly("r");

            gate.countDown();
            awaitModel(snapshots, model -> model.contains("c"), "the third step");
            assertThat(last(snapshots))
                    .as("the steps ran in declaration order, each after the previous one finished")
                    .containsExactly("r", "a", "b", "c");
            assertThat(started).as("each step's body ran exactly once").hasValue(3);
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
        } finally {
            tasks.shutdownNow();
        }
    }

    @Test
    void aSequenceDeliversTaskAndDelayMessagesInDeclarationOrder() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "r" ->
                        Command.sequence(
                                named("first"),
                                Command.delay(EffectId.of("wait"), Duration.ofMillis(30L), "tick", "rejected"),
                                named("last"));
                    case "last" -> Command.quit();
                    default -> null;
                },
                snapshots,
                "r");
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            Thread owner = start(fixture, loop, result, "r");

            awaitModel(snapshots, model -> model.contains("last"), "the last step");
            assertThat(last(snapshots))
                    .as("a delay step delivers before the next step is submitted")
                    .containsExactly("r", "first", "tick", "last");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
        }
    }

    @Test
    void aSequenceContinuesAfterAStepFailureIsReported() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "r" ->
                        Command.sequence(
                                Command.task(
                                        EffectId.of("boom"),
                                        () -> {
                                            throw new IllegalStateException("no");
                                        },
                                        value -> "unreachable",
                                        failure -> "failed"),
                                named("after"));
                    case "after" -> Command.quit();
                    default -> null;
                },
                snapshots,
                "r");
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            Thread owner = start(fixture, loop, result, "r");

            awaitModel(snapshots, model -> model.contains("after"), "the step after the failure");
            assertThat(last(snapshots))
                    .as("a step's failure message is the step's completion")
                    .containsExactly("r", "failed", "after");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
        }
    }

    @Test
    void aSequenceDoesNotWaitForTheReducerOfItsOwnStep() throws Exception {
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        CountDownLatch inUpdate = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        AtomicBoolean secondStarted = new AtomicBoolean();
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "r" ->
                        Command.sequence(
                                Command.dispatch("first"),
                                Command.task(
                                        EffectId.of("second"),
                                        () -> {
                                            secondStarted.set(true);
                                            return "second";
                                        },
                                        value -> value,
                                        failure -> "failed"));
                    case "first" -> {
                        // A reducer that takes its time: the step after it must already be submitted,
                        // because a sequence orders completion and does not wait for the model.
                        inUpdate.countDown();
                        try {
                            releaseUpdate.await();
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                        }
                        yield null;
                    }
                    case "second" -> Command.quit();
                    default -> null;
                },
                snapshots,
                "r");
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services(tasks));
            Thread owner = start(fixture, loop, result, "r");

            assertThat(inUpdate.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the first step's message reached update")
                    .isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (!secondStarted.get() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertThat(secondStarted)
                    .as("the next step was submitted while the reducer was still running")
                    .isTrue();

            releaseUpdate.countDown();
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(last(snapshots)).containsExactly("r", "first", "second");
        } finally {
            tasks.shutdownNow();
        }
    }

    @Test
    void aBatchStepInASequenceWaitsForEveryMember() throws Exception {
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        CountDownLatch firstMember = new CountDownLatch(1);
        CountDownLatch secondMember = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "r" ->
                        Command.sequence(
                                Command.batch(gated("a", firstMember, started), gated("b", secondMember, started)),
                                named("last"));
                    case "last" -> Command.quit();
                    default -> null;
                },
                snapshots,
                "r");
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services(tasks));
            Thread owner = start(fixture, loop, result, "r");
            awaitModel(snapshots, model -> started.get() >= 2, "both batch members starting");

            firstMember.countDown();
            awaitModel(snapshots, model -> model.contains("a"), "the first batch member");
            Thread.sleep(150L);
            assertThat(last(snapshots))
                    .as("the sequence waits for the whole batch, not for its first member")
                    .doesNotContain("last");

            secondMember.countDown();
            awaitModel(snapshots, model -> model.contains("last"), "the step after the batch");
            assertThat(last(snapshots)).contains("a", "b", "last");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
        } finally {
            tasks.shutdownNow();
        }
    }

    @Test
    void anExhaustedEffectQuotaFinishesAStepWithItsRejection() throws Exception {
        Mailbox mailbox = new Mailbox(8);
        RuntimeServices environment = new RuntimeServices(() -> 0L, Runnable::run, new ManualTimers(), message -> {});
        EffectRunner<String> runner = new EffectRunner<>(mailbox, environment, clipboard(), 0);
        runner.bindOwner(Thread.currentThread());

        runner.interpret(Command.sequence(
                Command.task(EffectId.of("one"), () -> "one", value -> value, failure -> "rejected-one"),
                Command.task(EffectId.of("two"), () -> "two", value -> value, failure -> "rejected-two")));

        assertThat(pump(mailbox))
                .as("a step the quota refused still finishes the sequence, in order")
                .containsExactly("rejected-one", "rejected-two");
    }

    @Test
    void cancellingTheRunningStepStopsTheSequence() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "r" ->
                        Command.batch(
                                Command.sequence(
                                        Command.delay(
                                                EffectId.of("waiting"), Duration.ofSeconds(5L), "tick", "rejected"),
                                        named("after")),
                                Command.cancel(EffectId.of("waiting")));
                    case "q" -> Command.quit();
                    default -> null;
                },
                snapshots,
                "r",
                "q");
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), services());
            Thread owner = start(fixture, loop, result, "r");
            awaitModel(snapshots, model -> model.contains("r"), "the starting key");
            Thread.sleep(100L);
            fixture.feed("q");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(last(snapshots))
                    .as("a canceled step neither delivers nor lets the sequence continue")
                    .containsExactly("r", "q");
        }
    }

    @Test
    void aSequenceWhoseScopeWasRemovedSubmitsNothingFurther() throws Exception {
        Mailbox mailbox = new Mailbox(16);
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicBoolean secondStarted = new AtomicBoolean();
        try {
            EffectRunner<String> runner = new EffectRunner<>(
                    mailbox, new RuntimeServices(() -> 0L, tasks, new ManualTimers(), message -> {}), clipboard(), 8);
            ScopeManager scopes = new ScopeManager();
            List<Mailbox.AppMessage> local = new ArrayList<>();
            runner.bindOwner(Thread.currentThread());
            runner.bindScopes(scopes, local::add);
            ComponentId child = ComponentId.of("child");
            ComponentPath path = ComponentPath.ROOT.append(child);
            scopes.commit(scopes.prepare(ComponentTree.of(new ComponentTree.Node(child, ComponentTree.empty())), 8, 8));

            runner.interpret(Command.sequence(
                            gated("first", gate, new AtomicInteger()),
                            Command.task(
                                    EffectId.of("second"),
                                    () -> {
                                        secondStarted.set(true);
                                        return "second";
                                    },
                                    value -> value,
                                    failure -> "failed"))
                    .scoped(path));

            // The parent unmounts the child while the first step is still running.
            scopes.commit(scopes.prepare(ComponentTree.empty(), 8, 8));
            gate.countDown();

            List<String> messages = pump(mailbox);
            assertThat(messages).contains("first");
            assertThat(secondStarted)
                    .as("the remaining steps belong to a component that no longer exists")
                    .isFalse();
            assertThat(messages).doesNotContain("second");
        } finally {
            tasks.shutdownNow();
        }
    }

    // ── R-03: message filtering, quit veto and the frame ceiling ─────────────

    @Test
    void aFilterTransformsAndDropsTheMessagesTheProgramSees() {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> message.equalsIgnoreCase("q") ? Command.quit() : null, snapshots, "r", "s", "q");
        MessageFilter filter = new MessageFilter() {
            @Override
            public Object apply(Object message) {
                if ("s".equals(message)) return null;
                return message.toString().toUpperCase(Locale.ROOT);
            }
        };
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    program, RuntimeOptions.defaults().withMessageFilter(filter), fixture.terminal(), services());
            AtomicBoolean fed = new AtomicBoolean();
            fixture.onOutput(text -> {
                if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                    fixture.feed("s");
                    fixture.feed("r");
                    fixture.feed("q");
                }
            });
            ExitResult result = loop.run();

            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(last(snapshots))
                    .as("a dropped message never reaches update, a transformed one arrives as returned")
                    .containsExactly("R", "Q");
        }
    }

    @Test
    void aFilterCanVetoAGracefulQuit() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ExitResult> result = new AtomicReference<>();
        AtomicInteger offers = new AtomicInteger();
        Component<List<String>, String, Never> program =
                scene((model, message) -> message.equals("q") ? Command.quit(3) : null, snapshots, "q");
        MessageFilter filter = new MessageFilter() {
            @Override
            public Object apply(Object message) {
                if (message instanceof QuitRequest) {
                    // Refuse the first request, accept the second: the guard an application puts in
                    // front of an exit that needs confirming.
                    return offers.incrementAndGet() > 1 ? message : null;
                }
                return message;
            }
        };
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    program, RuntimeOptions.defaults().withMessageFilter(filter), fixture.terminal(), services());
            Thread owner = start(fixture, loop, result, "q");
            awaitModel(snapshots, model -> model.contains("q"), "the quit request");
            Thread.sleep(200L);
            assertThat(owner.isAlive())
                    .as("a vetoed quit leaves the session running")
                    .isTrue();
            fixture.feed("q");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.get().status())
                    .as("the accepted status is the one reported")
                    .isEqualTo(3);
            assertThat(offers).as("the filter was offered both requests").hasValue(2);
        }
    }

    @Test
    void aFilterThatThrowsEndsTheRunAfterCleanup() {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program = scene((model, message) -> null, snapshots, "r");
        MessageFilter filter = new MessageFilter() {
            @Override
            public Object apply(Object message) {
                throw new IllegalStateException("filter bug");
            }
        };
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    program, RuntimeOptions.defaults().withMessageFilter(filter), fixture.terminal(), services());
            AtomicBoolean fed = new AtomicBoolean();
            fixture.onOutput(text -> {
                if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                    fixture.feed("r");
                }
            });
            ExitResult result = loop.run();

            assertThat(result.kind())
                    .as("a filter is application code, so its bug fails the run")
                    .isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure()).hasMessageContaining("message filter");
            assertThat(result.failure().getCause()).hasMessage("filter bug");
            assertThat(fixture.state())
                    .as("cleanup still ran: the terminal was closed")
                    .isEqualTo(Terminal.State.CLOSED);
        }
    }

    @Test
    void aPropagatedFailureIsThrownOnlyAfterCleanup() {
        Component<List<String>, String, Never> program =
                scene((model, message) -> null, new CopyOnWriteArrayList<>(), "r");
        MessageFilter filter = message -> {
            throw new IllegalStateException("filter bug");
        };
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    program,
                    RuntimeOptions.defaults().withMessageFilter(filter).withFailurePropagation(true),
                    fixture.terminal(),
                    services());
            AtomicBoolean fed = new AtomicBoolean();
            fixture.onOutput(text -> {
                if (text.contains(SCENE) && fed.compareAndSet(false, true)) {
                    fixture.feed("r");
                }
            });

            assertThatThrownBy(loop::run)
                    .as("the failure is thrown, not returned")
                    .isInstanceOf(ComponentFailure.class)
                    .hasMessageContaining("message filter");
            assertThat(fixture.state())
                    .as("cleanup ran before the failure was thrown")
                    .isEqualTo(Terminal.State.CLOSED);
        }
    }

    @Test
    void aFilterLeavesAScopedCommandsOwnScopeAlone() {
        List<String> seen = new CopyOnWriteArrayList<>();
        MessageFilter recording = message -> {
            seen.add(String.valueOf(message));
            return message;
        };
        Component<Integer, String, Never> child = new Component<>() {
            @Override
            public Update<Integer, String, Never> init(InitContext context) {
                // A one-shot effect the runtime interprets in this child's scope, not the root's.
                return Update.next(
                        0, Command.task(EffectId.of("work"), () -> "from-child", value -> value, failure -> "failed"));
            }

            @Override
            public Update<Integer, String, Never> update(Integer model, String message) {
                return message.equals("from-child") ? Update.next(model + 1, Command.quit()) : Update.next(model);
            }

            @Override
            public Element<String> view(Integer model, ViewContext context) {
                return Elements.text(SCENE + ":child:" + model);
            }
        };
        Mount<List<Integer>, String, Never, Integer, String, Never> mount = Mount.of(
                ComponentId.of("child"),
                child,
                ModelLens.of(model -> model.get(0), (model, value) -> List.of(value)),
                value -> value,
                OutputRoute.toMessage(value -> "unreachable"));
        Component<List<Integer>, String, Never> parent = new Component<>() {
            @Override
            public Update<List<Integer>, String, Never> init(InitContext context) {
                Initialization<Integer, String, Never> initialized = mount.initialize(context);
                return Update.next(List.of(initialized.model()), initialized.command());
            }

            @Override
            public Update<List<Integer>, String, Never> update(List<Integer> model, String message) {
                return mount.update(model, message);
            }

            @Override
            public Element<String> view(List<Integer> model, ViewContext context) {
                return Elements.text(SCENE + ":parent:" + model);
            }

            @Override
            public ComponentTree children(List<Integer> model) {
                return ComponentTree.of(mount.tree(model));
            }
        };
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<Integer>, String> loop = new RuntimeLoop<>(
                    parent, RuntimeOptions.defaults().withMessageFilter(recording), fixture.terminal(), services());
            ExitResult result = loop.run();

            assertThat(result.kind())
                    .as("the child's scoped completion reached its own update and quit")
                    .isEqualTo(ExitResult.Kind.QUIT);
            assertThat(loop.model())
                    .as("the scoped effect's message was not dropped by the filter or the scope check")
                    .containsExactly(1);
            assertThat(seen)
                    .as("the filter saw the delivered message once, and the quit it caused")
                    .containsExactly("from-child", "QuitRequest[status=0]");
        }
    }

    @Test
    void theFrameCeilingIsADocumentedRangeAndPacesPresentations() {
        assertThat(RuntimeOptions.DEFAULT_FPS).isEqualTo(60);
        assertThat(RuntimeOptions.frameIntervalNanos(1))
                .isEqualTo(Duration.ofSeconds(1L).toNanos());
        assertThat(RuntimeOptions.frameIntervalNanos(240))
                .isEqualTo(Duration.ofSeconds(1L).toNanos() / 240L);
        assertThatThrownBy(() -> RuntimeOptions.defaults().withFps(0))
                .as("below one frame a second")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fps");
        assertThatThrownBy(() -> RuntimeOptions.defaults().withFps(241))
                .as("above the supported ceiling")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fps");
        assertThatThrownBy(() -> RuntimeOptions.frameIntervalNanos(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RuntimeOptions.defaults().withFps(30).fps()).isEqualTo(30);
    }

    @Test
    void aSlowerFrameCeilingCoalescesMoreChangesIntoOneFrame() throws Exception {
        int fast = frames(RuntimeOptions.defaults());
        int slow = frames(RuntimeOptions.defaults().withFps(1));

        assertThat(slow)
                .as("two changes 40 ms apart share one frame at one frame per second")
                .isEqualTo(2);
        assertThat(fast).as("the default ceiling presents them separately").isGreaterThan(slow);
    }

    /**
     * Runs a scene whose two source messages arrive 40 ms apart, and counts the frames it presented.
     *
     * @param options the session options under test
     * @return how many synchronized frames the session wrote
     */
    private static int frames(RuntimeOptions options) {
        Component<List<String>, String, Never> program = new Component<>() {
            @Override
            public Update<List<String>, String, Never> init(InitContext context) {
                return Update.next(List.of());
            }

            @Override
            public Update<List<String>, String, Never> update(List<String> model, String message) {
                List<String> next = new ArrayList<>(model);
                next.add(message);
                return message.equals("quit")
                        ? Update.next(List.copyOf(next), Command.quit())
                        : Update.next(List.copyOf(next));
            }

            @Override
            public Element<String> view(List<String> model, ViewContext context) {
                return Elements.text(SCENE + ":" + String.join(",", model));
            }

            @Override
            public Subscription<String> subscriptions(List<String> model) {
                return Subscription.source(
                        SubscriptionId.of("feed"),
                        "feed",
                        sink -> {
                            Thread.ofVirtual().start(() -> {
                                sink.accept("one");
                                try {
                                    Thread.sleep(40L);
                                } catch (InterruptedException cancelled) {
                                    return;
                                }
                                sink.accept("two");
                                sink.accept("quit");
                            });
                            return () -> {};
                        },
                        value -> (String) value);
            }
        };
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop =
                    new RuntimeLoop<>(program, options, fixture.terminal(), services());
            ExitResult result = loop.run();
            assertThat(result.kind()).isIn(ExitResult.Kind.QUIT, ExitResult.Kind.INPUT_CLOSED);
            return BEGIN.split(fixture.outputText(), -1).length - 1;
        }
    }

    // ── R-04: the packaged demo ─────────────────────────────────────────────

    private static ApplicationControlDemo.Scene demo() {
        return new ApplicationControlDemo.Scene();
    }

    private static ApplicationControlDemo.Model start() {
        return demo().init(new InitContext(VIEWPORT, null)).model();
    }

    private static ApplicationControlDemo.Model apply(
            ApplicationControlDemo.Model model, ApplicationControlDemo.Msg message) {
        return demo().update(model, message).model();
    }

    private static Optional<ApplicationControlDemo.Msg> route(ApplicationControlDemo.Model model, String spec) {
        Key gesture = Key.parse(spec);
        return io.github.dhoard.braxton.testing.Routing.route(
                        demo(), model, new ViewContext(VIEWPORT), InputEvent.KeyInput.of(gesture, gesture.name()))
                .stream()
                .findFirst();
    }

    private static String row(ApplicationControlDemo.Model model, int index) {
        return ElementScreen.render(demo().view(model, new ViewContext(VIEWPORT)), VIEWPORT.rows(), VIEWPORT.cols())
                .lines()
                .get(index)
                .strip();
    }

    @Test
    void theDemoRoutesItsKeysAndRunsItsSequenceInOrder() {
        ApplicationControlDemo.Model model = start();
        assertThat(row(model, ApplicationControlDemo.STATUS_ROW))
                .isEqualTo("progress=- ticks=0 sequence=idle order=none quit=guarded");
        assertThat(row(model, ApplicationControlDemo.PROMPT_ROW)).isEqualTo("prompt=none s sequence • q quit");

        assertThat(route(model, "s")).containsInstanceOf(ApplicationControlDemo.Msg.StartSequence.class);
        assertThat(route(model, "q")).containsInstanceOf(ApplicationControlDemo.Msg.RequestQuit.class);
        assertThat(route(model, "y")).containsInstanceOf(ApplicationControlDemo.Msg.ConfirmQuit.class);
        assertThat(route(model, "esc")).containsInstanceOf(ApplicationControlDemo.Msg.CancelQuit.class);
        assertThat(route(model, "x"))
                .as("the scene is a status board, not a field")
                .isEmpty();

        Command<ApplicationControlDemo.Msg> sequence = demo().update(
                        model, new ApplicationControlDemo.Msg.StartSequence())
                .command();
        assertThat(sequence).isInstanceOf(Command.Sequence.class);
        assertThat(((Command.Sequence<?>) sequence).commands())
                .as("the demo's sequence has the shape its help describes")
                .hasSize(5);

        for (String step : List.of("a", "tick-a", "b", "b-done", "c")) {
            model = apply(model, new ApplicationControlDemo.Msg.Mark(step));
        }
        assertThat(row(model, ApplicationControlDemo.STATUS_ROW))
                .isEqualTo("progress=- ticks=0 sequence=done order=a,tick-a,b,b-done,c quit=guarded");
    }

    @Test
    void theDemoCountsProgressFromOutsideAndGuardsItsQuit() {
        ApplicationControlDemo.Guard guard = new ApplicationControlDemo.Guard();
        ApplicationControlDemo.Model model = start();

        model = apply(model, new ApplicationControlDemo.Msg.Progress(7));
        assertThat(row(model, ApplicationControlDemo.STATUS_ROW)).startsWith("progress=7 ticks=1 ");

        assertThat(guard.apply(new QuitRequest(0)))
                .as("an unconfirmed quit is vetoed")
                .isNull();
        assertThat(guard.confirmed()).isFalse();
        assertThat(guard.apply(new ApplicationControlDemo.Msg.ConfirmQuit()))
                .as("the confirmation is an ordinary message the filter still passes")
                .isNotNull();
        assertThat(guard.apply(new QuitRequest(0))).isInstanceOf(QuitRequest.class);

        model = apply(model, new ApplicationControlDemo.Msg.RequestQuit());
        assertThat(row(model, ApplicationControlDemo.STATUS_ROW)).endsWith("quit=confirm");
        assertThat(row(model, ApplicationControlDemo.PROMPT_ROW)).isEqualTo("prompt=confirm press y to quit");
        model = apply(model, new ApplicationControlDemo.Msg.CancelQuit());
        assertThat(row(model, ApplicationControlDemo.STATUS_ROW)).endsWith("quit=guarded");
    }

    @Test
    void theDemoPublishesAReusableSceneAndAMain() throws Exception {
        assertThat(ApplicationControlDemo.class.getMethod("main", String[].class))
                .isNotNull();
        assertThat(ApplicationControlDemo.class.getMethod("run")).isNotNull();
        assertThat(ApplicationControlDemo.class.getMethod("defaultKeys")).isNotNull();
        assertThat(Component.class.isAssignableFrom(ApplicationControlDemo.Scene.class))
                .as("the packaged PTY launch runs the same scene these cases drive")
                .isTrue();
        assertThat(ApplicationControlDemo.defaultKeys().bindings()).isNotEmpty();
        assertThat(ApplicationControlDemo.Model.start().progress()).isEqualTo(-1);
        assertThat(ApplicationControlDemo.describe(ExitResult.quit(0))).isEqualTo("QUIT/0");
        assertThat(ApplicationControlDemo.summary(ApplicationControlDemo.Model.start()))
                .isEqualTo("progress=- ticks=0 sequence=idle order=none");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * A sequence step that reports its start, waits for {@code gate}, and delivers its own name.
     */
    private static Command<String> gated(String name, CountDownLatch gate, AtomicInteger started) {
        return Command.task(
                EffectId.of(name),
                () -> {
                    started.incrementAndGet();
                    gate.await();
                    return name;
                },
                value -> value,
                failure -> "failed");
    }

    /**
     * A sequence step that completes at once and delivers its own name.
     */
    private static Command<String> named(String name) {
        return Command.task(EffectId.of(name), () -> name, value -> value, failure -> "failed");
    }

    /**
     * Takes every envelope the interpreter produced, running owner work as it goes.
     */
    private static List<String> pump(Mailbox mailbox) throws InterruptedException {
        List<String> messages = new ArrayList<>();
        Mailbox.Envelope envelope;
        while ((envelope = mailbox.poll(200L)) != null) {
            if (envelope instanceof Mailbox.AppMessage message) {
                messages.add((String) message.payload());
            } else if (envelope instanceof Mailbox.Resume resume) {
                resume.action().run();
            }
        }
        return messages;
    }

    /**
     * A clipboard service with no terminal to talk to.
     */
    private static ClipboardService clipboard() {
        return new ClipboardService(
                ClipboardConfig.defaults(), new TerminalEnvironment(Map.of("TERM", "dumb")), text -> {});
    }
}
