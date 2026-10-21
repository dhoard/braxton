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

package io.github.dhoard.braxton.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalOptions;

/**
 * The in-memory session must behave like the real one where the runtime depends on it.
 *
 * <p>Every runtime test runs against this session, so a divergence from
 * {@code BaseTerminalSession} silently hides the behaviour a real terminal would show.</p>
 */
class FakeTerminalTest {

    @Test
    void aClosedSessionRecordsNothingBecauseARealOneDropsTheWrite() throws IOException {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.open(new Size(6, 20), TerminalOptions.DEFAULT);
        terminal.write("frame");
        terminal.close();

        terminal.write("after close");
        assertThat(terminal.isOpen()).as("a closed session is not usable").isFalse();
        assertThat(terminal.writes()).as("a closed session keeps nothing it was handed")
                .containsExactly("frame");

        terminal.input().read();
        assertThat(terminal.input().read())
                .as("closing wakes the reader with end of input")
                .isEqualTo(-1);
    }

    @Test
    void aClosedSessionUsesTheRealSessionsFallbackSize() {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.open(new Size(6, 20), TerminalOptions.DEFAULT);
        assertThat(terminal.size()).isEqualTo(new Size(6, 20));
        terminal.close();
        terminal.resize(new Size(3, 4));
        assertThat(terminal.size()).isEqualTo(new Size(24, 80));
    }

    @Test
    void invalidSizeProbesUseTheRealSessionsFallback() {
        FakeTerminal terminal = new FakeTerminal();
        terminal.resize(null);
        assertThat(terminal.size()).isEqualTo(new Size(24, 80));
        terminal.resize(new Size(0, 0));
        assertThat(terminal.size()).isEqualTo(new Size(24, 80));
    }

    @Test
    void bulkReadsHonorZeroLengthAndValidateBeforeConsuming() throws IOException {
        FakeTerminal terminal = new FakeTerminal();
        terminal.feed("abc");
        byte[] buffer = {9, 9, 9, 9};
        assertThat(terminal.input().read(buffer, buffer.length, 0)).isZero();
        assertThat(terminal.input().available()).isEqualTo(3);
        assertThatThrownBy(() -> terminal.input().read(buffer, 3, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> terminal.input().read(buffer, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> terminal.input().read(null, 0, 1))
                .isInstanceOf(NullPointerException.class);
        assertThat(terminal.input().available()).isEqualTo(3);
        assertThat(terminal.input().read(buffer, 1, 2)).isEqualTo(2);
        assertThat(buffer).containsExactly((byte) 9, (byte) 'a', (byte) 'b', (byte) 9);
        assertThat(terminal.input().read()).isEqualTo('c');
        terminal.endInput();
        assertThat(terminal.input().read(buffer, 0, 0)).isZero();
        assertThat(terminal.input().read(buffer, 0, 1)).isEqualTo(-1);
    }

    @Test
    void failingInputWakesAnAlreadyBlockedReader() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        java.util.concurrent.FutureTask<Integer> result = new java.util.concurrent.FutureTask<>(
                () -> terminal.input().read());
        Thread reader = Thread.ofVirtual().start(result);
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (reader.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(reader.getState()).as("reader reached the scripted input wait")
                    .isEqualTo(Thread.State.TIMED_WAITING);
            IllegalStateException failure = new IllegalStateException("broken input");
            terminal.failInput(failure);
            assertThatThrownBy(() -> result.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCause(failure);
        } finally {
            terminal.close();
            reader.join(5_000);
        }
        assertThat(reader.isAlive()).isFalse();
    }

    @Test
    void anOpenedSessionKeepsEveryWriteInOrder() throws IOException {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.open(new Size(6, 20), TerminalOptions.DEFAULT);
        terminal.write("one");
        terminal.write("");
        terminal.write(null);
        terminal.write("two");
        assertThat(terminal.writes()).containsExactly("one", "two");
        assertThat(terminal.output()).isEqualTo("onetwo");
        terminal.close();
    }
}
