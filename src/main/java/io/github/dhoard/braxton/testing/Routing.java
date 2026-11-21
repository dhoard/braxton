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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.List;

/**
 * The runtime's input step, without a terminal.
 *
 * <p>A test that drives a component itself still has to make the two steps the runtime makes: route
 * the event through the tree with the component's claims and bindings, then ask the component what it
 * makes of the event it was shown. Skipping the second step is what makes an observed event look
 * unobserved in a test that has no runtime, so this helper is the whole of the sequence - and it is
 * the sequence, not an approximation of it.</p>
 */
public final class Routing {

    private Routing() {}

    /**
     * Routes one event through {@code component}'s tree, exactly as the runtime does.
     *
     * @param <M> the model type
     * @param <Msg> the message type
     * @param component the component whose tree, claims and bindings are used
     * @param model the model the frame is built from
     * @param context the view context the frame is built with
     * @param event the event to route
     * @return the messages the event produced: the routed ones, then the observed ones
     */
    public static <M, Msg> List<Msg> route(
            Component<M, Msg, ?> component, M model, ViewContext context, InputEvent event) {
        List<Msg> produced = new ArrayList<>(InteractionRouter.route(
                component.view(model, context), event, component.claims(model), component.bindings(model)));
        produced.addAll(component.observed(event));
        return List.copyOf(produced);
    }
}
