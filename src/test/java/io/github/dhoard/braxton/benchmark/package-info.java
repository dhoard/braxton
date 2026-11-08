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

/**
 * Offline JMH benchmarks for the paths a Braxton frame actually spends time in: measuring and
 * wrapping text, assembling styled runs, laying out a view tree, painting and encoding a screen,
 * rendering an editor draft, decoding input, and painting one whole frame.
 *
 * <p><b>These are not tests.</b> Surefire excludes them (see the {@code maven-surefire-plugin}
 * excludes in {@code pom.xml}); {@code ./mvnw clean verify} compiles them and never runs them. They
 * run only when someone asks for them:</p>
 *
 * <pre>{@code
 * ./mvnw -o -B -ntp test-compile dependency:build-classpath \
 *     -Dmdep.includeScope=test -Dmdep.outputFile=target/bench-cp.txt
 * java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" \
 *     org.openjdk.jmh.Main -prof gc -rff target/benchmarks/results.csv
 * }</pre>
 *
 * <p>Every benchmark carries its own {@code @Fork}, {@code @Warmup} and {@code @Measurement} so a
 * suite run is comparable across cycles; overriding them on the command line produces a number that
 * must not be compared with a recorded baseline. A run reports the score with its error margin and
 * {@code gc.alloc.rate.norm}, and both are machine-specific: they support a before/after comparison
 * on one machine, never a portable performance claim.</p>
 *
 * <p>Benchmarks here stay TTY-free and offline like the rest of the automated suite: no native
 * terminal backend, no stdin, no network, and no writes outside {@code target/}. A candidate
 * performance fix follows the project's hunt workflow and needs a deterministic in-repo oracle that
 * fails before the change, not a benchmark delta alone.</p>
 */
package io.github.dhoard.braxton.benchmark;
