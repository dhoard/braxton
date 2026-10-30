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

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** Runtime limits are validated where they are accepted, not when shutdown uses them. */
class RuntimeOptionsTest {

    private static RuntimeOptions options(Duration grace) {
        return new RuntimeOptions(
                grace,
                RuntimeOptions.DEFAULT_MAILBOX_CAPACITY,
                RuntimeOptions.DEFAULT_MAX_EFFECTS,
                RuntimeOptions.DEFAULT_MAX_SUBSCRIPTIONS,
                RuntimeOptions.DEFAULT_MAX_COMPONENTS,
                RuntimeOptions.DEFAULT_MAX_COMPONENT_DEPTH,
                RuntimeOptions.DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                null,
                null,
                null);
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

    @Test
    void theClipboardAndTerminalWithersKeepEveryOtherField() {
        RuntimeOptions base = options(Duration.ofMillis(750));
        io.github.dhoard.braxton.clipboard.ClipboardConfig clipboard =
                io.github.dhoard.braxton.clipboard.ClipboardConfig.builder().maxPayloadBytes(64).build();
        RuntimeOptions withClipboard = base.withClipboard(clipboard);
        assertThat(withClipboard.clipboard()).isSameAs(clipboard);
        assertThat(withClipboard.terminal()).isEqualTo(base.terminal());
        assertThat(withClipboard.input()).isSameAs(base.input());
        assertThat(withClipboard.shutdownGrace()).isEqualTo(base.shutdownGrace());
        assertThat(withClipboard.mailboxCapacity()).isEqualTo(base.mailboxCapacity());
        assertThat(withClipboard.maxConcurrentEffects()).isEqualTo(base.maxConcurrentEffects());
        assertThat(withClipboard.maxSubscriptions()).isEqualTo(base.maxSubscriptions());

        io.github.dhoard.braxton.app.TerminalOptions terminal =
                io.github.dhoard.braxton.app.TerminalOptions.DEFAULT;
        RuntimeOptions withTerminal = base.withTerminal(terminal);
        assertThat(withTerminal.terminal()).isSameAs(terminal);
        assertThat(withTerminal.clipboard()).isSameAs(base.clipboard());
        assertThat(withTerminal.input()).isSameAs(base.input());
        assertThat(withTerminal.shutdownGrace()).isEqualTo(base.shutdownGrace());
        assertThat(withTerminal.mailboxCapacity()).isEqualTo(base.mailboxCapacity());
    }
}
