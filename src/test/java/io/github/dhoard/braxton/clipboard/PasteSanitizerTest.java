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

package io.github.dhoard.braxton.clipboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Artifact stripping removes whole escape sequences, including their intermediate bytes. */
class PasteSanitizerTest {

    /**
     * An ANSI escape sequence carries zero or more intermediate bytes (0x20-0x2F) before its final
     * byte, so ESC # 8, ESC % G, ESC $ B, and ESC SP F are sequences, not a sequence followed by
     * printable text. Leaving the final byte behind pastes a stray character into the draft.
     */
    @Test
    void anEscapeSequenceWithAnIntermediateByteLeavesNoArtifact() {
        String[] sequences = {"\u001B#8", "\u001B%G", "\u001B$B", "\u001B F", "\u001B(B"};

        for (String sequence : sequences) {
            assertThat(PasteSanitizer.sanitize(sequence))
                    .as("sanitize %s", (int) sequence.charAt(1))
                    .isEmpty();
            assertThat(PasteSanitizer.plainText(sequence))
                    .as("plainText %s", (int) sequence.charAt(1))
                    .isEmpty();
        }
        assertThat(PasteSanitizer.sanitize("a\u001B#8b")).isEqualTo("ab");
        assertThat(PasteSanitizer.plainText("a\u001B%Gb")).isEqualTo("ab");
    }

    /** A sequence cut off before its final byte still consumes everything it holds. */
    @Test
    void anUnfinishedEscapeSequenceIsDroppedToTheEnd() {
        assertThat(PasteSanitizer.sanitize("a\u001B#")).isEqualTo("a");
        assertThat(PasteSanitizer.sanitize("a\u001B")).isEqualTo("a");
        assertThat(PasteSanitizer.plainText("a\u001B ")).isEqualTo("a");
    }
}
