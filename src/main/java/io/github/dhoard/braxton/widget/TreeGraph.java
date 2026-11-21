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

package io.github.dhoard.braxton.widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The traversal behind a {@link Tree}: what a model holds, what it shows, and where the window is.
 *
 * <p>A tree's content is a set of roots plus the children each opened branch loaded, which is a graph
 * rather than a list: a node's rows are only part of what matters about it. This class is the one
 * place that walks it, so the model's invariants, the projection the view paints, and the reductions
 * that move the selection all read the same answer - the visible rows, every id the tree holds, and
 * the parent of each.</p>
 *
 * <p>Nodes under a closed branch are walked but not shown: their ids and parents still matter while
 * their rows do not, which is what lets a selection walk out of a branch that closed and lets
 * {@link Tree.Msg.Select} reveal a node the tree already loaded. Repeating an id is rejected
 * here, once, for every entry point.</p>
 */
final class TreeGraph {

    private TreeGraph() {}

    /**
     * One node of a walk, with the depth it was reached at.
     */
    record Entry(Tree.Node node, int depth) {}

    /**
     * Everything a model holds, in the order it is painted.
     *
     * @param nodes the roots
     * @param expanded the ids the model names as open
     * @param loaded the children each branch loaded
     * @return the walk
     */
    static Walker walk(List<Tree.Node> nodes, Set<String> expanded, Map<String, List<Tree.Node>> loaded) {
        Walker walker = new Walker(expanded, loaded);
        walker.visit(nodes, 0, null, true);
        return walker;
    }

    /**
     * The rows a model shows, top to bottom.
     *
     * @param model the state to project
     * @return the visible rows
     */
    static List<Tree.Row> rows(Tree.Model model) {
        Walker walker = walk(model.nodes(), model.expanded(), model.loaded());
        List<Tree.Row> rows = new ArrayList<>(walker.visible().size());
        for (Entry entry : walker.visible()) {
            String id = entry.node().id();
            rows.add(new Tree.Row(
                    entry.node(),
                    entry.depth(),
                    model.expanded().contains(id),
                    id.equals(model.selected()),
                    model.focused()));
        }
        return List.copyOf(rows);
    }

    /**
     * The ancestors of a node, outermost first, from the content the tree already holds.
     *
     * @param model the state to read
     * @param id the node's id
     * @return the ancestor ids
     */
    static List<String> ancestors(Tree.Model model, String id) {
        Walker walker = walk(model.nodes(), model.expanded(), model.loaded());
        List<String> chain = new ArrayList<>();
        for (String cursor = walker.parents().get(id);
                cursor != null;
                cursor = walker.parents().get(cursor)) {
            chain.add(0, cursor);
        }
        return chain;
    }

    /**
     * The selection a model's content admits: a painted row, or none when nothing is painted.
     *
     * @param walker the content the model holds
     * @param selected the selection the model was given
     * @return the selection to use
     */
    static String resolveSelection(Walker walker, String selected) {
        List<Entry> visible = walker.visible();
        if (visible.isEmpty()) {
            return "";
        }
        String first = visible.get(0).node().id();
        if (selected == null || selected.isBlank()) {
            return first;
        }
        if (contains(visible, selected)) {
            return selected;
        }
        if (!walker.nodes().containsKey(selected)) {
            // A selection that names no node at all falls to the first row, the way a list does when
            // the item it selected is gone.
            return first;
        }
        // The node is in a closed branch: the innermost branch still on screen inherits the
        // selection, so collapsing a branch can never leave the selection invisible.
        for (String cursor = walker.parents().get(selected);
                cursor != null;
                cursor = walker.parents().get(cursor)) {
            if (contains(visible, cursor)) {
                return cursor;
            }
        }
        return first;
    }

    /**
     * The admitted window: inside the rows, and over the selection when it can be.
     *
     * @param walker the content the model holds
     * @param selected the resolved selection
     * @param offset the window the model was given
     * @param rows the rows the window may paint
     * @return the first row the window paints
     */
    static int admit(Walker walker, String selected, int offset, int rows) {
        List<Entry> visible = walker.visible();
        int index = -1;
        for (int at = 0; at < visible.size(); at++) {
            if (visible.get(at).node().id().equals(selected)) {
                index = at;
                break;
            }
        }
        return RowWindow.admit(index, visible.size(), offset, rows);
    }

    /**
     * The roots, as an immutable list.
     *
     * @param nodes the roots the caller gave
     * @return the copied roots
     */
    static List<Tree.Node> copyNodes(List<Tree.Node> nodes) {
        if (nodes == null) {
            return List.of();
        }
        List<Tree.Node> copy = new ArrayList<>(nodes.size());
        for (Tree.Node node : nodes) {
            copy.add(Objects.requireNonNull(node, "node"));
        }
        return List.copyOf(copy);
    }

    /**
     * The loaded children, as an immutable map of immutable lists.
     *
     * @param loaded the children the caller gave
     * @return the copied children
     */
    static Map<String, List<Tree.Node>> copyChildren(Map<String, List<Tree.Node>> loaded) {
        if (loaded == null) {
            return Map.of();
        }
        Map<String, List<Tree.Node>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Tree.Node>> entry : loaded.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            copy.put(entry.getKey(), copyNodes(entry.getValue()));
        }
        return copy;
    }

    /**
     * Whether a row list holds a node.
     */
    private static boolean contains(List<Entry> visible, String id) {
        for (Entry entry : visible) {
            if (entry.node().id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A traversal of everything a model holds, with the visible rows and the parents of each id.
     */
    static final class Walker {

        private final Set<String> expandedIds;
        private final Map<String, List<Tree.Node>> loadedIds;
        private final List<Entry> visible = new ArrayList<>();
        private final Map<String, Tree.Node> nodes = new LinkedHashMap<>();
        private final Map<String, String> parents = new LinkedHashMap<>();
        private final Set<String> keptExpanded = new LinkedHashSet<>();
        private final Map<String, List<Tree.Node>> keptLoaded = new LinkedHashMap<>();

        Walker(Set<String> expanded, Map<String, List<Tree.Node>> loaded) {
            this.expandedIds = expanded;
            this.loadedIds = loaded;
        }

        void visit(List<Tree.Node> children, int depth, String parent, boolean shown) {
            for (Tree.Node node : children) {
                if (node == null) {
                    continue;
                }
                String id = node.id();
                if (nodes.putIfAbsent(id, node) != null) {
                    throw new IllegalArgumentException("duplicate node id: " + id);
                }
                if (parent != null) {
                    parents.put(id, parent);
                }
                List<Tree.Node> loaded = loadedIds.get(id);
                if (loaded != null) {
                    keptLoaded.put(id, loaded);
                }
                // Only a branch whose children are loaded can be shown open: an expanded id without
                // the children it names is a state the tree cannot paint.
                boolean open = node.expandable() && expandedIds.contains(id) && loaded != null;
                if (open) {
                    keptExpanded.add(id);
                }
                if (shown) {
                    visible.add(new Entry(node, depth));
                }
                if (loaded != null && !loaded.isEmpty()) {
                    visit(loaded, depth + 1, id, shown && open);
                }
            }
        }

        List<Entry> visible() {
            return visible;
        }

        Map<String, Tree.Node> nodes() {
            return nodes;
        }

        Map<String, String> parents() {
            return parents;
        }

        Set<String> expanded() {
            return keptExpanded;
        }

        Map<String, List<Tree.Node>> loaded() {
            return keptLoaded;
        }
    }
}
