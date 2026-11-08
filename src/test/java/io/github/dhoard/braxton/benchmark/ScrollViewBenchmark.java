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

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.widget.ScrollView;
import java.util.ArrayList;
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

/** Indexed immutable display rows, independent of the AI Harness's application-level projection. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ScrollViewBenchmark {
    @Param({"10000", "100000", "1000000"})
    public int rows;

    @Param({"12", "24"})
    public int height;

    private static final StyledText ROW = StyledText.plain("fixed transcript display row");

    private record Document(int lineCount) implements ScrollView.Content {
        public StyledText line(int index) {
            return ROW;
        }
    }

    private final LayoutEngine engine = new LayoutEngine();
    private ScrollView widget;
    private ScrollView.Model model;
    private ViewContext context;
    private Viewport viewport;
    private Screen previous;
    private List<StyledText> publication;

    @Setup
    public void setup() {
        widget = new ScrollView(new ScrollView.Config(height, true));
        model = new ScrollView.Model(0, true, true, height, new Document(rows));
        viewport = new Viewport(height, 80);
        context = new ViewContext(viewport);
        previous = engine.layout(widget.view(model, context), viewport.bounds()).screen();
        publication = new ArrayList<>(java.util.Collections.nCopies(rows, ROW));
    }

    @Benchmark
    public Object appendAndScroll() {
        var appended = widget.update(model, new ScrollView.Msg.ContentChanged(new Document(rows + 1)))
                .model();
        return widget.update(appended, new ScrollView.Msg.Up(1)).model();
    }

    @Benchmark
    public String paintAndEncode() {
        return AnsiEncoder.frame(
                engine.layout(widget.view(model, context), viewport.bounds()).screen(), true);
    }

    @Benchmark
    public String paintAndDiff() {
        return AnsiEncoder.diff(
                previous,
                engine.layout(widget.view(model, context), viewport.bounds()).screen(),
                true);
    }

    /** Deliberately separate O(history) list publication from O(window) frame work. */
    @Benchmark
    public Object publishListSnapshot() {
        return ScrollView.Content.of(publication);
    }
}
