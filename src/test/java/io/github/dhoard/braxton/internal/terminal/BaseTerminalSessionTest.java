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
        private int released;

        ScriptedSession() {
            super(TerminalOptions.DEFAULT, TerminalCapabilities.PLAIN);
        }

        @Override
        protected void writeNative(String data) {
            writes.add(data);
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
