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

import java.util.Objects;

import io.github.dhoard.braxton.effect.Command;

/**
 * The result of {@code init} or {@code update}: the next model plus an inert command.
 *
 * <p>A returned identical model reference lets the runtime skip rebuilding the view; returning a
 * value equal to the previous one is also fine, because the screen diff suppresses redundant
 * output. Neither shortcut skips command interpretation or subscription reconciliation.</p>
 */
public record Transition<M, Msg>(M model, Command<Msg> command) {

    public Transition {
        Objects.requireNonNull(command, "command");
    }

    public static <M, Msg> Transition<M, Msg> next(M model) {
        return new Transition<>(model, Command.none());
    }

    public static <M, Msg> Transition<M, Msg> next(M model, Command<Msg> command) {
        return new Transition<>(model, command);
    }
}
