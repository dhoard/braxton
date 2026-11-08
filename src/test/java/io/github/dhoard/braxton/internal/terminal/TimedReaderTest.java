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

package io.github.dhoard.braxton.internal.terminal;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class TimedReaderTest {
    private static Utf8Reader scripted(Object... steps) {
        var queue = new ArrayDeque<Object>(java.util.List.of(steps));
        return new Utf8Reader((buffer, timeout) -> {
            if (queue.isEmpty()) return -1;
            Object step = queue.removeFirst();
            if (step instanceof Integer number) return number;
            byte[] bytes = (byte[]) step;
            System.arraycopy(bytes, 0, buffer, 0, bytes.length);
            return bytes.length;
        });
    }

    private static String drain(TimedReader reader) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int c; (c = reader.read(100)) != -1; ) {
            if (c != TimedReader.READ_EXPIRED) result.append((char) c);
        }
        return result.toString();
    }

    @Test
    void partialUtf8SurvivesTimeoutAndSupplementaryPairsStayIntact() throws Exception {
        try (var reader =
                scripted(new byte[] {(byte) 0xf0, (byte) 0x9f}, 0, new byte[] {(byte) 0x98, (byte) 0x80, 'x'})) {
            assertThat(reader.read(100)).isEqualTo(TimedReader.READ_EXPIRED);
            assertThat(drain(reader)).isEqualTo("😀x");
            assertThat(reader.read(100)).isEqualTo(-1);
        }
    }

    @Test
    void incompleteEofAndMalformedBytesAreReplacedWithoutLosingAdjacentText() throws Exception {
        try (var reader = scripted(new byte[] {'a', (byte) 0xff, 'b', (byte) 0xe2, (byte) 0x82})) {
            assertThat(drain(reader)).isEqualTo("a\ufffdb\ufffd");
        }
    }

    @Test
    void decoderHandlesMultipleFullBuffersAndOutputOverflow() throws Exception {
        byte[] bytes = ("A😀é".repeat(8000)).getBytes(StandardCharsets.UTF_8);
        int[] offset = {0};
        try (var reader = new Utf8Reader((target, timeout) -> {
            if (offset[0] == bytes.length) return -1;
            int count = Math.min(target.length, bytes.length - offset[0]);
            System.arraycopy(bytes, offset[0], target, 0, count);
            offset[0] += count;
            return count;
        })) {
            assertThat(drain(reader)).isEqualTo("A😀é".repeat(8000));
        }
    }

    @Test
    void closeReleasesAProducerBlockedByBoundedBuffering() throws Exception {
        var input = new CharacterInput(1);
        var entered = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                input.getWriter().write("a");
                entered.countDown();
                input.getWriter().write("b");
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        input.close();
        producer.join(2000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOf(IOException.class);
        assertThat(input.read(100)).isEqualTo(-1);
    }

    @Test
    void endDrainsBufferedCharactersBeforeEofAndRejectsFurtherWrites() throws Exception {
        try (var input = new CharacterInput(2)) {
            input.getWriter().write("ab");
            input.getWriter().close();
            assertThat(drain(input)).isEqualTo("ab");
            assertThatThrownBy(() -> input.getWriter().write("c")).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> input.read(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void terminalCapabilitiesRejectUnknownTypesAndKeepBasicVtConservative() {
        assertThatThrownBy(() -> NativeTerminal.capabilities(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NativeTerminal.capabilities("dumb")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NativeTerminal.capabilities("unknown")).isInstanceOf(IllegalStateException.class);
        assertThat(NativeTerminal.capabilities("xterm-256color").mouse()).isTrue();
        assertThat(NativeTerminal.capabilities("vt100").alternateScreen()).isFalse();
        assertThat(NativeTerminal.capabilities("linux").bracketedPaste()).isFalse();
    }
}
