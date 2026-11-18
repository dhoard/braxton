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

package io.github.dhoard.braxton.internal.input;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic input routing.
 *
 * <pre>
 *   topmost modal scope
 *     → ancestor capture bindings (outer → inner)
 *     → focused element bindings
 *     → focused element input handler
 *     → ancestor bubble bindings (inner → outer)
 * </pre>
 *
 * <p>The first consumer ends routing, so a consumed shortcut never also inserts text. Precedence is
 * a property of the view tree, not of registration order: there is nothing to register. When no
 * element is focused, only the scope's own bindings can consume an event: a focusable element that
 * does not own focus contributes neither its local bindings nor its input handler, even when that
 * element is the scope itself (a single-widget view used as the root, or as the topmost modal
 * layer).</p>
 *
 * <p>Only an activating key event - a press or a repeat - runs bindings, a focused element's input
 * handler, or text insertion. A key release carries the same key, modifiers and associated text as
 * the press it ends, so deciding by kind is what keeps a key-up from firing a shortcut or typing a
 * second character. Releases and the terminal's own notifications reach the ancestor interceptors,
 * which are the full-event hook; they are enough for an application that wants to observe them.
 * </p>
 */
public final class InputRouter {

    private InputRouter() {}

    /** Routes one event, returning the message produced by the first consumer. */
    public static <Msg> Optional<Msg> route(View<Msg> root, InputEvent event) {
        if (root == null || event == null) {
            return Optional.empty();
        }
        // Presentation wrappers reserve nothing, paint nothing of their own and consume nothing, so
        // routing starts inside them: a root that declares its terminal intent around the scene is
        // still the scene as far as a key is concerned.
        View<Msg> scope = modalScope(unwrap(root));
        List<View<Msg>> path = focusPath(scope);
        if (path == null) {
            path = List.of(scope);
        }
        // A release reports the press's content but is not that press; nothing a key does on the
        // way down may run on the way up.
        boolean activating = !(event instanceof InputEvent.KeyInput key) || key.activates();

        if (event instanceof InputEvent.KeyInput keyInput && activating) {
            Optional<Msg> captured = matchCapture(path, keyInput.key());
            if (captured.isPresent()) {
                return captured;
            }
        }

        Optional<Msg> intercepted = matchInterceptors(path, event);
        if (intercepted.isPresent()) {
            return intercepted;
        }

        View.Focused<Msg> focused = focusedNode(path);
        if (focused != null && activating) {
            if (event instanceof InputEvent.KeyInput keyInput) {
                Optional<Msg> local = match(focused.bindings(), keyInput.key());
                if (local.isPresent()) {
                    return local;
                }
            }
            Optional<Msg> handled = focused.input().handle(event);
            if (handled.isPresent()) {
                return handled;
            }
        }

        if (activating) {
            Optional<Msg> bubble = matchBubble(path, event);
            if (bubble.isPresent()) {
                return bubble;
            }
        }
        return Optional.empty();
    }

    /**
     * The path a pointer report's observers are offered it on.
     *
     * <p>A report that hit an element is observed from the root of the tree down to that element, so
     * an observer wrap around the whole application sees pointer input wherever it lands - including
     * inside a modal layer, which is a route of its own for keys but not for a report that has
     * already been hit-tested. A report that hit nothing is observed from the root down to the
     * topmost modal scope, and then along that scope's focus path, which is where a key with nothing
     * focused would go.</p>
     *
     * @param root the view the frame was painted from
     * @param target the element the report hit, or null when it hit none
     * @param <Msg> the root message type
     * @return the path, at least one element
     */
    static <Msg> List<View<Msg>> observerPath(View<Msg> root, ElementId target) {
        if (target != null) {
            List<View<Msg>> path = pathTo(root, target);
            if (path != null) {
                return path;
            }
        }
        View<Msg> scope = modalScope(root);
        List<View<Msg>> ancestors = identityPath(root, scope);
        if (ancestors == null) {
            return List.of(root);
        }
        List<View<Msg>> within = focusPath(scope);
        if (within == null || within.size() < 2) {
            return ancestors;
        }
        List<View<Msg>> path = new ArrayList<>(ancestors);
        path.addAll(within.subList(1, within.size()));
        return List.copyOf(path);
    }

    /** The path from a root to one of its subtrees, compared by identity, or null. */
    private static <Msg> List<View<Msg>> identityPath(View<Msg> root, View<Msg> target) {
        if (root == target) {
            List<View<Msg>> path = new ArrayList<>(8);
            path.add(root);
            return path;
        }
        if (root instanceof View.Sized<Msg> sized) {
            List<View<Msg>> built = identityPath(sized.build().apply(Size.EMPTY), target);
            if (built != null) {
                List<View<Msg>> path = new ArrayList<>(built.size() + 1);
                path.add(root);
                path.addAll(built);
                return path;
            }
        }
        for (View<Msg> child : root.children()) {
            List<View<Msg>> found = identityPath(child, target);
            if (found != null) {
                List<View<Msg>> path = new ArrayList<>(found.size() + 1);
                path.add(root);
                path.addAll(found);
                return path;
            }
        }
        return null;
    }

    /**
     * The last path to an element with this focus identity, in paint order.
     *
     * <p>A focus identity is meant to be unique within a scope, and a mount scopes the identities of
     * the component it owns, so the first match is normally the only one. Taking the last is what
     * keeps a duplicated identity resolving to the element painted on top, which is the one a
     * pointer could actually have hit.</p>
     *
     * @param root the view the frame was painted from
     * @param id the focus identity to find
     * @param <Msg> the root message type
     * @return the path, or null when no element declares that identity
     */
    static <Msg> List<View<Msg>> pathTo(View<Msg> root, ElementId id) {
        List<View<Msg>> found = new ArrayList<>();
        pathTo(root, id, new ArrayList<>(), found);
        return found.isEmpty() ? null : found;
    }

    private static <Msg> void pathTo(View<Msg> view, ElementId id, List<View<Msg>> path, List<View<Msg>> found) {
        path.add(view);
        if (view instanceof View.Focused<Msg> focused && focused.id().equals(id)) {
            found.clear();
            found.addAll(path);
        }
        if (view instanceof View.Sized<Msg> sized) {
            pathTo(sized.build().apply(Size.EMPTY), id, path, found);
        }
        for (View<Msg> child : view.children()) {
            pathTo(child, id, path, found);
        }
        path.remove(path.size() - 1);
    }

    /**
     * Every message the ancestor interceptors of a path report for one event, outermost first.
     *
     * <p>This is the pointer observer hook: a report always reaches the element under it, so its
     * observers add messages instead of consuming it.</p>
     *
     * @param path the path to the target, or to the scope when nothing was hit
     * @param event the event to offer
     * @param <Msg> the message type
     * @return the messages, empty when no interceptor reported one
     */
    static <Msg> List<Msg> observe(List<View<Msg>> path, InputEvent event) {
        List<Msg> messages = new ArrayList<>(1);
        for (View<Msg> node : path) {
            if (node instanceof View.Keyed<Msg> keyed) {
                keyed.intercept().handle(event).ifPresent(messages::add);
            }
        }
        return messages;
    }

    /** Restricts routing to the topmost modal layer declared anywhere in the tree. */
    public static <Msg> View<Msg> modalScope(View<Msg> root) {
        View<Msg> layer = topmostModalLayer(root);
        return layer == null ? root : layer;
    }

    /** The last-painted modal layer, if the tree declares one. */
    private static <Msg> View<Msg> topmostModalLayer(View<Msg> view) {
        if (view instanceof View.Overlay<Msg> overlay
                && overlay.modal()
                && !overlay.layers().isEmpty()) {
            View<Msg> top = overlay.layers().get(overlay.layers().size() - 1);
            View<Msg> nested = topmostModalLayer(top);
            return nested == null ? top : nested;
        }
        List<View<Msg>> children = view.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            View<Msg> found = topmostModalLayer(children.get(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The same view with the pure-presentation wrappers removed.
     *
     * <p>{@link View.CursorAt} and {@link View.Presentation} carry intent, not structure: neither
     * changes what a key does or where a caret goes, and both are documented as transparent to
     * layout. Routing treats them the same way, so wrapping a scene in a session intent cannot stop
     * the scene's own bindings from matching when nothing is focused.</p>
     */
    private static <Msg> View<Msg> unwrap(View<Msg> view) {
        View<Msg> current = view;
        while (true) {
            if (current instanceof View.Presentation<Msg> presented) {
                current = presented.child();
            } else if (current instanceof View.CursorAt<Msg> cursor) {
                current = cursor.child();
            } else {
                return current;
            }
        }
    }

    /** Path from the scope to the focused element, or null when nothing is focused. */
    private static <Msg> List<View<Msg>> focusPath(View<Msg> scope) {
        return findFocus(scope, new ArrayList<>());
    }

    private static <Msg> List<View<Msg>> findFocus(View<Msg> view, List<View<Msg>> path) {
        path.add(view);
        if (view instanceof View.Focused<Msg> focused && focused.focused()) {
            return path;
        }
        if (view instanceof View.Sized<Msg> sized) {
            // A size-aware subtree has no child until it is given a geometry, so routing builds it
            // for an empty one: the structure a handler and its bindings live in does not depend on
            // the size, and a layout already asks the same builder for zero columns while measuring.
            List<View<Msg>> built = findFocus(sized.build().apply(Size.EMPTY), path);
            if (built != null) {
                return built;
            }
        }
        for (View<Msg> child : view.children()) {
            List<View<Msg>> found = findFocus(child, path);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        return null;
    }

    /**
     * The path's focused element, or null when nothing on the path owns focus. The no-focus path is
     * the scope alone, and a focusable element can <em>be</em> the scope: a widget view used as the
     * root, or as the topmost layer of a modal, declares itself through {@code Views.focus}. Being
     * the scope does not make it the focused element - {@code focused} is model data, and the widget
     * paints its active style only while that flag is set - so its bindings and handler run only
     * when it says it owns focus.
     */
    @SuppressWarnings("unchecked")
    private static <Msg> View.Focused<Msg> focusedNode(List<View<Msg>> path) {
        View<Msg> last = path.get(path.size() - 1);
        return last instanceof View.Focused<?> focused && focused.focused() ? (View.Focused<Msg>) focused : null;
    }

    private static <Msg> Optional<Msg> matchCapture(List<View<Msg>> path, Key key) {
        for (View<Msg> node : path) {
            if (node instanceof View.Keyed<Msg> keyed) {
                Optional<Msg> matched = match(keyed.capture(), key);
                if (matched.isPresent()) {
                    return matched;
                }
            }
        }
        return Optional.empty();
    }

    private static <Msg> Optional<Msg> matchInterceptors(List<View<Msg>> path, InputEvent event) {
        for (View<Msg> node : path) {
            if (node instanceof View.Keyed<Msg> keyed) {
                Optional<Msg> handled = keyed.intercept().handle(event);
                if (handled.isPresent()) {
                    return handled;
                }
            }
        }
        return Optional.empty();
    }

    private static <Msg> Optional<Msg> matchBubble(List<View<Msg>> path, InputEvent event) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (!(path.get(i) instanceof View.Keyed<Msg> keyed)) {
                continue;
            }
            if (event instanceof InputEvent.KeyInput keyInput) {
                Optional<Msg> matched = match(keyed.bubble(), keyInput.key());
                if (matched.isPresent()) {
                    return matched;
                }
            }
        }
        return Optional.empty();
    }

    private static <Msg> Optional<Msg> match(List<Binding<Msg>> bindings, Key key) {
        for (Binding<Msg> binding : bindings) {
            if (binding.matches(key)) {
                return Optional.ofNullable(binding.message());
            }
        }
        return Optional.empty();
    }
}
