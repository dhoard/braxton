/*
 * Copyright (c) 2026-present Douglas Hoard
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

/**
 * Owner-managed active scopes. Candidate validation has no live-resource side effects.
 */
public final class ScopeManager {

    /**
     * Creates the scope manager with its default state.
     */
    public ScopeManager() {}
    /**
     * A component path together with its captured lifetime epoch.
     *
     * @param path the component scope path
     * @param epoch the component lifetime generation
     */
    public record Origin(ComponentPath path, long epoch) {}

    private record Pending(ComponentPath parent, ComponentTree tree) {}

    private volatile Map<ComponentPath, Long> active = Map.of(ComponentPath.ROOT, 1L);
    private volatile List<ComponentPath> lastRemoved = List.of();
    private long nextEpoch = 2L;

    /**
     * Validates a candidate tree and assigns stable or fresh lifetime epochs.
     *
     * @param tree the declared component tree
     * @param maxCount the maximum component count
     * @param maxDepth the maximum component nesting depth
     * @return validates a candidate tree and assigns stable or fresh lifetime epochs
     */
    public Map<ComponentPath, Long> prepare(ComponentTree tree, int maxCount, int maxDepth) {
        var next = new HashMap<ComponentPath, Long>();
        next.put(ComponentPath.ROOT, active.get(ComponentPath.ROOT));
        var pending = new ArrayDeque<Pending>();
        pending.add(new Pending(ComponentPath.ROOT, tree));
        while (!pending.isEmpty()) {
            Pending level = pending.removeLast();
            Set<ComponentId> siblings = new HashSet<>();
            for (ComponentTree.Node node : level.tree().nodes()) {
                if (!siblings.add(node.id())) throw invalid(level.parent(), "duplicate component id");
                ComponentPath path = level.parent().append(node.id());
                if (path.segments().size() > maxDepth) throw invalid(path, "component depth limit exceeded");
                if (next.size() >= maxCount) throw invalid(path, "component count limit exceeded");
                Long retained = active.get(path);
                next.put(path, retained == null ? nextEpoch++ : retained);
                var children = ComponentFailure.call(level.parent(), ComponentFailure.Phase.CHILDREN, node::children);
                if (!children.nodes().isEmpty()) pending.add(new Pending(path, children));
            }
        }
        return Map.copyOf(next);
    }

    private static ComponentFailure invalid(ComponentPath path, String message) {
        return new ComponentFailure(
                path, ComponentFailure.Phase.CHILDREN, message, new IllegalArgumentException(message));
    }

    /**
     * Installs the prepared scope tree and returns removed component paths.
     *
     * @param candidate the candidate value to compare
     * @return installs the prepared scope tree and returns removed component paths
     */
    public List<ComponentPath> commit(Map<ComponentPath, Long> candidate) {
        var removed = active.keySet().stream()
                .filter(path -> !candidate.containsKey(path))
                .toList();
        active = candidate;
        lastRemoved = removed;
        return removed;
    }

    /**
     * Whether the path is active in the committed tree.
     *
     * @param path the component scope path
     * @return {@code true} if the path is active in the committed tree; otherwise {@code false}
     */
    public boolean isActive(ComponentPath path) {
        return active.containsKey(Objects.requireNonNull(path, "path"));
    }

    /**
     * Whether the last commit tore this path, or an ancestor of it, down.
     *
     * <p>A command produced by the update whose commit removes its own component is legitimate at
     * the time it is returned and void once the scope is gone: the runtime discards it instead of
     * failing the run. Paths that were never declared are still reported as failures.</p>
     *
     * @param path the component scope path
     * @return {@code true} if the last commit tore this path, or an ancestor of it, down; otherwise {@code false}
     */
    public boolean wasJustRemoved(ComponentPath path) {
        Objects.requireNonNull(path, "path");
        for (ComponentPath removed : lastRemoved) {
            if (path.startsWith(removed)) return true;
        }
        return false;
    }

    /**
     * Captures the current lifetime epoch for a component path.
     *
     * @param path the component scope path
     * @return captures the current lifetime epoch for a component path
     */
    public Origin capture(ComponentPath path) {
        Long epoch = active.get(path);
        if (epoch == null) throw invalid(path, "command/source belongs to undeclared component");
        return new Origin(path, epoch);
    }

    /**
     * Tests whether a captured origin still names an active lifetime.
     *
     * @param origin the captured component lifetime
     * @return {@code true} if a captured origin still names an active lifetime; otherwise {@code false}
     */
    public boolean isCurrent(Origin origin) {
        return origin != null && Objects.equals(active.get(origin.path()), origin.epoch());
    }

    static <Msg> void validateSubscriptions(
            io.github.dhoard.braxton.effect.Subscription<Msg> sources,
            java.util.Map<ComponentPath, Long> nextScopes,
            int maxSubscriptions) {
        ComponentPath path = ComponentPath.ROOT;
        try {
            var leaves = io.github.dhoard.braxton.effect.Subscription.leaves(sources);
            if (leaves.size() > maxSubscriptions) throw new IllegalArgumentException("too many subscriptions");
            for (var leaf : leaves) {
                path = leaf.id().path();
                if (!nextScopes.containsKey(path))
                    throw new IllegalArgumentException("subscription belongs to undeclared component " + path);
            }
        } catch (IllegalArgumentException invalid) {
            // These are framework declaration errors, not thrown component/payload diagnostics.
            throw new ComponentFailure(path, ComponentFailure.Phase.SUBSCRIPTIONS, invalid.getMessage(), invalid);
        }
    }
}
