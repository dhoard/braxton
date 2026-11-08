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

import io.github.dhoard.braxton.internal.terminal.TimedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A paste body that is not a paste body.
 *
 * <p>Bracketed paste is the one input path that hands the application an arbitrarily large block of
 * text in a single event, and it is delimited by an escape sequence the pasted content itself can
 * contain. The decoder matches that delimiter incrementally, which means a near-miss - an escape, a
 * bracket, a "2", then something else - has to become payload again. Get that wrong in one direction
 * and a paste ends early, leaking the rest of the block into the application as keystrokes; get it
 * wrong the other way and the real delimiter is swallowed, so the paste runs on into whatever the user
 * types next.</p>
 *
 * <p>The byte limit is the other half: a limit applied mid-character is a corrupted string, and a limit
 * that is checked after appending is a buffer the attacker sized. So every stream here is checked for
 * the same four things: it ends, it never carries a whole delimiter, it stays inside the limit, and it
 * never ends on half a character.</p>
 */
@Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
class PasteDecoderFuzzTest {

    private static final String END = "\u001B[201~";

    private static final byte[][] FRAGMENTS = {
        bytes("a"),
        bytes("hello"),
        bytes(" "),
        bytes("\n"),
        bytes("\r\n"),
        bytes("\t"),
        bytes("["),
        bytes("2"),
        bytes("0"),
        bytes("1"),
        bytes("~"),
        bytes("[20"),
        bytes("[201"),
        bytes("[201~"),
        bytes("[201~[201~"),
        bytes("\u001B[20"),
        bytes("\u001B["),
        bytes("\u001B"),
        new byte[] {0x1B, 0x5B, 0x32, 0x30, 0x31},
        new byte[] {(byte) 0xC3, (byte) 0xA9},
        new byte[] {(byte) 0xC3},
        new byte[] {(byte) 0xE2, (byte) 0x9C, (byte) 0x93},
        new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80},
        new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98},
        new byte[] {(byte) 0xFF},
        new byte[] {(byte) 0x80},
        new byte[] {0x00},
        new byte[] {0x07},
        new byte[] {0x7F},
    };

    private static final int[] LIMITS = {-3, 0, 1, 2, 5, 7, 17, 64, 1024};

    /**
     * Idle window for the paste. It costs nothing: a {@code ByteArrayInputStream} reaches end of input
     * immediately, and that - not the window - is what ends the read. The window only has to be wide
     * enough that the reader thread's startup is never mistaken for a paste that stopped arriving.
     */
    private static final long IDLE = 50L;

    @Test
    void aPasteBodyAlwaysEndsAndNeverCarriesItsOwnDelimiter() throws IOException {
        Random random = new Random(271_828L);
        for (int round = 0; round < 3_000; round++) {
            byte[] stream = body(random);
            int limit = LIMITS[random.nextInt(LIMITS.length)];
            PasteDecoder.Result result = read(stream, limit);
            String where = "limit %d stream %s".formatted(limit, show(stream));

            assertThat(result.text().contains(END))
                    .as("%s: the paste carries the sequence that ends it: %s", where, show(result.text()))
                    .isFalse();
            int bytes = result.text().getBytes(StandardCharsets.UTF_8).length;
            assertThat(bytes)
                    .as("%s: paste payload is %d bytes, past the limit", where, bytes)
                    .isLessThanOrEqualTo(Math.max(0, limit));
            assertWholeCharacters(result.text(), where);
            assertThat(result.text().length())
                    .as("%s: more characters than the stream had bytes", where)
                    .isLessThanOrEqualTo(stream.length);
            // Whether the paste ended is a fact about the stream, not about the decoder's bookkeeping.
            assertThat(result.terminated())
                    .as(
                            "%s: terminated=%s, the stream %s the delimiter",
                            where, result.terminated(), containsEnd(stream) ? "held" : "did not hold")
                    .isEqualTo(containsEnd(stream));
            // A limit that falls inside a character stops before it rather than splitting it, so the
            // payload can sit under the limit. What it can never do is disagree with the same stream
            // read without a limit: cutting the past short only ever drops a suffix.
            PasteDecoder.Result whole = read(stream, Integer.MAX_VALUE);
            assertThat(whole.text())
                    .as(
                            "%s: the same body with no limit is %s, which does not begin with the limited read",
                            where, show(whole.text()))
                    .startsWith(result.text());
            if (result.truncated()) {
                assertThat(result.text().length())
                        .as("%s: reported truncation but the whole body was kept", where)
                        .isLessThan(whole.text().length());
            }
        }
    }

    /** Every fragment is also a complete paste body on its own, with no limit in the way. */
    @Test
    void eachFragmentOnItsOwnDecodesToItselfOrToTheMarkerItNearlyForms() throws IOException {
        for (byte[] fragment : FRAGMENTS) {
            PasteDecoder.Result result = PasteDecoder.read(
                    io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteArrayInputStream(fragment)),
                    Integer.MAX_VALUE,
                    IDLE);
            String where = "fragment " + show(fragment);
            assertThat(result.text().contains(END))
                    .as("%s: carries its own delimiter", where)
                    .isFalse();
            assertWholeCharacters(result.text(), where);
            assertThat(result.terminated())
                    .as(
                            "%s: terminated=%s but the fragment %s the delimiter",
                            where, result.terminated(), containsEnd(fragment) ? "holds" : "does not hold")
                    .isEqualTo(containsEnd(fragment));
        }
    }

    /** Half a surrogate pair would hand the application text it cannot encode or display. */
    private static void assertWholeCharacters(String text, String where) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean danglingHigh = Character.isHighSurrogate(c) && i + 1 >= text.length();
            boolean orphanLow =
                    Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)));
            assertThat(danglingHigh || orphanLow)
                    .as("%s: payload %s holds half of a character", where, show(text))
                    .isFalse();
        }
    }

    /**
     * One read, one reader, closed straight away. A reader is a thread: leaving thousands of them open
     * until the test method ends starves the ones that come after, and a starved reader answers end of
     * input - which looks exactly like a decoder that dropped the payload.
     */
    private static PasteDecoder.Result read(byte[] stream, int maxBytes) throws IOException {
        try (TimedReader reader =
                io.github.dhoard.braxton.internal.terminal.TimedReader.utf8(new ByteArrayInputStream(stream))) {
            return PasteDecoder.read(reader, maxBytes, IDLE);
        }
    }

    private static boolean containsEnd(byte[] stream) {
        byte[] needle = END.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int at = 0; at + needle.length <= stream.length; at++) {
            for (int i = 0; i < needle.length; i++) {
                if (stream[at + i] != needle[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] body(Random random) {
        List<Byte> all = new ArrayList<>();
        for (int count = 1 + random.nextInt(7); count > 0; count--) {
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
