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

package io.github.dhoard.braxton.terminal;

/**
 * Session behaviour an application can request before it starts.
 *
 * <p>Full-window applications use the alternate screen by default so exit restores the user's
 * scrollback instead of erasing it. Mouse reporting and bracketed paste are opt-in per session.</p>
 */
public record TerminalOptions(boolean alternateScreen, boolean mouse, boolean bracketedPaste) {

    public static final TerminalOptions DEFAULT = new TerminalOptions(true, false, true);

    public TerminalOptions {
    }

    public TerminalOptions withMouse(boolean value) {
        return new TerminalOptions(alternateScreen, value, bracketedPaste);
    }

    public TerminalOptions withAlternateScreen(boolean value) {
        return new TerminalOptions(value, mouse, bracketedPaste);
    }

    public TerminalOptions withBracketedPaste(boolean value) {
        return new TerminalOptions(alternateScreen, mouse, value);
    }
}
