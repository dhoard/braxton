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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;

/**
 * The key table driven with the byte sequences a real terminal sends.
 *
 * <p>Every sequence below is copied from {@code infocmp xterm-256color} together with the
 * capability that carries it, so the fixture is the terminal database rather than a hand-written
 * guess about what a terminal emits. Only sequences the decoder maps are listed: a sequence it
 * deliberately ignores (a mouse report, an unknown CSI) is not asserted here.</p>
 */
class TerminalKeySequenceTest {

    /** One terminal gesture: the terminfo capability, the bytes, and the key it must become. */
    private record Gesture(String capability, String sequence, Key key) {
    }

    private static final String ESC = "\u001B";

    private static final List<Gesture> TERMINFO_GESTURES = List.of(
            new Gesture("kbs", "\u007F", Key.BACKSPACE),
            new Gesture("kcbt", ESC + "[Z", Key.of("tab", Modifiers.SHIFT)),
            new Gesture("kcuu1", ESC + "OA", Key.UP),
            new Gesture("kcud1", ESC + "OB", Key.DOWN),
            new Gesture("kcuf1", ESC + "OC", Key.RIGHT),
            new Gesture("kcub1", ESC + "OD", Key.LEFT),
            new Gesture("khome", ESC + "OH", Key.HOME),
            new Gesture("kend", ESC + "OF", Key.END),
            new Gesture("kf1", ESC + "OP", Key.of("f1")),
            new Gesture("kf2", ESC + "OQ", Key.of("f2")),
            new Gesture("kf3", ESC + "OR", Key.of("f3")),
            new Gesture("kf4", ESC + "OS", Key.of("f4")),
            new Gesture("kf5", ESC + "[15~", Key.of("f5")),
            new Gesture("kf6", ESC + "[17~", Key.of("f6")),
            new Gesture("kf7", ESC + "[18~", Key.of("f7")),
            new Gesture("kf8", ESC + "[19~", Key.of("f8")),
            new Gesture("kf9", ESC + "[20~", Key.of("f9")),
            new Gesture("kf10", ESC + "[21~", Key.of("f10")),
            new Gesture("kf11", ESC + "[23~", Key.of("f11")),
            new Gesture("kf12", ESC + "[24~", Key.of("f12")),
            new Gesture("kpp", ESC + "[5~", Key.PAGE_UP),
            new Gesture("knp", ESC + "[6~", Key.PAGE_DOWN),
            new Gesture("kich1", ESC + "[2~", Key.of("insert")),
            new Gesture("kdch1", ESC + "[3~", Key.DELETE),
            new Gesture("kcuu1 (CSI form)", ESC + "[A", Key.UP),
            new Gesture("kcud1 (CSI form)", ESC + "[B", Key.DOWN),
            new Gesture("kcuf1 (CSI form)", ESC + "[C", Key.RIGHT),
            new Gesture("kcub1 (CSI form)", ESC + "[D", Key.LEFT),
            new Gesture("khome (CSI form)", ESC + "[H", Key.HOME),
            new Gesture("kend (CSI form)", ESC + "[F", Key.END),
            new Gesture("khome (xterm numeric)", ESC + "[1~", Key.HOME),
            new Gesture("kend (xterm numeric)", ESC + "[4~", Key.END));

    @Test
    void everyTerminfoKeySequenceBecomesItsKeyAndConsumesEveryByte() throws IOException {
        for (Gesture gesture : TERMINFO_GESTURES) {
            InputDecoder decoder = new InputDecoder(stream(gesture.sequence()));
            Optional<InputEvent> event = decoder.read();
            assertThat(event).as("%s %s decodes to an event", gesture.capability(),
                    gesture.sequence()).isNotEmpty();
            assertThat(event.get()).as("%s is a key gesture", gesture.capability())
                    .isInstanceOf(InputEvent.KeyInput.class);
            assertThat(((InputEvent.KeyInput) event.get()).key())
                    .as("%s names its key", gesture.capability()).isEqualTo(gesture.key());
            assertThat(decoder.read()).as("%s leaves no bytes behind", gesture.capability())
                    .isEmpty();
        }
    }

    @Test
    void everyTerminfoKeySequenceSurvivesAByteAtATimeStream() throws IOException {
        for (Gesture gesture : TERMINFO_GESTURES) {
            InputDecoder decoder = new InputDecoder(
                    new ByteAtATime(gesture.sequence().getBytes(StandardCharsets.UTF_8)));
            Optional<InputEvent> event = decoder.read();
            assertThat(event).as("%s split across reads still decodes", gesture.capability())
                    .isNotEmpty();
            assertThat(((InputEvent.KeyInput) event.get()).key())
                    .as("%s split across reads names its key", gesture.capability())
                    .isEqualTo(gesture.key());
        }
    }

    @Test
    void aTerminfoSequenceBetweenTypedCharactersKeepsBothSides() throws IOException {
        for (Gesture gesture : TERMINFO_GESTURES) {
            InputDecoder decoder = new InputDecoder(
                    stream("a" + gesture.sequence() + "b"));
            assertThat(keyOf(decoder.read())).as("%s after typed text", gesture.capability())
                    .isEqualTo(Key.of("a"));
            assertThat(keyOf(decoder.read())).as("%s between typed text", gesture.capability())
                    .isEqualTo(gesture.key());
            assertThat(keyOf(decoder.read())).as("%s before typed text", gesture.capability())
                    .isEqualTo(Key.of("b"));
        }
    }

    private static Key keyOf(Optional<InputEvent> event) {
        return ((InputEvent.KeyInput) event.orElseThrow(AssertionError::new)).key();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Serves one byte per read, so a sequence really does arrive in pieces. */
    private static final class ByteAtATime extends InputStream {
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
