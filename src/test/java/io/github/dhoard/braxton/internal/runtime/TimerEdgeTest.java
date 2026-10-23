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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Timer edges: a zero delay still runs, cancel is idempotent, and a zero delay cannot overflow. */
class TimerEdgeTest {

    @Test
    @Timeout(10)
    void aZeroOrNegativeDelayRunsAndCancelIsIdempotent() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Timers.Handle zero = Timers.system().schedule(0L, () -> {
            calls.incrementAndGet();
            ran.countDown();
        });
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        zero.cancel();
        zero.cancel();
        CountDownLatch negative = new CountDownLatch(1);
        Timers.Handle handle = Timers.system().schedule(-1_000_000L, negative::countDown);
        assertThat(negative.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();
        assertThat(calls.get()).isEqualTo(1);
    }
}
