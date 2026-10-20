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

import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.view.View;

/**
 * Whole-tree layout: allocation plus painting for a realistic view, and the intrinsic-height query a
 * scrolling widget asks before it allocates.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class LayoutBenchmark {

    @Param({"24x80", "40x120"})
    public String viewport;

    @Param({"10", "60"})
    public int tableRows;

    private final LayoutEngine engine = new LayoutEngine();

    private View<Object> root;
    private Rect bounds;

    @Setup
    public void setup() {
        Viewport parsed = BenchViews.viewport(viewport);
        bounds = parsed.bounds();
        root = BenchViews.transcript(tableRows, BenchCorpus.build("ascii", 120));
    }

    @Benchmark
    public void layout(Blackhole sink) {
        sink.consume(engine.layout(root, bounds));
    }

    @Benchmark
    public void intrinsicHeight(Blackhole sink) {
        sink.consume(engine.intrinsicHeight(root, bounds.cols()));
    }
}
