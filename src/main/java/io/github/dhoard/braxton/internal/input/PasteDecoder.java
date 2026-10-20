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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, streaming bracketed-paste reader (DECSET 2004).
 *
 * <p>The caller has already consumed {@code ESC [ 200~}. Bytes are scanned for the end marker across
 * arbitrarily short reads; markers split between reads are reassembled; the reader never consumes
 * bytes after the end marker. Oversized payloads are drained to the marker while excess bytes are
 * discarded, and a missing marker ends the paste after an idle window so later keyboard input is
 * processed normally.</p>
 *
 * <p>A paste normally arrives as one burst, so the stream is asked what it holds once per burst
 * rather than once per byte, and the idle window is re-armed at the same point. Bytes are still
 * taken one at a time so the read stops exactly at the end marker and the keystrokes after it stay
 * in the stream.</p>
 */
public final class PasteDecoder {

    public static final String START_BODY = "200~";
    public static final String END_BODY = "201~";
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 250L;

    private static final byte[] END = "\u001b[201~".getBytes(StandardCharsets.ISO_8859_1);
    private static final int INITIAL_CAPACITY = 8192;

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
        // Saturating conversion: a configured window above ~292 years would wrap a plain
        // multiplication into a negative value and cut the paste off after the first poll. A huge
        // window must remain a huge window, not become an immediate timeout.
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, idleTimeoutMillis));
        byte[] payload = new byte[0];
        int size = 0;
        boolean truncated = false;
        boolean terminated = false;
        int matched = 0;
        long deadline = addSaturated(System.nanoTime(), idleNanos);
        int burst = 0;

        while (true) {
            if (burst == 0) {
                burst = awaitBurst(in, deadline);
                if (burst < 0) {
                    break;
                }
                deadline = addSaturated(System.nanoTime(), idleNanos);
                if (size < limit) {
                    payload = reserve(payload, size, burst, limit);
                }
            }
            int value = in.read();
            if (value < 0) {
                break;
            }
            burst--;

            if (value == (END[matched] & 0xff)) {
                matched++;
                if (matched == END.length) {
                    terminated = true;
                    break;
                }
                continue;
            }
            if (matched > 0) {
                int room = limit - size;
                if (room <= 0) {
                    truncated = true;
                } else {
                    int take = Math.min(room, matched);
                    payload = reserve(payload, size, take, limit);
                    System.arraycopy(END, 0, payload, size, take);
                    size += take;
                    truncated |= take != matched;
                }
                matched = 0;
            }
            if (value == (END[0] & 0xff)) {
                matched = 1;
                continue;
            }
            if (size < limit) {
                payload[size++] = (byte) value;
            } else {
                truncated = true;
            }
        }

        if (!terminated && matched > 0) {
            int room = limit - size;
            if (room <= 0) {
                truncated = true;
            } else {
                int take = Math.min(room, matched);
                payload = reserve(payload, size, take, limit);
                System.arraycopy(END, 0, payload, size, take);
                size += take;
                truncated |= take != matched;
            }
        }

        if (truncated) {
            size = trimIncompleteUtf8Tail(payload, size);
        }
        return new Result(new String(payload, 0, size, StandardCharsets.UTF_8), truncated, terminated);
    }

    /** Adds without wrapping: a saturated deadline never expires by overflow. */
    private static long addSaturated(long start, long delta) {
        return delta < 0L || Long.MAX_VALUE - start < delta ? Long.MAX_VALUE : start + delta;
    }

    /**
     * Waits for the stream to hold something, returning how many bytes it already holds, or -1 when
     * the idle window expired or the stream ended. The count is only a promise that a read will not
     * block, so the caller still stops at the end marker and leaves the rest of the burst behind.
     */
    private static int awaitBurst(InputStream in, long deadline) throws IOException {
        while (true) {
            int available = in.available();
            if (available > 0) {
                return available;
            }
            if (System.nanoTime() - deadline >= 0L) {
                return -1;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    /**
     * Grows the buffer to hold {@code extra} more bytes, never past the payload limit. A burst is
     * known up front, so an empty buffer is sized to it directly; later growth doubles.
     */
    private static byte[] reserve(byte[] payload, int size, int extra, int limit) {
        long wanted = (long) size + extra;
        int needed = wanted > limit ? limit : (int) wanted;
        if (needed <= payload.length) {
            return payload;
        }
        int capacity = payload.length == 0
                ? Math.max(needed, Math.min(INITIAL_CAPACITY, limit))
                : Math.max(1, payload.length);
        while (capacity < needed) {
            capacity = (int) Math.min((long) needed, (long) capacity * 2);
        }
        return Arrays.copyOf(payload, capacity);
    }

    /** Removes a trailing incomplete UTF-8 sequence so truncation cannot produce a stray U+FFFD. */
    static int trimIncompleteUtf8Tail(byte[] data, int length) {
        if (length == 0) {
            return length;
        }
        int start = length - 1;
        while (start >= 0 && (data[start] & 0xC0) == 0x80) {
            start--;
        }
        if (start < 0) {
            return 0;
        }
        int lead = data[start] & 0xff;
        int expected = lead < 0x80 ? 1
                : (lead >> 5) == 0x6 ? 2
                : (lead >> 4) == 0xE ? 3
                : (lead >> 3) == 0x1E ? 4
                : 1;
        int actual = length - start;
        return actual < expected ? start : length;
    }
}
