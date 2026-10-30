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

package io.github.dhoard.braxton.testing;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;

import java.util.Objects;

/** Deterministic single-component test driver. Commands (including dispatch and quit) remain inert
 * and inspectable. This is not a runtime: no executor, subscription start, lifecycle or terminal I/O.
 * Send resulting dispatch messages explicitly when testing a composed pure update sequence. */
public final class ComponentDriver<M, Msg, Out> {
    private final Component<M, Msg, Out> component;
    private Update<M, Msg, Out> latest;

    private ComponentDriver(Component<M, Msg, Out> component, Update<M, Msg, Out> latest) {
        this.component = component;
        this.latest = latest;
    }

    public static <M, Msg, Out> ComponentDriver<M, Msg, Out> initialize(
            Component<M, Msg, Out> component, InitContext context) {
        Objects.requireNonNull(component, "component");
        return new ComponentDriver<>(
                component,
                Objects.requireNonNull(
                        component.init(Objects.requireNonNull(context, "context")), "update"));
    }

    public Update<M, Msg, Out> send(Msg message) {
        latest =
                Objects.requireNonNull(
                        component.update(
                                latest.model(), Objects.requireNonNull(message, "message")),
                        "update");
        return latest;
    }

    public M model() {
        return latest.model();
    }

    public Update<M, Msg, Out> latest() {
        return latest;
    }

    public View<Msg> view(ViewContext context) {
        return component.view(model(), context);
    }

    public Subscription<Msg> subscriptions() {
        return component.subscriptions(model());
    }

    public ComponentTree children() {
        return component.children(model());
    }

    public RenderedScreen screen(ViewContext context) {
        return new ScreenRenderer().screen(view(context), context.viewport());
    }
}
