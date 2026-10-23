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

package io.github.dhoard.braxton.input;

import java.time.Duration;
import java.util.Objects;

/** Input limits measured in complete Unicode scalars and UTF-8 equivalent payload bytes. */
public record InputOptions(
        Duration escapeTimeout,
        int maxCsiCharacters,
        int maxPasteBytes,
        Duration pasteIdleTimeout,
        Duration cancellationInterval) {
    public static final InputOptions DEFAULT = new InputOptions(
            Duration.ofMillis(30), 64, 1_048_576, Duration.ofMillis(250), Duration.ofMillis(100));

    public InputOptions {
        positive(escapeTimeout, "escape timeout");
        positive(pasteIdleTimeout, "paste idle timeout");
        positive(cancellationInterval, "cancellation interval");
        if (cancellationInterval.compareTo(Duration.ofMillis(100)) > 0) {
            throw new IllegalArgumentException("cancellation interval must be at most 100 ms");
        }
        if (maxCsiCharacters < 1 || maxPasteBytes < 0) {
            throw new IllegalArgumentException(
                    "CSI capacity must be positive and paste capacity nonnegative");
        }
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero())
            throw new IllegalArgumentException(name + " must be positive");
        try {
            value.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds", error);
        }
    }
}
