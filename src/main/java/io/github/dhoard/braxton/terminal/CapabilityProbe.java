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

package io.github.dhoard.braxton.terminal;

import java.time.Duration;
import java.util.Optional;

/**
 * A bounded question asked of the terminal during capability detection.
 */
@FunctionalInterface
public interface CapabilityProbe {

    /**
     * Asks one named question; empty means silence, an unrecognized reply, or a timeout.
     *
     * @param question the terminal feature to probe
     * @param timeout the maximum wait
     * @return asks one named question; empty means silence, an unrecognized reply, or a timeout
     */
    Optional<String> query(String question, Duration timeout);

    /**
     * A probe that is always silent, used when no active query channel is available.
     */
    CapabilityProbe NONE = (question, timeout) -> Optional.empty();
}
