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

package io.github.dhoard.braxton.ui;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Focus, key routing and pointer state over an {@link ElementTree} placement.
 *
 * <p>Focus is model state, not router state. A widget says whether it owns focus through
 * {@link Widget#focused(Object)}, so an application moves focus by setting that field in its own
 * model, exactly as it moves any other piece of state, and the router only reads the answer. That
 * is why there is no focus index to keep in step with the tree: the tree is rebuilt from the model
 * every frame, and the model is the only thing that can say what is focused. {@link #focusables}
 * reports the order a Tab walk goes in, so an application that binds Tab needs one line to advance
 * its own focus field.</p>
 *
 * <p>A gesture then reaches the focused leaf and nowhere else. A widget consumes a gesture by
 * emitting at least one message for it; a gesture the focused widget stays silent about belongs to
 * the application, which receives the same key as an ordinary message. There is no capture chain,
 * no bubble chain and no interceptor to agree on, so precedence is a property of the two writers of
 * state - the focused widget and the application - rather than of the shape of the tree.</p>
 *
 * <p>Pointer state is the one thing the router does keep: a press has to outlive the frame that
 * painted the cell it landed on, so a drag that leaves the widget still belongs to it.</p>
 *
 * <p>Three tiers answer a gesture, in order. A gesture the application <em>claims</em> is the
 * application's before it is the tree's, which is what a global shortcut or a modal trap needs: no
 * focused field may swallow it. Then the tree answers - the widget that owns focus first, and only
 * what it declines falls through - which is the one key path. Then the application's own bindings,
 * matched last, so an ordinary shortcut never takes a key away from the field being edited.</p>
 */
public final class InteractionRouter {

    private InteractionRouter() {}

    /**
     * How long a press may follow the previous one at the same cell and still be a double click.
     */
    public static final long DOUBLE_CLICK_NANOS = TimeUnit.MILLISECONDS.toNanos(400L);

    /**
     * Pointer state: which widget the pointer is over, which one owns the gesture, and the press the
     * next one is compared against.
     *
     * <p>The widgets are identities rather than positions, so a capture survives the frame that
     * repainted the tree: the widget is found again in the newest placement, with its newest model
     * and its newest rectangle, and a widget that is gone - unmounted, or covered by a layer that now
     * owns the screen - ends the gesture rather than handing it to whatever moved into its place.</p>
     *
     * @param hovered the widget the pointer is over, or null
     * @param pressed the widget the press captured, or null
     * @param lastPressAt when the last press arrived, or zero when none has
     * @param lastPressRow the row of the last press
     * @param lastPressColumn the column of the last press
     * @param lastPressButton the button of the last press
     */
    public record Interaction(
            Widget<?, ?> hovered,
            Widget<?, ?> pressed,
            long lastPressAt,
            int lastPressRow,
            int lastPressColumn,
            PointerButton lastPressButton) {

        /**
         * No pointer over the tree and none captured.
         */
        public static final Interaction NONE = new Interaction(null, null, 0L, -1, -1, PointerButton.NONE);

        /**
         * Rejects a missing button.
         */
        public Interaction {
            lastPressButton = lastPressButton == null ? PointerButton.NONE : lastPressButton;
        }

        /**
         * Pointer state with no press remembered yet.
         *
         * @param hovered the widget the pointer is over, or null
         * @param pressed the widget that owns the gesture, or null
         */
        public Interaction(Widget<?, ?> hovered, Widget<?, ?> pressed) {
            this(hovered, pressed, 0L, -1, -1, PointerButton.NONE);
        }
    }

    /**
     * All leaves in paint order.
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @return all leaves in paint order
     */
    public static <Msg> List<Element.Leaf<?, Msg>> leaves(ElementTree.Placement<Msg> placement) {
        List<Element.Leaf<?, Msg>> result = new ArrayList<>();
        collect(placement, result);
        return List.copyOf(result);
    }

    private static <Msg> void collect(ElementTree.Placement<Msg> placement, List<Element.Leaf<?, Msg>> result) {
        // A modal child owns its scope: everything else this placement holds is covered by it, so a
        // covered leaf is neither a hit target nor a stop on the focus walk.
        for (ElementTree.Placement<Msg> child : placement.children()) {
            if (child.element() instanceof Element.Modal<Msg>) {
                collect(child, result);
                return;
            }
        }
        for (ElementTree.Placement<Msg> child : placement.children()) collect(child, result);
        if (placement.element() instanceof Element.Leaf<?, Msg> leaf) result.add(leaf);
    }

    /**
     * The leaf that owns focus in an element tree, or null when nothing does.
     *
     * <p>Focus is model data, so the answer needs no geometry: it is the same answer a resolved
     * tree gives. A modal child owns its scope, so a leaf a layer covers is not reported however
     * deeply it is nested.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree
     * @return the focused leaf, or null
     */
    public static <Msg> Element.Leaf<?, Msg> focused(Element<Msg> root) {
        if (root == null) return null;
        for (Element<Msg> child : root.children()) {
            if (child instanceof Element.Modal<Msg>) {
                // A modal child owns its scope: everything else the tree holds is covered by it.
                return focused(child);
            }
        }
        List<Element<Msg>> children = root.children();
        for (int index = children.size() - 1; index >= 0; index--) {
            Element.Leaf<?, Msg> found = focused(children.get(index));
            if (found != null) return found;
        }
        return root instanceof Element.Leaf<?, Msg> leaf && leafReportsFocus(leaf) ? leaf : null;
    }

    /**
     * Whether any leaf of the tree owns focus.
     *
     * <p>This is what a composition point asks when it has to decide whether the key belongs to
     * the subtree it holds or to the application above it.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree
     * @return true when a leaf reports focus
     */
    public static <Msg> boolean ownsFocus(Element<Msg> root) {
        return focused(root) != null;
    }

    /**
     * Delivers a key gesture to the focused leaf and reports whether that leaf answered it.
     *
     * <p>The tree is read where focus lives - in the models - so a caller that holds an element
     * tree but no geometry can still route a key.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree
     * @param key the key gesture
     * @param emit where the widget's messages go
     * @return true when the focused widget answered the gesture
     */
    public static <Msg> boolean key(Element<Msg> root, InputEvent.KeyInput key, Consumer<Msg> emit) {
        if (root == null || key == null) return false;
        Element.Leaf<?, Msg> leaf = focused(root);
        if (leaf == null) return false;
        boolean[] answered = {false};
        leafWidgetKey(leaf, key, message -> {
            answered[0] = true;
            emit.accept(message);
        });
        return answered[0];
    }

    private static <M, Msg> boolean leafReportsFocus(Element.Leaf<M, Msg> leaf) {
        return leaf.widget().focused(leaf.model());
    }

    /**
     * The leaves that can take focus, in the order a focus walk moves through them.
     *
     * @param <Msg> the message type
     * @param placement the committed layout placement
     * @return the leaves that can take focus, in the order a focus walk moves through them
     */
    public static <Msg> List<Element.Leaf<?, Msg>> focusables(ElementTree.Placement<Msg> placement) {
        List<Element.Leaf<?, Msg>> result = new ArrayList<>();
        for (Element.Leaf<?, Msg> leaf : leaves(placement)) {
            if (canFocus(leaf)) result.add(leaf);
        }
        return List.copyOf(result);
    }

    /**
     * The placements of the leaves that can take focus, in the order a focus walk moves through
     * them.
     *
     * <p>This is {@link #focusables} with the geometry a Tab walk or a test needs: the rectangle
     * each stop occupies, so an application can move its own focus field to the next one.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param placement the resolved tree
     * @return the focusable placements, in focus order
     */
    public static <Msg> List<ElementTree.Placement<Msg>> focusablePlacements(ElementTree.Placement<Msg> placement) {
        List<ElementTree.Placement<Msg>> result = new ArrayList<>();
        for (Element.Leaf<?, Msg> leaf : focusables(placement)) {
            ElementTree.Placement<Msg> found = placementOf(placement, leaf);
            if (found != null) result.add(found);
        }
        return List.copyOf(result);
    }

    /**
     * The leaf that owns focus, or null when nothing does.
     *
     * <p>The topmost leaf whose widget reports focus wins, and a modal layer hides everything under
     * it: the answer is read from the same scope a pointer report is hit-tested in, so keys and
     * clicks never disagree about which layer is live.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param placement the resolved tree
     * @return the focused leaf, or null
     */
    public static <Msg> Element.Leaf<?, Msg> focused(ElementTree.Placement<Msg> placement) {
        List<Element.Leaf<?, Msg>> focused = ElementTree.focused(placement);
        return focused.isEmpty() ? null : focused.get(0);
    }

    /**
     * Delivers a key gesture to the focused leaf and reports whether that leaf answered it.
     *
     * @param <Msg> the message type the tree emits
     * @param placement the resolved tree
     * @param key the key gesture
     * @param emit where the widget's messages go
     * @return true when the focused widget answered the gesture
     */
    public static <Msg> boolean key(ElementTree.Placement<Msg> placement, InputEvent.KeyInput key, Consumer<Msg> emit) {
        Element.Leaf<?, Msg> leaf = focused(placement);
        if (leaf == null) return false;
        boolean[] answered = {false};
        leafWidgetKey(leaf, key, message -> {
            answered[0] = true;
            emit.accept(message);
        });
        return answered[0];
    }

    private static <M, Msg> void leafWidgetKey(Element.Leaf<M, Msg> leaf, InputEvent.KeyInput key, Consumer<Msg> emit) {
        leaf.widget().onKey(key, leaf.model(), emit);
    }

    /**
     * Delivers an input event that is not a key gesture to the focused leaf and reports whether that
     * leaf answered it.
     *
     * <p>A paste is the event this exists for: a focused widget that edits text sees the text as
     * text and never as the gestures it happens to spell.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree
     * @param event the event to deliver
     * @param emit where the widget's messages go
     * @return true when the focused widget emitted at least one message
     */
    public static <Msg> boolean input(Element<Msg> root, InputEvent event, Consumer<Msg> emit) {
        if (root == null || event == null) return false;
        Element.Leaf<?, Msg> leaf = focused(root);
        if (leaf == null) return false;
        boolean[] answered = {false};
        leafWidgetInput(leaf, event, message -> {
            answered[0] = true;
            emit.accept(message);
        });
        return answered[0];
    }

    private static <M, Msg> void leafWidgetInput(Element.Leaf<M, Msg> leaf, InputEvent event, Consumer<Msg> emit) {
        leaf.widget().onInput(event, leaf.model(), emit);
    }

    /**
     * The cursor the focused leaf places, or null when nothing is focused or the widget asks for no
     * caret.
     *
     * <p>The answer has to sit inside the rectangle the widget was arranged in: a caret outside the
     * cells the widget painted belongs to nothing the user can see, so it is not presented rather
     * than pinned to an edge.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param placement the resolved tree
     * @return the cursor to present, or null
     */
    public static <Msg> Cursor cursor(ElementTree.Placement<Msg> placement) {
        Element.Leaf<?, Msg> leaf = focused(placement);
        if (leaf == null) return null;
        ElementTree.Placement<Msg> focused = placementOf(placement, leaf);
        if (focused == null) return null;
        Cursor cursor = leafWidgetCursor(leaf, focused.bounds());
        if (cursor == null || !cursor.visible() || !focused.bounds().contains(cursor.row(), cursor.column())) {
            return null;
        }
        return cursor;
    }

    /**
     * The placement of one leaf of the tree, or null when the leaf is not in it.
     */
    private static <Msg> ElementTree.Placement<Msg> placementOf(
            ElementTree.Placement<Msg> placement, Element.Leaf<?, Msg> leaf) {
        for (ElementTree.Placement<Msg> child : placement.children()) {
            ElementTree.Placement<Msg> found = placementOf(child, leaf);
            if (found != null) return found;
        }
        return placement.element() == leaf ? placement : null;
    }

    private static <M, Msg> Cursor leafWidgetCursor(Element.Leaf<M, Msg> leaf, Rect bounds) {
        return leaf.widget().cursor(bounds, leaf.model());
    }

    /**
     * Routes one event with no application bindings.
     *
     * <p>This is the whole path for a component that declares neither claims nor bindings: the tree
     * answers, and a gesture it declines reaches nobody.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree the frame was built from
     * @param event the event to route
     * @return the messages the event produced, in emission order
     */
    public static <Msg> List<Msg> route(Element<Msg> root, InputEvent event) {
        return route(root, event, List.of(), List.of());
    }

    /**
     * Routes one event and returns the single message it produced, if any.
     *
     * <p>A widget can answer one gesture with several messages - a key can move a selection and
     * report it - so {@link #route} is the whole answer and this is the convenience for the caller
     * that is asking which message the gesture produced.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree the frame was built from
     * @param event the event to route
     * @return the first message the event produced, or empty when nothing answered it
     */
    public static <Msg> java.util.Optional<Msg> message(Element<Msg> root, InputEvent event) {
        List<Msg> produced = route(root, event);
        return produced.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(produced.get(0));
    }

    /**
     * Routes one event through the three tiers and returns every message it produced.
     *
     * <p>A claimed gesture is answered by the application before the tree sees it; otherwise the
     * focused widget answers, and a gesture it declines is matched against the application's own
     * bindings. Only an activating gesture runs any of it: a key release reports the press's key and
     * text but is not that press, so nothing a key does on the way down runs on the way up.</p>
     *
     * <p>A gesture that is not a key reaches the focused widget as itself rather than as a match: a
     * paste is text to insert, never the bindings its characters happen to spell, and a pointer
     * report is routed by geometry instead - see {@link #pointer}.</p>
     *
     * @param <Msg> the message type the tree emits
     * @param root the element tree the frame was built from
     * @param event the event to route
     * @param claims the application's claimed gestures, matched before the tree
     * @param application the application's own bindings, matched last
     * @return the messages the event produced, in emission order; empty when nothing answered it
     */
    public static <Msg> List<Msg> route(
            Element<Msg> root, InputEvent event, List<Binding<Msg>> claims, List<Binding<Msg>> application) {
        if (root == null || event == null) {
            return List.of();
        }
        boolean activating = !(event instanceof InputEvent.KeyInput key) || key.activates();
        if (!activating) {
            return List.of();
        }
        if (event instanceof InputEvent.KeyInput keyInput && claims != null && !claims.isEmpty()) {
            Msg claimed = match(claims, keyInput.key());
            if (claimed != null) {
                return List.of(claimed);
            }
        }
        List<Msg> produced = new ArrayList<>(1);
        boolean answered;
        if (event instanceof InputEvent.KeyInput keyInput) {
            answered = key(root, keyInput, produced::add);
        } else if (event instanceof InputEvent.PointerInput) {
            // A report is hit-tested against the painted frame, not matched here.
            answered = false;
        } else {
            answered = input(root, event, produced::add);
        }
        if (answered) {
            return List.copyOf(produced);
        }
        if (event instanceof InputEvent.KeyInput keyInput && application != null && !application.isEmpty()) {
            Msg own = match(application, keyInput.key());
            if (own != null) {
                return List.of(own);
            }
        }
        return List.of();
    }

    /**
     * The first binding that matches {@code key}, or null.
     */
    private static <Msg> Msg match(List<Binding<Msg>> bindings, Key key) {
        for (Binding<Msg> binding : bindings) {
            if (binding.matches(key)) {
                return binding.message();
            }
        }
        return null;
    }

    /**
     * Routes a pointer gesture to the hit leaf and returns the new pointer state.
     *
     * <p>A press captures the widget it landed on. The motion and the release that follow belong to
     * that widget until the release arrives, wherever the pointer has moved: dragging a selection
     * past the edge of a pane keeps extending it, and a drag that crosses into another widget does
     * not hand the gesture over half way through. The rectangle a captured widget is handed is its
     * own, so a cell outside it maps to the nearest row or cluster the way the widget already does
     * for a drag that leaves the pane.</p>
     *
     * <p>Hover still follows the pointer: {@link Interaction#hovered()} is what is under it now,
     * which is what a highlight is drawn from, while {@link Interaction#pressed()} is who owns the
     * gesture. The second press of a gesture is handed over as a press with a click count of two, so
     * a widget can select a word or open an entry without counting time itself.</p>
     *
     * @param <Msg> the message type
     * @param emit the consumer of typed messages
     * @param placement the committed layout placement
     * @param pointer the normalized pointer report
     * @param state the current immutable state
     * @return routes a pointer gesture to the hit leaf and returns the new pointer state
     */
    public static <Msg> Interaction pointer(
            ElementTree.Placement<Msg> placement, Interaction state, PointerEvent pointer, Consumer<Msg> emit) {
        PointerEvent event = pointer.isPress() ? count(pointer, state) : pointer;
        List<Element.Leaf<?, Msg>> all = leaves(placement);
        ElementTree.Placement<Msg> hit = ElementTree.hitPlacement(placement, event.row(), event.column());
        Element.Leaf<?, Msg> hovered = hit == null ? null : leafOf(hit);
        if (hovered != null && !enabled(hovered)) {
            hovered = null;
        }
        Element.Leaf<?, Msg> captured = null;
        boolean captureLost = false;
        if (event.isMotion() || event.isRelease()) {
            if (state.pressed() == null) {
                captured = null;
            } else {
                captured = leafOf(all, state.pressed());
                // The widget that owns the gesture is gone - unmounted, or a layer opened over it.
                // The gesture ends with it rather than being handed to whatever moved into its
                // place, so the report reaches nobody.
                captureLost = captured == null;
            }
        }
        Element.Leaf<?, Msg> target = captureLost ? null : captured != null ? captured : hovered;
        if (target != null) {
            ElementTree.Placement<Msg> bounds = placementOf(placement, target);
            if (bounds != null) {
                leafWidgetPointer(target, event, bounds.bounds(), emit);
            }
        }
        Widget<?, ?> pressed;
        if (event.isRelease() || captureLost) {
            pressed = null;
        } else if (captured != null) {
            pressed = state.pressed();
        } else {
            pressed = hovered == null ? null : identity(hovered.widget());
        }
        return new Interaction(
                hovered == null ? null : identity(hovered.widget()),
                pressed,
                event.isPress() ? event.capturedAtNanos() : state.lastPressAt(),
                event.isPress() ? event.row() : state.lastPressRow(),
                event.isPress() ? event.column() : state.lastPressColumn(),
                event.isPress() ? event.button() : state.lastPressButton());
    }

    /**
     * The press as the gesture it is: a press that repeats the previous one is a double click.
     */
    private static PointerEvent count(PointerEvent event, Interaction state) {
        return event.withClickCount(repeats(event, state) ? 2 : 1);
    }

    /**
     * Whether a press repeats the previous one: same button, same cell, inside the window.
     *
     * <p>An unstamped time cannot repeat anything, so a caller that does not stamp its reports gets
     * single clicks rather than clicks it never made.</p>
     */
    private static boolean repeats(PointerEvent event, Interaction state) {
        if (event.capturedAtNanos() == 0L || state.lastPressAt() == 0L) {
            return false;
        }
        return event.button() == state.lastPressButton()
                && event.row() == state.lastPressRow()
                && event.column() == state.lastPressColumn()
                && event.capturedAtNanos() - state.lastPressAt() <= DOUBLE_CLICK_NANOS
                && event.capturedAtNanos() >= state.lastPressAt();
    }

    /**
     * The leaf of a placement that is itself a leaf, or null.
     */
    @SuppressWarnings("unchecked")
    private static <Msg> Element.Leaf<?, Msg> leafOf(ElementTree.Placement<Msg> placement) {
        return placement.element() instanceof Element.Leaf<?, Msg> leaf ? leaf : null;
    }

    /**
     * The leaf of {@code leaves} whose widget is {@code widget}, or null when it is gone.
     */
    private static <Msg> Element.Leaf<?, Msg> leafOf(List<Element.Leaf<?, Msg>> leaves, Widget<?, ?> widget) {
        if (widget == null) {
            return null;
        }
        for (Element.Leaf<?, Msg> leaf : leaves) {
            if (identity(leaf.widget()) == widget) {
                return leaf;
            }
        }
        return null;
    }

    /**
     * The widget an interaction identity belongs to.
     *
     * <p>A leaf a mount composed is wrapped in a fresh message-mapping wrapper every frame, so the
     * wrapper cannot be the identity a capture is kept by: the widget it lifts is the component
     * that answers, and it is the same object in every frame it appears in.</p>
     */
    private static Widget<?, ?> identity(Widget<?, ?> widget) {
        Widget<?, ?> current = widget;
        while (current instanceof Mapped<?, ?, ?> mapped) {
            current = mapped.lifted();
        }
        return current;
    }

    private static <M, Msg> void leafWidgetPointer(
            Element.Leaf<M, Msg> leaf, PointerEvent pointer, Rect bounds, Consumer<Msg> emit) {
        leaf.widget().onPointer(pointer, bounds, leaf.model(), emit);
    }

    private static <M, Msg> boolean canFocus(Element.Leaf<M, Msg> leaf) {
        return leaf.widget().canFocus(leaf.model()) && enabled(leaf);
    }

    private static <M, Msg> boolean enabled(Element.Leaf<M, Msg> leaf) {
        return !leaf.widget().state(leaf.model()).disabled();
    }
}
