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
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;

/**
 * Cell painting and encoding: the grid the renderer fills and the one encoder that turns it into
 * the bytes a terminal receives. The encoder's output for a given screen is a compatibility
 * contract, so these benchmarks measure cost, never output shape.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ScreenEncodeBenchmark {

    @Param({"24x80", "40x120"})
    public String viewport;

    private int rows;
    private int cols;
    private Screen styled;
    private Screen changed;

    @Setup
    public void setup() {
        Viewport parsed = BenchViews.viewport(viewport);
        rows = parsed.rows();
        cols = parsed.cols();
        styled = paint(Style.NONE);
        changed = paint(Style.NONE);
        // One cell in the middle row differs: the smallest change the runtime can present.
        changed.put(rows / 2, cols / 2, "Z", Style.fg(Colors.ERROR_400), null, 1);
    }

    private Screen paint(Style base) {
        Screen screen = new Screen(rows, cols);
        Style style = base;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if ((row + col) % 7 == 0) {
                    style = Style.fg(Colors.BLUE_400);
                } else if ((row + col) % 11 == 0) {
                    style = Style.NONE.withBold();
                } else {
                    style = base;
                }
                screen.put(row, col, "x", style, null, 1);
            }
        }
        return screen;
    }

    @Benchmark
    public void paintScreen(Blackhole sink) {
        sink.consume(paint(Style.NONE));
    }

    @Benchmark
    public void encodeFullFrame(Blackhole sink) {
        sink.consume(AnsiEncoder.frame(styled, true));
    }

    @Benchmark
    public void encodeUnchangedDiff(Blackhole sink) {
        sink.consume(AnsiEncoder.diff(styled, styled, true));
    }

    @Benchmark
    public void encodeOneCellDiff(Blackhole sink) {
        sink.consume(AnsiEncoder.diff(styled, changed, true));
    }
}
