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

import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorOptions;
import io.github.dhoard.braxton.widget.EditorState;

/**
 * Editor rendering: the draft a composer repaints on every keystroke. The 4096-character draft
 * exercises the long-input rendering costs described in the project's benchmark memory notes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EditorRenderBenchmark {

    @Param({"ascii", "cjk"})
    public String corpus;

    @Param({"256", "4096"})
    public int draftLength;

    private EditorState state;
    private EditorOptions options;

    @Setup
    public void setup() {
        state = EditorState.of(BenchCorpus.build(corpus, draftLength));
        options = EditorOptions.defaults();
    }

    @Benchmark
    public void renderFocused(Blackhole sink) {
        sink.consume(Editor.render(state, options, true));
    }

    @Benchmark
    public void renderUnfocused(Blackhole sink) {
        sink.consume(Editor.render(state, options, false));
    }

    @Benchmark
    public void buildView(Blackhole sink) {
        sink.consume(Editor.<Object>view(ElementId.of("composer"), state, options, true,
                action -> action));
    }
}
