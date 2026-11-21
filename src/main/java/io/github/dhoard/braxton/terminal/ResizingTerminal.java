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

import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import io.github.dhoard.braxton.layout.Size;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A stream terminal whose size is supplied by the caller, so resize events can be tested.
 */
final class ResizingTerminal implements Terminal {

    private final Terminal delegate;
    private final TerminalSession.SizeSource sizes;

    ResizingTerminal(Terminal delegate, TerminalSession.SizeSource sizes) {
        this.delegate = delegate;
        this.sizes = sizes;
    }

    @Override
    public TimedReader reader() {
        return delegate.reader();
    }

    @Override
    public PrintWriter writer() {
        return delegate.writer();
    }

    @Override
    public IOException outputError() {
        return delegate.outputError();
    }

    @Override
    public Size size() {
        return sizes.get();
    }

    @Override
    public Capabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public void enterRawMode() throws IOException {
        delegate.enterRawMode();
    }

    @Override
    public State state() {
        return delegate.state();
    }

    @Override
    public void release() throws IOException {
        delegate.release();
    }

    @Override
    public void reacquire() throws IOException {
        delegate.reacquire();
    }

    @Override
    public boolean foreground() {
        return delegate.foreground();
    }

    @Override
    public void suspend() throws IOException {
        delegate.suspend();
    }

    @Override
    public void boundOutput() {
        delegate.boundOutput();
    }

    @Override
    public void onResize(Runnable callback) {
        delegate.onResize(callback);
    }

    @Override
    public long resizeEpoch() {
        return delegate.resizeEpoch();
    }

    @Override
    public void beginSynchronizedUpdate() {
        delegate.beginSynchronizedUpdate();
    }

    @Override
    public void endSynchronizedUpdate() {
        delegate.endSynchronizedUpdate();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
