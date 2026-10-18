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

package io.github.dhoard.braxton.internal.clipboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The tmux passthrough wrapper doubles every ESC and is applied at most once. */
class TmuxClipboardTest {

    private static final String PREFIX = "\u001bPtmux;";
    private static final String SUFFIX = "\u001b\\";
    private static final String OSC52 = "\u001b]52;c;aGk=\u0007";

    /** What tmux does to the payload before forwarding it: collapse each doubled ESC. */
    private static String unescape(String body) {
        return body.replace("\u001b\u001b", "\u001b");
    }

    private static int escapes(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\u001b') {
                count++;
            }
        }
        return count;
    }

    /** Every ESC in the payload sits in a pair, so unescaping never has to guess. */
    private static boolean doublesEveryEscape(String body) {
        for (int i = 0; i < body.length(); ) {
            if (body.charAt(i) != '\u001b') {
                i++;
            } else if (i + 1 < body.length() && body.charAt(i + 1) == '\u001b') {
                i += 2;
            } else {
                return false;
            }
        }
        return true;
    }

    @Test
    void nothingToWrapProducesNothing() {
        assertThat(TmuxClipboard.passthrough(null)).isEmpty();
        assertThat(TmuxClipboard.passthrough("")).isEmpty();
        assertThat(TmuxClipboard.isPassthrough(null)).isFalse();
        assertThat(TmuxClipboard.isPassthrough("")).isFalse();
        assertThat(TmuxClipboard.isPassthrough(OSC52)).isFalse();
    }

    @Test
    void theWrapperDoublesEscapesAndUnwrapsBackToTheInput() {
        String wrapped = TmuxClipboard.passthrough(OSC52);
        assertThat(wrapped).isEqualTo(PREFIX + "\u001b\u001b]52;c;aGk=\u0007" + SUFFIX);
        assertThat(TmuxClipboard.isPassthrough(wrapped)).isTrue();
        assertThat(unescape(wrapped.substring(PREFIX.length(), wrapped.length() - SUFFIX.length())))
                .isEqualTo(OSC52);
    }

    @Test
    void wrappingIsAppliedAtMostOnce() {
        String wrapped = TmuxClipboard.passthrough(OSC52);
        assertThat(TmuxClipboard.passthrough(wrapped)).isEqualTo(wrapped);
        assertThat(TmuxClipboard.passthrough(TmuxClipboard.passthrough(TmuxClipboard.passthrough(
                OSC52)))).isEqualTo(wrapped);
        // A body that already looks like a passthrough is left exactly as it is.
        assertThat(TmuxClipboard.passthrough(PREFIX)).isEqualTo(PREFIX);
        assertThat(TmuxClipboard.passthrough(PREFIX + "x" + SUFFIX))
                .isEqualTo(PREFIX + "x" + SUFFIX);
    }

    @Test
    void everyEscapeInThePayloadIsDoubledAndTheLengthIsExact() {
        Random random = new Random(71L);
        String[] pieces = {"\u001b", "]", "52;", "c", "a", "\u0007", "\u001b\\", "x", "\u001b]0;t\u0007",
            PREFIX, SUFFIX, "\u001b[1m"};
        for (int iteration = 0; iteration < 3_000; iteration++) {
            StringBuilder sequence = new StringBuilder();
            int count = random.nextInt(6);
            for (int i = 0; i < count; i++) {
                sequence.append(pieces[random.nextInt(pieces.length)]);
            }
            String source = sequence.toString();
            if (source.isEmpty() || source.startsWith(PREFIX)) {
                continue;
            }
            String wrapped = TmuxClipboard.passthrough(source);
            String body = wrapped.substring(PREFIX.length(), wrapped.length() - SUFFIX.length());
            assertThat(doublesEveryEscape(body)).as("iteration %d", iteration).isTrue();
            assertThat(unescape(body)).as("iteration %d", iteration).isEqualTo(source);
            assertThat(escapes(body)).as("iteration %d", iteration).isEqualTo(2 * escapes(source));
            assertThat(wrapped.length()).as("iteration %d", iteration)
                    .isEqualTo(PREFIX.length() + body.length() + SUFFIX.length());
        }
    }
}