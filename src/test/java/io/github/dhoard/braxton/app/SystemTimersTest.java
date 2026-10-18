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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** The scheduler never fires a delay before the requested time. */
class SystemTimersTest {

    @Test
    void aDelayThatRoundsUpPastTheLongRangeDoesNotFireImmediately() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        Timers.Handle handle = SystemTimers.INSTANCE.schedule(Long.MAX_VALUE, ran::countDown);
        try {
            assertThat(ran.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            handle.cancel();
        }
    }

    @Test
    void aScheduledTaskRunsOnAVirtualThread() throws InterruptedException {
        java.util.concurrent.atomic.AtomicBoolean virtual = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch ran = new CountDownLatch(1);
        Timers.Handle handle = SystemTimers.INSTANCE.schedule(1_000_000L, () -> {
            virtual.set(Thread.currentThread().isVirtual());
            ran.countDown();
        });
        try {
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            handle.cancel();
        }
        assertThat(virtual.get()).isTrue();
    }

    @Test
    void aShortDelayStillFires() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        Timers.Handle handle = SystemTimers.INSTANCE.schedule(1_000_000L, ran::countDown);
        try {
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            handle.cancel();
        }
    }
}
