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

package io.github.dhoard.braxton.internal.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.view.View;

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
 * element is focused, only the scope's own bindings can consume an event.</p>
 */
public final class InputRouter {

    private InputRouter() {
    }

    /** Routes one event, returning the message produced by the first consumer. */
    public static <Msg> Optional<Msg> route(View<Msg> root, InputEvent event) {
        if (root == null || event == null) {
            return Optional.empty();
        }
        View<Msg> scope = modalScope(root);
        List<View<Msg>> path = focusPath(scope);
        if (path == null) {
            path = List.of(scope);
        }

        if (event instanceof InputEvent.KeyInput keyInput) {
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
        if (focused != null) {
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

        Optional<Msg> bubble = matchBubble(path, event);
        if (bubble.isPresent()) {
            return bubble;
        }
        return Optional.empty();
    }

    /** Restricts routing to the topmost modal layer declared anywhere in the tree. */
    public static <Msg> View<Msg> modalScope(View<Msg> root) {
        View<Msg> layer = topmostModalLayer(root);
        return layer == null ? root : layer;
    }

    /** The last-painted modal layer, if the tree declares one. */
    private static <Msg> View<Msg> topmostModalLayer(View<Msg> view) {
        if (view instanceof View.Overlay<Msg> overlay && overlay.modal()
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

    /** Path from the scope to the focused element, or null when nothing is focused. */
    private static <Msg> List<View<Msg>> focusPath(View<Msg> scope) {
        return findFocus(scope, new ArrayList<>());
    }

    private static <Msg> List<View<Msg>> findFocus(View<Msg> view, List<View<Msg>> path) {
        path.add(view);
        if (view instanceof View.Focused<Msg> focused && focused.focused()) {
            return path;
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

    @SuppressWarnings("unchecked")
    private static <Msg> View.Focused<Msg> focusedNode(List<View<Msg>> path) {
        View<Msg> last = path.get(path.size() - 1);
        return last instanceof View.Focused<?> focused ? (View.Focused<Msg>) focused : null;
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
