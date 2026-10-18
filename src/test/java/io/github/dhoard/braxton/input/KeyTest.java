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

package io.github.dhoard.braxton.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Key identity: normalization, parsing, comparison, and the modifier prefix. */
class KeyTest {

    @Test
    void namesAreLowercasedAndBlankNamesAreRejected() {
        assertThat(Key.of("ENTER")).isEqualTo(Key.ENTER);
        assertThat(Key.of("Enter").name()).isEqualTo("enter");
        assertThat(Key.of("x", null).modifiers()).isEqualTo(Modifiers.NONE);
        assertThatThrownBy(() -> Key.of("  ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> Key.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseReadsModifierAliasesAndRejectsUnknownOnes() {
        assertThat(Key.parse("ctrl+alt+x")).isEqualTo(Key.of("x",
                new Modifiers(true, true, false)));
        assertThat(Key.parse("control+meta+shift+tab")).isEqualTo(Key.of("tab",
                new Modifiers(true, true, true)));
        assertThat(Key.parse("q")).isEqualTo(Key.of("q"));
        assertThatThrownBy(() -> Key.parse("hyper+x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hyper");
        assertThatThrownBy(() -> Key.parse("ctrl+"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparisonOrdersByNameThenPrefixAndToStringIsCanonical() {
        List<Key> keys = new ArrayList<>(List.of(Key.of("b"), Key.of("a", Modifiers.CTRL),
                Key.of("a")));
        keys.sort(null);
        assertThat(keys).containsExactly(Key.of("a"), Key.of("a", Modifiers.CTRL), Key.of("b"));
        assertThat(Key.of("x", new Modifiers(true, false, true)).toString())
                .isEqualTo("ctrl+shift+x");
        assertThat(Key.ESCAPE.toString()).isEqualTo("escape");
    }

    @Test
    void modifiersReportTheirPrefixAndEmptiness() {
        assertThat(new Modifiers(true, true, true).prefix()).isEqualTo("ctrl+alt+shift+");
        assertThat(Modifiers.NONE.prefix()).isEmpty();
        assertThat(Modifiers.NONE.none()).isTrue();
        assertThat(Modifiers.SHIFT.none()).isFalse();
        assertThat(Modifiers.NONE.withAlt(true)).isEqualTo(Modifiers.ALT);
        assertThat(Modifiers.CTRL.withCtrl(false)).isEqualTo(Modifiers.NONE);
    }
}
