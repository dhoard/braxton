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

import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;

import java.util.Objects;

/** A validated local identity. Mounting adds a structural path, never a string namespace. */
public final class SubscriptionId {
    private final String value;
    private final ComponentPath path;

    private SubscriptionId(String value, ComponentPath path) {
        this.value = ComponentId.of(value).value();
        this.path = Objects.requireNonNull(path, "path");
    }

    public static SubscriptionId of(String value) {
        return new SubscriptionId(value, ComponentPath.ROOT);
    }

    public String value() {
        return value;
    }

    public ComponentPath path() {
        return path;
    }

    /** Composition operation used by mounts; prepends a structural scope. */
    public SubscriptionId scoped(ComponentPath prefix) {
        return new SubscriptionId(value, path.prepend(prefix));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubscriptionId id && value.equals(id.value) && path.equals(id.path);
    }

    @Override
    public int hashCode() {
        return 31 * path.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return path.equals(ComponentPath.ROOT) ? value : path + ":" + value;
    }
}
