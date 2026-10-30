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

package io.github.dhoard.braxton.mvu;

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class MountTest {
    record Parent(Integer child, Object sibling) {}

    record Child(ComponentFailure.Phase failure) implements Component<Integer, Integer, Integer> {
        private void check(ComponentFailure.Phase phase) {
            if (failure == phase) throw new IllegalStateException("private payload");
        }

        public Update<Integer, Integer, Integer> init(InitContext context) {
            check(ComponentFailure.Phase.INIT);
            return Update.emit(1, 1);
        }

        public Update<Integer, Integer, Integer> update(Integer model, Integer message) {
            check(ComponentFailure.Phase.UPDATE);
            return new Update<>(
                    model + message,
                    Command.delay(EffectId.of("load"), Duration.ZERO, message, -1),
                    List.of(model + message));
        }

        public View<Integer> view(Integer model, ViewContext context) {
            check(ComponentFailure.Phase.VIEW);
            return Views.focus(
                    ElementId.of("input"),
                    true,
                    List.of(),
                    View.InputHandler.none(),
                    Views.text("" + model));
        }

        public Subscription<Integer> subscriptions(Integer model) {
            check(ComponentFailure.Phase.SUBSCRIPTIONS);
            return Subscription.interval(
                    SubscriptionId.of("tick"), Duration.ofSeconds(1), time -> model);
        }

        public ComponentTree children(Integer model) {
            check(ComponentFailure.Phase.CHILDREN);
            return ComponentTree.empty();
        }
    }

    private static Mount<Parent, String, String, Integer, Integer, Integer> mount(
            String id, ComponentFailure.Phase failure, OutputRoute<Integer, String, String> route) {
        return Mount.of(
                ComponentId.of(id),
                new Child(failure),
                ModelLens.of(Parent::child, (p, c) -> new Parent(c, p.sibling())),
                Object::toString,
                route);
    }

    @Test
    void childReplacementPreservesSiblingAndRoutesMessagesAfterItsCommand() {
        var mount = mount("left", null, OutputRoute.toMessage(value -> "changed:" + value));
        Object sibling = new Object();
        var result = mount.update(new Parent(2, sibling), 3);
        assertThat(result.model().child()).isEqualTo(5);
        assertThat(result.model().sibling()).isSameAs(sibling);
        assertThat(result.outputs()).isEmpty();
        var commands = ((Command.Batch<String>) result.command()).commands();
        assertThat(commands.get(0)).isInstanceOf(Command.Scoped.class);
        var scoped = (Command.Scoped<String>) commands.get(0);
        assertThat(scoped.path()).isEqualTo(ComponentPath.ROOT.append(ComponentId.of("left")));
        assertThat(((Command.Delay<String>) scoped.command()).message()).isEqualTo("3");
        assertThat(commands.get(1)).isEqualTo(Command.dispatch("changed:5"));
        var initialized = mount.initialize(new InitContext(null, null));
        assertThat(initialized.model()).isEqualTo(1);
        assertThat(initialized.command()).isEqualTo(Command.dispatch("changed:1"));
    }

    @Test
    void upwardOutputsDoNotInvokeParentCodeAndSiblingIdsDoNotCollide() {
        var left = mount("left", null, OutputRoute.toOutput(Object::toString));
        var right = mount("right", null, OutputRoute.toOutput(Object::toString));
        var model = new Parent(2, new Object());
        assertThat(left.update(model, 1).outputs()).containsExactly("3");
        var context = new ViewContext(new Viewport(2, 10));
        var renderer = new ScreenRenderer();
        var regions =
                renderer.regions(
                        Views.column(left.view(model, context), right.view(model, context)),
                        context.viewport());
        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).id()).isNotEqualTo(regions.get(1).id());
        assertThat(
                        Subscription.leaves(
                                Subscription.batch(
                                        left.subscriptions(model), right.subscriptions(model))))
                .hasSize(2);
        assertThat(left.tree(model).id()).isEqualTo(ComponentId.of("left"));
    }

    @Test
    void everyChildPhaseAndOutputRouteFailureHasOnlyStructuralDiagnostics() {
        var parent = new Parent(1, new Object());
        var context = new ViewContext(Viewport.EMPTY);
        for (var phase : ComponentFailure.Phase.values()) {
            var child =
                    mount(
                            "child",
                            phase,
                            output -> {
                                throw new IllegalStateException("private payload");
                            });
            Runnable action =
                    switch (phase) {
                        case INIT -> () -> child.initialize(new InitContext(null, null));
                        case UPDATE -> () -> child.update(parent, 1);
                        case VIEW -> () -> child.view(parent, context);
                        case SUBSCRIPTIONS -> () -> child.subscriptions(parent);
                        case CHILDREN -> () -> child.tree(parent).children();
                        case OUTPUT_ROUTE -> () -> child.initialize(new InitContext(null, null));
                    };
            assertThatThrownBy(action::run)
                    .isInstanceOfSatisfying(
                            ComponentFailure.class,
                            failure -> {
                                assertThat(failure.phase()).isEqualTo(phase);
                                assertThat(failure.path().toString()).isEqualTo("/child");
                                assertThat(failure.getMessage()).doesNotContain("private payload");
                                assertThat(failure.getCause()).hasMessage("private payload");
                            });
        }
    }

    @Test
    void structuralReasonsAndFatalErrorsKeepTheirContract() {
        var child = ComponentPath.ROOT.append(ComponentId.of("child"));
        var rejected =
                new ComponentFailure(
                        child,
                        ComponentFailure.Phase.CHILDREN,
                        "component depth limit exceeded",
                        new IllegalArgumentException("component depth limit exceeded"));
        assertThatThrownBy(
                        () ->
                                ComponentFailure.call(
                                        ComponentPath.ROOT,
                                        ComponentFailure.Phase.CHILDREN,
                                        () -> {
                                            throw rejected;
                                        }))
                .isInstanceOfSatisfying(
                        ComponentFailure.class,
                        failure -> {
                            assertThat(failure.path()).isEqualTo(child);
                            assertThat(failure.detail()).isEqualTo("component depth limit exceeded");
                            assertThat(failure.getMessage())
                                    .isEqualTo(
                                            "component /child failed during CHILDREN:"
                                                    + " component depth limit exceeded");
                        });
        // A thrown application failure carries no structural reason, only path, phase and cause.
        assertThatThrownBy(
                        () ->
                                ComponentFailure.call(
                                        child,
                                        ComponentFailure.Phase.UPDATE,
                                        () -> {
                                            throw new IllegalStateException("app failure");
                                        }))
                .isInstanceOfSatisfying(
                        ComponentFailure.class,
                        failure -> {
                            assertThat(failure.detail()).isNull();
                            assertThat(failure.getMessage())
                                    .isEqualTo("component /child failed during UPDATE");
                        });
        // Errors are fatal JVM conditions: they stay unwrapped so their type is preserved.
        var fatal = new StackOverflowError("fatal");
        assertThatThrownBy(
                        () ->
                                ComponentFailure.call(
                                        child,
                                        ComponentFailure.Phase.UPDATE,
                                        () -> {
                                            throw fatal;
                                        }))
                .isSameAs(fatal);
    }

    @Test
    void nestedDiagnosticsAndMountsAreTypedWithoutConsumerCasts() {
        Component<Parent, String, String> middle =
                new Component<>() {
                    final Mount<Parent, String, String, Integer, Integer, Integer> inner =
                            mount(
                                    "inner",
                                    ComponentFailure.Phase.UPDATE,
                                    OutputRoute.toOutput(Object::toString));

                    public Update<Parent, String, String> init(InitContext c) {
                        return Update.next(new Parent(1, new Object()));
                    }

                    public Update<Parent, String, String> update(Parent m, String msg) {
                        return inner.update(m, 1);
                    }

                    public View<String> view(Parent m, ViewContext c) {
                        return inner.view(m, c);
                    }
                };
        record Root(Parent middle) {}
        var outer =
                Mount.<Root, String, String, Parent, String, String>of(
                        ComponentId.of("outer"),
                        middle,
                        ModelLens.of(Root::middle, (r, c) -> new Root(c)),
                        value -> value,
                        OutputRoute.toOutput(value -> value));
        assertThatThrownBy(() -> outer.update(new Root(new Parent(1, new Object())), "go"))
                .isInstanceOfSatisfying(
                        ComponentFailure.class,
                        failure -> assertThat(failure.path().toString()).isEqualTo("/outer/inner"));
    }

    @Test
    void threeLevelTaskResultsMapOncePerBoundaryAndRemainInertUntilCompletion() throws Exception {
        var work = new AtomicInteger();
        var mappings = new AtomicInteger();
        Component<Integer, Integer, Never> leaf =
                new Component<>() {
                    public Update<Integer, Integer, Never> init(InitContext context) {
                        return Update.next(0);
                    }

                    public Update<Integer, Integer, Never> update(Integer model, Integer message) {
                        return Update.next(
                                model + message,
                                Command.task(
                                        EffectId.of("load"),
                                        () -> {
                                            work.incrementAndGet();
                                            return message;
                                        },
                                        value -> value,
                                        failure -> -1));
                    }

                    public View<Integer> view(Integer model, ViewContext context) {
                        return Views.blank();
                    }
                };
        var inner =
                Mount.<Parent, String, Never, Integer, Integer, Never>of(
                        ComponentId.of("inner"),
                        leaf,
                        ModelLens.of(Parent::child, (p, c) -> new Parent(c, p.sibling())),
                        value -> {
                            mappings.incrementAndGet();
                            return "middle:" + value;
                        },
                        OutputRoute.toMessage(Object::toString));
        Component<Parent, String, Never> middle =
                new Component<>() {
                    public Update<Parent, String, Never> init(InitContext context) {
                        return Update.next(new Parent(0, new Object()));
                    }

                    public Update<Parent, String, Never> update(Parent model, String message) {
                        return inner.update(model, Integer.parseInt(message));
                    }

                    public View<String> view(Parent model, ViewContext context) {
                        return inner.view(model, context);
                    }

                    public ComponentTree children(Parent model) {
                        return ComponentTree.of(inner.tree(model));
                    }
                };
        record Root(Parent middle) {}
        var outer =
                Mount.<Root, String, Never, Parent, String, Never>of(
                        ComponentId.of("outer"),
                        middle,
                        ModelLens.of(Root::middle, (r, c) -> new Root(c)),
                        value -> {
                            mappings.incrementAndGet();
                            return "outer:" + value;
                        },
                        OutputRoute.toMessage(Object::toString));
        var root = new Root(outer.initialize(new InitContext(null, null)).model());
        var result = outer.update(root, "7");
        assertThat(result.model().middle().child()).isEqualTo(7);
        assertThat(work).hasValue(0);
        assertThat(mappings).hasValue(0);
        var paths = new java.util.ArrayList<ComponentPath>();
        Command<String> command = result.command();
        while (command instanceof Command.Scoped<String> scoped) {
            paths.add(scoped.path());
            command = scoped.command();
        }
        assertThat(paths.stream().map(ComponentPath::toString)).containsExactly("/outer", "/inner");
        switch (command) {
            case Command.Task<String> task -> {
                assertThat(task.onSuccess().apply(task.work().call())).isEqualTo("outer:middle:7");
                assertThat(work).hasValue(1);
                assertThat(mappings).hasValue(2);
            }
            default -> throw new AssertionError("expected a scoped inert task");
        }
    }

    @Test
    void driverKeepsEffectsInertAndUsesProductionWidgetViews() {
        var editor = new Editor(Editor.Config.defaults());
        var driver = ComponentDriver.initialize(editor, new InitContext(null, null));
        assertThat(driver.model().text()).isEmpty();
        driver.send(new Editor.Msg.Insert("hello"));
        assertThat(driver.model().text()).isEqualTo("hello");
        driver.send(new Editor.Msg.Submit());
        assertThat(driver.latest().outputs()).containsExactly(new Editor.Output.Submit("hello"));
        assertThat(driver.screen(new ViewContext(new Viewport(1, 20))).plainLine(0))
                .contains("hello");
        assertThat(driver.subscriptions()).isInstanceOf(Subscription.None.class);
        assertThat(driver.children().nodes()).isEmpty();
        assertThatThrownBy(() -> driver.send(null)).isInstanceOf(NullPointerException.class);
        var calls = new AtomicInteger();
        Component<Integer, String, Never> inert =
                new Component<>() {
                    public Update<Integer, String, Never> init(InitContext context) {
                        return Update.next(
                                0,
                                Command.task(
                                        EffectId.of("work"),
                                        calls::incrementAndGet,
                                        Object::toString,
                                        Throwable::toString));
                    }

                    public Update<Integer, String, Never> update(Integer m, String message) {
                        return Update.next(m);
                    }

                    public View<String> view(Integer m, ViewContext c) {
                        return Views.blank();
                    }
                };
        assertThat(
                        ComponentDriver.initialize(inert, new InitContext(null, null))
                                .latest()
                                .command())
                .isInstanceOf(Command.Task.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void unchangedChildKeepsTheParentReferenceWithoutDroppingItsCommandOrOutput() {
        Component<Integer, Integer, Integer> child = new Component<>() {
            public Update<Integer, Integer, Integer> init(InitContext context) {
                return Update.next(1);
            }
            public Update<Integer, Integer, Integer> update(Integer model, Integer message) {
                return new Update<>(model, Command.dispatch(message), List.of(message));
            }
            public View<Integer> view(Integer model, ViewContext context) {
                return Views.text("ready");
            }
        };
        var replacements = new AtomicInteger();
        var mount = Mount.<Parent, String, String, Integer, Integer, Integer>of(
                ComponentId.of("child"), child,
                ModelLens.of(Parent::child, (parent, value) -> {
                    replacements.incrementAndGet();
                    return new Parent(value, parent.sibling());
                }),
                Object::toString, OutputRoute.toOutput(Object::toString));
        var parent = new Parent(1, new Object());
        var result = mount.update(parent, 7);
        assertThat(result.model()).isSameAs(parent);
        assertThat(replacements).hasValue(0);
        assertThat(result.outputs()).containsExactly("7");
        assertThat(result.command()).isInstanceOfSatisfying(Command.Scoped.class,
                scoped -> assertThat(scoped.command()).isEqualTo(Command.dispatch("7")));
    }
}
