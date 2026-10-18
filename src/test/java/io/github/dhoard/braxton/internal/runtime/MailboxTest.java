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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** The bounded hand-off between producers and the runtime owner. */
class MailboxTest {

    @Test
    void aHugeTimeoutStillWaitsForASlotInsteadOfDroppingTheMessage() throws Exception {
        Mailbox mailbox = new Mailbox(1);
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("first"));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean();
        Thread producer = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                accepted.set(mailbox.enqueue(new Mailbox.AppMessage("second"), Long.MAX_VALUE));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100L);
        assertThat(mailbox.size()).isEqualTo(1);
        assertThat(mailbox.poll(1_000L)).isInstanceOf(Mailbox.AppMessage.class);
        producer.join(5_000L);
        assertThat(producer.isAlive()).isFalse();
        assertThat(accepted).isTrue();
        assertThat(mailbox.poll(1_000L)).isInstanceOf(Mailbox.AppMessage.class);
    }

    @Test
    void closeWakesABlockedProducer() throws Exception {
        Mailbox mailbox = new Mailbox(1);
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("first"));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread producer = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                accepted.set(mailbox.enqueue(new Mailbox.AppMessage("second"), 60_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100L);
        mailbox.close();
        producer.join(2_000L);
        assertThat(producer.isAlive()).as("a blocked producer must be woken by close").isFalse();
        assertThat(accepted).as("a closed mailbox accepts nothing").isFalse();
        mailbox.poll(1_000L);
    }

    @Test
    void closeWakesABlockedConsumer() throws Exception {
        Mailbox mailbox = new Mailbox(4);
        AtomicReference<Mailbox.Envelope> polled = new AtomicReference<>();
        Thread consumer = Thread.ofVirtual().start(() -> {
            try {
                polled.set(mailbox.poll(60_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(100L);
        mailbox.close();
        consumer.join(2_000L);
        assertThat(consumer.isAlive()).as("a blocked consumer must be woken by close").isFalse();
        assertThat(polled.get()).isNull();
    }
}
