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

package io.github.dhoard.braxton.testing;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import java.util.Objects;

/**
 * Deterministic single-component test driver. Commands (including dispatch and quit) remain inert
 * and inspectable. This is not a runtime: no executor, subscription start, lifecycle or terminal I/O.
 * Send resulting dispatch messages explicitly when testing a composed pure update sequence.
 *
 * @param <M> the component model type
 * @param <Msg> the component message type
 * @param <Out> the component output type
 */
public final class ComponentDriver<M, Msg, Out> {
    private final Component<M, Msg, Out> component;
    private Update<M, Msg, Out> latest;

    private ComponentDriver(Component<M, Msg, Out> component, Update<M, Msg, Out> latest) {
        this.component = component;
        this.latest = latest;
    }

    /**
     * Initializes a pure component driver with the supplied context.
     *
     * @param <M> the component model type
     * @param <Msg> the component message type
     * @param <Out> the component output type
     * @param component the typed component to drive
     * @param context the initialization or view context
     * @return initializes a pure component driver with the supplied context
     */
    public static <M, Msg, Out> ComponentDriver<M, Msg, Out> initialize(
            Component<M, Msg, Out> component, InitContext context) {
        Objects.requireNonNull(component, "component");
        return new ComponentDriver<>(
                component,
                Objects.requireNonNull(component.init(Objects.requireNonNull(context, "context")), "update"));
    }

    /**
     * Applies a typed message through the component update.
     *
     * @param message the typed message to deliver
     * @return this driver after applying the message
     */
    public Update<M, Msg, Out> send(Msg message) {
        latest = Objects.requireNonNull(
                component.update(latest.model(), Objects.requireNonNull(message, "message")), "update");
        return latest;
    }

    /**
     * Returns the current immutable component model.
     *
     * @return the current immutable component model
     */
    public M model() {
        return latest.model();
    }

    /**
     * The component being driven, for a test that routes an event the way the runtime does.
     *
     * @return the component being driven, for a test that routes an event the way the runtime does
     */
    public Component<M, Msg, Out> component() {
        return component;
    }

    /**
     * Returns the most recently applied transition.
     *
     * @return the most recently applied transition
     */
    public Update<M, Msg, Out> latest() {
        return latest;
    }

    /**
     * Builds the current element tree for the supplied viewport.
     *
     * @param context the initialization or view context
     * @return the current element tree for the supplied viewport
     */
    public Element<Msg> view(ViewContext context) {
        return component.view(model(), context);
    }

    /**
     * Returns the current subscription declarations without starting them.
     *
     * @return the current subscription declarations without starting them
     */
    public Subscription<Msg> subscriptions() {
        return component.subscriptions(model());
    }

    /**
     * Returns the active child declaration for the current model.
     *
     * @return the active child declaration for the current model
     */
    public ComponentTree children() {
        return component.children(model());
    }

    /**
     * The frame this model paints, laid out and painted as the runtime would paint it.
     *
     * @param context the initialization or view context
     * @return the frame this model paints, laid out and painted as the runtime would paint it
     */
    public ElementScreen screen(ViewContext context) {
        ViewContext frame = context;
        return ElementScreen.render(
                view(frame), frame.viewport().rows(), frame.viewport().cols());
    }
}
