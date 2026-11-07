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

package io.github.dhoard.braxton.widget;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import io.github.dhoard.braxton.mvu.Update;

/**
 * The one place a bundled widget turns a reduction into an {@code Update}.
 *
 * <p>Every bundled widget shares one rule the runtime depends on: a message that changed nothing
 * returns the incoming model instance, because the view is a pure function of the model and the
 * runtime's unchanged-reference repaint rule is only meaningful if the reference really is
 * unchanged. Focus is re-applied after the reduction for the same reason — a widget owns its own
 * cursor or selection but the application owns focus, so an action must not move it.</p>
 *
 * <p>Keeping the rule here rather than in each widget means the three widgets cannot disagree about
 * it, and the null contract is stated once: both the model and the message are required, and the
 * reduction runs only after both have been checked.</p>
 */
final class WidgetUpdate {

    private WidgetUpdate() {
    }

    /**
     * Applies {@code reduce} to {@code message} and turns the result into an {@code Update}.
     *
     * @param model the incoming model; required
     * @param message the message being applied; required
     * @param reduce the widget's reducer, bound to {@code model}
     * @param refocus re-applies the incoming focus to the reduced state
     */
    static <M, Msg, O> Update<M, Msg, O> settled(
            M model,
            Msg message,
            Function<Msg, WidgetResult<M, O>> reduce,
            UnaryOperator<M> refocus) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        WidgetResult<M, O> result = reduce.apply(message);
        M next = refocus.apply(result.state());
        // An action that changed nothing keeps the incoming instance: the view is a pure function of
        // the model, so the runtime's unchanged-reference repaint rule stays meaningful.
        if (next.equals(model)) {
            next = model;
        }
        return result.intent() == null ? Update.next(next) : Update.emit(next, result.intent());
    }
}
