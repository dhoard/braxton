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

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A hierarchical list with expansion, selection, and lazily supplied children.
 *
 * <p>The immutable widget holds configuration and a pure {@link Children} loader; its {@link Model}
 * owns loaded nodes, expansion, selection, and the window. Keep one widget instance per placement
 * so pointer gestures retain their target.</p>
 *
 * <p>The first expansion loads and caches a branch's children. Collapsed branches are not loaded;
 * re-expansion reuses cached children. The loader runs within reduction over application-owned
 * data. Applications fetch expensive content themselves before supplying it. {@link Msg.Select}
 * selects a known node and opens its ancestors; {@link Msg.Reveal} loads branches along an explicit
 * id path.</p>
 *
 * <p>Ids are unique across loaded nodes. Expanded nodes have loaded children, and selection remains
 * visible. Collapsing a selected descendant chooses its nearest visible ancestor. The window
 * follows selection; a no-op retains the model instance.</p>
 *
 * <p>{@link #defaultKeys()} and {@link #keys()} supply routing and help. Up/down move one row, page
 * keys move one window, and home/end reach the first/last visible row. Right expands or enters a
 * branch; left collapses or moves to its parent. Enter toggles a branch or emits {@link
 * Output.Chosen} for a leaf.</p>
 *
 * <p>A press selects a row, its disclosure marker toggles expansion, and a second press activates
 * the selected row. Blank rows select nothing. The wheel steps selection. Labels flatten breaks and
 * clip horizontally; depth determines indentation. The model's row limit starts at terminal height
 * minus reserved rows and follows resize. Ancestor clipping bounds shorter panes.</p>
 *
 * <p>The live theme supplies muted markers, text labels, selected backgrounds, and focused
 * selection styles. A theme change repaints without changing the model.</p>
 *
 * @see Elements#tree(Tree, Tree.Model, java.util.function.Function)
 */
public final class Tree implements Component<Tree.Model, Tree.Msg, Tree.Output>, Interactive<Tree.Model, Tree.Msg> {

    /**
     * The glyph a default appearance paints for an expanded branch.
     */
    public static final String EXPANDED = "\u25BE";

    /**
     * The glyph a default appearance paints for a collapsed branch.
     */
    public static final String COLLAPSED = "\u25B8";

    /**
     * The glyph a default appearance paints for a leaf, which is the marker column left blank.
     */
    public static final String LEAF = " ";

    /**
     * The columns one depth of nesting adds in a default appearance.
     */
    public static final int INDENT = 2;

    /**
     * The rows a tree paints when it is initialized without a viewport.
     */
    public static final int DEFAULT_ROWS = 10;

    private static final SubscriptionId SIZE = SubscriptionId.of("size");

    private final Children children;
    private final Config config;

    /**
     * A tree with no nodes, loaded on demand.
     *
     * @param children supplies a branch's children; required
     */
    public Tree(Children children) {
        this(children, Config.of(List.of()));
    }

    /**
     * A tree over its configuration and its loader.
     *
     * @param children supplies a branch's children; required
     * @param config the nodes, geometry and appearance; required
     */
    public Tree(Children children, Config config) {
        this.children = Objects.requireNonNull(children, "children");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * A tree over nodes that carry no children of their own.
     *
     * @param nodes the root nodes
     * @return the tree
     */
    public static Tree of(List<Node> nodes) {
        return new Tree(Children.none(), Config.of(nodes));
    }

    /**
     * One node: its identity, its label, and whether it has children.
     *
     * @param id the identity, unique across the tree; never blank
     * @param label the label painted on the node's row; null is empty
     * @param expandable whether the node can be expanded, which is what the {@link Children} function
     *     answers for when the branch is opened
     */
    public record Node(String id, StyledText label, boolean expandable) {

        /**
         * Rejects a node without an id.
         */
        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("node id is required");
            }
            label = label == null ? StyledText.empty() : label;
        }

        /**
         * A node with no children.
         *
         * @param id the identity; never blank
         * @param label the plain label; null is empty
         * @return the node
         */
        public static Node leaf(String id, String label) {
            return new Node(id, StyledText.plain(label), false);
        }

        /**
         * A node with no children and a styled label.
         *
         * @param id the identity; never blank
         * @param label the label; null is empty
         * @return the node
         */
        public static Node leaf(String id, StyledText label) {
            return new Node(id, label, false);
        }

        /**
         * A node whose children the loader supplies.
         *
         * @param id the identity; never blank
         * @param label the plain label; null is empty
         * @return the node
         */
        public static Node branch(String id, String label) {
            return new Node(id, StyledText.plain(label), true);
        }

        /**
         * A node whose children the loader supplies, with a styled label.
         *
         * @param id the identity; never blank
         * @param label the label; null is empty
         * @return the node
         */
        public static Node branch(String id, StyledText label) {
            return new Node(id, label, true);
        }

        /**
         * This node with another label.
         *
         * @param value the label; null is empty
         * @return the node
         */
        public Node withLabel(StyledText value) {
            return new Node(id, value, expandable);
        }

        /**
         * This node with a different answer about its children.
         *
         * @param value whether the node can be expanded
         * @return the node
         */
        public Node withExpandable(boolean value) {
            return new Node(id, label, value);
        }
    }

    /**
     * The children of one node, as data.
     *
     * <p>The tree calls this while it reduces the message that opened a branch and keeps the answer,
     * so an implementation must be a pure function of the id: no I/O, no clock, no shared mutable
     * state. It is called at most once per node per model lineage, and a collision - a child whose id
     * is already in the tree - is rejected and names the repeated id.</p>
     */
    @FunctionalInterface
    public interface Children {

        /**
         * The children of a node.
         *
         * @param id the node's id
         * @return the children, top to bottom; null is treated as none
         */
        List<Node> children(String id);

        /**
         * A loader for a tree that has no lazy branches.
         *
         * @return the loader
         */
        static Children none() {
            return id -> List.of();
        }

        /**
         * A loader over children the application already holds.
         *
         * @param byParent the children of each branch, by node id
         * @return the loader
         */
        static Children from(Map<String, List<Node>> byParent) {
            Map<String, List<Node>> children = byParent == null ? Map.of() : Map.copyOf(byParent);
            return id -> children.getOrDefault(id, List.of());
        }
    }

    /**
     * What a tree paints: the marker glyphs and how far one depth of nesting indents.
     *
     * @param expanded the glyph of an expanded branch; one column wide
     * @param collapsed the glyph of a collapsed branch; one column wide
     * @param leaf the glyph of a leaf, where an empty value leaves the marker cell out; at most one
     *     column wide
     * @param indent the columns one depth of nesting adds; never negative
     */
    public record Appearance(String expanded, String collapsed, String leaf, int indent) {

        /**
         * Substitutes the default glyphs and rejects a glyph that cannot be one cell.
         */
        public Appearance {
            expanded = glyph(expanded, EXPANDED);
            collapsed = glyph(collapsed, COLLAPSED);
            leaf = leaf == null ? LEAF : leaf;
            if (indent < 0) {
                throw new IllegalArgumentException("indent must not be negative");
            }
            requireOneColumn(expanded, "expanded");
            requireOneColumn(collapsed, "collapsed");
            if (TextMeasure.columns(leaf) > 1) {
                throw new IllegalArgumentException("appearance glyph must be at most one column wide: " + leaf);
            }
        }

        /**
         * A triangle for a branch, a blank marker column for a leaf, and two columns per depth.
         *
         * @return the default appearance
         */
        public static Appearance defaults() {
            return new Appearance(EXPANDED, COLLAPSED, LEAF, INDENT);
        }

        /**
         * This appearance with another expanded glyph.
         *
         * @param value the glyph; null keeps the default
         * @return the new appearance
         */
        public Appearance withExpanded(String value) {
            return new Appearance(value, collapsed, leaf, indent);
        }

        /**
         * This appearance with another collapsed glyph.
         *
         * @param value the glyph; null keeps the default
         * @return the new appearance
         */
        public Appearance withCollapsed(String value) {
            return new Appearance(expanded, value, leaf, indent);
        }

        /**
         * This appearance with another leaf glyph.
         *
         * @param value the glyph; null keeps the default, empty leaves the marker cell out
         * @return the new appearance
         */
        public Appearance withLeaf(String value) {
            return new Appearance(expanded, collapsed, value, indent);
        }

        /**
         * This appearance with another indent.
         *
         * @param value the columns one depth adds; never negative
         * @return the new appearance
         */
        public Appearance withIndent(int value) {
            return new Appearance(expanded, collapsed, leaf, value);
        }

        private static String glyph(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }

        private static void requireOneColumn(String glyph, String name) {
            if (TextMeasure.columns(glyph) != 1) {
                throw new IllegalArgumentException("appearance glyph must be one column wide: " + name + "=" + glyph);
            }
        }
    }

    /**
     * One painted row: the node, how deep it sits, and how it reads.
     *
     * @param node the node the row paints
     * @param depth the rows of nesting above it; never negative
     * @param expanded whether the node's children are shown
     * @param selected whether this row is the selection
     * @param focused whether the tree owns focus
     */
    public record Row(Node node, int depth, boolean expanded, boolean selected, boolean focused) {

        /**
         * Rejects a negative depth.
         */
        public Row {
            if (depth < 0) {
                throw new IllegalArgumentException("depth must not be negative");
            }
        }

        /**
         * The column this row's label starts at: the indent, the marker cell, and its separator.
         *
         * @param appearance the appearance the row is painted with; null for the default
         * @return the label's column, counted from the row's first cell
         */
        public int labelColumn(Appearance appearance) {
            Appearance resolved = appearance == null ? Appearance.defaults() : appearance;
            String glyph = expanded ? resolved.expanded() : node.expandable() ? resolved.collapsed() : resolved.leaf();
            return depth * resolved.indent() + TextMeasure.columns(glyph) + 1;
        }
    }

    /**
     * Immutable tree state.
     *
     * <p>The canonical constructor is what keeps the state self-consistent, so every message and every
     * model the application builds obey the same rules: an expanded node is one whose children are
     * loaded, ids are unique across the loaded tree, the selection names a painted row, and the window
     * shows the selection. A node whose id is repeated throws, because a tree whose rows cannot be told
     * apart cannot select one of them.</p>
     *
     * @param nodes the root nodes, top to bottom
     * @param expanded the ids of the branches whose children are shown
     * @param loaded the children each expanded branch loaded, by node id
     * @param selected the id of the selected node, or empty when there is nothing to select
     * @param focused whether the tree owns focus
     * @param offset the first visible row this window paints; clamped into the rows
     * @param rows the rows this window may paint; never negative
     */
    public record Model(
            List<Node> nodes,
            Set<String> expanded,
            Map<String, List<Node>> loaded,
            String selected,
            boolean focused,
            int offset,
            int rows) {

        /**
         * Normalizes the content, resolves the selection and admits the window.
         */
        public Model {
            nodes = TreeGraph.copyNodes(nodes);
            expanded = expanded == null ? Set.of() : Set.copyOf(expanded);
            loaded = TreeGraph.copyChildren(loaded);
            rows = Math.max(0, rows);
            TreeGraph.Walker walker = TreeGraph.walk(nodes, expanded, loaded);
            expanded = walker.expanded();
            loaded = walker.loaded();
            selected = TreeGraph.resolveSelection(walker, selected);
            offset = TreeGraph.admit(walker, selected, Math.max(0, offset), rows);
        }

        /**
         * A tree showing its first row, out of focus.
         *
         * @param nodes the root nodes
         * @return the model
         */
        public static Model of(List<Node> nodes) {
            return new Model(nodes, Set.of(), Map.of(), "", false, 0, DEFAULT_ROWS);
        }

        /**
         * A tree showing its first row, focused or not.
         *
         * @param nodes the root nodes
         * @param focused whether the tree owns focus
         * @return the model
         */
        public static Model of(List<Node> nodes, boolean focused) {
            return new Model(nodes, Set.of(), Map.of(), "", focused, 0, DEFAULT_ROWS);
        }

        /**
         * This tree over other root nodes.
         *
         * @param value the root nodes
         * @return the new model
         */
        public Model withNodes(List<Node> value) {
            return new Model(value, expanded, loaded, selected, focused, offset, rows);
        }

        /**
         * This tree with another selection, which is resolved as the constructor resolves it.
         *
         * @param value the id to select; null selects nothing
         * @return the new model
         */
        public Model withSelection(String value) {
            return new Model(nodes, expanded, loaded, value, focused, offset, rows);
        }

        /**
         * The same tree, focused or not.
         *
         * @param value whether the tree owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(nodes, expanded, loaded, selected, value, offset, rows);
        }

        /**
         * This tree with a window of a different height.
         *
         * @param value the rows the window may paint; never negative
         * @return the new model
         */
        public Model withRows(int value) {
            return new Model(nodes, expanded, loaded, selected, focused, offset, value);
        }

        /**
         * Whether a node's children are shown.
         *
         * @param id the node's id
         * @return whether the node is expanded
         */
        public boolean isExpanded(String id) {
            return id != null && expanded.contains(id);
        }

        /**
         * The node with an id, whether or not it is visible.
         *
         * @param id the id to look for
         * @return the node, or empty when the tree does not hold it
         */
        public Optional<Node> node(String id) {
            if (id == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(
                    TreeGraph.walk(nodes, expanded, loaded).nodes().get(id));
        }
    }

    /**
     * The tree's messages.
     */
    public sealed interface Msg {

        /**
         * Selects the row above the selection.
         */
        record Up() implements Msg {}

        /**
         * Selects the row below the selection.
         */
        record Down() implements Msg {}

        /**
         * Selects the row one window above the selection.
         */
        record PageUp() implements Msg {}

        /**
         * Selects the row one window below the selection.
         */
        record PageDown() implements Msg {}

        /**
         * Selects the first visible row.
         */
        record Home() implements Msg {}

        /**
         * Selects the last visible row.
         */
        record End() implements Msg {}

        /**
         * Expands the selected branch, or steps into it when it is already open.
         */
        record Expand() implements Msg {}

        /**
         * Collapses the selected branch, or steps out to the nearest visible ancestor.
         */
        record Collapse() implements Msg {}

        /**
         * Activates the selection: a branch toggles, a leaf is reported as chosen.
         */
        record Activate() implements Msg {}

        /**
         * Selects a node by id, revealing it through the branches already loaded.
         *
         * @param id the node to select; a node the tree does not hold selects nothing
         */
        record Select(String id) implements Msg {}

        /**
         * Expands or collapses a node by id.
         *
         * @param id the node to toggle; a leaf or an unknown id changes nothing
         */
        record Toggle(String id) implements Msg {}

        /**
         * Opens a path and selects its last element.
         *
         * <p>Each id before the last names a branch that is expanded and, if it has not been opened
         * before, loaded. A path the tree cannot follow - an id it does not hold, or a node that is
         * not a branch - changes nothing, so a wrong path cannot leave the tree half opened.</p>
         *
         * @param path the ids from the root to the node to select
         */
        record Reveal(List<String> path) implements Msg {}

        /**
         * The window is a different height.
         *
         * @param rows the rows the window may paint; never negative
         */
        record RowsChanged(int rows) implements Msg {}

        /**
         * The children of a branch were replaced, so a directory can show what it now holds.
         *
         * <p>The branch keeps whatever expansion it had: a tree that already shows it stays open on
         * the new children, and a tree that has not opened it yet shows them when it does.</p>
         *
         * @param id the node whose children changed; a node the tree does not hold changes nothing
         * @param children the node's children; null is none
         */
        record ChildrenChanged(String id, List<Node> children) implements Msg {}

        /**
         * The tree now holds other root nodes.
         *
         * <p>Branches the new content still holds keep the children they loaded and the expansion
         * they had; everything else is dropped, and the selection falls to the first row when the
         * node it named is gone.</p>
         *
         * @param nodes the new roots; null is empty
         */
        record Replace(List<Node> nodes) implements Msg {}
    }

    /**
     * What the owner should do after an activation.
     */
    public sealed interface Output {

        /**
         * A leaf was activated.
         *
         * @param id the node's id
         * @param node the node itself
         */
        record Chosen(String id, Node node) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How a tree is configured: its nodes, its window and its appearance.
     *
     * @param nodes the root nodes, top to bottom
     * @param rows the rows the window paints when no viewport names a height; never negative
     * @param reservedRows the rows the application paints outside the tree; never negative
     * @param expandedIds the branches that start open, which are loaded during initialization
     * @param appearance the marker glyphs and the indent
     */
    public record Config(List<Node> nodes, int rows, int reservedRows, Set<String> expandedIds, Appearance appearance) {

        /**
         * Substitutes the defaults and rejects a negative height.
         */
        public Config {
            nodes = TreeGraph.copyNodes(nodes);
            if (rows < 0) {
                throw new IllegalArgumentException("rows must not be negative");
            }
            if (reservedRows < 0) {
                throw new IllegalArgumentException("reservedRows must not be negative");
            }
            expandedIds = expandedIds == null ? Set.of() : Set.copyOf(expandedIds);
            appearance = appearance == null ? Appearance.defaults() : appearance;
        }

        /**
         * A configuration over its nodes, with the defaults for everything else.
         *
         * @param nodes the root nodes
         * @return the configuration
         */
        public static Config of(List<Node> nodes) {
            return new Config(nodes, DEFAULT_ROWS, 0, Set.of(), Appearance.defaults());
        }

        /**
         * This configuration over other root nodes.
         *
         * @param value the root nodes
         * @return the new configuration
         */
        public Config withNodes(List<Node> value) {
            return new Config(value, rows, reservedRows, expandedIds, appearance);
        }

        /**
         * This configuration whose window paints a different height without a viewport.
         *
         * @param value the rows; never negative
         * @return the new configuration
         */
        public Config withRows(int value) {
            return new Config(nodes, value, reservedRows, expandedIds, appearance);
        }

        /**
         * This configuration with a different reservation for what the application paints elsewhere.
         *
         * @param value the rows; never negative
         * @return the new configuration
         */
        public Config withReservedRows(int value) {
            return new Config(nodes, rows, value, expandedIds, appearance);
        }

        /**
         * This configuration with branches that start open.
         *
         * @param value the branch ids; null is none
         * @return the new configuration
         */
        public Config withExpandedIds(Set<String> value) {
            return new Config(nodes, rows, reservedRows, value, appearance);
        }

        /**
         * This configuration with another appearance.
         *
         * @param value the appearance; null keeps the default
         * @return the new configuration
         */
        public Config withAppearance(Appearance value) {
            return new Config(nodes, rows, reservedRows, expandedIds, value);
        }
    }

    /**
     * The loader this tree asks for a branch's children.
     *
     * @return the loader this tree asks for a branch's children
     */
    public Children children() {
        return children;
    }

    /**
     * How this tree is configured.
     *
     * @return how this tree is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The tree's element: its painted rows, and the tree itself answering for them.
     *
     * <p>The rows are placed top to bottom, each one row tall, and the tree is a leaf over the whole
     * rectangle so a pointer anywhere in it reaches the tree rather than a row of text.</p>
     *
     * @param model the state to paint; required
     * @return the element, emitting this tree's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The rows a model shows, top to bottom.
     *
     * @param model the state to project; required
     * @return the visible rows
     */
    public static List<Row> visibleRows(Model model) {
        return TreeGraph.rows(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default actions: navigation, the two branch gestures, and activation.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191", "move up", "up"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193", "move down", "down"),
                        KeyAction.<Msg>of("pageup", new Msg.PageUp(), "page up", "move up a page", "pageup"),
                        KeyAction.<Msg>of("pagedown", new Msg.PageDown(), "page down", "move down a page", "pagedown"),
                        KeyAction.<Msg>of("home", new Msg.Home(), "home", "first row", "home"),
                        KeyAction.<Msg>of("end", new Msg.End(), "end", "last row", "end")),
                KeyMap.group(
                        "Branches",
                        KeyAction.<Msg>of("expand", new Msg.Expand(), "right", "expand or step in", "right"),
                        KeyAction.<Msg>of("collapse", new Msg.Collapse(), "left", "collapse or step out", "left")),
                KeyMap.group(
                        "Actions",
                        // A terminal reports Meta+Enter as ESC CR, which the decoder normalizes to
                        // alt+enter; the same alias the selector takes.
                        KeyAction.<Msg>of("activate", new Msg.Activate(), "enter", "activate", "enter", "alt+enter")));
    }

    /**
     * The actions this tree handles.
     *
     * @return the actions this tree handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        // The startup geometry is the only height a tree can know before a resize arrives: what the
        // application paints outside it is taken out of the terminal, and the configured height is
        // the fallback a headless test or a preview uses.
        int height = context.viewport().rows() > 0
                ? Math.max(0, context.viewport().rows() - config.reservedRows())
                : config.rows();
        Model state = new Model(config.nodes(), config.expandedIds(), loadInitially(), "", true, 0, height);
        return Update.next(state);
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        // One source: the terminal's size, so the window follows a resize and its window keys keep
        // stepping by what the user can actually see.
        return Subscription.resize(SIZE, size -> new Msg.RowsChanged(Math.max(0, size.rows() - config.reservedRows())));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The tree's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        keys().actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A tree with no visible row answers nothing, so it takes no turn in the focus walk.
     */
    @Override
    public boolean canFocus(Model model) {
        return !visibleRows(model).isEmpty();
    }

    /**
     * The tree's pointer gestures: a press selects, a marker press toggles, a second press activates,
     * and the wheel steps the selection.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (pointer.isWheel()) {
            if (pointer.button() == PointerButton.WHEEL_UP) {
                emit.accept(new Msg.Up());
            } else if (pointer.button() == PointerButton.WHEEL_DOWN) {
                emit.accept(new Msg.Down());
            }
            return;
        }
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        List<Row> rows = visibleRows(model);
        int painted = Math.max(0, Math.min(model.rows(), rows.size() - model.offset()));
        if (local.row() < 0 || local.row() >= painted) {
            return;
        }
        Row row = rows.get(model.offset() + local.row());
        String id = row.node().id();
        int marker = row.depth() * config.appearance().indent();
        if (row.node().expandable() && local.column() >= marker && local.column() < marker + 1) {
            emit.accept(new Msg.Toggle(id));
            return;
        }
        // The first press of a double click already selected this row, so the second press is the
        // click on an already selected row - which is what activating means here.
        emit.accept(local.clickCount() > 1 && id.equals(model.selected()) ? new Msg.Activate() : new Msg.Select(id));
    }

    /**
     * The painted rows, with the tree itself as the leaf that answers for them.
     */
    private Element<Msg> scene(Model model) {
        List<Row> rows = visibleRows(model);
        TreeRow<Msg> painter = new TreeRow<>(config.appearance());
        int first = Math.max(0, model.offset());
        int last = Math.min(rows.size(), first + model.rows());
        List<Element<Msg>> lines = new ArrayList<>(Math.max(0, last - first));
        for (int index = first; index < last; index++) {
            lines.add(new Element.Leaf<Row, Msg>(painter, rows.get(index), Sizing.fixed(1)));
        }
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Up up -> new Result(step(state, -1), null);
            case Msg.Down down -> new Result(step(state, 1), null);
            case Msg.PageUp pageUp -> new Result(step(state, -pageSize(state)), null);
            case Msg.PageDown pageDown -> new Result(step(state, pageSize(state)), null);
            case Msg.Home home -> new Result(selectIndex(state, 0), null);
            case Msg.End end -> new Result(selectIndex(state, Integer.MAX_VALUE), null);
            case Msg.Expand expand -> new Result(expandSelection(state), null);
            case Msg.Collapse collapse -> new Result(collapseSelection(state), null);
            case Msg.Toggle toggle -> new Result(toggle(state, toggle.id()), null);
            case Msg.Select select -> new Result(select(state, select.id()), null);
            case Msg.Reveal reveal -> new Result(reveal(state, reveal.path()), null);
            case Msg.RowsChanged changed -> new Result(state.withRows(changed.rows()), null);
            case Msg.Replace replaced -> new Result(state.withNodes(replaced.nodes()), null);
            case Msg.ChildrenChanged changed ->
                new Result(replaceChildren(state, changed.id(), changed.children()), null);
            case Msg.Activate activate -> activate(state);
        };
    }

    /**
     * The page step: one window, and at least one row so a zero-height model still moves.
     */
    private static int pageSize(Model state) {
        return Math.max(1, state.rows());
    }

    /**
     * The selection moved by a number of rows, clamped to the rows there are.
     */
    private static Model step(Model state, int delta) {
        List<Row> rows = visibleRows(state);
        if (rows.isEmpty() || delta == 0) {
            return state;
        }
        int index = indexOf(rows, state.selected());
        return selectIndex(state, (index < 0 ? 0 : index) + delta);
    }

    /**
     * The selection moved to a row index, clamped to the rows there are.
     */
    private static Model selectIndex(Model state, int index) {
        List<Row> rows = visibleRows(state);
        if (rows.isEmpty()) {
            return state;
        }
        int target = Math.max(0, Math.min(index, rows.size() - 1));
        String id = rows.get(target).node().id();
        return id.equals(state.selected()) ? state : state.withSelection(id);
    }

    /**
     * The selected node, or null when nothing is selected.
     */
    private static Row selectedRow(Model state) {
        List<Row> rows = visibleRows(state);
        int index = indexOf(rows, state.selected());
        return index < 0 ? null : rows.get(index);
    }

    /**
     * The position of a node in a row list, or -1.
     */
    private static int indexOf(List<Row> rows, String id) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).node().id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * {@code right}: open the selected branch, or step into it when it is already open.
     */
    private Model expandSelection(Model state) {
        List<Row> rows = visibleRows(state);
        int index = indexOf(rows, state.selected());
        if (index < 0) {
            return state;
        }
        Node node = rows.get(index).node();
        if (!node.expandable()) {
            return state;
        }
        if (!state.expanded().contains(node.id())) {
            return expand(state, node);
        }
        // Already open: the first child is where the user is trying to go.
        return index + 1 < rows.size()
                ? state.withSelection(rows.get(index + 1).node().id())
                : state;
    }

    /**
     * {@code left}: close the selected branch, or step out to the nearest visible ancestor.
     */
    private Model collapseSelection(Model state) {
        List<Row> rows = visibleRows(state);
        int index = indexOf(rows, state.selected());
        if (index < 0) {
            return state;
        }
        Row row = rows.get(index);
        if (state.expanded().contains(row.node().id())) {
            return collapse(state, row.node().id());
        }
        for (int previous = index - 1; previous >= 0; previous--) {
            if (rows.get(previous).depth() < row.depth()) {
                return state.withSelection(rows.get(previous).node().id());
            }
        }
        return state;
    }

    /**
     * {@code enter}: a branch toggles, a leaf is chosen.
     */
    private Result activate(Model state) {
        Row row = selectedRow(state);
        if (row == null) {
            return new Result(state, null);
        }
        if (row.node().expandable()) {
            return new Result(toggle(state, row.node().id()), null);
        }
        return new Result(state, new Output.Chosen(row.node().id(), row.node()));
    }

    /**
     * The branch opened, its children loaded if this is the first time.
     */
    private Model expand(Model state, Node node) {
        if (!node.expandable() || state.expanded().contains(node.id())) {
            return state;
        }
        Map<String, List<Node>> loaded = state.loaded();
        if (!loaded.containsKey(node.id())) {
            List<Node> answer = children.children(node.id());
            loaded = new LinkedHashMap<>(loaded);
            loaded.put(node.id(), answer == null ? List.of() : List.copyOf(answer));
        }
        Set<String> expanded = new LinkedHashSet<>(state.expanded());
        expanded.add(node.id());
        return new Model(
                state.nodes(), expanded, loaded, state.selected(), state.focused(), state.offset(), state.rows());
    }

    /**
     * The branch closed. Its loaded children stay, so opening it again costs nothing.
     */
    private Model collapse(Model state, String id) {
        if (!state.expanded().contains(id)) {
            return state;
        }
        Set<String> expanded = new LinkedHashSet<>(state.expanded());
        expanded.remove(id);
        return new Model(
                state.nodes(),
                expanded,
                state.loaded(),
                state.selected(),
                state.focused(),
                state.offset(),
                state.rows());
    }

    /**
     * A node's branch opened or closed, and nothing else.
     */
    private Model toggle(Model state, String id) {
        if (id == null || id.isBlank()) {
            return state;
        }
        Node node = state.node(id).orElse(null);
        if (node == null || !node.expandable()) {
            return state;
        }
        return state.expanded().contains(id) ? collapse(state, id) : expand(state, node);
    }

    /**
     * A node selected by id, through the branches the tree has already loaded.
     */
    private Model select(Model state, String id) {
        if (id == null || id.isBlank() || state.node(id).isEmpty()) {
            return state;
        }
        Model revealed = state;
        for (String ancestor : TreeGraph.ancestors(state, id)) {
            Node node = revealed.node(ancestor).orElse(null);
            if (node != null) {
                revealed = expand(revealed, node);
            }
        }
        return revealed.withSelection(id);
    }

    /**
     * A path opened branch by branch and its last node selected.
     */
    private Model reveal(Model state, List<String> path) {
        if (path == null || path.isEmpty()) {
            return state;
        }
        Model current = state;
        for (int index = 0; index < path.size(); index++) {
            String id = path.get(index);
            Node node = current.node(id).orElse(null);
            if (node == null) {
                // A path the tree cannot follow leaves it exactly as it was, so a wrong path cannot
                // half open a tree.
                return state;
            }
            if (index < path.size() - 1) {
                if (!node.expandable()) {
                    return state;
                }
                current = expand(current, node);
            }
            current = current.withSelection(id);
        }
        return current;
    }

    /**
     * A node's own children replaced, so a directory can show what it now holds.
     */
    private Model replaceChildren(Model state, String id, List<Node> replacement) {
        if (id == null || id.isBlank() || state.node(id).isEmpty()) {
            return state;
        }
        Map<String, List<Node>> loaded = new LinkedHashMap<>(state.loaded());
        loaded.put(id, replacement == null ? List.of() : List.copyOf(replacement));
        return new Model(
                state.nodes(),
                state.expanded(),
                loaded,
                state.selected(),
                state.focused(),
                state.offset(),
                state.rows());
    }

    /**
     * The branches that start open, loaded so their children are shown.
     */
    private Map<String, List<Node>> loadInitially() {
        Map<String, List<Node>> loaded = new LinkedHashMap<>();
        loadInitially(config.nodes(), loaded);
        return loaded;
    }

    private void loadInitially(List<Node> nodes, Map<String, List<Node>> loaded) {
        for (Node node : nodes) {
            if (node == null || !node.expandable() || !config.expandedIds().contains(node.id())) {
                continue;
            }
            List<Node> answer = children.children(node.id());
            List<Node> kids = answer == null ? List.of() : List.copyOf(answer);
            loaded.put(node.id(), kids);
            loadInitially(kids, loaded);
        }
    }
}
