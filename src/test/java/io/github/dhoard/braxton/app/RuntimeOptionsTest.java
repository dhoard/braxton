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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Runtime limits are validated where they are accepted, not when shutdown uses them. */
class RuntimeOptionsTest {

    private static RuntimeOptions options(Duration grace) {
        return new RuntimeOptions(grace, RuntimeOptions.DEFAULT_MAILBOX_CAPACITY,
                RuntimeOptions.DEFAULT_MAX_EFFECTS, RuntimeOptions.DEFAULT_MAX_SUBSCRIPTIONS,
                null, null, null);
    }

    @Test
    void aShutdownGraceThatCannotBeScheduledInNanosecondsIsRejected() {
        assertThatThrownBy(() -> options(Duration.ofDays(1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
    }

    @Test
    void aNegativeShutdownGraceIsRejected() {
        assertThatThrownBy(() -> options(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void aRepresentableShutdownGraceIsAccepted() {
        assertThat(options(Duration.ofNanos(Long.MAX_VALUE)).shutdownGrace())
                .isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
        assertThat(options(Duration.ofSeconds(2)).shutdownGrace()).isEqualTo(Duration.ofSeconds(2));
    }
}
