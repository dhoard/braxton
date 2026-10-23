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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.internal.runtime.JLineTestTerminal;

/** An executor may retain a task until after its effect has been invalidated. */
class EffectTaskStartupTest {

    @Test
    void cancellingBeforeExecutorStartupDoesNotRunTheBody() throws Exception {
        verifyInvalidation(Command.cancel(EffectId.of("group/work")), false);
    }

    @Test
    void cancellingANamespaceBeforeExecutorStartupDoesNotRunTheBody() throws Exception {
        verifyInvalidation(Command.cancelNamespace("group"), false);
    }

    @Test
    void shutdownBeforeExecutorStartupDoesNotRunTheBody() throws Exception {
        verifyInvalidation(null, false);
    }

    @Test
    void replacingBeforeExecutorStartupRunsOnlyTheReplacement() throws Exception {
        verifyInvalidation(null, true);
    }

    private void verifyInvalidation(Command<String> cancellation, boolean replace) throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        List<String> bodies = new ArrayList<>();
        List<String> mappings = new ArrayList<>();
        Mailbox mailbox = new Mailbox(8);
        RuntimeServices environment = new RuntimeServices(() -> 0L, queued::add,
                new ManualTimers(), message -> { });
        EffectRunner<String> runner = new EffectRunner<>(mailbox, environment, null, 1);
        EffectId id = EffectId.of("group/work");
        runner.interpret(task(id, "obsolete", bodies, mappings));
        assertThat(queued).hasSize(1);
        if (replace) {
            runner.interpret(task(id, "replacement", bodies, mappings));
        } else if (cancellation != null) {
            runner.interpret(cancellation);
        } else {
            runner.cancelAll();
        }
        // No threads or sleeps: the executor starts the obsolete runnable only after invalidation.
        queued.remove().run();
        assertThat(bodies).as("invalidated work must not start after cancellation/shutdown").isEmpty();
        assertThat(mappings).isEmpty();
        assertThat(mailbox.size()).isZero();
        assertThat(runner.awaitIdle(Duration.ZERO)).isZero();

        if (replace) {
            queued.remove().run();
            assertThat(bodies).containsExactly("replacement");
            assertThat(mappings).containsExactly("replacement");
            assertThat(((Mailbox.AppMessage) mailbox.poll(0)).payload()).isEqualTo("replacement");
        }
        // Invalidation releases quota and must not poison later work or its executor thread.
        runner.interpret(task(id, "fresh", bodies, mappings));
        queued.remove().run();
        assertThat(bodies).endsWith("fresh");
        assertThat(mappings).endsWith("fresh");
        assertThat(((Mailbox.AppMessage) mailbox.poll(0)).payload()).isEqualTo("fresh");
        assertThat(mailbox.size()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private Command<String> task(EffectId id, String name, List<String> bodies,
            List<String> mappings) {
        return Command.task(id, () -> {
            bodies.add(name);
            return name;
        }, value -> {
            mappings.add(name);
            return name;
        }, failure -> {
            mappings.add("failed:" + name);
            return "failed:" + name;
        });
    }
}
