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

import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;

/**
 * Entry point for running one MVU application.
 *
 * <p>The runtime owns the session, the mailbox, effects, subscriptions, layout, and output. The
 * program supplies only immutable values and pure functions. The returned result distinguishes a
 * requested quit, clean end-of-input, and failure, and carries any cleanup failures.</p>
 */
public final class Application {

    private Application() {}

    /**
     * Runs with the JLine FFM system terminal and default options.
     *
     * @return how the run ended; never null
     */
    public static <M, Msg> ExitResult run(Program<M, Msg> program) {
        return run(program, RuntimeOptions.defaults());
    }

    /**
     * Runs with the JLine FFM system terminal.
     *
     * @return how the run ended; never null
     */
    public static <M, Msg> ExitResult run(Program<M, Msg> program, RuntimeOptions options) {
        if (program == null) throw new IllegalArgumentException("program is required");
        return new RuntimeLoop<>(program, options).run();
    }
}
