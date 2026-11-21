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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.PasteDecoder;
import io.github.dhoard.braxton.internal.runtime.TerminalQueries;
import io.github.dhoard.braxton.style.ColorProfile;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The defaults table in the README is a published contract ("Defaults are configuration, not hidden
 * constants"), so the numbers in it are pinned here. Changing one is a documentation change too,
 * and this test is what fails if only the constant moves.
 */
class DocumentedDefaultsTest {

    @Test
    void theDocumentedRuntimeDefaultsAreTheShippedOnes() {
        assertThat(RuntimeOptions.DEFAULT_MAILBOX_CAPACITY)
                .as("mailbox capacity")
                .isEqualTo(4_096);
        assertThat(RuntimeOptions.DEFAULT_MAX_EFFECTS).as("one-shot effects").isEqualTo(64);
        assertThat(RuntimeOptions.DEFAULT_MAX_SUBSCRIPTIONS)
                .as("ongoing sources")
                .isEqualTo(64);
        assertThat(RuntimeOptions.DEFAULT_SHUTDOWN_GRACE).as("shutdown grace").isEqualTo(Duration.ofSeconds(2));

        RuntimeOptions defaults = RuntimeOptions.defaults();
        assertThat(defaults.mailboxCapacity()).isEqualTo(4_096);
        assertThat(defaults.maxConcurrentEffects()).isEqualTo(64);
        assertThat(defaults.maxSubscriptions()).isEqualTo(64);
        assertThat(defaults.maxComponents()).isEqualTo(4_096);
        assertThat(defaults.maxComponentDepth()).isEqualTo(64);
        assertThat(defaults.maxLocalDispatchesPerTurn()).isEqualTo(1_024);
        assertThat(defaults.withComponentLimits(8, 2, 16).maxComponents()).isEqualTo(8);
        assertThatThrownBy(() -> defaults.withComponentLimits(0, 1, 1))
                .as("component count limit")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withComponentLimits(1, 0, 1))
                .as("component depth limit")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withComponentLimits(1, 1, 0))
                .as("local dispatch limit")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(defaults.shutdownGrace()).isEqualTo(Duration.ofSeconds(2));
        assertThat(RuntimeOptions.DEFAULT_FPS).as("presentation ceiling").isEqualTo(60);
        assertThat(RuntimeOptions.MIN_FPS).isEqualTo(1);
        assertThat(RuntimeOptions.MAX_FPS).isEqualTo(240);
        assertThat(defaults.fps()).isEqualTo(60);
        assertThat(defaults.messageFilter())
                .as("a session without a filter delivers every message")
                .isNull();
        assertThat(defaults.propagateFailure())
                .as("a failure is reported through the exit result, not thrown")
                .isFalse();
        assertThat(defaults.installShutdownHook())
                .as("the run restores the terminal on its own")
                .isTrue();
        assertThat(defaults.withFps(240).fps()).isEqualTo(240);
        assertThatThrownBy(() -> defaults.withFps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withFps(241)).isInstanceOf(IllegalArgumentException.class);
        assertThat(defaults.withMessageFilter(message -> message).messageFilter())
                .isNotNull();
        assertThat(defaults.withFailurePropagation(true).propagateFailure()).isTrue();
        assertThat(defaults.withShutdownHook(false).installShutdownHook()).isFalse();
    }

    @Test
    void theDocumentedSessionIoDefaultsAreTheShippedOnes() {
        IoOptions defaults = IoOptions.defaults();
        assertThat(defaults.streamed())
                .as("the default run uses the owned terminal")
                .isFalse();
        assertThat(defaults.input()).isNull();
        assertThat(defaults.output()).isNull();
        assertThat(defaults.renderer()).as("the default run paints frames").isTrue();
        assertThat(defaults.diagnostics())
                .as("the default run reports no diagnostics")
                .isNull();
        assertThat(defaults.environment())
                .as("no environment override by default")
                .isEmpty();
        assertThat(IoOptions.DEFAULT_ROWS).isEqualTo(24);
        assertThat(IoOptions.DEFAULT_COLUMNS).isEqualTo(80);
        assertThat(defaults.size()).isEqualTo(new io.github.dhoard.braxton.layout.Size(24, 80));
        assertThat(defaults.withSize(10, 40).size()).isEqualTo(new io.github.dhoard.braxton.layout.Size(10, 40));
        assertThat(defaults.withRenderer(false).renderer()).isFalse();
        assertThatThrownBy(() -> new IoOptions(new java.io.StringReader(""), null, null, null, true, null))
                .as("half a stream pair is rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDocumentedInputAndClipboardLimitsAreTheShippedOnes() {
        // The runtime reads these limits from InputOptions.DEFAULT, not from the convenience
        // overloads' constants, so the shipped values are what this test has to pin.
        InputOptions input = InputOptions.DEFAULT;
        assertThat(input.escapeTimeout()).as("escape timeout").isEqualTo(Duration.ofMillis(30));
        assertThat(input.maxCsiCharacters()).as("CSI length bytes").isEqualTo(64);
        assertThat(input.maxPasteBytes()).as("paste payload bytes").isEqualTo(1_048_576);
        assertThat(input.pasteIdleTimeout()).as("paste idle timeout").isEqualTo(Duration.ofMillis(250));
        assertThat(PasteDecoder.DEFAULT_MAX_BYTES).as("paste payload bytes").isEqualTo(1_048_576);
        assertThat(PasteDecoder.DEFAULT_IDLE_TIMEOUT_MILLIS)
                .as("paste idle timeout ms")
                .isEqualTo(250L);
        assertThat(ClipboardConfig.DEFAULT_MAX_PAYLOAD_BYTES)
                .as("OSC 52 payload bytes")
                .isEqualTo(65_536);
        assertThat(ClipboardConfig.DEFAULT_MAX_INTERNAL_CHARS)
                .as("in-process buffer chars")
                .isEqualTo(1_048_576);
        assertThat(ClipboardConfig.DEFAULT_MAX_READ_PAYLOAD_BYTES)
                .as("OSC 52 read payload bytes")
                .isEqualTo(1_048_576);
        assertThat(ClipboardConfig.defaults().terminalReadsEnabled())
                .as("terminal clipboard reads")
                .isTrue();
        assertThat(ClipboardConfig.defaults().maxReadEncodedCharacters())
                .as("OSC 52 read reply characters, with the selection field")
                .isEqualTo(1_398_106);
        assertThat(TerminalQueries.DEFAULT_TIMEOUT).as("query reply deadline").isEqualTo(Duration.ofSeconds(1));
        assertThat(TerminalQueries.DEFAULT_MAX_OUTSTANDING).as("live queries").isEqualTo(16);
        assertThat(InputDecoder.MAX_REPLY_CHARACTERS)
                .as("reply payload characters")
                .isEqualTo(4_096);
        assertThat(InputDecoder.MAX_CLIPBOARD_REPLY_CHARACTERS)
                .as("clipboard reply characters")
                .isEqualTo(1_398_106);
        assertThat(RuntimeOptions.DEFAULT_COLOR_PROFILE).as("color profile").isEqualTo(ColorProfile.AUTO);
        assertThat(TerminalPresentation.MAX_TITLE_BYTES)
                .as("window title bytes")
                .isEqualTo(4_096);
        assertThat(TerminalPresentation.Status.MAX_ID_LENGTH)
                .as("status record id characters")
                .isEqualTo(128);
        assertThat(TerminalPresentation.Status.MAX_ID_SEGMENT)
                .as("status record id segment")
                .isEqualTo(32);
        assertThat(TerminalPresentation.Status.MAX_ID_DEPTH)
                .as("status record id depth")
                .isEqualTo(8);
        assertThat(TerminalPresentation.Status.MAX_APP_LENGTH)
                .as("program identifier characters")
                .isEqualTo(32);
        assertThat(TerminalPresentation.Status.MAX_STATUS_TITLE_BYTES)
                .as("status record label bytes")
                .isEqualTo(192);
        assertThat(TerminalPresentation.Status.MAX_MESSAGE_BYTES)
                .as("status record message bytes")
                .isEqualTo(2_048);
    }
}
