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

package io.github.dhoard.braxton.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The terminal write policy, driven by a scripted native attempt and a manual clock: slowness is
 * waited out, only a complete stall fails, shutdown shortens the stall limit, and a terminal error
 * fails at once.
 */
@Timeout(5)
class OutputDrainTest {
    private static final long MS = 1_000_000L;
    private static final long RUNNING = 30_000 * MS;

    /** A scripted terminal: each step accepts that many bytes (0 = none) and advances the clock. */
    private static final class Script implements OutputDrain.Attempt {
        final AtomicLong clock = new AtomicLong();
        final ArrayDeque<int[]> steps = new ArrayDeque<>();
        final List<int[]> calls = new ArrayList<>();
        Runnable onEachCall = () -> {};

        Script step(int accepted, long elapsedMillis) {
            steps.addLast(new int[] {accepted, (int) elapsedMillis});
            return this;
        }

        public int write(int offset, int length, int timeoutMillis) {
            calls.add(new int[] {offset, length, timeoutMillis});
            onEachCall.run();
            int[] step = steps.isEmpty() ? new int[] {0, OutputDrain.ATTEMPT_MILLIS} : steps.removeFirst();
            clock.addAndGet(step[1] * MS);
            return step[0] > 0 ? Math.min(step[0], length) : step[0];
        }
    }

    @Test
    void aSlowTerminalThatKeepsAcceptingBytesNeverFails() throws IOException {
        // Ten seconds for 10 bytes: far past the old one-second budget, but never a full stall.
        var script = new Script();
        for (int i = 0; i < 10; i++) script.step(0, 900).step(1, 100);
        OutputDrain.drain(10, script, script.clock::get, 2_000 * MS, () -> false);
        assertThat(script.clock.get()).isEqualTo(10_000 * MS);
        assertThat(script.calls).extracting(call -> call[2]).containsOnly(OutputDrain.ATTEMPT_MILLIS);
    }

    @Test
    void partialProgressResumesAtTheFirstUnwrittenByte() throws IOException {
        var script = new Script().step(3, 0).step(0, 100).step(4, 0).step(1, 0);
        OutputDrain.drain(8, script, script.clock::get, RUNNING, () -> false);
        assertThat(script.calls)
                .extracting(call -> call[0] + ":" + call[1])
                .containsExactly("0:8", "3:5", "3:5", "7:1");
    }

    @Test
    void aTerminalThatAcceptsNothingFailsAtTheRunningStallLimit() {
        var script = new Script().step(2, 0);
        assertThatThrownBy(() -> OutputDrain.drain(5, script, script.clock::get, 2_000 * MS, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessage("terminal accepted no output for 2000 ms (3 of 5 bytes unwritten)");
        // The stall is measured from the last progress, then one attempt at a time.
        assertThat(script.clock.get()).isEqualTo(2_000 * MS);
    }

    @Test
    void shutdownDuringAStalledWriteShortensItToTheShutdownLimit() {
        var script = new Script();
        var shuttingDown = new AtomicBoolean();
        // Five seconds into a stall the runtime begins shutdown; the next check gives up.
        script.onEachCall = () -> {
            if (script.clock.get() >= 5_000 * MS) shuttingDown.set(true);
        };
        assertThatThrownBy(() -> OutputDrain.drain(4, script, script.clock::get, RUNNING, shuttingDown::get))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("terminal accepted no output for 51")
                .hasMessageContaining("during shutdown");
        assertThat(script.clock.get()).isLessThanOrEqualTo(5_100 * MS + OutputDrain.ATTEMPT_MILLIS * MS);
    }

    @Test
    void duringShutdownProgressStillResetsTheShorterLimit() throws IOException {
        var script = new Script().step(0, 900).step(1, 0).step(0, 900).step(1, 0);
        OutputDrain.drain(2, script, script.clock::get, RUNNING, () -> true);
        assertThat(script.clock.get()).isEqualTo(1_800 * MS);
    }

    @Test
    void aRunningLimitShorterThanTheShutdownLimitStillApplies() {
        var script = new Script();
        assertThatThrownBy(() -> OutputDrain.drain(1, script, script.clock::get, 300 * MS, () -> true))
                .hasMessageStartingWith("terminal accepted no output for 300 ms");
    }

    @Test
    void aTerminalErrorFailsAtOnceWithItsErrno() {
        var script = new Script().step(4, 0).step(-5, 0);
        assertThatThrownBy(() -> OutputDrain.drain(8, script, script.clock::get, RUNNING, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessage("cannot write terminal (errno 5)");
        assertThat(script.calls).hasSize(2);
    }

    @Test
    void aCountBeyondTheRequestIsRejected() {
        OutputDrain.Attempt overreport = (offset, length, timeout) -> length + 1;
        assertThatThrownBy(() -> OutputDrain.drain(4, overreport, () -> 0L, RUNNING, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("more bytes than requested");
    }
}
