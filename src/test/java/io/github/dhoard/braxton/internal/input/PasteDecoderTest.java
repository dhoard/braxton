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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Bracketed-paste reading: termination, limits, and the idle window. */
class PasteDecoderTest {

    /** Reports no data for the first {@code quietPolls} queries, like a paste arriving in pieces. */
    private static final class DelayedStream extends InputStream {
        private final byte[] data;
        private final int quietPolls;
        private int polls;
        private int index;

        DelayedStream(byte[] data, int quietPolls) {
            this.data = data;
            this.quietPolls = quietPolls;
        }

        @Override
        public int read() {
            return index < data.length ? data[index++] & 0xFF : -1;
        }

        @Override
        public int available() {
            if (polls < quietPolls) {
                polls++;
                return 0;
            }
            return index < data.length ? 1 : 0;
        }
    }

    private static byte[] payload(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] end = "\u001b[201~".getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[body.length + end.length];
        System.arraycopy(body, 0, all, 0, body.length);
        System.arraycopy(end, 0, all, body.length, end.length);
        return all;
    }

    @Test
    void aHugeIdleWindowStillAcceptsAPasteThatArrivesLate() throws IOException {
        // Long.MAX_VALUE milliseconds used to wrap the nanosecond window negative, so the first
        // poll saw an expired deadline and the paste was dropped before its first byte.
        PasteDecoder.Result result =
                PasteDecoder.read(new DelayedStream(payload("slow"), 3), 1024, Long.MAX_VALUE);
        assertThat(result.text()).isEqualTo("slow");
        assertThat(result.terminated()).isTrue();
    }

    @Test
    void anExpiredWindowReturnsWhatArrivedUntruncated() throws IOException {
        PasteDecoder.Result result =
                PasteDecoder.read(new DelayedStream("partial".getBytes(StandardCharsets.UTF_8), 2),
                        1024, 50L);
        assertThat(result.text()).isEqualTo("partial");
        assertThat(result.terminated()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aPayloadOverTheLimitIsTruncatedAndTerminated() throws IOException {
        PasteDecoder.Result result = PasteDecoder.read(new DelayedStream(payload("abcdef"), 0), 3,
                1_000L);
        assertThat(result.text()).isEqualTo("abc");
        assertThat(result.truncated()).isTrue();
        assertThat(result.terminated()).isTrue();
    }
}
