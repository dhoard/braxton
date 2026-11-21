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

package io.github.dhoard.braxton.terminal;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.layout.Size;
import java.io.IOException;
import java.util.Optional;

/**
 * Package-private session implementation; only public types cross this boundary.
 */
final class Session implements TerminalSession {

    private final Terminal terminal;
    private final InputDecoder decoder;
    private Size lastSize;
    private boolean closed;

    Session(Terminal terminal, InputOptions options) {
        this.terminal = terminal;
        this.decoder = new InputDecoder(terminal.reader(), options);
        this.lastSize = terminal.size();
    }

    @Override
    public Capabilities capabilities() {
        return terminal.capabilities();
    }

    @Override
    public Size size() {
        return terminal.size();
    }

    @Override
    public void enterRawMode() throws TerminalException {
        try {
            terminal.enterRawMode();
        } catch (IOException failure) {
            throw new TerminalException("could not enter raw mode", failure);
        }
    }

    @Override
    public Optional<TerminalEvent> read() throws TerminalException {
        if (closed) return Optional.empty();
        Size current = terminal.size();
        if (!current.equals(lastSize)) {
            lastSize = current;
            return Optional.of(new TerminalEvent.Resize(current));
        }
        try {
            // Query replies are consumed here, never delivered as a key or paste to the application.
            while (true) {
                Optional<InputEvent> event = decoder.read();
                if (event.isEmpty() || !(event.get() instanceof InputEvent.TerminalReplyInput)) {
                    return event.map(TerminalEvent.Input::new);
                }
            }
        } catch (IOException failure) {
            throw new TerminalException("could not read terminal input", failure);
        }
    }

    @Override
    public void write(String raw) throws TerminalException {
        if (raw == null) throw new IllegalArgumentException("output is required");
        terminal.writer().write(raw);
    }

    @Override
    public void writeText(String text) throws TerminalException {
        if (text == null) throw new IllegalArgumentException("text is required");
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            safe.append(value == '\t' || value == '\n' || !Character.isISOControl(value) ? value : ' ');
        }
        write(safe.toString());
    }

    @Override
    public void flush() throws TerminalException {
        terminal.writer().flush();
        if (terminal.outputError() != null) {
            throw new TerminalException("terminal output failed", terminal.outputError());
        }
    }

    @Override
    public void close() throws TerminalException {
        if (closed) return;
        closed = true;
        try {
            terminal.close();
        } catch (IOException failure) {
            throw new TerminalException("could not close the terminal", failure);
        }
    }
}
