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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.testing.AllocationProbe;

class InputRoutingCostTest {

    @Test
    void searchingForFocusDoesNotCopyTheWholeAncestorPathAtEveryNode() {
        View<Object> tree = Views.blank();
        for (int i = 0; i < 512; i++) {
            tree = Views.column(tree);
        }
        View<Object> root = tree;
        InputEvent event = InputEvent.KeyInput.of(Key.of("a"), "a");
        assertThat(AllocationProbe.bytes(() -> InputRouter.route(root, event))).isLessThan(150_000);
        assertThat(InputRouter.route(root, event)).isEmpty();
    }
}
