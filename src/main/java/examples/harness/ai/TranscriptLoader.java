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

package examples.harness.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Explicit classpath loading of the bundled example transcript.
 *
 * <p>This is the only place that turns bytes into transcript values. It never reads user state,
 * an external session directory, or the network, and it performs no terminal I/O.</p>
 */
public final class TranscriptLoader {

    public static final String RESOURCE = "/examples/ai-harness/session.jsonl";

    private TranscriptLoader() {
    }

    /** Loads the bundled fixture, degrading to a warning-and-fallback transcript on failure. */
    public static HarnessTranscript load() {
        try (InputStream stream = TranscriptLoader.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return HarnessTranscript.empty(List.of(
                        "Bundled example transcript not found; fallback responses are active."));
            }
            return new TranscriptJsonlReader().read(stream);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + RESOURCE, failure);
        }
    }
}
