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

import java.util.Objects;
import java.util.function.Function;

/**
 * Pure routing of one child output: either a parent message or a parent output.
 *
 * <p>Routing is a value transformation. It never calls a parent update on the child stack, so a
 * parent reacts to an output only through its own update and the runtime's local dispatch.</p>
 *
 * @param <Out> the child output type this route consumes
 * @param <Msg> the parent message type
 * @param <ParentOut> the parent output type
 */
@FunctionalInterface
public interface OutputRoute<Out, Msg, ParentOut> {

    /**
     * Decides where one child output goes.
     *
     * @param output the child's output value
     * @return the destination for that output
     */
    Destination<Msg, ParentOut> route(Out output);

    /**
     * Where one output crossed the boundary: a parent message or a parent output.
     *
     * @param <Msg> the parent message type
     * @param <Out> the parent output type
     */
    sealed interface Destination<Msg, Out> {}

    /**
     * A parent message, dispatched in the parent's scope after the commit.
     *
     * @param value the parent message to dispatch
     * @param <Msg> the parent message type
     * @param <Out> the parent output type
     */
    record Message<Msg, Out>(Msg value) implements Destination<Msg, Out> {
        public Message {
            Objects.requireNonNull(value, "message");
        }
    }

    /**
     * A parent output, returned upward in the parent's own {@code Update.outputs}.
     *
     * @param value the parent output to return upward
     * @param <Msg> the parent message type
     * @param <Out> the parent output type
     */
    record Output<Msg, Out>(Out value) implements Destination<Msg, Out> {
        public Output {
            Objects.requireNonNull(value, "output");
        }
    }

    static <Out, Msg, ParentOut> OutputRoute<Out, Msg, ParentOut> toMessage(
            Function<? super Out, ? extends Msg> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return output -> new Message<>(mapper.apply(output));
    }

    static <Out, Msg, ParentOut> OutputRoute<Out, Msg, ParentOut> toOutput(
            Function<? super Out, ? extends ParentOut> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return output -> new Output<>(mapper.apply(output));
    }
}
