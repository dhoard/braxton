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

package io.github.dhoard.braxton.app;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A typed root component assembled from three pure functions.
 *
 * @param <M> the model type
 * @param <Msg> the message type
 */
public final class SimpleComponent<M, Msg> implements Component<M, Msg, Never> {

    private final Function<InitContext, M> init;
    private final BiFunction<M, Msg, Update<M, Msg, Never>> update;
    private final BiFunction<M, ViewContext, Element<Msg>> view;

    private SimpleComponent(
            Function<InitContext, M> init,
            BiFunction<M, Msg, Update<M, Msg, Never>> update,
            BiFunction<M, ViewContext, Element<Msg>> view) {
        this.init = Objects.requireNonNull(init, "init");
        this.update = Objects.requireNonNull(update, "update");
        this.view = Objects.requireNonNull(view, "view");
    }

    /**
     * Creates a root component from typed init, update and view functions.
     *
     * @param <M> the model type
     * @param <Msg> the message type
     * @param init the pure initialization function
     * @param update the pure model-update function
     * @param view the pure element-tree function
     * @return a root component from typed init, update and view functions
     */
    public static <M, Msg> SimpleComponent<M, Msg> of(
            Function<InitContext, M> init,
            BiFunction<M, Msg, Update<M, Msg, Never>> update,
            BiFunction<M, ViewContext, Element<Msg>> view) {
        return new SimpleComponent<>(init, update, view);
    }

    @Override
    public Update<M, Msg, Never> init(InitContext context) {
        return Update.next(init.apply(context));
    }

    @Override
    public Update<M, Msg, Never> update(M model, Msg message) {
        return update.apply(model, message);
    }

    @Override
    public Element<Msg> view(M model, ViewContext context) {
        return view.apply(model, context);
    }

    @Override
    public Subscription<Msg> subscriptions(M model) {
        return Subscription.none();
    }

    @Override
    public ComponentTree children(M model) {
        return ComponentTree.empty();
    }
}
