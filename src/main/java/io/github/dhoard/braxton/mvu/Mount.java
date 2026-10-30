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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.view.View;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Typed composition boundary: model replacement, message/output lifting and structural scoping.
 * Declare {@link #tree} for every active mount, including mounts not currently rendered.
 *
 * <p>The child's commands, subscriptions, views, focus elements and descendant paths are scoped to
 * the mount path. An output routed to a parent message is different: that message belongs to the
 * parent, so it is dispatched in the parent's scope and still reaches it when the same update
 * unmounts the child. Upward outputs stay values the parent decides about.</p>
 *
 * @param <P> the parent model type
 * @param <Msg> the parent message type
 * @param <Out> the parent output type
 * @param <C> the child model type
 * @param <ChildMsg> the child message type
 * @param <ChildOut> the child output type
 */
public final class Mount<P, Msg, Out, C, ChildMsg, ChildOut> {
    private final ComponentId id;
    private final Component<C, ChildMsg, ChildOut> child;
    private final ModelLens<P, C> lens;
    private final Function<? super ChildMsg, ? extends Msg> messages;
    private final OutputRoute<ChildOut, Msg, Out> outputs;
    private final ComponentPath path;

    private Mount(
            ComponentId id,
            Component<C, ChildMsg, ChildOut> child,
            ModelLens<P, C> lens,
            Function<? super ChildMsg, ? extends Msg> messages,
            OutputRoute<ChildOut, Msg, Out> outputs) {
        this.id = Objects.requireNonNull(id, "id");
        this.child = Objects.requireNonNull(child, "child");
        this.lens = Objects.requireNonNull(lens, "lens");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.path = ComponentPath.ROOT.append(id);
    }

    /**
     * Binds one child component to one slot of a parent model.
     *
     * @param id the child's local identity segment under its parent
     * @param child the child component definition
     * @param lens pure access/replacement of the child model in the parent model
     * @param messages maps every child message to a parent message
     * @param outputs routes every child output to a parent message or a parent output
     * @return the immutable mount
     */
    public static <P, Msg, Out, C, ChildMsg, ChildOut> Mount<P, Msg, Out, C, ChildMsg, ChildOut> of(
            ComponentId id,
            Component<C, ChildMsg, ChildOut> child,
            ModelLens<P, C> lens,
            Function<? super ChildMsg, ? extends Msg> messages,
            OutputRoute<ChildOut, Msg, Out> outputs) {
        return new Mount<>(id, child, lens, messages, outputs);
    }

    private <T> T call(ComponentFailure.Phase phase, Supplier<T> action) {
        return ComponentFailure.call(path, phase, action);
    }

    public Initialization<C, Msg, Out> initialize(InitContext context) {
        return lift(
                call(
                        ComponentFailure.Phase.INIT,
                        () -> child.init(Objects.requireNonNull(context, "context"))));
    }

    public Update<P, Msg, Out> update(P parent, ChildMsg message) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(message, "message");
        C previous = call(ComponentFailure.Phase.UPDATE, () -> lens.get().apply(parent));
        var result =
                call(ComponentFailure.Phase.UPDATE, () -> child.update(previous, message));
        var lifted = lift(result);
        P next =
                result.model() == previous
                        ? parent
                        : call(
                                ComponentFailure.Phase.UPDATE,
                                () -> lens.replace().apply(parent, result.model()));
        return new Update<>(next, lifted.command(), lifted.outputs());
    }

    private Initialization<C, Msg, Out> lift(Update<C, ChildMsg, ChildOut> result) {
        return call(
                ComponentFailure.Phase.OUTPUT_ROUTE,
                () -> {
                    var commands = new ArrayList<Command<Msg>>();
                    // The child's own work is scoped to the child path. Outputs routed to a parent
                    // message are not: that message belongs to the parent, so it is dispatched in
                    // the parent's scope and still arrives when this update unmounts the child.
                    commands.add(result.command().<Msg>map(messages).scoped(path));
                    var upward = new ArrayList<Out>();
                    for (ChildOut output : result.outputs()) {
                        switch (Objects.requireNonNull(outputs.route(output), "output route")) {
                            case OutputRoute.Message<Msg, Out> message ->
                                    commands.add(Command.dispatch(message.value()));
                            case OutputRoute.Output<Msg, Out> value -> upward.add(value.value());
                        }
                    }
                    return new Initialization<>(result.model(), Command.batch(commands), upward);
                });
    }

    public View<Msg> view(P parent, ViewContext context) {
        Objects.requireNonNull(parent, "parent");
        return call(
                ComponentFailure.Phase.VIEW,
                () ->
                        child.view(lens.get().apply(parent), context)
                                .<Msg>map(messages)
                                .scoped(path));
    }

    public Subscription<Msg> subscriptions(P parent) {
        Objects.requireNonNull(parent, "parent");
        return call(
                ComponentFailure.Phase.SUBSCRIPTIONS,
                () ->
                        child.subscriptions(lens.get().apply(parent))
                                .<Msg>map(messages)
                                .scoped(path));
    }

    public ComponentTree.Node tree(P parent) {
        Objects.requireNonNull(parent, "parent");
        return ComponentTree.Node.deferred(
                id,
                () ->
                        call(
                                ComponentFailure.Phase.CHILDREN,
                                () -> child.children(lens.get().apply(parent))));
    }
}
