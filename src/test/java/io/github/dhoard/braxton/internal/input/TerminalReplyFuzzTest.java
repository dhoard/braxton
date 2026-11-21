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
import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.input.TerminalReply;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Seeded fuzz for the framed replies a terminal sends in answer to a query.
 */
@Timeout(60)
class TerminalReplyFuzzTest {

    private static final InputOptions FAST =
            new InputOptions(Duration.ofMillis(1), 64, 1 << 16, Duration.ofMillis(1), Duration.ofMillis(1));

    private static final String[] PAYLOADS = {
        "",
        "rgb:ffff/0000/1234",
        "1;2",
        "?2004;1$y",
        "c;aGVsbG8=",
        "\u001b[31m",
        "\u0001\u0002\u007f",
        "ESC]52;c;bm90LWEta2V5",
        "a[b]c",
        "0+r524742"
    };

    @Test
    void randomRepliesNeverBecomeKeysAndNeverTrapTheDecoder() throws Exception {
        Random random = new Random(20261118L);
        for (int round = 0; round < 600; round++) {
            String reply = randomReply(random);
            byte[] stream = (reply + "a").getBytes(StandardCharsets.UTF_8);
            List<InputEvent> events = drain(stream);
            assertThat(events).as("reply %s", reply).isNotEmpty();
            InputEvent last = events.get(events.size() - 1);
            assertThat(last).as("final event after reply %s", reply).isInstanceOf(InputEvent.KeyInput.class);
            assertThat(((InputEvent.KeyInput) last).key().name()).isEqualTo("a");
            for (InputEvent event : events) {
                if (event instanceof InputEvent.KeyInput key) {
                    assertThat(key.text())
                            .as("reply payload leaked as text in %s", reply)
                            .doesNotContain("\u001b");
                    assertThat(key.key().name())
                            .as("reply payload leaked as a key in %s", reply)
                            .doesNotContain("\u001b");
                }
            }
        }
    }

    @Test
    void wellFormedRepliesKeepTheirFramingAndPayload() throws Exception {
        assertThat(drain("\u001b]11;rgb:0101/0202/0303\u0007a"))
                .first()
                .isEqualTo(
                        new InputEvent.TerminalReplyInput(new TerminalReply.OperatingSystem(11, "rgb:0101/0202/0303")));
        assertThat(drain("\u001BP>|xterm(390)\u001b\\a"))
                .first()
                .isEqualTo(new InputEvent.TerminalReplyInput(new TerminalReply.DeviceControl(">|", "xterm(390)")));
        assertThat(drain("\u001B[4;9Ra"))
                .first()
                .isEqualTo(new InputEvent.TerminalReplyInput(new TerminalReply.Control("4;9", 'R')));
    }

    private static String randomReply(Random random) {
        String payload = PAYLOADS[random.nextInt(PAYLOADS.length)];
        String terminator = random.nextBoolean() ? "\u0007" : "\u001b\\";
        return switch (random.nextInt(3)) {
            case 0 -> "\u001b]" + random.nextInt(120) + ";" + payload + terminator;
            case 1 -> "\u001bP" + random.nextInt(4) + "+r" + payload + "\u001b\\";
            default -> "\u001b[" + payload + (char) ('A' + random.nextInt(26));
        };
    }

    private static List<InputEvent> drain(String text) throws Exception {
        return drain(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<InputEvent> drain(byte[] stream) throws Exception {
        try (var reader = TimedReader.utf8(new ByteArrayInputStream(stream))) {
            InputDecoder decoder = new InputDecoder(reader, FAST);
            List<InputEvent> events = new ArrayList<>();
            int budget = stream.length + 1;
            while (budget-- > 0) {
                Optional<InputEvent> next = decoder.read();
                if (next.isEmpty()) return events;
                events.add(next.get());
            }
            throw new AssertionError("reply decoder produced more events than the stream has bytes");
        }
    }
}
