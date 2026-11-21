/*
 * Copyright (c) 2026-present Douglas Hoard
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

package examples.terminal;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.terminal.Capabilities;
import io.github.dhoard.braxton.terminal.TerminalEvent;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.util.Optional;

/**
 * A full-screen application written directly against the low-level terminal API.
 */
public final class TerminalApiDemo {

    private TerminalApiDemo() {}

    /**
     * Runs until the user presses {@code q}. Requires native access to open the terminal.
     *
     * @param args the command-line arguments
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    public static void main(String[] args) throws TerminalException {
        try (TerminalSession session = TerminalSession.open()) {
            Capabilities caps = session.capabilities();
            session.enterRawMode();
            session.clear();
            session.writeText("Braxton terminal API  term=" + caps.term() + "  color=" + caps.color());
            session.moveCursor(2, 0);
            session.writeText("Press q to quit. Resize the window to see its new size.");
            session.flush();
            while (true) {
                Optional<TerminalEvent> received = session.read();
                if (received.isEmpty()) return;
                switch (received.get()) {
                    case TerminalEvent.Resize resize -> {
                        session.clear();
                        session.writeText("Size: " + resize.size());
                        session.flush();
                    }
                    case TerminalEvent.Input input -> {
                        if (input.value() instanceof InputEvent.KeyInput key
                                && key.key().name().equals("q")) {
                            return;
                        }
                    }
                }
            }
        }
    }
}
