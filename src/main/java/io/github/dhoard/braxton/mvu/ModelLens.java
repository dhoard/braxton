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
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Pure access and replacement of one child model inside an explicit parent aggregate.
 *
 * <p>Replacement must preserve every unrelated parent field: it replaces exactly the child slot
 * selected by {@link #get()}. Neither function may mutate the parent or the child.</p>
 *
 * @param get reads the current child model from the parent model
 * @param replace returns the parent model with that child slot replaced
 * @param <P> the parent model type
 * @param <C> the child model type
 */
public record ModelLens<P, C>(Function<P, C> get, BiFunction<P, C, P> replace) {
    public ModelLens {
        Objects.requireNonNull(get, "get");
        Objects.requireNonNull(replace, "replace");
    }

    public static <P, C> ModelLens<P, C> of(Function<P, C> get, BiFunction<P, C, P> replace) {
        return new ModelLens<>(get, replace);
    }
}
