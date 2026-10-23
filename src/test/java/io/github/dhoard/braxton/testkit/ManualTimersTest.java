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

import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.runtime.Timers;

class ManualTimersTest {
    @Test
    void equalDeadlinesKeepRegistrationOrderAcrossCancellationAndReentrantScheduling() {
        ManualTimers timers = new ManualTimers();
        List<String> calls = new ArrayList<>();
        Timers.Handle[] peer = new Timers.Handle[1];
        timers.schedule(10, () -> {
            calls.add("first");
            peer[0].cancel();
            timers.schedule(0, () -> calls.add("nested"));
        });
        peer[0] = timers.schedule(10, () -> calls.add("cancelled"));
        timers.schedule(10, () -> calls.add("third"));
        timers.advance(9);
        assertThat(calls).isEmpty();
        assertThat(timers.pending()).isEqualTo(3);
        timers.advance(1);
        assertThat(calls).containsExactly("first", "third", "nested");
        assertThat(timers.pending()).isZero();
        timers.advance(0);
        assertThat(calls).containsExactly("first", "third", "nested");
    }

    @Test
    void reentrantPositiveDelaysWaitForTheNextAdvanceAndNegativeAdvancesDoNotRewind() {
        ManualTimers timers = new ManualTimers();
        List<String> calls = new ArrayList<>();
        timers.schedule(5, () -> {
            calls.add("first");
            timers.schedule(2, () -> calls.add("second"));
        });
        timers.advance(5);
        assertThat(calls).containsExactly("first");
        assertThat(timers.pending()).isEqualTo(1);
        timers.advance(-10);
        assertThat(timers.nowNanos()).isEqualTo(5);
        timers.advance(1);
        assertThat(calls).containsExactly("first");
        timers.advance(1);
        assertThat(calls).containsExactly("first", "second");
        assertThat(timers.nowNanos()).isEqualTo(7);
        assertThat(timers.pending()).isZero();
    }
}
