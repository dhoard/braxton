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

package io.github.dhoard.braxton.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalOptions;

/**
 * The in-memory session must behave like the real one where the runtime depends on it.
 *
 * <p>Every runtime test runs against this session, so a divergence from
 * {@code BaseTerminalSession} silently hides the behaviour a real terminal would show.</p>
 */
class FakeTerminalTest {

    @Test
    void aClosedSessionRecordsNothingBecauseARealOneDropsTheWrite() throws IOException {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.open(new Size(6, 20), TerminalOptions.DEFAULT);
        terminal.write("frame");
        terminal.close();

        terminal.write("after close");
        assertThat(terminal.isOpen()).as("a closed session is not usable").isFalse();
        assertThat(terminal.writes()).as("a closed session keeps nothing it was handed")
                .containsExactly("frame");

        terminal.input().read();
        assertThat(terminal.input().read())
                .as("closing wakes the reader with end of input")
                .isEqualTo(-1);
    }

    @Test
    void anOpenedSessionKeepsEveryWriteInOrder() throws IOException {
        FakeTerminal terminal = new FakeTerminal(new Size(6, 20));
        terminal.open(new Size(6, 20), TerminalOptions.DEFAULT);
        terminal.write("one");
        terminal.write("");
        terminal.write(null);
        terminal.write("two");
        assertThat(terminal.writes()).containsExactly("one", "two");
        assertThat(terminal.output()).isEqualTo("onetwo");
        terminal.close();
    }
}
