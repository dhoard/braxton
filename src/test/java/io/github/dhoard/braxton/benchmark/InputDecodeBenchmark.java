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

package io.github.dhoard.braxton.benchmark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.github.dhoard.braxton.clipboard.PasteMode;
import io.github.dhoard.braxton.clipboard.PasteSanitizer;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.PasteDecoder;

/**
 * Input path: decoding keystrokes off a stream, reading a bracketed paste body, and sanitizing the
 * text that paste produces. Everything runs on in-memory byte arrays, so no terminal, stdin, or
 * escape-timeout wait is involved.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class InputDecodeBenchmark {

    @Param({"1024", "65536"})
    public int size;

    private byte[] pasteBytes;
    private String pasteText;
    private byte[] keyBytes;

    @Setup
    public void setup() {
        pasteText = BenchCorpus.build("mixed", size);
        pasteBytes = (pasteText + "\u001B[201~").getBytes(StandardCharsets.UTF_8);
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            keys.append((char) ('a' + (i % 26)));
        }
        keyBytes = keys.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public void decodeKeys(Blackhole sink) throws IOException {
        InputDecoder decoder = new InputDecoder(stream(keyBytes));
        while (true) {
            InputEvent event = decoder.read().orElse(null);
            if (event == null) {
                break;
            }
            sink.consume(event);
        }
    }

    @Benchmark
    public void readBracketedPaste(Blackhole sink) throws IOException {
        sink.consume(PasteDecoder.read(stream(pasteBytes), PasteDecoder.DEFAULT_MAX_BYTES,
                PasteDecoder.DEFAULT_IDLE_TIMEOUT_MILLIS));
    }

    @Benchmark
    public void sanitizeNormal(Blackhole sink) {
        sink.consume(PasteSanitizer.sanitize(pasteText, PasteMode.NORMAL, false));
    }

    @Benchmark
    public void sanitizeAggressive(Blackhole sink) {
        sink.consume(PasteSanitizer.sanitize(pasteText, PasteMode.AGGRESSIVE, true));
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
