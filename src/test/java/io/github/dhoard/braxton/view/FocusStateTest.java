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

import java.util.List;

import org.junit.jupiter.api.Test;

/** Focus identity and order-preserving traversal. */
class FocusStateTest {

    private static final List<ElementId> ORDER =
            List.of(ElementId.of("a"), ElementId.of("b"), ElementId.of("c"));

    @Test
    void traversalWrapsInBothDirections() {
        assertThat(FocusState.of(ElementId.of("b")).next(ORDER)).isEqualTo(ElementId.of("c"));
        assertThat(FocusState.of(ElementId.of("c")).next(ORDER)).isEqualTo(ElementId.of("a"));
        assertThat(FocusState.of(ElementId.of("a")).previous(ORDER)).isEqualTo(ElementId.of("c"));
        assertThat(FocusState.of(ElementId.of("b")).previous(ORDER)).isEqualTo(ElementId.of("a"));
    }

    @Test
    void withoutFocusTraversalStartsAtEitherEnd() {
        assertThat(FocusState.NONE.next(ORDER)).isEqualTo(ElementId.of("a"));
        assertThat(FocusState.NONE.previous(ORDER)).isEqualTo(ElementId.of("c"));
        assertThat(FocusState.of(ElementId.of("gone")).next(ORDER)).isEqualTo(ElementId.of("a"));
        assertThat(FocusState.of(ElementId.of("gone")).previous(ORDER)).isEqualTo(ElementId.of("c"));
    }

    @Test
    void anEmptyOrderHasNoNextElement() {
        assertThat(FocusState.NONE.next(List.of())).isNull();
        assertThat(FocusState.NONE.previous(List.of())).isNull();
        assertThat(FocusState.NONE.next(null)).isNull();
    }

    @Test
    void equalityAndIdentityFollowTheFocusedId() {
        assertThat(FocusState.of(null)).isSameAs(FocusState.NONE);
        assertThat(FocusState.of(ElementId.of("a"))).isEqualTo(FocusState.of(ElementId.of("a")));
        assertThat(FocusState.of(ElementId.of("a")).hashCode())
                .isEqualTo(FocusState.of(ElementId.of("a")).hashCode());
        assertThat(FocusState.of(ElementId.of("a")).current()).contains(ElementId.of("a"));
        assertThat(FocusState.NONE.current()).isEmpty();
        assertThat(FocusState.of(ElementId.of("a")).isFocused(ElementId.of("a"))).isTrue();
        assertThat(FocusState.of(ElementId.of("a")).isFocused(null)).isFalse();
        assertThat(FocusState.NONE.toString()).isEqualTo("FocusState[none]");
        assertThat(FocusState.of(ElementId.of("a")).toString()).isEqualTo("FocusState[a]");
        assertThat(FocusState.of(ElementId.of("a")).clear()).isSameAs(FocusState.NONE);
        assertThat(FocusState.ofOptional(java.util.Optional.empty())).isEmpty();
        assertThat(FocusState.ofOptional(java.util.Optional.of(ElementId.of("z")))).isPresent();
        assertThat(FocusState.ofOptional(java.util.Optional.of(ElementId.of("z"))).orElseThrow()
                .current()).contains(ElementId.of("z"));
    }
}
