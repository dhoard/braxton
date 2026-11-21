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

package io.github.dhoard.braxton.internal.terminal;

import io.github.dhoard.braxton.layout.Size;
import java.io.IOException;

/**
 * Private boundary between Java lifetime policy and the owned POSIX ABI.
 */
interface TerminalTransport extends AutoCloseable {
    int read(byte[] bytes, int timeoutMillis) throws IOException;

    int write(byte[] bytes, int offset, int length, int timeoutMillis) throws IOException;

    int raw() throws IOException;

    int release() throws IOException;

    int reacquire() throws IOException;

    int foreground() throws IOException;

    int suspend() throws IOException;

    int resizeEpoch() throws IOException;

    int resumeEpoch() throws IOException;

    int registerSuspendBytes(String restore, String resume) throws IOException;

    Size size() throws IOException;

    void close() throws IOException;
}
