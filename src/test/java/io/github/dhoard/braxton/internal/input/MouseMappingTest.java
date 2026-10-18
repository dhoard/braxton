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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;

/** Mouse reports are decoded only where they carry a key identity; the rest are skipped. */
class MouseMappingTest {

    private static String decode(String bytes) throws IOException {
        InputDecoder decoder = new InputDecoder(new ByteAtATime(bytes.getBytes(StandardCharsets.UTF_8)));
        StringBuilder out = new StringBuilder();
        while (true) {
            var event = decoder.read();
            if (event.isEmpty()) {
                return out.toString();
            }
            InputEvent e = event.get();
            if (e instanceof InputEvent.KeyInput key) {
                out.append(key.key()).append(' ');
            } else if (e instanceof InputEvent.PasteInput paste) {
                out.append("paste[").append(paste.text()).append("] ");
            }
        }
    }

    @Test
    void sgrWheelPressesMapToWheelKeysAndOtherButtonsAreIgnored() throws IOException {
        assertThat(decode("\u001B[<64;3;4M")).isEqualTo("wheelup ");
        assertThat(decode("\u001B[<65;3;4M")).isEqualTo("wheeldown ");
        assertThat(decode("\u001B[<0;3;4M")).as("a left press is not a key").isEmpty();
        assertThat(decode("\u001B[<0;3;4m")).as("a release is not a key").isEmpty();
        assertThat(decode("\u001B[<64;3;4M\u001B[<65;3;4M")).isEqualTo("wheelup wheeldown ");
    }

    @Test
    void x10WheelReportsMapAndClicksAreIgnored() throws IOException {
        assertThat(decode("\u001B[M`!!")).isEqualTo("wheelup ");
        assertThat(decode("\u001B[Ma!!")).isEqualTo("wheeldown ");
        assertThat(decode("\u001B[M !!")).as("a click with no identity is skipped").isEmpty();
    }

    /** Serves a byte at a time, like a terminal delivering a sequence in pieces. */
    private static final class ByteAtATime extends java.io.InputStream {
        private final byte[] data;
        private int index;

        ByteAtATime(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return index < data.length ? data[index++] & 0xFF : -1;
        }

        @Override
        public int available() {
            return index < data.length ? 1 : 0;
        }
    }
}
