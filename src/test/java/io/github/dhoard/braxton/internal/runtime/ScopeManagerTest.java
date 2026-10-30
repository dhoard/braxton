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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.mvu.ComponentFailure;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.testing.AllocationProbe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

class ScopeManagerTest {
    private static ComponentTree.Node node(String key) {
        return new ComponentTree.Node(ComponentId.of(key), ComponentTree.empty());
    }

    @Test
    void reorderRetainsEpochRemovalInvalidatesAndReinsertionIsFresh() {
        var scopes = new ScopeManager();
        var a = ComponentPath.ROOT.append(ComponentId.of("a"));
        scopes.commit(scopes.prepare(ComponentTree.of(node("a"), node("b")), 3, 1));
        var origin = scopes.capture(a);
        assertThat(scopes.commit(scopes.prepare(ComponentTree.of(node("b"), node("a")), 3, 1)))
                .isEmpty();
        assertThat(scopes.isCurrent(origin)).isTrue();
        assertThat(scopes.commit(scopes.prepare(ComponentTree.of(node("b")), 3, 1)))
                .containsExactly(a);
        assertThat(scopes.isCurrent(origin)).isFalse();
        assertThatThrownBy(() -> scopes.capture(a)).isInstanceOf(ComponentFailure.class);
        scopes.commit(scopes.prepare(ComponentTree.of(node("a"), node("b")), 3, 1));
        assertThat(scopes.capture(a).epoch()).isNotEqualTo(origin.epoch());
        assertThat(scopes.isCurrent(origin)).isFalse();
    }

    @Test
    void aRemovedCommitReportsItselfAndItsDescendantsAsTornDown() {
        var scopes = new ScopeManager();
        var mid = ComponentPath.ROOT.append(ComponentId.of("mid"));
        var leaf = mid.append(ComponentId.of("leaf"));
        var other = ComponentPath.ROOT.append(ComponentId.of("other"));
        var nested =
                ComponentTree.of(
                        new ComponentTree.Node(ComponentId.of("mid"), ComponentTree.of(node("leaf"))),
                        node("other"));
        assertThat(scopes.commit(scopes.prepare(nested, 8, 8))).isEmpty();
        for (var path : new ComponentPath[] {mid, leaf, other, ComponentPath.ROOT}) {
            assertThat(scopes.isActive(path)).as("active %s", path).isTrue();
            assertThat(scopes.wasJustRemoved(path)).as("removed %s", path).isFalse();
        }

        // The removed set is unordered: component maps randomize iteration per JVM run.
        assertThat(scopes.commit(scopes.prepare(ComponentTree.of(node("other")), 8, 8)))
                .containsExactlyInAnyOrder(mid, leaf);
        // The removed subtree is gone, including the descendant that was never listed itself.
        assertThat(scopes.isActive(mid)).isFalse();
        assertThat(scopes.isActive(leaf)).isFalse();
        assertThat(scopes.wasJustRemoved(mid)).isTrue();
        assertThat(scopes.wasJustRemoved(leaf)).isTrue();
        assertThat(scopes.wasJustRemoved(leaf.append(ComponentId.of("deeper")))).isTrue();
        assertThat(scopes.isActive(other)).isTrue();
        assertThat(scopes.wasJustRemoved(other)).isFalse();
        // The window is the last commit only, and it is reset by the next one.
        scopes.commit(scopes.prepare(ComponentTree.of(node("other")), 8, 8));
        assertThat(scopes.wasJustRemoved(mid)).isFalse();
        assertThat(scopes.wasJustRemoved(leaf)).isFalse();
    }

    @Test
    void failedCandidatesLeaveActiveEpochsUnchanged() {
        var scopes = new ScopeManager();
        var root = scopes.capture(ComponentPath.ROOT);
        assertThatThrownBy(() -> scopes.prepare(ComponentTree.of(node("a"), node("a")), 4, 4))
                .isInstanceOf(ComponentFailure.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scopes.prepare(ComponentTree.of(node("a")), 1, 4))
                .isInstanceOf(ComponentFailure.class);
        var deep =
                ComponentTree.of(
                        new ComponentTree.Node(ComponentId.of("a"), ComponentTree.of(node("b"))));
        assertThatThrownBy(() -> scopes.prepare(deep, 4, 1)).isInstanceOf(ComponentFailure.class);
        assertThat(scopes.isCurrent(root)).isTrue();
        assertThat(scopes.prepare(deep, 3, 2)).hasSize(3);
    }

    @Test
    void randomKeyReordersNeverRestartRetainedResources() {
        var random = new Random(2917);
        var scopes = new ScopeManager();
        var nodes = new ArrayList<ComponentTree.Node>();
        for (int i = 0; i < 200; i++) nodes.add(node("k" + i));
        var initial = scopes.prepare(new ComponentTree(nodes), 201, 1);
        scopes.commit(initial);
        for (int i = 0; i < 50; i++) {
            Collections.shuffle(nodes, random);
            var next = scopes.prepare(new ComponentTree(nodes), 201, 1);
            assertThat(next).isEqualTo(initial);
            assertThat(scopes.commit(next)).isEmpty();
        }
    }

    private static ComponentTree randomTree(Random random, int depth) {
        if (depth == 0) return ComponentTree.empty();
        var nodes = new ArrayList<ComponentTree.Node>();
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++)
            nodes.add(
                    new ComponentTree.Node(ComponentId.of("n" + i), randomTree(random, depth - 1)));
        Collections.shuffle(nodes, random);
        return new ComponentTree(nodes);
    }

    @Test
    void seededNestedTreeChangesPreserveExactlyTheContinuouslyActiveEpochs() {
        var random = new Random(417);
        var scopes = new ScopeManager();
        java.util.Map<ComponentPath, Long> previous = java.util.Map.of(ComponentPath.ROOT, 1L);
        var history = new java.util.HashMap<ComponentPath, Long>(previous);
        record Pending(ComponentPath path, ComponentTree tree) {}
        for (int iteration = 0; iteration < 300; iteration++) {
            var tree = randomTree(random, 4);
            var expected = new java.util.HashSet<ComponentPath>();
            expected.add(ComponentPath.ROOT);
            var pending = new java.util.ArrayDeque<Pending>();
            pending.add(new Pending(ComponentPath.ROOT, tree));
            while (!pending.isEmpty()) {
                var level = pending.removeFirst();
                for (var node : level.tree().nodes()) {
                    var path = level.path().append(node.id());
                    expected.add(path);
                    pending.addLast(new Pending(path, node.children()));
                }
            }
            var next = scopes.prepare(tree, 200, 4);
            assertThat(next.keySet()).containsExactlyInAnyOrderElementsOf(expected);
            for (var entry : next.entrySet()) {
                if (previous.containsKey(entry.getKey())) {
                    assertThat(entry.getValue()).isEqualTo(previous.get(entry.getKey()));
                } else if (history.containsKey(entry.getKey())) {
                    assertThat(entry.getValue()).isGreaterThan(history.get(entry.getKey()));
                }
            }
            var removed = scopes.commit(next);
            assertThat(removed)
                    .containsExactlyInAnyOrderElementsOf(
                            previous.keySet().stream()
                                    .filter(path -> !expected.contains(path))
                                    .toList());
            for (var entry : previous.entrySet()) {
                assertThat(
                                scopes.isCurrent(
                                        new ScopeManager.Origin(entry.getKey(), entry.getValue())))
                        .isEqualTo(expected.contains(entry.getKey()));
            }
            history.putAll(next);
            previous = next;
        }
    }

    @Test
    void deepTreesAreValidatedIterativelyAndFlatTraversalHasALinearAllocationBound() {
        ComponentTree deep = ComponentTree.empty();
        for (int i = 0; i < 10_000; i++)
            deep = ComponentTree.of(new ComponentTree.Node(ComponentId.of("n"), deep));
        var finalTree = deep;
        assertThatThrownBy(() -> new ScopeManager().prepare(finalTree, 20_000, 64))
                .isInstanceOf(ComponentFailure.class);
        var nodes = new ArrayList<ComponentTree.Node>();
        for (int i = 0; i < 1_000; i++) nodes.add(node("k" + i));
        var tree = new ComponentTree(nodes);
        var scopes = new ScopeManager();
        scopes.commit(scopes.prepare(tree, 1_001, 1));
        long large = AllocationProbe.bytes(() -> scopes.prepare(tree, 1_001, 1));
        assertThat(large).isLessThan(1_000_000);
        var smallTree = new ComponentTree(nodes.subList(0, 250));
        var smallScopes = new ScopeManager();
        smallScopes.commit(smallScopes.prepare(smallTree, 251, 1));
        long small = AllocationProbe.bytes(() -> smallScopes.prepare(smallTree, 251, 1));
        assertThat(large).as("4x the nodes must not allocate quadratically").isLessThan(small * 6);
    }
}
