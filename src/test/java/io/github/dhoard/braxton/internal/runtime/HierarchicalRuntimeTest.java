/*
 * Copyright (c) 2026-present Doug Hoard
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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.app.*;
import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.internal.clipboard.*;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class HierarchicalRuntimeTest {
    private static ComponentPath path(String key) {
        return ComponentPath.ROOT.append(ComponentId.of(key));
    }

    private static ComponentTree tree(boolean a) {
        var b = new ComponentTree.Node(ComponentId.of("b"), ComponentTree.empty());
        return a
                ? ComponentTree.of(
                        new ComponentTree.Node(ComponentId.of("a"), ComponentTree.empty()), b)
                : ComponentTree.of(b);
    }

    private abstract static class Definition<M> implements Component<M, String, Never> {
        public View<String> view(M model, ViewContext context) {
            return Views.text("ready");
        }
    }

    @Test
    void localDispatchIsFifoNonreentrantAndDrainsBeforeExternalEnvelopes() {
        var seen = new ArrayList<String>();
        var updating = new AtomicBoolean();
        var component =
                new Definition<Integer>() {
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(
                                0, Command.batch(Command.dispatch("a"), Command.dispatch("b")));
                    }

                    public Update<Integer, String, Never> update(Integer model, String message) {
                        assertThat(updating.getAndSet(true)).isFalse();
                        seen.add(message);
                        try {
                            return switch (message) {
                                case "a" -> Update.next(model + 1, Command.dispatch("c"));
                                case "b" -> Update.next(model + 1);
                                case "c" -> Update.next(model + 1, Command.dispatch("done"));
                                default -> Update.next(model, Command.quit());
                            };
                        } finally {
                            updating.set(false);
                        }
                    }
                };
        try (var terminal = new JLineTestTerminal()) {
            var result =
                    JLineTestTerminal.run(
                            component,
                            RuntimeOptions.defaults().withComponentLimits(2, 2, 4),
                            terminal);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(seen).containsExactly("a", "b", "c", "done");
            assertThat(terminal.isOpen()).isFalse();
        }
    }

    @Test
    void dispatchBudgetFailsRatherThanStarvingAndQuitDiscardsAlreadyQueuedMessages() {
        for (boolean quit : List.of(false, true)) {
            var seen = new ArrayList<String>();
            var component =
                    new Definition<Integer>() {
                        public Update<Integer, String, Never> init(InitContext context) {
                            return Update.next(
                                    0,
                                    quit
                                            ? Command.batch(
                                                    Command.dispatch("discarded"),
                                                    Command.quit(),
                                                    Command.dispatch("also discarded"))
                                            : Command.dispatch("cycle"));
                        }

                        public Update<Integer, String, Never> update(
                                Integer model, String message) {
                            seen.add(message);
                            return Update.next(model, Command.dispatch(message));
                        }
                    };
            try (var terminal = new JLineTestTerminal()) {
                var result =
                        JLineTestTerminal.run(
                                component,
                                RuntimeOptions.defaults().withComponentLimits(1, 1, 3),
                                terminal);
                if (quit) {
                    assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
                    assertThat(seen).isEmpty();
                } else {
                    assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
                    assertThat(result.failure()).hasMessageContaining("local dispatch limit");
                    assertThat(seen).hasSize(3);
                }
                assertThat(terminal.isOpen()).isFalse();
            }
        }
    }

    @Test
    void removedAndReinsertedScopeDropsQueuedTaskAndSourceMessagesAndClosesOnce() {
        var starts = new AtomicInteger();
        var closes = new AtomicInteger();
        var seen = new ArrayList<String>();
        var component =
                new Definition<Boolean>() {
                    public Update<Boolean, String, Never> init(InitContext context) {
                        return Update.next(
                                true,
                                Command.batch(
                                        Command.<String, String>task(
                                                        EffectId.of("load"),
                                                        () -> "old-task",
                                                        value -> value,
                                                        error -> "failed")
                                                .scoped(path("a")),
                                        Command.dispatch("remove"),
                                        Command.dispatch("insert")));
                    }

                    public Update<Boolean, String, Never> update(Boolean active, String message) {
                        seen.add(message);
                        return switch (message) {
                            case "remove" -> Update.next(false);
                            case "insert" -> Update.next(true);
                            case "done" -> Update.next(active, Command.quit());
                            default ->
                                    throw new AssertionError("stale child message was delivered");
                        };
                    }

                    public ComponentTree children(Boolean active) {
                        return tree(active);
                    }

                    public Subscription<String> subscriptions(Boolean active) {
                        var a =
                                active
                                        ? Subscription.<String>source(
                                                        SubscriptionId.of("source"),
                                                        "same",
                                                        emit -> {
                                                            int number = starts.incrementAndGet();
                                                            emit.accept("old-source-" + number);
                                                            return () -> {
                                                                closes.incrementAndGet();
                                                                emit.accept("close-emission");
                                                            };
                                                        },
                                                        value -> (String) value)
                                                .scoped(path("a"))
                                        : Subscription.<String>none();
                        var b =
                                Subscription.<String>source(
                                                SubscriptionId.of("source"),
                                                "same",
                                                emit -> {
                                                    starts.incrementAndGet();
                                                    emit.accept("done");
                                                    return closes::incrementAndGet;
                                                },
                                                value -> (String) value)
                                        .scoped(path("b"));
                        return Subscription.batch(a, b);
                    }
                };
        try (var terminal = new JLineTestTerminal()) {
            var result = JLineTestTerminal.run(component, RuntimeOptions.defaults(), terminal);
            assertThat(result.failure()).isNull();
            assertThat(seen).containsExactly("remove", "insert", "done");
            assertThat(starts).hasValue(3);
            assertThat(closes).hasValue(3);
        }
    }

    @Test
    void duplicateTreesAndSubscriptionConstructionFailBeforeActivatingResources() {
        for (boolean duplicate : List.of(true, false)) {
            var starts = new AtomicInteger();
            var component =
                    new Definition<Integer>() {
                        public Update<Integer, String, Never> init(InitContext context) {
                            return Update.next(
                                    0,
                                    Command.task(
                                            EffectId.of("work"),
                                            starts::incrementAndGet,
                                            Object::toString,
                                            Throwable::toString));
                        }

                        public Update<Integer, String, Never> update(
                                Integer model, String message) {
                            return Update.next(model);
                        }

                        public ComponentTree children(Integer model) {
                            var node =
                                    new ComponentTree.Node(
                                            ComponentId.of("a"), ComponentTree.empty());
                            return duplicate ? ComponentTree.of(node, node) : ComponentTree.empty();
                        }

                        public Subscription<String> subscriptions(Integer model) {
                            throw new IllegalArgumentException("source configuration");
                        }
                    };
            try (var terminal = new JLineTestTerminal()) {
                var result = JLineTestTerminal.run(component, RuntimeOptions.defaults(), terminal);
                assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
                assertThat(result.failure())
                        .isInstanceOfSatisfying(
                                ComponentFailure.class,
                                failure ->
                                        assertThat(failure.phase())
                                                .isEqualTo(
                                                        duplicate
                                                                ? ComponentFailure.Phase.CHILDREN
                                                                : ComponentFailure.Phase
                                                                        .SUBSCRIPTIONS));
                assertThat(starts).hasValue(0);
                assertThat(terminal.isOpen()).isFalse();
            }
        }
    }

    @Test
    void queuedResultsAlsoHonorIndividualCancellationAndSourceLifetimes() throws Exception {
        var mailbox = new Mailbox(16);
        var timers = new ManualTimers();
        var services = new RuntimeServices(() -> 0L, Runnable::run, timers, ignored -> {});
        var clipboard =
                new ClipboardService(
                        ClipboardConfig.defaults(),
                        new TerminalEnvironment(Map.of("TERM", "dumb")),
                        ignored -> {});
        var runner = new EffectRunner<String>(mailbox, services, clipboard, 4);
        var id = EffectId.of("load");
        runner.interpret(Command.task(id, () -> "old", value -> value, Throwable::toString));
        var old = (Mailbox.AppMessage) mailbox.poll(1);
        runner.interpret(Command.cancel(id));
        assertThat(old.valid().getAsBoolean()).isFalse();
        runner.interpret(Command.task(id, () -> "replaced", value -> value, Throwable::toString));
        var replaced = (Mailbox.AppMessage) mailbox.poll(1);
        runner.interpret(Command.task(id, () -> "new", value -> value, Throwable::toString));
        var fresh = (Mailbox.AppMessage) mailbox.poll(1);
        assertThat(replaced.valid().getAsBoolean()).isFalse();
        assertThat(fresh.valid().getAsBoolean()).isTrue();
        var sources = new SubscriptionManager<String>(mailbox, timers, 4, ignored -> {});
        sources.reconcile(
                Subscription.source(
                        SubscriptionId.of("source"),
                        "config",
                        emit -> {
                            emit.accept("queued");
                            return () -> {};
                        },
                        Object::toString));
        var source = (Mailbox.AppMessage) mailbox.poll(1);
        sources.reconcile(Subscription.none());
        assertThat(source.valid().getAsBoolean()).isFalse();
        sources.reconcile(
                Subscription.interval(
                        SubscriptionId.of("tick"), Duration.ofSeconds(1), time -> "tick"));
        timers.advance(1_000_000_000L);
        var tick = (Mailbox.AppMessage) mailbox.poll(1);
        sources.closeAll();
        assertThat(tick.valid().getAsBoolean()).isFalse();
        runner.cancelAll();
    }

    @Test
    void undeclaredOrOverQuotaSubscriptionsFailBeforeTheyStart() {
        for (boolean undeclared : List.of(true, false)) {
            var starts = new AtomicInteger();
            var component =
                    new Definition<Integer>() {
                        public Update<Integer, String, Never> init(InitContext context) {
                            return Update.next(0);
                        }

                        public Update<Integer, String, Never> update(
                                Integer model, String message) {
                            return Update.next(model);
                        }

                        public Subscription<String> subscriptions(Integer model) {
                            var source =
                                    Subscription.<String>source(
                                            SubscriptionId.of("source"),
                                            "config",
                                            emit -> {
                                                starts.incrementAndGet();
                                                return () -> {};
                                            },
                                            Object::toString);
                            return undeclared ? source.scoped(path("missing")) : source;
                        }
                    };
            try (var terminal = new JLineTestTerminal()) {
                var options = RuntimeOptions.defaults().withLimits(16, 4, undeclared ? 4 : 0);
                var result = JLineTestTerminal.run(component, options, terminal);
                assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
                assertThat(result.failure())
                        .hasMessageContaining(undeclared ? "undeclared" : "too many subscriptions");
                assertThat(starts).hasValue(0);
                assertThat(terminal.isOpen()).isFalse();
            }
        }
    }

    @Test
    void siblingEffectIdsCancelIndependentlyAndCompletedMessagesKeepTheirOriginEpoch()
            throws Exception {
        var scopes = new ScopeManager();
        scopes.commit(scopes.prepare(tree(true), 3, 1));
        var mailbox = new Mailbox(16);
        var timers = new ManualTimers();
        var services = new RuntimeServices(() -> 0L, Runnable::run, timers, ignored -> {});
        var clipboard =
                new ClipboardService(
                        ClipboardConfig.defaults(),
                        new TerminalEnvironment(Map.of("TERM", "dumb")),
                        ignored -> {});
        var runner = new EffectRunner<String>(mailbox, services, clipboard, 4);
        runner.bindScopes(scopes, mailbox::enqueueFromOwner);
        runner.interpret(
                Command.delay(EffectId.of("load"), Duration.ofSeconds(1), "a", "r")
                        .scoped(path("a")));
        runner.interpret(
                Command.delay(EffectId.of("load"), Duration.ofSeconds(1), "b", "r")
                        .scoped(path("b")));
        runner.interpret(Command.<String>cancelAll().scoped(path("a")));
        timers.advance(2_000_000_000L);
        var b = (Mailbox.AppMessage) mailbox.poll(1);
        assertThat(b.payload()).isEqualTo("b");
        assertThat(scopes.isCurrent(b.origin())).isTrue();
        runner.interpret(
                Command.<String, String>task(
                                EffectId.of("load"), () -> "a", value -> value, Throwable::toString)
                        .scoped(path("a")));
        var old = (Mailbox.AppMessage) mailbox.poll(1);
        scopes.commit(scopes.prepare(tree(false), 3, 1));
        runner.cancelScope(path("a"));
        scopes.commit(scopes.prepare(tree(true), 3, 1));
        assertThat(scopes.isCurrent(old.origin())).isFalse();
        assertThat(scopes.isCurrent(b.origin())).isTrue();
    }

    @Test
    void commandsForAScopeRemovedByTheirOwnCommitAreDiscardedInsteadOfFailingTheRun() {
        var diagnostics = new ArrayList<String>();
        var seen = new ArrayList<String>();
        var ran = new AtomicInteger();
        var component =
                new Definition<Boolean>() {
                    public Update<Boolean, String, Never> init(InitContext context) {
                        return Update.next(true, Command.dispatch("remove"));
                    }

                    public Update<Boolean, String, Never> update(Boolean present, String message) {
                        seen.add(message);
                        return switch (message) {
                            case "remove" ->
                                    Update.next(
                                            false,
                                            Command.<String>batch(
                                                    Command.<String, Object>task(
                                                                    EffectId.of("load"),
                                                                    ran::incrementAndGet,
                                                                    Object::toString,
                                                                    Throwable::toString)
                                                            .scoped(path("a")),
                                                    Command.dispatch("child-local")
                                                            .scoped(path("a")),
                                                    Command.dispatch("parent-local")));
                            case "parent-local" -> Update.next(present, Command.quit());
                            default ->
                                    throw new AssertionError(
                                            "removed component message delivered: " + message);
                        };
                    }

                    public ComponentTree children(Boolean present) {
                        return present
                                ? ComponentTree.of(
                                        new ComponentTree.Node(
                                                ComponentId.of("a"), ComponentTree.empty()))
                                : ComponentTree.empty();
                    }
                };
        var services =
                RuntimeServices.system()
                        .withTimers(new ManualTimers())
                        .withEffectExecutor(Runnable::run)
                        .withDiagnostics(diagnostics::add);
        try (var terminal = new JLineTestTerminal()) {
            var result =
                    JLineTestTerminal.run(component, RuntimeOptions.defaults(), terminal, services);
            assertThat(result.failure()).isNull();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            // The removed component's effect never runs and its own message never arrives; the
            // parent's own command in the same batch still does.
            assertThat(ran).hasValue(0);
            assertThat(seen).containsExactly("remove", "parent-local");
            assertThat(diagnostics).anyMatch(text -> text.contains("discarded command")
                    && text.contains("/a"));
        }
    }

    @Test
    void commandsForANeverDeclaredScopeStillFailWithTheStructuralReason() {
        var component =
                new Definition<Integer>() {
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(
                                0,
                                Command.<String, String>task(
                                                EffectId.of("load"),
                                                () -> "never",
                                                value -> value,
                                                Throwable::toString)
                                        .scoped(path("ghost")));
                    }

                    public Update<Integer, String, Never> update(
                            Integer model, String message) {
                        return Update.next(model, Command.quit());
                    }
                };
        try (var terminal = new JLineTestTerminal()) {
            var result = JLineTestTerminal.run(component, RuntimeOptions.defaults(), terminal);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure())
                    .isInstanceOfSatisfying(
                            ComponentFailure.class,
                            failure -> {
                                assertThat(failure.path()).isEqualTo(path("ghost"));
                                assertThat(failure.detail())
                                        .isEqualTo("command/source belongs to undeclared component");
                                assertThat(failure.getMessage())
                                        .contains("undeclared component");
                            });
        }
    }

    private record OutputModel(boolean composerPresent, Integer counter) {}

    private sealed interface OutputMsg {
        record Typed(Integer value) implements OutputMsg {}

        record Routed(String value) implements OutputMsg {}
    }

    @Test
    void aChildOutputRoutedToAParentMessageSurvivesUnmountingThatChildInTheSameUpdate() {
        var seen = new ArrayList<String>();
        var child =
                new Component<Integer, Integer, String>() {
                    public Update<Integer, Integer, String> init(InitContext context) {
                        return Update.next(0);
                    }

                    public Update<Integer, Integer, String> update(Integer model, Integer message) {
                        return Update.emit(model + message, "submitted:" + (model + message));
                    }

                    public View<Integer> view(Integer model, ViewContext context) {
                        return Views.text("" + model);
                    }
                };
        var mount =
                Mount.<OutputModel, OutputMsg, Never, Integer, Integer, String>of(
                        ComponentId.of("composer"),
                        child,
                        ModelLens.of(
                                OutputModel::counter,
                                (m, c) -> new OutputModel(m.composerPresent(), c)),
                        OutputMsg.Typed::new,
                        OutputRoute.toMessage(OutputMsg.Routed::new));
        var parent =
                new Component<OutputModel, OutputMsg, Never>() {
                    public Update<OutputModel, OutputMsg, Never> init(InitContext context) {
                        var initialized = mount.initialize(context);
                        return Update.next(
                                new OutputModel(true, initialized.model()),
                                Command.batch(
                                        initialized.command(),
                                        Command.dispatch(new OutputMsg.Typed(7))));
                    }

                    public Update<OutputModel, OutputMsg, Never> update(
                            OutputModel model, OutputMsg message) {
                        return switch (message) {
                            case OutputMsg.Typed typed -> {
                                var result = mount.update(model, typed.value());
                                // The same update that takes the child's output unmounts the child.
                                yield Update.<OutputModel, OutputMsg, Never>next(
                                        new OutputModel(false, result.model().counter()),
                                        result.command());
                            }
                            case OutputMsg.Routed routed -> {
                                seen.add(routed.value());
                                yield Update.<OutputModel, OutputMsg, Never>next(
                                        model, Command.quit());
                            }
                        };
                    }

                    public ComponentTree children(OutputModel model) {
                        return model.composerPresent()
                                ? ComponentTree.of(mount.tree(model))
                                : ComponentTree.empty();
                    }

                    public View<OutputMsg> view(OutputModel model, ViewContext context) {
                        return Views.text("ok");
                    }
                };
        try (var terminal = new JLineTestTerminal()) {
            var result = JLineTestTerminal.run(parent, RuntimeOptions.defaults(), terminal);
            assertThat(result.failure()).isNull();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(seen).containsExactly("submitted:7");
        }
    }

    @Test
    void everyScopedCommandRejectsAnUndeclaredOriginBeforeDoingWork() {
        var calls = new AtomicInteger();
        var commands = List.<Command<String>>of(
                Command.cancel(EffectId.of("load")), Command.cancelAll(), Command.quit(),
                Command.copy("", result -> { calls.incrementAndGet(); return "copy"; }),
                Command.readClipboard(text -> { calls.incrementAndGet(); return "read"; }),
                Command.task(EffectId.of("load"), calls::incrementAndGet,
                        Object::toString, Throwable::toString),
                Command.delay(EffectId.of("delay"), Duration.ZERO, "fired", "rejected"),
                Command.dispatch("local"));
        for (var command : commands) {
            var component = new Definition<Integer>() {
                public Update<Integer, String, Never> init(InitContext context) {
                    return Update.next(0, Command.batch(command.scoped(path("ghost")), Command.quit()));
                }
                public Update<Integer, String, Never> update(Integer model, String message) {
                    throw new AssertionError("undeclared message delivered");
                }
            };
            try (var terminal = new JLineTestTerminal()) {
                var result = JLineTestTerminal.run(component, RuntimeOptions.defaults(), terminal);
                assertThat(result.kind()).as(command.getClass().getSimpleName())
                        .isEqualTo(ExitResult.Kind.FAILURE);
                assertThat(result.failure()).isInstanceOfSatisfying(ComponentFailure.class,
                        failure -> {
                            assertThat(failure.path()).isEqualTo(path("ghost"));
                            assertThat(failure.detail()).contains("undeclared component");
                        });
                assertThat(calls).hasValue(0);
            }
        }
    }

    @Test
    void invalidSubscriptionDeclarationsKeepTheCommittedModelAndReportTheirPhaseAndPath() {
        for (String kind : List.of("duplicate", "quota", "undeclared")) {
            var starts = new AtomicInteger();
            var closes = new AtomicInteger();
            var component = new Definition<Integer>() {
                public Update<Integer, String, Never> init(InitContext context) {
                    return Update.next(0, Command.dispatch("invalid"));
                }
                public Update<Integer, String, Never> update(Integer model, String message) {
                    return Update.next(1);
                }
                public ComponentTree children(Integer model) {
                    return tree(model == 0);
                }
                public Subscription<String> subscriptions(Integer model) {
                    var source = Subscription.<String>source(SubscriptionId.of("source"), "config",
                            emit -> { starts.incrementAndGet(); return closes::incrementAndGet; },
                            Object::toString);
                    if (model == 0) return source.scoped(path("a"));
                    return switch (kind) {
                        case "duplicate" -> Subscription.batch(source, source);
                        case "quota" -> Subscription.batch(source,
                                Subscription.resize(SubscriptionId.of("size"), Object::toString));
                        default -> source.scoped(path("missing"));
                    };
                }
            };
            try (var terminal = new JLineTestTerminal()) {
                var runtime = new RuntimeLoop<>(component,
                        RuntimeOptions.defaults().withLimits(16, 4, 1), terminal.terminal(),
                        RuntimeServices.system().withTimers(new ManualTimers()));
                var result = runtime.run();
                assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
                assertThat(runtime.model()).isZero();
                assertThat(starts).hasValue(1);
                assertThat(closes).hasValue(1);
                assertThat(result.failure()).isInstanceOfSatisfying(ComponentFailure.class,
                        failure -> {
                            assertThat(failure.phase()).isEqualTo(ComponentFailure.Phase.SUBSCRIPTIONS);
                            assertThat(failure.path()).isEqualTo(kind.equals("undeclared")
                                    ? path("missing") : ComponentPath.ROOT);
                            assertThat(failure.getMessage()).contains(switch (kind) {
                                case "duplicate" -> "duplicate subscription id";
                                case "quota" -> "too many subscriptions";
                                default -> "undeclared component";
                            });
                        });
                assertThat(terminal.isOpen()).isFalse();
            }
        }
    }
}
