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

package io.github.dhoard.braxton.internal.input;

import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Actual JLine decoders, closed after each parser test. */
public final class TestReaders implements AfterEachCallback {
    private static final ThreadLocal<List<NonBlockingReader>> READERS =
            ThreadLocal.withInitial(ArrayList::new);

    public static NonBlockingReader utf8(InputStream input) {
        NonBlockingReader reader = NonBlocking.nonBlocking("test", input, StandardCharsets.UTF_8);
        READERS.get().add(reader);
        return reader;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        try {
            for (NonBlockingReader reader : READERS.get()) reader.close();
        } finally {
            READERS.remove();
        }
    }
}
