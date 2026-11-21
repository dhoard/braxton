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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.layout.Size;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The R-01 capability matrix and its bounded, silent fallback behavior.
 */
class CapabilitiesTest {

    @Test
    void modernAndUnknownTerminalsStartWithPerFeatureEvidence() throws Exception {
        for (String term : List.of("st-256color", "konsole-256color", "contour", "my-vt-256color")) {
            Capabilities caps = Capabilities.inspect(term);
            assertThat(caps.term()).isEqualTo(term);
            assertThat(caps.report().results()).hasSize(4);
            for (Capability capability : Capability.values()) {
                assertThat(caps.report().result(capability).capability()).isEqualTo(capability);
            }
        }
        assertThat(Capabilities.inspect("st-256color").alternateScreen()).isTrue();
        assertThat(Capabilities.inspect("xterm-direct").color()).isTrue();
        assertThat(Capabilities.inspect("my-vt-256color").report().enabledCapabilities())
                .isEmpty();
    }

    @Test
    void unsetAndDumbTerminalsFailWithATypedDocumentedError() {
        assertThatThrownBy(() -> Capabilities.inspect(null))
                .isInstanceOf(UnsupportedTerminalException.class)
                .hasMessageContaining("TERM=null");
        assertThatThrownBy(() -> Capabilities.inspect("dumb"))
                .isInstanceOf(UnsupportedTerminalException.class)
                .hasMessageContaining("TERM=dumb");
    }

    @Test
    void aPositiveReplyEnablesOnlyTheProbedFeature() throws Exception {
        CapabilityProbe probe = (question, timeout) -> question.equals("?1000") ? Optional.of("yes") : Optional.empty();
        Capabilities caps = Capabilities.inspect("linux", probe, Duration.ofMillis(20));

        assertThat(caps.mouse()).isTrue();
        assertThat(caps.report().result(Capability.MOUSE).source()).isEqualTo(CapabilitySource.PROBE);
        assertThat(caps.color()).isFalse();
        assertThat(caps.bracketedPaste()).isFalse();
    }

    @Test
    void anUnansweredProbeFallsBackWithinItsBound() throws Exception {
        CapabilityProbe silent = (question, timeout) -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Optional.of("yes");
        };
        long started = System.nanoTime();
        Capabilities caps = Capabilities.inspect("linux", silent, Duration.ofMillis(20));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(caps.report().result(Capability.MOUSE).source()).isEqualTo(CapabilitySource.FALLBACK);
        assertThat(caps.report().result(Capability.MOUSE).enabled()).isFalse();
    }

    @Test
    void typedTerminalExceptionsKeepTheirContext() {
        assertThat(new NoControllingTerminalException()).hasMessageContaining("controlling terminal");
        assertThat(new UnsupportedTerminalException("bad terminal (TERM=dumb)").term())
                .isEqualTo("dumb");
        assertThat(new TerminalException("wrapped", new java.io.IOException("cause")))
                .hasMessage("wrapped")
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void aProbeReplyNeverReachesTheApplicationAsAKey() throws Exception {
        try (TerminalSession session = TerminalSession.of(
                new StringReader("\u001b]4;1;rgb:ffff/ffff/ffff\u0007a"), new StringWriter(), new Size(4, 20))) {
            TerminalEvent event = session.read().orElseThrow();
            assertThat(event).isInstanceOf(TerminalEvent.Input.class);
            assertThat(((TerminalEvent.Input) event).value()).isInstanceOf(InputEvent.KeyInput.class);
            assertThat(((InputEvent.KeyInput) ((TerminalEvent.Input) event).value())
                            .key()
                            .name())
                    .isEqualTo("a");
        }
    }

    @Test
    void explicitFactsKeepTheirPerFeatureReports() {
        for (int flags = 0; flags < 16; flags++) {
            Capabilities facts = Capabilities.of(
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, "explicit-vt");
            assertThat(facts.report().result(Capability.COLOR).enabled()).isEqualTo(facts.color());
            assertThat(facts.report().result(Capability.ALTERNATE_SCREEN).enabled())
                    .isEqualTo(facts.alternateScreen());
            assertThat(facts.report().result(Capability.BRACKETED_PASTE).enabled())
                    .isEqualTo(facts.bracketedPaste());
            assertThat(facts.report().result(Capability.MOUSE).enabled()).isEqualTo(facts.mouse());
            assertThat(facts.report().results()).hasSize(4);
        }
    }
}
