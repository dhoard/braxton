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

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.PasteDecoder;

/**
 * The defaults table in the README is a published contract ("Defaults are configuration, not hidden
 * constants"), so the numbers in it are pinned here. Changing one is a documentation change too,
 * and this test is what fails if only the constant moves.
 */
class DocumentedDefaultsTest {

    @Test
    void theDocumentedRuntimeDefaultsAreTheShippedOnes() {
        assertThat(RuntimeOptions.DEFAULT_MAILBOX_CAPACITY).as("mailbox capacity").isEqualTo(4_096);
        assertThat(RuntimeOptions.DEFAULT_MAX_EFFECTS).as("one-shot effects").isEqualTo(64);
        assertThat(RuntimeOptions.DEFAULT_MAX_SUBSCRIPTIONS).as("ongoing sources").isEqualTo(64);
        assertThat(RuntimeOptions.DEFAULT_SHUTDOWN_GRACE).as("shutdown grace")
                .isEqualTo(Duration.ofSeconds(2));

        RuntimeOptions defaults = RuntimeOptions.defaults();
        assertThat(defaults.mailboxCapacity()).isEqualTo(4_096);
        assertThat(defaults.maxConcurrentEffects()).isEqualTo(64);
        assertThat(defaults.maxSubscriptions()).isEqualTo(64);
        assertThat(defaults.shutdownGrace()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theDocumentedInputAndClipboardLimitsAreTheShippedOnes() {
        assertThat(InputDecoder.DEFAULT_ESCAPE_TIMEOUT_MILLIS).as("escape timeout ms")
                .isEqualTo(30);
        assertThat(InputDecoder.MAX_CSI_LENGTH).as("CSI length bytes").isEqualTo(64);
        assertThat(PasteDecoder.DEFAULT_MAX_BYTES).as("paste payload bytes")
                .isEqualTo(1_048_576);
        assertThat(PasteDecoder.DEFAULT_IDLE_TIMEOUT_MILLIS).as("paste idle timeout ms")
                .isEqualTo(250L);
        assertThat(ClipboardConfig.DEFAULT_MAX_PAYLOAD_BYTES).as("OSC 52 payload bytes")
                .isEqualTo(65_536);
        assertThat(ClipboardConfig.DEFAULT_MAX_INTERNAL_CHARS).as("in-process buffer chars")
                .isEqualTo(1_048_576);
    }
}
