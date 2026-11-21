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

import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.ui.Element;
import java.util.List;

/**
 * Pure MVU definition. Models belong to the caller; definitions hold immutable configuration.
 * Methods must not perform I/O or mutate models. Return inert commands for effects.
 *
 * <p>The runtime serializes root updates. Use {@link Mount} to replace child models and lift their
 * commands, outputs, subscriptions and views; never call a parent update from a child. Roots use
 * {@link Never} as their output type.</p>
 *
 * @param <M> immutable component model
 * @param <Msg> component-owned messages
 * @param <Out> semantic outputs consumed by the parent
 */
public interface Component<M, Msg, Out> {
    /**
     * Initializes a non-null model and inert effects from immutable startup facts.
     *
     * @param context the initialization or view context
     * @return initializes a non-null model and inert effects from immutable startup facts
     */
    Update<M, Msg, Out> init(InitContext context);

    /**
     * Returns the next model, command and ordered outputs without mutating either argument.
     *
     * @param model the immutable model to inspect
     * @param message the typed message to deliver
     * @return the next model, command and ordered outputs without mutating either argument
     */
    Update<M, Msg, Out> update(M model, Msg message);

    /**
     * Describes the current view without changing model or runtime state.
     *
     * @param model the immutable model to inspect
     * @param context the initialization or view context
     * @return describes the current view without changing model or runtime state
     */
    Element<Msg> view(M model, ViewContext context);

    /**
     * What this component asks the terminal to present with this model, or null to leave it to the
     * session and to any declaration in the tree.
     *
     * <p>Presentation is frame metadata, not a node: a session has one alternate-screen state, one
     * pointer level and one title per frame, so the root component's model is where the answer
     * belongs. A component that declares nothing keeps the session options it opened with, and the
     * metadata fields - title, default colors, progress, status - are absent when unnamed, because
     * a terminal has no default for them.</p>
     *
     * @param model the current model
     * @return the presentation to apply to this frame, or null to declare nothing
     */
    default TerminalPresentation presentation(M model) {
        return null;
    }

    /**
     * The gestures this component claims: matched before the focused widget, and before the tree.
     *
     * <p>A claim is how an application keeps a key that a focused field would otherwise take - the
     * scroll a transcript answers, the tab that moves focus, the two keys a dialog traps - so it is
     * the one place where the application outranks the widget that owns focus. Keep it small: a claim
     * that names an ordinary key costs the user that key in every field on the screen, and a claim
     * that names everything is an application that never lets a widget answer at all.</p>
     *
     * <p>Claims are read from the current model on every event, exactly like {@link #bindings(M)},
     * and they are matched only for activating key gestures: a release carries the press's content
     * but is not that press, so a claim never fires twice for one keystroke.</p>
     *
     * @param model the current model
     * @return the claimed bindings; never null
     */
    default List<Binding<Msg>> claims(M model) {
        return List.of();
    }

    /**
     * The messages this component makes of an event it did not have to ask for.
     *
     * <p>Observing is not consuming. The runtime offers every event to this hook and still routes it:
     * a widget answers it first if it can, the application's {@link #bindings(M)} answer it last, and
     * the messages returned here are dispatched after that routing has been decided. An application
     * that has to show what arrived - a transcript of the keyboard, a log of pointer reports, a
     * keyboard demo - can therefore record an event it never takes away from anyone, and an
     * application that has to <em>act</em> on a gesture claims it instead.</p>
     *
     * <p>The hook sees the event after it was stamped, so a key carries the time the terminal
     * reported it: a double-press window is measured on that stamp rather than on when the message
     * happened to be dispatched.</p>
     *
     * @param event the event being routed
     * @return the messages to dispatch after routing; never null
     */
    default List<Msg> observed(InputEvent event) {
        return List.of();
    }

    /**
     * The application's own key bindings, matched after the focused widget has declined a gesture.
     *
     * <p>A gesture belongs to the widget that owns focus first: the widget consumes it by emitting
     * its own message, and only a gesture it stays silent about is offered here. That is the whole
     * routing order - focus, then the application - so a component's bindings are where an
     * application declares its global shortcuts, and no node in the tree has to know about them.</p>
     *
     * <p>The bindings are read from the current model on every event, so which shortcuts exist can
     * depend on what is on screen: a dialog open, a field being edited, a pane focused. An empty
     * list is the default, and an unbound gesture falls through to exactly nothing.</p>
     *
     * @param model the current model
     * @return the bindings to match, in help order; never null
     */
    default List<Binding<Msg>> bindings(M model) {
        return List.of();
    }

    /**
     * Declares ongoing sources, including the scoped subscriptions of every active child.
     *
     * @param model the immutable model to inspect
     * @return declares ongoing sources, including the scoped subscriptions of every active child
     */
    default Subscription<Msg> subscriptions(M model) {
        return Subscription.none();
    }

    /**
     * Declares active mounts independently of visibility, before any resources are reconciled.
     *
     * @param model the immutable model to inspect
     * @return declares active mounts independently of visibility, before any resources are reconciled
     */
    default ComponentTree children(M model) {
        return ComponentTree.empty();
    }
}
