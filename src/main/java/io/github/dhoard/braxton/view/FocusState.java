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

package io.github.dhoard.braxton.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Focus is explicit model data: the identity of the currently focused element, if any.
 *
 * <p>Traversal helpers are pure and order-preserving. They never install listeners or query a
 * renderer; the application decides when to move focus and what to do when the focused target
 * disappears.</p>
 */
public final class FocusState {

    public static final FocusState NONE = new FocusState(null);

    private final ElementId current;

    private FocusState(ElementId current) {
        this.current = current;
    }

    public static FocusState of(ElementId id) {
        return id == null ? NONE : new FocusState(id);
    }

    public static Optional<FocusState> ofOptional(Optional<ElementId> id) {
        return id.map(FocusState::of);
    }

    public Optional<ElementId> current() {
        return Optional.ofNullable(current);
    }

    public boolean isFocused(ElementId id) {
        return Objects.equals(current, id);
    }

    public FocusState focus(ElementId id) {
        return of(id);
    }

    public FocusState clear() {
        return NONE;
    }

    /** The element after the current one, wrapping; the first element when nothing is focused. */
    public ElementId next(List<ElementId> order) {
        return step(order, 1);
    }

    /** The element before the current one, wrapping; the last element when nothing is focused. */
    public ElementId previous(List<ElementId> order) {
        return step(order, -1);
    }

    private ElementId step(List<ElementId> order, int direction) {
        if (order == null || order.isEmpty()) {
            return null;
        }
        int index = current == null ? -1 : order.indexOf(current);
        if (index < 0) {
            return direction > 0 ? order.get(0) : order.get(order.size() - 1);
        }
        int size = order.size();
        int next = Math.floorMod(index + direction, size);
        return order.get(next);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FocusState focus && Objects.equals(current, focus.current);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(current);
    }

    @Override
    public String toString() {
        return "FocusState[" + (current == null ? "none" : current.value()) + "]";
    }
}
