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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.runtime.JLineTestTerminal;

/** Cancelling a finished effect is a no-op and cancelAll does not block new work afterwards. */
class EffectLifecycleEdgeTest {

    @Test
    void cancellingACompletedDelayIsANoOpAndCancelAllDoesNotBlockLaterWork() throws Exception {
        Mailbox mailbox = new Mailbox(16);
        ManualTimers timers = new ManualTimers();
        RuntimeServices environment = new RuntimeServices(() -> 1_000L, Runnable::run, timers,
                message -> { });
        ClipboardService clipboard = new ClipboardService(ClipboardConfig.defaults(),
                new TerminalEnvironment(Map.of("TERM", "dumb")), message -> { });
        EffectRunner<String> runner = new EffectRunner<>(mailbox, environment, clipboard, 4);
        runner.bindOwner(Thread.currentThread());
        EffectId id = EffectId.of("a");
        runner.interpret(Command.delay(id, Duration.ofMillis(10), "tick", "rejected"));
        timers.advance(10_000_000L);
        assertThat(((Mailbox.AppMessage) mailbox.poll(20)).payload()).isEqualTo("tick");
        runner.interpret(Command.cancel(id));
        timers.advance(10_000_000L);
        assertThat(mailbox.size()).isZero();
        runner.cancelAll();
        runner.interpret(Command.delay(EffectId.of("b"), Duration.ofMillis(10), "second", "rejected"));
        timers.advance(10_000_000L);
        assertThat(((Mailbox.AppMessage) mailbox.poll(20)).payload()).isEqualTo("second");
        assertThat(List.of()).isEmpty();
    }
}
