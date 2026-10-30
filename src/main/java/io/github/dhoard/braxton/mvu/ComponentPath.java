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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structural path of component segments. Value equality is structural, and the diagnostic
 * {@link #toString()} rendering is not an identity parsing API.
 *
 * <p>Paths are serializable because they are part of the immutable diagnostic context carried by a
 * serializable {@link ComponentFailure}; a path is a value, never a persisted model format.</p>
 *
 * @param segments the immutable path from the root, empty for {@link #ROOT}
 */
public record ComponentPath(List<ComponentId> segments) implements Serializable {
    public static final ComponentPath ROOT = new ComponentPath(List.of());

    public ComponentPath {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    }

    public ComponentPath append(ComponentId id) {
        Objects.requireNonNull(id, "id");
        if (segments.isEmpty()) return new ComponentPath(List.of(id));
        var next = new ArrayList<>(segments);
        next.add(id);
        return new ComponentPath(next);
    }

    public ComponentPath prepend(ComponentPath prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.segments.isEmpty()) return this;
        if (segments.isEmpty()) return prefix;
        var next = new ArrayList<>(prefix.segments);
        next.addAll(segments);
        return new ComponentPath(next);
    }

    public boolean startsWith(ComponentPath prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (segments.size() < prefix.segments.size()) return false;
        for (int i = 0; i < prefix.segments.size(); i++) {
            if (!segments.get(i).equals(prefix.segments.get(i))) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ComponentPath path) || segments.size() != path.segments.size())
            return false;
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(path.segments.get(i))) return false;
        }
        return true;
    }

    /** Explicit because {@link #equals} is explicit: the two must stay one contract. */
    @Override
    public int hashCode() {
        int hash = 1;
        for (ComponentId segment : segments) hash = 31 * hash + segment.hashCode();
        return hash;
    }

    @Override
    public String toString() {
        return "/" + String.join("/", segments.stream().map(ComponentId::value).toList());
    }
}
