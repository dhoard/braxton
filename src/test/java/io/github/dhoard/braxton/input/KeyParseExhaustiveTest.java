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

/**
 * Every key specification the parser can be handed, enumerated rather than sampled.
 *
 * <p>{@link Key#parse(String)} is hand-written parsing over a split, and its awkward case is the name
 * of the key it is named for: the '+' key's canonical spec ends with a separator, so "ctrl++" has to
 * read as Ctrl and the plus key while "ctrl+" reads as nothing at all. A parser that decides that with
 * index arithmetic can read off the end of an array, and a bad spec typed into an application's key map
 * should be reported as a bad spec rather than crashing the runtime with an
 * {@code ArrayIndexOutOfBoundsException}.</p>
 *
 * <p>The modifier aliases, the modifier orders, the names, and the malformed strings are each
 * enumerated over a closed domain. Within that domain the parser either agrees with the aliases it
 * documents or it does not, and a malformed spec is either an
 * {@link IllegalArgumentException} or a usable {@link Key} - never anything else.</p>
 */
class KeyParseExhaustiveTest {

    private static final String[] NAMES = {
        "a", "z", "0", "9", "+", "enter", "tab", "space", "escape", "pageup", "wheeldown", "f1", "-", "[", "left"
    };

    /** Modifier words a spec may name, in every order the parser has to accept. */
    private static final String[] WORDS = {"ctrl", "control", "alt", "meta", "shift"};

    private static final String[] GARBAGE_ALPHABET = {"c", "t", "r", "l", "a", "+", "x", " "};

    @Test
    void everyModifierSpellingAndOrderNamesTheSameKey() {
        for (String name : NAMES) {
            for (List<String> words : wordCombinations()) {
                String spec = String.join("+", words) + (words.isEmpty() ? "" : "+") + name;
                Key parsed = Key.parse(spec);
                assertThat(parsed.name())
                        .as("spec [%s] lost its name", spec)
                        .isEqualTo(name.toLowerCase(java.util.Locale.ROOT));
                Modifiers expected = modifiersOf(words);
                assertThat(parsed.modifiers()).as("spec [%s] modifiers", spec).isEqualTo(expected);
                // The canonical form the value prints has to be readable back as the same key.
                assertThat(Key.parse(parsed.toString()))
                        .as("round trip of the spec [%s] that [%s] prints", parsed, spec)
                        .isEqualTo(parsed);
            }
        }
    }

    @Test
    void aMalformedSpecIsAnIllegalArgumentAndNeverAnythingElse() {
        for (String spec : garbage()) {
            try {
                Key key = Key.parse(spec);
                assertThat(key.name())
                        .as("spec [%s] parsed into a key with no name", spec)
                        .isNotBlank();
            } catch (IllegalArgumentException expected) {
                // The one failure an application can bind to and report.
            } catch (RuntimeException other) {
                throw new AssertionError(
                        "spec [" + spec + "] failed as " + other.getClass().getSimpleName(), other);
            }
        }
    }

    @Test
    void aKeyNameIsNeverBlankOrBuiltFromAControlCharacter() {
        assertThatThrownBy(() -> Key.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Key.of("   ")).isInstanceOf(IllegalArgumentException.class);
        for (char control : new char[] {'\n', '\r', '\t', 0x00, 0x07, 0x1B, 0x7F}) {
            assertThatThrownBy(() -> Key.of("a" + control))
                    .as("a key named after U+%04x", (int) control)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Key.parse("ctrl+a" + control))
                    .as("a modified key named after U+%04x", (int) control)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // An empty name with one trailing separator names nothing; a doubled separator is the plus key.
        assertThatThrownBy(() -> Key.parse("ctrl+")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Key.parse("ctrl++").name()).isEqualTo("+");
        assertThat(Key.parse("ctrl++").modifiers()).isEqualTo(Modifiers.CTRL);
        assertThat(Key.parse("+").name()).isEqualTo("+");
        assertThat(Key.parse("+").modifiers().none()).isTrue();
    }

    private static Modifiers modifiersOf(List<String> words) {
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        for (String word : words) {
            switch (word) {
                case "ctrl", "control" -> ctrl = true;
                case "alt", "meta" -> alt = true;
                case "shift" -> shift = true;
                default -> throw new IllegalStateException("not a modifier word: " + word);
            }
        }
        return new Modifiers(ctrl, alt, shift);
    }

    /** Every ordered run of zero to three modifier words, without repeating one. */
    private static List<List<String>> wordCombinations() {
        List<List<String>> all = new ArrayList<>();
        all.add(List.of());
        for (String first : WORDS) {
            all.add(List.of(first));
            for (String second : WORDS) {
                if (second.equals(first)) {
                    continue;
                }
                all.add(List.of(first, second));
                for (String third : WORDS) {
                    if (third.equals(first) || third.equals(second)) {
                        continue;
                    }
                    all.add(List.of(first, second, third));
                }
            }
        }
        return all;
    }

    /** Every string of length 1..4 over the garbage alphabet. */
    private static List<String> garbage() {
        List<String> all = new ArrayList<>();
        List<String> previous = List.of("");
        for (int length = 1; length <= 4; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : previous) {
                for (String piece : GARBAGE_ALPHABET) {
                    next.add(prefix + piece);
                }
            }
            all.addAll(next);
            previous = next;
        }
        return all;
    }
}
