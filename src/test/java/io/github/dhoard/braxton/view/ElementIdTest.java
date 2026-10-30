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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;

import org.junit.jupiter.api.Test;

class ElementIdTest {
    @Test
    void elementIdIsLocalAndStructurallyScoped() {
        var local = ElementId.of("tick");
        var a = ComponentPath.ROOT.append(ComponentId.of("a"));
        var b = ComponentPath.ROOT.append(ComponentId.of("b"));
        assertThat(local.value()).isEqualTo("tick");
        assertThat(local.path()).isEqualTo(ComponentPath.ROOT);
        assertThat(local.scoped(a)).isEqualTo(ElementId.of("tick").scoped(a));
        assertThat(local.scoped(a)).isNotEqualTo(local.scoped(b));
        assertThat(local.scoped(b).scoped(a).path()).isEqualTo(a.append(ComponentId.of("b")));
        assertThat(local.scoped(a).hashCode()).isEqualTo(ElementId.of("tick").scoped(a).hashCode());
        assertThat(local.toString()).isEqualTo("tick");
        assertThat(local.scoped(a).toString()).isEqualTo("/a:tick");
        for (String invalid :
                new String[] {"", " ", ".", "..", "a/b", "/b", "b/", "a\u001bb", "x".repeat(129)}) {
            assertThatThrownBy(() -> ElementId.of(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ElementId.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> local.scoped(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void optionalIsPresent() {
        assertThat(ElementId.of("x").asOptional()).contains(ElementId.of("x"));
    }
}
