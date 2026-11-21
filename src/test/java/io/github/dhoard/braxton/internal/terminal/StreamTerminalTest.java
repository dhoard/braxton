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

package io.github.dhoard.braxton.internal.terminal;

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.Capabilities;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamTerminalTest {
    @Test
    void streamEndpointsOutliveTheSessionAndResizeNotificationsDescribeRealChanges() throws Exception {
        class Input extends StringReader {
            boolean closed;

            Input() {
                super("abc");
            }

            public void close() {
                closed = true;
            }
        }
        class Output extends StringWriter {
            boolean closed;

            public void close() {
                closed = true;
            }
        }
        Input input = new Input();
        Output output = new Output();
        var terminal = StreamTerminal.of(input, output, null);
        assertThat(terminal.size()).isEqualTo(new Size(24, 80));
        assertThat(terminal.capabilities()).isSameAs(Capabilities.PLAIN);
        terminal.enterRawMode();
        terminal.boundOutput();
        assertThat(terminal.state()).isEqualTo(Terminal.State.ACTIVE);
        AtomicInteger resizes = new AtomicInteger();
        terminal.onResize(resizes::incrementAndGet);
        assertThatThrownBy(() -> terminal.resize(null)).isInstanceOf(IllegalArgumentException.class);
        terminal.resize(Size.EMPTY);
        terminal.resize(terminal.size());
        assertThat(terminal.resizeEpoch()).isZero();
        terminal.resize(new Size(10, 20));
        assertThat(resizes.get()).isEqualTo(1);
        assertThat(terminal.resizeEpoch()).isEqualTo(1);
        terminal.onResize(null);
        terminal.resize(new Size(12, 20));
        assertThat(resizes.get()).isEqualTo(1);
        assertThat(terminal.resizeEpoch()).isEqualTo(2);
        assertThat(terminal.reader().read(1000)).isEqualTo('a');
        terminal.writer().write("written");
        terminal.writer().close();
        terminal.close();
        terminal.close();
        assertThat(terminal.state()).isEqualTo(Terminal.State.CLOSED);
        assertThat(input.closed).isFalse();
        assertThat(output.closed).isFalse();
        output.write(" after");
        assertThat(output.toString()).isEqualTo("written after");
    }

    @Test
    void headlessEndpointsDiscardOutputAndFinishInputWithoutADevice() throws Exception {
        try (var terminal = StreamTerminal.headless(null)) {
            assertThat(terminal.reader().read(1)).isEqualTo(-1);
            terminal.writer().print("discarded");
            terminal.writer().flush();
            terminal.writer().close();
            assertThat(terminal.outputError()).isNull();
        }
    }

    @Test
    void writerAndFlushFailuresRetainTheirCauseWithoutClosingTheDestination() throws Exception {
        for (boolean failWrite : new boolean[] {true, false}) {
            IOException failure = new IOException("owned sink failure");
            Writer output = new Writer() {
                public void write(char[] chars, int offset, int length) throws IOException {
                    if (failWrite) throw failure;
                }

                public void flush() throws IOException {
                    if (!failWrite) throw failure;
                }

                public void close() {
                    throw new AssertionError("caller owns this writer");
                }
            };
            try (var terminal = StreamTerminal.of(null, output, new Size(2, 3))) {
                terminal.writer().print("x");
                terminal.writer().flush();
                assertThat(terminal.writer().checkError()).isTrue();
                assertThat(terminal.outputError()).isSameAs(failure);
            }
        }
    }

    @Test
    void suppliedTerminalDefaultsDoNotClaimDeviceFeatures() throws Exception {
        StringWriter output = new StringWriter();
        Terminal terminal = new Terminal() {
            public TimedReader reader() {
                return new CharacterInput();
            }

            public PrintWriter writer() {
                return new PrintWriter(output);
            }

            public Size size() {
                return new Size(2, 3);
            }

            public Capabilities capabilities() {
                return Capabilities.PLAIN;
            }

            public void enterRawMode() {}

            public void close() {}
        };
        assertThat(terminal.state()).isEqualTo(Terminal.State.ACTIVE);
        assertThat(terminal.outputError()).isNull();
        terminal.release();
        terminal.reacquire();
        terminal.boundOutput();
        terminal.onResize(() -> {});
        terminal.registerSuspendBytes("restore", "resume");
        assertThat(terminal.resizeEpoch()).isZero();
        assertThat(terminal.resumeEpoch()).isZero();
        assertThat(terminal.foreground()).isFalse();
        assertThatThrownBy(terminal::suspend).isInstanceOf(IOException.class).hasMessageContaining("no job control");
        terminal.beginSynchronizedUpdate();
        terminal.endSynchronizedUpdate();
        assertThat(output.toString()).isEqualTo("\u001b[?2026h\u001b[?2026l");
    }

    @Test
    void nativeStallPolicyRejectsInvalidDurationsAndSaturatesOverflow() {
        assertThatThrownBy(() -> NativeTerminal.stallNanos(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTerminal.stallNanos(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTerminal.stallNanos(java.time.Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NativeTerminal.stallNanos(java.time.Duration.ofSeconds(2))).isEqualTo(2_000_000_000L);
        assertThat(NativeTerminal.stallNanos(java.time.Duration.ofSeconds(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);
    }
}
