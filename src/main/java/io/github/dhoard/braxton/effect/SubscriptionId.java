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

/** Identity of one ongoing source. Same id and configuration keeps the live resource. */
public record SubscriptionId(String value) {

    public SubscriptionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("subscription id must not be blank");
        }
    }

    public static SubscriptionId of(String value) {
        return new SubscriptionId(value);
    }

    /**
     * An id inside this namespace.
     *
     * @throws IllegalArgumentException when {@code name} is null or blank; joining either would
     *     forge an id ("parent/null", "parent/   ") that collides with a real child name
     */
    public SubscriptionId child(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("child name must not be blank");
        }
        return new SubscriptionId(value + "/" + name);
    }

    @Override
    public String toString() {
        return value;
    }
}
