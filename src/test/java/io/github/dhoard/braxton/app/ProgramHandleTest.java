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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.runtime.Mailbox;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 22 acceptance for application control: the program handle.
 *
 * <p>{@link ProgramHandle} is the one public type that drives a run from outside, so these cases are
 * in its own package: they build the handle over an owned stream terminal rather than a process
 * terminal, which is the same runtime a real run uses. What is asserted is public behavior - a message
 * sent before the run opens is delivered, a full mailbox refuses without blocking, awaiting returns the
 * final model with the exit result, an await on the runtime's own thread fails instead of deadlocking,
 * a graceful quit can be vetoed and a forced one cannot, and both ends clean the terminal up.</p>
 */
@Timeout(120)
class ProgramHandleTest {

    private static final String SCENE = "handle";
    private static final long WAIT_SECONDS = 10L;

    /**
     * Real timers; a direct effect executor so a task body is the owner thread when that matters.
     */
    private static RuntimeServices services() {
        return RuntimeServices.system().withEffectExecutor(Runnable::run);
    }

    /**
     * A one-row scene that records every committed model.
     *
     * @param behavior the command each message returns, or null for none
     * @param snapshots where to record each committed model
     * @return the program
     */
    private static Component<List<String>, String, Never> scene(
            BiFunction<List<String>, String, Command<String>> behavior, List<List<String>> snapshots) {
        List<Binding<String>> bindings = List.of(new Binding<>(Key.of("q"), "q"));
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
                snapshots.add(List.copyOf(model));
                return Elements.text(SCENE + ":" + String.join(",", model));
            }

            /**
             * The gestures this fixture answers, once no leaf has taken them.
             */
            @Override
            public java.util.List<Binding<String>> claims(List<String> model) {
                return bindings;
            }
        };
    }

    /**
     * A handle over an owned stream terminal, with its runtime thread not yet started.
     */
    private static ProgramHandle<List<String>, String> handle(
            TestTerminal fixture,
            Component<List<String>, String, Never> program,
            RuntimeOptions options,
            int mailboxCapacity,
            RuntimeServices services) {
        Mailbox mailbox = new Mailbox(mailboxCapacity);
        return new ProgramHandle<>(new RuntimeLoop<>(program, options, fixture.terminal(), services, mailbox), mailbox);
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

    @Test
    void aMessageSentBeforeTheRunStartsIsDelivered() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program =
                scene((model, message) -> message.startsWith("quit") ? Command.quit() : null, snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults(), 8, services());

            assertThat(handle.send("early"))
                    .as("the mailbox exists before the run opens")
                    .isTrue();
            handle.start();

            awaitModel(snapshots, model -> model.contains("early"), "the message sent before the start");
            assertThat(handle.quit()).isTrue();
            ProgramResult<List<String>> result = handle.await();
            assertThat(result.model()).containsExactly("early");
            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.ok()).isTrue();
        }
    }

    @Test
    void sendDeliversAndReportsWhenTheRunHasEnded() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program =
                scene((model, message) -> message.equals("q") ? Command.quit(7) : null, snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults(), 8, services());
            handle.start();

            assertThat(handle.isRunning()).isTrue();
            assertThat(handle.trySend("q")).isEqualTo(ProgramHandle.Send.ACCEPTED);

            ProgramResult<List<String>> result = handle.await();
            assertThat(result.model())
                    .as("the final model is what the runtime committed last")
                    .containsExactly("q");
            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.exit().status()).isEqualTo(7);

            assertThat(handle.isRunning()).isFalse();
            assertThat(handle.trySend("late")).isEqualTo(ProgramHandle.Send.CLOSED);
            assertThat(handle.send("late"))
                    .as("a message for a finished run is refused, not queued forever")
                    .isFalse();
            assertThat(handle.quit()).isFalse();
            assertThat(handle.interrupt()).as("the run had already ended").isFalse();
            assertThat(handle.await().exit())
                    .as("awaiting twice reports the one stable result")
                    .isEqualTo(result.exit());
        }
    }

    @Test
    void trySendReportsFullWithoutBlocking() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program =
                scene((model, message) -> message.equals("quit") ? Command.quit() : null, snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults(), 1, services());

            assertThat(handle.trySend("one")).isEqualTo(ProgramHandle.Send.ACCEPTED);
            assertThat(handle.trySend("two"))
                    .as("a full mailbox refuses a non-blocking send")
                    .isEqualTo(ProgramHandle.Send.FULL);

            handle.start();
            awaitModel(snapshots, model -> model.contains("one"), "the queued message");
            assertThat(handle.quit()).isTrue();
            ProgramResult<List<String>> result = handle.await();
            assertThat(result.model())
                    .as("the refused message was never delivered")
                    .containsExactly("one");
        }
    }

    @Test
    void awaitFromTheRuntimeThreadFailsInsteadOfDeadlocking() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicReference<ProgramHandle<List<String>, String>> reference = new AtomicReference<>();
        Component<List<String>, String, Never> program = scene(
                (model, message) -> switch (message) {
                    case "probe" ->
                        Command.task(
                                EffectId.of("await"),
                                () -> {
                                    try {
                                        reference.get().await();
                                        return "returned";
                                    } catch (IllegalStateException refused) {
                                        return "refused";
                                    }
                                },
                                value -> value,
                                failure -> "failed");
                    case "refused" -> Command.quit();
                    default -> null;
                },
                snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults(), 8, services());
            reference.set(handle);
            handle.start();

            assertThat(handle.trySend("probe")).isEqualTo(ProgramHandle.Send.ACCEPTED);
            ProgramResult<List<String>> result = handle.await();
            assertThat(result.model())
                    .as("awaiting on the runtime's own thread is refused, so the task reports it")
                    .containsExactly("probe", "refused");
            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
        }
    }

    @Test
    void anInterruptEndsTheRunWithBoundedCleanupAndTheFilterCannotVetoIt() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        MessageFilter refusesEveryQuit = message -> message instanceof QuitRequest ? null : message;
        Component<List<String>, String, Never> program = scene((model, message) -> null, snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle = handle(
                    fixture, program, RuntimeOptions.defaults().withMessageFilter(refusesEveryQuit), 8, services());
            handle.start();
            awaitModel(snapshots, model -> true, "the first frame");

            assertThat(handle.quit()).as("the request was delivered").isTrue();
            Thread.sleep(150L);
            assertThat(handle.isRunning())
                    .as("the filter voided the graceful quit")
                    .isTrue();

            assertThat(handle.interrupt()).isTrue();
            ProgramResult<List<String>> result = handle.await();
            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.INTERRUPTED);
            assertThat(result.exit().status()).isEqualTo(130);
            assertThat(fixture.state())
                    .as("a forced end still cleans the terminal up")
                    .isEqualTo(Terminal.State.CLOSED);
        }
    }

    @Test
    void aGracefulQuitCanBeVetoedAndThenAccepted() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        AtomicInteger offers = new AtomicInteger();
        MessageFilter guard =
                message -> message instanceof QuitRequest && offers.incrementAndGet() == 1 ? null : message;
        Component<List<String>, String, Never> program = scene((model, message) -> null, snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults().withMessageFilter(guard), 8, services());
            handle.start();
            awaitModel(snapshots, model -> true, "the first frame");

            assertThat(handle.quit()).isTrue();
            Thread.sleep(150L);
            assertThat(handle.isRunning()).isTrue();
            assertThat(offers).as("the guard was offered the request").hasValue(1);

            assertThat(handle.quit()).isTrue();
            ProgramResult<List<String>> result = handle.await();
            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(offers).as("the second request was accepted").hasValue(2);
        }
    }

    @Test
    void sendingWhileTheRunIsBeingAskedToQuitNeitherFailsNorDuplicates() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program = scene((model, message) -> null, snapshots);
        AtomicInteger accepted = new AtomicInteger();
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults(), 64, services());
            handle.start();
            awaitModel(snapshots, model -> true, "the first frame");

            List<Thread> producers = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                String prefix = "p" + producer + "-";
                producers.add(Thread.ofVirtual().start(() -> {
                    for (int index = 0; index < 50; index++) {
                        if (handle.send(prefix + index)) {
                            accepted.incrementAndGet();
                        }
                    }
                }));
            }
            // The quit races the producers on purpose: every send is either accepted or refused,
            // and cleanup closes the mailbox under both.
            handle.quit();
            for (Thread producer : producers) {
                producer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            }
            ProgramResult<List<String>> result = handle.await();

            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
            List<String> delivered = result.model();
            assertThat(delivered).as("no message was delivered twice").doesNotHaveDuplicates();
            assertThat(accepted.get())
                    .as("a message the mailbox accepted is delivered unless the run ended first")
                    .isGreaterThanOrEqualTo(delivered.size());
            assertThat(handle.send("late")).isFalse();
        }
    }

    @Test
    void awaitWithATimeoutReportsWhetherTheRunHasEnded() throws Exception {
        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program = scene((model, message) -> null, snapshots);
        try (TestTerminal fixture = new TestTerminal()) {
            ProgramHandle<List<String>, String> handle =
                    handle(fixture, program, RuntimeOptions.defaults(), 8, services());
            handle.start();
            awaitModel(snapshots, model -> true, "the first frame");

            assertThat(handle.await(Duration.ofMillis(50L)))
                    .as("a run that is still going has no result yet")
                    .isEmpty();
            handle.interrupt();
            assertThat(handle.await(Duration.ofSeconds(WAIT_SECONDS)))
                    .isPresent()
                    .get()
                    .extracting(result -> result.exit().kind())
                    .isEqualTo(ExitResult.Kind.INTERRUPTED);
            assertThatThrownBy(() -> handle.await(Duration.ofMillis(-1L))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
