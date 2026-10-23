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

import static org.assertj.core.api.Assertions.*;

import org.jline.utils.NonBlockingPumpReader;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.input.*;

@Timeout(5)
class JLineReaderTest {
    @Test
    void aReadTimeoutIsNotEndOfInput() throws Exception {
        try (var reader = new NonBlockingPumpReader()) {
            assertThat(reader.read(1)).isEqualTo(NonBlockingReader.READ_EXPIRED);
            reader.getWriter().write("x");
            assertThat(((InputEvent.KeyInput) new InputDecoder(reader).read().orElseThrow()).text())
                    .isEqualTo("x");
        }
    }

    @Test
    void eofResolvesAnIncompleteSurrogateWithoutLosingTheFollowingCharacter() throws Exception {
        try (var reader = org.jline.utils.NonBlocking.nonBlocking(
                "test", new java.io.StringReader("\uD83Dx"))) {
            var decoder = new InputDecoder(reader);
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .isEqualTo("\uFFFD");
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .isEqualTo("x");
        }
    }

    @Test
    void supplementaryTextAndPasteLimitsUseCompleteScalars() throws Exception {
        try (var reader = new NonBlockingPumpReader()) {
            reader.getWriter().write("😀\u001b[200~😀x\u001b[201~z");
            var options = new InputOptions(
                    java.time.Duration.ofMillis(30),
                    64,
                    4,
                    java.time.Duration.ofMillis(50),
                    java.time.Duration.ofMillis(100));
            var decoder = new InputDecoder(reader, options);
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .isEqualTo("😀");
            assertThat(decoder.read().orElseThrow()).isEqualTo(new InputEvent.PasteInput("😀"));
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .isEqualTo("z");
        }
    }

    @Test
    void isolatedHighSurrogateAtEofIsReplacedOnce() throws Exception {
        try (var reader = org.jline.utils.NonBlocking.nonBlocking(
                "test", new java.io.StringReader("\uD83D"))) {
            var cursor = new ScalarInput(reader, 100);
            assertThat(cursor.read()).isEqualTo(0xFFFD);
            assertThat(cursor.read()).isEqualTo(-1);
            assertThat(cursor.read()).isEqualTo(-1);
        }
    }
}
