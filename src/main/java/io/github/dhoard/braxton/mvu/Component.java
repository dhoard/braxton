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

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.view.View;

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
    /** Initializes a non-null model and inert effects from immutable startup facts. */
    Update<M, Msg, Out> init(InitContext context);

    /** Returns the next model, command and ordered outputs without mutating either argument. */
    Update<M, Msg, Out> update(M model, Msg message);

    /** Describes the current view without changing model or runtime state. */
    View<Msg> view(M model, ViewContext context);

    /** Declares ongoing sources, including the scoped subscriptions of every active child. */
    default Subscription<Msg> subscriptions(M model) {
        return Subscription.none();
    }

    /** Declares active mounts independently of visibility, before any resources are reconciled. */
    default ComponentTree children(M model) {
        return ComponentTree.empty();
    }
}
