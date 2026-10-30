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

import io.github.dhoard.braxton.internal.runtime.ScopeManager;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Opt-in hierarchy overhead: isolated child replacement, view composition and retained scopes. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class HierarchyBenchmark {
    @Param({"16", "256"})
    public int count;

    public record Parent(Integer child, Object sibling) {}

    private final List<Mount<Parent, Integer, Never, Integer, Integer, Never>> mounts =
            new ArrayList<>();
    private final Parent parent = new Parent(1, new Object());
    private final ScopeManager scopes = new ScopeManager();
    private ComponentTree tree;
    private final ViewContext context = new ViewContext(new Viewport(24, 80));
    private static final Component<Integer, Integer, Never> CHILD =
            new Component<>() {
                public Update<Integer, Integer, Never> init(InitContext c) {
                    return Update.next(0);
                }

                public Update<Integer, Integer, Never> update(Integer m, Integer message) {
                    return Update.next(m + message);
                }

                public View<Integer> view(Integer m, ViewContext c) {
                    return Views.text("count: " + m);
                }
            };

    @Setup
    public void setup() {
        mounts.clear();
        for (int i = 0; i < count; i++)
            mounts.add(
                    Mount.of(
                            ComponentId.of("k" + i),
                            CHILD,
                            ModelLens.of(Parent::child, (p, c) -> new Parent(c, p.sibling())),
                            value -> value,
                            OutputRoute.toOutput(value -> value)));
        tree = new ComponentTree(mounts.stream().map(mount -> mount.tree(parent)).toList());
        scopes.commit(scopes.prepare(tree, count + 1, 1));
    }

    @Benchmark
    public void updateOneChild(Blackhole sink) {
        sink.consume(mounts.getFirst().update(parent, 1));
    }

    @Benchmark
    public void composeView(Blackhole sink) {
        sink.consume(
                Views.column(mounts.stream().map(mount -> mount.view(parent, context)).toList()));
    }

    @Benchmark
    public void reconcileScopes(Blackhole sink) {
        sink.consume(scopes.prepare(tree, count + 1, 1));
    }
}
