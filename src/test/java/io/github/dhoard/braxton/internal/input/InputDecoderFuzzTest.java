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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.InputOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Whatever a terminal can put on the wire, in whatever order it can put it there.
 *
 * <p>The decoder is the only place untrusted bytes become application events, and the input it accepts
 * is not a list anyone can enumerate: truncated UTF-8, a CSI that never ends, an ESC in the middle of a
 * sequence, a mouse report, a paste that opens and never closes. Every one of those reaches a running
 * application the moment a terminal, a multiplexer, or a scripted keystroke sends it. If one of them
 * throws, the update loop dies; if one of them hangs, the application stops answering; if one of them
 * yields an event with a control character in its name, {@link io.github.dhoard.braxton.input.Key}
 * rejects it and the keystroke that should have been ignored takes the session down.</p>
 *
 * <p>The example-based tests cover the sequences a terminal is known to send. This covers the ones it
 * is not known to send, and holds three things over all of them: the decoder terminates, it produces
 * nothing malformed, and it never invents more events than there were bytes to produce them from.</p>
 */
@Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
class InputDecoderFuzzTest {

    /** Fragments chosen to interleave badly: open sequences, orphan ends of sequences, invalid UTF-8. */
    private static final byte[][] FRAGMENTS = {
        bytes("a"),
        bytes("["),
        bytes("?"),
        bytes("200~"),
        bytes("201~"),
        bytes("<0;1;1M"),
        bytes("<0;1;1m"),
        bytes("[A"),
        bytes("[1;5A"),
        bytes("[15~"),
        bytes("[M"),
        bytes("OA"),
        bytes("OP"),
        bytes("\r"),
        bytes("\n"),
        bytes("\t"),
        bytes(" "),
        bytes("\u007F"),
        new byte[] {0x1B},
        new byte[] {0x00},
        new byte[] {0x07},
        new byte[] {0x08},
        new byte[] {(byte) 0x9B}, // a C1 introducer, sent as a raw byte
        new byte[] {(byte) 0xC3},
        new byte[] {(byte) 0xA9},
        new byte[] {(byte) 0xC3, (byte) 0xA9},
        new byte[] {(byte) 0xE2, (byte) 0x82, (byte) 0xAC},
        new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80},
        new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98},
        new byte[] {(byte) 0xFF},
        new byte[] {(byte) 0x80},
        new byte[] {(byte) 0xFE},
        new byte[] {(byte) 0xC0, (byte) 0x80},
        new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
        new byte[] {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},
    };

    private static final InputOptions FAST =
            new InputOptions(Duration.ofMillis(1), 64, 1 << 16, Duration.ofMillis(1), Duration.ofMillis(1));

    @Test
    void anyByteStreamDecodesToNothingOrToWellFormedEvents() throws IOException {
        Random random = new Random(97531L);
        for (int round = 0; round < 1500; round++) {
            byte[] stream = randomStream(random);
            List<InputEvent> events = drain(stream);
            // One event per byte at most: a single keystroke can never fan out into several, and a
            // sequence that consumed nothing would show up here as more events than input.
            assertThat(events.size())
                    .as("events decoded from %d bytes: %s", stream.length, events)
                    .isLessThanOrEqualTo(stream.length);
            for (InputEvent event : events) {
                if (event instanceof InputEvent.KeyInput key) {
                    assertThat(key.key().name())
                            .as("event from %s", show(stream))
                            .isNotBlank();
                    for (int i = 0; i < key.key().name().length(); i++) {
                        assertThat(Character.isISOControl(key.key().name().charAt(i)))
                                .as(
                                        "key name %s built from %s carries a control character",
                                        key.key().name(), show(stream))
                                .isFalse();
                    }
                    assertNoControlInText(key.text(), stream);
                } else if (event instanceof InputEvent.PasteInput paste) {
                    // A paste that reaches the application is application text. An escape byte here
                    // would be a terminal command smuggled in as typed content.
                    assertThat(paste.text().codePoints().anyMatch(c -> c == 0x1B || (c >= 0x80 && c < 0xA0)))
                            .as("pasted text from %s carries a control sequence", show(stream))
                            .isFalse();
                }
            }
        }
    }

    @Test
    void anOpenSequenceOrTruncatedScalarNeverStopsTheDecoder() throws IOException {
        Random random = new Random(13579L);
        for (int round = 0; round < 600; round++) {
            // Cut a stream at an arbitrary point: the prefix ends inside a sequence, mid-scalar, or
            // mid-escape, which is exactly what a terminal that is interrupted or slow produces.
            byte[] whole = randomStream(random);
            if (whole.length < 2) {
                continue;
            }
            byte[] prefix = new byte[1 + random.nextInt(whole.length - 1)];
            System.arraycopy(whole, 0, prefix, 0, prefix.length);
            List<InputEvent> events = drain(prefix);
            assertThat(events.size()).as("truncated stream %s", show(prefix)).isLessThanOrEqualTo(prefix.length);
        }
    }

    /**
     * The case whose answer is known without a decoder: printable ASCII carries its own text, one
     * gesture per character, in order. Anything else the decoder reports for the same bytes is a bug
     * in the decoder, not an ambiguity.
     */
    @Test
    void printableAsciiDecodesOneGesturePerCharacter() throws IOException {
        Random random = new Random(24680L);
        for (int round = 0; round < 400; round++) {
            StringBuilder text = new StringBuilder();
            for (int count = 1 + random.nextInt(20); count > 0; count--) {
                text.append((char) (0x20 + random.nextInt(0x5F)));
            }
            byte[] stream = text.toString().getBytes(StandardCharsets.UTF_8);
            List<InputEvent> events = drain(stream);
            assertThat(events).hasSize(stream.length);
            for (int i = 0; i < events.size(); i++) {
                char expected = text.charAt(i);
                assertThat(events.get(i))
                        .as("byte %d of [%s]", i, text)
                        .isInstanceOfSatisfying(InputEvent.KeyInput.class, key -> {
                            if (expected == ' ') {
                                assertThat(key.key().name()).isEqualTo("space");
                            } else {
                                assertThat(key.text()).isEqualTo(String.valueOf(expected));
                            }
                        });
            }
        }
    }

    private static void assertNoControlInText(String text, byte[] stream) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            assertThat(c == 0x1B || (c >= 0x80 && c <= 0x9F))
                    .as("event text %s from %s carries an escape or C1 character", show(text), show(stream))
                    .isFalse();
        }
    }

    /* The loop bound is the point: a decoder that never reaches end-of-input for a finite stream would
    otherwise spin here forever, and a spinning input loop is an application that stopped answering. */
    /**
     * Every event the decoder reports for {@code stream}, and nothing more. The reader is closed with
     * the read: each one is a thread, and a decoded that is starved of its reader answers end of input.
     */
    private static List<InputEvent> drain(byte[] stream) throws IOException {
        try (var reader =
                io.github.dhoard.braxton.internal.terminal.TimedReader.utf8(new ByteArrayInputStream(stream))) {
            return drain(new InputDecoder(reader, FAST), stream);
        }
    }

    private static List<InputEvent> drain(InputDecoder decoder, byte[] stream) throws IOException {
        List<InputEvent> events = new ArrayList<>();
        int budget = stream.length + 1;
        while (budget-- > 0) {
            Optional<InputEvent> next = decoder.read();
            if (next.isEmpty()) {
                return events;
            }
            events.add(next.get());
        }
        throw new AssertionError("the decoder produced more events than the stream has bytes: " + show(stream));
    }

    private static byte[] randomStream(Random random) {
        List<Byte> all = new ArrayList<>();
        for (int count = 1 + random.nextInt(6); count > 0; count--) {
            for (byte b : FRAGMENTS[random.nextInt(FRAGMENTS.length)]) {
                all.add(b);
            }
        }
        byte[] stream = new byte[all.size()];
        for (int i = 0; i < stream.length; i++) {
            stream[i] = all.get(i);
        }
        return stream;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }

    private static String show(byte[] value) {
        StringBuilder text = new StringBuilder();
        for (byte b : value) {
            text.append(String.format("%02x ", b));
        }
        return text.toString().trim();
    }
}
