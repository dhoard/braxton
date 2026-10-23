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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.jline.utils.NonBlockingReader;

/** Bounded scalar-aware paste parsing. Overflow is drained through the marker or idle deadline. */
public final class PasteDecoder {
    public static final String START_BODY = "200~";
    public static final String END_BODY = "201~";
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 250L;
    private static final String END = "\u001b[201~";

    public record Result(String text, boolean truncated, boolean terminated) {}

    private PasteDecoder() {}

    public static Result read(NonBlockingReader in) throws IOException {
        return read(in, DEFAULT_MAX_BYTES, DEFAULT_IDLE_TIMEOUT_MILLIS);
    }

    public static Result read(NonBlockingReader in, int maxBytes, long idleMillis)
            throws IOException {
        return read(new ScalarInput(in, 100), maxBytes, idleMillis);
    }

    static Result read(ScalarInput in, int maxBytes, long idleMillis) throws IOException {
        int limit = Math.max(0, maxBytes);
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, idleMillis));
        StringBuilder payload = new StringBuilder(Math.min(limit, 8192));
        int bytes = 0, matched = 0;
        boolean truncated = false, terminated = false;
        long deadline = System.nanoTime() + idleNanos;
        while (true) {
            int value = in.readUntil(deadline);
            if (value < 0) break;
            deadline = System.nanoTime() + idleNanos;
            if (value == END.charAt(matched)) {
                if (++matched == END.length()) {
                    terminated = true;
                    matched = 0;
                    break;
                }
                continue;
            }
            if (matched > 0) {
                for (int i = 0; i < matched; i++) {
                    if (!truncated && bytes < limit) {
                        payload.append(END.charAt(i));
                        bytes++;
                    } else truncated = true;
                }
                matched = 0;
                if (value == END.charAt(0)) {
                    matched = 1;
                    continue;
                }
            }
            int count = value <= 0x7F ? 1 : value <= 0x7FF ? 2 : value <= 0xFFFF ? 3 : 4;
            if (!truncated && (long) bytes + count <= limit) {
                payload.appendCodePoint(value);
                bytes += count;
            } else truncated = true;
        }
        // An incomplete marker is payload; sanitization belongs to the domain paste policy.
        for (int i = 0; i < matched; i++) {
            if (!truncated && bytes < limit) {
                payload.append(END.charAt(i));
                bytes++;
            } else truncated = true;
        }
        return new Result(payload.toString(), truncated, terminated);
    }
}
