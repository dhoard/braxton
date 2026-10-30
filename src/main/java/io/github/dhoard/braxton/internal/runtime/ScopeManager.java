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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.ComponentTree;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owner-managed active scopes. Candidate validation has no live-resource side effects. */
public final class ScopeManager {
    public record Origin(ComponentPath path, long epoch) {}

    private record Pending(ComponentPath parent, ComponentTree tree) {}

    private volatile Map<ComponentPath, Long> active = Map.of(ComponentPath.ROOT, 1L);
    private volatile List<ComponentPath> lastRemoved = List.of();
    private long nextEpoch = 2L;

    public Map<ComponentPath, Long> prepare(ComponentTree tree, int maxCount, int maxDepth) {
        var next = new HashMap<ComponentPath, Long>();
        next.put(ComponentPath.ROOT, active.get(ComponentPath.ROOT));
        var pending = new ArrayDeque<Pending>();
        pending.add(new Pending(ComponentPath.ROOT, tree));
        while (!pending.isEmpty()) {
            Pending level = pending.removeLast();
            Set<ComponentId> siblings = new HashSet<>();
            for (ComponentTree.Node node : level.tree().nodes()) {
                if (!siblings.add(node.id()))
                    throw invalid(level.parent(), "duplicate component id");
                ComponentPath path = level.parent().append(node.id());
                if (path.segments().size() > maxDepth)
                    throw invalid(path, "component depth limit exceeded");
                if (next.size() >= maxCount) throw invalid(path, "component count limit exceeded");
                Long retained = active.get(path);
                next.put(path, retained == null ? nextEpoch++ : retained);
                var children =
                        ComponentFailure.call(
                                level.parent(), ComponentFailure.Phase.CHILDREN, node::children);
                if (!children.nodes().isEmpty()) pending.add(new Pending(path, children));
            }
        }
        return Map.copyOf(next);
    }

    private static ComponentFailure invalid(ComponentPath path, String message) {
        return new ComponentFailure(
                path,
                ComponentFailure.Phase.CHILDREN,
                message,
                new IllegalArgumentException(message));
    }

    public List<ComponentPath> commit(Map<ComponentPath, Long> candidate) {
        var removed =
                active.keySet().stream().filter(path -> !candidate.containsKey(path)).toList();
        active = candidate;
        lastRemoved = removed;
        return removed;
    }

    /** Whether the path is active in the committed tree. */
    public boolean isActive(ComponentPath path) {
        return active.containsKey(Objects.requireNonNull(path, "path"));
    }

    /**
     * Whether the last commit tore this path, or an ancestor of it, down.
     *
     * <p>A command produced by the update whose commit removes its own component is legitimate at
     * the time it is returned and void once the scope is gone: the runtime discards it instead of
     * failing the run. Paths that were never declared are still reported as failures.</p>
     */
    public boolean wasJustRemoved(ComponentPath path) {
        Objects.requireNonNull(path, "path");
        for (ComponentPath removed : lastRemoved) {
            if (path.startsWith(removed)) return true;
        }
        return false;
    }

    public Origin capture(ComponentPath path) {
        Long epoch = active.get(path);
        if (epoch == null) throw invalid(path, "command/source belongs to undeclared component");
        return new Origin(path, epoch);
    }

    public boolean isCurrent(Origin origin) {
        return origin != null
                && Objects.equals(active.get(origin.path()), origin.epoch());
    }
}
