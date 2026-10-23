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

package io.github.dhoard.braxton.internal.runtime;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.app.*;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.view.*;

@Timeout(10)
class JLineRuntimeTest {
    private static Program<Integer, String> program(Command<String> command) {
        return new Program<>() {
            public Transition<Integer, String> init(InitContext context) {
                return Transition.next(0, command);
            }

            public Transition<Integer, String> update(Integer model, String message) {
                return Transition.next(model);
            }

            public View<String> view(Integer model, Viewport viewport) {
                return Views.text("ready");
            }
        };
    }

    @Test
    void aRealStreamTerminalUsesRawInputAndRestoresApplicationModes() {
        try (var fixture = new JLineTestTerminal()) {
            var result = JLineTestTerminal.run(
                    program(Command.quit(7)), RuntimeOptions.defaults(), fixture);
            assertThat(result.status()).isEqualTo(7);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(fixture.outputText())
                    .contains(
                            "ready",
                            "\u001b[?1049h",
                            "\u001b[?1049l",
                            "\u001b[?2004l",
                            "\u001b[?25h");
            assertThat(fixture.isOpen()).isFalse();
        }
    }

    @Test
    void callerInterruptionPreservesItsFlagAndReturnsAnExplicitResult() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var entered = new CountDownLatch(1);
            var result = new AtomicReference<ExitResult>();
            var flag = new AtomicBoolean();
            var services = RuntimeServices.system().withEffectExecutor(Runnable::run);
            var p = program(Command.task(
                    io.github.dhoard.braxton.effect.EffectId.of("ready"),
                    () -> {
                        entered.countDown();
                        return "ready";
                    },
                    v -> v,
                    f -> "failed"));
            Thread owner = Thread.ofVirtual().start(() -> {
                result.set(new RuntimeLoop<>(
                                p, RuntimeOptions.defaults(), fixture.terminal(), services)
                        .run());
                flag.set(Thread.currentThread().isInterrupted());
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            owner.interrupt();
            owner.join(5000);
            assertThat(owner.isAlive()).isFalse();
            assertThat(flag).isTrue();
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INTERRUPTED);
            assertThat(result.get().status()).isEqualTo(130);
            assertThat(fixture.outputText()).contains("\u001b[?25h");
        }
    }

    @Test
    void allInputOptionsRejectInvalidTimingAndOverflowBeforeOpeningATerminal() {
        assertThatThrownBy(() -> new io.github.dhoard.braxton.input.InputOptions(
                        java.time.Duration.ZERO,
                        64,
                        1,
                        java.time.Duration.ofMillis(10),
                        java.time.Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new io.github.dhoard.braxton.input.InputOptions(
                        java.time.Duration.ofSeconds(Long.MAX_VALUE),
                        64,
                        1,
                        java.time.Duration.ofMillis(10),
                        java.time.Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printWriterErrorsFailStartupAndStillReleaseTheTerminal() {
        try (var fixture = new JLineTestTerminal()) {
            fixture.failOutput();
            var result = JLineTestTerminal.run(
                    program(Command.quit()), RuntimeOptions.defaults(), fixture);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure()).hasMessage("terminal output failed");
            assertThat(fixture.isOpen()).isFalse();
            assertThat(result.cleanupFailures()).isNotEmpty();
        }
    }

    @Test
    void shutdownRestorationClosesAdmissionBeforeJLineClosesTheTerminal() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var painted = new CountDownLatch(1);
            fixture.onOutput(text -> {
                if (text.contains("ready")) painted.countDown();
            });
            var loop = new RuntimeLoop<>(
                    program(Command.none()),
                    RuntimeOptions.defaults(),
                    fixture.terminal(),
                    RuntimeServices.system());
            var result = new AtomicReference<ExitResult>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            try {
                assertThat(painted.await(5, TimeUnit.SECONDS)).isTrue();
                loop.shutdownRestore();
                owner.join(5000);
                assertThat(owner.isAlive()).isFalse();
                assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
                String output = fixture.outputText();
                assertThat(output.indexOf("ready")).isLessThan(output.indexOf("\u001b[?1049l"));
                assertThat(output.substring(output.indexOf("\u001b[?1049l")))
                        .doesNotContain("ready");
            } finally {
                fixture.endInput();
                owner.join(5000);
            }
        }
    }
}
