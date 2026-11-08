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

import io.github.dhoard.braxton.input.*;
import io.github.dhoard.braxton.internal.terminal.CharacterInput;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class TerminalReaderTest {
    @Test
    void alreadyDecodedInputIsNotLostWhenTheConsumerResumesAfterItsDeadline() throws Exception {
        byte[] bytes = "\u001b[A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean[] delivered = {false};
        try (var reader = new io.github.dhoard.braxton.internal.terminal.Utf8Reader((target, timeout) -> {
            if (delivered[0]) return 0;
            delivered[0] = true;
            System.arraycopy(bytes, 0, target, 0, bytes.length);
            return bytes.length;
        })) {
            var input = new ScalarInput(reader, 100);
            assertThat(input.read()).isEqualTo(27);
            long elapsed = System.nanoTime() - 1;
            assertThat(input.readUntil(elapsed)).isEqualTo('[');
            assertThat(input.readUntil(elapsed)).isEqualTo('A');
            assertThat(input.readUntil(elapsed)).isEqualTo(TimedReader.READ_EXPIRED);
        }
    }

    @Test
    void aReadTimeoutIsNotEndOfInput() throws Exception {
        try (var reader = new CharacterInput()) {
            assertThat(reader.read(1)).isEqualTo(TimedReader.READ_EXPIRED);
            reader.getWriter().write("x");
            assertThat(((InputEvent.KeyInput) new InputDecoder(reader).read().orElseThrow()).text())
                    .isEqualTo("x");
        }
    }

    @Test
    void eofResolvesAnIncompleteSurrogateWithoutLosingTheFollowingCharacter() throws Exception {
        try (var reader =
                io.github.dhoard.braxton.internal.terminal.TimedReader.from(new java.io.StringReader("\uD83Dx"))) {
            var decoder = new InputDecoder(reader);
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .isEqualTo("\uFFFD");
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .isEqualTo("x");
        }
    }

    @Test
    void supplementaryTextAndPasteLimitsUseCompleteScalars() throws Exception {
        try (var reader = new CharacterInput()) {
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
        try (var reader =
                io.github.dhoard.braxton.internal.terminal.TimedReader.from(new java.io.StringReader("\uD83D"))) {
            var cursor = new ScalarInput(reader, 100);
            assertThat(cursor.read()).isEqualTo(0xFFFD);
            assertThat(cursor.read()).isEqualTo(-1);
            assertThat(cursor.read()).isEqualTo(-1);
        }
    }
}
