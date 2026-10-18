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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** The documented routing order holds for one tree. */
class InputRouterPropertyTest {

    @Test
    void captureRunsBeforeFocusAndBubbleAfterIt() {
        View<String> tree = Views.keyed(List.of(Binding.of("k", "outer-capture")), event -> {
            throw new AssertionError("interceptor should not run for a captured key");
        }, Views.keyed(List.of(Binding.of("k", "inner-capture")), event -> java.util.Optional.of("intercept"),
                Views.focus(ElementId.of("f"), true, List.of(Binding.of("k", "focus")),
                        event -> java.util.Optional.of("handler"), Views.text("x")),
                List.of(Binding.of("k", "inner-bubble"))), List.of(Binding.of("k", "outer-bubble")));
        assertThat(InputRouter.route(tree, InputEvent.KeyInput.of(Key.of("k"))))
                .as("outermost capture wins").contains("outer-capture");
        View<String> noCaptures = Views.keyed(List.of(), event -> java.util.Optional.of("intercept"),
                Views.focus(ElementId.of("f"), true, List.of(Binding.of("k", "focus")),
                        event -> java.util.Optional.of("handler"), Views.text("x")),
                List.of(Binding.of("k", "bubble")));
        assertThat(InputRouter.route(noCaptures, InputEvent.KeyInput.of(Key.of("k"))))
                .as("an interceptor runs before the focused element").contains("intercept");
        View<String> handlerFirst = Views.keyed(List.of(), View.InputHandler.none(),
                Views.focus(ElementId.of("f"), true, List.of(), event -> java.util.Optional.of("handler"),
                        Views.text("x")), List.of(Binding.of("k", "bubble")));
        assertThat(InputRouter.route(handlerFirst, InputEvent.KeyInput.of(Key.of("k"))))
                .as("the focused handler runs before bubble bindings").contains("handler");
    }
}
