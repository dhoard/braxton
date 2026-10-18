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

package io.github.dhoard.braxton.app;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Injected runtime services.
 *
 * <p>The system implementation uses real time, a shared timer, and an effect executor that gives
 * each task a virtual thread (e.g. a waiting task parks instead of pinning a platform thread).
 * Tests provide manual timers and a controlled executor; production injection points stay small so
 * applications cannot accidentally reach terminal or thread state from pure code.</p>
 */
public record RuntimeEnvironment(LongSupplier nanoTime, Executor effectExecutor, Timers timers,
        Consumer<String> diagnostics) {

    public RuntimeEnvironment {
        nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        effectExecutor = effectExecutor == null ? Runnable::run : effectExecutor;
        timers = timers == null ? Timers.system() : timers;
        diagnostics = diagnostics == null ? message -> { } : diagnostics;
    }

    /** Real time, real timers, and a shared virtual-thread executor for effect tasks. */
    public static RuntimeEnvironment system() {
        return new RuntimeEnvironment(System::nanoTime, EffectExecutors.shared(),
                Timers.system(), message -> { });
    }

    public RuntimeEnvironment withEffectExecutor(Executor executor) {
        return new RuntimeEnvironment(nanoTime, executor, timers, diagnostics);
    }

    public RuntimeEnvironment withTimers(Timers value) {
        return new RuntimeEnvironment(nanoTime, effectExecutor, value, diagnostics);
    }

    public RuntimeEnvironment withNanoTime(LongSupplier value) {
        return new RuntimeEnvironment(value, effectExecutor, timers, diagnostics);
    }

    public RuntimeEnvironment withDiagnostics(Consumer<String> sink) {
        return new RuntimeEnvironment(nanoTime, effectExecutor, timers, sink);
    }
}
