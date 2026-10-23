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

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;

/**
 * One frame end to end through the headless path the runtime paints with: rebuild the screen from a
 * view tree, then encode it. This is the cost a dirty frame pays before the runtime's 16 ms
 * presentation gap is applied, so it measures the work, not the wait.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class FramePipelineBenchmark {

    @Param({"24x80", "40x120"})
    public String viewport;

    private final io.github.dhoard.braxton.internal.render.LayoutEngine engine = new io.github.dhoard.braxton.internal.render.LayoutEngine();

    private View<Object> root;
    private Viewport parsed;
    private Screen previous;

    @Setup
    public void setup() {
        parsed = BenchViews.viewport(viewport);
        root = BenchViews.transcript(30, BenchCorpus.build("ascii", 240));
        previous = engine.layout(root, parsed.bounds()).screen();
    }

    @Benchmark
    public void paintFrame(Blackhole sink) {
        sink.consume(engine.layout(root, parsed.bounds()).screen());
    }

    @Benchmark
    public void paintAndEncodeFrame(Blackhole sink) {
        sink.consume(AnsiEncoder.frame(engine.layout(root, parsed.bounds()).screen(), true));
    }

    @Benchmark
    public void paintAndDiffFrame(Blackhole sink) {
        sink.consume(AnsiEncoder.diff(previous, engine.layout(root, parsed.bounds()).screen(), true));
    }
}
