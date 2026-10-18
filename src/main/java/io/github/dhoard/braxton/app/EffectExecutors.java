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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared executor for application effect tasks.
 *
 * <p>Every task runs on its own virtual thread: a task is application work that may block on I/O,
 * and the project requires virtual threads unless one cannot work. The executor is never shut
 * down; virtual threads are daemons, so idle capacity cannot keep the process alive.</p>
 */
final class EffectExecutors {

    private static final ExecutorService SHARED = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("braxton-effect-", 0).factory());

    private EffectExecutors() {
    }

    static ExecutorService shared() {
        return SHARED;
    }
}
