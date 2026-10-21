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

package io.github.dhoard.braxton.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Effect and source identities: namespaced values whose child name must name something. */
class EffectIdTest {

    @Test
    void aChildIdIsNamespacedUnderItsParent() {
        assertThat(EffectId.of("harness").child("tick").value()).isEqualTo("harness/tick");
        assertThat(EffectId.of("harness").child("tick").namespace()).isEqualTo("harness");
        assertThat(SubscriptionId.of("harness").child("tick").value()).isEqualTo("harness/tick");
    }

    @Test
    void aChildNameMustNotBeNullOrBlank() {
        // "harness/null" and "harness/   " are identities a real child could carry, so a forged
        // one would cancel or replace the wrong effect or source.
        assertThatThrownBy(() -> EffectId.of("harness").child(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> EffectId.of("harness").child("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> SubscriptionId.of("harness").child(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> SubscriptionId.of("harness").child("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
    }

    @Test
    void aChildIdMustNotForgeAnotherNamespace() {
        // The join is a namespace separator, not part of the name: "a" + "b/c" and "a/b" + "c"
        // would both be "a/b/c", which the runner registers as one effect, so one feature's work
        // replaces and cancels the other's. The rule that rejects null and blank rejects a name
        // that forges a path through another namespace too.
        assertThatThrownBy(() -> EffectId.of("a").child("b/c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> EffectId.of("a").child("b/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> EffectId.of("a").child("/b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        // A parent may itself be namespaced: only the child name is a single path segment.
        assertThat(EffectId.of("a/b").child("c").value()).isEqualTo("a/b/c");
        assertThat(EffectId.of("a/b").child("c").namespace()).isEqualTo("a");
    }

    @Test
    void aSubscriptionChildIdMustNotForgeAnotherNamespace() {
        // The source identity carries the same join as the effect id: "a" + "b/c" and
        // "a/b" + "c" would both be "a/b/c", so the runtime sees one subscription where the
        // application declared two paths and rejects the set as a duplicate.
        assertThatThrownBy(() -> SubscriptionId.of("a").child("b/c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> SubscriptionId.of("a").child("b/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        assertThatThrownBy(() -> SubscriptionId.of("a").child("/b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child name");
        // A parent may itself be namespaced: only the child name is a single path segment.
        assertThat(SubscriptionId.of("a/b").child("c").value()).isEqualTo("a/b/c");
    }
}
