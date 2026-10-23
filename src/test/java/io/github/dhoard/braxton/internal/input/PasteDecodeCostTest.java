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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A paste arrives as one burst of bytes, but the reader asked the stream for permission once per
 * byte and re-armed its idle window on every one of them, so a 64 KiB paste cost as many
 * availability queries as it had bytes and grew its buffer by repeated doubling. The bounds below
 * pin the per-paste cost; the parity tests pin the byte-exact consumption the fast path must keep,
 * including the rule that nothing after the end marker is ever taken from the stream.
 */
@org.junit.jupiter.api.extension.ExtendWith(io.github.dhoard.braxton.internal.input.TestReaders.class)
class PasteDecodeCostTest {

    private static final String END = "\u001B[201~";
    private static final String MIXED_UNIT = "a漢🙂b" + "\u0301" + "\tz ";

    private static volatile Object sink;

    private static long allocated(Runnable action) {
        Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
        for (int i = 0; i < 5; i++) {
            action.run();
        }
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        action.run();
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
    }

    private static String mixed(int chars) {
        StringBuilder out = new StringBuilder(Math.max(chars, MIXED_UNIT.length()));
        while (out.length() < chars) {
            out.append(MIXED_UNIT);
        }
        return out.substring(0, Math.max(chars, MIXED_UNIT.length()));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Counts how often the decoder queries the stream, without changing what the stream holds. */
    private static final class CountingStream extends ByteArrayInputStream {
        int availabilityChecks;
        int singleByteReads;

        CountingStream(byte[] buf) {
            super(buf);
        }

        @Override
        public int available() {
            availabilityChecks++;
            return super.available();
        }

        @Override
        public int read() {
            singleByteReads++;
            return super.read();
        }
    }

    /**
     * Hands out at most {@code window} bytes per availability query, the way a terminal delivers a
     * paste across several reads, so an end marker can straddle two windows.
     */
    private static final class WindowedStream extends ByteArrayInputStream {
        private final int window;

        WindowedStream(byte[] buf, int window) {
            super(buf);
            this.window = window;
        }

        @Override
        public int available() {
            return Math.min(window, super.available());
        }
    }

    @Test
    void aPasteThatAlreadyArrivedIsNotPolledOncePerByte() throws IOException {
        byte[] payload = (mixed(65_536) + END).getBytes(StandardCharsets.UTF_8);
        CountingStream in = new CountingStream(payload);
        PasteDecoder.Result result =
                PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), PasteDecoder.DEFAULT_MAX_BYTES, 250L);
        assertThat(result.text()).as("the whole paste is decoded").hasSize(65_536);
        assertThat(result.terminated()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(in.availabilityChecks)
                .as("the stream already holds the whole paste, so one availability query covers "
                        + "the run of bytes instead of one query per byte")
                .isLessThan(64);
    }

    @Test
    void aSixtyFourKibibytePasteIsBufferedAboutOnce() {
        byte[] payload = (mixed(65_536) + END).getBytes(StandardCharsets.UTF_8);
        int body = payload.length - END.getBytes(StandardCharsets.ISO_8859_1).length;
        // The returned String is the one allocation the decoder cannot avoid; everything above the
        // payload's own size is buffering the decoder copies through.
        long stringOnly = allocated(
                () -> sink = new String(payload, 0, body, StandardCharsets.UTF_8));
        long fixtureOnly = allocated(() -> {
            String characters = new String(payload, StandardCharsets.UTF_8);
            try (var reader = new org.jline.utils.NonBlockingPumpReader(characters.length() + 1)) {
                reader.getWriter().write(characters);
                sink = reader;
            } catch (IOException failure) { throw new AssertionError(failure); }
        });
        long read = allocated(() -> {
            try {
                // Measure the parser's copying budget on JLine's character pump; byte-to-character
                // acquisition is covered separately by JLineReaderTest. Avoid charging JLine's
                // scalar charset-reader Timeout allocation to this Braxton buffer-copy invariant.
                String characters = new String(payload, StandardCharsets.UTF_8);
                try (var reader = new org.jline.utils.NonBlockingPumpReader(characters.length() + 1)) {
                    reader.getWriter().write(characters);
                    sink = PasteDecoder.read(reader, PasteDecoder.DEFAULT_MAX_BYTES, 250L);
                }
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });
        assertThat(read - fixtureOnly)
                .as("a paste should be buffered about once rather than copied on every buffer "
                        + "doubling and again on the way out")
                .isLessThan(stringOnly + payload.length + 32_768L);
    }

    @Test
    void aRejectedMarkerPrefixAcrossBurstsCannotExhaustThePayloadBuffer() throws IOException {
        for (int matched = 1; matched < END.length(); matched++) {
            String body = "x".repeat(8191) + END.substring(0, matched) + "X";
            int split = body.length() - 1;
            for (int limit : new int[] {8192, 20_000}) {
                ByteArrayInputStream in = new ByteArrayInputStream(bytes(body + END + "Z")) {
                    @Override
                    public int available() {
                        return pos < split ? split - pos : Math.min(1, super.available());
                    }
                };
                PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), limit, 250L);
                assertThat(result.text()).isEqualTo(body.substring(0, Math.min(body.length(), limit)));
                assertThat(result.terminated()).isTrue();
                assertThat(result.truncated()).isEqualTo(body.length() > limit);
                assertThat(in.read()).as("input after the true end marker remains unread").isEqualTo('Z');
            }
        }
    }

    @Test
    void nothingAfterTheEndMarkerIsTakenFromTheStream() throws IOException {
        byte[] payload = ("abc" + END + "\u001B[A").getBytes(StandardCharsets.UTF_8);
        CountingStream in = new CountingStream(payload);
        PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), 1024, 250L);
        assertThat(result.text()).isEqualTo("abc");
        assertThat(result.terminated()).isTrue();
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .as("the keystrokes that follow the paste stay in the stream")
                .isEqualTo("\u001B[A");
    }

    @Test
    void nothingAfterTheEndMarkerIsTakenWhenTheMarkerStraddlesTwoWindows() throws IOException {
        byte[] payload = ("abc" + END + "de").getBytes(StandardCharsets.UTF_8);
        for (int window = 1; window <= 8; window++) {
            WindowedStream in = new WindowedStream(payload, window);
            PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), 1024, 250L);
            assertThat(result.text()).as("window " + window).isEqualTo("abc");
            assertThat(result.terminated()).as("window " + window).isTrue();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .as("window " + window + " must leave the bytes after the marker")
                    .isEqualTo("de");
        }
    }

    @Test
    void aPartialEndMarkerAtTheIdleEdgeIsPartOfThePayload() throws IOException {
        // The stream stops after an incomplete marker: those bytes are text, not a terminator.
        WindowedStream in = new WindowedStream(("ab\u001B[20").getBytes(StandardCharsets.UTF_8), 3);
        PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), 1024, 50L);
        assertThat(result.text()).isEqualTo("ab\u001B[20");
        assertThat(result.terminated()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aLimitCutsThePayloadButStillConsumesThroughTheEndMarker() throws IOException {
        WindowedStream in = new WindowedStream(("abcdef" + END + "z").getBytes(StandardCharsets.UTF_8), 3);
        PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), 3, 250L);
        assertThat(result.text()).isEqualTo("abc");
        assertThat(result.truncated()).isTrue();
        assertThat(result.terminated()).isTrue();
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("z");
    }

    @Test
    void aLimitThatSplitsACharacterDropsTheIncompleteTail() throws IOException {
        // "漢" is three bytes; a limit of 4 keeps one whole character and trims the half of the next.
        WindowedStream in = new WindowedStream(("漢漢" + END).getBytes(StandardCharsets.UTF_8), 2);
        PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), 4, 250L);
        assertThat(result.text()).isEqualTo("漢");
        assertThat(result.truncated()).isTrue();
        assertThat(result.terminated()).isTrue();
    }

    @Test
    void everyDeliveryWindowDecodesTheSameTextAsOneBurst() throws IOException {
        String[] bodies = {"", "a", "abc", "\t\n ", "漢字🙂", "a\u001B", "\u001B[2", "\u001B[201",
                "tail\u001B[201", "漢\u001B[20"};
        for (String body : bodies) {
            byte[] payload = (body + END + "rest").getBytes(StandardCharsets.UTF_8);
            PasteDecoder.Result burst =
                    PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteArrayInputStream(payload)), 4096, 250L);
            assertThat(burst.text()).as("burst of " + body).isEqualTo(body);
            assertThat(burst.terminated()).as("burst of " + body).isTrue();
            for (int window = 1; window <= 7; window++) {
                PasteDecoder.Result split =
                        PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new WindowedStream(payload, window)), 4096, 250L);
                assertThat(split.text()).as("window " + window + " of " + body).isEqualTo(body);
                assertThat(split.terminated()).as("window " + window + " of " + body).isTrue();
                assertThat(split.truncated()).as("window " + window + " of " + body).isFalse();
            }
        }
    }

    @Test
    void anOversizedPasteIsDrainedToTheMarkerAndReportedTruncated() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(bytes("x".repeat(5_000)));
        payload.writeBytes(bytes(END));
        payload.writeBytes(bytes("after"));
        WindowedStream in = new WindowedStream(payload.toByteArray(), 512);
        PasteDecoder.Result result = PasteDecoder.read(io.github.dhoard.braxton.internal.input.TestReaders.utf8(in), 1_000, 250L);
        assertThat(result.text()).isEqualTo("x".repeat(1_000));
        assertThat(result.truncated()).isTrue();
        assertThat(result.terminated()).isTrue();
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("after");
    }
}
