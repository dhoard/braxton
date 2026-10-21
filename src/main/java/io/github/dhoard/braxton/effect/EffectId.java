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

package io.github.dhoard.braxton.effect;

import java.util.Objects;

/**
 * Identity of one-shot effect (task or delay). Ids are namespaced so composed features cannot
 * cancel each other's work accidentally.
 */
public record EffectId(String value) {

    public EffectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("effect id must not be blank");
        }
    }

    public static EffectId of(String value) {
        return new EffectId(value);
    }

    /**
     * An id inside this namespace.
     *
     * @throws IllegalArgumentException when {@code name} is null, blank, or contains the {@code /}
     *     namespace separator; joining any of them would forge an id ("parent/null", "parent/",
     *     "parent/child/grandchild") that collides with a real child name or with another parent's
     *     child, so one feature's effect would replace and cancel another's
     */
    public EffectId child(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("child name must not be blank");
        }
        if (name.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "child name must not contain the '/' namespace separator");
        }
        return new EffectId(value + "/" + name);
    }

    /** Namespace prefix, used by {@link Command.CancelNamespace}. */
    public String namespace() {
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    @Override
    public String toString() {
        return value;
    }
}
