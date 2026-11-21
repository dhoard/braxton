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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * Exercises Java ownership against a deterministic transport, without native access or a TTY.
 */
class NativeTerminalTest {
    static class Transport implements TerminalTransport {
        int reads, raw, releases, reacquires, closes, error, readResult = -1;
        int foreground = 1, epoch = -1, maxWrite = Integer.MAX_VALUE;
        String restore, resume;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch entered, unblock;

        public int read(byte[] bytes, int timeout) throws IOException {
            reads++;
            if (entered != null) {
                entered.countDown();
                try {
                    if (!unblock.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if (readResult > 0) bytes[0] = 'a';
            return readResult;
        }

        public int write(byte[] bytes, int offset, int count, int timeout) {
            if (error != 0) return -error;
            count = Math.min(count, maxWrite);
            output.write(bytes, offset, count);
            return count;
        }

        public int raw() {
            raw++;
            return error;
        }

        public int release() {
            releases++;
            return error;
        }

        public int reacquire() {
            reacquires++;
            return error;
        }

        public int foreground() throws IOException {
            return foreground;
        }

        public int suspend() {
            return error;
        }

        public int resizeEpoch() throws IOException {
            return epoch;
        }

        public int resumeEpoch() throws IOException {
            return epoch;
        }

        public int registerSuspendBytes(String before, String after) {
            restore = before;
            resume = after;
            return error;
        }

        public Size size() throws IOException {
            if (error != 0) throw new IOException("size");
            return new Size(24, 80);
        }

        public void close() throws IOException {
            closes++;
            if (error != 0) throw new IOException("close");
        }
    }

    NativeTerminal terminal(Transport transport) {
        return new NativeTerminal(Capabilities.PLAIN, 1_000_000L, transport);
    }

    @Test
    void readsWritesAndUnsignedEpochsUseTheTransport() throws Exception {
        Transport transport = new Transport();
        transport.maxWrite = 17;
        try (var terminal = terminal(transport)) {
            assertThat(terminal.capabilities()).isSameAs(Capabilities.PLAIN);
            assertThat(terminal.size()).isEqualTo(new Size(24, 80));
            assertThat(terminal.foreground()).isTrue();
            assertThat(terminal.resizeEpoch()).isEqualTo(4294967295L);
            assertThat(terminal.resumeEpoch()).isEqualTo(4294967295L);
            terminal.enterRawMode();
            assertThat(transport.raw).isEqualTo(1);
            String text = "unicode 🌳".repeat(2000);
            terminal.writer().write(text);
            terminal.writer().flush();
            assertThat(transport.output.toString(StandardCharsets.UTF_8)).isEqualTo(text);
            assertThat(terminal.outputError()).isNull();
            transport.readResult = 1;
            assertThat(terminal.reader().read(10)).isEqualTo('a');
            transport.readResult = 0;
            assertThat(terminal.reader().read(10)).isEqualTo(TimedReader.READ_EXPIRED);
            transport.readResult = -7;
            assertThatThrownBy(() -> terminal.reader().read(10))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("errno 5");
            transport.readResult = -1;
            assertThat(terminal.reader().read(10)).isEqualTo(-1);
            terminal.registerSuspendBytes(null, null);
            assertThat(transport.restore).isEmpty();
            assertThat(transport.resume).isEmpty();
            terminal.registerSuspendBytes("before", "after");
            assertThat(transport.restore).isEqualTo("before");
            assertThat(transport.resume).isEqualTo("after");
            terminal.suspend();
            terminal.boundOutput();
        }
        assertThat(transport.closes).isEqualTo(1);
    }

    @Test
    void handoffsAreIdempotentAndFailuresRestoreTheOwnedState() throws Exception {
        Transport t = new Transport();
        try (var terminal = terminal(t)) {
            terminal.reacquire();
            assertThat(t.reacquires).isZero();
            t.error = 5;
            assertThatThrownBy(terminal::release).isInstanceOf(IOException.class);
            assertThat(terminal.state()).isEqualTo(Terminal.State.ACTIVE);
            assertThatThrownBy(terminal::enterRawMode).isInstanceOf(IOException.class);
            assertThatThrownBy(terminal::suspend).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> terminal.registerSuspendBytes("a", "b")).isInstanceOf(IOException.class);
            assertThatThrownBy(terminal::size)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(IOException.class);
            t.error = 0;
            terminal.release();
            terminal.release();
            assertThat(t.releases).isEqualTo(2);
            assertThat(terminal.state()).isEqualTo(Terminal.State.RELEASED);
            assertThat(terminal.reader().read(10)).isEqualTo(TimedReader.READ_EXPIRED);
            assertThat(t.reads).isZero();
            assertThatThrownBy(terminal::enterRawMode)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("released");
            terminal.writer().print("refused");
            terminal.writer().flush();
            assertThat(terminal.outputError()).hasMessageContaining("released");
            t.error = 5;
            assertThatThrownBy(terminal::reacquire).isInstanceOf(IOException.class);
            assertThat(terminal.state()).isEqualTo(Terminal.State.RELEASED);
            t.error = 0;
            terminal.reacquire();
            terminal.reacquire();
            assertThat(t.reacquires).isEqualTo(2);
            t.foreground = 0;
            assertThat(terminal.foreground()).isFalse();
            t.foreground = -5;
            assertThatThrownBy(terminal::foreground)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("errno 5");
        }
    }

    @Test
    void closeIsFinalEvenWhenRestorationFails() throws Exception {
        Transport t = new Transport();
        NativeTerminal terminal = terminal(t);
        t.error = 5;
        assertThatThrownBy(terminal::close).isInstanceOf(IOException.class).hasMessage("close");
        terminal.close();
        assertThat(t.closes).isEqualTo(1);
        assertThat(terminal.state()).isEqualTo(Terminal.State.CLOSED);
        assertThat(terminal.foreground()).isFalse();
        assertThat(terminal.resumeEpoch()).isZero();
        assertThat(terminal.reader().read(10)).isEqualTo(-1);
        assertThatThrownBy(terminal::release).isInstanceOf(IOException.class).hasMessage("terminal closed");
        assertThatThrownBy(terminal::reacquire).isInstanceOf(IOException.class).hasMessage("terminal closed");
        assertThatThrownBy(terminal::enterRawMode)
                .isInstanceOf(IOException.class)
                .hasMessage("terminal closed");
        assertThatThrownBy(terminal::suspend).isInstanceOf(IOException.class).hasMessage("terminal closed");
        assertThatThrownBy(() -> terminal.registerSuspendBytes("", "")).isInstanceOf(IOException.class);
        assertThatThrownBy(terminal::size)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("terminal closed");
        terminal.writer().write("a");
        terminal.writer().flush();
        assertThat(terminal.outputError()).hasMessage("terminal closed");
    }

    @Test
    void nativeWriteErrorsAndStallsAreVisibleThroughPrintWriter() throws Exception {
        for (int error : new int[] {5, 0}) {
            Transport t = new Transport();
            try (var terminal = terminal(t)) {
                t.error = error;
                t.maxWrite = 0;
                terminal.writer().print("data");
                terminal.writer().flush();
                assertThat(terminal.writer().checkError()).isTrue();
                assertThat(terminal.outputError()).isInstanceOf(IOException.class);
                t.error = 0;
            }
        }
    }

    @Test
    void closeWaitsForAnInFlightReadBeforeFreeingTheContext() throws Exception {
        Transport t = new Transport();
        t.entered = new CountDownLatch(1);
        t.unblock = new CountDownLatch(1);
        NativeTerminal terminal = terminal(t);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> read = executor.submit(() -> terminal.reader().read(100));
            assertThat(t.entered.await(2, TimeUnit.SECONDS)).isTrue();
            CountDownLatch closing = new CountDownLatch(1);
            Future<?> close = executor.submit(() -> {
                closing.countDown();
                terminal.close();
                return null;
            });
            assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(t.closes).isZero();
            t.unblock.countDown();
            assertThat(read.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            close.get(2, TimeUnit.SECONDS);
            assertThat(t.closes).isEqualTo(1);
        } finally {
            t.unblock.countDown();
            terminal.close();
        }
    }

    @Test
    void transportQueryFailuresKeepTheirCauses() throws Exception {
        IOException failure = new IOException("transport query");
        Transport transport = new Transport() {
            public int resizeEpoch() throws IOException {
                throw failure;
            }

            public int resumeEpoch() throws IOException {
                throw failure;
            }

            public int foreground() throws IOException {
                throw failure;
            }
        };
        try (var terminal = terminal(transport)) {
            assertThatThrownBy(terminal::resizeEpoch)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCause(failure);
            assertThatThrownBy(terminal::resumeEpoch)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCause(failure);
            assertThatThrownBy(terminal::foreground)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCause(failure);
        }
    }
}
