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

import io.github.dhoard.braxton.app.TerminalCapabilities;
import io.github.dhoard.braxton.layout.Size;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;

/** Internal services needed by the runtime; never part of the public MVU surface. */
public interface Terminal extends Closeable {
    TimedReader reader();

    PrintWriter writer();

    Size size();

    TerminalCapabilities capabilities();

    void enterRawMode() throws IOException;

    default void onResize(Runnable callback) {}

    /** Monotonic notification token; changes can represent a shrink/grow with equal final size. */
    default long resizeEpoch() {
        return 0L;
    }

    default void beginSynchronizedUpdate() {
        writer().write("\u001b[?2026h");
    }

    default void endSynchronizedUpdate() {
        writer().write("\u001b[?2026l");
    }
}
