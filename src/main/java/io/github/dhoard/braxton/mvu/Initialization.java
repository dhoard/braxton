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
 * Initialized child model plus already lifted/scoped commands and routed parent outputs.
 *
 * <p>The parent constructs its aggregate model from {@link #model()} before returning these
 * effects to the runtime, so no effect can observe a model that does not exist yet.</p>
 *
 * @param model the child's initialized model
 * @param command the child's command, scoped to the mount path
 * @param outputs the outputs the mount routed upward to the parent
 * @param <M> the child model type
 * @param <Msg> the parent message type
 * @param <Out> the parent output type
 */
public record Initialization<M, Msg, Out>(M model, Command<Msg> command, List<Out> outputs) {
    public Initialization {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(command, "command");
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }
}
