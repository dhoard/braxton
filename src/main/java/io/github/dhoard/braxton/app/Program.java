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

package io.github.dhoard.braxton.app;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.view.View;

/**
 * One MVU application: immutable state, application-owned messages, a pure update, a pure view,
 * and the sources it currently needs.
 *
 * <p>Implementations must not perform I/O in {@code init}, {@code update}, {@code view}, or
 * {@code subscriptions}; effects are returned as {@link io.github.dhoard.braxton.effect.Command} values. Messages are
 * usually a sealed interface of records owned by the application, which keeps dispatch exhaustive
 * and lets child features map their own messages into the parent.</p>
 *
 * @param <M> application model
 * @param <Msg> application message type
 */
public interface Program<M, Msg> {

    /** Builds the initial model. Must be pure. */
    Transition<M, Msg> init(InitContext context);

    /** Pure state transition: same model and message always produce an equivalent result. */
    Transition<M, Msg> update(M model, Msg message);

    /** Pure description of the current screen. */
    View<Msg> view(M model, Viewport viewport);

    /** Ongoing sources required by the current model. Reconciled after every commit. */
    default Subscription<Msg> subscriptions(M model) {
        return Subscription.none();
    }
}
