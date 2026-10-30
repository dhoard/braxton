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

import io.github.dhoard.braxton.effect.Command;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of one initialization or update step. Output order is declaration order;
 * an unchanged model reference never suppresses a command or an output.
 *
 * @param model the next model, never null
 * @param command the inert command to interpret after the commit, never null
 * @param outputs the semantic outputs for the parent, in declaration order
 * @param <M> the component model type
 * @param <Msg> the component message type
 * @param <Out> the semantic output type crossing the component boundary
 */
public record Update<M, Msg, Out>(M model, Command<Msg> command, List<Out> outputs) {
    public Update {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(command, "command");
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }

    public static <M, Msg, Out> Update<M, Msg, Out> next(M model) {
        return next(model, Command.none());
    }

    public static <M, Msg, Out> Update<M, Msg, Out> next(M model, Command<Msg> command) {
        return new Update<>(model, command, List.of());
    }

    public static <M, Msg, Out> Update<M, Msg, Out> emit(M model, Out output) {
        Objects.requireNonNull(output, "output");
        return new Update<>(model, Command.none(), List.of(output));
    }
}
