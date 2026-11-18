/*
 * Copyright (c) 2026-present Douglas Hoard
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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The pointer protocols decode to normalized pointer reports, and malformed ones decode to none. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.dhoard.braxton.internal.input.TestReaders.class)
class MouseMappingTest {

    /** Every event the decoder produces, written so a whole input can be compared in one string. */
    private static String decode(String bytes) throws IOException {
        InputDecoder decoder =
                new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(bytes)));
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
            } else if (e instanceof InputEvent.PointerInput pointer) {
                PointerEvent value = pointer.pointer();
                out.append(value.action())
                        .append(':')
                        .append(value.button())
                        .append('@')
                        .append(value.row())
                        .append(',')
                        .append(value.column())
                        .append(' ');
            }
        }
    }

    @Test
    void sgrReportsDecodeToEveryPointerAction() throws IOException {
        assertThat(decode("\u001B[<0;3;4M")).isEqualTo("PRESS:LEFT@3,2 ");
        assertThat(decode("\u001B[<0;3;4m")).isEqualTo("RELEASE:LEFT@3,2 ");
        assertThat(decode("\u001B[<2;1;1M")).as("a right press").isEqualTo("PRESS:RIGHT@0,0 ");
        assertThat(decode("\u001B[<1;1;1M")).as("a middle press").isEqualTo("PRESS:MIDDLE@0,0 ");
        assertThat(decode("\u001B[<35;3;4M")).as("motion with nothing held").isEqualTo("MOTION:NONE@3,2 ");
        assertThat(decode("\u001B[<32;3;4M")).as("a drag").isEqualTo("MOTION:LEFT@3,2 ");
    }

    @Test
    void sgrWheelReportsKeepTheirDirectionAndCell() throws IOException {
        assertThat(decode("\u001B[<64;3;4M")).isEqualTo("WHEEL:WHEEL_UP@3,2 ");
        assertThat(decode("\u001B[<65;3;4M")).isEqualTo("WHEEL:WHEEL_DOWN@3,2 ");
        assertThat(decode("\u001B[<66;1;1M")).as("a horizontal wheel").isEqualTo("WHEEL:WHEEL_LEFT@0,0 ");
        assertThat(decode("\u001B[<67;1;1M")).isEqualTo("WHEEL:WHEEL_RIGHT@0,0 ");
        assertThat(decode("\u001B[<64;3;4M\u001B[<65;3;4M")).isEqualTo("WHEEL:WHEEL_UP@3,2 WHEEL:WHEEL_DOWN@3,2 ");
    }

    @Test
    void sgrReportsCarryTheModifierStateTheTerminalSet() {
        for (String[] reported : new String[][] {{"4", "shift"}, {"8", "alt"}, {"16", "ctrl"}, {"12", "shift+alt"}}) {
            int bits = Integer.parseInt(reported[0]);
            PointerEvent event = pointer("\u001B[<" + bits + ";1;1M");
            assertThat(event.modifiers())
                    .as("bits %d are %s", bits, reported[1])
                    .isNotEqualTo(Modifiers.NONE);
        }
        assertThat(pointer("\u001B[<16;1;1M").modifiers()).isEqualTo(new Modifiers(true, false, false));
        assertThat(pointer("\u001B[<4;1;1M").modifiers()).isEqualTo(new Modifiers(false, false, true));
        assertThat(pointer("\u001B[<8;1;1M").modifiers()).isEqualTo(new Modifiers(false, true, false));
    }

    @Test
    void x10ReportsDecodeToTheActionsTheirBytesCanName() throws IOException {
        // Each byte carries its value plus 32, and the form has no way to name a released button.
        assertThat(decode("\u001B[M`!!")).isEqualTo("WHEEL:WHEEL_UP@1,1 ");
        assertThat(decode("\u001B[Ma!!")).isEqualTo("WHEEL:WHEEL_DOWN@1,1 ");
        assertThat(decode("\u001B[M !!")).isEqualTo("PRESS:LEFT@1,1 ");
        assertThat(decode("\u001B[M#!!")).as("the legacy release").isEqualTo("RELEASE:NONE@1,1 ");
        assertThat(decode("\u001B[M@!!")).as("motion with a button held").isEqualTo("MOTION:LEFT@1,1 ");
    }

    @Test
    void malformedSgrReportsNeverBecomePointerEvents() throws IOException {
        for (String body : new String[] {
            "<64M",
            "<64;1M",
            "<64;1;1;1M",
            "<64;:;1M",
            "<64;1;0M",
            "<64;1;1A",
            "<+64;1;1M",
            "<64;2147483648;1M",
            "<300;1;1M",
            "<64;1;" + "0".repeat(100) + "1M",
            // The four extra buttons this project does not model, and a release that names a wheel.
            "<128;1;1M",
            "<64;1;1m"
        }) {
            assertThat(decode("\u001B[" + body + "z")).as(body).isEqualTo("z ");
        }
    }

    private static PointerEvent pointer(String sequence) {
        try {
            InputDecoder decoder = new InputDecoder(
                    io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(sequence)));
            InputEvent event = decoder.read().orElseThrow();
            assertThat(event).isInstanceOf(InputEvent.PointerInput.class);
            return ((InputEvent.PointerInput) event).pointer();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Serves one byte at a time, like a terminal delivering a sequence in pieces. */
    private static final class ByteAtATime extends java.io.InputStream {
        private final byte[] data;
        private int index;

        ByteAtATime(String text) {
            this.data = text.getBytes(StandardCharsets.UTF_8);
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
