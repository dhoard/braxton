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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.testkit.FakeTerminal;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

@Timeout(15)
class FrameCadenceTest {
    private static RuntimeOptions options() {
        return RuntimeOptions.defaults().withEnvironment(RuntimeEnvironment.system()
                .withTimers(new FakeTerminal.ManualTimers()).withEffectExecutor(Runnable::run)
                .withNanoTime(() -> 1000L));
    }

    @Test
    void aBurstAppliesEveryStateAndEffectInOrderButPresentsFewerFrames() {
        AtomicInteger views = new AtomicInteger();
        List<Integer> effects = new ArrayList<>();
        Program<Integer, String> program = new Program<>() {
            public Transition<Integer, String> init(InitContext context) {
                return Transition.stay(0);
            }
            public Transition<Integer, String> update(Integer model, String message) {
                if (!"inc".equals(message)) {
                    return Transition.stay(model);
                }
                int next = model + 1;
                Command<String> effect = Command.task(EffectId.of("effect/" + next), () -> {
                    effects.add(next);
                    return next;
                }, value -> "ack", failure -> "failure");
                return Transition.next(next, next == 200 ? Command.batch(effect, Command.quit(7)) : effect);
            }
            public View<String> view(Integer model, Viewport viewport) {
                views.incrementAndGet();
                return Views.text("count=" + model);
            }
            public Subscription<String> subscriptions(Integer model) {
                return Subscription.source(SubscriptionId.of("burst"), "burst", emitter -> {
                    for (int i = 0; i < 200; i++) {
                        emitter.accept("inc");
                    }
                    return () -> { };
                }, value -> (String) value);
            }
        };
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        ExitResult result = Application.run(program, options(), terminal);
        assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(result.status()).isEqualTo(7);
        assertThat(effects).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 200).boxed().toList());
        assertThat(views.get()).as("a burst must not layout one screen per app message").isLessThan(100);
        assertThat(terminal.output()).contains("count=200");
    }

    @Test
    void aLoneApplicationUpdatePaintsWithoutAnotherEventEvenWithAFixedApplicationClock() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        CountDownLatch painted = new CountDownLatch(1);
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Program<Integer, String> program = singleUpdate(painted, null);
        Thread owner = Thread.ofVirtual().start(() -> result.set(Application.run(program, options(), terminal)));
        try {
            assertThat(painted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            terminal.endInput();
            owner.join(5000);
        }
        assertThat(owner.isAlive()).isFalse();
        assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("count=1");
    }

    @Test
    void endOfInputFlushesTheLatestCommittedScreen() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        CountDownLatch updated = new CountDownLatch(1);
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> result.set(
                Application.run(singleUpdate(null, updated), options(), terminal)));
        try {
            assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            terminal.endInput();
            owner.join(5000);
        }
        assertThat(owner.isAlive()).isFalse();
        assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(terminal.output()).contains("count=1");
    }

    private static Program<Integer, String> singleUpdate(CountDownLatch painted, CountDownLatch updated) {
        return new Program<>() {
            public Transition<Integer, String> init(InitContext context) {
                return Transition.stay(0);
            }
            public Transition<Integer, String> update(Integer model, String message) {
                if (updated != null) {
                    updated.countDown();
                }
                return Transition.stay(1);
            }
            public View<String> view(Integer model, Viewport viewport) {
                if (model == 1 && painted != null) {
                    painted.countDown();
                }
                return Views.text("count=" + model);
            }
            public Subscription<String> subscriptions(Integer model) {
                return Subscription.source(SubscriptionId.of("one"), "one", emitter -> {
                    emitter.accept("inc");
                    return () -> { };
                }, value -> (String) value);
            }
        };
    }

    @Test
    void keysUseBindingsFromTheLatestCommittedModel() throws Exception {
        FakeTerminal terminal = new FakeTerminal(new Size(4, 20));
        CountDownLatch switched = new CountDownLatch(1);
        AtomicReference<ExitResult> result = new AtomicReference<>();
        Program<Integer, String> program = new Program<>() {
            public Transition<Integer, String> init(InitContext context) {
                return Transition.stay(0);
            }
            public Transition<Integer, String> update(Integer model, String message) {
                if ("switch".equals(message)) {
                    switched.countDown();
                    return Transition.stay(1);
                }
                return Transition.next(model, Command.quit(9));
            }
            public View<String> view(Integer model, Viewport viewport) {
                return Views.keyed(List.of(Binding.of(model == 0 ? "a" : "b", "quit")),
                        Views.text("state=" + model), List.of());
            }
            public Subscription<String> subscriptions(Integer model) {
                return Subscription.source(SubscriptionId.of("switch"), "switch", emitter -> {
                    emitter.accept("switch");
                    return () -> { };
                }, value -> (String) value);
            }
        };
        Thread owner = Thread.ofVirtual().start(() -> result.set(Application.run(program, options(), terminal)));
        try {
            assertThat(switched.await(5, TimeUnit.SECONDS)).isTrue();
            terminal.feed("b");
            owner.join(5000);
        } finally {
            terminal.endInput();
            owner.join(5000);
        }
        assertThat(owner.isAlive()).isFalse();
        assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(result.get().status()).isEqualTo(9);
    }
}
