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

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A slow terminal skips frames instead of queueing or tearing them.
 *
 * <p>While a frame write is blocked, the owner is inside it: producers queue messages and wait on
 * the mailbox, and when the write completes the next frame renders the latest model. The states
 * that arrived during the block are never rendered at all, so no stale frame reaches the terminal
 * and every frame that does is written whole.</p>
 */
@Timeout(10)
class FrameCoalescingTest {
    private static final String BEGIN = "\u001b[?2026h";
    private static final String END = "\u001b[?2026l";

    @Test
    void updatesThatArriveDuringABlockedFrameProduceOneFrameOfTheLatestState() throws Exception {
        var emitter = new AtomicReference<Consumer<Object>>();
        var subscribed = new CountDownLatch(1);
        var rendered = new CopyOnWriteArrayList<Integer>();
        var armed = new AtomicBoolean();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Component<Integer, String, Never> program = new Component<>() {
            public Update<Integer, String, Never> init(InitContext context) {
                return Update.next(0, Command.none());
            }

            public Update<Integer, String, Never> update(Integer model, String message) {
                if (message.equals("quit")) return Update.next(model, Command.quit());
                return Update.next(Integer.parseInt(message), Command.none());
            }

            public View<String> view(Integer model, ViewContext context) {
                rendered.add(model);
                if (model == 1) armed.set(true);
                return Views.text("state " + model);
            }

            public Subscription<String> subscriptions(Integer model) {
                return Subscription.source(
                        SubscriptionId.of("feed"),
                        "feed",
                        sink -> {
                            emitter.set(sink);
                            subscribed.countDown();
                            return () -> {};
                        },
                        value -> (String) value);
            }
        };
        try (var fixture = new TestTerminal()) {
            // Hold the first frame body written after state 1 renders: the owner is now inside a
            // frame write that the terminal is not accepting.
            fixture.beforeWrite(text -> {
                if (!text.equals(BEGIN) && !text.equals(END) && armed.compareAndSet(true, false)) {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            var loop =
                    new RuntimeLoop<>(program, RuntimeOptions.defaults(), fixture.terminal(), RuntimeServices.system());
            var result = new AtomicReference<ExitResult>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

            emitter.get().accept("1");
            assertThat(blocked.await(5, TimeUnit.SECONDS))
                    .as("the state-1 frame is being written")
                    .isTrue();
            emitter.get().accept("2");
            emitter.get().accept("3");
            emitter.get().accept("4");
            Mailbox mailbox = (Mailbox) field(loop, "mailbox");
            assertThat(mailbox.size())
                    .as("updates wait in the mailbox while the frame is blocked")
                    .isEqualTo(3);
            assertThat(rendered)
                    .as("nothing renders while the owner is in the write")
                    .containsExactly(0, 1);

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!rendered.contains(4) && System.nanoTime() < deadline) Thread.sleep(5);
            emitter.get().accept("quit");
            owner.join(5_000);

            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.QUIT);
            // The frame pacing (16 ms) collapses 2, 3 and 4 into one frame of the latest state.
            assertThat(rendered)
                    .as("intermediate states are skipped, not queued")
                    .containsExactly(0, 1, 4);
            String output = fixture.outputText();
            int begins = output.split(java.util.regex.Pattern.quote(BEGIN), -1).length - 1;
            int ends = output.split(java.util.regex.Pattern.quote(END), -1).length - 1;
            assertThat(begins).as("one bracket per presented frame").isEqualTo(3);
            // Restoration also ends synchronized output defensively, after the last frame.
            assertThat(ends).as("every started frame was closed").isEqualTo(begins + 1);
            assertThat(output.lastIndexOf(END)).isGreaterThan(output.lastIndexOf(BEGIN));
        }
    }

    private static Object field(RuntimeLoop<?, ?> loop, String name) throws Exception {
        var field = RuntimeLoop.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(loop);
    }
}
