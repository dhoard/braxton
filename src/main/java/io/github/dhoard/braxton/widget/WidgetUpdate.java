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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.mvu.Update;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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
 *
 * <p>A widget that has to return a command derives it from the reduced state through {@code
 * commands}: a countdown's timestamp request is a property of being armed, not of the gesture that
 * armed it, so the two rules cannot drift apart either.</p>
 */
final class WidgetUpdate {

    private WidgetUpdate() {}

    /**
     * Applies {@code reduce} to {@code message} and turns the result into an {@code Update}.
     *
     * @param model the incoming model; required
     * @param message the message being applied; required
     * @param reduce the widget's reducer, bound to {@code model}
     * @param refocus re-applies the incoming focus to the reduced state
     */
    static <M, Msg, O> Update<M, Msg, O> settled(
            M model, Msg message, Function<Msg, WidgetResult<M, O>> reduce, UnaryOperator<M> refocus) {
        return settled(model, message, reduce, refocus, state -> Command.none());
    }

    /**
     * Applies {@code reduce} to {@code message} and returns the inert command the reduced state
     * requires.
     *
     * @param model the incoming model; required
     * @param message the message being applied; required
     * @param reduce the widget's reducer, bound to {@code model}
     * @param refocus re-applies the incoming focus to the reduced state
     * @param commands derives the command the reduced model requires
     */
    static <M, Msg, O> Update<M, Msg, O> settled(
            M model,
            Msg message,
            Function<Msg, WidgetResult<M, O>> reduce,
            UnaryOperator<M> refocus,
            Function<M, Command<Msg>> commands) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(commands, "commands");
        WidgetResult<M, O> result = reduce.apply(message);
        M reduced = refocus.apply(result.state());
        // An action that changed nothing keeps the incoming instance: the view is a pure function of
        // the model, so the runtime's unchanged-reference repaint rule stays meaningful. It issues
        // nothing either, because a command derived from a state that did not move is the same work
        // the previous message already asked for.
        boolean unchanged = reduced.equals(model);
        M next = unchanged ? model : reduced;
        Command<Msg> command = unchanged ? Command.none() : Objects.requireNonNull(commands.apply(next), "command");
        return result.intent() == null
                ? Update.next(next, command)
                : new Update<>(next, command, List.of(result.intent()));
    }
}
