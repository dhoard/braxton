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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Bounded, streaming bracketed-paste reader (DECSET 2004).
 *
 * <p>The caller has already consumed {@code ESC [ 200~}. Bytes are scanned for the end marker across
 * arbitrarily short reads; markers split between reads are reassembled; the reader never consumes
 * bytes after the end marker. Oversized payloads are drained to the marker while excess bytes are
 * discarded, and a missing marker ends the paste after an idle window so later keyboard input is
 * processed normally.</p>
 */
public final class PasteDecoder {

    public static final String START_BODY = "200~";
    public static final String END_BODY = "201~";
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 250L;

    private static final byte[] END = "\u001b[201~".getBytes(StandardCharsets.ISO_8859_1);
    private static final int NEED_MORE = -2;

    /** One paste result; {@code terminated} is false when the idle window expired first. */
    public record Result(String text, boolean truncated, boolean terminated) {
    }

    private PasteDecoder() {
    }

    public static Result read(InputStream in) throws IOException {
        return read(in, DEFAULT_MAX_BYTES, DEFAULT_IDLE_TIMEOUT_MILLIS);
    }

    public static Result read(InputStream in, int maxBytes, long idleTimeoutMillis)
            throws IOException {
        if (in == null) {
            return new Result("", false, false);
        }
        int limit = Math.max(0, maxBytes);
        long idleNanos = Math.max(1L, idleTimeoutMillis) * 1_000_000L;
        ByteArrayOutputStream payload = new ByteArrayOutputStream(Math.min(limit, 8192));
        boolean truncated = false;
        boolean terminated = false;
        int matched = 0;
        long deadline = System.nanoTime() + idleNanos;

        while (true) {
            int value = readByte(in, deadline);
            if (value == NEED_MORE) {
                break;
            }
            deadline = System.nanoTime() + idleNanos;

            if (value == (END[matched] & 0xff)) {
                matched++;
                if (matched == END.length) {
                    terminated = true;
                    break;
                }
                continue;
            }
            if (matched > 0) {
                truncated |= !append(payload, END, 0, matched, limit);
                matched = 0;
            }
            if (value == (END[0] & 0xff)) {
                matched = 1;
                continue;
            }
            truncated |= !append(payload, value, limit);
        }

        if (!terminated && matched > 0) {
            truncated |= !append(payload, END, 0, matched, limit);
        }

        byte[] data = payload.toByteArray();
        if (truncated) {
            data = trimIncompleteUtf8Tail(data);
        }
        return new Result(new String(data, StandardCharsets.UTF_8), truncated, terminated);
    }

    private static int readByte(InputStream in, long deadline) throws IOException {
        while (true) {
            int available = in.available();
            if (available > 0) {
                int value = in.read();
                if (value >= 0) {
                    return value;
                }
                return NEED_MORE;
            }
            if (System.nanoTime() - deadline >= 0L) {
                return NEED_MORE;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return NEED_MORE;
            }
        }
    }

    private static boolean append(ByteArrayOutputStream payload, byte[] source, int offset,
            int length, int limit) {
        int room = limit - payload.size();
        if (room <= 0) {
            return false;
        }
        int take = Math.min(room, length);
        payload.write(source, offset, take);
        return take == length;
    }

    private static boolean append(ByteArrayOutputStream payload, int value, int limit) {
        if (payload.size() >= limit) {
            return false;
        }
        payload.write(value);
        return true;
    }

    /** Removes a trailing incomplete UTF-8 sequence so truncation cannot produce a stray U+FFFD. */
    static byte[] trimIncompleteUtf8Tail(byte[] data) {
        if (data.length == 0) {
            return data;
        }
        int start = data.length - 1;
        while (start >= 0 && (data[start] & 0xC0) == 0x80) {
            start--;
        }
        if (start < 0) {
            return new byte[0];
        }
        int lead = data[start] & 0xff;
        int expected = lead < 0x80 ? 1
                : (lead >> 5) == 0x6 ? 2
                : (lead >> 4) == 0xE ? 3
                : (lead >> 3) == 0x1E ? 4
                : 1;
        int actual = data.length - start;
        return actual < expected ? Arrays.copyOf(data, start) : data;
    }
}
