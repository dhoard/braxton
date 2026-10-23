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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.internal.runtime.JLineTestTerminal;

/** A source that fails to close is reported, not propagated. */
class SubscriptionCloseFailureTest {

    @Test
    void aFailingCloseIsReportedThroughDiagnostics() {
        List<String> diagnostics = new ArrayList<>();
        SubscriptionManager<String> manager = new SubscriptionManager<>(new Mailbox(8),
                new ManualTimers(), 4, diagnostics::add);
        Subscription<String> failing = Subscription.source(SubscriptionId.of("bad"), "cfg",
                emitter -> () -> {
                    throw new IllegalStateException("cannot close");
                }, value -> "value");
        manager.reconcile(failing);
        manager.closeAll();
        assertThat(diagnostics).anySatisfy(message ->
                assertThat(message).contains("bad").contains("close failed"));
        manager.closeAll();
        assertThat(diagnostics).hasSize(1);
    }

    @Test
    void anIntervalWithAnOverflowingPeriodIsRejectedAtConstruction() {
        assertThatThrownBy(() -> Subscription.interval(SubscriptionId.of("t"),
                Duration.ofSeconds(Long.MAX_VALUE), time -> "t"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nanoseconds");
    }
}
