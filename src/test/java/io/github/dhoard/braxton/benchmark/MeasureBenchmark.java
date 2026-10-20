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

import java.util.List;
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

import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * Text measurement: the width policy, grapheme segmentation and boundary scan that layout, the
 * editor and every widget call for each painted line.
 *
 * <p>{@code ascii} exercises the printable-ASCII fast path, {@code mixed} the Unicode path with a
 * wide glyph, an emoji, a combining mark and a tab in the same string.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MeasureBenchmark {

    @Param({"ascii", "mixed"})
    public String corpus;

    @Param({"256", "4096"})
    public int size;

    private String text;

    @Setup
    public void setup() {
        text = BenchCorpus.build(corpus, size);
    }

    @Benchmark
    public void columns(Blackhole sink) {
        sink.consume(TextMeasure.columns(text, WidthPolicy.PINNED));
    }

    @Benchmark
    public List<String> graphemes() {
        return TextMeasure.graphemes(text);
    }

    @Benchmark
    public void boundaryScan(Blackhole sink) {
        for (int i = 0; i < text.length(); i++) {
            sink.consume(TextMeasure.isBoundary(text, i));
        }
    }
}
