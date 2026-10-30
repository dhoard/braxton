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

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Active children, including invisible/effect-only children. Stable keys own lifetimes.
 *
 * @param nodes one node per declared child, in declaration order
 */
public record ComponentTree(List<Node> nodes) {
    public ComponentTree {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    }

    /** One declared child and, when reached, its own declaration. */
    public static final class Node {
        private final ComponentId id;
        private final Supplier<ComponentTree> descendants;

        public Node(ComponentId id, ComponentTree children) {
            this(id, () -> children);
            Objects.requireNonNull(children, "children");
        }

        private Node(ComponentId id, Supplier<ComponentTree> descendants) {
            this.id = Objects.requireNonNull(id, "id");
            this.descendants = Objects.requireNonNull(descendants, "descendants");
        }

        static Node deferred(ComponentId id, Supplier<ComponentTree> descendants) {
            return new Node(id, descendants);
        }

        public ComponentId id() {
            return id;
        }

        /** Pure declaration evaluated when this node is reached by bounded runtime traversal. */
        public ComponentTree children() {
            return Objects.requireNonNull(descendants.get(), "children");
        }
    }

    public static ComponentTree empty() {
        return new ComponentTree(List.of());
    }

    public static ComponentTree of(Node... nodes) {
        Objects.requireNonNull(nodes, "nodes");
        for (Node node : nodes) {
            Objects.requireNonNull(node, "node");
        }
        return new ComponentTree(List.of(nodes));
    }
}
