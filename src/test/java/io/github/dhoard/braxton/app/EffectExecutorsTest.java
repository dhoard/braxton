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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The shared effect executor gives every application task its own virtual thread. */
class EffectExecutorsTest {

    @Test
    @Timeout(10)
    void eachEffectTaskRunsOnAVirtualThread() throws InterruptedException {
        AtomicReference<Thread> first = new AtomicReference<>();
        AtomicReference<Thread> second = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(2);
        java.util.concurrent.ExecutorService executor = EffectExecutors.shared();
        executor.execute(() -> {
            first.set(Thread.currentThread());
            ran.countDown();
        });
        executor.execute(() -> {
            second.set(Thread.currentThread());
            ran.countDown();
        });
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(first.get().isVirtual()).isTrue();
        assertThat(second.get().isVirtual()).isTrue();
        assertThat(first.get().getName()).startsWith("braxton-effect-");
        assertThat(second.get().getName()).startsWith("braxton-effect-");
        assertThat(first.get()).isNotSameAs(second.get());
    }
}
