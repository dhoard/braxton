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
    void theOwnerOverflowIsLoudWhileAProducerTimesOutQuietly() throws Exception {
        // The owner thread must never block or drop silently: overflow is fatal. A background
        // producer waits only as long as it was told and then reports the drop.
        Mailbox mailbox = new Mailbox(1);
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("first"));
        assertThatThrownBy(() -> mailbox.enqueueFromOwner(new Mailbox.AppMessage("second")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overflow");
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("third"), 20L)).isFalse();
        assertThat(mailbox.size()).isEqualTo(1);
        assertThat(mailbox.poll(1_000L)).isInstanceOf(Mailbox.AppMessage.class);
    }

    @Test
    void producerAndOwnerEnvelopesAreBoundedSeparately() throws Exception {
        Mailbox mailbox = new Mailbox(2);
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("p1"), 0L)).isTrue();
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("p2"), 0L)).isTrue();
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("p3"), 20L))
                .as("producers still block at the capacity")
                .isFalse();
        // Producers filled the mailbox: the owner's own deliveries do not compete with them.
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("o1"));
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("o2"));
        assertThat(mailbox.size()).isEqualTo(4);
        assertThatThrownBy(() -> mailbox.enqueueFromOwner(new Mailbox.AppMessage("o3")))
                .as("an owner burst beyond the capacity is still the fatal overflow")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime mailbox overflow (capacity 2)");
        // Taking producer envelopes frees no owner room; taking an owner envelope does.
        assertThat(((Mailbox.AppMessage) mailbox.poll(0L)).payload()).isEqualTo("p1");
        assertThat(((Mailbox.AppMessage) mailbox.poll(0L)).payload()).isEqualTo("p2");
        assertThatThrownBy(() -> mailbox.enqueueFromOwner(new Mailbox.AppMessage("o3")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(((Mailbox.AppMessage) mailbox.poll(0L)).payload()).isEqualTo("o1");
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("o3"));
        // Producers wait until the total, of any origin, is below the capacity.
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("p3"), 0L)).isFalse();
        assertThat(((Mailbox.AppMessage) mailbox.poll(0L)).payload()).isEqualTo("o2");
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("p3"), 0L)).isTrue();
        assertThat(((Mailbox.AppMessage) mailbox.poll(0L)).payload()).isEqualTo("o3");
        assertThat(((Mailbox.AppMessage) mailbox.poll(0L)).payload()).isEqualTo("p3");
    }

    @Test
    void anOwnerDeliveryAfterABlockedProducerRefillsTheTakenSlotDoesNotOverflow() throws Exception {
        // The race the separate accounting removes: the owner takes one envelope from a full
        // mailbox, a blocked producer takes that slot at once, then the owner's turn delivers.
        Mailbox mailbox = new Mailbox(1);
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("first"), 0L)).isTrue();
        CountDownLatch refilled = new CountDownLatch(1);
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                if (mailbox.enqueue(new Mailbox.AppMessage("refill"), 60_000L)) refilled.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(mailbox.poll(1_000L)).isInstanceOf(Mailbox.AppMessage.class);
        assertThat(refilled.await(5, TimeUnit.SECONDS)).isTrue();
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("owner delivery"));
        assertThat(mailbox.size()).isEqualTo(2);
        producer.join(2_000L);
    }

    @Test
    void aClosedMailboxAcceptsNothingAndReleasesItsQueue() throws Exception {
        Mailbox mailbox = new Mailbox(2);
        mailbox.enqueueFromOwner(new Mailbox.AppMessage("pending"));
        mailbox.close();
        assertThat(mailbox.size())
                .as("closed mailboxes release undeliverable payloads")
                .isZero();
        mailbox.close();
        assertThat(mailbox.size()).isZero();
        assertThat(mailbox.enqueue(new Mailbox.AppMessage("late"), 0L)).isFalse();
        assertThatThrownBy(() -> mailbox.enqueueFromOwner(new Mailbox.AppMessage("late")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(mailbox.poll(0L)).as("a closed mailbox delivers nothing").isNull();
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
        assertThat(producer.isAlive())
                .as("a blocked producer must be woken by close")
                .isFalse();
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
        assertThat(consumer.isAlive())
                .as("a blocked consumer must be woken by close")
                .isFalse();
        assertThat(polled.get()).isNull();
    }
}
