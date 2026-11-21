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

package io.github.dhoard.braxton.mvu;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.ui.Element;
import java.util.ArrayList;
import java.util.List;
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
 * <p>A mount is active while the parent declares its {@link #tree} node, whether or not the parent
 * paints its {@link #view}. That is the keep-alive policy a page behind a tab or a pane behind a
 * switch relies on: the child keeps its model, its scoped element ids and its subscriptions while
 * another child is painted, so returning to it finds the same draft and a timer that is still
 * running. Declaring no node is the explicit unmount: the child's scoped subscriptions and commands
 * are canceled, its element ids are released, and a later remount starts from {@link #initialize}
 * again. Nothing is kept alive implicitly - the declaration is the whole policy, and a parent that
 * stops declaring a node has decided to end that child.</p>
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
     * @param <C> the child model type
     * @param <ChildMsg> the child message type
     * @param <ChildOut> the child output type
     * @param <Msg> the parent message type
     * @param <Out> the parent output type
     * @param <P> the parent model type
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

    /**
     * Initializes the child component and lifts its command and outputs.
     *
     * @param context the initialization or view context
     * @return initializes the child component and lifts its command and outputs
     */
    public Initialization<C, Msg, Out> initialize(InitContext context) {
        return lift(call(ComponentFailure.Phase.INIT, () -> child.init(Objects.requireNonNull(context, "context"))));
    }

    /**
     * Applies a child message and replaces its model through the lens.
     *
     * @param parent the parent model
     * @param message the typed message to deliver
     * @return the parent transition with mapped child commands and outputs
     */
    public Update<P, Msg, Out> update(P parent, ChildMsg message) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(message, "message");
        C previous = call(ComponentFailure.Phase.UPDATE, () -> lens.get().apply(parent));
        var result = call(ComponentFailure.Phase.UPDATE, () -> child.update(previous, message));
        var lifted = lift(result);
        P next = result.model() == previous
                ? parent
                : call(ComponentFailure.Phase.UPDATE, () -> lens.replace().apply(parent, result.model()));
        return new Update<>(next, lifted.command(), lifted.outputs());
    }

    private Initialization<C, Msg, Out> lift(Update<C, ChildMsg, ChildOut> result) {
        return call(ComponentFailure.Phase.OUTPUT_ROUTE, () -> {
            var commands = new ArrayList<Command<Msg>>();
            // The child's own work is scoped to the child path. Outputs routed to a parent
            // message are not: that message belongs to the parent, so it is dispatched in
            // the parent's scope and still arrives when this update unmounts the child.
            commands.add(result.command().<Msg>map(messages).scoped(path));
            var upward = new ArrayList<Out>();
            for (ChildOut output : result.outputs()) {
                switch (Objects.requireNonNull(outputs.route(output), "output route")) {
                    case OutputRoute.Message<Msg, Out> message -> commands.add(Command.dispatch(message.value()));
                    case OutputRoute.Output<Msg, Out> value -> upward.add(value.value());
                }
            }
            return new Initialization<>(result.model(), Command.batch(commands), upward);
        });
    }

    /**
     * Builds the child view and maps its messages into the parent type.
     *
     * @param parent the parent model
     * @param context the initialization or view context
     * @return the child view and maps its messages into the parent type
     */
    public Element<Msg> view(P parent, ViewContext context) {
        Objects.requireNonNull(parent, "parent");
        // The child speaks its own message type and the parent speaks another, so the whole answer
        // is one mapping: the tree keeps the child's geometry, focus and caret.
        return call(
                ComponentFailure.Phase.VIEW,
                () -> child.view(lens.get().apply(parent), context).map(messages));
    }

    /**
     * The gestures the child claims, mapped into the parent's message type.
     *
     * <p>A hook lives on the component, so a mounted child's claims do not travel with its view the
     * way a routing node in a view tree once did. The parent asks for them here and composes them
     * with its own: only the parent knows the order its children's gestures belong in, and only the
     * parent can decide that a child's claim outranks its own.</p>
     *
     * @param parent the parent model the child model is read from
     * @return the child's claimed bindings, in the parent's message type
     */
    public List<Binding<Msg>> claims(P parent) {
        Objects.requireNonNull(parent, "parent");
        C childModel = call(ComponentFailure.Phase.VIEW, () -> lens.get().apply(parent));
        return lift(call(ComponentFailure.Phase.VIEW, () -> child.claims(childModel)));
    }

    /**
     * The child's own key bindings, mapped into the parent's message type.
     *
     * @param parent the parent model the child model is read from
     * @return the child's bindings, in the parent's message type
     */
    public List<Binding<Msg>> bindings(P parent) {
        Objects.requireNonNull(parent, "parent");
        C childModel = call(ComponentFailure.Phase.VIEW, () -> lens.get().apply(parent));
        return lift(call(ComponentFailure.Phase.VIEW, () -> child.bindings(childModel)));
    }

    /**
     * What the child makes of an event it was shown, mapped into the parent's message type.
     *
     * @param parent the parent model the child model is read from
     * @param event the event being routed
     * @return the child's messages, in the parent's message type
     */
    public List<Msg> observed(P parent, InputEvent event) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(event, "event");
        C childModel = call(ComponentFailure.Phase.VIEW, () -> lens.get().apply(parent));
        return call(ComponentFailure.Phase.VIEW, () -> child.observed(event)).stream()
                .<Msg>map(messages)
                .toList();
    }

    /**
     * What the child asks the frame to present with this model, or null when it asks for nothing.
     *
     * <p>A presentation is the root's answer, so a parent that mounts a scene forwards it: the scene
     * that asked for an alternate screen still gets one when the parent is what the runtime asks.</p>
     *
     * @param parent the parent model the child model is read from
     * @return the child's presentation, or null
     */
    public io.github.dhoard.braxton.app.TerminalPresentation presentation(P parent) {
        Objects.requireNonNull(parent, "parent");
        C childModel = call(ComponentFailure.Phase.VIEW, () -> lens.get().apply(parent));
        // A child that declares no presentation answers null, and that is an answer: the wrapper
        // rejects null, so the child's own answer is returned unwrapped.
        return child.presentation(childModel);
    }

    /**
     * Lifts a child's bindings into the parent's message type.
     */
    private List<Binding<Msg>> lift(List<Binding<ChildMsg>> bindings) {
        List<Binding<Msg>> lifted = new ArrayList<>(bindings.size());
        for (Binding<ChildMsg> binding : bindings) {
            lifted.add(new Binding<>(binding.key(), messages.apply(binding.message())));
        }
        return List.copyOf(lifted);
    }

    /**
     * Scopes the child subscriptions and lifts their messages to the parent.
     *
     * @param parent the parent model
     * @return scopes the child subscriptions and lifts their messages to the parent
     */
    public Subscription<Msg> subscriptions(P parent) {
        Objects.requireNonNull(parent, "parent");
        return call(
                ComponentFailure.Phase.SUBSCRIPTIONS,
                () -> child.subscriptions(lens.get().apply(parent))
                        .<Msg>map(messages)
                        .scoped(path));
    }

    /**
     * Declares this child identity and its active descendants.
     *
     * @param parent the parent model
     * @return declares this child identity and its active descendants
     */
    public ComponentTree.Node tree(P parent) {
        Objects.requireNonNull(parent, "parent");
        return ComponentTree.Node.deferred(
                id,
                () -> call(
                        ComponentFailure.Phase.CHILDREN,
                        () -> child.children(lens.get().apply(parent))));
    }
}
