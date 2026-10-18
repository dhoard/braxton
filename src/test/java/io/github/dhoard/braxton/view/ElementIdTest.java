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

package io.github.dhoard.braxton.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Focus identities are namespaced values; a child name must name something. */
class ElementIdTest {

    @Test
    void aChildNameIsNamespacedUnderItsParent() {
        assertThat(ElementId.of("root").child("field").value()).isEqualTo("root/field");
        assertThat(ElementId.of("root").child("field").child("inner").value())
                .isEqualTo("root/field/inner");
    }

    @Test
    void aChildNameMustNotBeNullOrBlank() {
        // Both would join into a valid-looking identity ("root/null", "root/") that collides with
        // a real child of that name, so two focus targets would share one id.
        assertThatThrownBy(() -> ElementId.of("root").child(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> ElementId.of("root").child("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
    }
}
