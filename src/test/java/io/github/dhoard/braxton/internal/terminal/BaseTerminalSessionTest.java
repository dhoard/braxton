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

package io.github.dhoard.braxton.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.terminal.TerminalOptions;

/** Session mode entry and restoration, exercised without a native terminal. */
class BaseTerminalSessionTest {

    /** Records the mode output and can fail the entry write, like a broken descriptor. */
    private static final class ScriptedSession extends BaseTerminalSession {
        private final List<String> writes = new ArrayList<>();
        private boolean failNextWrite;
        private Error writeError;
        private int ownershipReleases;
        private int released;

        ScriptedSession() {
            super(TerminalOptions.DEFAULT, TerminalCapabilities.PLAIN);
        }

        @Override
        protected void writeNative(String data) {
            writes.add(data);
            if (writeError != null) {
                throw writeError;
            }
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException("broken output");
            }
        }

        @Override
        protected boolean waitReadable(int timeoutMillis) {
            return false;
        }

        @Override
        protected int readNative(byte[] buffer, int offset, int length) {
            return -1;
        }

        @Override
        protected Size sizeNative() {
            return new Size(24, 80);
        }

        @Override
        protected void releaseNative() {
            released++;
        }

        @Override
        protected void onClosed() {
            ownershipReleases++;
        }
    }

    /** Blocks in waitReadable until the session closes, like a terminal with no input pending. */
    private static final class BlockingSession extends BaseTerminalSession {
        private final java.util.concurrent.CountDownLatch waiting =
                new java.util.concurrent.CountDownLatch(1);

        BlockingSession() {
            super(TerminalOptions.DEFAULT, TerminalCapabilities.PLAIN);
        }

        @Override
        protected void writeNative(String data) {
        }

        @Override
        protected boolean waitReadable(int timeoutMillis) {
            waiting.countDown();
            try {
                Thread.sleep(2L);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        @Override
        protected int readNative(byte[] buffer, int offset, int length) {
            return -1;
        }

        @Override
        protected Size sizeNative() {
            return new Size(24, 80);
        }

        @Override
        protected void releaseNative() {
        }
    }

    @Test
    void closingTheSessionWakesABlockedReaderWithoutClosingSystemIn() throws Exception {
        BlockingSession session = new BlockingSession();
        session.startSession();
        java.util.concurrent.atomic.AtomicInteger result =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        Thread reader = Thread.ofVirtual().name("blocked-reader").start(() -> {
            try {
                result.set(session.input().read());
            } catch (java.io.IOException failure) {
                result.set(Integer.MAX_VALUE);
            }
        });
        assertThat(session.waiting.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        session.close();
        reader.join(5_000L);
        assertThat(reader.isAlive()).as("close must wake the reader").isFalse();
        assertThat(result.get()).as("a closed session reads as end of input").isEqualTo(-1);
    }

    @Test
    void bulkReadRejectsOverflowingRangesEvenAfterClose() throws Exception {
        ScriptedSession session = new ScriptedSession();
        session.close();
        byte[] buffer = new byte[4];
        int[][] ranges = {{Integer.MAX_VALUE, Integer.MAX_VALUE}, {1, Integer.MAX_VALUE},
                {-1, 1}, {0, -1}, {3, 2}, {5, 0}};
        for (int[] range : ranges) {
            assertThatThrownBy(() -> session.input().read(buffer, range[0], range[1]))
                    .as("offset %d, length %d", range[0], range[1])
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
        assertThatThrownBy(() -> session.input().read(null, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThat(session.input().read(buffer, 4, 0)).isZero();
        assertThat(session.input().read(buffer, 0, 1)).isEqualTo(-1);
    }

    @Test
    void closingAnEnteredSessionRestoresModesExactlyOnce() {
        ScriptedSession session = new ScriptedSession();
        session.startSession();
        session.close();
        session.close();
        assertThat(session.writes).hasSize(2);
        assertThat(session.writes.get(0))
                .contains(AnsiEncoder.ALT_SCREEN_ON)
                .contains(AnsiEncoder.HIDE_CURSOR);
        assertThat(session.writes.get(1))
                .contains(AnsiEncoder.DISABLE_BRACKETED_PASTE)
                .contains(AnsiEncoder.SHOW_CURSOR)
                .contains(AnsiEncoder.ALT_SCREEN_OFF);
        assertThat(session.released).isEqualTo(1);
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void anErrorRestoringModesStillReleasesNativeStateAndOwnership() {
        ScriptedSession session = new ScriptedSession();
        session.startSession();
        Error failure = new AssertionError("mode restore failed");
        session.writeError = failure;

        assertThatThrownBy(session::close).isSameAs(failure);
        assertThat(session.released).as("native/raw state must be released despite output failure")
                .isEqualTo(1);
        assertThat(session.ownershipReleases).isEqualTo(1);
        assertThat(session.isOpen()).isFalse();
        session.close();
        assertThat(session.released).isEqualTo(1);
        assertThat(session.ownershipReleases).isEqualTo(1);
        assertThat(session.writes).hasSize(2);
    }

    @Test
    void aRuntimeExceptionRestoringModesStillReleasesNativeStateAndOwnership() {
        ScriptedSession session = new ScriptedSession();
        session.startSession();
        session.failNextWrite = true;

        session.close();
        session.close();
        assertThat(session.released).isEqualTo(1);
        assertThat(session.ownershipReleases).isEqualTo(1);
        assertThat(session.isOpen()).isFalse();
        assertThat(session.writes).hasSize(2);
    }

    @Test
    void aFailedModeEntryIsStillUndoneByClose() {
        // The entry write can fail after the terminal already applied part of it (a short write),
        // so close must still emit the inverse modes instead of leaving the alternate screen and
        // hidden cursor behind.
        ScriptedSession session = new ScriptedSession();
        session.failNextWrite = true;
        assertThatThrownBy(session::startSession).isInstanceOf(IllegalStateException.class);
        session.close();
        assertThat(session.writes).hasSize(2);
        assertThat(session.writes.get(1))
                .contains(AnsiEncoder.DISABLE_BRACKETED_PASTE)
                .contains(AnsiEncoder.SHOW_CURSOR)
                .contains(AnsiEncoder.ALT_SCREEN_OFF);
        assertThat(session.released).isEqualTo(1);
    }
}
